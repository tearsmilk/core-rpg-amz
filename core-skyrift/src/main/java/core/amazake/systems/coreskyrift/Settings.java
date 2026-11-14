package core.amazake.systems.coreskyrift;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Настройки из {@code config.yml}. Неизменяемые: при перечитывании подменяется весь объект. Ошибка в значении —
 * предупреждение и значение по умолчанию.
 */
public record Settings(
    String prefix,
    Item item,
    Event event,
    Rift rift,
    Times times,
    Env env,
    Mobs mobs,
    Effects effects,
    Messages messages
) {

    /** Предмет. */
    public record Item(String name, List<String> lore, String model, boolean consume, int cooldownSeconds) {
    }

    /** Событие: радиус, лимиты, дальность видимости дисплея. */
    public record Event(double radius, int maxPerWorld, double minDistance, float viewRange) {

        /** Выход из события — дальше радиуса на столько. */
        public static final double LEAVE_MARGIN = 16;

        public double leaveRadius() {
            return this.radius + LEAVE_MARGIN;
        }
    }

    /** Направление разлома. */
    public enum YawMode { RANDOM, PLAYER, FIXED }

    /** Разлом: высота плоскости, размер, направление. */
    public record Rift(double height, double size, YawMode yawMode, double yawDegrees) {
    }

    /** Длительности стадий, секунды. */
    public record Times(double fogIn, double crack, double opening, double active, double closing, double fogOut) {
    }

    /** Окружение у игроков в радиусе. */
    public record Env(boolean bossBar, String bossBarTitle, boolean darkenSky, boolean createFog, boolean night,
                      long nightTime, int darknessPulseTicks, boolean screenFlash) {
    }

    /** Режим места спавна. */
    public enum SpawnMode { NEAR_PLAYERS, UNDER_RIFT }

    /** Мобы из разлома. */
    public record Mobs(boolean enabled, Map<String, Integer> list, String command, int maxAlive, int maxTotal,
                       int waveIntervalSeconds, int perWaveMin, int perWaveMax, int perPlayerCap, double startAtOpen,
                       boolean despawnOnEnd, SpawnMode spawnMode, double minDistance, double maxDistance,
                       int dropHeight, int slowFallingSeconds, boolean glowing) {

        /** Сколько живых мобов допустимо при {@code viewers} игроках в радиусе. */
        public int aliveCap(final int viewers) {
            final long perPlayer = (long) this.perPlayerCap * Math.max(1, viewers);
            return (int) Math.min(this.maxAlive, perPlayer);
        }
    }

    /** Частицы и звуки. */
    public record Effects(boolean particles, boolean sounds, boolean thunder, boolean ambientParticles, int trailTicks) {
    }

    /** Тексты. */
    public record Messages(String usage, String given, String received, String playerNotFound, String worldNotFound,
                           String reloaded, String cast, String started, String cooldown, String tooMany,
                           String tooClose, String stopped, String listEmpty, String list) {
    }

    public static final Settings DEFAULTS = new Settings(
        "<dark_gray>[<gradient:#ff2a00:#7a0008>Разлом</gradient>]</dark_gray> ",
        new Item("<!i><b><gradient:#ffb040:#ff2a00:#b00010:#4a0008>Разлом небес</gradient></b>",
            List.of("<!i><dark_red>Слиток, выкованный по ту сторону неба.", "<!i><gray>ПКМ — разорвать небо над собой.",
                "<!i><gray>Из разлома полезут твари."), "", true, 30),
        new Event(160, 1, 400, 2.0f),
        new Rift(160, 1.0, YawMode.RANDOM, 0),
        new Times(3, 3, 20, 60, 6, 4),
        new Env(true, "<dark_red><b>Разлом</b></dark_red>", true, true, true, 18000, 0, true),
        new Mobs(true, defaultMobs(), "mobs spawn {id} {amount} {world} {x} {y} {z}", 20, 60, 4, 2, 4, 6, 0.6, true,
            SpawnMode.NEAR_PLAYERS, 6, 24, 28, 8, false),
        new Effects(true, true, true, true, 80),
        new Messages(
            "<gray>/skyrift give [игрок] [кол-во] | start [игрок | мир x y z] | stop [игрок] | list | reload",
            "<gray>Выдано <yellow><player></yellow>: <white><amount></white> × «Разлом небес».",
            "<gray>Получено: <white><amount></white> × «Разлом небес».",
            "<red>Игрок <yellow><player></yellow> не найден.",
            "<red>Мир <yellow><world></yellow> не найден.",
            "<green>Настройки core-skyrift перечитаны.",
            "<dark_red>Небо над тобой рвётся…",
            "<gray>Разлом открыт: <white><where></white>.",
            "<red>Слиток ещё не остыл: подождите <yellow><seconds></yellow> с.",
            "<red>В этом мире небо уже разорвано.",
            "<red>Слишком близко к другому разлому (<yellow><distance></yellow> бл., нужно <yellow><need></yellow>).",
            "<gray>Закрывается разломов: <white><count></white>.",
            "<gray>Открытых разломов нет.",
            "<gray>Открытые разломы (<white><count></white>):"));

    private static Map<String, Integer> defaultMobs() {
        final Map<String, Integer> m = new LinkedHashMap<>();
        m.put("forest_zombie", 3);
        m.put("cave_skeleton", 2);
        m.put("venom_spider", 1);
        return java.util.Collections.unmodifiableMap(m);
    }

    /** Секунды → тики (не меньше 1). */
    public static long ticks(final double seconds) {
        return Math.max(1L, Math.round(seconds * 20));
    }

    // ------------------------------------------------------------------------------------------------- чтение

    public static Settings from(final ConfigurationSection c, final Consumer<String> warn) {
        final Settings d = DEFAULTS;
        final Reader r = new Reader(c, warn);
        final Item item = new Item(
            r.str("item.name", d.item.name()),
            r.strList("item.lore", d.item.lore()),
            r.str("item.model", d.item.model()).trim(),
            r.bool("item.consume", d.item.consume()),
            r.integer("item.cooldown-seconds", d.item.cooldownSeconds(), 0, 86400));
        final Event event = new Event(
            r.dbl("event.radius", d.event.radius(), 16, 512),
            r.integer("event.max-per-world", d.event.maxPerWorld(), 1, 8),
            r.dbl("event.min-distance", d.event.minDistance(), 0, 10000),
            (float) r.dbl("event.view-range", d.event.viewRange(), 0.5, 8));
        final Rift rift = new Rift(
            r.dbl("rift.height", d.rift.height(), 40, 400),
            r.dbl("rift.size", d.rift.size(), 0.25, 2.0),
            YawMode.RANDOM, 0);
        final Rift riftYaw = yaw(c, rift, warn);
        final Times times = new Times(
            r.dbl("timeline.fog-in", d.times.fogIn(), 0.5, 30),
            r.dbl("timeline.crack", d.times.crack(), 0.5, 30),
            r.dbl("timeline.opening", d.times.opening(), 1, 120),
            r.dbl("timeline.active", d.times.active(), 0, 1800),
            r.dbl("timeline.closing", d.times.closing(), 1, 60),
            r.dbl("timeline.fog-out", d.times.fogOut(), 0.5, 30));
        final Env env = new Env(
            r.bool("env.boss-bar", d.env.bossBar()),
            r.str("env.boss-bar-title", d.env.bossBarTitle()),
            r.bool("env.darken-sky", d.env.darkenSky()),
            r.bool("env.create-fog", d.env.createFog()),
            r.bool("env.night", d.env.night()),
            r.integer("env.night-time", (int) d.env.nightTime(), 0, 23999),
            r.integer("env.darkness-pulse-ticks", d.env.darknessPulseTicks(), 0, 200),
            r.bool("env.screen-flash", d.env.screenFlash()));
        final int[] perWave = r.range("mobs.per-wave", d.mobs.perWaveMin(), d.mobs.perWaveMax(), 1, 50);
        double minD = r.dbl("mobs.min-distance", d.mobs.minDistance(), 0, 64);
        double maxD = r.dbl("mobs.max-distance", d.mobs.maxDistance(), 1, 96);
        if (maxD < minD + 1) {
            warn.accept("mobs.max-distance меньше min-distance + 1: беру " + d.mobs.minDistance() + ".." + d.mobs.maxDistance());
            minD = d.mobs.minDistance();
            maxD = d.mobs.maxDistance();
        }
        final Mobs mobs = new Mobs(
            r.bool("mobs.enabled", d.mobs.enabled()),
            r.weights("mobs.list", d.mobs.list()),
            r.command("mobs.command", d.mobs.command()),
            r.integer("mobs.max-alive", d.mobs.maxAlive(), 0, 200),
            r.integer("mobs.max-total", d.mobs.maxTotal(), 0, 1000),
            r.integer("mobs.wave-interval-seconds", d.mobs.waveIntervalSeconds(), 1, 120),
            perWave[0], perWave[1],
            r.integer("mobs.per-player-cap", d.mobs.perPlayerCap(), 1, 200),
            r.dbl("mobs.start-at-open", d.mobs.startAtOpen(), 0, 1),
            r.bool("mobs.despawn-on-end", d.mobs.despawnOnEnd()),
            r.spawnMode("mobs.spawn-mode", d.mobs.spawnMode()),
            minD, maxD,
            r.integer("mobs.drop-height", d.mobs.dropHeight(), 0, 120),
            r.integer("mobs.slow-falling-seconds", d.mobs.slowFallingSeconds(), 0, 120),
            r.bool("mobs.glowing", d.mobs.glowing()));
        final Effects effects = new Effects(
            r.bool("effects.particles", d.effects.particles()),
            r.bool("effects.sounds", d.effects.sounds()),
            r.bool("effects.thunder", d.effects.thunder()),
            r.bool("effects.ambient-particles", d.effects.ambientParticles()),
            r.integer("effects.trail-ticks", d.effects.trailTicks(), 0, 400));
        final Messages m = d.messages;
        final Messages messages = new Messages(
            r.str("messages.usage", m.usage()),
            r.str("messages.given", m.given()),
            r.str("messages.received", m.received()),
            r.str("messages.player-not-found", m.playerNotFound()),
            r.str("messages.world-not-found", m.worldNotFound()),
            r.str("messages.reloaded", m.reloaded()),
            r.str("messages.cast", m.cast()),
            r.str("messages.started", m.started()),
            r.str("messages.cooldown", m.cooldown()),
            r.str("messages.too-many", m.tooMany()),
            r.str("messages.too-close", m.tooClose()),
            r.str("messages.stopped", m.stopped()),
            r.str("messages.list-empty", m.listEmpty()),
            r.str("messages.list", m.list()));
        return new Settings(r.str("prefix", d.prefix), item, event, riftYaw, times, env, mobs, effects, messages);
    }

    private static Rift yaw(final ConfigurationSection c, final Rift rift, final Consumer<String> warn) {
        final Object raw = c.get("rift.yaw");
        if (raw == null) {
            return rift;
        }
        final String v = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "random", "" -> {
                return new Rift(rift.height(), rift.size(), YawMode.RANDOM, 0);
            }
            case "player" -> {
                return new Rift(rift.height(), rift.size(), YawMode.PLAYER, 0);
            }
            default -> {
                try {
                    final double deg = Double.parseDouble(v);
                    if (Double.isFinite(deg)) {
                        return new Rift(rift.height(), rift.size(), YawMode.FIXED, ((deg % 360) + 360) % 360);
                    }
                } catch (final NumberFormatException ignored) {
                    // ниже — предупреждение
                }
                warn.accept("rift.yaw: «" + raw + "» — не random, player и не число; беру random");
                return rift;
            }
        }
    }

    /** Чтение значений с проверкой. */
    private record Reader(ConfigurationSection c, Consumer<String> warn) {

        String str(final String key, final String def) {
            if (!this.c.contains(key)) {
                return def;
            }
            final Object v = this.c.get(key);
            if (v instanceof String || v instanceof Number || v instanceof Boolean) {
                return String.valueOf(v);
            }
            this.warn.accept(key + ": ожидается строка");
            return def;
        }

        List<String> strList(final String key, final List<String> def) {
            if (!this.c.contains(key)) {
                return def;
            }
            if (this.c.isList(key)) {
                return List.copyOf(this.c.getStringList(key));
            }
            if (this.c.isString(key)) {
                return List.of(this.c.getString(key, ""));
            }
            this.warn.accept(key + ": ожидается список строк");
            return def;
        }

        boolean bool(final String key, final boolean def) {
            if (!this.c.contains(key)) {
                return def;
            }
            final Object v = this.c.get(key);
            if (v instanceof Boolean b) {
                return b;
            }
            final String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
            if (s.equals("true") || s.equals("on") || s.equals("yes")) {
                return true;
            }
            if (s.equals("false") || s.equals("off") || s.equals("no")) {
                return false;
            }
            this.warn.accept(key + ": ожидается true/false, беру " + def);
            return def;
        }

        double dbl(final String key, final double def, final double min, final double max) {
            if (!this.c.contains(key)) {
                return def;
            }
            final Object v = this.c.get(key);
            final double x;
            if (v instanceof Number n) {
                x = n.doubleValue();
            } else {
                try {
                    x = Double.parseDouble(String.valueOf(v).trim());
                } catch (final NumberFormatException e) {
                    this.warn.accept(key + ": «" + v + "» — не число, беру " + def);
                    return def;
                }
            }
            if (!Double.isFinite(x) || x < min || x > max) {
                this.warn.accept(key + ": " + v + " вне " + fmt(min) + ".." + fmt(max) + ", беру " + fmt(def));
                return def;
            }
            return x;
        }

        int integer(final String key, final int def, final int min, final int max) {
            final double x = this.dbl(key, def, min, max);
            if (x != Math.rint(x)) {
                this.warn.accept(key + ": ожидается целое, беру " + def);
                return def;
            }
            return (int) x;
        }

        /** Число "3" или диапазон "2-4". */
        int[] range(final String key, final int defMin, final int defMax, final int min, final int max) {
            if (!this.c.contains(key)) {
                return new int[] {defMin, defMax};
            }
            final String s = String.valueOf(this.c.get(key)).trim();
            final int[] parsed = parseRange(s);
            if (parsed == null || parsed[0] < min || parsed[1] > max || parsed[0] > parsed[1]) {
                this.warn.accept(key + ": «" + s + "» — ожидается число или диапазон «a-b» в " + min + ".." + max
                    + ", беру " + defMin + "-" + defMax);
                return new int[] {defMin, defMax};
            }
            return parsed;
        }

        Map<String, Integer> weights(final String key, final Map<String, Integer> def) {
            if (!this.c.contains(key)) {
                return def;
            }
            final Map<String, Integer> out = new LinkedHashMap<>();
            if (this.c.isConfigurationSection(key)) {
                final ConfigurationSection s = this.c.getConfigurationSection(key);
                for (final String id : s.getKeys(false)) {
                    final Object v = s.get(id);
                    int w = -1;
                    if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
                        w = n.intValue();
                    }
                    if (!validId(id) || w < 0 || w > 10000) {
                        this.warn.accept(key + "." + id + ": ожидается вес 0..10000 у id из [a-z0-9_-], пропускаю");
                        continue;
                    }
                    if (w > 0) {
                        out.put(id, w);
                    }
                }
            } else if (this.c.isList(key)) {
                for (final String id : this.c.getStringList(key)) {
                    if (validId(id.trim())) {
                        out.put(id.trim(), 1);
                    } else {
                        this.warn.accept(key + ": «" + id + "» — не id моба, пропускаю");
                    }
                }
            } else {
                this.warn.accept(key + ": ожидается раздел «id: вес» или список id");
                return def;
            }
            if (out.isEmpty()) {
                this.warn.accept(key + ": список пуст — мобы из разлома не появятся");
            }
            return java.util.Collections.unmodifiableMap(out);
        }

        String command(final String key, final String def) {
            final String s = this.str(key, def).trim();
            final String cmd = s.startsWith("/") ? s.substring(1) : s;
            if (cmd.isEmpty() || !cmd.contains("{id}")) {
                this.warn.accept(key + ": команда должна содержать {id}, беру «" + def + "»");
                return def;
            }
            return cmd;
        }

        SpawnMode spawnMode(final String key, final SpawnMode def) {
            if (!this.c.contains(key)) {
                return def;
            }
            final String s = String.valueOf(this.c.get(key)).trim().toLowerCase(Locale.ROOT);
            return switch (s) {
                case "near-players", "near_players", "players" -> SpawnMode.NEAR_PLAYERS;
                case "under-rift", "under_rift", "rift" -> SpawnMode.UNDER_RIFT;
                default -> {
                    this.warn.accept(key + ": «" + s + "» — ожидается near-players или under-rift");
                    yield def;
                }
            };
        }
    }

    /** "3" → [3, 3], "2-4" → [2, 4], иначе null. */
    static int[] parseRange(final String s) {
        final String t = s.replace(" ", "");
        try {
            final int dash = t.indexOf('-', 1);
            if (dash < 0) {
                final int v = Integer.parseInt(t);
                return new int[] {v, v};
            }
            return new int[] {Integer.parseInt(t.substring(0, dash)), Integer.parseInt(t.substring(dash + 1))};
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    static boolean validId(final String id) {
        return id != null && id.matches("[a-z0-9_\\-]{1,64}");
    }

    private static String fmt(final double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    /** Список id через запятую (для журнала). */
    public List<String> mobIds() {
        return new ArrayList<>(this.mobs.list().keySet());
    }
}
