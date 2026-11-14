package core.amazake.systems.coreportals.portal;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreportals.PortalItem;
import core.amazake.systems.coreportals.Settings;
import core.amazake.systems.coreportals.TeleportAction;
import core.amazake.systems.coreportals.anim.Phase;
import core.amazake.systems.coreportals.anim.Timeline;
import core.amazake.systems.coreportals.geo.BlockQuery;
import core.amazake.systems.coreportals.geo.Box;
import core.amazake.systems.coreportals.geo.Placement;
import core.amazake.systems.coreportals.geo.Spot;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.persistence.PersistentDataType;

/**
 * Открытые порталы: открытие по ПКМ предметом, тик анимации и входа в потоке региона портала, закрытие,
 * уборка при выгрузке чанка и выключении системы.
 */
public final class PortalManager implements Listener {

    /** Повторный ПКМ в течение этого времени игнорируется молча (событие приходит для обеих рук). */
    private static final long DEBOUNCE_MS = 250;
    private static final double ENTRY_SCAN_RADIUS = 3.5;

    private final SystemContext context;
    private final Supplier<Settings> settings;
    /** Сообщение игроку с префиксом: (кому, шаблон). Плейсхолдеры подставляет вызывающий. */
    private final Messenger messenger;
    private final String instance = UUID.randomUUID().toString();
    private final Map<UUID, Portal> portals = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();
    private final Map<UUID, Long> immuneUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> notOwnerNotified = new ConcurrentHashMap<>();

    /** Отправка сообщений с префиксом и плейсхолдерами. */
    public interface Messenger {
        void send(Player player, String template, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers);
    }

    public PortalManager(final SystemContext context, final Supplier<Settings> settings, final Messenger messenger) {
        this.context = context;
        this.settings = settings;
        this.messenger = messenger;
    }

    // ---------------------------------------------------------------- открытие

    /** ПКМ предметом-порталом. Поток игрока. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final ItemStack item = event.getItem();
        if (!PortalItem.is(item) || event.getHand() == null) {
            return;
        }
        // Смолу нельзя ставить на блок: событие отменяем в любом случае.
        event.setCancelled(true);
        final Player player = event.getPlayer();
        if (event.getHand() == EquipmentSlot.OFF_HAND && PortalItem.is(player.getInventory().getItemInMainHand())) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Long last = this.lastUse.put(player.getUniqueId(), now);
        if (last != null && now - last < DEBOUNCE_MS) {
            return;
        }
        this.tryOpen(player, event.getHand());
    }

    /** Открыть портал перед игроком. Поток игрока. */
    private void tryOpen(final Player player, final EquipmentSlot hand) {
        final Settings s = this.settings.get();
        final long now = System.currentTimeMillis();
        final Long until = this.cooldownUntil.get(player.getUniqueId());
        if (until != null && until > now) {
            final long seconds = Math.max(1, (until - now + 999) / 1000);
            this.messenger.send(player, s.messages().cooldown(), Placeholder.unparsed("seconds", Long.toString(seconds)));
            return;
        }
        if (s.maxPerPlayer() > 0 && this.countOwned(player.getUniqueId()) >= s.maxPerPlayer()) {
            this.messenger.send(player, s.messages().tooMany());
            return;
        }
        final Location loc = player.getLocation();
        final World world = player.getWorld();
        final Optional<Spot> found = Placement.find(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(),
            s.distance(), s.groundSearch(), new WorldBlocks(world));
        if (found.isEmpty()) {
            this.messenger.send(player, s.messages().noRoom());
            return;
        }
        final Spot spot = found.get();
        final int cx = (int) Math.floor(spot.x()) >> 4;
        final int cz = (int) Math.floor(spot.z()) >> 4;
        if (!Bukkit.isOwnedByCurrentRegion(world, cx, cz)) {
            // Место портала принадлежит другому потоку (граница региона): не трогаем.
            this.messenger.send(player, s.messages().noRoom());
            return;
        }
        if (s.consume() && player.getGameMode() != GameMode.CREATIVE) {
            final PlayerInventory inv = player.getInventory();
            final ItemStack inHand = inv.getItem(hand);
            if (!PortalItem.is(inHand)) {
                return;
            }
            final int left = inHand.getAmount() - 1;
            if (left > 0) {
                inHand.setAmount(left);
                inv.setItem(hand, inHand);
            } else {
                inv.setItem(hand, null);
            }
        }
        if (s.cooldownTicks() > 0) {
            this.cooldownUntil.put(player.getUniqueId(), now + s.cooldownTicks() * 50L);
        }
        this.open(player.getUniqueId(), player.getName(), world, spot, s);
    }

