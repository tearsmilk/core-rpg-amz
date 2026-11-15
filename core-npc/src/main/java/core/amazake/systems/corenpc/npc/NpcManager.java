package core.amazake.systems.corenpc.npc;

import com.destroystokyo.paper.profile.ProfileProperty;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corenpc.Settings;
import core.amazake.systems.corenpc.Text;
import core.amazake.systems.corenpc.hook.ModelHook;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Живые NPC: описание (npcs.yml) → сущность в мире. Сущности не сохраняются в чанк ({@code setPersistent(false)}):
 * система ставит их сама при включении и при загрузке чанка и убирает при выключении. Всё, что трогает сущность, —
 * в потоке её региона ({@code getRegionScheduler().execute} по чанку NPC); общие карты — ConcurrentHashMap.
 */
public final class NpcManager implements Listener {

    /** Что делать по клику на NPC (поток игрока). */
    public interface ClickHandler {
        void click(Player player, NpcDef def, Entity entity, boolean left);
    }

    /** Живая сущность NPC. */
    public record Live(String id, UUID entity, UUID holo, String world, int cx, int cz) {
    }

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final ModelHook models;
    private final Path file;
    private final NamespacedKey npcKey;
    private final NamespacedKey runKey;
    private final String run = UUID.randomUUID().toString();
    private final Map<String, NpcDef> defs = new ConcurrentHashMap<>();
    private final Map<String, Live> live = new ConcurrentHashMap<>();
    private final Map<UUID, String> byEntity = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<String, Boolean> warned = new ConcurrentHashMap<>();
    private final Map<String, Long> respawned = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object saveLock = new Object();
    private volatile ClickHandler clicks;

    public NpcManager(final SystemContext context, final Supplier<Settings> settings, final ModelHook models) {
        this.context = context;
        this.settings = settings;
        this.models = models;
        this.file = context.dataFolder().resolve("npcs.yml");
        this.npcKey = new NamespacedKey("corenpc", "npc");
        this.runKey = new NamespacedKey("corenpc", "run");
    }

    public void onClick(final ClickHandler handler) {
        this.clicks = handler;
    }

    // ------------------------------------------------------------------------------------------- данные

    /** Прочитать npcs.yml (при включении). */
    public int load() throws IOException {
        final Map<String, NpcDef> read = this.read();
        this.defs.clear();
        this.defs.putAll(read);
        return read.size();
    }

    /** Прочитать npcs.yml, ничего не меняя (можно вне потоков тика). Битый файл — IOException. */
    public Map<String, NpcDef> read() throws IOException {
        return NpcStore.load(this.file, w -> this.context.logger().warning(w));
    }

    public NpcDef get(final String id) {
        return id == null ? null : this.defs.get(id);
    }

    public List<NpcDef> all() {
        final List<NpcDef> out = new ArrayList<>(this.defs.values());
        out.sort(Comparator.comparing(NpcDef::id));
        return out;
    }

    /** Система выключена (поздние ответы — поиск скина и т. п. — игнорируются). */
    public boolean isClosed() {
        return this.closed.get();
    }

    public boolean isLive(final String id) {
        return this.live.containsKey(id);
    }

    /** Сохранить npcs.yml вне потоков тика. */
    public void saveAsync() {
        if (this.closed.get()) {
            this.saveNow();
            return;
        }
        Bukkit.getAsyncScheduler().runNow(this.context.plugin(), t -> this.saveNow());
    }

    public void saveNow() {
        synchronized (this.saveLock) {
            try {
                NpcStore.save(this.file, this.all());
            } catch (final IOException e) {
                this.context.logger().log(Level.WARNING, "Не удалось сохранить npcs.yml", e);
            }
        }
    }

    public boolean add(final NpcDef def) {
        if (this.defs.putIfAbsent(def.id(), def) != null) {
            return false;
        }
        this.saveAsync();
        this.spawn(def);
        return true;
    }

    public boolean remove(final String id) {
        final NpcDef old = this.defs.remove(id);
        if (old == null) {
            return false;
        }
        this.saveAsync();
        this.despawn(id);
        return true;
    }

