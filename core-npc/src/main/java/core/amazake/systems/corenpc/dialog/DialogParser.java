package core.amazake.systems.corenpc.dialog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * dialogs/&lt;id&gt;.yml → {@link Dialog} и список проблем. Ошибки (битая ссылка, нет страниц) — диалог всё равно
 * загружается, но битые переходы закрывают его; предупреждения — недостижимые страницы, лишние строки и т. п.
 */
public final class DialogParser {

    /** Строк текста на странице (больше не помещается в окно над хотбаром). */
    public static final int MAX_LINES = 3;

    public record Result(Dialog dialog, List<String> errors, List<String> warnings) {
    }

    private DialogParser() {
    }

    /** Цель перехода: «end»/«close»/пусто → null (закрыть). */
    public static String normalizeTarget(final String raw) {
        if (raw == null) {
            return null;
        }
        final String s = raw.strip();
        if (s.isEmpty()) {
            return null;
        }
        final String low = s.toLowerCase(Locale.ROOT);
        return low.equals("end") || low.equals("close") || low.equals("exit") ? null : s;
    }

    public static Result parse(final String id, final ConfigurationSection root) {
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final String defSpeaker = root.getString("speaker", "");
        final double defSpeed = root.getDouble("speed", 0);
        final String defSound = root.getString("sound", "");
        final Map<String, Page> pages = new LinkedHashMap<>();
        final ConfigurationSection ps = root.getConfigurationSection("pages");
        if (ps == null || ps.getKeys(false).isEmpty()) {
            errors.add("нет ни одной страницы (раздел pages)");
        } else {
            for (final String pid : ps.getKeys(false)) {
                final ConfigurationSection p = ps.getConfigurationSection(pid);
                if (p == null) {
                    errors.add("страница «" + pid + "»: должна быть разделом");
                    continue;
                }
                pages.put(pid, page(pid, p, defSpeaker, defSpeed, defSound, errors, warnings));
            }
        }
        String start = root.getString("start");
        if (start == null || start.isBlank()) {
            start = pages.isEmpty() ? "" : pages.keySet().iterator().next();
        } else if (!pages.containsKey(start)) {
            errors.add("start: нет страницы «" + start + "»");
        }
        final List<Action> onEnd = actions(root.getStringList("on-end"), "on-end", errors);
        final String mode = root.getString("mode", "");
        final Dialog d = new Dialog(id, start, pages, onEnd, mode == null ? "" : mode.strip().toLowerCase(Locale.ROOT));
        validate(d, errors, warnings);
        return new Result(d, List.copyOf(errors), List.copyOf(warnings));
    }

    private static Page page(final String pid, final ConfigurationSection p, final String defSpeaker, final double defSpeed,
                             final String defSound, final List<String> errors, final List<String> warnings) {
        final String where = "страница «" + pid + "»";
        List<String> lines = p.isList("lines") ? p.getStringList("lines") : List.of();
        if (lines.isEmpty() && p.isString("text")) {
            lines = List.of(p.getString("text", "").split("\n", -1));
        }
        if (lines.isEmpty() && p.isString("lines")) {
            lines = List.of(p.getString("lines", "").split("\n", -1));
        }
        if (lines.size() > MAX_LINES) {
            warnings.add(where + ": " + lines.size() + " строк, в окно помещается " + MAX_LINES + " — лишние не показываются");
            lines = lines.subList(0, MAX_LINES);
        }
        final List<Answer> answers = new ArrayList<>();
        final List<?> raw = p.getList("answers", List.of());
        for (int i = 0; i < raw.size(); i++) {
            final Object o = raw.get(i);
            final String aw = where + ", ответ " + (i + 1);
            if (o instanceof String s) {
                answers.add(new Answer(s, List.of(), List.of(), null));
            } else if (o instanceof Map<?, ?> m) {
                final Object text = m.get("text");
                if (text == null) {
                    errors.add(aw + ": нет text");
                    continue;
                }
                final List<Condition> req = new ArrayList<>();
                for (final String c : strings(m.containsKey("requires") ? m.get("requires") : m.get("if"))) {
                    try {
                        req.add(Condition.parse(c));
                    } catch (final IllegalArgumentException e) {
                        errors.add(aw + ": " + e.getMessage());
                    }
                }
                final Object go = m.containsKey("goto") ? m.get("goto") : m.get("next");
                answers.add(new Answer(String.valueOf(text), req, actions(strings(m.get("actions")), aw, errors),
                    normalizeTarget(go == null ? null : String.valueOf(go))));
            } else {
                errors.add(aw + ": ожидается строка или раздел с text");
            }
        }
        final String next = normalizeTarget(p.getString("next"));
        if (!answers.isEmpty() && next != null) {
            warnings.add(where + ": есть ответы — next не используется");
        }
        return new Page(pid, p.getString("speaker", defSpeaker), lines, p.getDouble("speed", defSpeed),
            p.getString("sound", defSound), actions(p.getStringList("actions"), where, errors), answers, next);
    }

