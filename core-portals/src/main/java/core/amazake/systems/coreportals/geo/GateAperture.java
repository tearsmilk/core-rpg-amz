package core.amazake.systems.coreportals.geo;

/**
 * Размер энергетического кольца «Врат междумирья» в проёме рамы (размеры квадрата дисплея, блоков). Центр
 * неподвижен ({@link #CENTER_Y} над землёй), а ширина и высота растут из нуля: кольцо «вырастает от земли» и
 * раскрывается к краям проёма. Чистая математика, без Bukkit.
 *
 * @param centerY высота центра квадрата над землёй, блоков
 * @param width   ширина квадрата, блоков (полная — 5,3)
 * @param height  высота квадрата, блоков (полная — 9,4)
 */
public record GateAperture(double centerY, double width, double height) {

    /** Высота центра кольца над землёй, блоков. */
    public static final double CENTER_Y = 4.465;

    /**
     * Размер при раскрытии {@code progress} 0..1. Ширина растёт быстрее высоты (степень 0,68): сначала узкая
     * вертикальная полоса, затем она раздаётся в стороны. Ноль заменён на 0,0001 — у дисплея не бывает нулевого
     * масштаба.
     */
    public static GateAperture at(final double progress) {
        final double g = Math.max(0.0, Math.min(1.0, progress));
        return new GateAperture(CENTER_Y, Math.max(1.0E-4, 5.3 * Math.pow(g, 0.68)), Math.max(1.0E-4, 9.4 * g));
    }

    /** Нижний край видимого кольца над землёй: шейдер рисует кольцо на 8,77 / 9,4 высоты квадрата. */
    public double ringBottom() {
        return this.centerY - this.height * 0.9329787234042553 / 2.0;
    }

    /** Верхний край видимого кольца над землёй. */
    public double ringTop() {
        return this.centerY + this.height * 0.9329787234042553 / 2.0;
    }
}
