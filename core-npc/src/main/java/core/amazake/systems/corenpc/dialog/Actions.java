package core.amazake.systems.corenpc.dialog;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corenpc.Text;
import core.amazake.systems.corenpc.hook.Papi;
import core.amazake.systems.corenpc.util.Placeholders;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Выполнение действий для игрока (его поток). Консольные команды уходят в глобальный поток. */
public final class Actions {

    /** Чем закончились действия: переход (null — нет) и/или закрытие. */
    public record Flow(String target, boolean close) {
        public static final Flow NONE = new Flow(null, false);
    }

    private final SystemContext context;
    private final Papi papi;

    public Actions(final SystemContext context, final Papi papi) {
        this.context = context;
        this.papi = papi;
    }

    /** Подставить {переменные} и %плейсхолдеры% в команду (без экранирования). */
    public String command(final Player p, final String template, final Map<String, String> vars) {
        return this.papi.apply(p, Placeholders.apply(template, vars));
    }

    /** То же для MiniMessage: значения переменных экранируются. */
    public String mini(final Player p, final String template, final Map<String, String> vars) {
        return this.papi.apply(p, Placeholders.apply(template, vars, MiniMessage.miniMessage()::escapeTags));
    }

    public Flow run(final Player p, final List<Action> actions, final Map<String, String> vars) {
        String target = null;
        boolean close = false;
        for (final Action a : actions) {
            switch (a.type()) {
                case PLAYER -> {
                    final String cmd = this.command(p, a.value(), vars);
                    if (!cmd.isBlank()) {
                        p.performCommand(cmd);
                    }
                }
                case CONSOLE -> {
                    final String cmd = this.command(p, a.value(), vars);
                    if (!cmd.isBlank()) {
                        Bukkit.getGlobalRegionScheduler().execute(this.context.plugin(),
                            () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
                    }
                }
                case MESSAGE -> p.sendMessage(Text.parse(this.mini(p, a.value(), vars)));
                case ACTIONBAR -> p.sendActionBar(Text.parse(this.mini(p, a.value(), vars)));
                case SOUND -> sound(p, a.value());
                case GOTO -> target = a.value().strip();
                case CLOSE -> close = true;
                default -> {
                }
            }
        }
        return target == null && !close ? Flow.NONE : new Flow(target, close);
    }

    /** «minecraft:entity.villager.yes 1 1.2» — звук, громкость, высота. */
    public static void sound(final Player p, final String spec) {
        if (spec == null || spec.isBlank()) {
            return;
        }
        final String[] parts = spec.strip().split("\\s+");
        float vol = 1f;
        float pitch = 1f;
        try {
            if (parts.length > 1) {
                vol = Float.parseFloat(parts[1]);
            }
            if (parts.length > 2) {
                pitch = Float.parseFloat(parts[2]);
            }
        } catch (final NumberFormatException ignored) {
            // значения по умолчанию
        }
        p.playSound(p.getLocation(), parts[0].toLowerCase(Locale.ROOT), vol, pitch);
    }
}
