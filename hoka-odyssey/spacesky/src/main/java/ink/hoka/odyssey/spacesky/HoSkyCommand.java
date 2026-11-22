package ink.hoka.odyssey.spacesky;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentStringArray;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;

/**
 * /hosky — области космоса (только OP и консоль):
 * без аргументов — список (и в каких ты сейчас); add &lt;имя&gt; &lt;milky_way|nebula|stars&gt; &lt;радиус&gt; [#цвет] —
 * область с центром там, где стоишь; remove &lt;имя&gt;; reload — перечитать config.yml.
 * Любое изменение пересобирает пак — у игроков он перезагрузится (пара секунд).
 * world — карты и их тип мира (dimension в maps/&lt;карта&gt;/map.yml); world on|off &lt;карта&gt; — записать/убрать
 * dimension: "hoka:space" (применится при следующей загрузке карты).
 */
final class HoSkyCommand extends Command {
    /** Папка карт ядра (Maps: Path.of("maps") от рабочей папки сервера). */
    private static final Path MAPS = Path.of("maps");
    private static final List<String> SUBS = List.of("add", "remove", "reload", "list", "world");

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
        } else if (a[0].equalsIgnoreCase("world")) {
            if (a.length == 2) {
                out.addAll(List.of("on", "off"));
            } else if (a.length == 3) {
                out.addAll(m.server().mapNames());
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
            case "world", "map" -> world(s, a);
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
        msg(s, "/hosky world [on|off <карта>] — тип мира карт (" + SpaceDimension.KEY + " — космос)", NamedTextColor.DARK_AQUA);
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

    /** Карты, которые сейчас загружены: имя → тип мира, с которым загружена (в тике). */
    private Map<String, String> loadedMaps() {
        Map<String, String> out = new TreeMap<>();
        for (Instance i : MinecraftServer.getInstanceManager().getInstances()) {
            m.server().mapName(i).ifPresent(n -> out.put(n, i.getDimensionType().key().asString()));
        }
        return out;
    }

    private void world(CommandSender s, String[] a) {
        if (a.length == 1) {
            worldList(s);
            return;
        }
        String op = a[1].toLowerCase(Locale.ROOT);
        if (a.length < 3 || !(op.equals("on") || op.equals("off"))) {
            msg(s, "/hosky world — список карт; /hosky world on|off <карта>", NamedTextColor.RED);
            return;
        }
        boolean on = op.equals("on");
        String map = a[2].toLowerCase(Locale.ROOT);
        Map<String, String> loaded = loadedMaps();
        boolean registered = MinecraftServer.getDimensionTypeRegistry().get(Key.key(SpaceDimension.KEY)) != null;
        m.scheduler().async(() -> {
            String prev;
            String err = null;
            try {
                prev = MapDimensions.write(MAPS, map, on);
            } catch (RuntimeException e) {
                prev = null;
                // список карт — тоже здесь: это чтение папки maps/, ему не место в тике
                err = e.getMessage() + ". Карты: " + String.join(", ", m.server().mapNames());
            }
            String p = prev;
            String e = err;
            runInTick(() -> {
                if (e != null) {
                    msg(s, "Не вышло: " + e, NamedTextColor.RED);
                    return;
                }
                boolean wasSpace = SpaceDimension.KEY.equals(p);
                if (on) {
                    msg(s, wasSpace ? "У карты «" + map + "» уже dimension: \"" + SpaceDimension.KEY + "\"."
                        : "Карта «" + map + "»: в map.yml записан dimension: \"" + SpaceDimension.KEY + "\""
                        + (p != null ? " (был " + p + ")." : "."), NamedTextColor.GREEN);
                } else if (wasSpace) {
                    msg(s, "Карта «" + map + "»: dimension убран из map.yml — будет " + MapDimensions.DEFAULT + ".",
                        NamedTextColor.YELLOW);
                } else {
                    msg(s, "У карты «" + map + "» нет dimension: \"" + SpaceDimension.KEY + "\""
                        + (p != null ? " (там " + p + " — не трогаю)." : " — ничего не изменено."), NamedTextColor.YELLOW);
                    return;
                }
                msg(s, "Применится при следующей загрузке карты (перезапуск сервера или выгрузка пустой карты); "
                    + "нужен патч ядра (docs/core-dimension.md).", NamedTextColor.GRAY);
                if (loaded.containsKey(map)) {
                    msg(s, "Карта сейчас загружена (тип мира " + loaded.get(map) + "). Если ядро без исправления "
                        + "setspawn (docs/core-dimension.md), до её перезагрузки не меняйте точку появления "
                        + "(/homap setspawn): ядро перепишет map.yml своей копией и вернёт прежний dimension"
                        + (p != null ? " (" + p + ")." : " (без него)."), NamedTextColor.GOLD);
                }
                if (on && map.equals("world")) {
                    msg(s, "Основная карта «world» загружается до модулей — пока ядро это не изменит, для неё "
                        + SpaceDimension.KEY + " не сработает (будет overworld).", NamedTextColor.GOLD);
                }
                if (on && !registered) {
                    msg(s, "Тип мира " + SpaceDimension.KEY + " не зарегистрирован (модуль включён после старта) — "
                        + "нужен перезапуск сервера.", NamedTextColor.GOLD);
                }
            });
        });
    }

    private void worldList(CommandSender s) {
        Map<String, String> loaded = loadedMaps();
        boolean registered = MinecraftServer.getDimensionTypeRegistry().get(Key.key(SpaceDimension.KEY)) != null;
        boolean everywhere = m.config().getBoolean("everywhere", true);
        m.scheduler().async(() -> {
            Map<String, String> dims = MapDimensions.read(MAPS, m.server().mapNames());
            runInTick(() -> {
                msg(s, "Карты и тип мира (dimension в map.yml). Космос: "
                    + (everywhere ? "во всех мирах (everywhere: true)" : "только " + SpaceDimension.KEY + " (everywhere: false)")
                    + (registered ? "" : "; " + SpaceDimension.KEY + " не зарегистрирован — нужен перезапуск"),
                    NamedTextColor.AQUA);
                for (Map.Entry<String, String> e : dims.entrySet()) {
                    String now = loaded.get(e.getKey());
                    boolean space = SpaceDimension.KEY.equals(e.getValue());
                    Component line = Component.text(" " + e.getKey() + " — ", NamedTextColor.GRAY)
                        .append(Component.text(e.getValue(), space ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.WHITE));
                    if (now != null) {
                        line = line.append(Component.text(now.equals(e.getValue()) ? "  (загружена)"
                            : "  (загружена как " + now + ")", NamedTextColor.GREEN));
                    }
                    s.sendMessage(line);
                }
                msg(s, "/hosky world on|off <карта> — записать/убрать dimension: \"" + SpaceDimension.KEY + "\"",
                    NamedTextColor.DARK_AQUA);
            });
        });
    }

    /** Обратно в тик; если модуль уже выключен — молча. */
    private void runInTick(Runnable r) {
        try {
            m.scheduler().run(r);
        } catch (IllegalStateException off) {
            // модуль выключен
        }
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