    /** Изменить NPC и поставить его заново. null — нет такого. */
    public NpcDef update(final String id, final UnaryOperator<NpcDef> change) {
        if (this.closed.get()) {
            return null;
        }
        final NpcDef[] result = new NpcDef[1];
        this.defs.computeIfPresent(id, (k, d) -> result[0] = change.apply(d));
        if (result[0] == null) {
            return null;
        }
        this.saveAsync();
        this.despawn(id);
        this.spawn(result[0]);
        return result[0];
    }

    // ------------------------------------------------------------------------------------------- мир

    private World world(final NpcDef d) {
        final World w = Bukkit.getWorld(d.world());
        if (w == null && this.warned.putIfAbsent("world:" + d.id(), Boolean.TRUE) == null) {
            this.context.logger().warning("NPC " + d.id() + ": нет мира «" + d.world() + "» — не ставлю");
        }
        return w;
    }

    public static EntityType parseType(final String raw) {
        if (raw == null) {
            return null;
        }
        final NamespacedKey k = NamespacedKey.fromString(raw.toLowerCase(Locale.ROOT));
        final EntityType t = k == null ? null : Registry.ENTITY_TYPE.get(k);
        if (t == null || !t.isSpawnable() || !t.isAlive() || t.getEntityClass() == null
            || !LivingEntity.class.isAssignableFrom(t.getEntityClass()) || t == EntityType.PLAYER) {
            return null;
        }
        return t;
    }

    /** Поставить NPC, если его чанк загружен (иначе встанет при загрузке чанка). Любой поток. */
    public void spawnAll() {
        for (final NpcDef d : this.defs.values()) {
            this.spawn(d);
        }
    }

    public void spawn(final NpcDef d) {
        if (this.closed.get()) {
            return;
        }
        final World w = this.world(d);
        if (w == null) {
            return;
        }
        final int cx = (int) Math.floor(d.x()) >> 4;
        final int cz = (int) Math.floor(d.z()) >> 4;
        Bukkit.getRegionScheduler().execute(this.context.plugin(), w, cx, cz, () -> this.spawnHere(d.id()));
    }

    /** Поток региона чанка NPC. */
    private void spawnHere(final String id) {
        final NpcDef d = this.defs.get(id);
        if (d == null || this.closed.get()) {
            return;
        }
        final World w = Bukkit.getWorld(d.world());
        final int cx = (int) Math.floor(d.x()) >> 4;
        final int cz = (int) Math.floor(d.z()) >> 4;
        if (w == null || !w.isChunkLoaded(cx, cz)) {
            return;
        }
        final Live cur = this.live.get(id);
        if (cur != null) {
            final Entity e = w.getEntity(cur.entity());
            final Entity h = cur.holo() == null ? null : w.getEntity(cur.holo());
            if (e != null && e.isValid() && (cur.holo() == null || h != null && h.isValid())) {
                return;
            }
            // тело или голограмму убрал кто-то другой — убрать остаток и поставить заново
            this.forget(cur);
            if (e != null) {
                this.models.detach(e);
                e.remove();
            }
            if (h != null) {
                h.remove();
            }
        }
        // хвосты прошлых запусков в этом чанке
        for (final Entity e : w.getChunkAt(cx, cz).getEntities()) {
            final String tag = e.getPersistentDataContainer().get(this.npcKey, PersistentDataType.STRING);
            if (tag != null && !this.byEntity.containsKey(e.getUniqueId())) {
                e.remove();
            }
        }
        final EntityType type = parseType(d.type());
        if (type == null) {
            if (this.warned.putIfAbsent("type:" + d.id() + d.type(), Boolean.TRUE) == null) {
                this.context.logger().warning("NPC " + d.id() + ": тип «" + d.type() + "» не живой моб — не ставлю");
            }
            return;
        }
        final Location at = new Location(w, d.x(), d.y(), d.z(), d.yaw(), d.pitch());
        final Settings s = this.settings.get();
        final Entity e;
        try {
            e = w.spawnEntity(at, type, CreatureSpawnEvent.SpawnReason.CUSTOM, raw -> this.configure(raw, d));
        } catch (final RuntimeException ex) {
            this.context.logger().log(Level.WARNING, "NPC " + d.id() + ": не удалось поставить " + type, ex);
            return;
        }
        if (!e.isValid()) {
            if (this.warned.putIfAbsent("cancel:" + d.id(), Boolean.TRUE) == null) {
                this.context.logger().warning("NPC " + d.id() + ": появление отменил другой плагин (" + type + ")");
            }
            return;
        }
        if (e instanceof LivingEntity le) {
            le.setBodyYaw(d.yaw());
        }
        UUID holo = null;
        if (d.hologram().equals("display")) {
            final double h = e.getHeight() + s.npc().hologramOffset();
            final TextDisplay td = w.spawn(at.clone().add(0, h, 0), TextDisplay.class, t -> {
                this.tag(t, d.id());
                t.setPersistent(false);
                t.setBillboard(Display.Billboard.CENTER);
                final List<String> rows = new ArrayList<>();
                if (!d.name().isBlank()) {
                    rows.add(d.name());
                }
                rows.addAll(d.lines());
                t.text(Text.parse(String.join("<newline>", rows)));
            });
            holo = td.getUniqueId();
            this.byEntity.put(holo, d.id());
        }
        final Live l = new Live(d.id(), e.getUniqueId(), holo, w.getName(), cx, cz);
        this.byEntity.put(e.getUniqueId(), d.id());
        final Live old = this.live.put(d.id(), l);
        if (old != null) {
            this.forget(old);
        }
        if (d.model() != null && s.modelEngine() && this.models.available()) {
            this.models.attach(e, d.model().id(), d.model().scale(), d.model().idle());
        }
    }

