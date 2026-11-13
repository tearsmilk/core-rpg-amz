package core.amazake.systems.corerpg.sharpen;

import core.amazake.systems.corerpg.util.Gear;
import core.amazake.systems.corerpg.util.Text;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /sharpen} (алиасы zatochka, zat, заточка): menu, info, list — всем; give, set, reload — с правом
 * {@code core.sharpen.admin}. Команда игрока выполняется в его потоке, из консоли — в глобальном; чужого игрока
 * меняем только задачей в его потоке.
 */
final class SharpenCommand extends Command {

    static final String ADMIN = "core.sharpen.admin";
    private static final List<String> PLAYER_SUBS = List.of("menu", "info", "list", "help");
    private static final List<String> ADMIN_SUBS = List.of("give", "set", "reload");
    private static final List<String> GROUPS = List.of("all", "stones", "runes");

    private final SharpenModule module;

    SharpenCommand(final SharpenModule module) {
        super("sharpen", "Заточка предметов", "/sharpen [menu|info|list|give|set|reload]", List.of("zatochka", "zat", "заточка"));
        this.module = module;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        final String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu", "меню" -> this.menu(sender);
            case "info", "инфо" -> this.info(sender);
            case "list", "список" -> this.list(sender);
            case "give" -> {
                if (this.admin(sender)) {
                    this.give(sender, label, args);
                }
            }
            case "set" -> {
                if (this.admin(sender)) {
                    this.set(sender, label, args);
                }
            }
            case "reload" -> {
                if (this.admin(sender)) {
                    this.module.reloadAsync(sender);
                }
            }
            default -> this.help(sender, label);
        }
        return true;
    }

    private boolean admin(final CommandSender sender) {
        if (sender.hasPermission(ADMIN)) {
            return true;
        }
        Text.send(sender, "<red>Нет прав.</red>");
        return false;
    }

    private void help(final CommandSender sender, final String label) {
        final String l = Text.escape(label);
        final StringBuilder sb = new StringBuilder("<gold>Заточка</gold><gray> — команды:</gray>")
            .append("\n<yellow>/").append(l).append(" menu</yellow> <gray>— открыть меню заточки</gray>")
            .append("\n<yellow>/").append(l).append(" info</yellow> <gray>— заточка предмета в руке</gray>")
            .append("\n<yellow>/").append(l).append(" list</yellow> <gray>— камни и руны с шансами для предмета в руке</gray>");
        if (sender.hasPermission(ADMIN)) {
            sb.append("\n<yellow>/").append(l).append(" give <ник> <расходник|all|stones|runes> [1..64]</yellow>")
                .append(" <gray>— выдать расходники</gray>")
                .append("\n<yellow>/").append(l).append(" set <ник> <0..11></yellow> <gray>— заточка предмета в руке игрока</gray>")
                .append("\n<yellow>/").append(l).append(" reload</yellow> <gray>— перечитать sharpen.yml</gray>");
        }
        Text.send(sender, sb.toString().replace("<ник>", "\\<ник>").replace("<расходник", "\\<расходник")
            .replace("<0..11>", "\\<0..11>"));
    }

    private void menu(final CommandSender sender) {
        if (!(sender instanceof final Player player)) {
            Text.send(sender, "<red>Только для игроков.</red>");
            return;
        }
        this.module.menus().open(player);
    }

    private void info(final CommandSender sender) {
        if (!(sender instanceof final Player player)) {
            Text.send(sender, "<red>Только для игроков.</red>");
            return;
        }
        final ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            Text.send(player, "<red>В руке нет предмета.</red>");
            return;
        }
        final Gear.Kind kind = Gear.kind(hand);
        if (kind == null) {
            Text.send(player, "<red>Этот предмет нельзя точить.</red>");
            return;
        }
        if (this.module.items().migrate(hand)) {
            player.getInventory().setItemInMainHand(hand);
        }
        final SharpenSettings s = this.module.settings();
        final int lvl = this.module.items().level(hand);
        final Gear.Grade grade = Gear.grade(hand);
        final int max = s.maxLevel(grade);
        final double bonus = s.bonus(kind.attacks(), lvl);
        Text.send(player, "<gold>Заточка: <yellow>+" + lvl + "</yellow> / +" + max + " <gray>(грейд " + grade + ", бонус "
            + (kind.attacks() ? "атаки" : "защиты") + " +" + Text.num(bonus) + "%)</gray>");
        if (kind == Gear.Kind.SHIELD) {
            Text.send(player, "<gray>Щит точится, но боевого бонуса не даёт.</gray>");
        }
        if (lvl >= 11) {
            Text.send(player, "<gold>Пока предмет надет: +" + s.legendaryStat() + " ко всем параметрам.</gold>");
        }
        if (lvl < max) {
            if (SharpenRules.needsSacrifice(s, grade, lvl)) {
                Text.send(player, "<gray>Следующий уровень +" + (lvl + 1) + ": нужна жертва — второй такой же предмет "
                    + "(<white>/" + "sharpen list</white> — шансы).</gray>");
            } else {
                Text.send(player, "<gray>Следующий уровень +" + (lvl + 1) + ": бонус +"
                    + Text.num(s.bonus(kind.attacks(), lvl + 1)) + "%. Шансы камней — <white>/sharpen list</white>.</gray>");
            }
        }
    }

    private void list(final CommandSender sender) {
        if (!(sender instanceof final Player player)) {
            Text.send(sender, "<red>Только для игроков.</red>");
            return;
        }
        final ItemStack hand = player.getInventory().getItemInMainHand();
        final Gear.Kind kind = Gear.kind(hand);
        if (kind == null) {
            Text.send(player, "<red>Возьмите в основную руку оружие, броню, щит или инструмент.</red>");
            return;
        }
        if (this.module.items().migrate(hand)) {
            player.getInventory().setItemInMainHand(hand);
        }
        final SharpenSettings s = this.module.settings();
        final int lvl = this.module.items().level(hand);
        final Gear.Grade grade = Gear.grade(hand);
        final int max = s.maxLevel(grade);
        if (lvl >= max) {
            Text.send(player, "<yellow>Предмет уже имеет максимальный уровень заточки (+" + max + ").</yellow>");
            return;
        }
        final StringBuilder sb = new StringBuilder("<gold>Попытка +" + (lvl + 1) + "</gold> <gray>(грейд " + grade + ", "
            + categoryName(kind.sharpenCategory()) + ", режим " + (s.mode() == SharpenSettings.Mode.ROYAL_QUEST ? "Royal Quest" : "простой")
            + "):</gray>");
        if (SharpenRules.needsSacrifice(s, grade, lvl)) {
            sb.append("\n<gray>Камень не нужен: положите в меню второй такой же предмет. Шанс по его заточке:</gray>");
            for (int i = 0; i <= 10; i++) {
                sb.append("\n<white>  жертва +").append(i).append(" → <green>")
                    .append(SharpenRules.percent(s.legendaryChance(i))).append("%</green></white>");
            }
            sb.append("\n<red>Жертва тратится всегда; при провале остаётся +").append(lvl).append(".</red>");
        } else {
            for (final Consumable stone : Consumable.values()) {
                if (!stone.isStone()) {
                    continue;
                }
                final String name = Text.escape(this.module.items().name(stone));
                final SharpenRules.Check check = SharpenRules.plan(s, kind.sharpenCategory(), grade, lvl, stone, null, -1);
                if (!check.ok()) {
                    sb.append("\n<dark_gray>• ").append(name).append(": только для грейда C</dark_gray>");
                    continue;
                }
                final SharpenRules.Plan plan = check.plan();
                sb.append("\n<white>• ").append(name).append(": <green>")
                    .append(SharpenRules.percent(plan.success())).append("%</green>");
                if (plan.noDestroy()) {
                    sb.append(" <aqua>(не уничтожает)</aqua>");
                } else {
                    sb.append(" <gray>уничтожение при провале: <red>")
                        .append(SharpenRules.percent(plan.destroy())).append("%</red></gray>");
                }
                sb.append("</white>");
            }
            for (final Consumable rune : Consumable.values()) {
                if (rune.isRune()) {
                    sb.append("\n<aqua>• ").append(Text.escape(this.module.items().name(rune)))
                        .append("</aqua><gray>: при провале уровень не меняется, предмет цел</gray>");
                }
            }
            sb.append("\n<gray>Без руны провал: бросок уничтожения, иначе уровень −1.</gray>");
        }
        Text.send(player, sb.toString());
    }

    private static String categoryName(final String category) {
        return switch (category) {
            case "armor" -> "броня";
            case "weapon_2h" -> "двуручное";
            default -> "одноручное";
        };
    }

    private void give(final CommandSender sender, final String label, final String[] args) {
        if (args.length < 3) {
            Text.send(sender, "<yellow>Использование: /<label> give \\<ник> \\<расходник|all|stones|runes> [1..64]</yellow>",
                Placeholder.unparsed("label", label));
            Text.send(sender, "<gray>Расходники: " + String.join(", ", ids()) + "</gray>");
            return;
        }
        final Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Text.send(sender, "<red>Игрок <white><name></white> не в сети.</red>", Placeholder.unparsed("name", args[1]));
            return;
        }
        // Имя может быть из нескольких слов («руна сохранения»), последнее число — количество.
        final List<String> rest = new ArrayList<>(Arrays.asList(args).subList(2, args.length));
        int amount = 1;
        if (rest.size() > 1) {
            final Integer parsed = parseInt(rest.get(rest.size() - 1));
            if (parsed != null) {
                amount = parsed;
                rest.remove(rest.size() - 1);
            }
        }
        if (amount < 1 || amount > 64) {
            Text.send(sender, "<red>Количество — от 1 до 64.</red>");
            return;
        }
        final String what = String.join(" ", rest);
        final List<Consumable> types = this.resolve(what);
        if (types.isEmpty()) {
            Text.send(sender, "<red>Неизвестный расходник: <white><what></white>. Есть: " + String.join(", ", ids()) + ".</red>",
                Placeholder.unparsed("what", what));
            return;
        }
        final int count = amount;
        final String targetName = target.getName();
        final boolean queued = this.module.context().runOnceForPlayer(target, () -> {
            // поток получателя
            for (final Consumable type : types) {
                int left = count;
                while (left > 0) {
                    final ItemStack stack = this.module.items().create(type, left);
                    final int portion = Math.min(left, stack.getMaxStackSize());
                    stack.setAmount(portion);
                    left -= portion;
                    for (final ItemStack over : target.getInventory().addItem(stack).values()) {
                        target.getWorld().dropItemNaturally(target.getLocation(), over);
                    }
                }
            }
            Text.send(target, "<green>Вы получили расходники заточки: <white>" + count + " × " + this.names(types) + "</white>.</green>");
        });
        if (!queued) {
            Text.send(sender, "<red>Игрок вышел.</red>");
            return;
        }
        this.module.reply(sender, "<green>Выдано " + count + " × " + this.names(types) + " игроку <white><name></white>.</green>",
            Placeholder.unparsed("name", targetName));
    }

    private List<Consumable> resolve(final String what) {
        final String n = Consumable.normalize(what);
        final List<Consumable> out = new ArrayList<>();
        switch (n) {
            case "all", "все", "всё" -> out.addAll(Arrays.asList(Consumable.values()));
            case "stones", "камни" -> {
                for (final Consumable c : Consumable.values()) {
                    if (c.isStone()) {
                        out.add(c);
                    }
                }
            }
            case "runes", "руны" -> {
                for (final Consumable c : Consumable.values()) {
                    if (c.isRune()) {
                        out.add(c);
                    }
                }
            }
            default -> {
                final Consumable c = this.module.settings().resolveConsumable(what);
                if (c != null) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private String names(final List<Consumable> types) {
        if (types.size() > 3) {
            return types.size() + " видов";
        }
        final List<String> names = new ArrayList<>();
        for (final Consumable c : types) {
            names.add(Text.escape(this.module.items().name(c)));
        }
        return String.join(", ", names);
    }

    private void set(final CommandSender sender, final String label, final String[] args) {
        if (args.length < 3) {
            Text.send(sender, "<yellow>Использование: /<label> set \\<ник> \\<0..11></yellow>", Placeholder.unparsed("label", label));
            return;
        }
        final Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Text.send(sender, "<red>Игрок <white><name></white> не в сети.</red>", Placeholder.unparsed("name", args[1]));
            return;
        }
        final Integer level = parseInt(args[2]);
        if (level == null || level < 0 || level > SharpenSettings.HARD_MAX) {
            Text.send(sender, "<red>Уровень — целое от 0 до " + SharpenSettings.HARD_MAX + ".</red>");
            return;
        }
        final boolean queued = this.module.context().runOnceForPlayer(target, () -> {
            // поток цели
            final ItemStack hand = target.getInventory().getItemInMainHand();
            if (Gear.kind(hand) == null) {
                this.module.reply(sender, "<red>У <white><name></white> в руке нет предмета, который можно точить.</red>",
                    Placeholder.unparsed("name", target.getName()));
                return;
            }
            final int max = this.module.settings().maxLevel(Gear.grade(hand));
            if (level > max) {
                this.module.reply(sender, "<red>Для этого предмета максимум +" + max + " (грейд " + Gear.grade(hand) + ").</red>");
                return;
            }
            this.module.items().setLevel(hand, level);
            target.getInventory().setItemInMainHand(hand);
            this.module.system().aspects().refreshLater(target);
            this.module.reply(sender, "<green>Заточка предмета в руке <white><name></white>: <yellow>+" + level + "</yellow>.</green>",
                Placeholder.unparsed("name", target.getName()));
        });
        if (!queued) {
            Text.send(sender, "<red>Игрок вышел.</red>");
        }
    }

    private static Integer parseInt(final String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static List<String> ids() {
        final List<String> out = new ArrayList<>();
        for (final Consumable c : Consumable.values()) {
            out.add(c.id());
        }
        return out;
    }

    // ------------------------------------------------------------------ автодополнение

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        final boolean admin = sender.hasPermission(ADMIN);
        if (args.length == 1) {
            final List<String> subs = new ArrayList<>(PLAYER_SUBS);
            if (admin) {
                subs.addAll(ADMIN_SUBS);
            }
            return filter(subs, args[0]);
        }
        if (!admin) {
            return List.of();
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("give") && !sub.equals("set")) {
            return List.of();
        }
        if (args.length == 2) {
            final List<String> names = new ArrayList<>();
            for (final Player p : Bukkit.getOnlinePlayers()) {
                if (!(sender instanceof final Player self) || self.canSee(p)) {
                    names.add(p.getName());
                }
            }
            return filter(names, args[1]);
        }
        if (sub.equals("set")) {
            if (args.length == 3) {
                final List<String> levels = new ArrayList<>();
                for (int i = 0; i <= SharpenSettings.HARD_MAX; i++) {
                    levels.add(Integer.toString(i));
                }
                return filter(levels, args[2]);
            }
            return List.of();
        }
        if (args.length == 3) {
            final Set<String> options = new LinkedHashSet<>(GROUPS);
            options.addAll(ids());
            for (final SharpenSettings.ConsumableDef def : this.module.settings().consumables().values()) {
                for (final String alias2 : def.aliases()) {
                    if (!alias2.contains(" ")) {
                        options.add(alias2);
                    }
                }
            }
            return filter(new ArrayList<>(options), args[2]);
        }
        if (args.length == 4) {
            return filter(List.of("1", "8", "16", "32", "64"), args[3]);
        }
        return List.of();
    }

    private static List<String> filter(final List<String> options, final String input) {
        final String lower = input.toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (final String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(o);
            }
        }
        return out;
    }
}
