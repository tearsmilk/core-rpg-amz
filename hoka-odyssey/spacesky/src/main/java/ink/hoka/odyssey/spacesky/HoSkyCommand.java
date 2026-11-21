package ink.hoka.odyssey.spacesky;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentStringArray;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;

/**
 * /hosky — области космоса (только OP и консоль):
 * без аргументов — список (и в каких ты сейчас); add &lt;имя&gt; &lt;milky_way|nebula|stars&gt; &lt;радиус&gt; [#цвет] —
 * область с центром там, где стоишь; remove &lt;имя&gt;; reload — перечитать config.yml.
 * Любое изменение пересобирает пак — у игроков он перезагрузится (пара секунд).
 */
final class HoSkyCommand extends Command {
    private static final List<String> SUBS = List.of("add", "remove", "reload", "list");

    private final SpaceSkyModule m;

    HoSkyCommand(SpaceSkyModule m) {
        super("hosky");
        this.m = m;
        setCondition((s, c) -> !(s instanceof Player p) || m.server().isOp(p));
        setDefaultExecutor((s, c) -> list(s));
        ArgumentStringArray args = ArgumentType.StringArray("аргументы");
        args.setSuggestionCallback((sender, ctx, sug) -> {
            String input = sug.getInput().replace("\0", "");
            String rest = input.indexOf(' ') < 0 ? "" : input.substring(input.indexOf(' ') + 1);
            String[] parts = rest.split(" ", -1);
            String last = parts[parts.length - 1].toLowerCase(Locale.ROOT);
            for (String o : complete(parts)) {
                if (o.toLowerCase(Locale.ROOT).startsWith(last)) {
                    sug.addEntry(new SuggestionEntry(o));
                }
            }
        });
        addSyntax((s, c) -> run(s, c.get(args)), args);
    }

