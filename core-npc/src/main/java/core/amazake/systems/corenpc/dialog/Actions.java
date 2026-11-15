package core.amazake.systems.corenpc.dialog;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corenpc.Text;
import core.amazake.systems.corenpc.hook.Papi;
import core.amazake.systems.corenpc.util.Placeholders;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
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
    private final Map<String, Boolean> warned = new ConcurrentHashMap<>();

    public Actions(final SystemContext context, final Papi papi) {
        this.context = context;
        this.papi = papi;
    }

    /**
     * Переменные, которые подставляются в консольные команды: их значения не задаёт игрок (ник — только
     * [A-Za-z0-9_]). {player_display} (ник из плагинов ников), {npc_name} и прочие в консольной команде остаются
     * как есть.
     */
    public static final Set<String> CONSOLE_VARS = Set.of("player", "player_uuid", "npc", "dialog", "world");

    /** Подставить {переменные} и %плейсхолдеры% в команду игрока (без экранирования: она выполняется с его правами). */
    public String command(final Player p, final String template, final Map<String, String> vars) {
        return this.papi.apply(p, Placeholders.apply(template, vars));
    }

    /**
     * Консольная команда: только {@link #CONSOLE_VARS}, и каждое подставленное значение (переменная или
     * %плейсхолдер%) должно быть одним безопасным словом ({@link #safeArgument}) — иначе null: команда не
     * выполняется (в лог — один раз на шаблон). Так значение не добавит команде аргументов и не подставит селектор.
     */
    public String console(final Player p, final String template, final Map<String, String> vars) {
        final Map<String, String> safe = new HashMap<>();
        for (final String k : CONSOLE_VARS) {
            final String v = vars.get(k);
            if (v != null) {
                safe.put(k, v);
            }
        }
        final String[] bad = new String[1];
        final UnaryOperator<String> check = v -> {
            if (!safeArgument(v) && bad[0] == null) {
                bad[0] = v;
            }
            return v;
        };
        final String cmd = this.papi.apply(p, Placeholders.apply(template, safe, check), check);
        if (bad[0] != null) {
            if (this.warned.putIfAbsent(template, Boolean.TRUE) == null) {
                this.context.logger().warning("Консольная команда «" + template + "» не выполнена для " + p.getName()
                    + ": подставленное значение «" + bad[0] + "» — не одно слово (пробелы, управляющие символы или @)");
            }
            return null;
        }
        return cmd;
    }

    /** Одно слово без пробелов и управляющих символов и не селектор (@a, @e…). */
    public static boolean safeArgument(final String v) {
        if (v.startsWith("@")) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            final char c = v.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c) || c == '\u00a7') {
                return false;
            }
        }
        return true;
    }

    /**
     * То же для MiniMessage: значения переменных и результаты %плейсхолдеров% экранируются — ник или ответ
     * плейсхолдера не станет тегами (цвет, click). Плейсхолдер, который должен вернуть разметку, так не сработает.
     */
    public String mini(final Player p, final String template, final Map<String, String> vars) {
        final UnaryOperator<String> esc = MiniMessage.miniMessage()::escapeTags;
        return this.papi.apply(p, Placeholders.apply(template, vars, esc), esc);
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
                    final String cmd = this.console(p, a.value(), vars);
                    if (cmd != null && !cmd.isBlank()) {
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
