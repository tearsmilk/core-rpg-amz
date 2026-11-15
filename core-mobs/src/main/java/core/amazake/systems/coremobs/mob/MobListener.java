package core.amazake.systems.coremobs.mob;

import core.amazake.systems.coremobs.drop.DropStore;
import core.amazake.systems.coremobs.util.Text;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;

/** События мобов (поток региона, где они происходят) и яйца призыва (поток игрока). */
public final class MobListener implements Listener {

    private final Plugin plugin;
    private final MobService mobs;
    private final Supplier<MobCatalog> catalog;
    private final DropStore drops;

    public MobListener(final Plugin plugin, final MobService mobs, final Supplier<MobCatalog> catalog, final DropStore drops) {
        this.plugin = plugin;
        this.mobs = mobs;
        this.catalog = catalog;
        this.drops = drops;
    }

    // --- смерть и лут ---

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity entity = event.getEntity();
        final String id = MobService.mobId(entity);
        if (id == null) {
            return;
        }
        this.mobs.untrack(entity.getUniqueId());
        final MobDef def = this.catalog.get().get(id);
        if (def == null) {
            return;
        }
        if (!def.vanillaDrops()) {
            final List<ItemStack> equipment = def.dropEquipment() ? equipmentOf(entity) : List.of();
            event.getDrops().removeIf(stack -> equipment.stream().noneMatch(stack::isSimilar));
        }
        event.getDrops().addAll(this.drops.roll(id, ThreadLocalRandom.current()));
        if (def.exp() != null) {
            event.setDroppedExp(def.exp().roll(ThreadLocalRandom.current()));
        }
    }

    private static List<ItemStack> equipmentOf(final LivingEntity entity) {
        final var eq = entity.getEquipment();
        if (eq == null) {
            return List.of();
        }
        return java.util.stream.Stream.of(eq.getItemInMainHand(), eq.getItemInOffHand(), eq.getHelmet(),
            eq.getChestplate(), eq.getLeggings(), eq.getBoots()).filter(i -> i != null && !i.isEmpty()).toList();
    }

    @EventHandler
    public void onRemove(final EntityRemoveFromWorldEvent event) {
        if (MobService.regionOf(event.getEntity()) != null) {
            this.mobs.untrack(event.getEntity().getUniqueId());
        }
    }

    // --- имя со здоровьем ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent event) {
        this.refreshLater(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegain(final EntityRegainHealthEvent event) {
        this.refreshLater(event.getEntity());
    }

    /** Здоровье меняется после события: имя обновим в следующем тике, в потоке, где стоит моб. */
    private void refreshLater(final Entity entity) {
        if (!(entity instanceof final LivingEntity living) || MobService.mobId(entity) == null) {
            return;
        }
        final MobDef def = this.mobs.defOf(entity);
        if (def == null || !def.showName() || !def.showHealth()) {
            return;
        }
        Bukkit.getRegionScheduler().run(this.plugin, entity.getLocation(), task -> {
            if (MobService.ownedAndValid(living)) {
                this.mobs.refreshName(living, def);
            }
        });
    }

    // --- бой ---

    /** Эффекты при ударе: от самого моба или от его стрелы. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof final LivingEntity victim)) {
            return;
        }
        Entity attacker = event.getDamager();
        if (attacker instanceof final Projectile projectile && projectile.getShooter() instanceof final Entity shooter) {
            attacker = shooter;
        }
        final MobDef def = this.mobs.defOf(attacker);
        if (def == null || def.onHitEffects().isEmpty()) {
            return;
        }
        for (final MobDef.Effect effect : def.onHitEffects()) {
            if (effect.chance() >= 100 || ThreadLocalRandom.current().nextDouble() * 100 < effect.chance()) {
                victim.addPotionEffect(new PotionEffect(effect.type(), effect.durationTicks(), effect.amplifier(), false, true));
            }
        }
    }

    /** Урон стрел моба из projectile-damage. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(final EntityShootBowEvent event) {
        final MobDef def = this.mobs.defOf(event.getEntity());
        if (def != null && def.projectileDamage() != null && event.getProjectile() instanceof final AbstractArrow arrow) {
            arrow.setDamage(def.projectileDamage());
        }
    }

    /** Не горит на солнце (prevent-sunburn). Огонь от лавы, поджога и чар остаётся. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombust(final EntityCombustEvent event) {
        if (event instanceof EntityCombustByBlockEvent || event instanceof EntityCombustByEntityEvent) {
            return;
        }
        final MobDef def = this.mobs.defOf(event.getEntity());
        if (def != null && def.preventSunburn()) {
            event.setCancelled(true);
        }
    }

    /** Огненные шары мобов core-mobs (блейз, гаст) не поджигают блоки и не ломают их (no-fire, по умолчанию true). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(final org.bukkit.event.block.BlockIgniteEvent event) {
        Entity source = event.getIgnitingEntity();
        if (source instanceof final Projectile p && p.getShooter() instanceof final Entity shooter) {
            source = shooter;
        }
        if (source != null) {
            final MobDef def = this.mobs.defOf(source);
            if (def != null && def.noFire()) {
                event.setCancelled(true);
            }
        }
        if (Boolean.getBoolean("coremobs.debugIgnite")) {
            Bukkit.getLogger().info("[core-mobs] ignite " + event.getCause() + " by " + event.getIgnitingEntity()
                + " cancelled=" + event.isCancelled());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFireballExplode(final org.bukkit.event.entity.EntityExplodeEvent event) {
        if (event.getEntity() instanceof final Projectile p && p.getShooter() instanceof final Entity shooter) {
            final MobDef def = this.mobs.defOf(shooter);
            if (def != null && def.noFire()) {
                event.blockList().clear();
            }
        }
    }

    /** Зомби не тонет в утопленника, скелет не становится зимогором: иначе пропадут настройки и лут. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTransform(final EntityTransformEvent event) {
        if (MobService.mobId(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    // --- яйцо призыва ---

    @EventHandler(priority = EventPriority.HIGH)
    public void onEgg(final PlayerInteractEvent event) {
        final String id = MobService.eggId(event.getItem());
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() == null || event.getClickedBlock() == null) {
            return;
        }
        final Player player = event.getPlayer();
        final MobDef def = this.catalog.get().get(id);
        if (def == null) {
            player.sendMessage(Text.mm(Text.prefix() + "<red>Моба <white>" + Text.escape(id) + "</white> больше нет в mobs.yml.</red>"));
            return;
        }
        final Block target = event.getClickedBlock().getRelative(event.getBlockFace());
        final Location at = target.getLocation().add(0.5, 0, 0.5);
        at.setYaw(player.getLocation().getYaw() + 180f);
        this.mobs.spawn(def, at, null);
        if (player.getGameMode() != GameMode.CREATIVE) {
            final EquipmentSlot hand = event.getHand();
            final ItemStack stack = player.getInventory().getItem(hand);
            stack.setAmount(stack.getAmount() - 1);
            player.getInventory().setItem(hand, stack.getAmount() <= 0 ? null : stack);
        }
    }

    /** Яйцом по мобу — ванильный детёныш: запрещаем. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEggEntity(final PlayerInteractEntityEvent event) {
        if (MobService.eggId(event.getPlayer().getInventory().getItem(event.getHand())) != null) {
            event.setCancelled(true);
        }
    }

    /** Раздатчик с яйцом заспавнил бы ванильного моба. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(final BlockDispenseEvent event) {
        if (MobService.eggId(event.getItem()) != null) {
            event.setCancelled(true);
        }
    }
}
