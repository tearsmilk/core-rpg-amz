package core.amazake.systems.corenpc.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Пакет ресурсов pack/assets/corenpc по правилам клиента 1.21.11: у bitmap ascent ≤ height (иначе клиент отбрасывает
 * весь файл шрифта), каждая клетка глифа не больше 256×256 (атлас FontTexture), все символы раскладки есть в шрифтах.
 */
class PackTest {

    private static final Path ASSETS = Path.of("pack", "assets", "corenpc");

    @Test
    void fontsFollowClientRules() throws Exception {
        try (Stream<Path> files = Files.list(ASSETS.resolve("font"))) {
            for (final Path f : (Iterable<Path>) files::iterator) {
                check(f);
            }
        }
    }

    private static Set<Integer> check(final Path f) throws Exception {
        final JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
        final Set<Integer> cps = new HashSet<>();
        for (final JsonElement e : root.getAsJsonArray("providers")) {
            final JsonObject p = e.getAsJsonObject();
            if (p.get("type").getAsString().equals("space")) {
                for (final String k : p.getAsJsonObject("advances").keySet()) {
                    cps.add(k.codePointAt(0));
                }
                continue;
            }
            assertEquals("bitmap", p.get("type").getAsString(), f.toString());
            final int height = p.has("height") ? p.get("height").getAsInt() : 8;
            final int ascent = p.get("ascent").getAsInt();
            final String file = p.get("file").getAsString();
            assertTrue(ascent <= height, f + ": " + file + " ascent " + ascent + " > height " + height);
            final BufferedImage img = ImageIO.read(ASSETS.resolve("textures").resolve(file.substring(file.indexOf(':') + 1)).toFile());
            final JsonArray chars = p.getAsJsonArray("chars");
            final int rows = chars.size();
            int cols = 0;
            for (final JsonElement r : chars) {
                final String row = r.getAsString();
                cols = Math.max(cols, row.codePointCount(0, row.length()));
                row.codePoints().forEach(cps::add);
            }
            assertEquals(0, img.getHeight() % rows, f + ": " + file);
            assertEquals(0, img.getWidth() % cols, f + ": " + file);
            assertTrue(img.getWidth() / cols <= 256 && img.getHeight() / rows <= 256,
                f + ": " + file + " клетка " + img.getWidth() / cols + "×" + img.getHeight() / rows + " больше 256");
        }
        return cps;
    }

    @Test
    void layoutCharsExistInTheirFonts() throws Exception {
        final Set<Integer> dialog = check(ASSETS.resolve("font/dialog.json"));
        HudLayout.BOX.codePoints().forEach(c -> assertTrue(dialog.contains(c), Integer.toHexString(c)));
        assertTrue(dialog.contains(0xF801) && dialog.contains(0xF821 + 9));
        for (final String g : new String[] {HudLayout.PANEL, HudLayout.NEXT, HudLayout.POINTER[0], HudLayout.POINTER[1],
            HudLayout.POINTER[2]}) {
            g.codePoints().forEach(c -> assertTrue(dialog.contains(c), Integer.toHexString(c)));
        }
        for (int i = 1; i <= 6; i++) {
            check(ASSETS.resolve("font/row" + i + ".json"));
        }
        final FontWidths w = HudTest.widths();
        final Set<Integer> row0 = check(ASSETS.resolve("font/row0.json"));
        for (final int cp : new int[] {'a', 'Ж', 'Й', 'Ё', ' '}) {
            assertTrue(w.known(cp) && row0.contains(cp), Integer.toHexString(cp));
        }
    }
}
