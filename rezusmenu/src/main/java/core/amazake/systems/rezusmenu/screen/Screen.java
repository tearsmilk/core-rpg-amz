package core.amazake.systems.rezusmenu.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
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
 * Экран перед игроком из дисплеев: плитки (блок-дисплеи), текст и предметы. Видит его только этот игрок. Игрок сидит
 * на невидимом сиденье и не ходит; прицел — курсор: кнопка под прицелом подсвечивается, ЛКМ её нажимает.
 *
 * <p>Всё — в потоке игрока: экран стоит рядом с ним, в его регионе.</p>
 */
public final class Screen {

    /** Кнопка: прямоугольник на экране и действие. */
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

        /** Сменить цвета (выбранная клетка и т. п.). */
        public void colors(final Material normalColor, final Material hoverColor) {
            this.normal = normalColor;
            this.hover = hoverColor;
            Screen.this.paint(this, Screen.this.hovered == this);
        }
    }

    private record Element(Entity entity, String group) {
    }

    private final Plugin plugin;
    private final Player player;
    private final ArmorStand seat;
    private final float yaw;
    private final double distance;
    private final Location origin;
    private ScreenFrame frame;
    private final List<Element> elements = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private Quaternionf facing = new Quaternionf();
    private Button hovered;
    private boolean closed;

    private Screen(final Plugin plugin, final Player player, final ArmorStand seat, final float yaw,
                   final double distance, final Location origin) {
        this.plugin = plugin;
        this.player = player;
        this.seat = seat;
        this.yaw = yaw;
        this.distance = distance;
        this.origin = origin;
    }

    /**
     * Поставить плоскость экрана перед глазами сидящего игрока. Вызывается через тик после посадки, когда сервер уже
     * пересадил игрока и его глаза на своём месте.
     */
    public void place() {
        final Location eye = this.player.getEyeLocation();
        this.frame = ScreenFrame.facing(eye.getX(), eye.getY(), eye.getZ(), this.yaw, this.distance);
        // Локальная ось Z элемента смотрит на игрока, X — вправо по экрану.
        this.facing = new Quaternionf().rotationY((float) Math.atan2(-this.frame.fx(), -this.frame.fz()));
    }

    public boolean placed() {
        return this.frame != null;
    }

    /**
     * Посадить игрока и поставить пустой экран на {@code distance} блоков перед глазами. Поток игрока.
     * null — игрок сейчас не может открыть экран (едет на чём-то, мёртв, наблюдатель).
     */
    public static Screen open(final Plugin plugin, final Player player, final double distance) {
        if (player.isDead() || player.isInsideVehicle() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return null;
        }
        final Location origin = player.getLocation();
        final ScreenFrame frame = ScreenFrame.facing(0, 0, 0, origin.getYaw(), distance);
        final Location seatAt = origin.clone();
        seatAt.setYaw(frame.yaw());
        seatAt.setPitch(0f);
        player.teleport(seatAt);
        final ArmorStand seat = origin.getWorld().spawn(seatAt, ArmorStand.class, a -> {
            a.setInvisible(true);
            a.setMarker(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setPersistent(false);
            a.setVisibleByDefault(false);
        });
        // Сиденье должен видеть сам игрок, иначе его клиент не знает, что сидит, и даёт ходить.
        player.showEntity(plugin, seat);
        seat.addPassenger(player);
        return new Screen(plugin, player, seat, frame.yaw(), distance, origin);
    }

    public Player player() {
        return this.player;
    }

    public ArmorStand seat() {
        return this.seat;
    }

    public boolean closed() {
        return this.closed;
    }

    // --- элементы ---

    /** Плитка-прямоугольник: левый нижний угол (u, v), размер w × h. */
    public BlockDisplay rect(final double u, final double v, final double w, final double h, final int layer,
                             final Material color, final String group) {
        final double[] p = this.frame.point(u, v, layer);
        final World world = this.seat.getWorld();
        final BlockDisplay d = world.spawn(new Location(world, p[0], p[1], p[2]), BlockDisplay.class, e -> {
            e.setBlock(color.createBlockData());
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(this.facing),
                new Vector3f((float) w, (float) h, 0.002f), new Quaternionf()));
            this.common(e);
        });
        this.track(d, group);
        return d;
    }

    /**
     * Текст по центру прямоугольника (u, v, w, h). {@code lineHeight} — высота строки в блоках.
     */
    public TextDisplay text(final double u, final double v, final double w, final double h, final double lineHeight,
                            final Component text, final int layer, final String group) {
        final float scale = (float) (lineHeight / 0.25);
        final int lines = Math.max(1, countLines(text));
        final double textHeight = lineHeight * lines;
        final double[] p = this.frame.point(u + w / 2, v + (h - textHeight) / 2, layer);
        final World world = this.seat.getWorld();
        final TextDisplay d = world.spawn(new Location(world, p[0], p[1], p[2]), TextDisplay.class, e -> {
            e.text(text);
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setDefaultBackground(false);
            e.setShadowed(true);
            e.setLineWidth(Math.max(20, (int) Math.round(w / 0.025 / scale)));
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(this.facing),
                new Vector3f(scale, scale, scale), new Quaternionf()));
            this.common(e);
        });
        this.track(d, group);
        return d;
    }

    /** Предмет по центру (u, v), размер {@code size} блоков. */
    public ItemDisplay item(final double u, final double v, final double size, final ItemStack item, final int layer,
                            final String group) {
        final double[] p = this.frame.point(u, v, layer);
        final World world = this.seat.getWorld();
        final ItemDisplay d = world.spawn(new Location(world, p[0], p[1], p[2]), ItemDisplay.class, e -> {
            e.setItemStack(item == null ? null : item.clone());
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(this.facing),
                new Vector3f((float) size, (float) size, 0.02f), new Quaternionf()));
            this.common(e);
        });
        this.track(d, group);
        return d;
    }

    /** Кнопка: плитка с подсветкой под прицелом. */
    public Button button(final double u, final double v, final double w, final double h, final int layer,
                         final Material normal, final Material hover, final String group, final Consumer<Button> action) {
        final BlockDisplay plate = this.rect(u, v, w, h, layer, normal, group);
        final Button b = new Button(u, v, w, h, plate, normal, hover, action, group);
        this.buttons.add(b);
        return b;
    }

    /** Новый размер плитки (полоска здоровья, опыта). Левый нижний угол остаётся на месте. */
    public void resize(final BlockDisplay d, final double w, final double h) {
        if (d != null && d.isValid()) {
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(this.facing),
                new Vector3f((float) Math.max(0.0001, w), (float) h, 0.002f), new Quaternionf()));
        }
    }

    /** Подвинуть текстовый дисплей на новый текст (без пересоздания). */
    public static void setText(final TextDisplay d, final Component text) {
        if (d != null && d.isValid()) {
            d.text(text);
        }
    }

    /** Убрать все элементы и кнопки группы (содержимое вкладки). */
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

    private void common(final Display e) {
        e.setBillboard(Display.Billboard.FIXED);
        e.setBrightness(new Display.Brightness(15, 15));
        e.setViewRange(0.3f);
        e.setPersistent(false);
        e.setVisibleByDefault(false);
    }

    private void track(final Entity e, final String group) {
        this.player.showEntity(this.plugin, e);
        this.elements.add(new Element(e, group));
    }

    // --- курсор и клики ---

    /** Пересчитать кнопку под прицелом. Поток игрока, каждые пару тиков. */
    public void updateHover() {
        if (this.closed || this.frame == null) {
            return;
        }
        final Location eye = this.player.getEyeLocation();
        final org.bukkit.util.Vector dir = eye.getDirection();
        final double[] uv = this.frame.hit(eye.getX(), eye.getY(), eye.getZ(), dir.getX(), dir.getY(), dir.getZ());
        Button now = null;
        if (uv != null) {
            for (final Button b : this.buttons) {
                if (b.contains(uv[0], uv[1])) {
                    now = b;
                }
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

    /** ЛКМ: нажать кнопку под прицелом. */
    public void click() {
        this.updateHover();
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

    /** Убрать экран и сиденье, вернуть игрока на место. Поток игрока. */
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
        if (this.seat.isValid()) {
            this.seat.eject();
            this.seat.remove();
        }
        if (this.player.isOnline() && !this.player.isDead() && this.player.getWorld().equals(this.origin.getWorld())) {
            final Location back = this.origin.clone();
            back.setYaw(this.player.getLocation().getYaw());
            back.setPitch(this.player.getLocation().getPitch());
            this.player.teleport(back);
        }
    }

    private static int countLines(final Component c) {
        final String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
        return (int) plain.chars().filter(ch -> ch == '\n').count() + 1;
    }
}
