package core.amazake.systems.corerpg.seals;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corerpg.util.Text;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /seals} (алиасы {@code seal}, {@code печати}). Игрокам без права: menu, list, info. Админам
 * ({@value SealsModule#ADMIN}): give, set, reload.
 */
final class SealsCommand extends Command {

    static final int PAGE_SIZE = 10;
    private static final List<String> PLAYER_SUBS = List.of("menu", "list", "info");
    private static final List<String> ADMIN_SUBS = List.of("give", "set", "reload");
    private static final List<String> TYPE_WORDS = List.of("оружие", "броня", "щит");
    private static final List<String> AMOUNTS = List.of("1", "8", "16", "32", "64");

    private final SealsModule module;

    SealsCommand(final SealsModule module) {
        super("seals", "Магические печати: меню мастера, каталог, печать на предмете", "/seals [menu|list|info]",
            List.of("seal", "печати"));
        this.module = module;
    }

    private SystemContext context() {
        return this.module.system().context();
    }

    private static boolean admin(final CommandSender sender) {
        return sender.hasPermission(SealsModule.ADMIN);
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        final String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu", "меню" -> this.menu(sender);
            case "list", "список" -> this.list(sender, label, args);
            case "info", "инфо" -> this.info(sender);
            case "give" -> {
                if (this.checkAdmin(sender)) {
                    this.give(sender, label, args);
                }
            }
            case "set" -> {
                if (this.checkAdmin(sender)) {
                    this.set(sender, label, args);
                }
            }
            case "reload" -> {
                if (this.checkAdmin(sender)) {
                    this.reload(sender);
                }
            }
            default -> this.usage(sender, label);
        }
        return true;
    }

    private boolean checkAdmin(final CommandSender sender) {
        if (admin(sender)) {
            return true;
        }
        Text.send(sender, "<red>Недостаточно прав.</red>");
        return false;
    }

    private void usage(final CommandSender sender, final String label) {
        final TagResolver l = Placeholder.unparsed("l", label);
        Text.send(sender, "<gold>Магические печати:</gold>");
        Text.send(sender, "<yellow>/<l> menu</yellow> <gray>— меню мастера зачарования</gray>", l);
        Text.send(sender, "<yellow>/<l> list [оружие|броня|щит] [класс] [страница]</yellow> <gray>— каталог печатей</gray>", l);
        Text.send(sender, "<yellow>/<l> info</yellow> <gray>— печать на предмете в руке</gray>", l);
        if (admin(sender)) {
            Text.send(sender, "<yellow>/<l> give <ник> <расходник|all|stones|wax> [1..64]</yellow> <gray>— выдать расходники</gray>", l);
            Text.send(sender, "<yellow>/<l> set <ник> <печать> <цвет></yellow> <gray>— печать на предмет в руке игрока</gray>", l);
            Text.send(sender, "<yellow>/<l> reload</yellow> <gray>— перечитать seals.yml</gray>", l);
        }
    }

    /** Ответ отправителю из чужого потока: игроку — в его потоке. */
    private void replyLater(final CommandSender sender, final String text, final TagResolver... resolvers) {
        if (sender instanceof final Player player) {
            this.context().runOnceForPlayer(player, () -> Text.send(player, text, resolvers));
        } else {
            Text.send(sender, text, resolvers);
        }
    }

    // --- menu ---

    private void menu(final CommandSender sender) {
        if (!(sender instanceof final Player player)) {
            Text.send(sender, "<red>Эта команда доступна только игроку.</red>");
            return;
        }
        this.module.openMenu(player);
    }

    // --- list ---

    /** Фильтры /seals list. */
    record ListQuery(String type, String classKey, int page, String error) {
    }

    /** Разбор аргументов list (после самого «list»). Чистая логика для тестов. */
    static ListQuery parseList(final List<String> tokens, final Collection<String> classKeys) {
        String type = null;
        String classKey = null;
        int page = 1;
        for (final String token : tokens) {
            final String t = token.toLowerCase(Locale.ROOT).trim().replace('ё', 'е');
            final String asType = typeOf(t);
            if (asType != null) {
                type = asType;
                continue;
            }
            if (t.matches("\\d{1,6}")) {
                page = Math.max(1, Integer.parseInt(t));
                continue;
            }
            if (classKeys.contains(t)) {
                classKey = t;
                continue;
            }
            return new ListQuery(null, null, 1, token);
        }
        return new ListQuery(type, classKey, page, null);
    }

    static String typeOf(final String word) {
        return switch (word) {
            case "оружие", "weapon" -> "WEAPON";
            case "броня", "armor" -> "ARMOR";
            case "щит", "shield" -> "SHIELD";
            default -> null;
        };
    }

    private static String typeNominative(final String type) {
        return switch (type) {
            case "WEAPON" -> "оружие";
            case "ARMOR" -> "броня";
            case "SHIELD" -> "щит";
            default -> type;
        };
    }

    static int pages(final int count) {
        return Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void list(final CommandSender sender, final String label, final String[] args) {
        final SealCatalog catalog = this.module.catalog();
        final List<String> tokens = args.length > 1 ? List.of(args).subList(1, args.length) : List.of();
        final ListQuery q = parseList(tokens, catalog.classKeys());
        if (q.error() != null) {
            Text.send(sender, "<red>Не понял «<arg>»: ожидается оружие, броня, щит, класс ("
                + String.join(", ", catalog.classKeys()) + ") или номер страницы.</red>", Placeholder.unparsed("arg", q.error()));
            return;
        }
        final List<SealDefinition> found = new ArrayList<>();
        for (final SealDefinition seal : catalog.all()) {
            if ((q.type() == null || seal.accepts(q.type())) && (q.classKey() == null || seal.fitsClass(q.classKey(), null))) {
                found.add(seal);
            }
        }
        if (found.isEmpty()) {
            Text.send(sender, "<yellow>Печатей с такими фильтрами нет.</yellow>");
            return;
        }
        final int pages = pages(found.size());
        final int page = Math.min(q.page(), pages);
        final StringJoiner filter = new StringJoiner(", ");
        if (q.type() != null) {
            filter.add(typeNominative(q.type()));
        }
        if (q.classKey() != null) {
            filter.add(q.classKey());
        }
        Text.send(sender, "<gold>Печати" + (filter.length() > 0 ? " (<filter>)" : "") + ": <white>" + found.size()
                + "</white>, страница <white>" + page + "/" + pages + "</white>.</gold> <gray>Наведите на печать — бонусы.</gray>",
            Placeholder.unparsed("filter", filter.toString()));
        for (final SealDefinition seal : found.subList((page - 1) * PAGE_SIZE, Math.min(found.size(), page * PAGE_SIZE))) {
            sender.sendMessage(this.listLine(seal));
        }
        if (page < pages) {
            final StringBuilder next = new StringBuilder("/").append(label).append(" list");
            if (q.type() != null) {
                next.append(' ').append(typeNominative(q.type()));
            }
            if (q.classKey() != null) {
                next.append(' ').append(q.classKey());
            }
            next.append(' ').append(page + 1);
            final String cmd = next.toString();
            sender.sendMessage(Text.mm(Text.prefix() + "<gray>Дальше: </gray>")
                .append(Text.mm("<yellow><cmd></yellow>", Placeholder.unparsed("cmd", cmd))
                    .clickEvent(ClickEvent.runCommand(cmd))));
        }
    }

    private Component listLine(final SealDefinition seal) {
        final StringJoiner types = new StringJoiner(", ");
        for (final String type : List.of("WEAPON", "ARMOR", "SHIELD")) {
            if (seal.accepts(type)) {
                types.add(typeNominative(type));
            }
        }
        final String typesText = types.length() == 0 ? "нет слота" : types.toString();
        final Component line = Text.mm("<gray>• <white><id></white> — <gold><name></gold> <dark_gray>(<types>)</dark_gray>"
                + (seal.enabled() ? "" : " <dark_gray>[не выпадает]</dark_gray>"),
            Placeholder.unparsed("id", seal.id()), Placeholder.unparsed("name", seal.name()),
            Placeholder.unparsed("types", typesText));
        return line.hoverEvent(HoverEvent.showText(hover(seal, typesText)));
    }

    private static Component hover(final SealDefinition seal, final String typesText) {
        Component out = Text.mm("<gold>Печать <name></gold>", Placeholder.unparsed("name", seal.name()))
            .append(Component.newline())
            .append(Text.mm("<gray>Предметы: <white><types></white>", Placeholder.unparsed("types", typesText)))
            .append(Component.newline())
            .append(Text.mm("<gray>Классы: <white><classes></white>",
                Placeholder.unparsed("classes", seal.forAnyClass() ? "любые" : String.join(", ", seal.classes()))));
        if (seal.bonuses().isEmpty()) {
            return out.append(Component.newline()).append(Text.mm("<dark_gray>Бонусов нет"));
        }
        out = out.append(Component.newline())
            .append(Text.mm("<dark_gray>Зелёная / синяя / фиолетовая / оранжевая:"));
        for (final Map.Entry<String, SealDefinition.Values> e : seal.bonuses().entrySet()) {
            final String key = e.getKey();
            final StringBuilder values = new StringBuilder();
            for (final SealColor color : SealColor.values()) {
                if (color != SealColor.GREEN) {
                    values.append(" <dark_gray>/</dark_gray> ");
                }
                final double v = e.getValue().get(color);
                values.append('<').append(color.tag()).append('>')
                    .append(Double.isNaN(v) ? "—" : Text.escape(BonusFormat.value(key, v)))
                    .append("</").append(color.tag()).append('>');
            }
            out = out.append(Component.newline()).append(Text.mm("<gray><label>: " + values
                    + (BonusFormat.inactive(key) ? " <dark_gray>(пока не действует)</dark_gray>" : ""),
                Placeholder.unparsed("label", BonusFormat.label(key))));
        }
        return out;
    }

    // --- info ---

    private void info(final CommandSender sender) {
        if (!(sender instanceof final Player player)) {
            Text.send(sender, "<red>Только для игрока.</red>");
            return;
        }
        final ItemStack item = this.module.mainHand(player);
        final SealItems.SealData data = this.module.items().read(item);
        if (data == null || data.id() == null || data.color() == null) {
            Text.send(player, "<yellow>В основной руке нет предмета с печатью.</yellow>");
            return;
        }
        final SealDefinition seal = this.module.catalog().get(data.id());
        if (seal == null) {
            Text.send(player, "<yellow>Печать «<id>» отсутствует в каталоге seals.yml.</yellow>",
                Placeholder.unparsed("id", data.id()));
            return;
        }
        final SealColor color = data.color();
        Text.send(player, "<green>Печать <white><name></white>, цвет: <" + color.tag() + ">" + color.russian()
            + "</" + color.tag() + ">.</green>", Placeholder.unparsed("name", seal.name()));
        for (final Component line : SealItems.bonusLines(seal, color)) {
            player.sendMessage(Text.mm(Text.prefix()).append(line));
        }
        if (data.boundTo() != null) {
            Text.send(player, data.boundTo().equals(player.getUniqueId().toString())
                ? "<red>Персональный предмет</red> <gray>(привязан к вам).</gray>"
                : "<red>Персональный предмет другого игрока.</red>");
        }
    }

    // --- give ---

    private void give(final CommandSender sender, final String label, final String[] args) {
        if (args.length < 3 || args.length > 4) {
            Text.send(sender, "<yellow>Использование: /<l> give <ник> <расходник|all|stones|wax> [1..64]</yellow>",
                Placeholder.unparsed("l", label));
            Text.send(sender, "<gray>Расходники: <ids>.</gray>",
                Placeholder.unparsed("ids", String.join(", ", Consumable.completions())));
            return;
        }
        final Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Text.send(sender, "<red>Игрок <name> должен быть в сети.</red>", Placeholder.unparsed("name", args[1]));
            return;
        }
        final List<Consumable> list = Consumable.resolve(args[2]);
        if (list.isEmpty()) {
            Text.send(sender, "<red>Неизвестный расходник «<arg>». Допустимо: <ids>.</red>",
                Placeholder.unparsed("arg", args[2]), Placeholder.unparsed("ids", String.join(", ", Consumable.completions())));
            return;
        }
        final Integer amount = parseAmount(args.length == 4 ? args[3] : "1");
        if (amount == null) {
            Text.send(sender, "<red>Количество должно быть числом от 1 до 64.</red>");
            return;
        }
        final SealSettings settings = this.module.settings();
        final StringJoiner names = new StringJoiner(", ");
        for (final Consumable c : list) {
            names.add(Text.plain(Text.mm(settings.look(c).name())));
        }
        final TagResolver resolver = TagResolver.resolver(Placeholder.unparsed("player", target.getName()),
            Placeholder.unparsed("what", names.toString()), Placeholder.unparsed("n", String.valueOf(amount)));
        final boolean queued = this.context().runOnceForPlayer(target, () -> {
            this.module.give(target, list, amount);
            Text.send(target, "<green>Вы получили: <white><what></white> × <n>.</green>", resolver);
            if (sender != target) {
                this.replyLater(sender, "<green>Выдано игроку <player>: <white><what></white> × <n>.</green>", resolver);
            }
        });
        if (!queued) {
            Text.send(sender, "<red>Игрок <name> должен быть в сети.</red>", Placeholder.unparsed("name", args[1]));
        }
    }

    /** 1..64 или null. */
    static Integer parseAmount(final String raw) {
        try {
            final int n = Integer.parseInt(raw.trim());
            return n >= 1 && n <= 64 ? n : null;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    // --- set ---

    private void set(final CommandSender sender, final String label, final String[] args) {
        if (args.length != 4) {
            Text.send(sender, "<yellow>Использование: /<l> set <ник> <печать> <green|blue|purple|orange></yellow>",
                Placeholder.unparsed("l", label));
            return;
        }
        final Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Text.send(sender, "<red>Игрок <name> должен быть в сети.</red>", Placeholder.unparsed("name", args[1]));
            return;
        }
        final SealDefinition seal = this.module.catalog().get(args[2]);
        if (seal == null) {
            Text.send(sender, "<red>Неизвестная печать «<arg>». Список: /<l> list</red>",
                Placeholder.unparsed("arg", args[2]), Placeholder.unparsed("l", label));
            return;
        }
        final SealColor color = SealColor.parse(args[3]);
        if (color == null) {
            Text.send(sender, "<red>Неизвестный цвет «<arg>»: green, blue, purple, orange или зелёная, синяя, фиолетовая, оранжевая.</red>",
                Placeholder.unparsed("arg", args[3]));
            return;
        }
        final boolean queued = this.context().runOnceForPlayer(target, () -> {
            final SealsModule.Reply reply = this.module.setSeal(target, seal, color);
            if (sender == target) {
                reply.send(target);
            } else {
                this.replyLater(sender, reply.text(), reply.resolver());
            }
        });
        if (!queued) {
            Text.send(sender, "<red>Игрок <name> должен быть в сети.</red>", Placeholder.unparsed("name", args[1]));
        }
    }

    // --- reload ---

    private void reload(final CommandSender sender) {
        final SystemContext context = this.context();
        Text.send(sender, "<gray>Перечитываю seals.yml…</gray>");
        // Файл читается вне тика; снимки подменяются атомарно.
        Bukkit.getAsyncScheduler().runNow(context.plugin(), task -> {
            try {
                this.module.load(context);
            } catch (final RuntimeException e) {
                context.logger().log(Level.WARNING, "Не удалось перечитать seals.yml", e);
                this.replyLater(sender, "<red>Не удалось перечитать seals.yml: <error>. Остались прежние настройки.</red>",
                    Placeholder.unparsed("error", String.valueOf(e.getMessage())));
                return;
            }
            for (final Player player : Bukkit.getOnlinePlayers()) {
                this.module.system().aspects().refreshLater(player);
            }
            this.replyLater(sender, "<green>seals.yml перечитан: печатей в каталоге <white>" + this.module.catalogSize()
                + "</white>, выпадают <white>" + this.module.enabledCount() + "</white>.</green>");
        });
    }

    // --- автодополнение ---

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        final boolean admin = admin(sender);
        if (args.length == 1) {
            final List<String> subs = new ArrayList<>(PLAYER_SUBS);
            if (admin) {
                subs.addAll(ADMIN_SUBS);
            }
            return partial(subs, args[0]);
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        final String last = args[args.length - 1];
        switch (sub) {
            case "list", "список" -> {
                final List<String> options = new ArrayList<>(TYPE_WORDS);
                options.addAll(this.module.catalog().classKeys());
                for (int i = 1; i <= Math.min(pages(this.module.catalogSize()), 15); i++) {
                    options.add(String.valueOf(i));
                }
                return partial(options, last);
            }
            case "give" -> {
                if (!admin) {
                    return List.of();
                }
                return switch (args.length) {
                    case 2 -> partial(onlineNames(), last);
                    case 3 -> partial(Consumable.completions(), last);
                    case 4 -> partial(AMOUNTS, last);
                    default -> List.of();
                };
            }
            case "set" -> {
                if (!admin) {
                    return List.of();
                }
                return switch (args.length) {
                    case 2 -> partial(onlineNames(), last);
                    case 3 -> partial(this.module.catalog().all().stream().map(SealDefinition::id).toList(), last);
                    case 4 -> partial(List.of("green", "blue", "purple", "orange", "зелёная", "синяя", "фиолетовая", "оранжевая"), last);
                    default -> List.of();
                };
            }
            default -> {
                return List.of();
            }
        }
    }

    private static List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    static List<String> partial(final Collection<String> options, final String token) {
        final String t = token.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(t)).distinct().toList();
    }
}
