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
 */
public record Settings(String prefix, String itemName, List<String> itemLore, String itemModel, boolean consume,
                       long cooldownTicks, long lifetimeTicks, int distance, int groundSearch, int maxPerPlayer,
                       boolean ownerOnly, long immunityTicks, String frameBlock, float viewRange,
                       TeleportAction teleport, boolean particles, boolean sounds, Messages messages) {

    /** Тексты игрокам (MiniMessage). */
    public record Messages(String noRoom, String cooldown, String tooMany, String given, String received,
                           String reloaded, String closed, String notOwner, String entered, String playerNotFound,
                           String list, String listEmpty, String usage) {

        public static final Messages DEFAULTS = new Messages(
            "<red>Здесь не открыть портал: нужен свободный проём 2×3 на твёрдой земле в 2–3 блоках перед вами.",
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
            "<gray>/portals give [игрок] [кол-во] | reload | close | list");

        static Messages from(final ConfigurationSection s) {
            final Messages d = DEFAULTS;
            if (s == null) {
                return d;
            }
            return new Messages(s.getString("no-room", d.noRoom()), s.getString("cooldown", d.cooldown()),
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
        TeleportAction.DEFAULT, true, true, Messages.DEFAULTS);

    public static Settings from(final ConfigurationSection c, final Consumer<String> warn) {
        final Settings d = DEFAULTS;
        final String model = c.getString("item.model", d.itemModel()).strip();
        String itemModel = model;
        if (!model.isEmpty() && !model.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            warn.accept("item.model: «" + model + "» — не ключ вида namespace:path, иконка не меняется");
            itemModel = "";
        }
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

        final TeleportAction.RunAs runAs = TeleportAction.parseRunAs(c.getString("teleport.run-as"), warn);
        final List<String> commands = c.isList("teleport.commands")
            ? c.getStringList("teleport.commands") : d.teleport().commands();
        if (commands.isEmpty()) {
            warn.accept("teleport.commands пуст: портал никуда не ведёт");
        }
        final List<String> grant = c.isList("teleport.grant-permissions")
            ? c.getStringList("teleport.grant-permissions") : d.teleport().grantPermissions();
        if (runAs == TeleportAction.RunAs.CONSOLE && commands.stream().anyMatch(s -> s.strip().matches("/?warp( .*)?"))) {
            warn.accept("teleport: /warp AmazakeCore работает только от имени игрока — с run-as: console он не сработает");
        }

        return new Settings(c.getString("prefix", d.prefix()), c.getString("item.name", d.itemName()), lore,
            itemModel, c.getBoolean("item.consume", d.consume()), cooldown, lifetime, distance, search, max,
            ownerOnly, immunity, frameBlock, (float) view, new TeleportAction(runAs, commands, grant),
            c.getBoolean("effects.particles", d.particles()), c.getBoolean("effects.sounds", d.sounds()),
            Messages.from(c.getConfigurationSection("messages")));
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
