package dev.tearsmilk.chatshader;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record Settings(Position position, int margin, boolean positionValid, boolean packInstall, String packDir,
                       boolean reloadNexo, List<String> testLines) {

    public static final int MAX_MARGIN = 48;
    public static final String CONFIG_PATH = "chatshader/shaders/include/cs_config.glsl";
    private static final Pattern GLSL = Pattern.compile("#define CS_POSITION (\\d+)\\s+#define CS_MARGIN (\\d+)\\.0");

    public record Shader(Position position, int margin) {

        public boolean moves() {
            return position != Position.BOTTOM_LEFT || margin != 0;
        }
    }

    public static Settings from(ConfigurationSection c) {
        String raw = c.getString("chat.position", "bottom-left");
        Position p = Position.parse(raw);
        int margin = Math.max(0, Math.min(MAX_MARGIN, c.getInt("chat.margin", 0)));
        List<String> lines = new ArrayList<>(c.getStringList("test.lines"));
        return new Settings(p == null ? Position.BOTTOM_LEFT : p, margin, p != null, c.getBoolean("pack.install", true),
                c.getString("pack.dir", ""), c.getBoolean("pack.reload-nexo", true), List.copyOf(lines));
    }

    public boolean moves() {
        return position != Position.BOTTOM_LEFT || margin != 0;
    }

    public static String glsl(Position position, int margin) {
        return "#version 330\n\n#define CS_POSITION " + position.code() + "\n#define CS_MARGIN " + margin + ".0\n";
    }

    public static Shader parseGlsl(String text) {
        Matcher m = GLSL.matcher(text);
        if (!m.find()) {
            return null;
        }
        int code = Integer.parseInt(m.group(1));
        for (Position p : Position.values()) {
            if (p.code() == code) {
                return new Shader(p, Integer.parseInt(m.group(2)));
            }
        }
        return null;
    }

    public static boolean overlaps(Position chat, String board) {
        boolean chatRight = chat == Position.TOP_RIGHT || chat == Position.BOTTOM_RIGHT;
        boolean chatTop = chat == Position.TOP_LEFT || chat == Position.TOP_RIGHT;
        boolean boardRight = board.endsWith("right");
        boolean boardLeft = board.endsWith("left");
        if (chatRight ? !boardRight : !boardLeft) {
            return false;
        }
        if (board.equals("right") || board.equals("left")) {
            return true;
        }
        return board.startsWith("top") == chatTop;
    }
}