    private List<String> complete(String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length <= 1) {
            out.addAll(SUBS);
        } else if (a[0].equalsIgnoreCase("add")) {
            if (a.length == 2) {
                out.add("<имя>");
            } else if (a.length == 3) {
                for (Zones.Kind k : Zones.Kind.values()) {
                    out.add(k.id);
                }
            } else if (a.length == 4) {
                out.addAll(List.of("500", "1000", "2000", "5000"));
            } else if (a.length == 5 && Zones.Kind.parse(a[2]) == Zones.Kind.NEBULA) {
                out.addAll(List.of("#B060FF", "#40A0FF", "#FF6080", "#60FFB0"));
            }
        } else if (a[0].equalsIgnoreCase("remove") && a.length == 2) {
            for (Zones.Zone z : m.zones()) {
                out.add(z.id());
            }
        }
        return out;
    }

    private static void msg(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(Component.text(text, color));
    }

    private void run(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "add" -> add(s, a);
            case "remove", "del", "delete" -> remove(s, a);
            case "reload" -> {
                msg(s, "Перечитываю config.yml и пересобираю пак…", NamedTextColor.GRAY);
                apply(s, "Небо обновлено.");
            }
            default -> list(s);
        }
    }

    private void list(CommandSender s) {
        List<Zones.Zone> zs = m.zones();
        msg(s, "Области космоса: " + zs.size() + " (вне их — только общий фон и редкие звёзды)", NamedTextColor.AQUA);
        Pos at = s instanceof Player p ? p.getPosition() : null;
        for (Zones.Zone z : zs) {
            String where = "";
            if (at != null) {
                double d = Math.hypot(at.x() - z.x(), at.z() - z.z());
                where = d <= z.radius() ? "  ← ты здесь" : String.format(Locale.ROOT, "  (%.0f бл.)", d - z.radius());
            }
            Component line = Component.text(String.format(Locale.ROOT, " %s — %s, центр %.0f %.0f, радиус %.0f",
                z.id(), z.kind().title, z.x(), z.z(), z.radius()), NamedTextColor.GRAY);
            if (z.kind() == Zones.Kind.NEBULA) {
                line = line.append(Component.text(" ■", TextColor.color(z.color())));
            }
            s.sendMessage(line.append(Component.text(where, NamedTextColor.GREEN)));
        }
        msg(s, "/hosky add <имя> <milky_way|nebula|stars> <радиус> [#цвет] — область там, где стоишь", NamedTextColor.DARK_AQUA);
        msg(s, "/hosky remove <имя>, /hosky reload", NamedTextColor.DARK_AQUA);
    }

    private void add(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            msg(s, "Центр области — там, где стоит игрок; из консоли — правьте zones в config.yml и /hosky reload.",
                NamedTextColor.RED);
            return;
        }
        if (a.length < 4) {
            msg(s, "/hosky add <имя> <milky_way|nebula|stars> <радиус> [#цвет]", NamedTextColor.RED);
            return;
        }
        String id = a[1].toLowerCase(Locale.ROOT);
        Zones.Kind kind = Zones.Kind.parse(a[2]);
        double radius;
        try {
            radius = Double.parseDouble(a[3].replace(',', '.'));
        } catch (NumberFormatException e) {
            radius = -1;
        }
        Integer color = a.length >= 5 ? Zones.parseColor(a[4]) : Integer.valueOf(0xB060FF);
        if (!Zones.ID.matcher(id).matches()) {
            msg(s, "Имя — только a-z, 0-9, _ (до 32 символов).", NamedTextColor.RED);
        } else if (kind == null) {
            msg(s, "Вид — milky_way, nebula или stars.", NamedTextColor.RED);
        } else if (!(radius > 0) || radius > 1_000_000) {
            msg(s, "Радиус — число блоков больше 0.", NamedTextColor.RED);
        } else if (color == null) {
            msg(s, "Цвет — #RRGGBB.", NamedTextColor.RED);
        } else if (m.zones().size() >= Zones.MAX && m.zones().stream().noneMatch(z -> z.id().equals(id))) {
            msg(s, "Уже " + Zones.MAX + " областей — больше нельзя.", NamedTextColor.RED);
        } else {
            Pos pos = p.getPosition();
            Zones.write(m.config(), new Zones.Zone(id, kind, Math.round(pos.x()), Math.round(pos.z()), radius, 0.3, color));
            m.config().save();
            msg(s, "Область " + id + " (" + kind.title + ") — центр здесь, радиус " + (long) radius + ". Пересобираю пак…",
                NamedTextColor.GREEN);
            apply(s, "Готово: пак отправлен игрокам.");
        }
    }

    private void remove(CommandSender s, String[] a) {
        if (a.length < 2) {
            msg(s, "/hosky remove <имя>", NamedTextColor.RED);
            return;
        }
        String id = a[1].toLowerCase(Locale.ROOT);
        if (m.zones().stream().noneMatch(z -> z.id().equals(id))) {
            msg(s, "Нет области " + id + ". Есть: " + String.join(", ", m.zones().stream().map(Zones.Zone::id).toList()),
                NamedTextColor.RED);
            return;
        }
        m.config().set("zones." + id, null);
        m.config().save();
        msg(s, "Область " + id + " убрана. Пересобираю пак…", NamedTextColor.YELLOW);
        apply(s, "Готово: пак отправлен игрокам.");
    }

    private void apply(CommandSender s, String ok) {
        m.syncPack().whenComplete((errors, err) -> {
            try {
                m.scheduler().run(() -> {
                    if (err != null) {
                        Throwable c = err.getCause() != null ? err.getCause() : err;
                        msg(s, "Пак не собран: " + c.getMessage(), NamedTextColor.RED);
                        return;
                    }
                    for (String e : errors) {
                        msg(s, "config.yml, zones: " + e, NamedTextColor.RED);
                    }
                    msg(s, ok + " Областей: " + m.zones().size() + ".", NamedTextColor.GREEN);
                });
            } catch (IllegalStateException off) {
                // модуль выключен
            }
        });
    }
}
