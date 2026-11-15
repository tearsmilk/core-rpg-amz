package core.amazake.systems.rezusmenu.profiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.systems.rezusmenu.screen.PanelMath;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Пак в pack/ совпадает с таблицей глифов кода: символ, файл, размер; правый столбец каждой текстуры не пустой. */
class PackTest {

    @Test
    void uiJsonMatchesGlyphTable() throws Exception {
        final Path root = Path.of("pack/assets/rezusmenu");
        final String json = Files.readString(root.resolve("font/ui.json"));
        final Matcher m = Pattern.compile("\"file\": \"rezusmenu:ui/([a-z0-9_]+)\\.png\",\\s*\"ascent\": (\\d+),\\s*"
            + "\"height\": (\\d+),\\s*\"chars\": \\[\\s*\"\\\\u([0-9a-fA-F]{4})\"").matcher(json);
        final List<String> seen = new ArrayList<>();
        while (m.find()) {
            final String id = m.group(1);
            if (id.equals("pixel")) {
                continue;
            }
            seen.add(id);
            final PanelMath.Glyph g = PanelMath.glyph(id);
            assertEquals((int) g.ch(), Integer.parseInt(m.group(4), 16), id);
            assertEquals(g.h(), Integer.parseInt(m.group(3)), id);
            assertTrue(Integer.parseInt(m.group(2)) <= Integer.parseInt(m.group(3)), id + ": ascent <= height");
            final BufferedImage img = ImageIO.read(root.resolve("textures/ui/" + id + ".png").toFile());
            assertEquals(g.w(), img.getWidth(), id);
            assertEquals(g.h(), img.getHeight(), id);
            boolean last = false;
            for (int y = 0; y < img.getHeight(); y++) {
                last |= (img.getRGB(img.getWidth() - 1, y) >>> 24) != 0;
            }
            assertTrue(last, id + ": правый столбец пустой — клиент сузит глиф");
        }
        assertEquals(PanelMath.glyphIds().size(), seen.size());
    }
}
