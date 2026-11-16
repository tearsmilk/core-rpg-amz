package core.amazake.systems.corepython.host;

import core.amazake.systems.api.AmazakeSystem;
import core.amazake.systems.api.SystemContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Хост core-python на сервере: движок и Python-системы ({@link ScriptManager}) + логика команды /python.
 * Создаётся загрузчиком (CorePythonSystem) в асинхронном потоке.
 */
public final class Host implements AmazakeSystem, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String P = "<#7fb6ff>[py]</#7fb6ff> ";

    private final SystemContext context;
    private final long started;
    private final ScriptManager manager;

    public Host(final SystemContext context, final YamlConfiguration config, final long started) {
        this.context = context;
        this.started = started;
        this.manager = new ScriptManager(new BukkitPlatform(context), context.dataFolder(),
            new ScriptManager.Options(config.getBoolean("graalpy.warn-interpreter-only", false)), Host.class.getClassLoader());
    }

    @Override
    public void enable(final SystemContext context) throws Exception {
        final long t0 = System.nanoTime();
        this.manager.engine();
        final long engineMs = (System.nanoTime() - t0) / 1_000_000;
        final long t1 = System.nanoTime();
        final int n = this.manager.loadEnabled();
        final Runtime rt = Runtime.getRuntime();
        context.logger().info("core-python: GraalPy " + this.manager.pythonVersion() + " (" + this.manager.runtimeName()
            + ") готов: движок " + engineMs + " мс, Python-систем загружено " + n + " за " + (System.nanoTime() - t1) / 1_000_000
            + " мс, всего с включения " + (System.nanoTime() - this.started) / 1_000_000 + " мс; куча занята "
            + ((rt.totalMemory() - rt.freeMemory()) >> 20) + " МБ");
    }

    @Override
    public void disable(final SystemContext context) {
        this.manager.close();
    }

    @Override
    public void reload(final SystemContext context) {
        Bukkit.getAsyncScheduler().runNow(context.plugin(), t -> {
            for (final PyScript s : this.manager.loaded()) {
                this.manager.unload(s.name());
            }
            this.manager.loadEnabled();
        });
    }

    private static void send(final CommandSender to, final String mm, final String text) {
        to.sendMessage(MM.deserialize(P + mm, Placeholder.unparsed("t", text)));
    }

    /** Действие с системой — в асинхронном потоке (загрузка занимает секунды). */
    private void async(final CommandSender sender, final String what, final ThrowingRunnable body) {
        Bukkit.getAsyncScheduler().runNow(this.context.plugin(), t -> {
            try {
                body.run();
            } catch (final Exception e) {
                send(sender, "<red><t>", what + ": " + e.getMessage());
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        final String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        final String name = args.length > 1 ? args[1] : null;
        switch (sub) {
            case "list" -> {
                send(sender, "<gray><t>", "Python-системы в " + this.manager.scriptsDir() + ":");
                final List<String> lines = this.manager.describe();
                if (lines.isEmpty()) {
                    send(sender, "<gray><t>", "  (нет папок с manifest.yml)");
                }
                for (final String l : lines) {
                    send(sender, "<white><t>", "  " + l);
                }
            }
            case "load" -> this.withName(sender, name, n -> this.async(sender, "load " + n, () -> {
                final PyScript s = this.manager.load(n);
                send(sender, "<green><t>", "система " + n + " " + s.manifest.version() + " загружена за " + s.loadMillis + " мс");
            }));
            case "unload" -> this.withName(sender, name, n -> this.async(sender, "unload " + n, () -> {
                if (this.manager.unload(n)) {
                    send(sender, "<green><t>", "система " + n + " выгружена");
                } else {
                    send(sender, "<yellow><t>", "система " + n + " не была загружена");
                }
            }));
            case "reload" -> this.withName(sender, name, n -> this.async(sender, "reload " + n, () -> {
                if (n.equals("all")) {
                    for (final PyScript s : this.manager.loaded()) {
                        this.manager.unload(s.name());
                    }
                    send(sender, "<green><t>", "перезагружено систем: " + this.manager.loadEnabled());
                    return;
                }
                final PyScript s = this.manager.reload(n);
                send(sender, "<green><t>", "система " + n + " перезагружена за " + s.loadMillis + " мс");
            }));
            case "info" -> {
                final Runtime rt = Runtime.getRuntime();
                send(sender, "<white><t>", "GraalPy " + this.manager.pythonVersion() + ", рантайм: " + this.manager.runtimeName()
                    + (this.manager.runtimeName().toLowerCase(Locale.ROOT).contains("interpret") ? " (без JIT — см. README)" : "")
                    + "; Java " + Runtime.version() + "; куча " + ((rt.totalMemory() - rt.freeMemory()) >> 20) + "/" + (rt.maxMemory() >> 20) + " МБ"
                    + "; загружено систем: " + this.manager.loaded().size());
            }
            default -> send(sender, "<gray><t>", "/python list | load <имя> | unload <имя> | reload <имя|all> | info");
        }
        return true;
    }

    private void withName(final CommandSender sender, final String name, final java.util.function.Consumer<String> body) {
        if (name == null || !Manifest.NAME.matcher(name).matches() && !name.equals("all")) {
            send(sender, "<yellow><t>", "укажите имя системы (папка в scripts/)");
            return;
        }
        body.accept(name);
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias, final String[] args) {
        final List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (final String s : List.of("list", "load", "unload", "reload", "info")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2) {
            final List<String> names = new ArrayList<>(this.manager.discover().keySet());
            if (args[0].equalsIgnoreCase("reload")) {
                names.add("all");
            }
            for (final String n : names) {
                if (n.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(n);
                }
            }
        }
        return out;
    }
}
