package core.amazake.systems.corepython.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.graalvm.polyglot.Value;

/** Команда Python-системы. Выполняется в потоке отправителя (игрок — его регион, консоль — глобальный поток). */
public final class PyCommand extends Command {

    private final PyScript script;
    private final Value executor;
    private volatile Value completer;

    PyCommand(final PyScript script, final String name, final String description, final String usage, final List<String> aliases,
              final String permission, final Value executor) {
        super(name, description, usage, aliases);
        this.script = script;
        this.executor = executor;
        if (permission != null && !permission.isBlank()) {
            this.setPermission(permission);
        }
    }

    /** Функция автодополнения: fn(sender, args) -&gt; список строк. */
    public void setCompleter(final Value fn) {
        this.completer = fn;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        this.script.call("команде /" + this.getName(), this.executor, sender, label, (Object) args);
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        final Value fn = this.completer;
        if (fn == null || !this.testPermissionSilent(sender)) {
            return List.of();
        }
        final Value r = this.script.call("автодополнении /" + this.getName(), fn, sender, (Object) args);
        final List<String> out = new ArrayList<>();
        if (r == null || !r.hasArrayElements()) {
            return out;
        }
        final String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        for (long i = 0; i < r.getArraySize() && i < 200; i++) {
            final Value v = r.getArrayElement(i);
            final String s = v.isString() ? v.asString() : v.toString();
            if (s.toLowerCase(Locale.ROOT).startsWith(last)) {
                out.add(s);
            }
        }
        return out;
    }
}
