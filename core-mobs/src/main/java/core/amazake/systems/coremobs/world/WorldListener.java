package core.amazake.systems.coremobs.world;

import core.amazake.systems.coremobs.Settings;
import core.amazake.systems.coremobs.mob.MobService;
import java.util.function.Supplier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Миры без ванильных мобов: новых не пускаем, загруженных из чанков убираем. Мобы core-mobs и причины спавна из
 * {@code worlds.allowed-spawn-reasons} (по умолчанию другие плагины и /summon) проходят. События — в потоке
 * региона, где это происходит.
 */
public final class WorldListener implements Listener {

    private final WorldRules rules;
    private final Supplier<Settings> settings;

    public WorldListener(final WorldRules rules, final Supplier<Settings> settings) {
        this.rules = rules;
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Mob) || !this.rules.isDisabled(event.getEntity().getWorld().getName())) {
            return;
        }
        if (MobService.mobId(event.getEntity()) != null
            || this.settings.get().worldAllowedReasons().contains(event.getSpawnReason())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onEntitiesLoad(final EntitiesLoadEvent event) {
        if (!this.settings.get().worldRemoveExisting() || !this.rules.isDisabled(event.getWorld().getName())) {
            return;
        }
        for (final Entity e : event.getEntities()) {
            if (removable(e)) {
                e.remove();
            }
        }
    }

    /**
     * Ванильный моб, которого можно убрать. Не трогаем мобов core-mobs, прирученных, с именем (бирка),
     * на поводке и тех, на ком или в ком сидит игрок.
     */
    public static boolean removable(final Entity e) {
        if (!(e instanceof final Mob mob) || !mob.isValid() || MobService.mobId(mob) != null) {
            return false;
        }
        if (mob instanceof final Tameable tameable && tameable.isTamed()) {
            return false;
        }
        if (mob.customName() != null || mob.isLeashed()) {
            return false;
        }
        if (mob.getVehicle() instanceof Player) {
            return false;
        }
        for (final Entity passenger : mob.getPassengers()) {
            if (passenger instanceof Player) {
                return false;
            }
        }
        return true;
    }
}
