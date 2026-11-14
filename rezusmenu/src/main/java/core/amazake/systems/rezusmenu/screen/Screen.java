package core.amazake.systems.rezusmenu.screen;

import core.amazake.systems.rezusmenu.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
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
 * <p>Мышь всё равно поворачивает голову: сервер читает поворот и двигает по экрану курсор-стрелку. Кнопка под
 * курсором подсвечивается, ЛКМ нажимает. Видит экран только этот игрок. Всё — в потоке игрока.</p>
 *
 * <p>Единицы: «камера» — блоки на расстоянии 1 перед глазами, (0, 0) — центр экрана; «макет» — координаты,
 * которыми рисует меню, переводятся в камеру умножением на {@link #scale}.</p>
 */
public final class Screen {

    /** Видимая половина экрана в единицах камеры (на расстоянии 1): узнаётся калибровкой. */
    public record Calibration(double halfW, double halfH) {

        /** Поле зрения 70°, монитор 16:9 — пока игрок не откалибровал. */
        public static final Calibration DEFAULT = new Calibration(1.245, 0.700);
    }

    /** Кнопка: прямоугольник в единицах макета и действие. */
    public final class Button {
        final double u0;
        final double v0;
        final double u1;
        final double v1;
        final BlockDisplay plate;
        Material normal;
        Material hover;
        final Consumer<Button> action;
        final String group;

        Button(final double u0, final double v0, final double w, final double h, final BlockDisplay plate,
               final Material normal, final Material hover, final Consumer<Button> action, final String group) {
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u0 + w;
            this.v1 = v0 + h;
            this.plate = plate;
            this.normal = normal;
            this.hover = hover;
            this.action = action;
            this.group = group;
        }

        boolean contains(final double u, final double v) {
            return u >= this.u0 && u <= this.u1 && v >= this.v0 && v <= this.v1;
        }

        /** Сменить цвета (выбранная клетка, активная вкладка). */
        public void colors(final Material normalColor, final Material hoverColor) {
            this.normal = normalColor;
            this.hover = hoverColor;
            Screen.this.paint(this, Screen.this.hovered == this);
        }
    }

    /** Что вернуть игроку после меню. */
    public record Saved(GameMode mode, Location location, float walkSpeed, float flySpeed, boolean allowFlight,
                        boolean flying) {
    }

    private record Element(Entity entity, String group) {
    }

    private static final double LAYER_STEP = 0.0015;
    private static final float CURSOR_SIZE = 0.07f;

    private final Plugin plugin;
    private final Player player;
    private final Saved saved;
    private final Calibration calibration;
    private final double eyeOffset;
    private final double sensitivity;
    private final List<Element> elements = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private double scale = 1.0;
    private double boundW;
    private double boundH;
    private ItemDisplay cursor;
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
    }

    /**
     * Перевести игрока в режим меню (наблюдатель, без движения, взгляд прямо) и поставить фон и курсор.
     * Поток игрока. null — сейчас нельзя (мёртв, едет на чём-то).
     *
     * @param eyeOffset насколько точка пассажиров выше глаз (у игрока 1.8 − 1.62)
     * @param sensitivity сколько единиц камеры проходит курсор за градус поворота мыши
     */
    public static Screen open(final Plugin plugin, final Player player, final Calibration calibration,
                              final double eyeOffset, final double sensitivity) {
        if (player.isDead() || player.isInsideVehicle()) {
            return null;
        }
        final Saved saved = new Saved(player.getGameMode(), player.getLocation(), player.getWalkSpeed(),
            player.getFlySpeed(), player.getAllowFlight(), player.isFlying());
        final Screen screen = new Screen(plugin, player, saved, calibration, eyeOffset, sensitivity);
        final Location look = player.getLocation();
        look.setPitch(0f);
        player.teleport(look);
        player.setGameMode(GameMode.SPECTATOR);
        player.setFlySpeed(0f);
        player.setWalkSpeed(0f);
        screen.refYaw = look.getYaw();
        screen.refPitch = 0f;
        screen.backdrop();
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

    /** Масштаб макета → камера. */
    public void scale(final double value) {
        this.scale = value;
    }

    public double scale() {
        return this.scale;
    }

    /** Куда может уйти курсор (единицы камеры). По умолчанию — откалиброванный экран. */
    public void cursorBounds(final double halfW, final double halfH) {
        this.boundW = halfW;
        this.boundH = halfH;
    }

    /** Фон во весь экран (с запасом) и табличка для вида от третьего лица. */
    private void backdrop() {
        final double w = this.calibration.halfW() * 3;
        final double h = this.calibration.halfH() * 3;
        this.spawnRect(-w, -h, 2 * w, 2 * h, -1.02, Material.BLACK_CONCRETE, "base");
        // За спиной: видно только после F5 (камера отъезжает назад или встаёт спереди и смотрит на игрока).
        this.spawnRect(-4, -3, 8, 6, 1.6, Material.BLACK_CONCRETE, "base");
        this.spawnText(0, -0.15, 0.08, Text.item("<gold>Вы в режиме от третьего лица.\nНажмите F5, чтобы вернуться в меню.</gold>"),
            1.55, "base");
        this.cursor = this.spawn(ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(Material.ARROW));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
            e.setTransformation(this.cursorTransform());
        }, "base");
    }

    // --- элементы (в единицах макета) ---

    /** Плитка: левый нижний угол (u, v), размер w × h. */
    public BlockDisplay rect(final double u, final double v, final double w, final double h, final int layer,
                             final Material color, final String group) {
        return this.spawnRect(u * this.scale, v * this.scale, w * this.scale, h * this.scale, depth(layer), color, group);
    }

    /** Текст по центру прямоугольника; {@code lineHeight} — высота строки (макет). */
    public TextDisplay text(final double u, final double v, final double w, final double h, final double lineHeight,
                            final Component text, final int layer, final String group) {
        final int lines = Math.max(1, countLines(text));
        final double textH = lineHeight * lines;
        final TextDisplay d = this.spawnText((u + w / 2) * this.scale, (v + (h - textH) / 2) * this.scale,
            lineHeight * this.scale, text, depth(layer), group);
        d.setLineWidth(Math.max(20, (int) Math.round(w / lineHeight * 10)));
        return d;
    }

    /** Предмет по центру (u, v), размер {@code size} (макет). */
    public ItemDisplay item(final double u, final double v, final double size, final ItemStack item, final int layer,
                            final String group) {
        final float s = (float) (size * this.scale);
        final Vector3f at = this.at(u * this.scale, v * this.scale, depth(layer));
        return this.spawn(ItemDisplay.class, e -> {
            e.setItemStack(item == null ? null : item.clone());
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
            e.setTransformation(new Transformation(at, new Quaternionf(), new Vector3f(s, s, 0.001f), new Quaternionf()));
        }, group);
    }

    /** Кнопка: плитка с подсветкой под курсором. */
    public Button button(final double u, final double v, final double w, final double h, final int layer,
                         final Material normal, final Material hover, final String group, final Consumer<Button> action) {
        final BlockDisplay plate = this.rect(u, v, w, h, layer, normal, group);
        final Button b = new Button(u, v, w, h, plate, normal, hover, action, group);
        this.buttons.add(b);
        return b;
    }

    /** Новый размер плитки (полоски), левый нижний угол на месте. Размеры — макет. */
    public void resize(final BlockDisplay d, final double w, final double h) {
        if (d != null && d.isValid()) {
            final Transformation t = d.getTransformation();
            d.setTransformation(new Transformation(t.getTranslation(), new Quaternionf(),
                new Vector3f((float) Math.max(0.00001, w * this.scale), (float) (h * this.scale), 0.0005f), new Quaternionf()));
        }
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
            this.hovered = null;
        }
    }

    // --- низкий уровень (единицы камеры) ---

    private static double depth(final int layer) {
        return -1.0 + layer * LAYER_STEP;
    }

    private Vector3f at(final double x, final double y, final double z) {
        return new Vector3f((float) x, (float) (y - this.eyeOffset), (float) z);
    }

    private BlockDisplay spawnRect(final double x, final double y, final double w, final double h, final double z,
                                   final Material color, final String group) {
        final Vector3f at = this.at(x, y, z);
        return this.spawn(BlockDisplay.class, e -> {
            e.setBlock(color.createBlockData());
            e.setTransformation(new Transformation(at, new Quaternionf(),
                new Vector3f((float) w, (float) h, 0.0005f), new Quaternionf()));
        }, group);
    }

    private TextDisplay spawnText(final double cx, final double y, final double lineHeight, final Component text,
                                  final double z, final String group) {
        final float s = (float) (lineHeight / 0.25);
        final Vector3f at = this.at(cx, y, z);
        return this.spawn(TextDisplay.class, e -> {
            e.text(text);
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setDefaultBackground(false);
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

    private Transformation cursorTransform() {
        // Иконка стрелы смотрит остриём вправо-вверх; поворот на 90° — остриё влево-вверх, как у курсора мыши.
        final float s = CURSOR_SIZE;
        final Vector3f at = this.at(this.cu + s * 0.42, this.cv - s * 0.42, depth(60));
        return new Transformation(at, new Quaternionf().rotationZ((float) (Math.PI / 2)), new Vector3f(s, s, 0.001f),
            new Quaternionf());
    }

    /** Курсор — в центр (пробел). */
    public void recenter() {
        final Location l = this.player.getLocation();
        this.refYaw = l.getYaw();
        this.refPitch = l.getPitch();
        this.cu = 0;
        this.cv = 0;
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
            if (this.cursor.isValid()) {
                this.cursor.setInterpolationDelay(0);
                this.cursor.setInterpolationDuration(1);
                this.cursor.setTransformation(this.cursorTransform());
            }
        }
        this.updateHover();
    }

    private void updateHover() {
        final double lu = this.cu / this.scale;
        final double lv = this.cv / this.scale;
        Button now = null;
        for (final Button b : this.buttons) {
            if (b.contains(lu, lv)) {
                now = b;
            }
        }
        if (now != this.hovered) {
            if (this.hovered != null) {
                this.paint(this.hovered, false);
            }
            if (now != null) {
                this.paint(now, true);
            }
            this.hovered = now;
        }
    }

    /** ЛКМ: нажать кнопку под курсором. */
    public void click() {
        if (this.hovered != null && !this.closed) {
            this.hovered.action.accept(this.hovered);
        }
    }

    public Button hovered() {
        return this.hovered;
    }

    void paint(final Button b, final boolean hover) {
        if (b.plate.isValid()) {
            b.plate.setBlock((hover ? b.hover : b.normal).createBlockData());
        }
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
        restore(this.player, this.saved);
    }

    /** Вернуть игроку сохранённое состояние (и после падения сервера — из файла). */
    public static void restore(final Player player, final Saved saved) {
        if (!player.isOnline()) {
            return;
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