    private void tag(final Entity e, final String id) {
        final PersistentDataContainer pdc = e.getPersistentDataContainer();
        pdc.set(this.npcKey, PersistentDataType.STRING, id);
        pdc.set(this.runKey, PersistentDataType.STRING, this.run);
    }

    /** До появления в мире (consumer spawn). */
    private void configure(final Entity e, final NpcDef d) {
        this.tag(e, d.id());
        e.setPersistent(false);
        e.setInvulnerable(true);
        e.setSilent(true);
        e.setGravity(d.gravity());
        final boolean nameTag = d.hologram().equals("name") && !d.name().isBlank();
        e.customName(nameTag ? Text.parse(d.name()) : null);
        e.setCustomNameVisible(nameTag);
        if (e instanceof LivingEntity le) {
            le.setAI(false);
            le.setCollidable(d.collidable());
            le.setRemoveWhenFarAway(false);
            le.setCanPickupItems(false);
            if (d.scale() > 0 && d.scale() != 1.0) {
                final AttributeInstance a = le.getAttribute(Attribute.SCALE);
                if (a != null) {
                    a.setBaseValue(Math.max(0.0625, Math.min(16, d.scale())));
                }
            }
        }
        if (e instanceof Mannequin m) {
            m.setImmovable(true);
            m.setDescription(null);
            if (d.skin() != null && !d.skin().value().isBlank()) {
                final ResolvableProfile.Builder b = ResolvableProfile.resolvableProfile();
                final String n = d.skin().name();
                if (n != null && n.matches("[A-Za-z0-9_]{1,16}")) {
                    b.name(n);
                }
                b.addProperty(new ProfileProperty("textures", d.skin().value(),
                    d.skin().signature() == null || d.skin().signature().isBlank() ? null : d.skin().signature()));
                m.setProfile(b.build());
            }
        }
    }

    /** Забыть живую сущность (без удаления). */
    private void forget(final Live l) {
        this.byEntity.remove(l.entity());
        if (l.holo() != null) {
            this.byEntity.remove(l.holo());
        }
        this.live.remove(l.id(), l);
    }

    /** Убрать NPC из мира. Любой поток: свой регион — сразу, иначе задачей региона. */
    public void despawn(final String id) {
        this.despawn(id, true);
    }

