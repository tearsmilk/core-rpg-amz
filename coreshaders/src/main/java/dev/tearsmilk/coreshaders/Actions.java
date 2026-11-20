package dev.tearsmilk.coreshaders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

public final class Actions {
    public enum Executor {
        PLAYER,
        CONSOLE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Executor parse(String s) {
            if (s == null) {
                return null;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "player", "игрок", "p" -> PLAYER;
                case "console", "консоль", "c" -> CONSOLE;
                default -> null;
            };
        }
    }

    public enum Trigger {
        ENTER,
        CLICK,
        BOTH,
        OFF;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean onEnter() {
            return this == ENTER || this == BOTH;
        }

        public boolean onClick() {
            return this == CLICK || this == BOTH;
        }

        public static Trigger parse(String s) {
            if (s == null) {
                return null;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "enter", "вход", "walk" -> ENTER;
                case "click", "клик", "rmb", "пкм" -> CLICK;
                case "both", "оба", "all" -> BOTH;
                case "off", "нет", "none" -> OFF;
                default -> null;
            };
        }
    }

    public record Action(Executor executor, String command) {
        public String encode() {
            return executor.id() + ":" + command;
        }

        public static Action decode(String s) {
            if (s == null) {
                return null;
            }
            int i = s.indexOf(':');
            if (i <= 0) {
                return null;
            }
            Executor ex = Executor.parse(s.substring(0, i));
            String cmd = clean(s.substring(i + 1));
            if (ex == null || cmd.isEmpty()) {
                return null;
            }
            return new Action(ex, cmd);
        }
    }

    public record Data(List<Action> actions, Trigger trigger, double cooldown, String permission, String message,
        String group) {
        public Data {
            actions = Collections.unmodifiableList(new ArrayList<>(actions));
        }

        public boolean active() {
            return !actions.isEmpty() && trigger != Trigger.OFF;
        }

        public Data withActions(List<Action> a) {
            return new Data(a, trigger, cooldown, permission, message, group);
        }

        public Data withTrigger(Trigger t) {
            return new Data(actions, t, cooldown, permission, message, group);
        }

        public Data withCooldown(double c) {
            return new Data(actions, trigger, c, permission, message, group);
        }

        public Data withPermission(String p) {
            return new Data(actions, trigger, cooldown, p, message, group);
        }

        public Data withMessage(String m) {
            return new Data(actions, trigger, cooldown, permission, m, group);
        }

        public Data withGroup(String g) {
            return new Data(actions, trigger, cooldown, permission, message, g);
        }
    }

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.]{1,16}");

    private Actions() {
    }

    public static boolean safeName(String name) {
        return name != null && SAFE_NAME.matcher(name).matches();
    }

    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (s.startsWith("/")) {
            s = s.substring(1).trim();
        }
        return s;
    }

    public static String fill(String template, Map<String, String> values) {
        return fill(template, values, UnaryOperator.identity());
    }

    public static String fill(String template, Map<String, String> values, UnaryOperator<String> escape) {
        StringBuilder out = new StringBuilder(template.length() + 32);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int j = template.indexOf('}', i + 1);
                if (j > i) {
                    String key = template.substring(i + 1, j).toLowerCase(Locale.ROOT);
                    String v = values.get(key);
                    if (v != null) {
                        out.append(escape.apply(v));
                        i = j + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    public static Double parseCooldown(String raw) {
        if (raw == null) {
            return null;
        }
        double v;
        try {
            v = Double.parseDouble(raw.trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0.0 || v > 86400.0) {
            return null;
        }
        return v;
    }

    public static boolean ready(Long last, long now, double cooldownSeconds) {
        return last == null || now - last >= (long) (cooldownSeconds * 1000.0);
    }
}
