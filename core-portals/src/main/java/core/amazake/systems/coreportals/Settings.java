package core.amazake.systems.coreportals;

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
 */
public record Settings(String prefix, String itemName, List<String> itemLore, String itemModel, boolean consume,
                       long cooldownTicks, long lifetimeTicks, int distance, int groundSearch, int maxPerPlayer,
                       boolean ownerOnly, long immunityTicks, String frameBlock, float viewRange,
                       TeleportAction teleport, boolean particles, boolean sounds, Messages messages,
                       Emerald emerald) {

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
        return type == PortalType.EMERALD && this.emerald.teleport() != null ? this.emerald.teleport() : this.teleport;
    }

    /** Срок жизни портала этого вида, тиков. */
    public long lifetimeFor(final PortalType type) {
        return type == PortalType.EMERALD ? this.emerald.lifetimeTicks() : this.lifetimeTicks;
    }

    /** Расстояние до портала этого вида, рядов блоков. */
    public int distanceFor(final PortalType type) {
        return type == PortalType.EMERALD ? this.emerald.distance() : this.distance;
    }

    /** Тексты игрокам (MiniMessage). */
    public record Messages(String noRoom, String noRoomEmerald, String cooldown, String tooMany, String given, String received,
                           String reloaded, String closed, String notOwner, String entered, String playerNotFound,
                           String list, String listEmpty, String usage) {

        public static final Messages DEFAULTS = new Messages(
            "<red>Здесь не открыть портал: нужен свободный проём 2×4 на твёрдой земле в 2–3 блоках перед вами.",
            "<red>Здесь не открыть изумрудный портал: нужно свободное место 3×4 над твёрдой землёй в 2–3 блоках перед вами.",
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
            "<gray>/portals give [игрок] [кол-во] [resin|emerald] | reload | close | list");

        static Messages from(final ConfigurationSection s) {
            final Messages d = DEFAULTS;
            if (s == null) {
                return d;
            }
            return new Messages(s.getString("no-room", d.noRoom()), s.getString("no-room-emerald", d.noRoomEmerald()),
                s.getString("cooldown", d.cooldown()),
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
        TeleportAction.DEFAULT, true, true, Messages.DEFAULTS, Emerald.DEFAULTS);

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
        return new Settings(c.getString("prefix", d.prefix()), c.getString("item.name", d.itemName()), lore,
            itemModel, c.getBoolean("item.consume", d.consume()), cooldown, lifetime, distance, search, max,
            ownerOnly, immunity, frameBlock, (float) view, teleport,
            c.getBoolean("effects.particles", d.particles()), c.getBoolean("effects.sounds", d.sounds()),
            Messages.from(c.getConfigurationSection("messages")), emerald);
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

    /** Действие из раздела {@code path} ({@code teleport} или {@code emerald.teleport}); чего нет — из {@code base}. */
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
