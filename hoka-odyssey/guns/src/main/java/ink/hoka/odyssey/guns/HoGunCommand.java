package ink.hoka.odyssey.guns;

import java.io.IOException;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.arguments.number.ArgumentInteger;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;

/**
 * /hogun — только операторы и консоль:
 * <ul>
 *   <li>{@code /hogun give <оружие> [игрок]} — выдать оружие (полный магазин);</li>
 *   <li>{@code /hogun ammo [кол-во] [игрок]} — патроны к оружию в руке (или к первому оружию);</li>
 *   <li>{@code /hogun reload [игрок]} — перезарядить оружие в руке (как клавиша R);</li>
 *   <li>{@code /hogun list} — оружие из config.yml; {@code /hogun info [игрок]} — что в руке, патроны, связи;</li>
 *   <li>{@code /hogun install} — положить модель/иконки/звуки TACZ в hoitems и hosounds и дописать их config.yml.</li>
 * </ul>
 */
final class HoGunCommand extends Command {
    private final GunsModule mod;

    HoGunCommand(GunsModule mod) {
        super("hogun");
        this.mod = mod;
        setCondition((s, c) -> !(s instanceof Player p) || mod.server().isOp(p));
        setDefaultExecutor((s, c) -> help(s));

        ArgumentWord gun = ArgumentType.Word("оружие");
        gun.setSuggestionCallback((s, c, sug) -> mod.guns().keySet().forEach(k -> sug.addEntry(new SuggestionEntry(k))));
        ArgumentWord who = ArgumentType.Word("игрок");
        who.setSuggestionCallback((s, c, sug) -> MinecraftServer.getConnectionManager().getOnlinePlayers()
                .forEach(p -> sug.addEntry(new SuggestionEntry(p.getUsername()))));
        ArgumentInteger count = ArgumentType.Integer("кол-во");
        count.between(1, 64 * 36);

        addSyntax((s, c) -> give(s, c.get(gun), null), ArgumentType.Literal("give"), gun);
        addSyntax((s, c) -> give(s, c.get(gun), c.get(who)), ArgumentType.Literal("give"), gun, who);
        addSyntax((s, c) -> ammo(s, 90, null), ArgumentType.Literal("ammo"));
        addSyntax((s, c) -> ammo(s, c.get(count), null), ArgumentType.Literal("ammo"), count);
        addSyntax((s, c) -> ammo(s, c.get(count), c.get(who)), ArgumentType.Literal("ammo"), count, who);
        addSyntax((s, c) -> reload(s, null), ArgumentType.Literal("reload"));
        addSyntax((s, c) -> reload(s, c.get(who)), ArgumentType.Literal("reload"), who);
        addSyntax((s, c) -> list(s), ArgumentType.Literal("list"));
        addSyntax((s, c) -> info(s, null), ArgumentType.Literal("info"));
        addSyntax((s, c) -> info(s, c.get(who)), ArgumentType.Literal("info"), who);
        addSyntax((s, c) -> install(s), ArgumentType.Literal("install"));
    }

    private static void help(CommandSender s) {
        s.sendMessage(Component.text("Оружие: /hogun give <оружие> [игрок] · /hogun ammo [кол-во] [игрок] · /hogun reload [игрок] · /hogun list"
                + " · /hogun info [игрок] · /hogun install", NamedTextColor.AQUA));
    }

    private static Player target(CommandSender s, String name) {
        if (name == null) {
            if (s instanceof Player p) {
                return p;
            }
            s.sendMessage(Component.text("Из консоли укажите игрока", NamedTextColor.RED));
            return null;
        }
        Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
        if (p == null) {
            s.sendMessage(Component.text("Игрок " + name + " не в сети", NamedTextColor.RED));
        }
        return p;
    }

    private void give(CommandSender s, String id, String who) {
        GunDef g = mod.guns().get(id);
        if (g == null) {
            s.sendMessage(Component.text("Нет оружия «" + id + "». Есть: " + String.join(", ", mod.guns().keySet()), NamedTextColor.RED));
            return;
        }
        Player p = target(s, who);
        if (p == null) {
            return;
        }
        ItemStack item = mod.items().gun(g);
        if (!p.getInventory().addItemStack(item)) {
            s.sendMessage(Component.text("У игрока нет места в инвентаре", NamedTextColor.RED));
            return;
        }
        s.sendMessage(Component.text("Выдано: " + g.name() + " → " + p.getUsername()
                + (mod.links().hasItems() ? "" : " (hoitems нет — простой предмет; /hogun install)"), NamedTextColor.GREEN));
    }

    private void reload(CommandSender s, String who) {
        Player p = target(s, who);
        if (p == null) {
            return;
        }
        mod.syncHeld(p);
        ShooterState st = mod.state(p);
        if (st.gun == null) {
            s.sendMessage(Component.text(p.getUsername() + ": оружия в руке нет", NamedTextColor.GRAY));
            return;
        }
        mod.startReload(p, st);
        s.sendMessage(Component.text(p.getUsername() + ": " + (st.reloading() ? "перезарядка" : "перезарядка не нужна или нет патронов"),
                NamedTextColor.GREEN));
    }

