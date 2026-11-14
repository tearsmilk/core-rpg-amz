package core.amazake.systems.rezusmenu.screen;

import core.amazake.systems.rezusmenu.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Экран во весь монитор, прибитый к камере. Игрок на время меню — наблюдатель (нет руки, хотбара и прицела) и стоит
 * на месте; дисплеи едут на нём как пассажиры с поворотом «к камере» и стоят на расстоянии 1 блок перед глазами,
 * поэтому, как ни крути мышью, картинка неподвижна. Сплошной фон закрывает мир.
 *
 * <p>Мышь всё равно поворачивает голову: сервер читает поворот и двигает по экрану курсор. Кнопка под курсором
 * подсвечивается накладкой, ЛКМ нажимает. Видит экран только этот игрок. Всё — в потоке игрока.</p>
 *
 * <p>Без блок-дисплеев: их грани затеняются по направлению, и при повороте камеры экран мигал. Картинки — плоские
 * предмет-дисплеи с моделью-квадратом из пака ({@code rezusmenu:<id>}, грань без затенения), сплошные заливки —
 * фон текстового дисплея (он не освещается).</p>
 *
 * <p>Единицы: «камера» — блоки на расстоянии 1 перед глазами, (0, 0) — центр экрана, y вверх; «холст» — пиксели
 * панели 512 × 288 (см. {@link PanelMath}), y вниз.</p>
 */
public final class Screen {

    /** Видимая половина экрана в единицах камеры (на расстоянии 1): узнаётся калибровкой. */
    public record Calibration(double halfW, double halfH) {

        /** Поле зрения 70°, монитор 16:9 — пока игрок не откалибровал. */
        public static final Calibration DEFAULT = new Calibration(1.245, 0.700);
    }

    /** Что вернуть игроку после меню. */
    public record Saved(GameMode mode, Location location, float walkSpeed, float flySpeed, boolean allowFlight,
                        boolean flying) {
    }

    /**
     * Текстура-квадрат на холсте: левый верхний угол (x, y), размер w × h px. Прячется нулевым масштабом, а не
     * удаляется: подсветки ставятся один раз и потом только двигаются.
     */
    public final class Quad {
        private final TextDisplay display;
        private final PanelMath.Glyph glyph;
        private final int layer;
        private double x;
        private double y;
        private double w;
        private final double h;
        private boolean shown;

        Quad(final TextDisplay display, final PanelMath.Glyph glyph, final double x, final double y, final double w,
             final double h, final int layer, final boolean shown) {
            this.display = display;
            this.glyph = glyph;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.layer = layer;
            this.shown = shown;
        }

        /** Показать с левым верхним углом в (x, y). */
        public void place(final double px, final double py) {
            this.x = px;
            this.y = py;
            this.shown = true;
            this.apply();
        }

        public void hide() {
            if (this.shown) {
                this.shown = false;
                this.apply();
            }
        }

        /** Новая ширина, левый край на месте (заливка полоски). 0 — спрятать. */
        public void width(final double px) {
            final double next = Math.max(0, px);
            if (next == this.w && this.shown == next > 0) {
                return;
            }
            this.w = next;
            this.shown = next > 0;
            this.apply();
        }

        private void apply() {
            if (this.display.isValid()) {
                this.display.setTransformation(Screen.this.quadTransform(this.glyph, this.x, this.y, this.w, this.h,
                    this.layer, this.shown));
            }
        }
    }

    /** Кнопка: прямоугольник на холсте, накладка-подсветка и действие. */
    public final class Button {
        final PanelMath.Rect rect;
        final String hoverId;
        final Consumer<Button> action;
        final String group;

        Button(final PanelMath.Rect rect, final String hoverId, final Consumer<Button> action, final String group) {
            this.rect = rect;
            this.hoverId = hoverId;
            this.action = action;
            this.group = group;
        }

        public PanelMath.Rect rect() {
            return this.rect;
        }
    }