    /** Поставить портал. Поток региона, которому принадлежит место портала. */
    private void open(final UUID owner, final String ownerName, final World world, final Spot spot, final Settings s) {
        final Timeline tl = Timeline.of(s.lifetimeTicks());
        final Portal portal = new Portal(owner, ownerName, world, spot, tl);
        portal.visuals.spawn(world, spot, s, tl, this.instance);
        portal.effects.tick(tl, 0, s);
        this.portals.put(portal.id, portal);
        final ScheduledTask task = Bukkit.getRegionScheduler().runAtFixedRate(this.context.plugin(), world,
            portal.chunkX, portal.chunkZ, t -> this.tick(portal, t), 1L, 1L);
        portal.task = task;
        this.context.logger().info("Портал открыт: " + ownerName + " в " + world.getName() + " "
            + fmt(spot.x()) + " " + spot.y() + " " + fmt(spot.z()) + " (" + spot.facing() + ")");
    }

    // ---------------------------------------------------------------- тик

    /** Тик портала. Поток его региона. */
    private void tick(final Portal p, final ScheduledTask task) {
        if (p.removed) {
            task.cancel();
            return;
        }
        final long t = ++p.tick;
        if (p.closeRequested) {
            p.timeline = p.timeline.closeAt(t);
        }
        if (!p.visuals.valid()) {
            this.remove(p);
            return;
        }
        final Timeline tl = p.timeline;
        final Phase phase = tl.phase(t);
        if (phase == Phase.DONE) {
            this.remove(p);
            return;
        }
        if (phase != Phase.IDLE && t % Timeline.KEY_STEP == 0) {
            // Длительность чуть больше шага: следующий кадр приходит, пока клиент ещё тянет этот, — без рывков.
            p.visuals.apply(tl, t + Timeline.KEY_STEP, Timeline.KEY_STEP + 1);
        }
        final Settings s = this.settings.get();
        p.effects.tick(tl, t, s);
        if (phase == Phase.IDLE) {
            this.checkEntry(p, s);
        }
    }

