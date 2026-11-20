package dev.tearsmilk.tabshader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class Texts {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private FileConfiguration config;
    private BiFunction<Player, String, String> external = (p, s) -> s;
    private boolean noExternal = true;

    public Texts(FileConfiguration config) {
        this.config = config;
    }

    public void reload(FileConfiguration config) {
        this.config = config;
    }

    public void external(BiFunction<Player, String, String> f) {
        this.external = f == null ? (p, s) -> s : f;
        this.noExternal = f == null;
    }

    public MiniMessage mini() {
        return mm;
    }

    public static String fill(String template, Map<String, String> vars, MiniMessage mm) {
        return fill(template, vars, mm, null);
    }

    public static String fill(String template, Map<String, String> vars, MiniMessage mm, Function<String, String> external) {
        if (template == null) {
            return "";
        }
        if (template.indexOf('{') < 0 && (external == null || template.indexOf('%') < 0)) {
            return template;
        }
        StringBuilder out = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i + 1);
                if (end > i) {
                    String name = template.substring(i + 1, end);
                    String v = vars.get(name);
                    if (v != null) {
                        out.append(mm.escapeTags(v));
                        i = end + 1;
                        continue;
                    }
                }
            } else if (c == '%' && external != null) {
                int end = template.indexOf('%', i + 1);
                if (end > i + 1 && isToken(template, i + 1, end)) {
                    String token = template.substring(i, end + 1);
                    String v = external.apply(token);
                    out.append(mm.escapeTags(v == null ? token : v));
                    i = end + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    static boolean isToken(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '<' || c == '>' || c == '{' || c == '}') {
                return false;
            }
        }
        return true;
    }

    public Component render(Player p, String template, Map<String, String> vars) {
        Function<String, String> ext = p == null || noExternal ? null : token -> external.apply(p, token);
        return mm.deserialize(fill(template, vars, mm, ext));
    }

    public List<Component> render(Player p, List<String> templates, Map<String, String> vars) {
        List<Component> out = new ArrayList<>(templates.size());
        for (String t : templates) {
            out.add(render(p, t, vars));
        }
        return out;
    }

    public String raw(String key) {
        String s = config.getString("messages." + key);
        return s == null ? key : s;
    }

    public Component get(String key, Object... pairs) {
        List<TagResolver> resolvers = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String name = String.valueOf(pairs[i]);
            Object v = pairs[i + 1];
            if (v instanceof Component c) {
                resolvers.add(Placeholder.component(name, c));
            } else {
                resolvers.add(Placeholder.unparsed(name, String.valueOf(v)));
            }
        }
        return mm.deserialize(raw(key), TagResolver.resolver(resolvers));
    }

    public void send(CommandSender to, String key, Object... pairs) {
        to.sendMessage(get(key, pairs));
    }
}
