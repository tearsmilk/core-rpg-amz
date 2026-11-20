package dev.tearsmilk.chatshader;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRulesTest {

    private static final String SLASH = "/";

    private static Path root() {
        return Path.of(System.getProperty("chatshader.root", "."));
    }

    private static Path assets() {
        return root().resolve("pack/assets");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void everyMessageKeyUsedInCodeExists() throws IOException {
        String config = read(root().resolve("src/main/resources/config.yml"));
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(new StringReader(config));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Pattern use = Pattern.compile("(?:send|get|raw)\\(\\s*(?:[a-zA-Z]+,\\s*)?\"([a-z0-9-]+)\"\\s*[,)]");
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root().resolve("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = use.matcher(read(p));
                while (m.find()) {
                    if (!y.isString("messages." + m.group(1))) {
                        missing.add(p.getFileName() + ": " + m.group(1));
                    }
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void noCommentsAndNoLongDashes() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root())) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                String rel = root().relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("build/") || rel.startsWith(".gradle/") || rel.startsWith("gradle/")
                        || rel.startsWith("tools/.cache/") || rel.startsWith("gradlew") || rel.contains("__pycache__")
                        || rel.endsWith(".png") || rel.endsWith(".jar") || rel.endsWith(".zip")) {
                    continue;
                }
                String s = read(p);
                if (s.indexOf((char) 0x2014) >= 0 || s.indexOf((char) 0x2013) >= 0) {
                    bad.add("dash: " + rel);
                }
                boolean code = rel.endsWith(".java") || rel.endsWith(".glsl") || rel.endsWith(".vsh") || rel.endsWith(".fsh");
                if (code && (s.contains(SLASH + SLASH) || s.contains(SLASH + "*"))) {
                    bad.add("comment: " + rel);
                }
            }
        }
        assertEquals(List.of(), bad);
    }
}
