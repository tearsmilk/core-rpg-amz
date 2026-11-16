package core.amazake.systems.corepython.host;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

/** Ошибки Python в лог: с трассой Python, одинаковые — не чаще раза в 30 секунд (с числом пропущенных). */
final class ErrorLog {

    private static final long INTERVAL = TimeUnit.SECONDS.toNanos(30);

    private final Logger logger;
    private final String prefix;
    private final Map<String, long[]> last = new ConcurrentHashMap<>();
    /** Папка системы: пути файлов внутри неё в трассах — относительные. */
    private final Path base;

    ErrorLog(final Logger logger, final String prefix) {
        this(logger, prefix, null);
    }

    ErrorLog(final Logger logger, final String prefix, final Path base) {
        this.logger = logger;
        this.prefix = prefix;
        this.base = base;
    }

    void report(final String where, final Throwable e) {
        final String message = String.valueOf(e.getMessage());
        final String key = where + '|' + message;
        final long now = System.nanoTime();
        final long[] slot = this.last.computeIfAbsent(key, k -> new long[]{now - INTERVAL - 1, 0});
        synchronized (slot) {
            if (now - slot[0] < INTERVAL) {
                slot[1]++;
                return;
            }
            final long skipped = slot[1];
            slot[0] = now;
            slot[1] = 0;
            this.logger.severe(this.prefix + "ошибка в " + where + ": " + format(e, this.base)
                + (skipped > 0 ? "\n  (ещё " + skipped + " таких же за 30 с не показаны)" : ""));
        }
        if (this.last.size() > 512) {
            this.last.clear();
        }
    }

    static String format(final Throwable e) {
        return format(e, null);
    }

    /**
     * «Тип: сообщение» и кадры Python (файл, строка, функция; сначала самый внутренний); для ошибок Java — первые кадры
     * Java. Кадры Python — из __traceback__ исключения (точная строка; кадры polyglot дают только начало функции);
     * пути внутри папки системы base — относительные. Кадры переводчика японского режима (amazake_ja.py) не показываются.
     */
    static String format(final Throwable e, final Path base) {
        final StringBuilder b = new StringBuilder(String.valueOf(e.getMessage()));
        if (e instanceof PolyglotException pe) {
            syntaxLocation(pe, b, base);
            if (!pythonFrames(pe, b, base)) {
                int n = 0;
                for (final PolyglotException.StackFrame f : pe.getPolyglotStackTrace()) {
                    if (n++ > 24) {
                        b.append("\n    …");
                        break;
                    }
                    if (f.isGuestFrame()) {
                        final var loc = f.getSourceLocation();
                        b.append("\n    File \"").append(loc == null ? "?" : shortPath(loc.getSource().getName(), base)).append("\", line ")
                            .append(loc == null ? "?" : loc.getStartLine()).append(", in ").append(f.getRootName());
                    } else if (pe.isHostException() && n < 6) {
                        b.append("\n    (java) ").append(f);
                    }
                }
            } else if (pe.isHostException()) {
                int n = 0;
                for (final PolyglotException.StackFrame f : pe.getPolyglotStackTrace()) {
                    if (f.isGuestFrame() || n++ >= 5) {
                        break;
                    }
                    b.append("\n    (java) ").append(f);
                }
            }
            if (pe.isHostException()) {
                final Throwable host = pe.asHostException();
                b.append("\n    причина в Java: ").append(host);
            }
        } else {
            b.insert(0, e.getClass().getSimpleName() + ": ");
        }
        return b.toString();
    }

    private static String shortPath(final String file, final Path base) {
        if (file == null) {
            return "?";
        }
        if (base != null) {
            try {
                final Path p = Path.of(file).toAbsolutePath().normalize();
                final Path b = base.toAbsolutePath().normalize();
                if (p.startsWith(b)) {
                    return b.relativize(p).toString();
                }
            } catch (final RuntimeException ignored) {
                // не путь (например, «<string>»)
            }
        }
        return file;
    }

    private static String str(final Value v) {
        return v == null || v.isNull() ? "?" : v.isString() ? v.asString() : v.toString();
    }

    /** Кадры из __traceback__ исключения Python; false — их нет (ошибка Java без кадров Python и т.п.). */
    private static boolean pythonFrames(final PolyglotException pe, final StringBuilder b, final Path base) {
        if (!pe.isGuestException() || pe.isHostException()) {
            return false;
        }
        try {
            final Value g = pe.getGuestObject();
            if (g == null || g.isNull() || !g.hasMember("__traceback__")) {
                return false;
            }
            final List<String> frames = new ArrayList<>();
            Value tb = g.getMember("__traceback__");
            while (tb != null && !tb.isNull() && frames.size() < 200) {
                final Value code = tb.getMember("tb_frame").getMember("f_code");
                final String file = str(code.getMember("co_filename"));
                if (!file.endsWith("amazake_ja.py")) {
                    frames.add("\n    File \"" + shortPath(file, base) + "\", line " + str(tb.getMember("tb_lineno")) + ", in "
                        + str(code.getMember("co_name")));
                }
                tb = tb.getMember("tb_next");
            }
            if (frames.isEmpty()) {
                return g.hasMember("lineno");   // SyntaxError: место уже напечатано
            }
            Collections.reverse(frames);
            int n = 0;
            for (final String f : frames) {
                if (n++ >= 25) {
                    b.append("\n    …");
                    break;
                }
                b.append(f);
            }
            return true;
        } catch (final RuntimeException ex) {
            return false;
        }
    }

    /**
     * SyntaxError: у него нет кадра в трассе — файл, строка и сама строка исходника из полей исключения (в японском
     * режиме переводчик кладёт туда исходную, непереведённую строку).
     */
    private static void syntaxLocation(final PolyglotException pe, final StringBuilder b, final Path base) {
        if (!pe.isGuestException() || pe.isHostException()) {
            return;
        }
        try {
            final Value g = pe.getGuestObject();
            if (g == null || g.isNull() || !g.hasMember("lineno") || !g.hasMember("filename") || !g.hasMember("text")) {
                return;
            }
            final Value file = g.getMember("filename");
            final Value line = g.getMember("lineno");
            if (file == null || file.isNull() || line == null || !line.isNumber()) {
                return;
            }
            b.append("\n    File \"").append(shortPath(str(file), base)).append("\", line ").append(line.asLong());
            final Value text = g.getMember("text");
            if (text != null && text.isString() && !text.asString().isBlank()) {
                b.append("\n      ").append(text.asString().strip());
            }
        } catch (final RuntimeException ignored) {
            // контекст закрыт или объект необычный — без строки
        }
    }
}
