package core.amazake.systems.coreportals;

import core.amazake.systems.coreportals.geo.RiftPlacement;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Неизменяемый снимок config.yml. Разбор отдельно от Bukkit-логики, чтобы проверять его юнит-тестами.
 *
 * @param prefix            префикс сообщений (MiniMessage)
 * @param itemName          имя предмета (MiniMessage)
 * @param itemLore          описание предмета (MiniMessage)
 * @param itemModel         item_model иконки (пусто — обычная смола)
 * @param consume           тратить предмет при использовании (в творческом режиме не тратится)
 * @param cooldownTicks     задержка между использованиями одним игроком
 * @param lifetimeTicks     срок жизни портала от появления до исчезновения
 * @param distance          на сколько блоков впереди игрока ставится портал
 * @param groundSearch      на сколько блоков вверх и вниз искать землю
 * @param maxPerPlayer      сколько порталов одного игрока могут стоять одновременно (0 — без ограничения)
 * @param ownerOnly         входить может только открывший
 * @param immunityTicks     после входа портал не трогает игрока столько тиков
 * @param frameBlock        блок тёмной рамы (имя Material)
 * @param viewRange         дальность видимости дисплеев (множитель, 1.0 ≈ 64 блока)
 * @param teleport          действие при входе
 * @param particles         частицы
 * @param sounds            звуки
 * @param messages          тексты
 * @param emerald           «Изумрудный портал»: свои предмет, срок жизни, расстояние и (по желанию) действие
 * @param lapis             «Лазуритовый портал»: свои предмет, срок жизни, расстояние, размер разлома, эффекты на
 *                          экране, затягивание и (по желанию) действие
 */
