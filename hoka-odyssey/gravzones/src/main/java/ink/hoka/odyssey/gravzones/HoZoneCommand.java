package ink.hoka.odyssey.gravzones;

import java.util.List;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.entity.Player;

/**
 * /hozone — зоны гравитации (только операторы; консоли — list и reload).
 * <pre>
 * /hozone axe                    топорик выделения
 * /hozone pos1|pos2              точка выделения — блок у ног (как //pos1 в WorldEdit; удобно для точек в воздухе)
 * /hozone create &lt;имя&gt; &lt;сила&gt;   зона из выделения
 * /hozone set &lt;имя&gt; &lt;сила&gt;      сменить силу
 * /hozone remove &lt;имя&gt;           убрать
 * /hozone list                   зоны этой карты и в какой ты сейчас
 * /hozone info &lt;имя&gt;             подробно
 * /hozone reload                 перечитать config.yml
 * </pre>
 */
final class HoZoneCommand extends Command {
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_-]{1,32}");
    private static final List<String> STRENGTHS = List.of("0.1", "0.3", "0.5", "2", "5", "10", "11", "12", "13");
    private final GravZonesModule m;

    HoZoneCommand(GravZonesModule m) {
        super("hozone");
        this.m = m;
        setCondition((s, c) -> !(s instanceof Player p) || m.server().isOp(p));
        setDefaultExecutor((s, c) -> help(s));

        ArgumentWord newName = ArgumentType.Word("имя");
        ArgumentWord name = zoneArgument();
        ArgumentWord g1 = strengthArgument();
        ArgumentWord g2 = strengthArgument();
        addSyntax((s, c) -> axe(s), ArgumentType.Literal("axe"));
        addSyntax((s, c) -> here(s, 1), ArgumentType.Literal("pos1"));
        addSyntax((s, c) -> here(s, 2), ArgumentType.Literal("pos2"));
        addSyntax((s, c) -> create(s, c.get(newName), c.get(g1)), ArgumentType.Literal("create"), newName, g1);
        addSyntax((s, c) -> help(s), ArgumentType.Literal("create"));
        addSyntax((s, c) -> set(s, c.get(name), c.get(g2)), ArgumentType.Literal("set"), name, g2);
        addSyntax((s, c) -> remove(s, c.get(name)), ArgumentType.Literal("remove"), name);
        addSyntax((s, c) -> list(s), ArgumentType.Literal("list"));
        addSyntax((s, c) -> info(s, c.get(name)), ArgumentType.Literal("info"), name);
        addSyntax((s, c) -> {
            m.reloadSettings();
            ok(s, "gravzones: config.yml перечитан");
        }, ArgumentType.Literal("reload"));
    }

    /** Имя существующей зоны с подсказками (зоны карты игрока). */
    private ArgumentWord zoneArgument() {
        ArgumentWord a = ArgumentType.Word("зона");
        a.setSuggestionCallback((sender, ctx, sug) -> {
            if (!(sender instanceof Player p)) {
                return;
            }
            String map = m.mapOf(p);
            if (map == null) {
                return;
            }
            String input = sug.getInput().replace("\0", "");
            String typed = input.substring(input.lastIndexOf(' ') + 1).toLowerCase();
            for (Zone z : m.zones().on(map)) {
                if (z.name().toLowerCase().startsWith(typed)) {
                    sug.addEntry(new SuggestionEntry(z.name(), Component.text("сила " + Physics.fmt(z.g()))));
                }
            }
        });
        return a;
    }

    private static ArgumentWord strengthArgument() {
        ArgumentWord a = ArgumentType.Word("сила");
        a.setSuggestionCallback((sender, ctx, sug) -> {
            String input = sug.getInput().replace("\0", "");
            String typed = input.substring(input.lastIndexOf(' ') + 1);
            for (String v : STRENGTHS) {
                if (v.startsWith(typed)) {
                    sug.addEntry(new SuggestionEntry(v, Component.text(describe(Double.parseDouble(v)))));
                }
            }
        });
        return a;
    }

    static String describe(double g) {
        if (Physics.isVacuum(g)) {
            return "вакуумный выброс";
        }
        if (g < 1) {
            return "слабая гравитация";
        }
        return g == 1 ? "обычная гравитация" : "сильная гравитация";
    }

    /** Разобрать силу (точка или запятая); NaN — ошибка. */
    static double parseStrength(String s) {
        try {
            double g = Double.parseDouble(s.replace(',', '.'));
            return Physics.valid(g) ? g : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // ───────────────────────────── команды ─────────────────────────────

    private void help(CommandSender s) {
        s.sendMessage(Component.text("Зоны гравитации:", NamedTextColor.AQUA));
        for (String l : List.of(
            "/hozone axe — топорик: ЛКМ по блоку — точка 1, ПКМ — точка 2",
            "/hozone pos1 | pos2 — точка 1/2 там, где стоишь (для точек в воздухе)",
            "/hozone create <имя> <сила> — зона из выделения",
            "/hozone set <имя> <сила> — сменить силу",
            "/hozone remove <имя> — убрать зону",
            "/hozone list — зоны этой карты (и в какой ты)",
            "/hozone info <имя> — подробно",
            "/hozone reload — перечитать config.yml",
            "Сила: 0.1…10 — во сколько раз гравитация сильнее обычной (0.1 — почти луна, 10 — очень тяжело);",
            "11, 12, 13 — вакуумный выброс: подбрасывает вверх, дальше невесомость.",
            "Пересекаются — действует меньшая по объёму (при равном — новее).")) {
            s.sendMessage(Component.text(l, NamedTextColor.GRAY));
        }
    }

    private Player player(CommandSender s) {
        if (s instanceof Player p) {
            return p;
        }
        err(s, "Только для игрока");
        return null;
    }

    private String map(Player p) {
        String map = m.mapOf(p);
        if (map == null) {
            err(p, "Этот мир не карта из maps/ — зоны здесь не ставятся");
        }
        return map;
    }

    private boolean ready(CommandSender s) {
        if (!m.loaded()) {
            err(s, "Зоны ещё загружаются, повторите через секунду");
        }
        return m.loaded();
    }

    private void axe(CommandSender s) {
        Player p = player(s);
        if (p == null) {
            return;
        }
        p.getInventory().addItemStack(AxeListener.create());
        ok(p, "Топорик выдан: ЛКМ по блоку — точка 1, ПКМ — точка 2, затем /hozone create <имя> <сила>");
    }

    private void here(CommandSender s, int which) {
        Player p = player(s);
        if (p == null) {
            return;
        }
        m.axe().point(p, which, p.getPosition());
    }

    private void create(CommandSender s, String name, String gText) {
        Player p = player(s);
        if (p == null || !ready(s)) {
            return;
        }
        String map = map(p);
        if (map == null) {
            return;
        }
        if (!NAME.matcher(name).matches()) {
            err(p, "Имя: буквы, цифры, _ и -, до 32 знаков");
            return;
        }
        double g = parseStrength(gText);
        if (Double.isNaN(g)) {
            err(p, "Сила — число от 0.1 до 10 или 11, 12, 13 (вакуумный выброс)");
            return;
        }
        Selection sel = m.selection(p);
        if (!sel.complete() || !map.equals(sel.map)) {
            err(p, "Сначала выделите область топориком на этой карте (/hozone axe): ЛКМ — точка 1, ПКМ — точка 2");
            return;
        }
        if (m.zones().get(map, name) != null) {
            err(p, "Зона «" + name + "» на этой карте уже есть: /hozone set " + name + " <сила> или /hozone remove " + name);
            return;
        }
        Zone z = new Zone(name, map, sel.p1[0], sel.p1[1], sel.p1[2], sel.p2[0], sel.p2[1], sel.p2[2], g, m.zones().nextSeq());
        m.zones().put(z);
        List<Zone> over = m.zones().overlapping(z);
        m.save(e -> {
            if (e == null) {
                ok(p, "Зона «" + name + "» создана: " + z.bounds() + ", " + z.volume() + " бл., сила " + Physics.fmt(g)
                    + " (" + describe(g) + ")");
                if (!over.isEmpty()) {
                    StringBuilder b = new StringBuilder("Пересекается с: ");
                    for (Zone o : over) {
                        b.append(o.name()).append(z.beats(o) ? " (главнее новая)" : " (главнее " + o.name() + ")").append("; ");
                    }
                    p.sendMessage(Component.text(b.toString(), NamedTextColor.YELLOW));
                }
            } else {
                err(p, "Зона создана, но не сохранена: " + e);
            }
        });
    }

    private void set(CommandSender s, String name, String gText) {
        Player p = player(s);
        if (p == null || !ready(s)) {
            return;
        }
        String map = map(p);
        if (map == null) {
            return;
        }
        Zone z = m.zones().get(map, name);
        if (z == null) {
            err(p, "Зоны «" + name + "» на этой карте нет (/hozone list)");
            return;
        }
        double g = parseStrength(gText);
        if (Double.isNaN(g)) {
            err(p, "Сила — число от 0.1 до 10 или 11, 12, 13 (вакуумный выброс)");
            return;
        }
        m.zones().put(z.withG(g));
        m.save(e -> {
            if (e == null) {
                ok(p, "Зона «" + z.name() + "»: сила " + Physics.fmt(g) + " (" + describe(g) + ")");
            } else {
                err(p, "Изменено, но не сохранено: " + e);
            }
        });
    }

    private void remove(CommandSender s, String name) {
        Player p = player(s);
        if (p == null || !ready(s)) {
            return;
        }
        String map = map(p);
        if (map == null) {
            return;
        }
        Zone z = m.zones().remove(map, name);
        if (z == null) {
            err(p, "Зоны «" + name + "» на этой карте нет (/hozone list)");
            return;
        }
        m.save(e -> {
            if (e == null) {
                ok(p, "Зона «" + z.name() + "» убрана");
            } else {
                err(p, "Убрана, но не сохранено: " + e);
            }
        });
    }

    private void list(CommandSender s) {
        if (!ready(s)) {
            return;
        }
        if (!(s instanceof Player p)) {
            List<Zone> all = m.zones().all();
            s.sendMessage(Component.text("Зон гравитации: " + all.size(), NamedTextColor.AQUA));
            for (Zone z : all) {
                s.sendMessage(Component.text(" " + z.map() + "/" + z.name() + ": сила " + Physics.fmt(z.g()) + ", " + z.bounds(),
                    NamedTextColor.GRAY));
            }
            return;
        }
        String map = map(p);
        if (map == null) {
            return;
        }
        List<Zone> on = m.zones().on(map);
        p.sendMessage(Component.text("Зоны гравитации на карте " + map + ": " + (on.isEmpty() ? "нет" : on.size()),
            NamedTextColor.AQUA));
        for (Zone z : on) {
            p.sendMessage(Component.text(" " + z.name() + ": сила " + Physics.fmt(z.g()) + " (" + describe(z.g()) + "), "
                + z.bounds() + ", " + z.volume() + " бл.", NamedTextColor.GRAY));
        }
        Zone here = m.zoneOf(p);
        p.sendMessage(Component.text(here == null ? "Ты сейчас вне зон"
            : "Ты сейчас в зоне «" + here.name() + "» (сила " + Physics.fmt(here.g()) + ")", NamedTextColor.YELLOW));
    }

    private void info(CommandSender s, String name) {
        Player p = player(s);
        if (p == null || !ready(s)) {
            return;
        }
        String map = map(p);
        if (map == null) {
            return;
        }
        Zone z = m.zones().get(map, name);
        if (z == null) {
            err(p, "Зоны «" + name + "» на этой карте нет (/hozone list)");
            return;
        }
        Settings st = m.settings();
        p.sendMessage(Component.text("Зона «" + z.name() + "» (карта " + z.map() + ")", NamedTextColor.AQUA));
        p.sendMessage(Component.text(" Границы: " + z.bounds() + ", объём " + z.volume() + " бл.", NamedTextColor.GRAY));
        p.sendMessage(Component.text(" Сила: " + Physics.fmt(z.g()) + " — " + describe(z.g()), NamedTextColor.GRAY));
        if (z.vacuum()) {
            p.sendMessage(Component.text(" Выброс вверх: " + Physics.fmt(st.launch(z.g())) + " бл/с, затем невесомость; после выхода ещё "
                + Physics.fmt(st.lingerTicks() / (double) Physics.TPS) + " с", NamedTextColor.GRAY));
        } else {
            double[] j = Physics.jumpIn(z.g(), st.weakExponent(), st.strongExponent());
            double[] j0 = Physics.jump(Physics.VANILLA_JUMP, Physics.VANILLA_GRAVITY);
            p.sendMessage(Component.text(" Гравитация " + Physics.fmt(Physics.VANILLA_GRAVITY * z.g()) + " бл/тик², прыжок ≈ "
                + Physics.fmt(j[0]) + " бл., в воздухе ≈ " + Physics.fmt(j[1] / Physics.TPS) + " с (обычно "
                + Physics.fmt(j0[0]) + " бл., " + Physics.fmt(j0[1] / Physics.TPS) + " с)", NamedTextColor.GRAY));
        }
        List<Zone> over = m.zones().overlapping(z);
        if (!over.isEmpty()) {
            StringBuilder b = new StringBuilder(" Пересекается с: ");
            for (Zone o : over) {
                b.append(o.name()).append(z.beats(o) ? " (эта главнее)" : " (главнее " + o.name() + ")").append("; ");
            }
            p.sendMessage(Component.text(b.toString(), NamedTextColor.YELLOW));
        }
    }

    private static void ok(CommandSender s, String text) {
        s.sendMessage(Component.text(text, NamedTextColor.GREEN));
    }

    private static void err(CommandSender s, String text) {
        s.sendMessage(Component.text(text, NamedTextColor.RED));
    }
}
