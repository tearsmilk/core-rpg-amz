package core.amazake.systems.coreweapons;

import core.amazake.systems.api.AmazakeSystem;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreweapons.runtime.WeaponManager;
import java.util.concurrent.atomic.AtomicReference;

/**
 * core-weapons — эффекты кастомного оружия Nexo: кольцо частиц вокруг клинка, кадры замаха (строка
 * custom_model_data, модель кадра выбирает пак), огненный след, дрожь земли при крите. Общий core-шейдер не трогается.
 */
public final class CoreWeaponsSystem implements AmazakeSystem {

    private final AtomicReference<Settings> settings = new AtomicReference<>(Settings.EMPTY);
    private volatile SystemContext context;
    private volatile WeaponManager manager;

    @Override
    public void enable(final SystemContext context) {
        this.context = context;
        context.saveDefaultResource("config.yml");
        this.loadSettings();
        final WeaponManager m = new WeaponManager(context, this.settings::get);
        this.manager = m;
        context.registerListener(m);
        context.registerCommand(new WeaponsCommand(context, this.settings::get, m, this::reloadNow));
        m.start();
        context.logger().info("core-weapons включён: оружия в config.yml — " + this.settings.get().weapons().size() + ".");
    }

    @Override
    public void disable(final SystemContext context) {
        final WeaponManager m = this.manager;
        if (m != null) {
            m.shutdown();
        }
    }

    @Override
    public void reload(final SystemContext context) {
        this.reloadNow();
    }

    private void reloadNow() {
        this.loadSettings();
        this.context.logger().info("core-weapons: настройки перечитаны, оружий — " + this.settings.get().weapons().size() + ".");
    }

    private void loadSettings() {
        this.settings.set(Settings.from(this.context.loadConfig(), w -> this.context.logger().warning("config.yml: " + w)));
    }
}
