package core.amazake.systems.corenpc.dialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Условие ответа: {@code permission: узел}, {@code !permission: узел}, {@code papi: %плейсхолдер% >= 10}
 * (операторы {@code == != >= <= > < contains}; числа сравниваются как числа, иначе строки без учёта регистра).
 * Несколько вариантов через {@code ||} — достаточно любого; список условий — нужны все.
 */
public final class Condition {

    /** Что условию нужно от игрока. */
    public interface Env {
        boolean hasPermission(String node);

        /** Подставить плейсхолдеры (PlaceholderAPI); без него — строка как есть. */
        String placeholders(String text);
    }

    private record Atom(boolean negate, String kind, String left, String op, String right) {
    }

    private final String source;
    private final List<Atom> any;

    private Condition(final String source, final List<Atom> any) {
        this.source = source;
        this.any = any;
    }

    public String source() {
        return this.source;
    }

    private static final String[] OPS = {"==", "!=", ">=", "<=", ">", "<", " contains ", "="};

    /** Разобрать; IllegalArgumentException — с понятным текстом. */
    public static Condition parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("пустое условие");
        }
        final List<Atom> atoms = new ArrayList<>();
        for (final String part : raw.split("\\|\\|")) {
            String s = part.strip();
            boolean neg = false;
            if (s.startsWith("!")) {
                neg = true;
                s = s.substring(1).strip();
            }
            final int colon = s.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("условие «" + part.strip() + "»: нужно permission: … или papi: …");
            }
            final String kind = s.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            final String body = s.substring(colon + 1).strip();
            switch (kind) {
                case "permission", "perm" -> {
                    if (body.isEmpty()) {
                        throw new IllegalArgumentException("условие «" + part.strip() + "»: не указано право");
                    }
                    atoms.add(new Atom(neg, "permission", body, null, null));
                }
                case "papi", "placeholder" -> {
                    String op = null;
                    int at = -1;
                    for (final String o : OPS) {
                        at = body.indexOf(o);
                        if (at > 0) {
                            op = o.strip();
                            break;
                        }
                    }
                    if (op == null) {
                        throw new IllegalArgumentException("условие «" + part.strip() + "»: нет оператора (== != >= <= > < contains)");
                    }
                    final String opRaw = body.substring(at).startsWith(" contains ") ? " contains " : op;
                    atoms.add(new Atom(neg, "papi", body.substring(0, at).strip(), op.equals("=") ? "==" : op,
                        body.substring(at + opRaw.length()).strip()));
                }
                default -> throw new IllegalArgumentException("условие «" + part.strip() + "»: неизвестный тип «" + kind + "»");
            }
        }
        return new Condition(raw.strip(), List.copyOf(atoms));
    }

    public boolean test(final Env env) {
        for (final Atom a : this.any) {
            final boolean r = switch (a.kind()) {
                case "permission" -> env.hasPermission(a.left());
                default -> compare(env.placeholders(a.left()), a.op(), env.placeholders(a.right()));
            };
            if (r != a.negate()) {
                return true;
            }
        }
        return false;
    }

    /** Все условия списка выполнены (пустой список — да). */
    public static boolean all(final List<Condition> list, final Env env) {
        for (final Condition c : list) {
            if (!c.test(env)) {
                return false;
            }
        }
        return true;
    }

    static boolean compare(final String left, final String op, final String right) {
        final String l = left == null ? "" : left.strip();
        final String r = right == null ? "" : right.strip();
        if (op.equals("contains")) {
            return l.toLowerCase(Locale.ROOT).contains(r.toLowerCase(Locale.ROOT));
        }
        final Double ln = num(l);
        final Double rn = num(r);
        if (ln != null && rn != null) {
            final int c = Double.compare(ln, rn);
            return switch (op) {
                case "==" -> c == 0;
                case "!=" -> c != 0;
                case ">=" -> c >= 0;
                case "<=" -> c <= 0;
                case ">" -> c > 0;
                case "<" -> c < 0;
                default -> false;
            };
        }
        final int c = l.compareToIgnoreCase(r);
        return switch (op) {
            case "==" -> c == 0;
            case "!=" -> c != 0;
            case ">=" -> c >= 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            case "<" -> c < 0;
            default -> false;
        };
    }

    private static Double num(final String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (final NumberFormatException e) {
            return null;
        }
    }
}
