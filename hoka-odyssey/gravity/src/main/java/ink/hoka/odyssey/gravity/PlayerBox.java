package ink.hoka.odyssey.gravity;

/**
 * Рамка игрока с модом spaceflight, стоящего на стене или потолке (флаг рамки в C2S op 3, {@link SpaceflightLink}).
 * Без Minestom — проверяется тестами; ставит её {@link BodyRelay}.
 * <p>
 * Грани блоков стоят по осям мира, поэтому и рамка ровная по осям: 1.8 вдоль нормали грани, 0.6×0.6 поперёк.
 * Позиция игрока — середина нижней стороны рамки (как у любой рамки Minestom и игры): {@code new BoundingBox(sx,
 * sy, sz)} со сдвигом по умолчанию (−sx/2, 0, −sz/2) — ровно та рамка, что у клиента. На потолке она совпадает с
 * обычной (голова внизу), на полу рамка обычная.
 * <p>
 * Клиент меняет рамку со сдвигом позиции (на стене позиция — у середины длинной рамки, в 0.9 от стены): до
 * {@link #MAX_SHIFT} блока за раз. Проверка скорости даёт на это разовый запас {@link #SHIFT_CREDIT}.
 */
final class PlayerBox {
    /** Рост и ширина рамки игрока (стоя). */
    static final double HEIGHT = 1.8;
    static final double WIDTH = 0.6;
    /** Наибольший сдвиг позиции клиентом при смене рамки, блоки (клиент: SurfaceBox.MAX_SHIFT). */
    static final double MAX_SHIFT = 1.5;
    /** Разовый запас пути проверки скорости на смену рамки, блоки (сдвиг + движение этого тика). */
    static final double SHIFT_CREDIT = MAX_SHIFT + 0.5;
    /** Запас на смену рамки — не чаще, чем раз в столько тиков (иначе переключением рамки можно «бежать»). */
    static final int CREDIT_COOLDOWN = 5;
    /** Грань «пол» (+Y): рамка обычная. */
    static final int FLOOR = 2;

    private PlayerBox() {}

    /** Рамка по этой грани повёрнута (стены 0, 1, 4, 5 и потолок 3); пол и −1 — обычная. */
    static boolean turned(int surface) {
        return surface >= 0 && surface < SpaceflightLink.SURFACES && surface != FLOOR;
    }

    /** Размеры рамки {sx, sy, sz} для грани (0..5: +X, −X, +Y, −Y, +Z, −Z) или обычной (−1, пол). */
    static double[] size(int surface) {
        if (!turned(surface) || surface / 2 == 1) {
            return new double[] {WIDTH, HEIGHT, WIDTH};
        }
        return surface / 2 == 0 ? new double[] {HEIGHT, WIDTH, WIDTH} : new double[] {WIDTH, WIDTH, HEIGHT};
    }
}