    private static List<String> strings(final Object o) {
        if (o == null) {
            return List.of();
        }
        if (o instanceof List<?> l) {
            final List<String> out = new ArrayList<>();
            for (final Object x : l) {
                if (x != null) {
                    out.add(String.valueOf(x));
                }
            }
            return out;
        }
        return List.of(String.valueOf(o));
    }

    private static List<Action> actions(final List<String> raw, final String where, final List<String> errors) {
        final List<Action> out = new ArrayList<>();
        for (final String s : raw) {
            final Action a = Action.parse(s);
            if (a == null) {
                continue;
            }
            if ((a.type() == Action.Type.PLAYER || a.type() == Action.Type.CONSOLE) && a.value().isBlank()) {
                errors.add(where + ": пустая команда «" + s + "»");
                continue;
            }
            out.add(a);
        }
        return out;
    }

    /** Переходы со страницы: ответы, next, goto в действиях (страницы и ответов). */
    static List<String> targets(final Page p) {
        final List<String> out = new ArrayList<>();
        for (final Answer a : p.answers()) {
            if (a.target() != null) {
                out.add(a.target());
            }
            for (final Action x : a.actions()) {
                if (x.type() == Action.Type.GOTO) {
                    out.add(x.value());
                }
            }
        }
        if (p.answers().isEmpty() && p.next() != null) {
            out.add(p.next());
        }
        for (final Action x : p.actions()) {
            if (x.type() == Action.Type.GOTO) {
                out.add(x.value());
            }
        }
        return out;
    }

    /** Ссылка «диалог:страница» на другой диалог? (у своих страниц двоеточия нет). */
    public static boolean external(final String target) {
        return target != null && target.indexOf(':') > 0;
    }

    static void validate(final Dialog d, final List<String> errors, final List<String> warnings) {
        for (final Page p : d.pages().values()) {
            for (final String t : targets(p)) {
                if (!external(t) && !d.pages().containsKey(t)) {
                    errors.add("страница «" + p.id() + "»: переход на несуществующую страницу «" + t + "»");
                }
            }
        }
        if (!d.pages().containsKey(d.start())) {
            return;
        }
        final Set<String> seen = new HashSet<>();
        final Deque<String> queue = new ArrayDeque<>();
        queue.add(d.start());
        while (!queue.isEmpty()) {
            final String id = queue.poll();
            if (!seen.add(id)) {
                continue;
            }
            final Page p = d.page(id);
            if (p == null) {
                continue;
            }
            for (final String t : targets(p)) {
                if (!external(t)) {
                    queue.add(t);
                }
            }
        }
        for (final String id : d.pages().keySet()) {
            if (!seen.contains(id)) {
                warnings.add("страница «" + id + "»: до неё нельзя дойти со стартовой «" + d.start() + "»");
            }
        }
    }

    /** Проверка ссылок «диалог:страница» между загруженными диалогами. */
    public static List<String> validateExternal(final Map<String, Dialog> all) {
        final List<String> errors = new ArrayList<>();
        for (final Dialog d : all.values()) {
            for (final Page p : d.pages().values()) {
                for (final String t : targets(p)) {
                    if (!external(t)) {
                        continue;
                    }
                    final int c = t.indexOf(':');
                    final Dialog other = all.get(t.substring(0, c));
                    final String page = t.substring(c + 1);
                    if (other == null) {
                        errors.add(d.id() + ", страница «" + p.id() + "»: нет диалога «" + t.substring(0, c) + "»");
                    } else if (!page.isEmpty() && !other.pages().containsKey(page)) {
                        errors.add(d.id() + ", страница «" + p.id() + "»: в диалоге «" + other.id() + "» нет страницы «" + page + "»");
                    }
                }
            }
        }
        return errors;
    }
}
