package core.amazake.systems.corerpg.progression.command;

import core.amazake.systems.corerpg.progression.ClassCatalog;
import core.amazake.systems.corerpg.progression.ProgressionModule;
import core.amazake.systems.corerpg.util.Text;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /class} (алиас {@code класс}): без класса — меню выбора, с классом — меню персонажа; {@code choose <класс>},
 * {@code info}; {@code admin reset|setlevel|giveexp|set|info|reload} — право {@link ProgressionModule#ADMIN_PERMISSION}.
 * Команда игрока выполняется в его потоке, консоли — в глобальном.
 */
public final class ClassCommand extends Command {

    private static final List<String> ADMIN_SUBS = List.of("reset", "setlevel", "giveexp", "set", "info", "reload");

    private final ProgressionModule module;

    public ClassCommand(final ProgressionModule module) {
        super("class", "Класс, персонаж и админ-команды", "/class [choose <класс>|info|admin]", List.of("класс"));
        this.module = module;
    }

    @Override
    public boolean execute(final @NotNull CommandSender sender, final @NotNull String label, final @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof final Player player)) {
                this.help(sender);
                return true;
            }
            if (!this.module.isLoaded(player)) {
                this.module.send(player, "profile-loading");
            } else if (this.module.profile(player).hasClass()) {
                this.module.openCharacterMenu(player);
            } else {
                this.module.openClassMenu(player);
            }
            return true;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "choose", "выбрать" -> this.choose(sender, Arrays.copyOfRange(args, 1, args.length));
            case "info", "инфо" -> {
                if (sender instanceof final Player player) {
                    player.sendMessage(this.module.selfInfo(player));
                } else {
                    this.module.send(sender, "only-players");
                }
            }
            case "menu", "меню" -> {
                if (sender instanceof final Player player) {
                    this.module.openCharacterMenu(player);
                } else {
                    this.module.send(sender, "only-players");
                }
            }
            case "admin", "админ" -> this.admin(sender, Arrays.copyOfRange(args, 1, args.length));
            case "help", "помощь" -> this.help(sender);
            default -> {
                // «/class мечник» — тоже выбор класса.
                if (this.module.catalog().findClass(String.join(" ", args)) != null) {
                    this.choose(sender, args);
                } else {
                    this.help(sender);
                }
            }
        }
        return true;
    }

    private void choose(final CommandSender sender, final String[] rest) {
        if (!(sender instanceof final Player player)) {
            this.module.send(sender, "only-players");
            return;
        }
        if (rest.length == 0) {
            this.module.openClassMenu(player);
            return;
        }
        final String input = String.join(" ", rest);
        final ClassCatalog.RpgClass clazz = this.module.catalog().findClass(input);
        if (clazz == null) {
            this.module.send(player, "class-unknown", Placeholder.unparsed("input", input),
                Placeholder.unparsed("list", this.module.classList()));
            return;
        }
        this.module.chooseClass(player, clazz);
    }

    private void help(final CommandSender sender) {
        final StringBuilder sb = new StringBuilder();
        sb.append("<gold>═══ Класс ═══</gold>\n");
        sb.append("<yellow>/class</yellow> <gray>— выбор класса или меню персонажа</gray>\n");
        sb.append("<yellow>/class choose <класс></yellow> <gray>— выбрать класс (один раз)</gray>\n");
        sb.append("<yellow>/class info</yellow> <gray>— кратко о персонаже</gray>\n");
        sb.append("<yellow>/profession [имя]</yellow> <gray>— выбор профессии с ").append(this.module.professionLevel())
            .append(" уровня</gray>\n");
        sb.append("<yellow>/stats</yellow> <gray>— распределение очков параметров</gray>");
        if (sender.hasPermission(ProgressionModule.ADMIN_PERMISSION)) {
            sb.append('\n').append(adminHelp(this.module.maxLevel()));
        }
        sender.sendMessage(Text.mm(sb.toString()));
    }

    private static String adminHelp(final int max) {
        return "<gold>═══ Админ ═══</gold>\n"
            + "<yellow>/class admin reset <ник> class|profession|stats|all</yellow> <gray>— сброс</gray>\n"
            + "<yellow>/class admin setlevel <ник> <1.." + max + "></yellow> <gray>— установить уровень</gray>\n"
            + "<yellow>/class admin giveexp <ник> <опыт></yellow> <gray>— выдать опыт</gray>\n"
            + "<yellow>/class admin set <ник> class|profession <ключ></yellow> <gray>— назначить класс или профессию</gray>\n"
            + "<yellow>/class admin info <ник></yellow> <gray>— сведения об игроке</gray>\n"
            + "<yellow>/class admin reload</yellow> <gray>— перечитать настройки всей системы</gray>";
    }

    // --- админ ---

    private void admin(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission(ProgressionModule.ADMIN_PERMISSION)) {
            this.module.send(sender, "no-permission");
            return;
        }
        if (args.length == 0) {
            sender.sendMessage(Text.mm(adminHelp(this.module.maxLevel())));
            return;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            this.module.adminReload(sender);
            return;
        }
        if (!ADMIN_SUBS.contains(sub)) {
            sender.sendMessage(Text.mm(adminHelp(this.module.maxLevel())));
            return;
        }
        if (args.length < 2) {
            this.usage(sender, sub);
            return;
        }
        final Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            this.module.send(sender, "player-not-found", Placeholder.unparsed("input", args[1]));
            return;
        }
        switch (sub) {
            case "info" -> this.module.reply(sender, this.module.adminInfo(target));
            case "reset" -> {
                final ProgressionModule.ResetType type = args.length >= 3 ? ProgressionModule.ResetType.parse(args[2]) : null;
                if (type == null) {
                    this.usage(sender, sub);
                    return;
                }
                this.module.adminReset(sender, target, type);
            }
            case "setlevel" -> {
                final Long level = args.length >= 3 ? parse(args[2]) : null;
                if (level == null || level < 1 || level > this.module.maxLevel()) {
                    this.module.send(sender, "bad-number", Placeholder.unparsed("input", args.length >= 3 ? args[2] : ""));
                    this.usage(sender, sub);
                    return;
                }
                this.module.adminSetLevel(sender, target, level.intValue());
            }
            case "giveexp" -> {
                final Long amount = args.length >= 3 ? parse(args[2]) : null;
                if (amount == null || amount < 1) {
                    this.module.send(sender, "bad-number", Placeholder.unparsed("input", args.length >= 3 ? args[2] : ""));
                    this.usage(sender, sub);
                    return;
                }
                this.module.adminGiveExp(sender, target, amount);
            }
            case "set" -> this.adminSet(sender, target, args);
            default -> this.usage(sender, sub);
        }
    }

    private void adminSet(final CommandSender sender, final Player target, final String[] args) {
        if (args.length < 4) {
            this.usage(sender, "set");
            return;
        }
        final String what = args[2].toLowerCase(Locale.ROOT);
        final String input = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        final ClassCatalog catalog = this.module.catalog();
        switch (what) {
            case "class", "класс" -> {
                final ClassCatalog.RpgClass c = catalog.findClass(input);
                if (c == null) {
                    this.module.send(sender, "admin-unknown-key", Placeholder.unparsed("input", input),
                        Placeholder.unparsed("list", String.join(", ", catalog.classes().keySet())));
                    return;
                }
                this.module.adminSetClass(sender, target, c);
            }
            case "profession", "профессия" -> {
                final ClassCatalog.RpgProfession p = catalog.findProfession(input);
                if (p == null) {
                    this.module.send(sender, "admin-unknown-key", Placeholder.unparsed("input", input),
                        Placeholder.unparsed("list", String.join(", ", catalog.professions().keySet())));
                    return;
                }
                this.module.adminSetProfession(sender, target, p);
            }
            default -> this.usage(sender, "set");
        }
    }

    private void usage(final CommandSender sender, final String sub) {
        final String text = switch (sub) {
            case "reset" -> "/class admin reset <ник> class|profession|stats|all";
            case "setlevel" -> "/class admin setlevel <ник> <1.." + this.module.maxLevel() + ">";
            case "giveexp" -> "/class admin giveexp <ник> <опыт>";
            case "set" -> "/class admin set <ник> class|profession <ключ>";
            case "info" -> "/class admin info <ник>";
            default -> "/class admin";
        };
        sender.sendMessage(Text.mm(Text.prefix() + "<red>Использование:</red> <yellow><usage></yellow>",
            Placeholder.unparsed("usage", text)));
    }

    private static Long parse(final String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    // --- автодополнение ---

    @Override
    public @NotNull List<String> tabComplete(final @NotNull CommandSender sender, final @NotNull String alias,
                                             final @NotNull String[] args) {
        final boolean admin = sender.hasPermission(ProgressionModule.ADMIN_PERMISSION);
        final ClassCatalog catalog = this.module.catalog();
        if (args.length == 1) {
            final List<String> options = new ArrayList<>(List.of("choose", "info", "menu", "help"));
            if (admin) {
                options.add("admin");
            }
            return Completions.filter(options, args[0]);
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        if ((sub.equals("choose") || sub.equals("выбрать")) && args.length == 2) {
            return Completions.filter(this.classTokens(catalog), args[1]);
        }
        if (!admin || !(sub.equals("admin") || sub.equals("админ"))) {
            return List.of();
        }
        if (args.length == 2) {
            return Completions.filter(ADMIN_SUBS, args[1]);
        }
        final String adminSub = args[1].toLowerCase(Locale.ROOT);
        if (adminSub.equals("reload")) {
            return List.of();
        }
        if (args.length == 3) {
            return Completions.players(args[2]);
        }
        if (args.length == 4) {
            return switch (adminSub) {
                case "reset" -> Completions.filter(List.of("class", "profession", "stats", "all"), args[3]);
                case "setlevel" -> Completions.filter(List.of("1", String.valueOf(this.module.professionLevel()),
                    String.valueOf(this.module.maxLevel())), args[3]);
                case "giveexp" -> Completions.filter(List.of("100", "1000", "10000"), args[3]);
                case "set" -> Completions.filter(List.of("class", "profession"), args[3]);
                default -> List.of();
            };
        }
        if (args.length == 5 && adminSub.equals("set")) {
            final String what = args[3].toLowerCase(Locale.ROOT);
            if (what.equals("class") || what.equals("класс")) {
                return Completions.filter(catalog.classes().keySet(), args[4]);
            }
            if (what.equals("profession") || what.equals("профессия")) {
                return Completions.filter(catalog.professions().keySet(), args[4]);
            }
        }
        return List.of();
    }

    private List<String> classTokens(final ClassCatalog catalog) {
        final List<String> out = new ArrayList<>();
        for (final ClassCatalog.RpgClass c : catalog.classes().values()) {
            out.add(c.key());
            out.add(Completions.token(c.displayName()));
        }
        return out;
    }
}