    /** Кто задел поверхность хитбоксом — входит. Поток региона портала. */
    private void checkEntry(final Portal p, final Settings s) {
        final Box box = p.spot.surfaceBox();
        final double[] c = p.spot.toWorld(0, Spot.SURFACE_HEIGHT / 2, 0);
        final Location center = new Location(p.world, c[0], c[1], c[2]);
        final long now = System.currentTimeMillis();
        for (final Player player : p.world.getNearbyPlayers(center, ENTRY_SCAN_RADIUS)) {
            if (!Bukkit.isOwnedByCurrentRegion(player) || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            final org.bukkit.util.BoundingBox bb = player.getBoundingBox();
            if (!box.overlaps(bb.getMinX(), bb.getMinY(), bb.getMinZ(), bb.getMaxX(), bb.getMaxY(), bb.getMaxZ())) {
                continue;
            }
            final UUID id = player.getUniqueId();
            final Long immune = this.immuneUntil.get(id);
            if (immune != null && immune > now) {
                continue;
            }
            if (s.ownerOnly() && !id.equals(p.owner)) {
                final Long told = this.notOwnerNotified.get(id);
                if (told == null || now - told > 3000) {
                    this.notOwnerNotified.put(id, now);
                    this.context.runOnceForPlayer(player, () -> this.messenger.send(player, s.messages().notOwner()));
                }
                continue;
            }
            this.immuneUntil.put(id, now + Math.max(s.immunityTicks(), 20L) * 50L);
            p.effects.enter(player, s);
            this.context.logger().info("Игрок " + player.getName() + " вошёл в портал " + p.ownerName + ".");
            this.context.runOnceForPlayer(player, () -> this.runAction(player, s));
        }
    }

    /** Действие портала: команды от имени игрока (с временными правами) или консоли. Поток игрока. */
    private void runAction(final Player player, final Settings s) {
        final TeleportAction action = s.teleport();
        final List<String> commands = action.resolve(player.getName(), player.getUniqueId().toString());
        if (action.runAs() == TeleportAction.RunAs.CONSOLE) {
            Bukkit.getGlobalRegionScheduler().execute(this.context.plugin(), () -> {
                for (final String c : commands) {
                    this.dispatch(Bukkit.getConsoleSender(), c);
                }
            });
        } else {
            PermissionAttachment attachment = null;
            if (!action.grantPermissions().isEmpty()) {
                attachment = player.addAttachment(this.context.plugin());
                for (final String perm : action.grantPermissions()) {
                    attachment.setPermission(perm, true);
                }
            }
            try {
                for (final String c : commands) {
                    this.dispatch(player, c);
                }
            } finally {
                if (attachment != null) {
                    player.removeAttachment(attachment);
                }
            }
        }
        this.messenger.send(player, s.messages().entered());
    }

    private void dispatch(final org.bukkit.command.CommandSender sender, final String command) {
        try {
            if (!Bukkit.dispatchCommand(sender, command)) {
                this.context.logger().warning("Команда портала не найдена: /" + command);
            }
        } catch (final RuntimeException e) {
            this.context.logger().log(Level.WARNING, "Команда портала /" + command + " упала", e);
        }
    }

    // ---------------------------------------------------------------- закрытие и уборка

    /** Убрать портал сразу. Поток его региона. */
    private void remove(final Portal p) {
        p.removed = true;
        final ScheduledTask task = p.task;
        if (task != null) {
            task.cancel();
        }
        p.visuals.remove();
        this.portals.remove(p.id, p);
    }

    /** Чанк портала выгружается: портал убирается сразу. Поток региона чанка. */
    @EventHandler
    public void onChunkUnload(final ChunkUnloadEvent event) {
        final World world = event.getWorld();
        final int x = event.getChunk().getX();
        final int z = event.getChunk().getZ();
        for (final Portal p : List.copyOf(this.portals.values())) {
            if (p.world.equals(world) && p.chunkX == x && p.chunkZ == z) {
                this.remove(p);
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        this.lastUse.remove(id);
        this.immuneUntil.remove(id);
        this.notOwnerNotified.remove(id);
    }

    /** Закрыть все порталы с анимацией. Любой поток. Возвращает, сколько порталов закрывается. */
    public int closeAll() {
        int n = 0;
        for (final Portal p : this.portals.values()) {
            if (!p.removed && !p.closeRequested) {
                p.closeRequested = true;
                n++;
            }
        }
        return n;
    }

    /**
     * Выключение системы: убрать всё сразу, без анимации. Глобальный поток: сущности убираются здесь, если их
     * регион принадлежит этому потоку (сегодняшний однопоточный тик), иначе — задачей в их регионе. Дисплеи не
     * сохраняются в чанк, так что и без этого они не переживут выгрузку и рестарт.
     */
    public void removeAllNow() {
        for (final Portal p : List.copyOf(this.portals.values())) {
            p.removed = true;
            final ScheduledTask task = p.task;
            if (task != null) {
                task.cancel();
            }
            if (Bukkit.isOwnedByCurrentRegion(p.world, p.chunkX, p.chunkZ)) {
                p.visuals.remove();
            } else {
                Bukkit.getRegionScheduler().execute(this.context.plugin(), p.world, p.chunkX, p.chunkZ, p.visuals::remove);
            }
        }
        this.portals.clear();
    }

    /** Хвосты прошлых запусков рядом с игроком (дисплеи с меткой другого экземпляра). Поток игрока. */
    public void sweepStale(final Player player) {
        this.context.runOnceForPlayer(player, () -> {
            for (final Entity e : player.getNearbyEntities(64, 64, 64)) {
                final String mark = e.getPersistentDataContainer().get(PortalVisuals.PART, PersistentDataType.STRING);
                if (mark != null && !mark.equals(this.instance)) {
                    e.remove();
                }
            }
        });
    }

    /** Список для /portals list: «владелец мир x y z стадия». Любой поток. */
    public List<String> describe() {
        final List<String> out = new ArrayList<>();
        for (final Portal p : this.portals.values()) {
            final long t = p.tick;
            out.add(p.ownerName + " " + p.world.getName() + " " + fmt(p.spot.x()) + " " + p.spot.y() + " "
                + fmt(p.spot.z()) + " " + p.spot.facing() + " " + p.timeline.phase(t) + " (" + t / 20 + " с)");
        }
        return out;
    }

    public int size() {
        return this.portals.size();
    }

    private int countOwned(final UUID owner) {
        int n = 0;
        for (final Portal p : this.portals.values()) {
            if (owner.equals(p.owner) && !p.removed && !p.closeRequested) {
                n++;
            }
        }
        return n;
    }

    private static String fmt(final double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** Блоки мира для {@link Placement}. Поток региона этих блоков. */
    private record WorldBlocks(World world) implements BlockQuery {

        private Block at(final int x, final int y, final int z) {
            if (y < this.world.getMinHeight() || y >= this.world.getMaxHeight()
                || !this.world.isChunkLoaded(x >> 4, z >> 4)) {
                return null;
            }
            return this.world.getBlockAt(x, y, z);
        }

        @Override
        public boolean solid(final int x, final int y, final int z) {
            final Block b = this.at(x, y, z);
            return b != null && b.getType().isSolid() && !b.isPassable();
        }

        @Override
        public boolean passable(final int x, final int y, final int z) {
            final Block b = this.at(x, y, z);
            return b != null && b.isPassable() && b.getType() != Material.LAVA;
        }
    }
}
