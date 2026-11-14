package core.amazake.systems.rezusmenu.screen;

import core.amazake.systems.rezusmenu.util.Text;
import java.util.function.Consumer;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.inventory.ItemStack;

/**
 * Калибровка экрана: сервер не знает поле зрения и пропорции монитора игрока. Курсор — око Края; игрок уводит его в
 * правый верхний угол, пока оно ещё видно целиком, и жмёт ЛКМ. Правый и верхний край ока — это и есть края экрана
 * (половина ширины и высоты видимой области на расстоянии 1).
 *
 * <p>Рисует в единицах камеры. Курсор на время калибровки может уйти далеко за любой разумный экран.</p>
 */
public final class CalibrationScreen {

    /** Размер ока на экране (единицы камеры) и его половина — от центра до правого/верхнего края. */
    static final double EYE_SIZE = 0.09;
    static final double EYE_HALF = EYE_SIZE / 2;
    /** Меньше этого — явно не угол экрана (нажали, не доведя око до края). */
    static final double MIN_U = 0.5;
    static final double MIN_V = 0.3;
    /** Куда может уйти курсор при калибровке. */
    private static final double BOUND_W = 3.2;
    private static final double BOUND_H = 1.45;
    /** Больше не бывает: поле зрения 110° даёт половину высоты tan 55° ≈ 1.43; ширина/высота — от 1:1 до 21:9. */
    static final double MAX_V = 1.44;
    static final double MIN_RATIO = 1.0;
    static final double MAX_RATIO = 2.6;

    private final Screen screen;
    private final Consumer<Screen.Calibration> done;
    private boolean finished;

    public CalibrationScreen(final Screen screen, final Consumer<Screen.Calibration> done) {
        this.screen = screen;
        this.done = done;
    }

    public void build() {
        this.screen.cursorBounds(BOUND_W, BOUND_H);
        this.screen.textAt(0, -0.22, 0.045, Text.item(
            "<white><b>Двигайте око Края в правый верхний угол экрана —</b></white>\n"
                + "<white><b>до края, пока оно ещё видно целиком.</b></white>\n"
                + "<yellow>Нажмите ЛКМ.</yellow> <gray>Пробел — око в центр.</gray>"),
            2, Color.fromARGB(230, 24, 18, 12), "step");
        this.screen.itemCursor(new ItemStack(Material.ENDER_EYE), EYE_SIZE);
        this.screen.onEmptyClick(this::pick);
    }

    /** Калибровка по точке ока (центр, единицы камеры); null — око не у края экрана. */
    static Screen.Calibration result(final double cu, final double cv) {
        if (cu < MIN_U || cv < MIN_V || cv > MAX_V) {
            return null;
        }
        final double w = cu + EYE_HALF;
        final double h = cv + EYE_HALF;
        if (w / h < MIN_RATIO || w / h > MAX_RATIO) {
            return null;
        }
        return new Screen.Calibration(w, h);
    }

    private void pick() {
        if (this.finished) {
            return;
        }
        final Screen.Calibration cal = result(this.screen.cursorU(), this.screen.cursorV());
        if (cal == null) {
            this.screen.player().sendActionBar(Text.mm(
                "<yellow>Уведите око Края в самый правый верхний угол экрана и нажмите ЛКМ</yellow>"));
            this.screen.player().playSound(this.screen.player().getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.2f);
            return;
        }
        this.finished = true;
        this.screen.player().playSound(this.screen.player().getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        this.done.accept(cal);
    }
}