public record Settings(String prefix, String itemName, List<String> itemLore, String itemModel, boolean consume,
                       long cooldownTicks, long lifetimeTicks, int distance, int groundSearch, int maxPerPlayer,
                       boolean ownerOnly, long immunityTicks, String frameBlock, float viewRange,
                       TeleportAction teleport, boolean particles, boolean sounds, Messages messages,
                       Emerald emerald, Lapis lapis) {

    /**
     * Раздел {@code lapis:} config.yml — «Лазуритовый портал» (разлом). Ключи, которых там нет, берутся из общих
     * ({@code portal.lifetime-seconds}, {@code portal.distance}, {@code teleport.*}).
     *
     * @param itemName      имя предмета (MiniMessage)
     * @param itemLore      описание предмета (MiniMessage)
     * @param itemModel     item_model иконки (пусто — обычный лазурит)
     * @param lifetimeTicks срок жизни разлома
     * @param distance      на сколько рядов блоков впереди игрока ставится разлом
     * @param size          ширина, высота и высота центра над землёй
     * @param radius        радиус «ауры», блоков (от прямоугольника разлома): свечение и трещины на экране,
     *                      затягивание; 0 — без эффектов на экране и без затягивания
     * @param screenGlow    свечение по краям экрана у игроков в радиусе
     * @param screenCracks  трещины на экране у игроков в радиусе, пока разлом раскрывается
     * @param pullStrength  сила затягивания, блоков/тик за толчок вплотную к разлому (0 — без затягивания)
     * @param pullMaxSpeed  скорость к разлому, выше которой затягивание не разгоняет, блоков/тик
     * @param teleport      своё действие при входе; {@code null} — общее {@code teleport}
     */
    public record Lapis(String itemName, List<String> itemLore, String itemModel, long lifetimeTicks, int distance,
                        RiftPlacement.Size size, double radius,
                        boolean screenGlow, boolean screenCracks, double pullStrength, double pullMaxSpeed,
                        TeleportAction teleport) {

        public static final Lapis DEFAULTS = new Lapis(
            "<!i><gradient:#0a1f8f:#1a4cff:#9fe8ff:#1a4cff:#0a1f8f>Лазуритовый портал</gradient>",
            List.of("<!i><gray>В синей глубине камня спит разлом.",
                "<!i><gray>ПКМ — разорвать пространство перед собой."),
            "", 600L, 3, RiftPlacement.Size.DEFAULT, 16.0, true, true,
            0.15, 0.25, null);

        public Lapis {
            itemLore = List.copyOf(itemLore);
        }
    }

    /**
     * Раздел {@code emerald:} config.yml. Ключи, которых там нет, берутся из общих ({@code portal.*},
     * {@code teleport.*}).
     *
     * @param itemName      имя предмета (MiniMessage)
     * @param itemLore      описание предмета (MiniMessage)
     * @param itemModel     item_model иконки (пусто — обычный изумруд)
     * @param lifetimeTicks срок жизни портала
     * @param distance      на сколько блоков впереди игрока ставится кольцо
     * @param teleport      своё действие при входе; {@code null} — общее {@code teleport}
     */
    public record Emerald(String itemName, List<String> itemLore, String itemModel, long lifetimeTicks, int distance,
                          TeleportAction teleport) {

        public static final Emerald DEFAULTS = new Emerald(
            "<!i><gradient:#0a7a35:#2bea7f:#b4ffd4:#2bea7f:#0a7a35>Изумрудный портал</gradient>",
            List.of("<!i><gray>В глубине камня кружит крошечная бездна со звёздами.",
                "<!i><gray>ПКМ — открыть портал перед собой."),
            "", 600L, 3, null);

        public Emerald {
            itemLore = List.copyOf(itemLore);
        }
    }

    /** Действие при входе в портал этого вида. */
    public TeleportAction teleportFor(final PortalType type) {
        if (type == PortalType.EMERALD && this.emerald.teleport() != null) {
            return this.emerald.teleport();
        }
        if (type == PortalType.LAPIS && this.lapis.teleport() != null) {
            return this.lapis.teleport();
        }
        return this.teleport;
    }

    /** Срок жизни портала этого вида, тиков. */
    public long lifetimeFor(final PortalType type) {
        return switch (type) {
            case EMERALD -> this.emerald.lifetimeTicks();
            case LAPIS -> this.lapis.lifetimeTicks();
            default -> this.lifetimeTicks;
        };
    }

    /** Расстояние до портала этого вида, рядов блоков. */
    public int distanceFor(final PortalType type) {
        return switch (type) {
            case EMERALD -> this.emerald.distance();
            case LAPIS -> this.lapis.distance();
            default -> this.distance;
        };
    }

    /** Тексты игрокам (MiniMessage). */
    public record Messages(String noRoom, String noRoomEmerald, String noRoomLapis, String cooldown, String tooMany,
                           String given, String received, String reloaded, String closed, String notOwner,
                           String entered, String playerNotFound, String list, String listEmpty, String usage) {

        public static final Messages DEFAULTS = new Messages(
            "<red>Здесь не открыть портал: нужен свободный проём 2×4 на твёрдой земле в 2–3 блоках перед вами.",
            "<red>Здесь не открыть изумрудный портал: нужно свободное место 3×4 над твёрдой землёй в 2–3 блоках перед вами.",
            "<red>Здесь не разорвать пространство: нужно почти свободное место <width>×<height> над твёрдой землёй в <distance> блоках перед вами.",
            "<red>Портал ещё не готов: подождите <yellow><seconds></yellow> с.",
            "<red>У вас уже открыт портал.",
            "<green>Выдано порталов: <white><amount></white> игроку <white><player></white>.",
            "<green>Вы получили портал (<white><amount></white>).",
            "<green>Настройки core-portals перечитаны.",
            "<yellow>Закрываю порталов: <white><count></white>.",
            "<red>Этот портал открыт не для вас.",
            "",
            "<red>Игрок <white><player></white> не в сети.",
            "<gold>Порталы (<count>):",
            "<gray>Открытых порталов нет.",
            "<gray>/portals give [игрок] [кол-во] [resin|emerald|lapis] | reload | close | list");

        static Messages from(final ConfigurationSection s) {
            final Messages d = DEFAULTS;
            if (s == null) {
                return d;
            }
            return new Messages(s.getString("no-room", d.noRoom()), s.getString("no-room-emerald", d.noRoomEmerald()),
                s.getString("no-room-lapis", d.noRoomLapis()), s.getString("cooldown", d.cooldown()),
                s.getString("too-many", d.tooMany()), s.getString("given", d.given()),
                s.getString("received", d.received()), s.getString("reloaded", d.reloaded()),
                s.getString("closed", d.closed()), s.getString("not-owner", d.notOwner()),
                s.getString("entered", d.entered()), s.getString("player-not-found", d.playerNotFound()),
                s.getString("list", d.list()), s.getString("list-empty", d.listEmpty()),
                s.getString("usage", d.usage()));
        }
    }

    public static final Settings DEFAULTS = new Settings(
        "<dark_gray>[<gradient:#ff2a12:#ff9a1f>Порталы</gradient>]</dark_gray> ",
        "<!i><gradient:#ff2a12:#ff9a1f>Портал</gradient>",
        List.of("<!i><gray>Застывшая смола древнего разлома.", "<!i><gray>ПКМ — открыть портал перед собой."),
        "", true, 100L, 600L, 3, 3, 1, false, 60L, "POLISHED_BLACKSTONE", 1.5f,
        TeleportAction.DEFAULT, true, true, Messages.DEFAULTS, Emerald.DEFAULTS, Lapis.DEFAULTS);

    public static Settings from(final ConfigurationSection c, final Consumer<String> warn) {
        final Settings d = DEFAULTS;
        final String itemModel = model(c, "item.model", d.itemModel(), warn);
        final List<String> lore = c.isList("item.lore") ? List.copyOf(c.getStringList("item.lore")) : d.itemLore();

        final long cooldown = seconds(c, "item.cooldown-seconds", d.cooldownTicks(), 0, 3600, warn);
        final long lifetime = seconds(c, "portal.lifetime-seconds", d.lifetimeTicks(), 6, 3600, warn);
        final int distance = range(c, "portal.distance", d.distance(), 1, 6, warn);
        final int search = range(c, "portal.ground-search", d.groundSearch(), 0, 8, warn);
        final int max = range(c, "portal.max-per-player", d.maxPerPlayer(), 0, 100, warn);
        final long immunity = seconds(c, "portal.immunity-seconds", d.immunityTicks(), 0, 600, warn);

        final String entryRaw = c.getString("portal.entry", "everyone").strip().toLowerCase(Locale.ROOT);
        final boolean ownerOnly;
        switch (entryRaw) {
            case "everyone", "all", "все" -> ownerOnly = false;
            case "owner", "владелец" -> ownerOnly = true;
            default -> {
                warn.accept("portal.entry: «" + entryRaw + "» — нужно everyone или owner, беру everyone");
                ownerOnly = false;
            }
        }
        String frameBlock = c.getString("portal.frame-block", d.frameBlock()).strip().toUpperCase(Locale.ROOT);
        if (!frameBlock.matches("[A-Z0-9_]+")) {
            warn.accept("portal.frame-block: «" + frameBlock + "» — не имя блока, беру " + d.frameBlock());
            frameBlock = d.frameBlock();
        }
        double view = c.getDouble("portal.view-range", d.viewRange());
        if (!(view > 0.05 && view <= 16)) {
            warn.accept("portal.view-range: " + view + " вне 0.05..16, беру " + d.viewRange());
            view = d.viewRange();
        }

        final TeleportAction teleport = teleport(c, "teleport", d.teleport(), warn);
        if (teleport.commands().isEmpty()) {
            warn.accept("teleport.commands пуст: портал никуда не ведёт");
        }
        final Emerald emerald = emerald(c, lifetime, distance, teleport, warn);
        final Lapis lapis = lapis(c, lifetime, distance, teleport, warn);
        return new Settings(c.getString("prefix", d.prefix()), c.getString("item.name", d.itemName()), lore,
            itemModel, c.getBoolean("item.consume", d.consume()), cooldown, lifetime, distance, search, max,
            ownerOnly, immunity, frameBlock, (float) view, teleport,
            c.getBoolean("effects.particles", d.particles()), c.getBoolean("effects.sounds", d.sounds()),
            Messages.from(c.getConfigurationSection("messages")), emerald, lapis);
    }

    /** Раздел {@code lapis:}: чего нет — берётся из общих настроек. */
    private static Lapis lapis(final ConfigurationSection c, final long lifetime, final int distance,
                               final TeleportAction shared, final Consumer<String> warn) {
        final Lapis d = Lapis.DEFAULTS;
        final List<String> lore = c.isList("lapis.item.lore") ? c.getStringList("lapis.item.lore") : d.itemLore();
        TeleportAction teleport = null;
        if (c.isConfigurationSection("lapis.teleport")) {
            teleport = teleport(c, "lapis.teleport", shared, warn);
            if (teleport.commands().isEmpty()) {
                warn.accept("lapis.teleport.commands пуст: лазуритовый портал никуда не ведёт");
            }
        }
        final var ds = d.size();
        final double width = real(c, "lapis.portal.width", ds.width(), 1, 16, warn);
        final double height = real(c, "lapis.portal.height", ds.height(), 1, 16, warn);
        // Центр по умолчанию: низ овала на 0,1 над землёй (как 9 × 13 с центром 6,6).
        final double defCenter = c.contains("lapis.portal.height") ? height / 2 + 0.1 : ds.centerUp();
        double center = real(c, "lapis.portal.center-up", defCenter, 0.5, 24, warn);
        if (center < height / 2 - 0.5) {
            warn.accept("lapis.portal.center-up: " + fmt(center) + " — низ разлома уходит в пол больше чем на 0,5 блока, беру "
                + fmt(height / 2 + 0.1));
            center = height / 2 + 0.1;
        }
        return new Lapis(c.getString("lapis.item.name", d.itemName()), lore,
            model(c, "lapis.item.model", d.itemModel(), warn),
            seconds(c, "lapis.portal.lifetime-seconds", lifetime, 6, 3600, warn),
            range(c, "lapis.portal.distance", distance, 1, 8, warn),
            new RiftPlacement.Size(width, height, center),
            real(c, "lapis.screen.radius", d.radius(), 0, 32, warn),
            c.getBoolean("lapis.screen.glow", d.screenGlow()), c.getBoolean("lapis.screen.cracks", d.screenCracks()),
            real(c, "lapis.pull.strength", d.pullStrength(), 0, 0.3, warn),
            real(c, "lapis.pull.max-speed", d.pullMaxSpeed(), 0.02, 1.0, warn), teleport);
    }

    private static double real(final ConfigurationSection c, final String path, final double def, final double min,
                               final double max, final Consumer<String> warn) {
        if (!c.contains(path)) {
            return def;
        }
        final double v = c.getDouble(path, def);
        if (!(v >= min && v <= max)) {
            warn.accept(path + ": " + v + " вне " + fmt(min) + ".." + fmt(max) + ", беру " + fmt(def));
            return def;
        }
        return v;
    }

    /** Раздел {@code emerald:}: чего нет — берётся из общих настроек. */
    private static Emerald emerald(final ConfigurationSection c, final long lifetime, final int distance,
                                   final TeleportAction shared, final Consumer<String> warn) {
        final Emerald d = Emerald.DEFAULTS;
        final List<String> lore = c.isList("emerald.item.lore") ? c.getStringList("emerald.item.lore") : d.itemLore();
        TeleportAction teleport = null;
        if (c.isConfigurationSection("emerald.teleport")) {
            teleport = teleport(c, "emerald.teleport", shared, warn);
            if (teleport.commands().isEmpty()) {
                warn.accept("emerald.teleport.commands пуст: изумрудный портал никуда не ведёт");
            }
        }
        return new Emerald(c.getString("emerald.item.name", d.itemName()), lore,
            model(c, "emerald.item.model", d.itemModel(), warn),
            seconds(c, "emerald.portal.lifetime-seconds", lifetime, 6, 3600, warn),
            range(c, "emerald.portal.distance", distance, 1, 6, warn), teleport);
    }

    /** Действие из раздела {@code path} ({@code teleport}, {@code emerald.teleport}, {@code lapis.teleport}); чего нет — из {@code base}. */
    private static TeleportAction teleport(final ConfigurationSection c, final String path, final TeleportAction base,
                                           final Consumer<String> warn) {
        final String rawRunAs = c.getString(path + ".run-as");
        final TeleportAction.RunAs runAs = rawRunAs == null ? base.runAs()
            : TeleportAction.parseRunAs(rawRunAs, w -> warn.accept(w.replaceFirst("^teleport", path)));
        final List<String> commands = c.isList(path + ".commands") ? c.getStringList(path + ".commands") : base.commands();
        final List<String> grant = c.isList(path + ".grant-permissions")
            ? c.getStringList(path + ".grant-permissions") : base.grantPermissions();
        if (runAs == TeleportAction.RunAs.CONSOLE && commands.stream().anyMatch(s -> s.strip().matches("/?warp( .*)?"))) {
            warn.accept(path + ": /warp AmazakeCore работает только от имени игрока — с run-as: console он не сработает");
        }
        return new TeleportAction(runAs, commands, grant);
    }

    private static String model(final ConfigurationSection c, final String path, final String def,
                                final Consumer<String> warn) {
        final String model = c.getString(path, def).strip();
        if (!model.isEmpty() && !model.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            warn.accept(path + ": «" + model + "» — не ключ вида namespace:path, иконка не меняется");
            return "";
        }
        return model;
    }

    private static long seconds(final ConfigurationSection c, final String path, final long defTicks,
                                final double min, final double max, final Consumer<String> warn) {
        if (!c.contains(path)) {
            return defTicks;
        }
        final double v = c.getDouble(path, defTicks / 20.0);
        if (!(v >= min && v <= max)) {
            warn.accept(path + ": " + v + " вне " + fmt(min) + ".." + fmt(max) + ", беру " + fmt(defTicks / 20.0));
            return defTicks;
        }
        return Math.round(v * 20);
    }

    private static int range(final ConfigurationSection c, final String path, final int def, final int min,
                             final int max, final Consumer<String> warn) {
        final int v = c.getInt(path, def);
        if (v < min || v > max) {
            warn.accept(path + ": " + v + " вне " + min + ".." + max + ", беру " + def);
            return def;
        }
        return v;
    }

    private static String fmt(final double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
