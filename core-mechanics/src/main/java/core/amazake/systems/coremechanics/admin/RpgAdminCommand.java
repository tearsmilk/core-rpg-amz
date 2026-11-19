package core.amazake.systems.coremechanics.admin;

import core.amazake.systems.api.SystemContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /rpgadmin [on|off|status] [ник]} — режим админа (см. {@link RpgAdmin}). Без аргументов — переключить себе.
 * Право {@code coremechanics.rpgadmin}. Из консоли — только с ником. Чужой игрок обрабатывается в его потоке.
 */
public final class RpgAdminCommand extends Command {

    /** Что сделать: переключить, включить, выключить, показать. */
    enum Op { TOGGLE, ON, OFF, STATUS }

    /** Разобранные аргументы: действие и ник (null — сам отправитель). */
    record Parsed(Op op, String target) {
    }

    private final SystemContext context;

    public RpgAdminCommand(final SystemContext context) {
        super("rpgadmin", "Режим админа: творческий режим, RPG-системы не трогают игрока (core-mechanics)",
            "/rpgadmin [on|off|status] [ник]", List.of());
        this.setPermission(RpgAdmin.PERMISSION);
        this.context = context;
    }

    /** null — аргументы не разобраны (показать справку). */
    static Parsed parse(final String[] a) {
        if (a.length == 0) {
            return new Parsed(Op.TOGGLE, null);
        }
        if (a.length > 2) {
            return null;
        }
        final Op op = switch (a[0].toLowerCase(Locale.ROOT)) {
            case "on", "вкл" -> Op.ON;
            case "off", "выкл" -> Op.OFF;
            case "status", "статус" -> Op.STATUS;
            case "toggle" -> Op.TOGGLE;
            default -> null;
        };
        if (op == null) {
            // /rpgadmin <ник> — переключить другому
            return a.length == 1 && isNick(a[0]) ? new Parsed(Op.TOGGLE, a[0]) : null;
        }
        if (a.length == 2) {
            return isNick(a[1]) ? new Parsed(op, a[1]) : null;
        }
        return new Parsed(op, null);
    }

    static boolean isNick(final String s) {
        return s != null && s.matches("[A-Za-z0-9_]{1,16}");
    }

    @Override
    public boolean execute(final CommandSender s, final String label, final String[] a) {
        if (!this.testPermission(s)) {
            return true;
        }
        final Parsed p = parse(a);
        if (p == null) {
            send(s, "Использование: /rpgadmin [on|off|status] [ник] — без аргументов переключить себе.", NamedTextColor.YELLOW);
            return true;
        }
        final Player target;
        if (p.target() == null) {
            if (!(s instanceof Player self)) {
                send(s, "Из консоли укажите ник: /rpgadmin [on|off|status] <ник>", NamedTextColor.RED);
                return true;
            }
            target = self;
        } else {
            target = Bukkit.getPlayerExact(p.target());
            if (target == null) {
                send(s, "Игрок " + p.target() + " не в сети.", NamedTextColor.RED);
                return true;
            }
        }
        if (Bukkit.isOwnedByCurrentRegion(target)) {
            this.apply(s, target, p.op());
        } else if (!this.context.runOnceForPlayer(target, () -> this.apply(s, target, p.op()))) {
            send(s, "Игрок " + target.getName() + " недоступен (вышел?).", NamedTextColor.RED);
        }
        return true;
    }

    /** Поток игрока {@code target}. */
    private void apply(final CommandSender s, final Player target, final Op op) {
        if (!target.isOnline()) {
            return;
        }
        final boolean self = s == target;
        final String who = self ? "" : " у " + target.getName();
        final boolean on = RpgAdmin.isOn(target);
        final Op eff = op == Op.TOGGLE ? (on ? Op.OFF : Op.ON) : op;
        switch (eff) {
            case STATUS -> send(s, "Режим админа" + who + (on ? " включён" : " выключен") + ". Режим игры: "
                + RpgAdmin.modeName(target.getGameMode()) + ".", NamedTextColor.GRAY);
            case ON -> {
                final boolean changed = RpgAdmin.enable(target);
                if (!changed) {
                    send(s, "Режим админа" + who + " уже включён.", NamedTextColor.GRAY);
                    return;
                }
                send(s, "Режим админа включён" + who + ": творческий режим, RPG-системы не трогают инвентарь. "
                    + "/rpgadmin — выключить.", NamedTextColor.GOLD);
                if (!self) {
                    send(target, "Вам включили режим админа: творческий режим. /rpgadmin — выключить.", NamedTextColor.GOLD);
                }
            }
            case OFF -> {
                final GameMode back = RpgAdmin.disable(target);
                if (back == null) {
                    send(s, "Режим админа" + who + " и так выключен.", NamedTextColor.GRAY);
                    return;
                }
                send(s, "Режим админа выключен" + who + ". Режим игры: " + RpgAdmin.modeName(back) + ".", NamedTextColor.GREEN);
                if (!self) {
                    send(target, "Режим админа выключен. Режим игры: " + RpgAdmin.modeName(back) + ".", NamedTextColor.GREEN);
                }
            }
            default -> {
            }
        }
    }

    private static void send(final CommandSender s, final String m, final NamedTextColor c) {
        s.sendMessage(Component.text(m, c));
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        final List<String> names = new ArrayList<>();
        for (final Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return complete(args, names);
    }

    static List<String> complete(final String[] args, final List<String> online) {
        final List<String> out = new ArrayList<>();
        if (args.length == 1) {
            final String pre = args[0].toLowerCase(Locale.ROOT);
            for (final String o : List.of("on", "off", "status")) {
                if (o.startsWith(pre)) {
                    out.add(o);
                }
            }
            for (final String n : online) {
                if (n.toLowerCase(Locale.ROOT).startsWith(pre)) {
                    out.add(n);
                }
            }
        } else if (args.length == 2 && List.of("on", "off", "status", "toggle").contains(args[0].toLowerCase(Locale.ROOT))) {
            final String pre = args[1].toLowerCase(Locale.ROOT);
            for (final String n : online) {
                if (n.toLowerCase(Locale.ROOT).startsWith(pre)) {
                    out.add(n);
                }
            }
        }
        return out;
    }
}
