package core.amazake.systems.corerpg.papi;

/**
 * Неизменяемый снимок предмета в основной руке игрока для плейсхолдеров. Снимается в потоке игрока (вкладчик аспектов
 * order 300), читается из любого.
 *
 * @param sharpen      уровень заточки (0 — нет)
 * @param sharpenMax   максимум заточки для предмета (0 — не точится)
 * @param sharpenBonus бонус заточки, %
 * @param sealName     название печати или "" без печати
 * @param sealColor    цвет печати по-русски или ""
 */
public record HandSnapshot(int sharpen, int sharpenMax, double sharpenBonus, String sealName, String sealColor) {

    public static final HandSnapshot EMPTY = new HandSnapshot(0, 0, 0.0, "", "");

    public HandSnapshot {
        sealName = sealName == null ? "" : sealName;
        sealColor = sealColor == null ? "" : sealColor;
    }
}