    private record Element(Entity entity, String group) {
    }

    /** Шаг по глубине между слоями (единицы камеры), ближе к глазам — выше. */
    private static final double LAYER_STEP = 0.0015;
    /** Слой подсветок под курсором и слой курсора (самый верхний). */
    public static final int HOVER_LAYER = 3;
    public static final int CURSOR_LAYER = 20;
    private static final String BASE = "base";
    private static final NamespacedKey SCALE_KEY = new NamespacedKey("rezusmenu", "menu_scale");
    /** На сколько блоков выше лимита постройки уносим игрока: там нет блоков, которые лезут в экран. */
    private static final int SKY = 64;
    /** Фон текстового дисплея с текстом « » при масштабе 1, блоки (ширина, высота). Берём с запасом. */
    private static final double FILL_W = 0.125;
    private static final double FILL_H = 0.25;
    private static final Color BLACK = Color.fromARGB(255, 8, 8, 12);
    private static final Color CLEAR = Color.fromARGB(0, 0, 0, 0);
    /** Шрифт пака с текстурами меню (символы — в {@link PanelMath}). */
    private static final net.kyori.adventure.key.Key FONT = net.kyori.adventure.key.Key.key("rezusmenu", "ui");
    /** Пиксель шрифта в блоках при масштабе 1 (так рисует текст текстовый дисплей). */
    private static final double FONT_PX = 0.025;
    /**
     * Где глиф стоит относительно точки текстового дисплея, px шрифта: дисплей центрует строку (ширина = ширина
     * картинки + 1 px отступа), низ картинки с ascent = height лежит на базовой линии строки над точкой дисплея.
     * Если меню в игре съехало на пиксель-два относительно надписей, правится в config.yml (art.glyph-offset-x/y).
     */
    private static volatile double glyphDx = 0.5;
    private static volatile double glyphDy = 2.0;

    private final Plugin plugin;
    private final Player player;
    private final Saved saved;
    private final Calibration calibration;
    private final double eyeOffset;
    private final double sensitivity;
    private final List<Element> elements = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    /** Подсветки «под курсором» по id текстуры: одна на экран, переезжает к кнопке под курсором. */
    private final Map<String, Quad> hoverQuads = new HashMap<>();
    /** Пиксель холста → единицы камеры. */
    private double scale;
    private double boundW;
    private double boundH;
    /** Курсор: стрелка-глиф (остриё — в точке курсора) или предмет (центр — в точке курсора). */
    private Display cursor;
    private boolean cursorCentered;
    private double cursorSize;
    /** Невидимая кликабельная сущность вокруг глаз: наблюдатель шлёт клик только по сущности. */
    private org.bukkit.entity.Interaction target;
    private Runnable emptyClick;
    private double cu;
    private double cv;
    private float refYaw;
    private float refPitch;
    private Button hovered;
    private boolean closed;

    private Screen(final Plugin plugin, final Player player, final Saved saved, final Calibration calibration,
                   final double eyeOffset, final double sensitivity) {
        this.plugin = plugin;
        this.player = player;
        this.saved = saved;
        this.calibration = calibration;
        this.eyeOffset = eyeOffset;
        this.sensitivity = sensitivity;
        this.boundW = calibration.halfW();
        this.boundH = calibration.halfH();
        this.scale = PanelMath.scale(calibration.halfW(), calibration.halfH());
    }