    /**
     * queue=false — при выключении: задачи регионов ядро отменяет после disable(), поэтому чужой регион не трогаем
     * (false — сущность осталась). Она несохраняемая: пропадёт при выгрузке чанка, а при следующем включении её
     * уберёт spawnHere/onEntitiesLoad как хвост с меткой corenpc:npc.
     */
    private boolean despawn(final String id, final boolean queue) {
        final Live l = this.live.remove(id);
        if (l == null) {
            return true;
        }
        final World w = Bukkit.getWorld(l.world());
        if (w == null) {
            this.forget(l);
            return true;
        }
        if (Bukkit.isOwnedByCurrentRegion(w, l.cx(), l.cz())) {
            this.removeEntities(w, l);
            return true;
        }
        if (queue) {
            Bukkit.getRegionScheduler().execute(this.context.plugin(), w, l.cx(), l.cz(), () -> this.removeEntities(w, l));
            return true;
        }
        this.forget(l);
        return false;
    }

    private void removeEntities(final World w, final Live l) {
        final Entity e = w.getEntity(l.entity());
        if (e != null) {
            this.models.detach(e);
            e.remove();
        }
        if (l.holo() != null) {
            final Entity h = w.getEntity(l.holo());
            if (h != null) {
                h.remove();
            }
        }
        this.forget(l);
    }

    /**
     * Выключение (глобальный поток): убрать тех, чей регион принадлежит этому потоку (сегодня Amazake тикает мир
     * в одном потоке — это все). Задачам регионов здесь не доверяемся: ядро отменяет их после disable().
     */
    public void shutdown() {
        this.closed.set(true);
        int left = 0;
        for (final String id : new ArrayList<>(this.live.keySet())) {
            if (!this.despawn(id, false)) {
                left++;
            }
        }
        if (left > 0) {
            this.context.logger().info(left + " NPC в регионах других потоков не убраны сразу: они несохраняемые и "
                + "пропадут при выгрузке чанка (при следующем включении их уберёт сама система).");
        }
        this.saveNow();
    }

    /** /npc reload: заменить описания уже прочитанными (см. {@link #read}), убрать всех и поставить заново. */
    public int apply(final Map<String, NpcDef> read) {
        for (final String id : new ArrayList<>(this.live.keySet())) {
            this.despawn(id);
        }
        this.defs.clear();
        this.defs.putAll(read);
        this.spawnAll();
        return read.size();
    }

    /**
     * Раз в несколько секунд (глобальный поток): NPC, чей чанк загружен, а сущности нет или она невалидна (её убрал
     * другой плагин или система, например чистка мобов), ставится заново в своём регионе.
     */
    public void healTick() {
        if (this.closed.get()) {
            return;
        }
        for (final NpcDef d : this.defs.values()) {
            final World w = Bukkit.getWorld(d.world());
            if (w == null) {
                continue;
            }
            final int cx = (int) Math.floor(d.x()) >> 4;
            final int cz = (int) Math.floor(d.z()) >> 4;
            Bukkit.getRegionScheduler().execute(this.context.plugin(), w, cx, cz, () -> this.spawnHere(d.id()));
        }
    }

    // ------------------------------------------------------------------------------------------- «смотреть на игрока»

    public void lookTick() {
        final Settings s = this.settings.get();
        for (final Live l : this.live.values()) {
            final NpcDef d = this.defs.get(l.id());
            if (d == null || !d.lookAt()) {
                continue;
            }
            final double r = d.lookRadius() >= 0 ? d.lookRadius() : s.npc().lookRadius();
            if (r <= 0) {
                continue;
            }
            final World w = Bukkit.getWorld(l.world());
            if (w == null) {
                continue;
            }
            Bukkit.getRegionScheduler().execute(this.context.plugin(), w, l.cx(), l.cz(), () -> this.look(w, l, d, r));
        }
    }

