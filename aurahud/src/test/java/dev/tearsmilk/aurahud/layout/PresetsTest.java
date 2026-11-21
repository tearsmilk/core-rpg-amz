package dev.tearsmilk.aurahud.layout;

import dev.tearsmilk.aurahud.layout.Placement.Point;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetsTest {

    private static final int[][] SIZES = {{427, 240}, {480, 270}, {640, 360}};

    @Test
    void everyBuiltInPresetLoadsCleanly() {
        for (String name : Presets.BUILT_IN) {
            YamlConfiguration y = Presets.builtIn(name);
            assertNotNull(y, name);
            assertTrue(y.isString("description"), name);
            HudSettings s = HudSettings.read(y.getConfigurationSection("hud"));
            assertEquals(List.of(), s.warnings(), name);
            assertEquals(!name.equals("vanilla"), GlslConfig.active(LayoutEngine.place(s)), name);
        }
    }

    @Test
    void presetElementsStayOnScreen() {
        for (String name : Presets.BUILT_IN) {
            Map<Element, Placement> p = LayoutEngine.place(HudSettings.read(Presets.builtIn(name).getConfigurationSection("hud")));
            for (int[] size : SIZES) {
                for (Placement e : p.values()) {
                    if (e.mode() == Placement.Mode.HIDDEN || e.element() == Element.ITEM_NAME
                            || (e.element() == Element.OFFHAND && e.geometry().follow())) {
                        continue;
                    }
                    int[] r = rect(e, size[0], size[1]);
                    String where = name + " " + e.element().id() + " " + size[0] + "x" + size[1];
                    assertTrue(r[0] >= 0 && r[1] >= 0 && r[2] <= size[0] && r[3] <= size[1] + 1, where + " " + java.util.Arrays.toString(r));
                }
            }
        }
    }

    @Test
    void presetsDoNotOverlapAt427x240() {
        List<List<Element>> allowed = List.of(List.of(Element.FOOD, Element.MOUNT));
        for (String name : Presets.BUILT_IN) {
            if (name.equals("vanilla")) {
                continue;
            }
            Map<Element, Placement> p = LayoutEngine.place(HudSettings.read(Presets.builtIn(name).getConfigurationSection("hud")));
            for (Element a : Element.values()) {
                for (Element b : Element.values()) {
                    if (a.ordinal() >= b.ordinal() || a == Element.ITEM_NAME || b == Element.ITEM_NAME
                            || allowed.contains(List.of(a, b)) || hidden(p, a) || hidden(p, b)) {
                        continue;
                    }
                    if ((a == Element.OFFHAND && p.get(a).geometry().follow()) || (b == Element.OFFHAND && p.get(b).geometry().follow())) {
                        continue;
                    }
                    int[] ra = rect(p.get(a), 427, 240);
                    int[] rb = rect(p.get(b), 427, 240);
                    boolean overlap = ra[0] < rb[2] && rb[0] < ra[2] && ra[1] < rb[3] && rb[1] < ra[3];
                    assertTrue(!overlap, name + ": " + a.id() + " overlaps " + b.id());
                }
            }
        }
    }

    private static boolean hidden(Map<Element, Placement> p, Element e) {
        return p.get(e).mode() == Placement.Mode.HIDDEN;
    }

    static int[] rect(Placement e, int w, int h) {
        Point tl = e.topLeft(w, h);
        if (e.element().shape() != Element.Shape.ICONS) {
            return new int[]{tl.x(), tl.y(), tl.x() + e.geometry().width(), tl.y() + e.geometry().height()};
        }
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int y1 = Integer.MIN_VALUE;
        Element.Icons ic = e.element().icons();
        for (int i = 0; i < ic.base(); i++) {
            if ((e.geometry().hiddenMask() >> i & 1) != 0) {
                continue;
            }
            Point q = tl.plus(e.geometry().positions().get(i));
            x0 = Math.min(x0, q.x());
            y0 = Math.min(y0, q.y());
            x1 = Math.max(x1, q.x() + ic.width());
            y1 = Math.max(y1, q.y() + ic.height());
        }
        return new int[]{x0, y0, x1, y1};
    }

    @Test
    void customPresetsSaveAndLoad(@TempDir Path dir) throws IOException {
        Presets presets = new Presets(dir);
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("hud.hearts.anchor", "top-left");
        cfg.set("hud.hotbar.slots.3.y", -5);
        presets.save("Mine", cfg.getConfigurationSection("hud"), "test");
        assertTrue(presets.names().contains("mine"));
        YamlConfiguration back = presets.load("mine");
        assertEquals("top-left", back.getString("hud.hearts.anchor"));
        assertEquals(-5, back.getInt("hud.hotbar.slots.3.y"));
        assertNull(presets.load("../etc"));
        assertNotNull(presets.load("vanilla"));
        YamlConfiguration target = new YamlConfiguration();
        target.set("hud.food.x", 3);
        Presets.copy(back.getConfigurationSection("hud"), target, "hud");
        assertTrue(!target.contains("hud.food.x"));
        assertEquals(-5, target.getInt("hud.hotbar.slots.3.y"));
    }
}
