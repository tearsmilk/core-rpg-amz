package core.amazake.systems.corevault.level;

import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * Формула кривой опыта: числа, переменная {@code level} (или {@code lvl}, {@code x}), {@code + - * / % ^},
 * скобки, функции {@code floor ceil round sqrt abs ln log10 min max}. {@code ^} — правоассоциативная степень.
 * Разбирается один раз в дерево замыканий; вычисление — без выделения памяти.
 */
public final class Expr {

    private final String text;
    private final DoubleUnaryOperator fn;

    private Expr(final String text, final DoubleUnaryOperator fn) {
        this.text = text;
        this.fn = fn;
    }

    public static Expr parse(final String text) {
        final Parser p = new Parser(text);
        final DoubleUnaryOperator f = p.expr();
        p.skip();
        if (p.pos < p.s.length()) {
            throw new IllegalArgumentException("лишнее в формуле с позиции " + (p.pos + 1) + ": «" + p.s.substring(p.pos) + "»");
        }
        return new Expr(text, f);
    }

    public double eval(final double level) {
        return this.fn.applyAsDouble(level);
    }

    @Override
    public String toString() {
        return this.text;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(final String s) {
            this.s = s == null ? "" : s.toLowerCase(Locale.ROOT);
        }

        void skip() {
            while (this.pos < this.s.length() && Character.isWhitespace(this.s.charAt(this.pos))) {
                this.pos++;
            }
        }

        boolean eat(final char c) {
            this.skip();
            if (this.pos < this.s.length() && this.s.charAt(this.pos) == c) {
                this.pos++;
                return true;
            }
            return false;
        }

        DoubleUnaryOperator expr() {
            DoubleUnaryOperator left = this.term();
            while (true) {
                if (this.eat('+')) {
                    final DoubleUnaryOperator a = left;
                    final DoubleUnaryOperator b = this.term();
                    left = x -> a.applyAsDouble(x) + b.applyAsDouble(x);
                } else if (this.eat('-')) {
                    final DoubleUnaryOperator a = left;
                    final DoubleUnaryOperator b = this.term();
                    left = x -> a.applyAsDouble(x) - b.applyAsDouble(x);
                } else {
                    return left;
                }
            }
        }

        DoubleUnaryOperator term() {
            DoubleUnaryOperator left = this.unary();
            while (true) {
                if (this.eat('*')) {
                    final DoubleUnaryOperator a = left;
                    final DoubleUnaryOperator b = this.unary();
                    left = x -> a.applyAsDouble(x) * b.applyAsDouble(x);
                } else if (this.eat('/')) {
                    final DoubleUnaryOperator a = left;
                    final DoubleUnaryOperator b = this.unary();
                    left = x -> a.applyAsDouble(x) / b.applyAsDouble(x);
                } else if (this.eat('%')) {
                    final DoubleUnaryOperator a = left;
                    final DoubleUnaryOperator b = this.unary();
                    left = x -> a.applyAsDouble(x) % b.applyAsDouble(x);
                } else {
                    return left;
                }
            }
        }

        DoubleUnaryOperator unary() {
            if (this.eat('-')) {
                final DoubleUnaryOperator a = this.unary();
                return x -> -a.applyAsDouble(x);
            }
            if (this.eat('+')) {
                return this.unary();
            }
            return this.power();
        }

        DoubleUnaryOperator power() {
            final DoubleUnaryOperator base = this.atom();
            if (this.eat('^')) {
                final DoubleUnaryOperator exp = this.unary();
                return x -> Math.pow(base.applyAsDouble(x), exp.applyAsDouble(x));
            }
            return base;
        }

        DoubleUnaryOperator atom() {
            this.skip();
            if (this.eat('(')) {
                final DoubleUnaryOperator e = this.expr();
                if (!this.eat(')')) {
                    throw this.error("нет закрывающей скобки");
                }
                return e;
            }
            if (this.pos >= this.s.length()) {
                throw this.error("формула оборвалась");
            }
            final char c = this.s.charAt(this.pos);
            if (Character.isDigit(c) || c == '.') {
                final int start = this.pos;
                while (this.pos < this.s.length() && (Character.isDigit(this.s.charAt(this.pos)) || this.s.charAt(this.pos) == '.')) {
                    this.pos++;
                }
                final double v;
                try {
                    v = Double.parseDouble(this.s.substring(start, this.pos));
                } catch (final NumberFormatException e) {
                    throw this.error("не число «" + this.s.substring(start, this.pos) + "»");
                }
                return x -> v;
            }
            if (Character.isLetter(c)) {
                final int start = this.pos;
                while (this.pos < this.s.length() && (Character.isLetterOrDigit(this.s.charAt(this.pos)) || this.s.charAt(this.pos) == '_')) {
                    this.pos++;
                }
                final String name = this.s.substring(start, this.pos);
                switch (name) {
                    case "level", "lvl", "x" -> {
                        return x -> x;
                    }
                    case "pi" -> {
                        return x -> Math.PI;
                    }
                    case "e" -> {
                        return x -> Math.E;
                    }
                    default -> {
                        return this.function(name);
                    }
                }
            }
            throw this.error("непонятный символ «" + c + "»");
        }

        DoubleUnaryOperator function(final String name) {
            if (!this.eat('(')) {
                throw this.error("неизвестное имя «" + name + "» (есть level, функции floor ceil round sqrt abs ln log10 min max)");
            }
            final DoubleUnaryOperator a = this.expr();
            DoubleUnaryOperator b = null;
            if (this.eat(',')) {
                b = this.expr();
            }
            if (!this.eat(')')) {
                throw this.error("нет закрывающей скобки у " + name + "(…)");
            }
            final DoubleUnaryOperator b2 = b;
            final boolean two = name.equals("min") || name.equals("max");
            if (two != (b2 != null)) {
                throw this.error(name + "(…): " + (two ? "нужно два аргумента" : "нужен один аргумент"));
            }
            return switch (name) {
                case "floor" -> x -> Math.floor(a.applyAsDouble(x));
                case "ceil" -> x -> Math.ceil(a.applyAsDouble(x));
                case "round" -> x -> Math.rint(a.applyAsDouble(x));
                case "sqrt" -> x -> Math.sqrt(a.applyAsDouble(x));
                case "abs" -> x -> Math.abs(a.applyAsDouble(x));
                case "ln" -> x -> Math.log(a.applyAsDouble(x));
                case "log10" -> x -> Math.log10(a.applyAsDouble(x));
                case "min" -> x -> Math.min(a.applyAsDouble(x), b2.applyAsDouble(x));
                case "max" -> x -> Math.max(a.applyAsDouble(x), b2.applyAsDouble(x));
                default -> throw this.error("неизвестная функция «" + name + "»");
            };
        }

        IllegalArgumentException error(final String what) {
            return new IllegalArgumentException(what + " (позиция " + (this.pos + 1) + ")");
        }
    }
}