    private void look(final World w, final Live l, final NpcDef d, final double r) {
        final Entity e = w.getEntity(l.entity());
        if (!(e instanceof LivingEntity le) || !e.isValid()) {
            this.spawnHere(l.id());   // убрали извне — поставить заново (сам проверит, что чанк загружен)
            return;
        }
        final Location eye = le.getEyeLocation();
        Player best = null;
        double bestD = r * r;
        for (final Player p : w.getNearbyPlayers(eye, r)) {
            if (p.getGameMode() == GameMode.SPECTATOR || !Bukkit.isOwnedByCurrentRegion(p)) {
                continue;
            }
            final double dist = p.getEyeLocation().distanceSquared(eye);
            if (dist <= bestD) {
                bestD = dist;
                best = p;
            }
        }
        final float yaw;
        final float pitch;
        if (best == null) {
            yaw = d.yaw();
            pitch = d.pitch();
        } else {
            final Location t = best.getEyeLocation();
            final double dx = t.getX() - eye.getX();
            final double dy = t.getY() - eye.getY();
            final double dz = t.getZ() - eye.getZ();
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        }
        final Location cur = e.getLocation();
        if (Math.abs(cur.getYaw() - yaw) < 0.5 && Math.abs(cur.getPitch() - pitch) < 0.5) {
            return;
        }
        e.setRotation(yaw, pitch);
        le.setBodyYaw(yaw);
    }

    // ------------------------------------------------------------------------------------------- события

    public String npcOf(final Entity e) {
        final String id = this.byEntity.get(e.getUniqueId());
        if (id != null) {
            return id;
        }
        return e.getPersistentDataContainer().get(this.npcKey, PersistentDataType.STRING);
    }

    public boolean isNpc(final Entity e) {
        return this.npcOf(e) != null;
    }

    /** Чанк загрузил сущности: хвосты с нашей меткой — убрать, NPC этого чанка — поставить. Поток региона. */
    @EventHandler
    public void onEntitiesLoad(final EntitiesLoadEvent event) {
        for (final Entity e : event.getEntities()) {
            if (e.getPersistentDataContainer().has(this.npcKey, PersistentDataType.STRING)
                && !this.byEntity.containsKey(e.getUniqueId())) {
                e.remove();
            }
        }
        final World w = event.getWorld();
        final long key = NpcDef.chunkKey(event.getChunk().getX(), event.getChunk().getZ());
        for (final NpcDef d : this.defs.values()) {
            if (d.chunkKey() == key && d.world().equals(w.getName())) {   // spawnHere сам пропустит живого
                this.spawn(d);
            }
        }
    }

    /** Чанк выгружается: несохраняемые NPC пропадут вместе с ним — забыть их. */
    @EventHandler
    public void onEntitiesUnload(final EntitiesUnloadEvent event) {
        for (final Entity e : event.getEntities()) {
            final String id = this.byEntity.get(e.getUniqueId());
            if (id != null) {
                final Live l = this.live.get(id);
                if (l != null && (e.getUniqueId().equals(l.entity()) || e.getUniqueId().equals(l.holo()))) {
                    this.models.detach(e);
                    this.forget(l);
                }
            }
        }
    }

