package dev.tearsmilk.chatshader;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    private static Path root() {
        return Path.of(System.getProperty("chatshader.root", "."));
    }

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(new StringReader(text));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return y;
    }

    @Test
    void defaultsAreBottomLeftWithoutMoves() throws IOException {
        Settings s = Settings.from(yaml(Files.readString(root().resolve("src/main/resources/config.yml"), StandardCharsets.UTF_8)));
        assertEquals(Position.BOTTOM_LEFT, s.position());
        assertEquals(0, s.margin());
        assertTrue(s.positionValid());
        assertFalse(s.moves());
        assertTrue(s.packInstall());
        assertTrue(s.testLines().size() >= 5);
        String pack = Files.readString(root().resolve("pack/assets/" + Settings.CONFIG_PATH), StandardCharsets.UTF_8);
        assertEquals(Settings.glsl(Position.BOTTOM_LEFT, 0), pack);
    }

    @Test
    void positionsParseAndMarginIsClamped() {
        assertEquals(Position.TOP_RIGHT, Position.parse(" Top_Right "));
        assertNull(Position.parse("middle"));
        Settings s = Settings.from(yaml("chat:\n  position: top-left\n  margin: 500\n"));
        assertEquals(Settings.MAX_MARGIN, s.margin());
        assertTrue(s.moves());
        Settings bad = Settings.from(yaml("chat:\n  position: middle\n  margin: -4\n"));
        assertEquals(Position.BOTTOM_LEFT, bad.position());
        assertFalse(bad.positionValid());
        assertEquals(0, bad.margin());
        assertEquals("#version 330\n\n#define CS_POSITION 2\n#define CS_MARGIN 6.0\n", Settings.glsl(Position.TOP_RIGHT, 6));
    }

    @Test
    void shaderUsesTheSameCodesAndMark() throws IOException {
        Path inc = root().resolve("pack/assets/chatshader/shaders/include");
        String layout = Files.readString(inc.resolve("cs_layout.glsl"), StandardCharsets.UTF_8);
        assertTrue(layout.contains("(p == 2 || p == 3)"));
        assertEquals(2, Position.TOP_RIGHT.code());
        assertEquals(3, Position.BOTTOM_RIGHT.code());
        assertTrue(layout.contains("(p == 1 || p == 2)"));
        assertEquals(1, Position.TOP_LEFT.code());
        String text = Files.readString(inc.resolve("cs_text_vertex.glsl"), StandardCharsets.UTF_8);
        assertTrue(text.contains("#define CS_MARK ivec3(" + ChatMark.RESIDUE[0] + ", " + ChatMark.RESIDUE[1] + ", " + ChatMark.RESIDUE[2] + ")"));
        assertTrue(text.contains("#define CS_FX_TEXT_R " + ChatMark.FX_TEXT_R));
        assertTrue(text.contains("#define CS_FX_SHADOW_R " + ChatMark.FX_SHADOW_R));
        assertTrue(text.contains("#define CS_TS_TEXT_R " + ChatMark.TEXTSHADER_R));
        assertTrue(text.contains("#define CS_TS_SHADOW_R " + ChatMark.TEXTSHADER_SHADOW_R));
        assertTrue(text.contains("(c & ivec3(" + (ChatMark.STEP - 1) + ")) == CS_MARK"));
    }

    @Test
    void packConfigParsesBackAndBoardOverlapIsDetected() {
        for (Position p : Position.values()) {
            for (int m : new int[]{0, 4, 48}) {
                assertEquals(new Settings.Shader(p, m), Settings.parseGlsl(Settings.glsl(p, m)));
            }
        }
        assertNull(Settings.parseGlsl("#version 330\n"));
        assertTrue(Settings.overlaps(Position.TOP_RIGHT, "right"));
        assertTrue(Settings.overlaps(Position.BOTTOM_RIGHT, "right"));
        assertTrue(Settings.overlaps(Position.TOP_RIGHT, "top-right"));
        assertFalse(Settings.overlaps(Position.BOTTOM_RIGHT, "top-right"));
        assertFalse(Settings.overlaps(Position.BOTTOM_LEFT, "right"));
        assertTrue(Settings.overlaps(Position.BOTTOM_LEFT, "left"));
        assertTrue(Settings.overlaps(Position.BOTTOM_LEFT, "bottom-left"));
        assertFalse(Settings.overlaps(Position.TOP_LEFT, "bottom-left"));
    }
}
