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
 * world — карты и их тип мира (dimension в maps/&lt;карта&gt;/map.yml); world &lt;карта&gt; &lt;профиль|off&gt; — записать
 * dimension: "hoka:&lt;профиль&gt;" (on = space) или убрать его (применится при следующей загрузке карты).
 * profiles — профили неба (вид, номер, карты).
 */
final class HoSkyCommand extends Command {
    private static final Path MAPS = MapDimensions.MAPS;
    private static final List<String> SUBS = List.of("add", "remove", "reload", "list", "world", "profiles");

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
            } else if (a.length >= 5) {
                if (a.length == 5 && Zones.Kind.parse(a[2]) == Zones.Kind.NEBULA) {
                    out.addAll(List.of("#B060FF", "#40A0FF", "#FF6080", "#60FFB0"));
                }
                for (Profiles.Profile p : m.profiles()) {
                    if (!p.planet()) {
                        out.add(p.id());
                    }
                }
            }
        } else if (a[0].equalsIgnoreCase("world")) {
            if (a.length == 2) {
                out.addAll(m.cachedMapNames());
            } else if (a.length == 3) {
                out.addAll(profileIds());
                out.add("off");
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
            case "profiles", "profile" -> profiles(s);
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
            Component line = Component.text(String.format(Locale.ROOT, " %s — %s, центр %.0f %.0f, радиус %.0f%s",
                z.id(), z.kind().title, z.x(), z.z(), z.radius(),
                z.profile() != null ? ", профиль " + z.profile() : ""), NamedTextColor.GRAY);
            if (z.kind() == Zones.Kind.NEBULA && z.color() >= 0) {
                line = line.append(Component.text(" ■", TextColor.color(z.color())));
            }
            s.sendMessage(line.append(Component.text(where, NamedTextColor.GREEN)));
        }
        List<Holes.Hole> hs = m.holes();
        if (!hs.isEmpty()) {
            msg(s, "Чёрные дыры в небе (из gravity, /hograv hole): " + hs.size(), NamedTextColor.AQUA);
            for (Holes.Hole h : hs) {
                msg(s, String.format(Locale.ROOT, " %s (карта %s, небо %s) — %.0f %.0f %.0f, горизонт %.1f", h.name(),
                    h.map(), profileName(h.profile()), h.x(), h.y(), h.z(), h.horizon()), NamedTextColor.GRAY);
            }
        }
        msg(s, "/hosky add <имя> <milky_way|nebula|stars> <радиус> [#цвет] [профиль] — область там, где стоишь",
            NamedTextColor.DARK_AQUA);
        msg(s, "/hosky remove <имя>, /hosky reload, /hosky profiles — профили неба", NamedTextColor.DARK_AQUA);
        msg(s, "/hosky world [<карта> <профиль|off>] — небо карт (тип мира hoka:<профиль>)", NamedTextColor.DARK_AQUA);
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
        Integer color = -1;
        String profile = null;
        for (int i = 4; i < a.length; i++) {
            if (a[i].startsWith("#")) {
                color = Zones.parseColor(a[i]);
            } else {
                profile = a[i].toLowerCase(Locale.ROOT);
            }
        }
        if (!Zones.ID.matcher(id).matches()) {
            msg(s, "Имя — только a-z, 0-9, _ (до 32 символов).", NamedTextColor.RED);
        } else if (kind == null) {
            msg(s, "Вид — milky_way, nebula или stars.", NamedTextColor.RED);
        } else if (!(radius > 0) || radius > 1_000_000) {
            msg(s, "Радиус — число блоков больше 0.", NamedTextColor.RED);
        } else if (color == null) {
            msg(s, "Цвет — #RRGGBB.", NamedTextColor.RED);
        } else if (profile != null && !profileIds().contains(profile)) {
            msg(s, "Нет профиля неба «" + profile + "». Есть: " + String.join(", ", profileIds()), NamedTextColor.RED);
        } else if (m.zones().size() >= Zones.MAX && m.zones().stream().noneMatch(z -> z.id().equals(id))) {
            msg(s, "Уже " + Zones.MAX + " областей — больше нельзя.", NamedTextColor.RED);
        } else {
            Pos pos = p.getPosition();
            Zones.Zone zone = new Zones.Zone(id, kind, Math.round(pos.x()), Math.round(pos.z()), radius, 0.3, color, profile);
            saveZones(s, c -> Zones.write(c, zone), "Область " + id + " (" + kind.title + ") — центр здесь, радиус "
                + (long) radius + ". Пересобираю пак…", NamedTextColor.GREEN);
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
        saveZones(s, c -> c.set("zones." + id, null), "Область " + id + " убрана. Пересобираю пак…",
            NamedTextColor.YELLOW);
    }

    /**
     * Записать раздел zones в config.yml (в async, остальной текст и комментарии файла целы — {@link Zones#saveYaml}),
     * затем в тике — сообщение и пересборка пака (config.yml перечитывается).
     */
    private void saveZones(CommandSender s, java.util.function.Consumer<ink.hoka.odyssey.api.ModuleConfig> change,
                           String ok, NamedTextColor color) {
        Path file = m.config().file();
        m.scheduler().async(() -> {
            String err = null;
            try {
                Zones.saveYaml(file, change);
            } catch (RuntimeException e) {
                err = e.getMessage();
            }
            String e = err;
            runInTick(() -> {
                if (e != null) {
                    msg(s, "config.yml не записан: " + e, NamedTextColor.RED);
                    return;
                }
                msg(s, ok, color);
                apply(s, "Готово: пак отправлен игрокам.");
            });
        });
    }

    /** Карты, которые сейчас загружены: имя → тип мира, с которым загружена (в тике). */
    private Map<String, String> loadedMaps() {
        Map<String, String> out = new TreeMap<>();
        for (Instance i : MinecraftServer.getInstanceManager().getInstances()) {
            m.server().mapName(i).ifPresent(n -> out.put(n, i.getDimensionType().key().asString()));
        }
        return out;
    }

    /** id профилей: зарегистрированные и из config.yml (новые — после перезапуска). */
    private List<String> profileIds() {
        List<String> out = new ArrayList<>();
        for (Profiles.Profile p : m.profiles()) {
            out.add(p.id());
        }
        for (String id : m.pendingProfiles()) {
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private String profileName(int index) {
        for (Profiles.Profile p : m.profiles()) {
            if (p.index() == index) {
                return p.id();
            }
        }
        return "#" + index;
    }

    private void world(CommandSender s, String[] a) {
        if (a.length == 1) {
            worldList(s);
            return;
        }
        if (a.length < 3) {
            msg(s, "/hosky world — список карт; /hosky world <карта> <профиль|off> (on = " + Profiles.DEFAULT_ID + ")",
                NamedTextColor.RED);
            return;
        }
        String map = a[1].toLowerCase(Locale.ROOT);
        String arg = a[2].toLowerCase(Locale.ROOT);
        String profile = arg.equals("on") ? Profiles.DEFAULT_ID : arg.equals("off") ? null : arg;
        if (profile != null && !profileIds().contains(profile)) {
            msg(s, "Нет профиля неба «" + profile + "». Есть: " + String.join(", ", profileIds())
                + " (новый — в config.yml, раздел profiles, и перезапуск сервера).", NamedTextColor.RED);
            return;
        }
        String dim = profile == null ? null : Profiles.PREFIX + profile;
        Map<String, String> loaded = loadedMaps();
        boolean registered = dim == null || MinecraftServer.getDimensionTypeRegistry().get(Key.key(dim)) != null;
        m.scheduler().async(() -> {
            String prev;
            String err = null;
            try {
                prev = MapDimensions.write(MAPS, map, dim);
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
                if (dim != null) {
                    if (dim.equals(p)) {
                        msg(s, "У карты «" + map + "» уже dimension: \"" + dim + "\".", NamedTextColor.GREEN);
                        return;
                    }
                    msg(s, "Карта «" + map + "»: в map.yml записан dimension: \"" + dim + "\""
                        + (p != null ? " (был " + p + ")." : "."), NamedTextColor.GREEN);
                } else if (Profiles.profileOf(p) != null) {
                    msg(s, "Карта «" + map + "»: dimension убран из map.yml (был " + p + ") — будет "
                        + MapDimensions.DEFAULT + ".", NamedTextColor.YELLOW);
                } else {
                    msg(s, "У карты «" + map + "» нет неба профиля"
                        + (p != null ? " (там " + p + " — не трогаю)." : " — ничего не изменено."), NamedTextColor.YELLOW);
                    return;
                }
                m.refreshMapNames();
                msg(s, "Применится при следующей загрузке карты (перезапуск сервера или выгрузка пустой карты); "
                    + "нужен патч ядра (docs/core-dimension.md).", NamedTextColor.GRAY);
                if (loaded.containsKey(map)) {
                    msg(s, "Карта сейчас загружена (тип мира " + loaded.get(map) + "). Если ядро без исправления "
                        + "setspawn (docs/core-dimension.md), до её перезагрузки не меняйте точку появления "
                        + "(/homap setspawn): ядро перепишет map.yml своей копией и вернёт прежний dimension"
                        + (p != null ? " (" + p + ")." : " (без него)."), NamedTextColor.GOLD);
                }
                if (dim != null && map.equals("world")) {
                    msg(s, "Основная карта «world» загружается до модулей — пока ядро это не изменит, для неё "
                        + dim + " не сработает (будет overworld).", NamedTextColor.GOLD);
                }
                if (!registered) {
                    msg(s, "Тип мира " + dim + " ещё не зарегистрирован (новый профиль или модуль включён после "
                        + "старта) — нужен перезапуск сервера.", NamedTextColor.GOLD);
                }
            });
        });
    }

    private void worldList(CommandSender s) {
        Map<String, String> loaded = loadedMaps();
        boolean everywhere = m.config().getBoolean("everywhere", true);
        m.scheduler().async(() -> {
            Map<String, String> dims = MapDimensions.read(MAPS, m.server().mapNames());
            runInTick(() -> {
                msg(s, "Карты и тип мира (dimension в map.yml). Небо профилей: "
                    + (everywhere ? "во всех мирах (everywhere: true; без профиля — " + Profiles.DEFAULT_ID + ")"
                    : "только в мирах hoka:<профиль> (everywhere: false)"), NamedTextColor.AQUA);
                for (Map.Entry<String, String> e : dims.entrySet()) {
                    String now = loaded.get(e.getKey());
                    boolean prof = Profiles.profileOf(e.getValue()) != null;
                    Component line = Component.text(" " + e.getKey() + " — ", NamedTextColor.GRAY)
                        .append(Component.text(e.getValue(), prof ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.WHITE));
                    if (now != null) {
                        line = line.append(Component.text(now.equals(e.getValue()) ? "  (загружена)"
                            : "  (загружена как " + now + ")", NamedTextColor.GREEN));
                    }
                    s.sendMessage(line);
                }
                msg(s, "/hosky world <карта> <профиль|off> — небо карты (профили: " + String.join(", ", profileIds())
                    + ")", NamedTextColor.DARK_AQUA);
            });
        });
    }

    private void profiles(CommandSender s) {
        // карты профиля — по типу мира, который на деле у мира карты (ядро без патча dimension не читает)
        Map<String, String> raw = m.cachedMapDims();
        Map<String, String> dims = m.cachedMapDimsEffective();
        msg(s, "Профили неба: " + m.profiles().size() + " (не больше " + Profiles.MAX + "). Карта выбирает профиль: "
            + "/hosky world <карта> <профиль>", NamedTextColor.AQUA);
        for (Profiles.Profile p : m.profiles()) {
            List<String> maps = new ArrayList<>();
            dims.forEach((map, d) -> {
                if (p.key().equals(d)) {
                    maps.add(map);
                }
            });
            Component line = Component.text(String.format(Locale.ROOT, " %s — %s, тип мира %s, №%d, метка %s",
                p.id(), p.kind().title, p.key(), p.index(), Zones.hex(Profiles.skyMark(p.index()))), NamedTextColor.GRAY)
                .append(Component.text(" ■", TextColor.color(p.planet() ? p.zenith().rgb() : p.nebula().rgb())));
            if (!maps.isEmpty()) {
                line = line.append(Component.text("  карты: " + String.join(", ", maps), NamedTextColor.GREEN));
            }
            s.sendMessage(line);
        }
        boolean patched = SpaceSkyModule.coreAppliesDimension()
            || dims.values().stream().anyMatch(d -> Profiles.profileOf(d) != null);
        raw.forEach((map, d) -> {
            String now = dims.getOrDefault(map, d);
            if (Profiles.profileOf(d) != null && !d.equals(now)) {
                msg(s, " карта " + map + ": в map.yml " + d + ", но её мир — " + now + (patched
                    ? " (применится при следующей загрузке карты)"
                    : " — ядро без патча docs/core-dimension.md: dimension карт не применяется, все карты overworld (небо "
                        + Profiles.DEFAULT_ID + ")"), NamedTextColor.GOLD);
            }
        });
        for (String id : m.pendingProfiles()) {
            msg(s, " " + id + " — есть в config.yml, тип мира hoka:" + id + " появится после перезапуска сервера",
                NamedTextColor.GOLD);
        }
        m.registration().status().forEach((id, st) -> {
            if (st == SpaceDimension.Status.STALE || st == SpaceDimension.Status.FULL) {
                msg(s, " " + id + " — не доступен (" + (st == SpaceDimension.Status.FULL ? "больше " + Profiles.MAX
                    + " профилей" : "старая метка, нужен перезапуск") + ")", NamedTextColor.RED);
            }
        });
        msg(s, "Профили — в config.yml (раздел profiles); /hosky reload применяет цвета (кроме облаков); "
            + "новые профили, type, clouds и cloud-color — после перезапуска сервера.", NamedTextColor.DARK_AQUA);
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
                        msg(s, "config.yml: " + e, NamedTextColor.RED);
                    }
                    msg(s, ok + " Областей: " + m.zones().size() + ".", NamedTextColor.GREEN);
                });
            } catch (IllegalStateException off) {
                // модуль выключен
            }
        });
    }
}
