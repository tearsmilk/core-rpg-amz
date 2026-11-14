package core.amazake.systems.coreportals;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Что делать с игроком, вошедшим в портал: список команд с подстановкой {@code {player}}, от имени игрока или
 * консоли. От имени игрока можно на время выполнения выдать права ({@code grant-permissions}) — так игрок
 * без права на {@code /warp} всё равно уйдёт на нужный варп, а других варпов не получит.
 *
 * @param runAs            кто выполняет команды
 * @param commands         команды без «/»
 * @param grantPermissions права на время выполнения (только для {@link RunAs#PLAYER})
 */
public record TeleportAction(RunAs runAs, List<String> commands, List<String> grantPermissions) {

    /** Кто выполняет команды. */
    public enum RunAs {
        PLAYER,
        CONSOLE
    }

    /**
     * По умолчанию: {@code /warp dungeon1} от имени игрока с временным правом {@code amazakecore.essentials.warp}.
     * Варпы AmazakeCore работают только для игрока, который вводит команду (из консоли — «только для игроков»,
     * указать другого игрока нельзя), поэтому от консоли {@code warp} не годится.
     */
    public static final TeleportAction DEFAULT = new TeleportAction(RunAs.PLAYER, List.of("warp dungeon1"),
        List.of("amazakecore.essentials.warp"));

    public TeleportAction {
        commands = List.copyOf(commands);
        grantPermissions = List.copyOf(grantPermissions);
    }

    /** Команды для игрока: {@code {player}} и {@code {uuid}} подставлены, ведущий «/» снят. */
    public List<String> resolve(final String player, final String uuid) {
        final List<String> out = new ArrayList<>(this.commands.size());
        for (final String c : this.commands) {
            String s = c.strip();
            while (s.startsWith("/")) {
                s = s.substring(1);
            }
            if (!s.isEmpty()) {
                out.add(s.replace("{player}", player).replace("{uuid}", uuid));
            }
        }
        return out;
    }

    static RunAs parseRunAs(final String raw, final Consumer<String> warn) {
        if (raw == null) {
            return DEFAULT.runAs();
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "player", "игрок" -> RunAs.PLAYER;
            case "console", "консоль" -> RunAs.CONSOLE;
            default -> {
                warn.accept("teleport.run-as: «" + raw + "» — нужно player или console, беру player");
                yield RunAs.PLAYER;
            }
        };
    }
}
