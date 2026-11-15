package core.amazake.systems.corenpc.dialog;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Печать текста по буквам с учётом тегов MiniMessage: теги не считаются буквами и не режутся пополам, перед n-й
 * буквой выводятся все теги, стоящие до неё. {@code <wait:N>} — пауза N тиков (в вывод не попадает).
 * {@code \<} — экранированная скобка, считается одной буквой. Чистая логика.
 */
public final class Typewriter {

    /** Тег MiniMessage: &lt;имя…&gt; / &lt;/имя&gt; / &lt;#hex&gt; / &lt;!имя&gt;; аргументы в кавычках могут содержать &gt;. */
    private static final Pattern TAG = Pattern.compile("<[/!?#]?[a-zA-Z0-9_#][^<>\"']*(?:(?:\"[^\"]*\"|'[^']*')[^<>\"']*)*>");
    private static final Pattern WAIT = Pattern.compile("<wait:(\\d{1,5})>", Pattern.CASE_INSENSITIVE);

    private sealed interface Token permits Tag, Letter, Wait {
    }

    private record Tag(String text) implements Token {
    }

    private record Letter(String text) implements Token {
    }

    private record Wait(int ticks) implements Token {
    }

    private final List<Token> tokens;
    private final int length;

    private Typewriter(final List<Token> tokens) {
        this.tokens = tokens;
        int n = 0;
        for (final Token t : tokens) {
            if (t instanceof Letter) {
                n++;
            }
        }
        this.length = n;
    }

    public static Typewriter of(final String mini) {
        final List<Token> out = new ArrayList<>();
        final String s = mini == null ? "" : mini;
        final Matcher m = TAG.matcher(s);
        int i = 0;
        while (i < s.length()) {
            final char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && (s.charAt(i + 1) == '<' || s.charAt(i + 1) == '\\')) {
                out.add(new Letter(s.substring(i, i + 2)));
                i += 2;
                continue;
            }
            if (c == '<' && m.find(i) && m.start() == i) {
                final String tag = m.group();
                final Matcher w = WAIT.matcher(tag);
                if (w.matches()) {
                    out.add(new Wait(Integer.parseInt(w.group(1))));
                } else {
                    out.add(new Tag(tag));
                }
                i = m.end();
                continue;
            }
            final int cp = s.codePointAt(i);
            final int len = Character.charCount(cp);
            out.add(new Letter(s.substring(i, i + len)));
            i += len;
        }
        return new Typewriter(List.copyOf(out));
    }

    /** Сколько букв (видимых символов). */
    public int length() {
        return this.length;
    }

    /** MiniMessage с первыми n буквами (n ≥ length — весь текст без &lt;wait&gt;). */
    public String slice(final int n) {
        final StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (final Token t : this.tokens) {
            if (t instanceof Letter l) {
                if (shown >= n) {
                    break;
                }
                sb.append(l.text());
                shown++;
            } else if (t instanceof Tag g) {
                if (shown >= n && n < this.length) {
                    // закрывающие теги после последней буквы можно не выводить; открывающие тем более
                    break;
                }
                sb.append(g.text());
            }
        }
        return sb.toString();
    }

    /** Весь текст без пауз. */
    public String full() {
        return this.slice(Integer.MAX_VALUE);
    }

    /** Пауза (тиков) после n-й буквы — сумма &lt;wait&gt; между n-й и (n+1)-й буквой. */
    public int waitAfter(final int n) {
        int shown = 0;
        int sum = 0;
        for (final Token t : this.tokens) {
            if (t instanceof Letter) {
                if (shown == n) {
                    return sum;
                }
                shown++;
            } else if (t instanceof Wait w && shown == n) {
                sum += w.ticks();
            }
        }
        return shown == n ? sum : 0;
    }
}
