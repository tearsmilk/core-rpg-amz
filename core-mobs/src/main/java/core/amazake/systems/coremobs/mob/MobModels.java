package core.amazake.systems.coremobs.mob;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import core.amazake.systems.coremobs.hook.ModelHook;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.plugin.Plugin;

/**
 * Модели ModelEngine на мобах core-mobs ({@code model} в mobs.yml).
 *
 * <ul>
 *   <li>Спавн — модель вешается сразу ({@link MobService#spawn}), в потоке региона моба.</li>
 *   <li>Моб снова появился в мире (загрузка чанка, перезапуск сервера, перенос между мирами) — через 2 тика
 *       проверяем: модель уже висит — ничего, нет — вешаем. Два раза модель не вешается (сначала смотрим
 *       ModeledEntity моба). Модели помечены «не сохранять», восстанавливает их только core-mobs.</li>
 *   <li>ModelEngine ещё грузит модели (первые секунды после старта) — моб пока без модели; когда модели
 *       загрузятся, моб получит модель сам (повтор раз в 2 с, до 2 минут).</li>
 *   <li>Моб загрузился раньше, чем включилась система (forceload, чанки при старте сервера), — его модель проверяет
 *       {@link #ensureLoaded()}: при включении core-mobs, {@code /mobs reload} и включении ModelEngine, когда модели
 *       загружены, система проходит все загруженные чанки.</li>
 *   <li>idle, walk, death проигрывает сам ModelEngine; attack — при выстреле из лука и ударе моба в ближнем бою,
 *       hit — когда моба ударила сущность и он выжил (огонь, яд, падение — без hit).</li>
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

    /**
     * То же из любого потока: сразу, если сущность в текущем регионе, иначе — задачей планировщика самой сущности
     * (он следует за ней между регионами).
     */
    public void ensureAnywhere(final Entity entity) {
        if (MobService.mobId(entity) == null) {
            return;
        }
        if (MobService.ownedAndValid(entity)) {
            this.ensure(entity, this.mobs.defOf(entity));
            return;
        }
        entity.getScheduler().run(this.plugin, task -> this.ensureOwned(entity), null);
    }

    /**
     * Все загруженные мобы core-mobs с моделью: проверить модель каждого. Нужно для мобов, которые попали в мир раньше,
     * чем включилась система (чанки, загруженные при старте сервера: forceload, чанки, которые держат другие плагины), —
     * их EntityAddToWorldEvent система не видела. Список чанков — из глобального потока, сами сущности — в потоке
     * региона чанка. Повесить модель второй раз нельзя: {@link #ensure} сначала смотрит ModeledEntity моба.
     */
    public void ensureLoaded() {
        for (final World world : Bukkit.getWorlds()) {
            final Chunk[] chunks;
            try {
                chunks = world.getLoadedChunks();
            } catch (final RuntimeException e) {
                continue;
            }
            for (final Chunk chunk : chunks) {
                final int cx = chunk.getX();
                final int cz = chunk.getZ();
                Bukkit.getRegionScheduler().execute(this.plugin, world, cx, cz, () -> {
                    if (!world.isChunkLoaded(cx, cz)) {
                        return;
                    }
                    for (final Entity e : world.getChunkAt(cx, cz).getEntities()) {
                        if (MobService.mobId(e) != null) {
                            this.ensureOwned(e);
                        }
                    }
                });
            }
        }
    }

    private void ensureOwned(final Entity entity) {
        this.ensureOwned(entity, 0);
    }

    /** Сколько раз (по 2 с) ждать, пока ModelEngine загрузит blueprint, — 2 минуты, как CoreMobsSystem#checkModels. */
    static final int PENDING_RETRIES = 60;

    private void ensureOwned(final Entity entity, final int attempt) {
        if (!MobService.ownedAndValid(entity)) {
            return;
        }
        final MobDef def = this.mobs.defOf(entity);
        if (def == null || def.model() == null) {
            return;
        }
        if (this.hook.pending(def.model().id())) {
            // ModelEngine ещё грузит модели: повторить позже (планировщик сущности; моб выгрузился — задача снимется)
            if (attempt < PENDING_RETRIES) {
                entity.getScheduler().runDelayed(this.plugin, task -> this.ensureOwned(entity, attempt + 1), null, 40L);
            }
            return;
        }
        this.ensure(entity, def);
    }

    /**
     * Моб с моделью добавлен в мир (загрузка чанка, перезапуск сервера). Вешаем через 2 тика: слушатели того же
     * события (в том числе ModelEngine) к этому времени отработали. Модели core-mobs помечены «не сохранять»
     * ({@code setSaved(false)}), так что восстанавливает их только core-mobs. Свежий спавн тоже сюда попадает —
     * тогда модель уже висит и проверка ничего не делает.
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
        entity.getScheduler().runDelayed(this.plugin, task -> this.ensureOwned(entity), null, 2L);
    }

    /** Скелет выстрелил — attack. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(final EntityShootBowEvent event) {
        this.play(event.getEntity(), true);
    }

    /** Моб ударил в ближнем бою (не снарядом, не шипами) — attack. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMelee(final EntityDamageByEntityEvent event) {
        if (isMelee(event.getCause())) {
            this.play(event.getDamager(), true);
        }
    }

    /**
     * Моба ударили (сущность: игрок, моб, стрела, взрыв от сущности) и он выжил — hit. Огонь, яд, падение и прочий
     * урон без сущности hit не играют: иначе горящий скелет дёргался бы каждую секунду.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(final EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof final LivingEntity living && event.getCause() != DamageCause.THORNS
            && living.getHealth() - event.getFinalDamage() > 0) {
            this.play(living, false);
        }
    }

    static boolean isMelee(final DamageCause cause) {
        return cause == DamageCause.ENTITY_ATTACK || cause == DamageCause.ENTITY_SWEEP_ATTACK;
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
