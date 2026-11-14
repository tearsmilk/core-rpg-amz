package core.amazake.systems.rezusmenu.screen;

import core.amazake.systems.rezusmenu.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Экран во весь монитор. Игрок на время меню — наблюдатель высоко над миром, ходить и летать не может (скорость 0),
 * поэтому камера стоит на месте. Все дисплеи стоят в мире ровно в точке глаз, с поворотом «к камере» (billboard
 * CENTER): точка дисплея совпадает с камерой, и картинка неподвижна на экране, как ни крути мышью — ни параллакса,
 * ни дрожания. Видит экран только этот игрок. Всё — в потоке игрока.
 *
 * <p>Мышь поворачивает голову, сервер двигает курсор. Курсор — либо шейдерный (предмет в мире, который шейдер пака
 * отражает через центр экрана: он идёт за мышью с частотой кадров), либо глиф-стрелка, которую двигает сервер.</p>
 *
 * <p>Единицы: «камера» — блоки на расстоянии 1 перед глазами, (0, 0) — центр экрана, y вверх; «холст» — пиксели
 * холста 704 × 400 (см. {@link PanelMath}), y вниз.</p>
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

    /** Как курсор следует за мышью. */
    public enum CursorMode {
        /** Сервер считает курсор из поворота головы (чувствительность из config.yml) и двигает дисплей. */
        ROTATION,
        /** Предмет в мире с маркерным цветом: шейдер пака отражает его через центр экрана. */
        SHADER
    }

    /**
     * Текстура на холсте: левый верхний угол (x, y), ширина w px (высота — высота текстуры, 1:1). Прячется нулевым
     * масштабом, а не удаляется: подсветки ставятся один раз и потом только двигаются.
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

        /** Показать с левым верхним углом в (x, y) холста. */
        public void place(final double px, final double py) {
            if (this.shown && px == this.x && py == this.y) {
                return;
            }
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

        /**
         * Новая ширина всей текстуры (левый край на месте): заливка полоски режется масштабом по x, а не
         * растягивается. 0 — спрятать.
         */
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
        final String name;

        Button(final PanelMath.Rect rect, final String hoverId, final Consumer<Button> action, final String group,
               final String name) {
            this.rect = rect;
            this.hoverId = hoverId;
            this.action = action;
            this.group = group;
            this.name = name;
        }

        public PanelMath.Rect rect() {
            return this.rect;
        }

        /** Имя для отладки кликов. */
        public String name() {
            return this.name;
        }
    }

    private record Element(Entity entity, String group) {
    }

    /** Шаг по глубине между слоями (единицы камеры), ближе к глазам — выше. */
    private static final double LAYER_STEP = 0.0015;
    /** Слой подсветок под курсором и слой курсора-глифа (самый верхний). */
    public static final int HOVER_LAYER = 4;
    public static final int CURSOR_LAYER = 20;
    private static final String BASE = "base";
    /** Старые версии уменьшали игрока на время меню: модификатор снимается при возврате (и после падения). */
    private static final NamespacedKey SCALE_KEY = new NamespacedKey("rezusmenu", "menu_scale");
    /** На сколько блоков выше лимита постройки уносим игрока: там нет блоков, которые лезут в экран. */
    private static final int SKY = 64;
    /** Фон текстового дисплея с текстом « » при масштабе 1, блоки (ширина, высота). Берём с запасом. */
    private static final double FILL_W = 0.125;
    private static final double FILL_H = 0.25;
    private static final Color BLACK = Color.fromARGB(255, 8, 8, 12);
    private static final Color CLEAR = Color.fromARGB(0, 0, 0, 0);
    /** Шрифт пака с текстурами меню (символы — в {@link PanelMath}). */
    private static final Key FONT = Key.key("rezusmenu", "ui");
    /**
     * Свет предметов меню: блок 15, небо 14. Шейдер пака узнаёт его (UV2 = 240, 224) и рисует такие предметы без
     * направленного света — иначе они светлеют, когда смотришь вниз, и темнеют, когда вверх. Текст света не
     * имеет направления, ему — обычные 15/15.
     */
    private static final Display.Brightness ITEM_LIGHT = new Display.Brightness(15, 14);
    private static final Display.Brightness TEXT_LIGHT = new Display.Brightness(15, 15);
    /** Маркерный цвет шейдерного курсора (254, 0, 254) и модель предмета с тинтом custom_model_data. */
    private static final Color MARKER = Color.fromRGB(254, 0, 254);
    private static final NamespacedKey CURSOR_MODEL = new NamespacedKey("rezusmenu", "cursor_mirror");
    /** Шейдерный курсор ближе панели (она на глубине ~1), чтобы рисоваться поверх. */
    private static final double CURSOR_DIST = 0.6;
    /** Насколько игрок может сдвинуться, прежде чем меню закроется (блоки). */
    private static final double MAX_DRIFT = 0.3;

    private final Plugin plugin;
    private final Player player;
    private final Saved saved;
    private final Calibration calibration;
    private final double sensitivity;
    private final double fineX;
    private final double fineY;
    /** Точка глаз = точка всех дисплеев = камера. И место ног — для проверки, что игрок не сдвинулся. */
    private final Location origin;
    private final Location feet;
    private final List<Element> elements = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    /** Подсветки «под курсором» по id текстуры: одна на экран, переезжает к кнопке под курсором. */
    private final Map<String, Quad> hoverQuads = new HashMap<>();
    /** Пиксель холста → единицы камеры. */
    private final double scale;
    private double boundW;
    private double boundH;
    private CursorMode mode = CursorMode.ROTATION;
    /** Курсор: стрелка-глиф (остриё — в точке курсора), предмет (центр — в точке курсора) или шейдерный. */
    private Display cursor;
    private boolean cursorCentered;
    private double cursorSize;
    /** Шейдерный курсор: точка мира, которую шейдер показывает в точке курсора. */
    private double[] anchor;
    /** Невидимая кликабельная сущность вокруг глаз: наблюдатель шлёт клик только по сущности. */
    private Interaction target;
    private Runnable emptyClick;
    private double cu;
    private double cv;
    private float refYaw;
    private float refPitch;
    private Button hovered;
    private boolean closed;

    private Screen(final Plugin plugin, final Player player, final Saved saved, final Calibration calibration,
                   final double sensitivity, final double fineX, final double fineY) {
        this.plugin = plugin;
        this.player = player;
        this.saved = saved;
        this.calibration = calibration;
        this.sensitivity = sensitivity;
        this.fineX = fineX;
        this.fineY = fineY;
        this.boundW = calibration.halfW();
        this.boundH = calibration.halfH();
        this.scale = PanelMath.scale(calibration.halfW(), calibration.halfH());
        this.feet = player.getLocation();
        final Location eye = player.getEyeLocation();
        eye.setYaw(0f);
        eye.setPitch(0f);
        this.origin = eye;
    }

    /**
     * Перевести игрока в режим меню (наблюдатель над миром, без движения, взгляд прямо) и поставить фон.
     * Поток игрока. null — сейчас нельзя (мёртв, едет на чём-то).
     *
     * @param sensitivity сколько единиц камеры проходит курсор за градус поворота мыши (курсор {@link CursorMode#ROTATION})
     * @param fineX       тонкая подстройка картинок, px шрифта вправо (config.yml art.glyph-offset-x)
     * @param fineY       то же вверх (art.glyph-offset-y)
     */
    public static Screen open(final Plugin plugin, final Player player, final Calibration calibration,
                              final double sensitivity, final double fineX, final double fineY) {
        if (player.isDead() || player.isInsideVehicle()) {
            return null;
        }
        final Saved saved = new Saved(player.getGameMode(), player.getLocation(), player.getWalkSpeed(),
            player.getFlySpeed(), player.getAllowFlight(), player.isFlying());
        // Высоко над миром: ни один блок не встанет между глазами и экраном.
        final Location look = player.getLocation();
        look.setY(look.getWorld().getMaxHeight() + SKY);
        look.setPitch(0f);
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(look);
        player.setFlySpeed(0f);
        player.setWalkSpeed(0f);
        removeScale(player);
        final Screen screen = new Screen(plugin, player, saved, calibration, sensitivity, fineX, fineY);
        screen.refYaw = look.getYaw();
        screen.refPitch = 0f;
        screen.backdrop();
        screen.spawnTarget();
        return screen;
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

    /** Точка курсора на холсте, px. */
    public double cursorX() {
        return PanelMath.canvasX(this.cu, this.scale);
    }

    public double cursorY() {
        return PanelMath.canvasY(this.cv, this.scale);
    }

    public CursorMode cursorMode() {
        return this.mode;
    }

    /** Что делать по ЛКМ, когда под курсором нет кнопки (калибровка). */
    public void onEmptyClick(final Runnable action) {
        this.emptyClick = action;
    }

    /** Игрок сдвинулся с места (чужой телепорт, толчок): меню надо закрыть. */
    public boolean moved() {
        final Location now = this.player.getLocation();
        return now.getWorld() != this.feet.getWorld() || now.distanceSquared(this.feet) > MAX_DRIFT * MAX_DRIFT;
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
     * Невидимая кликабельная коробка вокруг глаз игрока. Наблюдатель не шлёт удар по воздуху, а ЛКМ по сущности
     * шлёт, если её коробка содержит глаза (тогда клиент выбирает её на расстоянии 0): сервер пробует «вселить»
     * игрока в неё — это событие и есть клик (см. MenuManager). Коробка стоит в мире, а не едет на игроке.
     */
    private void spawnTarget() {
        final Location at = this.origin.clone().subtract(0, 0.75, 0);
        this.target = at.getWorld().spawn(at, Interaction.class, e -> {
            e.setInteractionWidth(1.5f);
            e.setInteractionHeight(1.5f);
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

    /** Текстура {@code id} в натуральную величину, левый верхний угол в (x, y). */
    public Quad quad(final String id, final double x, final double y, final int layer, final String group) {
        final PanelMath.Glyph g = glyphOf(id);
        return this.spawnQuad(g, x, y, g.w(), layer, true, group);
    }

    /** Текстура {@code id}, ширина всей текстуры {@code w} px (заливки полосок), высота — натуральная. */
    public Quad quad(final String id, final double x, final double y, final double w, final int layer,
                     final String group) {
        return this.spawnQuad(glyphOf(id), x, y, w, layer, w > 0, group);
    }

    /**
     * Накладка (размер — размер её текстуры), пока спрятана: {@link Quad#place} показывает её левым верхним углом в
     * точке. Живёт до закрытия экрана.
     */
    public Quad marker(final String id, final int layer) {
        final PanelMath.Glyph g = glyphOf(id);
        return this.spawnQuad(g, 0, 0, g.w(), layer, false, BASE);
    }

    /** Текст по центру прямоугольника (x, y, w, h); {@code lineHeight} — высота строки, px. */
    public TextDisplay text(final double x, final double y, final double w, final double h, final double lineHeight,
                            final Component text, final int layer, final String group) {
        final int lines = Math.max(1, countLines(text));
        final double bottom = y + (h + lineHeight * lines) / 2;
        final TextDisplay d = this.spawnText(PanelMath.camX(x + w / 2, this.scale), PanelMath.camY(bottom, this.scale),
            lineHeight * this.scale, text, depth(layer), null, group);
        d.setLineWidth(Math.max(20, (int) Math.round(w / lineHeight * PanelMath.LINE_H)));
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
                         final String group, final String name, final Consumer<Button> action) {
        final Button b = new Button(new PanelMath.Rect(x, y, w, h), hoverId, action, group, name);
        this.buttons.add(b);
        return b;
    }

    /**
     * Картинка из «пикселей» текста (портрет-скин): {@code argb} — {@code w × h}, 0 — прозрачно; левый верхний угол
     * в (x, y), пиксель картинки — {@code px} пикселей холста. Строка картинки — один текстовый дисплей: цветные
     * глифы-пиксели (шириной 2, рисуют 1) склеены шагом −1, прозрачные — пустой шаг +1, поэтому ширина строки
     * всегда {@code w} и пиксели стоят без зазоров.
     */
    public void pixels(final int[] argb, final int w, final int h, final double x, final double y, final double px,
                       final int layer, final String group) {
        final double z = depth(layer);
        final double k = depthFactor(z);
        final PanelMath.FontBox box = PanelMath.lineBox(w, 0, w, 1, PanelMath.PIXEL_ASCENT);
        for (int row = 0; row < h; row++) {
            final TextComponent.Builder line = Component.text().font(FONT).color(NamedTextColor.WHITE);
            boolean any = false;
            for (int col = 0; col < w; col++) {
                final int c = argb[row * w + col];
                if (c >>> 24 == 0) {
                    line.append(Component.text(String.valueOf(PanelMath.POS1)));
                } else {
                    any = true;
                    line.append(Component.text(String.valueOf(PanelMath.PIXEL), TextColor.color(c & 0xFFFFFF)));
                    line.append(Component.text(String.valueOf(PanelMath.NEG1)));
                }
            }
            if (!any) {
                continue;
            }
            final PanelMath.Fit f = PanelMath.fit(box, PanelMath.camX(x, this.scale),
                PanelMath.camY(y + row * px, this.scale), w * px * this.scale, px * this.scale, 0, 0);
            final Component text = line.build();
            final Transformation t = new Transformation(
                new Vector3f((float) (f.tx() * k), (float) (f.ty() * k), (float) z), new Quaternionf(),
                new Vector3f((float) (f.sx() * k), (float) (f.sy() * k), 1f), new Quaternionf());
            this.spawn(TextDisplay.class, e -> {
                e.text(text);
                e.setDefaultBackground(false);
                e.setBackgroundColor(CLEAR);
                e.setShadowed(false);
                e.setLineWidth(4096);
                e.setTransformation(t);
            }, group);
        }
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
        if (d != null && d.isValid() && !text.equals(d.text())) {
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

    private static PanelMath.Glyph glyphOf(final String id) {
        final PanelMath.Glyph g = PanelMath.glyph(id);
        if (g == null) {
            throw new IllegalArgumentException("нет текстуры " + id);
        }
        return g;
    }

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

    /** Точка в единицах камеры (на плоскости z = −1) → перенос дисплея на глубине z. Дисплей стоит в глазах. */
    private static Vector3f at(final double x, final double y, final double z) {
        final double k = depthFactor(z);
        return new Vector3f((float) (x * k), (float) (y * k), (float) z);
    }

    /**
     * Перенос и масштаб глифа {@code glyph}: левый верхний угол в (left, top), ширина всей текстуры w, высота h —
     * единицы камеры на плоскости z = −1. Раскладка глифа — как у клиента ({@link PanelMath#glyphBox}).
     */
    private Transformation glyphTransform(final PanelMath.Glyph glyph, final double left, final double top,
                                          final double w, final double h, final double z, final boolean shown) {
        if (!shown || w <= 0 || h <= 0) {
            return new Transformation(at(left, top, z), new Quaternionf(), new Vector3f(0f, 0f, 0f), new Quaternionf());
        }
        final double k = depthFactor(z);
        final PanelMath.Fit f = PanelMath.fit(PanelMath.glyphBox(glyph.w(), glyph.h()), left, top, w, h,
            this.fineX, this.fineY);
        return new Transformation(at(f.tx(), f.ty(), z), new Quaternionf(),
            new Vector3f((float) (f.sx() * k), (float) (f.sy() * k), 1f), new Quaternionf());
    }

    private Transformation quadTransform(final PanelMath.Glyph glyph, final double x, final double y, final double w,
                                         final double h, final int layer, final boolean shown) {
        return this.glyphTransform(glyph, PanelMath.camX(x, this.scale), PanelMath.camY(y, this.scale),
            w * this.scale, h * this.scale, depth(layer), shown);
    }

    /** Символ текстуры шрифтом пака, без тени и подложки. */
    static Component glyphText(final PanelMath.Glyph glyph) {
        return Component.text(String.valueOf(glyph.ch())).font(FONT).color(NamedTextColor.WHITE);
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

    private Quad spawnQuad(final PanelMath.Glyph glyph, final double x, final double y, final double w,
                           final int layer, final boolean shown, final String group) {
        final TextDisplay d = this.spawnGlyph(glyph, this.quadTransform(glyph, x, y, w, glyph.h(), layer, shown), group);
        return new Quad(d, glyph, x, y, w, glyph.h(), layer, shown);
    }

    /**
     * Ванильный предмет: транформ FIXED — как в рамке, лицом к камере (GUI в дисплее показывает его задом, зеркально).
     */
    private ItemDisplay spawnItem(final double cx, final double cy, final double size, final ItemStack item,
                                  final double z, final String group) {
        final float s = (float) (size * depthFactor(z));
        final Vector3f at = at(cx, cy, z);
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
        final Vector3f at = at(cx, cy - h / 2, z);
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
        final float s = (float) (lineHeight / (PanelMath.LINE_H * PanelMath.FONT_PX) * depthFactor(z));
        final Vector3f at = at(cx, bottom, z);
        return this.spawn(TextDisplay.class, e -> {
            e.text(text);
            e.setDefaultBackground(false);
            e.setBackgroundColor(background == null ? CLEAR : background);
            e.setShadowed(true);
            e.setTransformation(new Transformation(at, new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
        }, group);
    }

    /** Дисплей в точке глаз, лицом к камере, только для этого игрока. */
    private <T extends Display> T spawn(final Class<T> type, final Consumer<T> setup, final String group) {
        return this.spawnAt(this.origin, type, Display.Billboard.CENTER, setup, group);
    }

    private <T extends Display> T spawnAt(final Location where, final Class<T> type, final Display.Billboard billboard,
                                          final Consumer<T> setup, final String group) {
        final World world = where.getWorld();
        final T d = world.spawn(where, type, e -> {
            e.setBillboard(billboard);
            e.setBrightness(e instanceof ItemDisplay ? ITEM_LIGHT : TEXT_LIGHT);
            e.setViewRange(1.0f);
            e.setPersistent(false);
            e.setVisibleByDefault(false);
            e.setTeleportDuration(0);
            setup.accept(e);
        });
        this.player.showEntity(this.plugin, d);
        this.elements.add(new Element(d, group));
        return d;
    }

    // --- курсор ---

    /**
     * Курсор-стрелка. {@code shader}: шейдерный (если пак с шейдером не стоит, курсор поедет против мыши — для
     * этого есть запасной режим в config.yml); иначе глиф из пака, остриё — левый верхний угол — в точке курсора.
     */
    public void arrowCursor(final boolean shader) {
        this.removeCursor();
        this.cursorCentered = false;
        this.cursorSize = 16 * this.scale;
        if (shader) {
            this.mode = CursorMode.SHADER;
            this.cu = 0;
            this.cv = 0;
            this.placeAnchor(this.refYaw, this.refPitch, 0, 0);
        } else {
            this.mode = CursorMode.ROTATION;
            this.cursor = this.spawnGlyph(PanelMath.glyph("cursor"), this.cursorTransform(), BASE);
        }
    }

    /** Курсор-предмет (ванильная иконка) размером {@code size} единиц камеры, центр — в точке курсора. */
    public void itemCursor(final ItemStack item, final double size) {
        this.removeCursor();
        this.mode = CursorMode.ROTATION;
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
        this.anchor = null;
    }

    private Transformation cursorTransform() {
        final double z = depth(CURSOR_LAYER);
        if (!this.cursorCentered) {
            return this.glyphTransform(PanelMath.glyph("cursor"), this.cu, this.cv, this.cursorSize, this.cursorSize, z, true);
        }
        final float s = (float) (this.cursorSize * depthFactor(z));
        return new Transformation(at(this.cu, this.cv, z), new Quaternionf(), new Vector3f(s, s, 0.001f),
            new Quaternionf());
    }

    private double[] eye() {
        return new double[] {this.origin.getX(), this.origin.getY(), this.origin.getZ()};
    }

    /**
     * Шейдерный курсор: поставить предмет в точку мира, которую шейдер при взгляде (yaw, pitch) покажет в (sx, sy).
     * Предмет смотрит на глаза; его правый нижний угол (остриё стрелки, повёрнутой на 180°) — ровно в этой точке.
     */
    private void placeAnchor(final float yaw, final float pitch, final double sx, final double sy) {
        final double[] eye = this.eye();
        final double[] p = PanelMath.anchor(eye, yaw, pitch, sx, sy, CURSOR_DIST);
        this.anchor = p;
        final float[] rot = PanelMath.lookAt(eye, p);
        final Location loc = new Location(this.origin.getWorld(), p[0], p[1], p[2], rot[0], rot[1]);
        if (this.cursor != null && this.cursor.isValid()) {
            this.cursor.teleport(loc);
            return;
        }
        // Размер на глубине CURSOR_DIST: на экране — как 16 px холста. Локальный x дисплея смотрит влево от
        // взгляда, y — вверх: сдвиг (+s/2, +s/2) ставит правый нижний угол квадрата в точку дисплея.
        final float s = (float) (this.cursorSize * CURSOR_DIST);
        final ItemStack item = new ItemStack(Material.PAPER);
        item.editMeta(m -> {
            m.setItemModel(CURSOR_MODEL);
            final org.bukkit.inventory.meta.components.CustomModelDataComponent cmd = m.getCustomModelDataComponent();
            cmd.setColors(List.of(MARKER));
            m.setCustomModelDataComponent(cmd);
        });
        this.cursor = this.spawnAt(loc, ItemDisplay.class, Display.Billboard.FIXED, e -> {
            e.setItemStack(item);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setTransformation(new Transformation(new Vector3f(s / 2, s / 2, 0f), new Quaternionf(),
                new Vector3f(s, s, s), new Quaternionf()));
        }, BASE);
    }

    /** Курсор — в центр (пробел). */
    public void recenter() {
        final Location l = this.player.getLocation();
        this.refYaw = l.getYaw();
        this.refPitch = l.getPitch();
        this.cu = 0;
        this.cv = 0;
        if (this.mode == CursorMode.SHADER) {
            this.placeAnchor(l.getYaw(), l.getPitch(), 0, 0);
        } else {
            this.moveCursor(0);
        }
        this.updateHover();
    }

    /** Такт: курсор по повороту головы и подсветка кнопки под ним. Поток игрока, каждый тик. */
    public void update() {
        if (this.closed) {
            return;
        }
        final Location l = this.player.getLocation();
        if (this.mode == CursorMode.SHADER) {
            this.updateShaderCursor(l.getYaw(), l.getPitch());
        } else {
            this.updateRotationCursor(l);
        }
        this.updateHover();
    }

    /** Сервер считает курсор тем же отражением, что и шейдер; у края экрана переставляет опорную точку. */
    private void updateShaderCursor(final float yaw, final float pitch) {
        if (this.anchor == null) {
            this.placeAnchor(yaw, pitch, 0, 0);
        }
        final double[] s = PanelMath.mirror(this.eye(), yaw, pitch, this.anchor);
        double u = s == null ? this.cu : s[0];
        double v = s == null ? this.cv : s[1];
        final boolean clamp = s == null || Math.abs(u) > this.boundW || Math.abs(v) > this.boundH;
        u = Math.max(-this.boundW, Math.min(this.boundW, u));
        v = Math.max(-this.boundH, Math.min(this.boundH, v));
        if (clamp) {
            this.placeAnchor(yaw, pitch, u, v);
        }
        this.cu = u;
        this.cv = v;
    }

    private void updateRotationCursor(final Location l) {
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
    }

    private void moveCursor(final int interpolation) {
        if (this.mode == CursorMode.ROTATION && this.cursor != null && this.cursor.isValid()) {
            this.cursor.setInterpolationDelay(0);
            this.cursor.setInterpolationDuration(interpolation);
            this.cursor.setTransformation(this.cursorTransform());
        }
    }

    private void updateHover() {
        final double px = this.cursorX();
        final double py = this.cursorY();
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

    private static void removeScale(final Player player) {
        final org.bukkit.attribute.AttributeInstance scale = player.getAttribute(org.bukkit.attribute.Attribute.SCALE);
        if (scale != null) {
            scale.removeModifier(SCALE_KEY);
        }
    }

    /** Вернуть игроку сохранённое состояние (и после падения сервера — из файла). */
    public static void restore(final Player player, final Saved saved) {
        if (!player.isOnline()) {
            return;
        }
        removeScale(player);
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
