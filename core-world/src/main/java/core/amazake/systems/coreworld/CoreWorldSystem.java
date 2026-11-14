package core.amazake.systems.coreworld;

import core.amazake.systems.api.AmazakeSystem;
import core.amazake.systems.api.SystemContext;
import core.amazake.systems.coreworld.hunger.HungerModule;
import core.amazake.systems.coreworld.module.WorldModule;
import core.amazake.systems.coreworld.spawn.SpawnModule;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * core-world — правила мира, собранные из модулей ({@link WorldModule}). Каждый модуль включается и настраивается
 * своим разделом config.yml. Модули: {@code hunger} (голода нет), {@code spawn} (запрет мобов, по умолчанию летучих мышей).
 */
public final class CoreWorldSystem implements AmazakeSystem {

    private volatile SystemContext context;
    private volatile List<WorldModule> modules = List.of();

    /** Все модули системы. Новый модуль — добавить сюда. */
    private static List<WorldModule> createModules() {
        return List.of(new HungerModule(), new SpawnModule());
    }

    @Override
    public void enable(final SystemContext context) {
        this.context = context;
        this.modules = createModules();
        final YamlConfiguration config = context.loadConfig();
        for (final WorldModule module : this.modules) {
            module.enable(context);
        }
        this.configure(config);
        context.registerCommand(new CoreWorldCommand(context, () -> this.modules, this::reloadConfig));
        context.logger().info("core-world включён: " + this.summary() + ".");
    }

    @Override
    public void disable(final SystemContext context) {
        for (final WorldModule module : this.modules) {
            try {
                module.disable();
            } catch (final RuntimeException e) {
                context.logger().log(Level.WARNING, "Модуль " + module.id() + " не выключился чисто", e);
            }
        }
    }

    /** {@code amazake system reload core-world}: перечитать config.yml и применить без перезапуска. */
    @Override
    public void reload(final SystemContext context) {
        this.reloadConfig();
    }

    /**
     * Перечитать config.yml и применить ко всем модулям. Глобальный поток. Если файл не читается (ошибка YAML),
     * остаются прежние настройки. Возвращает текст ошибки или null.
     */
    String reloadConfig() {
        final YamlConfiguration config;
        try {
            config = this.context.loadConfig();
        } catch (final IllegalStateException e) {
            this.context.logger().log(Level.WARNING, "config.yml не прочитан, настройки оставлены прежними", e);
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
        this.configure(config);
        this.context.logger().info("core-world: настройки перечитаны: " + this.summary() + ".");
        return null;
    }

    private void configure(final YamlConfiguration config) {
        for (final WorldModule module : this.modules) {
            module.configure(config, w -> this.context.logger().warning("config.yml: " + w));
        }
    }

    private String summary() {
        final StringBuilder sb = new StringBuilder();
        for (final WorldModule module : this.modules) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(module.id()).append(module.active() ? " вкл" : " выкл");
        }
        return sb.toString();
    }
}
