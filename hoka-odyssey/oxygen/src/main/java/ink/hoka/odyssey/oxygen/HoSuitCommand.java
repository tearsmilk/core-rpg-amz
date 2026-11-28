package ink.hoka.odyssey.oxygen;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.entity.Player;

/**
 * /hosuit [ник] — выдать скафандр себе или игроку (только операторы и консоль).
 */
final class HoSuitCommand extends Command {
    private final OxygenModule m;

    HoSuitCommand(OxygenModule m) {
        super("hosuit");
        this.m = m;
        setCondition((s, c) -> !(s instanceof Player p) || m.server().isOp(p));
        setDefaultExecutor((s, c) -> {
            if (s instanceof Player p) {
                give(s, p);
            } else {
                s.sendMessage(Component.text("/hosuit <ник> — выдать скафандр игроку", NamedTextColor.AQUA));
            }
        });

        ArgumentWord nick = ArgumentType.Word("ник");
        nick.setSuggestionCallback((sender, ctx, sug) -> {
            String input = sug.getInput().replace("\0", "");
            String typed = input.substring(input.lastIndexOf(' ') + 1).toLowerCase(java.util.Locale.ROOT);
            for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
                if (p.getUsername().toLowerCase(java.util.Locale.ROOT).startsWith(typed)) {
                    sug.addEntry(new SuggestionEntry(p.getUsername()));
                }
            }
        });
        addSyntax((s, c) -> {
            String name = c.get(nick);
            Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
            if (target == null) {
                s.sendMessage(Component.text("Игрок " + name + " не в сети", NamedTextColor.RED));
            } else {
                give(s, target);
            }
        }, nick);
    }

    private void give(CommandSender s, Player target) {
        if (!target.getInventory().addItemStack(Suit.create(m.settings()))) {
            s.sendMessage(Component.text(s == target ? "Инвентарь полон — освободите место для скафандра"
                : "Инвентарь игрока " + target.getUsername() + " полон — скафандр не выдан", NamedTextColor.RED));
            return;
        }
        if (s != target) {
            s.sendMessage(Component.text("Скафандр выдан игроку " + target.getUsername(), NamedTextColor.GREEN));
        }
        target.sendMessage(Component.text("Вы получили скафандр — наденьте его на голову (ПКМ с ним в руке)",
            NamedTextColor.GREEN));
    }
}
