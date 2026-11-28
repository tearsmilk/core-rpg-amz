package ink.hoka.odyssey.gravity;

import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;

/**
 * /hogm — выживание ↔ креатив (только операторы и консоль).
 * <pre>
 * /hogm                     переключить себя: выживание ↔ креатив
 * /hogm s|c|survival|creative          поставить себе режим
 * /hogm &lt;режим&gt; &lt;игрок&gt;     поставить режим другому игроку (из консоли — только так)
 * </pre>
 * Вакуум при смене режима не выключается: в креативе та же физика невесомости, но без урона (креатив неуязвим,
 * удары gravity ранят только в выживании/приключении). Скорость при смене — с нуля ({@link GravityModule#resetMotion}),
 * чтобы скорость креативного полёта не уносила игрока после перехода в выживание.
 */
final class HoGmCommand extends Command {
    private final GravityModule m;

    HoGmCommand(GravityModule m) {
        super("hogm");
        this.m = m;
        setCondition((s, c) -> !(s instanceof Player p) || m.server().isOp(p));
        setDefaultExecutor((s, c) -> {
            if (!(s instanceof Player p)) {
                s.sendMessage(Component.text("Из консоли: /hogm <s|c> <игрок>", NamedTextColor.YELLOW));
                return;
            }
            set(s, p, p.getGameMode() == GameMode.CREATIVE ? GameMode.SURVIVAL : GameMode.CREATIVE);
        });
        ArgumentWord mode = ArgumentType.Word("режим").from("s", "c", "survival", "creative", "выживание", "креатив");
        mode.setCallback((s, ex) -> s.sendMessage(Component.text(
            "Режим: s (survival, выживание) или c (creative, креатив)", NamedTextColor.RED)));
        ArgumentWord target = ArgumentType.Word("игрок");
        target.setSuggestionCallback((sender, ctx, sug) -> {
            String input = sug.getInput().replace("\0", "");
            String typed = input.substring(input.lastIndexOf(' ') + 1).toLowerCase(Locale.ROOT);
            for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
                if (p.getUsername().toLowerCase(Locale.ROOT).startsWith(typed)) {
                    sug.addEntry(new SuggestionEntry(p.getUsername()));
                }
            }
        });
        addSyntax((s, c) -> {
            if (!(s instanceof Player p)) {
                s.sendMessage(Component.text("Из консоли укажите игрока: /hogm <s|c> <игрок>", NamedTextColor.YELLOW));
                return;
            }
            set(s, p, parse(c.get(mode)));
        }, mode);
        addSyntax((s, c) -> {
            Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(c.get(target));
            if (p == null) {
                s.sendMessage(Component.text("Игрок «" + c.get(target) + "» не в сети", NamedTextColor.RED));
                return;
            }
            set(s, p, parse(c.get(mode)));
        }, mode, target);
    }

    /** Режим по слову команды (слово уже проверено ArgumentWord.from). */
    static GameMode parse(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "c", "creative", "креатив" -> GameMode.CREATIVE;
            default -> GameMode.SURVIVAL;
        };
    }

    static String name(GameMode gm) {
        return gm == GameMode.CREATIVE ? "креатив" : gm == GameMode.SURVIVAL ? "выживание" : gm.name().toLowerCase(Locale.ROOT);
    }

    private void set(CommandSender s, Player p, GameMode gm) {
        if (p.getGameMode() == gm) {
            s.sendMessage(Component.text(p.getUsername() + " уже в режиме «" + name(gm) + "»", NamedTextColor.YELLOW));
            return;
        }
        if (!p.setGameMode(gm)) {
            s.sendMessage(Component.text("Режим не сменить: его запретил другой модуль", NamedTextColor.RED));
            return;
        }
        m.resetMotion(p);
        p.sendMessage(Component.text("Режим игры: " + name(gm), NamedTextColor.GREEN));
        if (s != p) {
            s.sendMessage(Component.text(p.getUsername() + ": режим «" + name(gm) + "»", NamedTextColor.GREEN));
        }
    }
}
