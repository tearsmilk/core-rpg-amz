package core.amazake.systems.corerpg.combat;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corerpg.CoreModule;
import core.amazake.systems.corerpg.CoreRpgSystem;
import core.amazake.systems.corerpg.util.Configs;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.potion.PotionEffectType;

/**
 * Модуль D: бой. Усиление атаки игрока (заточка, пассивки, урон по целям, крит, статусы, вампиризм), защита игрока
 * (уворот, заточка брони, сопротивление) и регенерация по аспектам. Настройки — {@code combat.yml}.
 */
public final class CombatModule implements CoreModule {

    /** Настройки и то, что из них разрешено через реестры сервера (эффекты статусов). Неизменяемо. */
    record Loaded(CombatSettings settings, PotionEffectType bleed, PotionEffectType poison) {
    }

    private final CoreRpgSystem system;
    private final AtomicReference<Loaded> loaded = new AtomicReference<>(new Loaded(CombatSettings.DEFAULTS, null, null));
    private volatile RegenTasks regen;
    private volatile NamespacedKey projectileRageKey;

    public CombatModule(final CoreRpgSystem system) {
        this.system = system;
    }

    @Override
    public void enable(final SystemContext context) {
        this.projectileRageKey = this.system.keys().key("projectile_rage");
        this.load(context);
        context.registerListener(new CombatListener(this.system, this));
        final RegenTasks tasks = new RegenTasks(this.system, this);
        this.regen = tasks;
        context.registerListener(tasks);
        // Систему могли включить при игроках онлайн.
        tasks.startAll(this.settings().regen().periodTicks());
    }

    @Override
    public void reload(final SystemContext context) {
        this.load(context);
        final RegenTasks tasks = this.regen;
        if (tasks != null) {
            tasks.reload(this.settings().regen().periodTicks());
        }
    }

    @Override
    public void disable(final SystemContext context) {
        final RegenTasks tasks = this.regen;
        if (tasks != null) {
            tasks.stopAll();
        }
        this.regen = null;
        // Модуль не открывает меню, не вешает модификаторов и не показывает ничего постоянного (только action bar):
        // возвращать игрокам нечего. Слушатели и задачи ядро снимет само.
    }

    private void load(final SystemContext context) {
        final Consumer<String> warn = w -> context.logger().warning("combat.yml: " + w);
        final CombatSettings settings = CombatSettings.from(Configs.load(context, "combat.yml"), warn);
        final PotionEffectType bleed = effect(settings.statuses().bleed(), "statuses.bleed", warn);
        final PotionEffectType poison = effect(settings.statuses().poison(), "statuses.poison", warn);
        this.loaded.set(new Loaded(settings, bleed, poison));
    }

    private static PotionEffectType effect(final CombatSettings.Status status, final String path, final Consumer<String> warn) {
        if (!status.enabled() || status.effect().isEmpty()) {
            return null;
        }
        final NamespacedKey key = NamespacedKey.fromString(status.effect());
        final PotionEffectType type = key == null ? null : Registry.MOB_EFFECT.get(key);
        if (type == null) {
            warn.accept(path + ".effect: неизвестный эффект «" + status.effect() + "», статус отключён");
        }
        return type;
    }

    // --- для слушателей модуля (любой поток) ---

    Loaded loaded() {
        return this.loaded.get();
    }

    /**
     * Описание пассивки для меню других модулей (MiniMessage), по текущим числам combat.yml; null — неизвестная
     * пассивка. Любой поток.
     */
    public String passiveDescription(final String key) {
        final Loaded current = this.loaded.get();
        return current == null ? null : current.settings().passiveDescription(key);
    }

    CombatSettings settings() {
        return this.loaded.get().settings();
    }

    NamespacedKey projectileRageKey() {
        return this.projectileRageKey;
    }
}