    private void ammo(CommandSender s, int n, String who) {
        Player p = target(s, who);
        if (p == null) {
            return;
        }
        String held = GunItems.gunId(p.getItemInMainHand());
        GunDef g = held != null ? mod.guns().get(held) : null;
        if (g == null) {
            g = mod.guns().values().stream().findFirst().orElse(null);
        }
        if (g == null) {
            s.sendMessage(Component.text("В config.yml нет оружия", NamedTextColor.RED));
            return;
        }
        int left = n;
        while (left > 0) {
            int k = Math.min(64, left);
            if (!p.getInventory().addItemStack(mod.items().ammo(g, k))) {
                break;
            }
            left -= k;
        }
        s.sendMessage(Component.text("Патроны: " + (n - left) + " шт. (" + g.ammoName() + ") → " + p.getUsername(),
                left == 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
    }

    private void list(CommandSender s) {
        s.sendMessage(Component.text("Оружие (" + mod.guns().size() + "):", NamedTextColor.AQUA));
        for (GunDef g : mod.guns().values()) {
            s.sendMessage(Component.text(" " + g.id() + " — " + g.name() + ": магазин " + g.magazine()
                    + (g.chamber() ? "+1" : "") + ", " + (int) g.rpm() + " в/мин, урон " + g.damage()[0]
                    + ", разброс " + g.spreadStand() + "°/" + g.spreadAim() + "° в прицеле", NamedTextColor.GRAY));
        }
    }

    private void info(CommandSender s, String who) {
        Player p = target(s, who);
        if (p == null) {
            return;
        }
        ShooterState st = mod.state(p);
        if (st.gun == null) {
            s.sendMessage(Component.text(p.getUsername() + ": оружия в руке нет", NamedTextColor.GRAY));
        } else {
            s.sendMessage(Component.text(p.getUsername() + ": " + st.gun.name() + " №" + st.serial + ", патроны "
                    + st.ammo + " / " + GunsModule.fmtReserve(mod.reserve(p, st.gun)) + ", режим "
                    + (st.mode == 0 ? "очередь" : "одиночный") + (st.reloading() ? ", перезаряжает" : "")
                    + (st.vacuum ? ", в вакууме" : ""), NamedTextColor.AQUA));
        }
        s.sendMessage(Component.text("Клиентский модуль guns: " + (mod.modded(p) ? "есть" : "нет (стрельба ЛКМ по одному, F — перезарядка)")
                + "; clientmods " + yes(mod.links().hasClient()) + ", hoitems " + yes(mod.links().hasItems())
                + ", hosounds " + yes(mod.links().hasSounds()), NamedTextColor.GRAY));
    }

    private static String yes(boolean b) {
        return b ? "есть" : "нет";
    }

    private void install(CommandSender s) {
        GunDef g = mod.guns().get("ak47");
        if (g == null) {
            g = mod.guns().values().stream().findFirst().orElse(null);
        }
        if (g == null) {
            s.sendMessage(Component.text("В config.yml нет оружия", NamedTextColor.RED));
            return;
        }
        Assets.Report r;
        try {
            r = mod.assets().install(g);
        } catch (IOException | RuntimeException e) {
            s.sendMessage(Component.text("Не получилось: " + e, NamedTextColor.RED));
            return;
        }
        s.sendMessage(Component.text("Ассеты TACZ (CC BY-NC-ND 4.0, некоммерчески, с указанием авторов):", NamedTextColor.AQUA));
        for (String l : r.lines()) {
            s.sendMessage(Component.text(" " + l, NamedTextColor.GRAY));
        }
        if (r.manualYaml() != null) {
            s.sendMessage(Component.text("Допишите вручную (YAML — и в лог сервера):", NamedTextColor.YELLOW));
            for (String l : r.manualYaml().split("\n")) {
                s.sendMessage(Component.text(l, NamedTextColor.WHITE));
            }
            mod.logger().info("guns: допишите в config.yml:\n{}", r.manualYaml());
        }
        // перечитать hoitems и hosounds (их команды — от имени консоли)
        var cm = MinecraftServer.getCommandManager();
        for (String cmd : List.of("hoitem reload", "hosound reload")) {
            String name = cmd.substring(0, cmd.indexOf(' '));
            if (cm.commandExists(name)) {
                cm.execute(cm.getConsoleSender(), cmd);
                s.sendMessage(Component.text(" /" + cmd + " — выполнено", NamedTextColor.GREEN));
            } else {
                s.sendMessage(Component.text(" модуля для /" + name + " нет — файлы лежат, включите его", NamedTextColor.YELLOW));
            }
        }
    }
}
