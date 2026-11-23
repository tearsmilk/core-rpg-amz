package ink.hoka.odyssey.client.starmap;

import com.mojang.blaze3d.platform.InputConstants;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;

/**
 * Карта-радар (клавиша M): вид сверху на прогруженные блоки вокруг, ты, корабли и игроки (с сервера — модуль
 * starmap), свои точки. Окно — {@link RadarScreen}, слой блоков — {@link Terrain}, точки — {@link Waypoints}.
 *
 * <p>Протокол (канал «starmap», big-endian). Клиент → сервер: op 1 — карта открыта/закрыта: {@code u8 op, u8 open}
 * (пока открыта — повтор раз в 5 с); op 2 — «на какой я карте»: {@code u8 op} (при входе и смене мира).
 * Сервер → клиент: op 1 — контакты (см. {@link Contacts}); op 2 — имя карты: {@code u8 op, UTF map}.
 * Без серверного модуля карта работает сама: блоки, ты, игроки, которых видит клиент, точки.
 */
public final class StarmapClientModule implements ClientModule {
    static final int OP_OPEN = 1;
    static final int OP_WHERE = 2;
    private static final int KEEPALIVE_TICKS = 100;

    ClientContext ctx;
    MapConfig cfg;
    final Terrain terrain = new Terrain();
    final Contacts contacts = new Contacts();
    Waypoints waypoints;
    InputConstants.Key openKey;
    InputConstants.Key recenterKey;
    /** Радиус обзора — запоминается между открытиями. */
    double range;

    private boolean openDown;
    /** В прошлый тик было открыто окно (любое): тогда M не открывает карту — иначе M, закрывшая карту, тут же
     *  открыла бы её снова. */
    private boolean hadScreen;
    private boolean enabled;
    private ClientLevel lastLevel;
    private long ticks;
    private long lastKeepalive;
    private RadarScreen screen;

    @Override
    public void enable(ClientContext ctx) {
        this.ctx = ctx;
        cfg = MapConfig.load(ctx.dataDir(), ctx.logger());
        openKey = key(cfg.openKey, "key.keyboard.m");
        recenterKey = key(cfg.recenterKey, "key.keyboard.r");
        range = cfg.defaultRange;
        waypoints = new Waypoints(ctx.dataDir(), ctx.logger());
        enabled = true;
        ctx.onMessage(this::onMessage);
        ctx.onTick(this::tick);
        ctx.onHud(this::hud);
        ctx.logger().info("starmap: загружен (открыть — {}, блоки {}, метки точек на экране {})", cfg.openKey,
                cfg.terrain ? "вкл." : "выкл.", cfg.hudWaypoints ? "вкл." : "выкл.");
    }

    @Override
    public void disable() {
        enabled = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof RadarScreen) mc.setScreen(null);  // removed() скажет серверу «закрыта»
        screen = null;
        if (waypoints != null) waypoints.save();
        terrain.close(mc);
        contacts.clear();
    }

    private InputConstants.Key key(String name, String fallback) {
        try {
            return InputConstants.getKey(name);
        } catch (RuntimeException e) {
            ctx.logger().warn("starmap: неизвестная клавиша «{}» — используется {}", name, fallback);
            return InputConstants.getKey(fallback);
        }
    }

    // ---------------------------------------------------------------- сеть

    private void onMessage(byte[] data) {
        try {
            if (contacts.read(data)) syncWaypoints(Minecraft.getInstance());
        } catch (IOException | RuntimeException e) {
            ctx.logger().warn("starmap: битое сообщение сервера ({} байт): {}", data == null ? 0 : data.length, e.toString());
        }
    }

    void sendOpen(boolean open) {
        if (!enabled) return;
        lastKeepalive = ticks;
        ctx.send(new byte[]{OP_OPEN, (byte) (open ? 1 : 0)});
    }

    // ---------------------------------------------------------------- тик

    private void tick() {
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastLevel) {                                // вход, смена мира, возрождение
            lastLevel = mc.level;
            contacts.clear();
            syncWaypoints(mc);
            if (mc.level != null) ctx.send(new byte[]{OP_WHERE});
        }
        boolean down = physical(mc, openKey);
        if (down && !openDown && !hadScreen && mc.screen == null && mc.player != null && mc.level != null) {
            screen = new RadarScreen(this);
            mc.setScreen(screen);
        }
        openDown = down;
        hadScreen = mc.screen != null;
        if (mc.screen instanceof RadarScreen && ticks - lastKeepalive >= KEEPALIVE_TICKS) sendOpen(true);
    }

    /** Точки — для этого сервера и карты (имя карты с сервера; без него — измерение игры). */
    void syncWaypoints(Minecraft mc) {
        String server;
        if (mc.getCurrentServer() != null) server = mc.getCurrentServer().ip;
        else server = mc.isLocalServer() ? "singleplayer" : "unknown";
        String map = contacts.map;
        if (map.isEmpty()) map = mc.level == null ? "none" : mc.level.dimension().identifier().toString();
        waypoints.use(server, map);
    }

    static boolean physical(Minecraft mc, InputConstants.Key key) {
        if (key == null || key.equals(InputConstants.UNKNOWN) || mc.getWindow() == null) return false;
        int v = key.getValue();
        if (v < 0) return false;
        return switch (key.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(mc.getWindow(), v);
            case MOUSE -> GLFW.glfwGetMouseButton(mc.getWindow().handle(), v) == GLFW.GLFW_PRESS;
            default -> false;
        };
    }

    // ---------------------------------------------------------------- метки точек на экране

    private void hud(GuiGraphics g, DeltaTracker delta) {
        if (!cfg.hudWaypoints) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || mc.options.hideGui || waypoints.all().isEmpty()) return;
        float camYaw = mc.gameRenderer.getMainCamera().yRot();
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        int w = g.guiWidth();
        int half = Math.min(140, w / 3);
        for (Waypoints.Wp wp : waypoints.all()) {
            double dx = wp.x + 0.5 - px, dz = wp.z + 0.5 - pz, dy = wp.y - py;
            double dist = Math.sqrt(dx * dx + dz * dz + dy * dy);
            if (dist > cfg.hudWaypointsDistance) continue;
            double bearing = Math.toDegrees(Math.atan2(-dx, dz));
            double rel = wrap(bearing - camYaw);
            if (Math.abs(rel) > 60) continue;
            int x = w / 2 + (int) Math.round(rel / 60 * half);
            int col = Radar.AMBER;
            Radar.diamond(g, x, 6, 3, col);
            String s = wp.name + " " + Radar.dist(dist);
            g.drawString(mc.font, s, x - mc.font.width(s) / 2, 12, col, true);
        }
    }

    static double wrap(double deg) {
        deg %= 360;
        if (deg > 180) deg -= 360;
        if (deg < -180) deg += 360;
        return deg;
    }
}
