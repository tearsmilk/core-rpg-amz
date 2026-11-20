package dev.tearsmilk.textshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class Commands implements TabExecutor {

    static final String ADMIN = "textshader.admin";
    static final String USE = "textshader.use";
    private static final List<String> SUBS = List.of("menu", "set", "nick", "off", "preview", "list", "code",
            "give-name", "title", "broadcast", "player", "reload");
    private static final List<String> ADMIN_SUBS = List.of("give-name", "title", "broadcast", "player", "reload");

    private final TextShaderPlugin plugin;

    public Commands(TextShaderPlugin plugin) {
        this.plugin = plugin;
    }

    private Msg msg() {
        return plugin.msg();
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "menu" : a[0].toLowerCase(Locale.ROOT);
        if (ADMIN_SUBS.contains(sub) && !s.hasPermission(ADMIN)) {
            msg().send(s, "no-permission");
            return true;
        }
        if (!ADMIN_SUBS.contains(sub) && !s.hasPermission(USE)) {
            msg().send(s, "no-permission");
            return true;
        }
        switch (sub) {
            case "menu" -> menu(s, a);
            case "set" -> set(s, a, Choices.Target.CHAT);
            case "nick" -> set(s, a, Choices.Target.NICK);
            case "off" -> off(s, a);
            case "preview" -> preview(s, a);
            case "list" -> list(s);
            case "code" -> code(s, a);
            case "give-name" -> giveName(s, a);
            case "title" -> title(s, a);
            case "broadcast" -> broadcast(s, a);
            case "player" -> player(s, a);
            case "reload" -> {
                plugin.reload();
                msg().send(s, "reloaded");
            }
            case "help" -> msg().send(s, "help");
            default -> msg().send(s, "help");
        }
        return true;
    }

    private Player self(CommandSender s) {
        if (s instanceof Player p) {
            return p;
        }
        msg().send(s, "players-only");
        return null;
    }

    static FxSpec specArgs(String[] a, int from) {
        if (a.length <= from) {
            return null;
        }
        FxSpec spec = FxSpec.parse(a[from]);
        if (spec == null) {
            return null;
        }
        if (a.length > from + 1 && !a[from].contains(":")) {
            Palette p = Palette.byKey(a[from + 1]);
            if (p != null) {
                return spec.withPalette(p);
            }
        }
        return spec;
    }

    static int specWidth(String[] a, int from) {
        if (a.length > from + 1 && !a[from].contains(":") && Palette.byKey(a[from + 1]) != null) {
            return 2;
        }
        return 1;
    }

    private boolean unknown(CommandSender s, String[] a, int from) {
        msg().send(s, "unknown-effect", "effect", a.length > from ? a[from] : "");
        return true;
    }

    private void menu(CommandSender s, String[] a) {
        Player p = self(s);
        if (p == null) {
            return;
        }
        Choices.Target t = a.length > 1 && "nick".equalsIgnoreCase(a[1]) ? Choices.Target.NICK : Choices.Target.CHAT;
        plugin.menu().open(p, t);
    }

    private void set(CommandSender s, String[] a, Choices.Target target) {
        Player p = self(s);
        if (p == null) {
            return;
        }
        if (a.length < 2) {
            msg().send(s, target == Choices.Target.CHAT ? "usage-set" : "usage-nick");
            return;
        }
        if ("off".equalsIgnoreCase(a[1])) {
            plugin.apply(p, target, null, true);
            return;
        }
        FxSpec spec = specArgs(a, 1);
        if (spec == null) {
            unknown(s, a, 1);
            return;
        }
        if (!p.hasPermission(spec.effect().permission())) {
            msg().send(p, "no-effect-permission", "effect", spec.effect().title());
            return;
        }
        plugin.apply(p, target, spec, true);
    }

    private void off(CommandSender s, String[] a) {
        Player p = self(s);
        if (p == null) {
            return;
        }
        Choices.Target t = a.length > 1 ? Choices.Target.byKey(a[1]) : null;
        if (t == null || t == Choices.Target.CHAT) {
            plugin.apply(p, Choices.Target.CHAT, null, true);
        }
        if (t == null || t == Choices.Target.NICK) {
            plugin.apply(p, Choices.Target.NICK, null, true);
        }
    }

    private void preview(CommandSender s, String[] a) {
        FxSpec spec = specArgs(a, 1);
        if (spec == null) {
            if (a.length < 2) {
                msg().send(s, "usage-preview");
                return;
            }
            unknown(s, a, 1);
            return;
        }
        int from = 1 + specWidth(a, 1);
        String text = a.length > from ? String.join(" ", Arrays.copyOfRange(a, from, a.length))
                : plugin.getConfig().getString("preview-text", "Пример текста с эффектом");
        msg().send(s, "preview-header", "effect", spec.effect().title(), "key", spec.effect().key());
        s.sendMessage(FxText.render(text, spec));
        if (specWidth(a, 1) == 1 && !a[1].contains(":")) {
            for (Palette p : new Palette[]{Palette.RED, Palette.AQUA, Palette.GOLD}) {
                s.sendMessage(Component.text(p.title() + ": ", NamedTextColor.DARK_GRAY)
                        .append(FxText.render(text, spec.withPalette(p))));
            }
        }
        s.sendMessage(Component.text("<fx:" + spec.effect().key() + ":" + spec.palette().key() + ">", NamedTextColor.DARK_GRAY)
                .clickEvent(ClickEvent.copyToClipboard("<fx:" + spec.effect().key() + ":" + spec.palette().key() + ">")));
    }

    private void list(CommandSender s) {
        msg().send(s, "list-header", "count", Effect.visible().size());
        TextComponent.Builder b = Component.text();
        boolean first = true;
        for (Effect e : Effect.visible()) {
            if (!first) {
                b.append(Component.text(" ", NamedTextColor.DARK_GRAY));
            }
            first = false;
            boolean allowed = s.hasPermission(e.permission());
            Component name = allowed ? FxText.render(e.key(), FxSpec.of(e))
                    : Component.text(e.key(), NamedTextColor.DARK_GRAY).decorate(TextDecoration.STRIKETHROUGH);
            b.append(name.hoverEvent(HoverEvent.showText(Component.text(e.title(), NamedTextColor.GOLD)))
                    .clickEvent(ClickEvent.runCommand("/textshader preview " + e.key())));
        }
        s.sendMessage(b.build());
        TextComponent.Builder c = Component.text().append(msg().get("list-colors"));
        for (Palette p : Palette.values()) {
            c.append(Component.text(p.key() + " ", TextColor.color(p.rgb())));
        }
        s.sendMessage(c.build());
    }

    private void code(CommandSender s, String[] a) {
        FxSpec spec = specArgs(a, 1);
        if (spec == null) {
            unknown(s, a, 1);
            return;
        }
        String hex = FxCode.hex(spec.code());
        msg().send(s, "code", "effect", FxText.render(spec.effect().title(), spec), "hex", hex);
    }

    private void giveName(CommandSender s, String[] a) {
        Player p = self(s);
        if (p == null) {
            return;
        }
        FxSpec spec = specArgs(a, 1);
        if (spec == null) {
            if (a.length < 2) {
                msg().send(s, "usage-give-name");
                return;
            }
            unknown(s, a, 1);
            return;
        }
        ItemStack it = p.getInventory().getItemInMainHand();
        if (it.getType().isAir()) {
            msg().send(p, "hold-item");
            return;
        }
        int from = 1 + specWidth(a, 1);
        String text = a.length > from ? String.join(" ", Arrays.copyOfRange(a, from, a.length))
                : PlainTextComponentSerializer.plainText().serialize(it.effectiveName());
        Component name = FxText.render(text, spec).decoration(TextDecoration.ITALIC, false);
        it.editMeta(m -> m.displayName(name));
        p.getInventory().setItemInMainHand(it);
        msg().send(p, "item-renamed", "name", name);
    }

    private void title(CommandSender s, String[] a) {
        if (a.length < 4) {
            msg().send(s, "usage-title");
            return;
        }
        List<Player> targets = new ArrayList<>();
        if ("*".equals(a[1]) || "@a".equals(a[1])) {
            targets.addAll(Bukkit.getOnlinePlayers());
        } else {
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) {
                msg().send(s, "player-not-found", "player", a[1]);
                return;
            }
            targets.add(t);
        }
        FxSpec spec = specArgs(a, 2);
        if (spec == null) {
            unknown(s, a, 2);
            return;
        }
        int from = 2 + specWidth(a, 2);
        if (a.length <= from) {
            msg().send(s, "usage-title");
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(a, from, a.length));
        String[] parts = text.split("\\s*\\|\\s*", 2);
        Component main = FxText.render(parts[0], spec);
        Component subtitle = parts.length > 1 ? FxText.render(parts[1], spec) : Component.empty();
        Title t = Title.title(main, subtitle, Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(600)));
        for (Player p : targets) {
            p.showTitle(t);
        }
        msg().send(s, "title-sent", "count", targets.size());
    }

    private void broadcast(CommandSender s, String[] a) {
        FxSpec spec = specArgs(a, 1);
        if (spec == null) {
            if (a.length < 3) {
                msg().send(s, "usage-broadcast");
                return;
            }
            unknown(s, a, 1);
            return;
        }
        int from = 1 + specWidth(a, 1);
        if (a.length <= from) {
            msg().send(s, "usage-broadcast");
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(a, from, a.length));
        Component line = ChatTags.hasTags(text) ? ChatListener.tagged(null, text, spec) : FxText.render(text, spec);
        Bukkit.broadcast(line);
    }

    private void player(CommandSender s, String[] a) {
        if (a.length < 4) {
            msg().send(s, "usage-player");
            return;
        }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) {
            msg().send(s, "player-not-found", "player", a[1]);
            return;
        }
        Choices.Target target = Choices.Target.byKey(a[2]);
        if (target == null) {
            msg().send(s, "usage-player");
            return;
        }
        FxSpec spec = "off".equalsIgnoreCase(a[3]) ? null : specArgs(a, 3);
        if (spec == null && !"off".equalsIgnoreCase(a[3])) {
            unknown(s, a, 3);
            return;
        }
        plugin.apply(t, target, spec, false);
        msg().send(s, "player-set", "player", t.getName(), "target", target.title(),
                "effect", spec == null ? msg().raw("off-word") : spec.effect().title() + " / " + spec.palette().title());
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        if (a.length <= 1) {
            for (String sub : SUBS) {
                if (ADMIN_SUBS.contains(sub) && !s.hasPermission(ADMIN)) {
                    continue;
                }
                out.add(sub);
            }
        } else {
            String sub = a[0].toLowerCase(Locale.ROOT);
            int effectArg = switch (sub) {
                case "set", "nick", "preview", "code", "give-name", "broadcast" -> 1;
                case "title" -> 2;
                case "player" -> 3;
                default -> -1;
            };
            int idx = a.length - 1;
            if ((sub.equals("title") || sub.equals("player")) && idx == 1) {
                return null;
            }
            if (sub.equals("player") && idx == 2) {
                out.add("chat");
                out.add("nick");
            } else if (sub.equals("off") && idx == 1) {
                out.add("chat");
                out.add("nick");
            } else if (sub.equals("menu") && idx == 1) {
                out.add("chat");
                out.add("nick");
            } else if (idx == effectArg) {
                if (sub.equals("set") || sub.equals("nick") || sub.equals("player")) {
                    out.add("off");
                }
                if (last.contains(":")) {
                    String e = last.substring(0, last.indexOf(':'));
                    for (Palette p : Palette.values()) {
                        out.add(e + ":" + p.key());
                    }
                } else {
                    for (Effect e : Effect.visible()) {
                        out.add(e.key());
                    }
                }
            } else if (effectArg > 0 && idx == effectArg + 1 && !a[effectArg].contains(":")) {
                for (Palette p : Palette.values()) {
                    out.add(p.key());
                }
            }
        }
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
