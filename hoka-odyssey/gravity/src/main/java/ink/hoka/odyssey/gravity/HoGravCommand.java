package ink.hoka.odyssey.gravity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.arguments.number.ArgumentDouble;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import java.util.List;
import java.util.Map;

/**
 * /hograv — карты с вакуумом (только операторы и консоль).
 * <pre>
 * /hograv               список карт с вакуумом
 * /hograv on &lt;карта&gt;    включить вакуум (сохраняется в config.yml, сразу действует на игроков карты)
 * /hograv off &lt;карта&gt;   выключить
 * /hograv reload        перечитать config.yml
 * /hograv hole list [карта]                     чёрные дыры
 * /hograv hole add &lt;имя&gt; [горизонт] [радиус]    дыра там, где стоишь (на твоей карте)
 * /hograv hole remove &lt;имя&gt; [карта]            убрать дыру
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
            s.sendMessage(Component.text("gravity: config.yml перечитан, вакуум на картах: " + names()
                + ", чёрных дыр: " + m.holeCount(), NamedTextColor.GREEN));
        }, ArgumentType.Literal("reload"));

        var hole = ArgumentType.Literal("hole");
        ArgumentWord listMap = ArgumentType.Word("карта");
        listMap.setSuggestionCallback((sender, ctx, sug) -> suggest(sug, m.server().mapNames()));
        addSyntax((s, c) -> holes(s, null), hole, ArgumentType.Literal("list"));
        addSyntax((s, c) -> holes(s, c.get(listMap)), hole, ArgumentType.Literal("list"), listMap);

        ArgumentWord newName = ArgumentType.Word("имя");
        ArgumentDouble horizon = (ArgumentDouble) ArgumentType.Double("горизонт").min(0.1);
        ArgumentDouble reach = (ArgumentDouble) ArgumentType.Double("радиус").min(0.1);
        var add = ArgumentType.Literal("add");
        addSyntax((s, c) -> add(s, c.get(newName), null, null), hole, add, newName);
        addSyntax((s, c) -> add(s, c.get(newName), c.get(horizon), null), hole, add, newName, horizon);
        addSyntax((s, c) -> add(s, c.get(newName), c.get(horizon), c.get(reach)), hole, add, newName, horizon, reach);

        ArgumentWord oldName = ArgumentType.Word("имя");
        oldName.setSuggestionCallback((sender, ctx, sug) -> {
            java.util.Set<String> names = new java.util.TreeSet<>();
            m.settings().space().holes().values().forEach(l -> l.forEach(h -> names.add(h.name())));
            suggest(sug, names);
        });
        ArgumentWord holeMap = ArgumentType.Word("карта");
        holeMap.setSuggestionCallback((sender, ctx, sug) -> suggest(sug, m.settings().space().holes().keySet()));
        var remove = ArgumentType.Literal("remove");
        addSyntax((s, c) -> remove(s, c.get(oldName), null), hole, remove, oldName);
        addSyntax((s, c) -> remove(s, c.get(oldName), c.get(holeMap)), hole, remove, oldName, holeMap);
    }

    private static void suggest(net.minestom.server.command.builder.suggestion.Suggestion sug, java.util.Collection<String> names) {
        String input = sug.getInput().replace("\0", "");
        String typed = input.substring(input.lastIndexOf(' ') + 1);
        for (String name : names) {
            if (name.startsWith(typed)) {
                sug.addEntry(new SuggestionEntry(name));
            }
        }
    }

    private void holes(CommandSender s, String map) {
        Map<String, List<BlackHole>> all = m.settings().space().holes();
        if (all.isEmpty() || (map != null && !all.containsKey(map))) {
            s.sendMessage(Component.text("Чёрных дыр " + (map == null ? "нет" : "на карте " + map + " нет")
                + ". Добавить: /hograv hole add <имя> [горизонт] [радиус]", NamedTextColor.YELLOW));
            return;
        }
        all.forEach((mp, list) -> {
            if (map != null && !map.equals(mp)) {
                return;
            }
            boolean vac = m.settings().maps().contains(mp);
            s.sendMessage(Component.text("Карта " + mp + (vac ? "" : " (вакуум выключен — дыры не действуют)") + ":",
                NamedTextColor.AQUA));
            for (BlackHole h : list) {
                s.sendMessage(Component.text(" " + h.name() + ": " + HoleYaml.num(h.x()) + " " + HoleYaml.num(h.y()) + " "
                    + HoleYaml.num(h.z()) + ", горизонт " + HoleYaml.num(h.horizon()) + ", радиус " + HoleYaml.num(h.reach())
                    + ", притяжение " + HoleYaml.num(h.pull()) + " бл/с², предел " + HoleYaml.num(h.maxSpeed()) + " бл/с",
                    NamedTextColor.GRAY));
            }
        });
    }

    private void add(CommandSender s, String name, Double horizon, Double reach) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Component.text("Дыру ставят там, где стоит игрок — команда только для игрока", NamedTextColor.RED));
            return;
        }
        if (!HoleYaml.NAME.matcher(name).matches()) {
            s.sendMessage(Component.text("Имя дыры: латиница, цифры, _ и - (до 32 символов)", NamedTextColor.RED));
            return;
        }
        String map = m.mapOf(p.getInstance()).orElse(null);
        if (map == null) {
            s.sendMessage(Component.text("Вы не на карте из maps/", NamedTextColor.RED));
            return;
        }
        BlackHole d = m.settings().space().holeDefaults();
        Pos pos = p.getPosition();
        double hz = horizon != null ? horizon : d.horizon();
        double r = reach != null ? reach : Math.max(d.reach(), hz);
        if (r < hz) {
            s.sendMessage(Component.text("Радиус действия меньше горизонта", NamedTextColor.RED));
            return;
        }
        BlackHole prev = m.settings().space().holes(map).stream().filter(h -> h.name().equals(name)).findFirst().orElse(null);
        boolean replaced = prev != null;
        // перенос — вид в небе (disk-*) прежний
        BlackHole h = new BlackHole(name, round(pos.x()), round(pos.y() + 0.9), round(pos.z()), hz, r, d.pull(), d.maxSpeed(),
            prev != null ? prev.disk() : HoleDisk.DEFAULT);
        m.setHole(map, name, h, err -> {
            if (err != null) {
                s.sendMessage(Component.text("Не удалось сохранить config.yml: " + err, NamedTextColor.RED));
                return;
            }
            s.sendMessage(Component.text("Чёрная дыра «" + name + "» " + (replaced ? "перенесена" : "создана") + " на карте "
                + map + ": " + HoleYaml.num(h.x()) + " " + HoleYaml.num(h.y()) + " " + HoleYaml.num(h.z()) + ", горизонт "
                + HoleYaml.num(hz) + ", радиус " + HoleYaml.num(r)
                + (m.settings().maps().contains(map) ? "" : ". Вакуум на карте выключен — включите: /hograv on " + map),
                NamedTextColor.GREEN));
        });
    }

    private void remove(CommandSender s, String name, String map) {
        Map<String, List<BlackHole>> all = m.settings().space().holes();
        String where = map;
        if (where == null && s instanceof Player p) {
            String here = m.mapOf(p.getInstance()).orElse(null);
            if (here != null && all.getOrDefault(here, List.of()).stream().anyMatch(h -> h.name().equals(name))) {
                where = here;
            }
        }
        if (where == null) {
            where = all.entrySet().stream().filter(e -> e.getValue().stream().anyMatch(h -> h.name().equals(name)))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        }
        String mp = where;
        if (mp == null || all.getOrDefault(mp, List.of()).stream().noneMatch(h -> h.name().equals(name))) {
            s.sendMessage(Component.text("Нет такой дыры: " + name + (map == null ? "" : " на карте " + map)
                + " (см. /hograv hole list)", NamedTextColor.RED));
            return;
        }
        m.setHole(mp, name, null, err -> {
            if (err != null) {
                s.sendMessage(Component.text("Не удалось сохранить config.yml: " + err, NamedTextColor.RED));
            } else {
                s.sendMessage(Component.text("Чёрная дыра «" + name + "» на карте " + mp + " убрана", NamedTextColor.GREEN));
            }
        });
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
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
        s.sendMessage(Component.text("Сейчас в невесомости игроков: " + m.floating() + ", предметов: " + m.floatingItems()
            + ", чёрных дыр: " + m.holeCount(), NamedTextColor.GRAY));
        s.sendMessage(Component.text("/hograv on <карта> | off <карта> | reload | hole list|add|remove", NamedTextColor.GRAY));
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
