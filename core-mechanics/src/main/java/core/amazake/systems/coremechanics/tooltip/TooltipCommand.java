package core.amazake.systems.coremechanics.tooltip;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** /ttip apply | reset — подсказка-свиток на предмете в руке (тестовый вариант). Право coremechanics.admin. */
public final class TooltipCommand extends Command {

    private final Supplier<Map<String, String>> flavors;

    public TooltipCommand(final Supplier<Map<String, String>> flavors) {
        super("ttip", "Подсказка-свиток на предмете в руке (core-mechanics)", "/ttip apply | reset", List.of("tooltip"));
        this.setPermission("coremechanics.admin");
        this.flavors = flavors;
    }

    @Override
    public boolean execute(final CommandSender s, final String label, final String[] a) {
        if (!this.testPermission(s)) {
            return true;
        }
        if (!(s instanceof Player p)) {
            s.sendMessage(Component.text("Только в игре.", NamedTextColor.RED));
            return true;
        }
        final ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.isEmpty()) {
            p.sendMessage(Component.text("Возьмите предмет в основную руку.", NamedTextColor.RED));
            return true;
        }
        final String sub = a.length == 0 ? "apply" : a[0].toLowerCase(java.util.Locale.ROOT);
        if (sub.equals("reset")) {
            final boolean ok = ParchmentTooltip.reset(hand);
            p.getInventory().setItemInMainHand(hand);
            p.sendMessage(Component.text(ok ? "Подсказка возвращена как была." : "На предмете нет подсказки-свитка.",
                ok ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            return true;
        }
        ParchmentTooltip.apply(hand, this.flavors.get());
        p.getInventory().setItemInMainHand(hand);
        p.sendMessage(Component.text("Подсказка-свиток применена (/ttip reset — вернуть).", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender s, final String alias, final String[] a) {
        return a.length == 1 ? List.of("apply", "reset") : List.of();
    }
}
