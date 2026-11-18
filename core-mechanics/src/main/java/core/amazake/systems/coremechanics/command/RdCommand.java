package core.amazake.systems.coremechanics.command;

import core.amazake.systems.coremechanics.doll.RagdollManager;
import core.amazake.systems.coremechanics.statue.StatueManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /rd} (псевдонимы /ragdoll, /coremechanics) — рэгдоллы и статуи. Право {@code coremechanics.admin}. Команда
 * игрока выполняется в его потоке, из консоли — в глобальном; работа с миром уходит в потоки регионов.
 */
public final class RdCommand extends Command {

    public static final String PERMISSION = "coremechanics.admin";

    private final RagdollManager ragdolls;
    private final StatueManager statues;
    private final Supplier<String> status;

    public RdCommand(final RagdollManager ragdolls, final StatueManager statues, final Supplier<String> status) {
        super("rd", "Рэгдоллы и куклы-статуи (core-mechanics)", "/rd <ник> | clear | statue <ник> [поза|анимация] | …",
            List.of("ragdoll", "coremechanics"));
        this.setPermission(PERMISSION);
        this.ragdolls = ragdolls;
        this.statues = statues;
        this.status = status;
    }

    @Override
    public boolean execute(final CommandSender s, final String label, final String[] a) {
        if (!this.testPermission(s)) {
            return true;
        }
        final String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("info")) {
            send(s, this.status.get(), NamedTextColor.GRAY);
            return true;
        }
        if (sub.equals("clear")) {
            send(s, "Рэгдоллов убрано: " + this.ragdolls.clear(), NamedTextColor.GREEN);
            return true;
        }
        if (sub.equals("summon") && !(s instanceof Player)) {
            this.statues.exportAll(s);   // консоль: команды /summon всех статуй — в файлы
            return true;
        }
        if (!(s instanceof Player p)) {
            send(s, "Из консоли: /rd clear | info | summon (команды всех статуй в statue_summon/). Остальное — только игроку.",
                NamedTextColor.YELLOW);
            return true;
        }
        if (a.length == 0) {
            send(p, "/rd <ник> — рэгдолл игрока перед тобой · /rd clear — убрать все", NamedTextColor.YELLOW);
            send(p, "Статуи: /rd statue <ник> [поза|анимация] · pose · anim · frame add|play|stop|clear|speed · tool · "
                + "topple · up · turn · move · remove · removeall · list · poses · summon", NamedTextColor.YELLOW);
            return true;
        }
        if (this.statues.command(p, a)) {
            return true;
        }
        if (!a[0].matches("[A-Za-z0-9_]{1,16}")) {
            send(p, "Не похоже на ник: " + a[0] + ". /rd — справка.", NamedTextColor.RED);
            return true;
        }
        final String m = this.ragdolls.spawnCommand(p, a[0]);
        send(p, m, m.startsWith("Физдвижок") ? NamedTextColor.RED : NamedTextColor.GREEN);
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (!this.testPermissionSilent(sender)) {
            return List.of();
        }
        final List<String> online = new ArrayList<>();
        for (final Player o : Bukkit.getOnlinePlayers()) {
            online.add(o.getName());
        }
        final List<String> known = new ArrayList<>();
        if (args.length == 1) {
            for (final OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op.getName() != null) {
                    known.add(op.getName());
                }
            }
        }
        return RdTab.complete(args, online, known);
    }

    private static void send(final CommandSender s, final String t, final NamedTextColor c) {
        s.sendMessage(Component.text(t, c));
    }
}
