package ink.hoka.odyssey.client.devprobe;

import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Зонд стенда разработки (tools/devclient). Игрокам не раздаётся — только локальный сервер стенда.
 * <ul>
 *   <li>при загрузке пишет в лог «devprobe: загружен …» — по этой строке скрипты стенда понимают, что модули дошли;</li>
 *   <li>маленькая строка на HUD: FPS, мир, координаты (выключается командой {@code hud off});</li>
 *   <li>«скриптовый» режим — ввод без xdotool: файл {@code <dataDir>/script.txt} (обычно
 *       {@code .minecraft/hoka_odyssey/data/devprobe/script.txt}); как только файл меняется, команды из него
 *       выполняются по одной за тик. По окончании — строка «devprobe: скрипт выполнен» в логе и файл
 *       {@code script.done} рядом.</li>
 * </ul>
 * Команды скрипта (по одной в строке, # — комментарий):
 * <pre>
 *   wait &lt;тики&gt;                     — пауза
 *   hold &lt;клавиша&gt; &lt;тики&gt;          — держать: forward back left right jump sneak sprint
 *   press &lt;клавиша&gt;                 — нажать на 1 тик
 *   look &lt;yaw&gt; &lt;pitch&gt;             — повернуть взгляд (градусы)
 *   turn &lt;dyaw&gt; &lt;dpitch&gt;           — довернуть взгляд
 *   chat &lt;текст или /команда&gt;       — сообщение в чат или команда серверу
 *   shot [имя]                      — снимок экрана в screenshots/&lt;имя&gt;.png
 *   screen                          — в лог: открытый экран («devprobe: экран нет» — в игре)
 *   hud on|off                      — строка зонда на HUD
 *   log &lt;текст&gt;                     — строка в лог
 * </pre>
 */
public final class DevProbeModule implements ClientModule {

    private ClientContext ctx;
    private Path script;
    private long scriptStamp = Long.MIN_VALUE;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Map<KeyMapping, Integer> held = new HashMap<>();
    private int waitTicks;
    private boolean running;
    private boolean hud = true;

    @Override
    public void enable(ClientContext ctx) {
        this.ctx = ctx;
        Minecraft mc = Minecraft.getInstance();
        script = ctx.dataDir().resolve("script.txt");
        ctx.logger().info("devprobe: загружен (мир {}, скрипт {})",
                mc.level == null ? "—" : mc.level.dimension().identifier(), script);
        // старый script.txt не запускаем заново после перезахода — только изменения после загрузки
        scriptStamp = stamp();
        ctx.onTick(this::tick);
        ctx.onHud((g, delta) -> {
            if (!hud) return;
            var p = mc.player;
            String s = "devprobe · " + mc.getFps() + " FPS"
                    + (mc.level == null ? "" : " · " + mc.level.dimension().identifier())
                    + (p == null ? "" : String.format(Locale.ROOT, " · %.1f %.1f %.1f", p.getX(), p.getY(), p.getZ()));
            g.drawString(mc.font, s, 4, 16, 0xA0FFFFFF, true);    // под строкой skyspike, не на хотбаре
        });
    }

    @Override
    public void disable() {
        releaseAll();
    }

    private long stamp() {
        try {
            return Files.isRegularFile(script) ? Files.getLastModifiedTime(script).toMillis() : Long.MIN_VALUE;
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    private void tick() {
        long st = stamp();
        if (st != scriptStamp) {                                   // новый скрипт — начать заново
            scriptStamp = st;
            if (st != Long.MIN_VALUE) load();
        }
        // удерживаемые клавиши
        held.replaceAll((k, left) -> left - 1);
        held.entrySet().removeIf(e -> {
            if (e.getValue() > 0) { e.getKey().setDown(true); return false; }
            e.getKey().setDown(false);
            return true;
        });
        if (!running) return;
        if (waitTicks > 0) { waitTicks--; return; }
        String line = queue.poll();
        if (line == null) {
            if (held.isEmpty()) finish();
            return;
        }
        try {
            run(line);
        } catch (RuntimeException e) {
            ctx.logger().warn("devprobe: строка скрипта «{}» не выполнена: {}", line, e.toString());
        }
    }

    private void load() {
        try {
            List<String> lines = Files.readAllLines(script, StandardCharsets.UTF_8);
            releaseAll();
            queue.clear();
            for (String l : lines) {
                l = l.strip();
                if (!l.isEmpty() && !l.startsWith("#")) queue.add(l);
            }
            waitTicks = 0;
            running = true;
            Files.deleteIfExists(script.resolveSibling("script.done"));
            ctx.logger().info("devprobe: скрипт, строк {}", queue.size());
        } catch (IOException e) {
            ctx.logger().warn("devprobe: скрипт не прочитан: {}", e.toString());
        }
    }

    private void finish() {
        running = false;
        ctx.logger().info("devprobe: скрипт выполнен");
        try {
            Files.writeString(script.resolveSibling("script.done"), "ok\n");
        } catch (IOException ignored) {
            // только метка для скриптов стенда
        }
    }

    private void run(String line) {
        Minecraft mc = Minecraft.getInstance();
        String[] a = line.split("\\s+", 2);
        String arg = a.length > 1 ? a[1] : "";
        String[] w = arg.isEmpty() ? new String[0] : arg.split("\\s+");
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "wait" -> waitTicks = Math.max(0, Integer.parseInt(w[0]) - 1);
            case "hold" -> hold(key(w[0]), Integer.parseInt(w[1]));
            case "press" -> hold(key(w[0]), 1);
            case "look" -> {
                if (mc.player == null) return;
                mc.player.setYRot(Float.parseFloat(w[0]));
                mc.player.setXRot(Float.parseFloat(w[1]));
            }
            case "turn" -> {
                if (mc.player == null) return;
                mc.player.setYRot(mc.player.getYRot() + Float.parseFloat(w[0]));
                mc.player.setXRot(mc.player.getXRot() + Float.parseFloat(w[1]));
            }
            case "chat" -> {
                if (mc.player == null) return;
                if (arg.startsWith("/")) mc.player.connection.sendCommand(arg.substring(1));
                else mc.player.connection.sendChat(arg);
            }
            case "shot" -> {
                String name = (w.length > 0 ? w[0] : "devprobe-" + System.currentTimeMillis()) + ".png";
                Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), 1,
                        msg -> ctx.logger().info("devprobe: снимок {} — {}", name, msg.getString()));
            }
            case "screen" -> ctx.logger().info("devprobe: экран {}",
                    mc.screen == null ? "нет" : "«" + mc.screen.getTitle().getString() + "»");
            case "hud" -> hud = !"off".equalsIgnoreCase(arg);
            case "log" -> ctx.logger().info("devprobe: {}", arg);
            default -> ctx.logger().warn("devprobe: неизвестная команда «{}»", a[0]);
        }
    }

    private static KeyMapping key(String name) {
        var o = Minecraft.getInstance().options;
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "forward", "w" -> o.keyUp;
            case "back", "s" -> o.keyDown;
            case "left", "a" -> o.keyLeft;
            case "right", "d" -> o.keyRight;
            case "jump", "space" -> o.keyJump;
            case "sneak", "shift" -> o.keyShift;
            case "sprint", "ctrl" -> o.keySprint;
            default -> throw new IllegalArgumentException("клавиша " + name);
        };
    }

    /** Нажать сразу и держать {@code ticks} тиков (отпускает tick()). */
    private void hold(KeyMapping k, int ticks) {
        k.setDown(true);
        held.put(k, Math.max(1, ticks));
    }

    private void releaseAll() {
        held.keySet().forEach(k -> k.setDown(false));
        held.clear();
    }
}
