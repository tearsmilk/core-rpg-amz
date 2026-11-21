package ink.hoka.odyssey.gravity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.entity.Player;

/**
 * /hograv — карты с вакуумом (только операторы и консоль).
 * <pre>
 * /hograv               список карт с вакуумом
 * /hograv on &lt;карта&gt;    включить вакуум (сохраняется в config.yml, сразу действует на игроков карты)
 * /hograv off &lt;карта&gt;   выключить
 * /hograv reload        перечитать config.yml
 * </pre>
 */
final class HoGravCommand extends Command {
    private final GravityModule m;

    HoGravCommand(GravityModule m) {
        super("hograv");
        this.m = m;
        setCondition((s, c) -> !(s instanceof Player p) || m.server().isOp(p));
        setDefaultExecutor((s, c) -> list(s));

        ArgumentWord mapOn = mapArgument(true);
        ArgumentWord mapOff = mapArgument(false);
        addSyntax((s, c) -> toggle(s, c.get(mapOn), true), ArgumentType.Literal("on"), mapOn);
        addSyntax((s, c) -> toggle(s, c.get(mapOff), false), ArgumentType.Literal("off"), mapOff);
        addSyntax((s, c) -> {
            m.reloadSettings();
            s.sendMessage(Component.text("gravity: config.yml перечитан, вакуум на картах: " + names(), NamedTextColor.GREEN));
        }, ArgumentType.Literal("reload"));
    }

    /** Аргумент «карта» с подсказками: для on — карты без вакуума, для off — с вакуумом. */
    private ArgumentWord mapArgument(boolean enabling) {
        ArgumentWord map = ArgumentType.Word("карта");
        map.setSuggestionCallback((sender, ctx, sug) -> {
            String input = sug.getInput().replace("\0", "");
            String typed = input.substring(input.lastIndexOf(' ') + 1);
            for (String name : m.server().mapNames()) {
                if (name.startsWith(typed) && m.settings().maps().contains(name) != enabling) {
                    sug.addEntry(new SuggestionEntry(name));
                }
            }
        });
        return map;
    }

    private String names() {
        return m.settings().maps().isEmpty() ? "нет" : String.join(", ", m.settings().maps());
    }

    private void list(CommandSender s) {
        s.sendMessage(Component.text("Вакуум (невесомость) на картах: " + names(), NamedTextColor.AQUA));
        s.sendMessage(Component.text("Сейчас в невесомости игроков: " + m.floating(), NamedTextColor.GRAY));
        s.sendMessage(Component.text("/hograv on <карта> | off <карта> | reload", NamedTextColor.GRAY));
    }

    private void toggle(CommandSender s, String map, boolean on) {
        if (on && !m.server().mapNames().contains(map)) {
            s.sendMessage(Component.text("Нет такой карты: " + map + " (см. /homap list)", NamedTextColor.RED));
            return;
        }
        if (m.settings().maps().contains(map) == on) {
            s.sendMessage(Component.text("На карте " + map + " вакуум уже " + (on ? "включён" : "выключен"), NamedTextColor.YELLOW));
            return;
        }
        m.setMap(map, on, err -> {
            if (err != null) {
                s.sendMessage(Component.text("Не удалось сохранить config.yml: " + err, NamedTextColor.RED));
            } else {
                s.sendMessage(Component.text("Вакуум на карте " + map + (on ? " включён" : " выключен")
                    + ". Карты с вакуумом: " + names(), NamedTextColor.GREEN));
            }
        });
    }
}
