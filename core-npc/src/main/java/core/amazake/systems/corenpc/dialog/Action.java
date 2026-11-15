package core.amazake.systems.corenpc.dialog;

import java.util.Locale;

/**
 * Действие страницы, ответа или NPC: строка «тип: значение».
 * <ul>
 *   <li>{@code player: spawn} — команда от имени игрока (без /);</li>
 *   <li>{@code console: give {player} bread 3} — команда из консоли;</li>
 *   <li>{@code message: <green>Держи!} — сообщение игроку (MiniMessage);</li>
 *   <li>{@code actionbar: ...}, {@code sound: entity.villager.yes [громкость] [высота]};</li>
 *   <li>{@code goto: page} / {@code goto: other_dialog:page} — перейти, {@code close} / {@code end} — закрыть.</li>
 * </ul>
 * Строка без известного префикса — команда игрока (как у Citizens: «/spawn» → player).
 */
public record Action(Type type, String value) {

    public enum Type { PLAYER, CONSOLE, MESSAGE, ACTIONBAR, SOUND, GOTO, CLOSE }

    /** null — пустая строка. */
    public static Action parse(final String raw) {
        if (raw == null) {
            return null;
        }
        final String s = raw.strip();
        if (s.isEmpty()) {
            return null;
        }
        final String low = s.toLowerCase(Locale.ROOT);
        if (low.equals("close") || low.equals("end") || low.equals("close:") || low.equals("end:")) {
            return new Action(Type.CLOSE, "");
        }
        final int colon = s.indexOf(':');
        if (colon > 0) {
            final String head = low.substring(0, colon).strip();
            final String rest = s.substring(colon + 1).strip();
            final Type t = switch (head) {
                case "player", "cmd", "command" -> Type.PLAYER;
                case "console" -> Type.CONSOLE;
                case "message", "msg", "tell" -> Type.MESSAGE;
                case "actionbar" -> Type.ACTIONBAR;
                case "sound" -> Type.SOUND;
                case "goto" -> Type.GOTO;
                case "close", "end" -> Type.CLOSE;
                default -> null;
            };
            if (t != null) {
                return new Action(t, t == Type.PLAYER || t == Type.CONSOLE ? stripSlash(rest) : rest);
            }
        }
        return new Action(Type.PLAYER, stripSlash(s));
    }

    private static String stripSlash(final String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }

    /** Обратно в строку для npcs.yml. */
    public String serialize() {
        return switch (this.type) {
            case CLOSE -> "close";
            default -> this.type.name().toLowerCase(Locale.ROOT) + ": " + this.value;
        };
    }
}
