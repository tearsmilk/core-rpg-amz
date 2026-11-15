package core.amazake.systems.corenpc.dialog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Состояние открытого диалога одного игрока без Bukkit: какая страница, сколько букв напечатано, какой ответ выбран.
 * Строки страницы печатаются по очереди; ответы появляются, когда текст допечатан. Не потокобезопасен — живёт в
 * потоке игрока.
 */
public final class DialogFlow {

    public enum Kind {
        /** Ничего не произошло (нельзя пропустить печать). */
        NONE,
        /** Печать пропущена — текст показан целиком. */
        SKIPPED,
        /** Выбран ответ: answer, target — куда дальше (null — закрыть). */
        CHOSEN,
        /** Страница без ответов дочитана: target — next (null — закрыть). */
        NEXT
    }

    public record Step(Kind kind, Answer answer, String target) {
    }

    private final Dialog dialog;
    private final double defaultSpeed;
    private final boolean preventSkip;
    private Page page;
    private List<String> rawLines = List.of();
    private List<Typewriter> lines = List.of();
    private int[] prefix = new int[0];
    private int total;
    private int shown;
    private double progress;
    private int wait;
    private List<Answer> answers = List.of();
    private int selected;

    public DialogFlow(final Dialog dialog, final double defaultSpeed, final boolean preventSkip) {
        this.dialog = dialog;
        this.defaultSpeed = defaultSpeed <= 0 ? 1 : defaultSpeed;
        this.preventSkip = preventSkip;
    }

    public Dialog dialog() {
        return this.dialog;
    }

    public Page page() {
        return this.page;
    }

    /**
     * Показать страницу. lines — уже подготовленные строки (плейсхолдеры подставлены), visible — какие ответы
     * показать (условия).
     */
    public void open(final Page page, final List<String> lines, final Predicate<Answer> visible) {
        this.page = page;
        this.rawLines = List.copyOf(lines);
        final List<Typewriter> tw = new ArrayList<>();
        this.prefix = new int[lines.size() + 1];
        int sum = 0;
        for (int i = 0; i < lines.size(); i++) {
            final Typewriter t = Typewriter.of(lines.get(i));
            tw.add(t);
            this.prefix[i] = sum;
            sum += t.length();
        }
        this.prefix[lines.size()] = sum;
        this.lines = List.copyOf(tw);
        this.total = sum;
        this.shown = 0;
        this.progress = 0;
        this.wait = this.globalWait(0);
        final List<Answer> a = new ArrayList<>();
        for (final Answer x : page.answers()) {
            if (visible.test(x)) {
                a.add(x);
            }
        }
        this.answers = List.copyOf(a);
        this.selected = 0;
    }

    private double speed() {
        return this.page != null && this.page.speed() > 0 ? this.page.speed() : this.defaultSpeed;
    }

    private int globalWait(final int n) {
        int sum = 0;
        for (int i = 0; i < this.lines.size(); i++) {
            final int local = n - this.prefix[i];
            if (local >= 0 && local <= this.lines.get(i).length()) {
                sum += this.lines.get(i).waitAfter(local);
            }
        }
        return sum;
    }

    /** Тик печати; возвращает, сколько букв появилось. */
    public int tick() {
        if (this.shown >= this.total) {
            return 0;
        }
        if (this.wait > 0) {
            this.wait--;
            return 0;
        }
        this.progress += this.speed();
        int typed = 0;
        while (this.progress >= 1 && this.shown < this.total) {
            this.shown++;
            this.progress -= 1;
            typed++;
            this.wait = this.globalWait(this.shown);
            if (this.wait > 0) {
                this.progress = 0;
                break;
            }
        }
        return typed;
    }

    public boolean typing() {
        return this.shown < this.total;
    }

    public int shown() {
        return this.shown;
    }

    public int total() {
        return this.total;
    }

    /** Строки в текущем состоянии печати (MiniMessage). */
    public List<String> visibleLines() {
        final List<String> out = new ArrayList<>(this.lines.size());
        for (int i = 0; i < this.lines.size(); i++) {
            final int n = Math.max(0, Math.min(this.lines.get(i).length(), this.shown - this.prefix[i]));
            out.add(this.typing() ? this.lines.get(i).slice(n) : this.lines.get(i).full());
        }
        return out;
    }

    /** Строки целиком (для обычного окна диалога и чата). */
    public List<String> fullLines() {
        final List<String> out = new ArrayList<>(this.lines.size());
        for (final Typewriter t : this.lines) {
            out.add(t.full());
        }
        return out;
    }

    public List<String> rawLines() {
        return this.rawLines;
    }

    public List<Answer> answers() {
        return this.answers;
    }

    public int selected() {
        return this.selected;
    }

    /** Сдвинуть выбор (по кругу). false — нечего выбирать. */
    public boolean move(final int delta) {
        if (this.answers.isEmpty() || this.typing()) {
            return false;
        }
        final int n = this.answers.size();
        this.selected = ((this.selected + delta) % n + n) % n;
        return true;
    }

    public boolean select(final int index) {
        if (index < 0 || index >= this.answers.size()) {
            return false;
        }
        this.selected = index;
        return true;
    }

    /** Подтверждение (F, ЛКМ, клик по NPC): допечатать, выбрать ответ или «дальше». */
    public Step confirm() {
        if (this.typing()) {
            if (this.preventSkip) {
                return new Step(Kind.NONE, null, null);
            }
            this.shown = this.total;
            this.wait = 0;
            return new Step(Kind.SKIPPED, null, null);
        }
        if (!this.answers.isEmpty()) {
            final Answer a = this.answers.get(this.selected);
            return new Step(Kind.CHOSEN, a, a.target());
        }
        return new Step(Kind.NEXT, null, this.page == null ? null : this.page.next());
    }

    /** Выбор ответа по номеру (клик в чате, кнопка окна): допечатывает текст и выбирает. */
    public Step choose(final int index) {
        if (index < 0 || index >= this.answers.size()) {
            return new Step(Kind.NONE, null, null);
        }
        this.shown = this.total;
        this.wait = 0;
        this.selected = index;
        final Answer a = this.answers.get(index);
        return new Step(Kind.CHOSEN, a, a.target());
    }
}
