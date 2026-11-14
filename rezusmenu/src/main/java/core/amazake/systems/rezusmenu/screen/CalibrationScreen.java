package core.amazake.systems.rezusmenu.screen;

import core.amazake.systems.rezusmenu.util.Text;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.Sound;

/**
 * Калибровка экрана: сервер не знает поле зрения и пропорции монитора игрока. Сначала — столбик чисел вверх
 * («нажмите на самое верхнее число, полностью видимое на экране»), потом — строка чисел вправо. По ответам видно,
 * где кончается экран, и меню ляжет ровно во весь монитор.
 *
 * <p>Рисует в единицах камеры (масштаб 1). Курсор на время калибровки может уйти далеко за типичный экран.</p>
 */
public final class CalibrationScreen {

    private static final String STEP = "step";
    private static final double TEXT = 0.05;

    private final Screen screen;
    private final Consumer<Screen.Calibration> done;
    private double halfH;

    public CalibrationScreen(final Screen screen, final Consumer<Screen.Calibration> done) {
        this.screen = screen;
        this.done = done;
    }

    public void build() {
        this.screen.scale(1.0);
        this.screen.cursorBounds(2.8, 1.6);
        this.vertical();
    }

    private void box(final String text) {
        this.screen.rect(-0.42, -0.36, 0.84, 0.26, 2, Material.GRAY_CONCRETE, STEP);
        this.screen.text(-0.42, -0.36, 0.84, 0.26, 0.055, Text.item(text), 3, STEP);
    }

    /** Шаг 1: числа вверх — верхний край экрана. */
    private void vertical() {
        this.screen.clear(STEP);
        this.box("<white><b>Нажмите на самое верхнее\nчисло, ПОЛНОСТЬЮ видимое\nна экране.</b></white>\n<gray>Пробел — курсор в центр</gray>");
        for (int n = 1; n <= 24; n++) {
            final double top = 0.08 + n * 0.055;
            final int number = n;
            final double side = n % 2 == 0 ? 0.06 : -0.16;
            this.screen.button(side, top - TEXT, 0.1, TEXT, 2, Material.BLACK_CONCRETE, Material.GRAY_CONCRETE, STEP,
                b -> this.pickTop(top, number));
            this.screen.text(side, top - TEXT, 0.1, TEXT, TEXT * 0.85, Text.item("<white>" + number + "</white>"), 3, STEP);
        }
    }

    private void pickTop(final double top, final int number) {
        // Число полностью видно, следующее — уже нет: край между ними.
        this.halfH = top + 0.02;
        this.sound();
        this.horizontal();
    }

    /** Шаг 2: числа вправо — правый край экрана. */
    private void horizontal() {
        this.screen.clear(STEP);
        this.box("<white><b>Теперь нажмите на самое\nправое число, ПОЛНОСТЬЮ\nвидимое на экране.</b></white>\n<gray>Пробел — курсор в центр</gray>");
        for (int n = 1; n <= 30; n++) {
            final double right = 0.40 + n * 0.08;
            final double left = right - 0.07;
            final int number = n;
            final double v = n % 2 == 0 ? 0.0 : 0.09;
            this.screen.button(left, v, 0.07, TEXT, 2, Material.BLACK_CONCRETE, Material.GRAY_CONCRETE, STEP,
                b -> this.pickRight(right));
            this.screen.text(left, v, 0.07, TEXT, TEXT * 0.85, Text.item("<white>" + number + "</white>"), 3, STEP);
        }
    }

    private void pickRight(final double right) {
        this.sound();
        this.screen.clear(STEP);
        this.done.accept(new Screen.Calibration(right + 0.03, this.halfH));
    }

    private void sound() {
        this.screen.player().playSound(this.screen.player().getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }
}
