package core.amazake.systems.coremobs.mob;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import core.amazake.systems.coremobs.hook.ModelHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.plugin.Plugin;

/**
 * Модели ModelEngine на мобах core-mobs ({@code model} в mobs.yml).
 *
 * <ul>
 *   <li>Спавн — модель вешается сразу ({@link MobService#spawn}), в потоке региона моба.</li>
 *   <li>Моб снова появился в мире (загрузка чанка, перезапуск сервера, перенос между мирами) — через 2 тика
 *       проверяем: ModelEngine восстановил модель сам — ничего, нет — вешаем. Два раза модель не вешается.</li>
 *   <li>idle, walk, death проигрывает сам ModelEngine; attack — при выстреле из лука и ударе моба, hit — при уроне
 *       (кроме смертельного: там death).</li>
 *   <li>Модель принадлежит ModelEngine: при выключении core-mobs не снимается, при смерти или удалении моба её
 *       убирает сам ModelEngine. Своих карт по сущностям здесь нет — утекать нечему.</li>
 * </ul>
 */
public final class MobModels implements Listener {

    private final Plugin plugin;
    private final ModelHook hook;
    private final MobService mobs;

    public MobModels(final Plugin plugin, final ModelHook hook, final MobService mobs) {
        this.plugin = plugin;
        this.hook = hook;
        this.mobs = mobs;
    }

    /** Повесить модель моба, если она есть в mobs.yml и её ещё нет. Поток региона моба. */
    public void ensure(final Entity entity, final MobDef def) {
        if (def == null || def.model() == null) {
            return;
        }
        final MobDef.Model m = def.model();
        this.hook.ensure(entity, m.id(), m.scale(), m.hideBase());
    }

    /** То же из любого потока: сразу, если сущность в текущем регионе, иначе — задачей в её регион. */
    public void ensureAnywhere(final Entity entity) {
        final MobDef def = this.mobs.defOf(entity);
        if (def == null || def.model() == null) {
            return;
        }
        if (MobService.ownedAndValid(entity)) {
            this.ensure(entity, def);
            return;
        }
        Bukkit.getRegionScheduler().run(this.plugin, entity.getLocation(), task -> {
            if (MobService.ownedAndValid(entity)) {
                this.ensure(entity, this.mobs.defOf(entity));
            }
        });
    }

    /**
     * Моб с моделью добавлен в мир. Вешаем не сразу: ModelEngine восстанавливает свои модели на том же событии
     * загрузки, а порядок слушателей не задан.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(final EntityAddToWorldEvent event) {
        final Entity entity = event.getEntity();
        if (MobService.mobId(entity) == null) {
            return;
        }
        final MobDef def = this.mobs.defOf(entity);
        if (def == null || def.model() == null) {
            return;
        }
        Bukkit.getRegionScheduler().runDelayed(this.plugin, entity.getLocation(), task -> {
            if (MobService.ownedAndValid(entity)) {
                this.ensure(entity, this.mobs.defOf(entity));
            }
        }, 2L);
    }

    /** Скелет выстрелил — attack. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(final EntityShootBowEvent event) {
        this.play(event.getEntity(), true);
    }

    /** Моб ударил в ближнем бою (не снарядом) — attack. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMelee(final EntityDamageByEntityEvent event) {
        final Entity damager = event.getDamager();
        if (!(damager instanceof Projectile)) {
            this.play(damager, true);
        }
    }

    /** Моб получил несмертельный урон — hit. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(final EntityDamageEvent event) {
        if (event.getEntity() instanceof final LivingEntity living && living.getHealth() - event.getFinalDamage() > 0) {
            this.play(living, false);
        }
    }

    private void play(final Entity entity, final boolean attack) {
        if (MobService.mobId(entity) == null) {
            return;
        }
        final MobDef def = this.mobs.defOf(entity);
        if (def == null || def.model() == null) {
            return;
        }
        this.hook.animate(entity, attack ? def.model().attack() : def.model().hit());
    }
}
