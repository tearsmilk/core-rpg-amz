package core.amazake.systems.corerpg.aspect;

import core.amazake.systems.corerpg.CoreRpgSystem;
import core.amazake.systems.corerpg.profile.PlayerProfile;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlotGroup;

/**
 * Считает аспекты игрока из всех вкладчиков и применяет их к атрибутам как временные (transient) модификаторы с
 * ключами {@code core-rpg:aspect_*}: они не сохраняются в файл игрока и снимаются при выключении системы.
 *
 * <p>Пересчёт — только в потоке игрока ({@link #refresh}); из любого другого места — {@link #refreshLater}, который
 * склеивает несколько запросов за тик в один.</p>
 */
public final class AspectService implements Listener {

    /** Аспекты, которые применяются как атрибуты, и их атрибуты. */
    private record Bound(Aspect aspect, Attribute attribute) {
    }

    private static final List<Bound> ATTRIBUTES = List.of(
        new Bound(Aspect.ATTACK_DAMAGE, Attribute.ATTACK_DAMAGE),
        new Bound(Aspect.ARMOR, Attribute.ARMOR),
        new Bound(Aspect.MAX_HEALTH, Attribute.MAX_HEALTH),
        new Bound(Aspect.MOVEMENT_SPEED, Attribute.MOVEMENT_SPEED),
        new Bound(Aspect.ATTACK_SPEED, Attribute.ATTACK_SPEED),
        new Bound(Aspect.KNOCKBACK, Attribute.ATTACK_KNOCKBACK),
        new Bound(Aspect.KNOCKBACK_RESISTANCE, Attribute.KNOCKBACK_RESISTANCE),
        new Bound(Aspect.RANGE, Attribute.ENTITY_INTERACTION_RANGE));

    private final CoreRpgSystem system;
    private final List<AspectContributor> contributors = new CopyOnWriteArrayList<>();
    private final Map<UUID, AspectSnapshot> snapshots = new ConcurrentHashMap<>();
    private final RefreshGate<Player> pending = new RefreshGate<>();
    private volatile boolean contributorFailed;

    public AspectService(final CoreRpgSystem system) {
        this.system = system;
    }

    /** Регистрация вкладчика: в enable модуля. Список очищается при выключении системы вместе с объектом. */
    public void register(final AspectContributor contributor) {
        this.contributors.add(contributor);
        final List<AspectContributor> sorted = new ArrayList<>(this.contributors);
        sorted.sort(Comparator.comparingInt(AspectContributor::order));
        this.contributors.clear();
        this.contributors.addAll(sorted);
    }

    /** Последний посчитанный снимок; {@link AspectSnapshot#EMPTY}, если игрок ещё не посчитан. Любой поток. */
    public AspectSnapshot get(final UUID uuid) {
        final AspectSnapshot s = this.snapshots.get(uuid);
        return s == null ? AspectSnapshot.EMPTY : s;
    }

    /** Посчитать без применения: для превью в меню. Поток игрока. */
    public AspectSnapshot compute(final Player player) {
        final PlayerProfile profile = this.system.profiles().get(player.getUniqueId());
        final AspectSnapshot.Builder builder = AspectSnapshot.builder();
        for (final AspectContributor contributor : this.contributors) {
            try {
                contributor.contribute(player, profile, builder);
            } catch (final RuntimeException e) {
                if (!this.contributorFailed) {
                    this.contributorFailed = true;
                    this.system.context().logger().log(Level.WARNING,
                        "Вкладчик аспектов " + contributor.getClass().getName() + " упал (дальше молчу)", e);
                }
            }
        }
        return builder.build();
    }

    /** Пересчитать и применить атрибуты. Только поток игрока. */
    public AspectSnapshot refresh(final Player player) {
        if (!player.isOnline()) {
            return this.get(player.getUniqueId());
        }
        final AspectSnapshot snapshot = this.compute(player);
        this.snapshots.put(player.getUniqueId(), snapshot);
        this.apply(player, snapshot);
        return snapshot;
    }

    /** Пересчитать в потоке игрока чуть позже; повторные вызовы до пересчёта склеиваются. Любой поток. */
    public void refreshLater(final Player player) {
        if (player == null) {
            return;
        }
        final UUID uuid = player.getUniqueId();
        if (!this.pending.claim(uuid, player)) {
            return;
        }
        final boolean queued = this.system.context().runOnceForPlayer(player, () -> {
            this.pending.release(uuid, player);
            this.refresh(player);
        });
        if (!queued) {
            this.pending.release(uuid, player);
        }
    }

    /** Снять модификаторы системы (выключение). Поток игрока. */
    public void clear(final Player player) {
        for (final Bound bound : ATTRIBUTES) {
            final AttributeInstance instance = player.getAttribute(bound.attribute());
            if (instance != null) {
                instance.removeModifier(this.system.keys().aspectModifier(bound.aspect().key()));
            }
        }
        this.clampHealth(player);
        this.snapshots.remove(player.getUniqueId());
    }

    public void forget(final UUID uuid) {
        this.snapshots.remove(uuid);
        this.pending.forget(uuid);
    }

    private void apply(final Player player, final AspectSnapshot snapshot) {
        for (final Bound bound : ATTRIBUTES) {
            final AttributeInstance instance = player.getAttribute(bound.attribute());
            if (instance == null) {
                continue;
            }
            final NamespacedKey key = this.system.keys().aspectModifier(bound.aspect().key());
            final double amount = snapshot.get(bound.aspect());
            final AttributeModifier old = instance.getModifier(key);
            if (old != null && old.getAmount() == amount) {
                continue;
            }
            instance.removeModifier(key);
            if (amount != 0.0) {
                instance.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
            }
        }
        this.clampHealth(player);
    }

    private void clampHealth(final Player player) {
        final AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        if (max != null && player.getHealth() > max.getValue()) {
            player.setHealth(max.getValue());
        }
    }

    // --- смена экипировки: пересчёт (поток игрока или региона сущности) ---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEquipment(final EntityEquipmentChangedEvent event) {
        if (event.getEntity() instanceof final Player player) {
            this.refreshLater(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(final PlayerItemHeldEvent event) {
        this.refreshLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(final PlayerSwapHandItemsEvent event) {
        this.refreshLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(final InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof final Player player) {
            this.refreshLater(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(final InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof final Player player) {
            this.refreshLater(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(final InventoryCloseEvent event) {
        if (event.getPlayer() instanceof final Player player) {
            this.refreshLater(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(final PlayerDropItemEvent event) {
        this.refreshLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(final EntityPickupItemEvent event) {
        if (event.getEntity() instanceof final Player player) {
            this.refreshLater(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(final PlayerItemBreakEvent event) {
        this.refreshLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(final PlayerRespawnEvent event) {
        this.refreshLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.forget(event.getPlayer().getUniqueId());
    }
}