    /**
     * Перевести игрока в режим меню (наблюдатель, без движения, взгляд прямо) и поставить фон.
     * Поток игрока. null — сейчас нельзя (мёртв, едет на чём-то).
     *
     * @param eyeOffset насколько точка пассажиров выше глаз у игрока обычного размера (1.8 − 1.62)
     * @param sensitivity сколько единиц камеры проходит курсор за градус поворота мыши
     * @param playerScale размер игрока на время меню (атрибут scale): чем меньше, тем ближе точка пассажиров
     *                    к глазам и тем неподвижнее экран при движении мыши вверх-вниз
     */
    public static Screen open(final Plugin plugin, final Player player, final Calibration calibration,
                              final double eyeOffset, final double sensitivity, final double playerScale) {
        if (player.isDead() || player.isInsideVehicle()) {
            return null;
        }
        final Saved saved = new Saved(player.getGameMode(), player.getLocation(), player.getWalkSpeed(),
            player.getFlySpeed(), player.getAllowFlight(), player.isFlying());
        final Screen screen = new Screen(plugin, player, saved, calibration, eyeOffset * playerScale, sensitivity);
        // Высоко над миром: ни один блок не встанет между глазами и экраном.
        final Location look = player.getLocation();
        look.setY(look.getWorld().getMaxHeight() + SKY);
        look.setPitch(0f);
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(look);
        player.setFlySpeed(0f);
        player.setWalkSpeed(0f);
        final org.bukkit.attribute.AttributeInstance scale = player.getAttribute(org.bukkit.attribute.Attribute.SCALE);
        if (scale != null) {
            scale.removeModifier(SCALE_KEY);
            scale.addTransientModifier(new org.bukkit.attribute.AttributeModifier(SCALE_KEY, playerScale - 1.0,
                org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
        screen.refYaw = look.getYaw();
        screen.refPitch = 0f;
        screen.backdrop();
        screen.spawnTarget();
        return screen;
    }

    /** Сдвиг глифов относительно точки дисплея, px шрифта (config.yml art.glyph-offset-x/y). */
    public static void glyphOffsets(final double dx, final double dy) {
        glyphDx = dx;
        glyphDy = dy;
    }

    public Player player() {
        return this.player;
    }

    public Saved saved() {
        return this.saved;
    }

    public Calibration calibration() {
        return this.calibration;
    }

    public boolean closed() {
        return this.closed;
    }

    /** Пиксель холста в единицах камеры. */
    public double scale() {
        return this.scale;
    }

    /** Куда может уйти курсор (единицы камеры). По умолчанию — откалиброванный экран. */
    public void cursorBounds(final double halfW, final double halfH) {
        this.boundW = halfW;
        this.boundH = halfH;
    }

    /** Точка курсора, единицы камеры. */
    public double cursorU() {
        return this.cu;
    }

    public double cursorV() {
        return this.cv;
    }

    /** Что делать по ЛКМ, когда под курсором нет кнопки (калибровка). */
    public void onEmptyClick(final Runnable action) {
        this.emptyClick = action;
    }

    /** Сплошной фон во весь экран (с запасом) и табличка для вида от третьего лица. */
    private void backdrop() {
        final double w = this.calibration.halfW() * 3;
        final double h = this.calibration.halfH() * 3;
        this.spawnFill(0, 0, 2 * w, 2 * h, -1.02, BLACK, BASE);
        // За спиной: видно только после F5 (камера отъезжает назад или встаёт спереди и смотрит на игрока).
        this.spawnFill(0, 0, 8, 6, 1.6, BLACK, BASE);
        this.spawnText(0, -0.15, 0.08, Text.item("<gold>Вы в режиме от третьего лица.\nНажмите F5, чтобы вернуться в меню.</gold>"),
            1.55, null, BASE);
    }

    /**
     * Невидимая кликабельная коробка вокруг глаз игрока. В режиме наблюдателя клиент не шлёт удар по воздуху, а
     * ЛКМ по сущности шлёт: сервер пробует «вселить» игрока в неё — это событие и есть клик (см. MenuManager).
     */
    private void spawnTarget() {
        final Location eye = this.player.getEyeLocation();
        final Location at = new Location(eye.getWorld(), eye.getX(), eye.getY() - 0.7, eye.getZ());
        this.target = eye.getWorld().spawn(at, org.bukkit.entity.Interaction.class, e -> {
            e.setInteractionWidth(1.4f);
            e.setInteractionHeight(1.4f);
            e.setResponsive(false);
            e.setPersistent(false);
            e.setVisibleByDefault(false);
        });
        this.player.showEntity(this.plugin, this.target);
    }

    /** Это кликабельная коробка этого экрана. */
    public boolean isTarget(final Entity entity) {
        return this.target != null && entity != null && entity.getUniqueId().equals(this.target.getUniqueId());
    }

    // --- элементы (пиксели холста) ---

    /** Текстура {@code rezusmenu:<id>} размером w × h px, левый верхний угол в (x, y). */
    public Quad quad(final String id, final double x, final double y, final double w, final double h, final int layer,
                     final String group) {
        return this.spawnQuad(id, x, y, w, h, layer, true, group);
    }

    /**
     * Накладка из таблицы пака (размер — размер её текстуры), пока спрятана: {@link Quad#place} показывает её
     * левым верхним углом в точке. Живёт до закрытия экрана.
     */
    public Quad marker(final String id, final int layer) {
        final PanelMath.Glyph g = PanelMath.glyph(id);
        return this.spawnQuad(id, 0, 0, g.w(), g.h(), layer, false, BASE);
    }

    /** Текст по центру прямоугольника (x, y, w, h); {@code lineHeight} — высота строки, px. */
    public TextDisplay text(final double x, final double y, final double w, final double h, final double lineHeight,
                            final Component text, final int layer, final String group) {
        final int lines = Math.max(1, countLines(text));
        final double bottom = y + (h + lineHeight * lines) / 2;
        final TextDisplay d = this.spawnText(PanelMath.camX(x + w / 2, this.scale), PanelMath.camY(bottom, this.scale),
            lineHeight * this.scale, text, depth(layer), null, group);
        d.setLineWidth(Math.max(20, (int) Math.round(w / lineHeight * 10)));
        return d;
    }

    /** Ванильный предмет по центру (cx, cy), размер {@code size} px. */
    public ItemDisplay item(final double cx, final double cy, final double size, final ItemStack item, final int layer,
                            final String group) {
        return this.spawnItem(PanelMath.camX(cx, this.scale), PanelMath.camY(cy, this.scale), size * this.scale,
            item, depth(layer), group);
    }

    /** Кнопка: зона нажатия (x, y, w, h); {@code hoverId} — накладка под курсором (её левый верхний угол — в (x, y)). */
    public Button button(final double x, final double y, final double w, final double h, final String hoverId,
                         final String group, final Consumer<Button> action) {
        final Button b = new Button(new PanelMath.Rect(x, y, w, h), hoverId, action, group);
        this.buttons.add(b);
        return b;
    }

    // --- элементы (единицы камеры) ---

    /** Текст по центру (cx, cy) в единицах камеры, с подложкой {@code background} (null — без неё). */
    public TextDisplay textAt(final double cx, final double cy, final double lineHeight, final Component text,
                              final int layer, final Color background, final String group) {
        final int lines = Math.max(1, countLines(text));
        final TextDisplay d = this.spawnText(cx, cy - lineHeight * lines / 2, lineHeight, text, depth(layer),
            background, group);
        d.setLineWidth(1000);
        return d;
    }

    public static void setText(final TextDisplay d, final Component text) {
        if (d != null && d.isValid()) {
            d.text(text);
        }
    }

    /** Убрать элементы и кнопки группы (содержимое вкладки). */
    public void clear(final String group) {
        this.elements.removeIf(e -> {
            if (e.group().equals(group)) {
                e.entity().remove();
                return true;
            }
            return false;
        });
        this.buttons.removeIf(b -> b.group.equals(group));
        if (this.hovered != null && this.hovered.group.equals(group)) {
            this.setHovered(null);
        }
    }

    // --- низкий уровень (единицы камеры) ---

    private static double depth(final int layer) {
        return -1.0 + layer * LAYER_STEP;
    }

    /**
     * Перспектива: слои стоят на разной глубине, а курсор и попадание считаются на плоскости z = −1. Чтобы любой
     * слой лёг на экране ровно туда же, его координаты и размер умножаются на глубину. За спиной (z > 0, табличка
     * для F5) — как есть.
     */
    private static double depthFactor(final double z) {
        return z < 0 ? -z : 1.0;
    }

    /** Точка в единицах камеры (на плоскости z = −1) → перенос дисплея на глубине z. */
    private Vector3f at(final double x, final double y, final double z) {
        final double k = depthFactor(z);
        return new Vector3f((float) (x * k), (float) (y * k - this.eyeOffset), (float) z);
    }

    /**
     * Перенос и масштаб глифа: картинка {@code glyph} (px) растягивается в прямоугольник с левым верхним углом
     * (left, top) и размером w × h — всё в единицах камеры на плоскости z = −1.
     */
    private Transformation glyphTransform(final PanelMath.Glyph glyph, final double left, final double top,
                                          final double w, final double h, final double z, final boolean shown) {
        if (!shown || w <= 0 || h <= 0) {
            return new Transformation(this.at(left, top, z), new Quaternionf(), new Vector3f(0f, 0f, 0f), new Quaternionf());
        }
        final double k = depthFactor(z);
        final double sx = w / (glyph.w() * FONT_PX);
        final double sy = h / (glyph.h() * FONT_PX);
        final double ox = left + w / 2 + glyphDx * FONT_PX * sx;
        final double oy = top - h - glyphDy * FONT_PX * sy;
        return new Transformation(this.at(ox, oy, z), new Quaternionf(),
            new Vector3f((float) (sx * k), (float) (sy * k), 1f), new Quaternionf());
    }

    private Transformation quadTransform(final PanelMath.Glyph glyph, final double x, final double y, final double w,
                                         final double h, final int layer, final boolean shown) {
        return this.glyphTransform(glyph, PanelMath.camX(x, this.scale), PanelMath.camY(y, this.scale),
            w * this.scale, h * this.scale, depth(layer), shown);
    }

    /** Символ текстуры шрифтом пака, без тени и подложки. */
    static Component glyphText(final PanelMath.Glyph glyph) {
        return Component.text(String.valueOf(glyph.ch())).font(FONT).color(net.kyori.adventure.text.format.NamedTextColor.WHITE);
    }

    private TextDisplay spawnGlyph(final PanelMath.Glyph glyph, final Transformation transform, final String group) {
        return this.spawn(TextDisplay.class, e -> {
            e.text(glyphText(glyph));
            e.setDefaultBackground(false);
            e.setBackgroundColor(CLEAR);
            e.setShadowed(false);
            e.setLineWidth(4096);
            e.setTransformation(transform);
        }, group);
    }

    private Quad spawnQuad(final String id, final double x, final double y, final double w, final double h,
                           final int layer, final boolean shown, final String group) {
        final PanelMath.Glyph glyph = PanelMath.glyph(id);
        if (glyph == null) {
            throw new IllegalArgumentException("нет текстуры " + id);
        }
        final TextDisplay d = this.spawnGlyph(glyph, this.quadTransform(glyph, x, y, w, h, layer, shown), group);
        return new Quad(d, glyph, x, y, w, h, layer, shown);
    }

    /**
     * Ванильный предмет: транформ FIXED — как в рамке, лицом к камере (GUI в дисплее показывает его задом, зеркально).
     */
    private ItemDisplay spawnItem(final double cx, final double cy, final double size, final ItemStack item,
                                  final double z, final String group) {
        final float s = (float) (size * depthFactor(z));
        final Vector3f at = this.at(cx, cy, z);
        return this.spawn(ItemDisplay.class, e -> {
            e.setItemStack(item == null ? null : item.clone());
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setTransformation(new Transformation(at, new Quaternionf(), new Vector3f(s, s, 0.001f), new Quaternionf()));
        }, group);
    }

    /** Сплошная заливка: фон текстового дисплея с пробелом (не освещается). Центр (cx, cy), размер w × h. */
    private void spawnFill(final double cx, final double cy, final double w, final double h, final double z,
                           final Color color, final String group) {
        final double k = depthFactor(z);
        final Vector3f at = this.at(cx, cy - h / 2, z);
        this.spawn(TextDisplay.class, e -> {
            e.text(Component.text(" "));
            e.setDefaultBackground(false);
            e.setBackgroundColor(color);
            e.setShadowed(false);
            e.setTransformation(new Transformation(at, new Quaternionf(),
                new Vector3f((float) (w * k / FILL_W), (float) (h * k / FILL_H), 1f), new Quaternionf()));
        }, group);
    }

    /** Текст: (cx, bottom) — середина нижнего края. */
    private TextDisplay spawnText(final double cx, final double bottom, final double lineHeight, final Component text,
                                  final double z, final Color background, final String group) {
        final float s = (float) (lineHeight / 0.25 * depthFactor(z));
        final Vector3f at = this.at(cx, bottom, z);
        return this.spawn(TextDisplay.class, e -> {
            e.text(text);
            e.setDefaultBackground(false);
            e.setBackgroundColor(background == null ? CLEAR : background);
            e.setShadowed(true);
            e.setTransformation(new Transformation(at, new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
        }, group);
    }

    private <T extends Display> T spawn(final Class<T> type, final Consumer<T> setup, final String group) {
        final World world = this.player.getWorld();
        final T d = world.spawn(this.player.getEyeLocation(), type, e -> {
            e.setBillboard(Display.Billboard.CENTER);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(1.0f);
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setTeleportDuration(0);
            setup.accept(e);
        });
        this.player.showEntity(this.plugin, d);
        this.player.addPassenger(d);
        this.elements.add(new Element(d, group));
        return d;
    }

    // --- курсор ---

    /** Курсор-стрелка из пака (16 × 16 px холста), остриё — левый верхний угол — точно в точке курсора. */
    public void arrowCursor() {
        this.removeCursor();
        this.cursorCentered = false;
        this.cursorSize = 16 * this.scale;
        this.cursor = this.spawnGlyph(PanelMath.glyph("cursor"), this.cursorTransform(), BASE);
    }

    /** Курсор-предмет (ванильная иконка) размером {@code size} единиц камеры, центр — в точке курсора. */
    public void itemCursor(final ItemStack item, final double size) {
        this.removeCursor();
        this.cursorCentered = true;
        this.cursorSize = size;
        this.cursor = this.spawn(ItemDisplay.class, e -> {
            e.setItemStack(item);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setTransformation(this.cursorTransform());
        }, BASE);
    }

    private void removeCursor() {
        if (this.cursor != null) {
            final Display old = this.cursor;
            this.elements.removeIf(e -> e.entity() == old);
            old.remove();
            this.cursor = null;
        }
    }

    private Transformation cursorTransform() {
        final double z = depth(CURSOR_LAYER);
        if (!this.cursorCentered) {
            return this.glyphTransform(PanelMath.glyph("cursor"), this.cu, this.cv, this.cursorSize, this.cursorSize, z, true);
        }
        final float s = (float) (this.cursorSize * depthFactor(z));
        return new Transformation(this.at(this.cu, this.cv, z), new Quaternionf(), new Vector3f(s, s, 0.001f),
            new Quaternionf());
    }

    /** Курсор — в центр (пробел). */
    public void recenter() {
        final Location l = this.player.getLocation();
        this.refYaw = l.getYaw();
        this.refPitch = l.getPitch();
        this.cu = 0;
        this.cv = 0;
        this.moveCursor(0);
    }

    /** Такт: курсор по повороту головы и подсветка кнопки под ним. Поток игрока, каждый тик. */
    public void update() {
        if (this.closed) {
            return;
        }
        final Location l = this.player.getLocation();
        final double s = this.sensitivity;
        double u = wrap(l.getYaw() - this.refYaw) * s;
        double v = -(l.getPitch() - this.refPitch) * s;
        // Упёрся в край — сдвигаем опору, чтобы обратное движение сразу вело курсор назад.
        if (Math.abs(u) > this.boundW) {
            u = Math.copySign(this.boundW, u);
            this.refYaw = (float) (l.getYaw() - u / s);
        }
        if (Math.abs(v) > this.boundH) {
            v = Math.copySign(this.boundH, v);
            this.refPitch = (float) (l.getPitch() + v / s);
        }
        if (u != this.cu || v != this.cv) {
            this.cu = u;
            this.cv = v;
            this.moveCursor(1);
        }
        this.updateHover();
    }

    private void moveCursor(final int interpolation) {
        if (this.cursor != null && this.cursor.isValid()) {
            this.cursor.setInterpolationDelay(0);
            this.cursor.setInterpolationDuration(interpolation);
            this.cursor.setTransformation(this.cursorTransform());
        }
    }

    private void updateHover() {
        final double px = PanelMath.panelX(this.cu, this.scale);
        final double py = PanelMath.panelY(this.cv, this.scale);
        final List<PanelMath.Rect> rects = new ArrayList<>(this.buttons.size());
        for (final Button b : this.buttons) {
            rects.add(b.rect);
        }
        final int i = PanelMath.hit(rects, px, py);
        final Button now = i < 0 ? null : this.buttons.get(i);
        if (now != this.hovered) {
            this.setHovered(now);
        }
    }

    private void setHovered(final Button now) {
        if (this.hovered != null && this.hovered.hoverId != null) {
            final Quad q = this.hoverQuads.get(this.hovered.hoverId);
            if (q != null) {
                q.hide();
            }
        }
        this.hovered = now;
        if (now != null && now.hoverId != null) {
            this.hoverQuads.computeIfAbsent(now.hoverId, id -> this.marker(id, HOVER_LAYER))
                .place(now.rect.x(), now.rect.y());
        }
    }

    /** ЛКМ: нажать кнопку под курсором (или действие «мимо кнопок»). */
    public void click() {
        if (this.closed) {
            return;
        }
        if (this.hovered != null) {
            this.hovered.action.accept(this.hovered);
        } else if (this.emptyClick != null) {
            this.emptyClick.run();
        }
    }

    public Button hovered() {
        return this.hovered;
    }

    /** Убрать экран и вернуть игроку режим, скорость и место. Поток игрока. */
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (final Element e : this.elements) {
            e.entity().remove();
        }
        this.elements.clear();
        this.buttons.clear();
        this.hoverQuads.clear();
        this.cursor = null;
        this.hovered = null;
        if (this.target != null && this.target.isValid()) {
            this.target.remove();
        }
        restore(this.player, this.saved);
    }

    /** Вернуть игроку сохранённое состояние (и после падения сервера — из файла). */
    public static void restore(final Player player, final Saved saved) {
        if (!player.isOnline()) {
            return;
        }
        final org.bukkit.attribute.AttributeInstance scale = player.getAttribute(org.bukkit.attribute.Attribute.SCALE);
        if (scale != null) {
            scale.removeModifier(SCALE_KEY);
        }
        player.setGameMode(saved.mode());
        player.setWalkSpeed(saved.walkSpeed());
        player.setFlySpeed(saved.flySpeed());
        player.setAllowFlight(saved.allowFlight() || saved.mode() == GameMode.CREATIVE);
        player.setFlying(saved.flying() && player.getAllowFlight());
        if (!player.isDead() && saved.location().getWorld() != null) {
            final Location back = saved.location().clone();
            back.setYaw(player.getLocation().getYaw());
            back.setPitch(player.getLocation().getPitch());
            player.teleport(back);
        }
    }

    static double wrap(final double degrees) {
        double d = degrees % 360.0;
        if (d > 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }

    private static int countLines(final Component c) {
        final String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
        return (int) plain.chars().filter(ch -> ch == '\n').count() + 1;
    }
}