    /**
     * Сущность NPC убрал кто-то другой (не выгрузка чанка — её ловит onEntitiesUnload; свои удаления сначала
     * убирают запись из live, поэтому сюда не попадают). Поток региона сущности: забыть и поставить заново в
     * следующем тике, но не чаще раза в 10 с на NPC (иначе плагин, убивающий всё подряд, крутил бы цикл); остальное —
     * healTick.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(final EntityRemoveEvent event) {
        if (event.getCause() == EntityRemoveEvent.Cause.UNLOAD) {
            return;
        }
        final UUID uuid = event.getEntity().getUniqueId();
        final String id = this.byEntity.get(uuid);
        if (id == null) {
            return;
        }
        final Live l = this.live.get(id);
        if (l == null || !(uuid.equals(l.entity()) || uuid.equals(l.holo()))) {
            return;
        }
        if (this.warned.putIfAbsent("removed:" + id + event.getCause(), Boolean.TRUE) == null) {
            this.context.logger().warning("NPC " + id + ": сущность убрана извне (" + event.getCause() + ") — ставлю заново");
        }
        this.forget(l);
        if (this.closed.get()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Long last = this.respawned.get(id);
        if (last != null && now - last < 10_000) {
            return;
        }
        this.respawned.put(id, now);
        final World w = Bukkit.getWorld(l.world());
        if (w != null) {
            Bukkit.getRegionScheduler().execute(this.context.plugin(), w, l.cx(), l.cz(), () -> this.spawnHere(id));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDamage(final EntityDamageEvent event) {
        if (this.isNpc(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCombust(final EntityCombustEvent event) {
        if (this.isNpc(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractAt(final PlayerInteractAtEntityEvent event) {
        if (this.isNpc(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    /** ПКМ по NPC (поток игрока). */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(final PlayerInteractEntityEvent event) {
        if (event instanceof PlayerInteractAtEntityEvent) {
            return;
        }
        final String id = this.npcOf(event.getRightClicked());
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        this.handleClick(event.getPlayer(), id, event.getRightClicked(), false);
    }

    /** ЛКМ по NPC: удар всегда отменяется; если у NPC left-click — как ПКМ. */
    @EventHandler(priority = EventPriority.LOW)
    public void onAttack(final PrePlayerAttackEntityEvent event) {
        final String id = this.npcOf(event.getAttacked());
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        this.handleClick(event.getPlayer(), id, event.getAttacked(), true);
    }

    private void handleClick(final Player p, final String id, final Entity clicked, final boolean left) {
        final NpcDef d = this.defs.get(id);
        final ClickHandler h = this.clicks;
        if (d == null || h == null) {
            return;
        }
        final Live l = this.live.get(id);
        Entity body = clicked;
        if (l != null && clicked.getUniqueId().equals(l.holo())) {
            final Entity e = clicked.getWorld().getEntity(l.entity());
            body = e != null ? e : clicked;
        }
        h.click(p, d, body, left);
    }

    /** Кулдаун клика игрока по NPC: true — можно (и время отмечено). */
    public boolean tryCooldown(final Player p, final NpcDef d) {
        final double sec = d.cooldown() >= 0 ? d.cooldown() : this.settings.get().npc().cooldown();
        if (sec <= 0) {
            return true;
        }
        final String key = d.id() + '|' + p.getUniqueId();
        final long now = System.currentTimeMillis();
        final Long until = this.cooldowns.get(key);
        if (until != null && until > now) {
            return false;
        }
        this.cooldowns.put(key, now + (long) (sec * 1000));
        if (this.cooldowns.size() > 4096) {
            this.cooldowns.values().removeIf(v -> v < now);
        }
        return true;
    }

    /** Анимация модели при клике (поток региона сущности). */
    public void playInteract(final NpcDef d, final Entity e) {
        if (d.model() == null || d.model().interact() == null || d.model().interact().isBlank() || !this.models.available()) {
            return;
        }
        final Runnable r = () -> {
            if (e.isValid()) {
                this.models.animate(e, d.model().interact());
            }
        };
        if (Bukkit.isOwnedByCurrentRegion(e)) {
            r.run();
            return;
        }
        // место — из описания NPC: getLocation() сущности чужого региона читать нельзя
        final World w = Bukkit.getWorld(d.world());
        if (w != null) {
            Bukkit.getRegionScheduler().execute(this.context.plugin(), w, (int) Math.floor(d.x()) >> 4,
                (int) Math.floor(d.z()) >> 4, r);
        }
    }

    /** Глаза NPC (для «повернуться к NPC» и расстояния): x, y, z мира. */
    public Location eyeOf(final NpcDef d) {
        final World w = Bukkit.getWorld(d.world());
        if (w == null) {
            return null;
        }
        return new Location(w, d.x(), d.y() + 1.62 * (d.scale() > 0 ? d.scale() : 1), d.z());
    }

    public Component displayName(final NpcDef d) {
        return Text.parse(d.name().isBlank() ? d.id() : d.name());
    }

    public int liveCount() {
        return this.live.size();
    }
}
