package core.amazake.systems.corepython.host;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.graalvm.polyglot.PolyglotException;

/** Ошибки Python в лог: с трассой Python, одинаковые — не чаще раза в 30 секунд (с числом пропущенных). */
final class ErrorLog {

    private static final long INTERVAL = TimeUnit.SECONDS.toNanos(30);

    private final Logger logger;
    private final String prefix;
    private final Map<String, long[]> last = new ConcurrentHashMap<>();

    ErrorLog(final Logger logger, final String prefix) {
        this.logger = logger;
        this.prefix = prefix;
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
            this.logger.severe(this.prefix + "ошибка в " + where + ": " + format(e)
                + (skipped > 0 ? "\n  (ещё " + skipped + " таких же за 30 с не показаны)" : ""));
        }
        if (this.last.size() > 512) {
            this.last.clear();
        }
    }

    /** «Тип: сообщение» и кадры Python (файл, строка, функция); для ошибок Java — первые кадры Java. */
    static String format(final Throwable e) {
        final StringBuilder b = new StringBuilder(String.valueOf(e.getMessage()));
        if (e instanceof PolyglotException pe) {
            int n = 0;
            for (final PolyglotException.StackFrame f : pe.getPolyglotStackTrace()) {
                if (n++ > 24) {
                    b.append("\n    …");
                    break;
                }
                if (f.isGuestFrame()) {
                    final var loc = f.getSourceLocation();
                    b.append("\n    File \"").append(loc == null ? "?" : loc.getSource().getName()).append("\", line ")
                        .append(loc == null ? "?" : loc.getStartLine()).append(", in ").append(f.getRootName());
                } else if (pe.isHostException() && n < 6) {
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
}
