package core.amazake.systems.coreeffects.runtime;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreeffects.Settings;
import core.amazake.systems.coreeffects.Text;
import core.amazake.systems.coreeffects.effect.Active;
import core.amazake.systems.coreeffects.effect.EffectBook;
import core.amazake.systems.coreeffects.effect.EffectType;
import core.amazake.systems.coreeffects.item.Items;
import core.amazake.systems.coreeffects.item.Keys;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Игроки с эффектами: загрузка из PDC при входе/включении, сохранение при выходе/выключении, события (лечение,
 * бинт, обморок, фантомы, смерть). Карта {@code players} — потокобезопасная; каждый {@link PlayerFx} трогается
 * только в потоке своего игрока.
 */
public final class EffectManager implements Listener {

    private final SystemContext context;
    private final Supplier<Settings> settings;
    private final String instance = UUID.randomUUID().toString();
    private final Map<UUID, PlayerFx> players = new ConcurrentHashMap<>();

    public EffectManager(final SystemContext context, final Supplier<Settings> settings) {
        this.context = context;
        this.settings = settings;
    }

    /** Включение: все, кто уже онлайн. Глобальный поток. */
    public void start() {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            this.context.runOnceForPlayer(p, () -> this.attach(p));
        }
    }

    /** Выключение: любой поток. */
    public void shutdown() {
        for (final PlayerFx fx : List.copyOf(this.players.values())) {
            fx.shutdown();
        }
        this.players.clear();
    }

    /** Любой поток: эффекты игрока (null — не в сети или ещё не загружен). */
    public PlayerFx fx(final UUID id) {
        return this.players.get(id);
    }

    /** Любой поток: сколько игроков сейчас с эффектами. */
    public int withEffects() {
        int n = 0;
        for (final PlayerFx fx : this.players.values()) {
            if (!fx.book().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** Наложить (из любого потока: работа уходит в поток игрока). */
    public boolean give(final Player target, final EffectType type, final long ticks) {
        return this.context.runOnceForPlayer(target, () -> {
            final PlayerFx fx = this.attach(target);
            fx.give(type, ticks);
        });
    }

    public boolean remove(final Player target, final EffectType type) {
        return this.context.runOnceForPlayer(target, () -> this.attach(target).remove(type, true));
    }

    public boolean clear(final Player target) {
        return this.context.runOnceForPlayer(target, () -> this.attach(target).clearAll(true));
    }

    // -------------------------------------------------------------------------------------------- игроки

    /** Поток игрока: загрузить (если ещё нет) и запустить задачу. */
    private PlayerFx attach(final Player player) {
        final PlayerFx have = this.players.get(player.getUniqueId());
        if (have != null && have.player() == player) {
            return have;
        }
        final String raw = player.getPersistentDataContainer().get(Keys.STATE, PersistentDataType.STRING);
        final EffectBook book = EffectBook.decode(raw, (part, why) -> this.context.logger().warning(
            "Эффекты игрока " + player.getName() + ": «" + part + "» пропущено (" + why + ")"));
        final PlayerFx fx = new PlayerFx(this.context, this.settings, player, book, this.instance);
        final PlayerFx old = this.players.put(player.getUniqueId(), fx);
        if (old != null) {
            old.quit();
        }
        this.sweep(player);
        fx.start();
        return fx;
    }

    /** Фантомы прошлых запусков рядом с игроком. Поток игрока. */
    private void sweep(final Player player) {
        for (final Entity e : player.getNearbyEntities(64, 64, 64)) {
            if (!Bukkit.isOwnedByCurrentRegion(e)) {
                continue;
            }
            final String mark = e.getPersistentDataContainer().get(Keys.PHANTOM, PersistentDataType.STRING);
            if (mark != null && !mark.equals(this.instance)) {
                e.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        this.attach(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        final PlayerFx fx = this.players.remove(event.getPlayer().getUniqueId());
        if (fx != null) {
            fx.quit();
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(final PlayerDeathEvent event) {
        final PlayerFx fx = this.players.get(event.getPlayer().getUniqueId());
        if (fx == null) {
            return;
        }
        final Settings s = this.settings.get();
        final EffectType cause = fx.deathCause();
        if (cause != null) {
            String msg = cause == EffectType.WARMTH && !s.warmth().deathMessage().isBlank() ? s.warmth().deathMessage()
                : s.messages().get("death-" + cause.id());
            if (!msg.isBlank()) {
                event.deathMessage(Text.parse(msg, Placeholder.unparsed("player", event.getPlayer().getName())));
            }
        }
        fx.death(s);
    }

    // -------------------------------------------------------------------------------------------- лечение

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(final PlayerItemConsumeEvent event) {
        final PlayerFx fx = this.players.get(event.getPlayer().getUniqueId());
        if (fx == null || fx.book().isEmpty()) {
            return;
        }
        fx.useItem(Items.tokens(event.getItem()));
    }

    /** Взрывное зелье лечения: поток региона зелья; игрока трогаем его потоком. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSplash(final PotionSplashEvent event) {
        if (!Items.isHealingPotion(event.getPotion().getItem())) {
            return;
        }
        for (final LivingEntity e : event.getAffectedEntities()) {
            if (e instanceof Player p && event.getIntensity(e) > 0 && this.players.containsKey(p.getUniqueId())) {
                this.context.runOnceForPlayer(p, () -> {
                    final PlayerFx fx = this.players.get(p.getUniqueId());
                    if (fx != null) {
                        fx.useItem(Set.of("healing-potion", "splash-potion"));
                    }
                });
            }
        }
    }

    /** Бинт по воздуху/блоку — себе. Без сознания — ничего нельзя. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(final PlayerInteractEvent event) {
        final Player p = event.getPlayer();
        final PlayerFx fx = this.players.get(p.getUniqueId());
        if (fx != null && fx.unconscious()) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final ItemStack item = event.getItem();
        if (!Items.isBandage(item)) {
            return;
        }
        event.setCancelled(true);
        final Settings s = this.settings.get();
        if (fx == null || !fx.curableBy("bandage")) {
            Text.send(p, s, "bandage-useless");
            return;
        }
        if (!fx.bandageReady(s.bandage().cooldownTicks())) {
            return;
        }
        fx.markBandage();
        fx.useItem(Set.of("bandage"));
        this.consume(p, event.getHand());
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, SoundCategory.PLAYERS, 1f, 1.2f);
    }

    /** Бинт по другому игроку — перевязать его (его поток). */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        final Player p = event.getPlayer();
        final PlayerFx self = this.players.get(p.getUniqueId());
        if (self != null && self.unconscious()) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getRightClicked() instanceof Player target)) {
            return;
        }
        final ItemStack item = p.getInventory().getItem(event.getHand());
        if (!Items.isBandage(item)) {
            return;
        }
        final Settings s = this.settings.get();
        event.setCancelled(true);
        if (!s.bandage().others()) {
            return;
        }
        final PlayerFx other = this.players.get(target.getUniqueId());
        if (other == null || !other.curableBy("bandage")) {
            Text.send(p, s, "bandage-useless");
            return;
        }
        if (self != null && !self.bandageReady(s.bandage().cooldownTicks())) {
            return;
        }
        if (self != null) {
            self.markBandage();
        }
        this.consume(p, event.getHand());
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, SoundCategory.PLAYERS, 1f, 1.2f);
        Text.send(p, s, "bandaged-other", Placeholder.unparsed("player", target.getName()));
        final String by = p.getName();
        this.context.runOnceForPlayer(target, () -> {
            final PlayerFx fx = this.players.get(target.getUniqueId());
            if (fx != null && !fx.useItem(Set.of("bandage")).isEmpty()) {
                Text.send(target, s, "bandaged-by", Placeholder.unparsed("player", by));
            }
        });
    }

    private void consume(final Player p, final EquipmentSlot hand) {
        if (p.getGameMode() == GameMode.CREATIVE || hand == null) {
            return;
        }
        final ItemStack it = p.getInventory().getItem(hand);
        if (it.getAmount() <= 1) {
            p.getInventory().setItem(hand, null);
        } else {
            it.setAmount(it.getAmount() - 1);
            p.getInventory().setItem(hand, it);
        }
    }

    // ---------------------------------------------------------------------------------- обморок, фантомы

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        final PlayerFx fx = this.players.get(event.getPlayer().getUniqueId());
        if (fx != null && fx.unconscious()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        final PlayerFx fx = this.players.get(event.getPlayer().getUniqueId());
        if (fx != null && fx.unconscious()) {
            event.setCancelled(true);
        }
    }

    /**
     * Поток региона жертвы. Без сознания — не бьёт. Удар по фантому: урона нет, фантом растворяется (в потоке его
     * хозяина). Свой {@code PlayerFx} здесь только читается (потокобезопасно).
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageBy(final EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (damager instanceof Player p) {
            final PlayerFx fx = this.players.get(p.getUniqueId());
            if (fx != null && fx.unconscious()) {
                event.setCancelled(true);
                return;
            }
            if (isPhantom(event.getEntity()) && fx != null) {
                event.setCancelled(true);
                final Entity ghost = event.getEntity();
                this.context.runOnceForPlayer(p, () -> fx.hitPhantom(ghost));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhantomDamage(final EntityDamageEvent event) {
        if (isPhantom(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhantomCombust(final EntityCombustEvent event) {
        if (isPhantom(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhantomTarget(final EntityTargetEvent event) {
        if (isPhantom(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    private static boolean isPhantom(final Entity e) {
        return e.getPersistentDataContainer().has(Keys.PHANTOM, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------------------------- PlaceholderAPI

    /**
     * {@code %<id>_<effect>%} — yes/no; {@code _<effect>_seconds} — секунды (−1 бесконечно, 0 нет);
     * {@code _<effect>_time} — «m:ss»; {@code _count}; {@code _unconscious} — yes/no; {@code _warmth_thirst} — % пути к
     * смерти от жара. Любой поток.
     */
    public String placeholder(final UUID id, final String params) {
        final PlayerFx fx = id == null ? null : this.players.get(id);
        final EffectBook book = fx == null ? new EffectBook() : fx.book();
        final String p = params.toLowerCase(java.util.Locale.ROOT);
        if (p.equals("count")) {
            return Integer.toString(book.snapshot().size());
        }
        if (p.equals("unconscious")) {
            return fx != null && fx.unconscious() ? "yes" : "no";
        }
        if (p.equals("warmth_thirst")) {
            final Active a = book.get(EffectType.WARMTH);
            final int deadline = this.settings.get().warmth().deadlineTicks();
            return a == null || deadline <= 0 ? "0" : Long.toString(Math.min(100, a.a() * 100 / deadline));
        }
        for (final EffectType t : EffectType.values()) {
            if (p.equals(t.id())) {
                return book.has(t) ? "yes" : "no";
            }
            if (p.startsWith(t.id() + "_")) {
                final Active a = book.get(t);
                final String rest = p.substring(t.id().length() + 1);
                return switch (rest) {
                    case "seconds" -> a == null ? "0" : Long.toString(core.amazake.systems.coreeffects.logic.Durations.seconds(a.remaining()));
                    case "time" -> a == null ? "" : core.amazake.systems.coreeffects.logic.Durations.format(a.remaining());
                    case "name" -> this.settings.get().of(t).name();
                    default -> null;
                };
            }
        }
        return null;
    }
}
