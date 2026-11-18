package core.amazake.systems.coremechanics.natives;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibrariesTest {

    private static final byte[] DATA = "jolt".getBytes(StandardCharsets.UTF_8);
    private static final String SHA = Libraries.sha256(DATA);

    @Test
    void parsesLines() {
        final List<Libraries.Lib> l = Libraries.parse("# комментарий\n\na.jar 4 " + SHA + " com/x/a/1/a-1.jar\n");
        assertEquals(1, l.size());
        assertEquals("a.jar", l.get(0).file());
        assertEquals(4, l.get(0).size());
        assertEquals("com/x/a/1/a-1.jar", l.get(0).mavenPath());
    }

    @Test
    void rejectsBadLines() {
        assertThrows(IllegalArgumentException.class, () -> Libraries.parse("../evil.jar 4 " + SHA + " x"));
        assertThrows(IllegalArgumentException.class, () -> Libraries.parse("a/b.jar 4 " + SHA + " x"));
        assertThrows(IllegalArgumentException.class, () -> Libraries.parse("a.jar 4 nothex x"));
        assertThrows(IllegalArgumentException.class, () -> Libraries.parse("a.txt 4 " + SHA + " x"));
        assertThrows(IllegalArgumentException.class, () -> Libraries.parse("# пусто\n"));
    }

    @Test
    void fingerprintDependsOnContent() {
        final List<Libraries.Lib> a = Libraries.parse("a.jar 4 " + SHA + " p\n");
        final List<Libraries.Lib> b = Libraries.parse("a.jar 4 " + SHA.replace(SHA.charAt(0), SHA.charAt(0) == 'a' ? 'b' : 'a') + " p\n");
        assertEquals(Libraries.fingerprint(a), Libraries.fingerprint(Libraries.parse("a.jar 4 " + SHA + " p\n")));
        assertNotEquals(Libraries.fingerprint(a), Libraries.fingerprint(b));
    }

    @Test
    void extractsBundledAndVerifies(@TempDir final Path dir) throws Exception {
        final List<Libraries.Lib> libs = Libraries.parse("a.jar 4 " + SHA + " p\n");
        assertFalse(Libraries.valid(dir, libs.get(0)));
        Libraries.ensure(dir, libs, f -> new ByteArrayInputStream(DATA), false, List.of(), m -> { });
        assertTrue(Libraries.valid(dir, libs.get(0)));
        assertTrue(Files.notExists(dir.resolve("a.jar.part")));
        // битый файл — перезаписывается из .azs
        Files.writeString(dir.resolve("a.jar"), "evil");
        assertFalse(Libraries.valid(dir, libs.get(0)));
        Libraries.ensure(dir, libs, f -> new ByteArrayInputStream(DATA), false, List.of(), m -> { });
        assertTrue(Libraries.valid(dir, libs.get(0)));
    }

    @Test
    void wrongBundledShaIsRejected(@TempDir final Path dir) {
        final List<Libraries.Lib> libs = Libraries.parse("a.jar 4 " + SHA + " p\n");
        final IOException e = assertThrows(IOException.class,
            () -> Libraries.ensure(dir, libs, f -> new ByteArrayInputStream("evil".getBytes(StandardCharsets.UTF_8)), false, List.of(), m -> { }));
        assertTrue(e.getMessage().contains("sha256") || e.getMessage().contains("автоскачивание"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("a.jar")));
    }

    @Test
    void noBundleNoDownloadFails(@TempDir final Path dir) {
        final List<Libraries.Lib> libs = Libraries.parse("a.jar 4 " + SHA + " p\n");
        assertThrows(IOException.class, () -> Libraries.ensure(dir, libs, f -> null, false, List.of(), m -> { }));
    }
}
