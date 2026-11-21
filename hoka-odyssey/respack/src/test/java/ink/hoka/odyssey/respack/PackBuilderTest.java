package ink.hoka.odyssey.respack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackBuilderTest {
    @TempDir
    Path tmp;

    static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void sameContentGivesSameSha1() throws Exception {
        PackSources s = new PackSources();
        s.putFile("a", "assets/a/x.txt", b("1"));
        s.putFile("a", "assets/a/y.txt", b("2"));
        PackBuilder.Built one = PackBuilder.build(s.snapshot(), null, "d", 75);
        Thread.sleep(1100);                                         // другое время — тот же zip
        PackBuilder.Built two = PackBuilder.build(s.snapshot(), null, "d", 75);
        assertArrayEquals(one.zip(), two.zip());
        assertEquals(one.info().sha1(), two.info().sha1());
        assertEquals(40, one.info().sha1().length());
        assertEquals(PackBuilder.sha1(one.zip()), one.info().sha1());
        s.putFile("a", "assets/a/y.txt", b("3"));
        assertNotEquals(one.info().sha1(), PackBuilder.build(s.snapshot(), null, "d", 75).info().sha1());
    }

    @Test
    void filesSortedAndMcmetaGenerated() throws Exception {
        PackSources s = new PackSources();
        s.putFile("m", "assets/z/b.txt", b("b"));
        s.putFile("m", "assets/a/a.txt", b("a"));
        PackBuilder.Built built = PackBuilder.build(s.snapshot(), null, "Привет \"мир\"", 75);
        Map<String, String> files = unzip(built.zip());
        assertEquals(List.of("assets/a/a.txt", "assets/z/b.txt", "pack.mcmeta"), List.copyOf(files.keySet()));
        String meta = files.get("pack.mcmeta");
        assertTrue(meta.contains("\"pack_format\": 75"), meta);
        assertTrue(meta.contains("\"min_format\": 75"), meta);
        assertTrue(meta.contains("Привет \\\"мир\\\""), meta);
        assertEquals(3, built.info().files());
    }

    @Test
    void firstOwnerWinsAndAdminOverrides() throws Exception {
        PackSources s = new PackSources();
        s.putFile("first", "assets/x/t.txt", b("first"));
        s.putFile("second", "assets/x/t.txt", b("second"));
        s.putFile("second", "pack.mcmeta", b("{}"));
        PackBuilder.Built built = PackBuilder.build(s.snapshot(), tmp.resolve("missing"), "d", 75);
        Map<String, String> files = unzip(built.zip());
        assertEquals("first", files.get("assets/x/t.txt"));
        assertTrue(files.get("pack.mcmeta").contains("pack_format"));
        assertEquals(2, built.warnings().size(), built.warnings().toString());

        Path admin = tmp.resolve("admin");
        Files.createDirectories(admin.resolve("assets/x"));
        Files.writeString(admin.resolve("assets/x/t.txt"), "admin");
        Files.writeString(admin.resolve("pack.mcmeta"), "{\"pack\":{}}");
        Files.writeString(admin.resolve(".hidden"), "no");
        Files.writeString(admin.resolve("assets/x/half.png.tmp"), "no");
        Files.writeString(admin.resolve("Bad Name.txt"), "no");
        files = unzip(PackBuilder.build(s.snapshot(), admin, "d", 75).zip());
        assertEquals("admin", files.get("assets/x/t.txt"));
        assertEquals("{\"pack\":{}}", files.get("pack.mcmeta"));
        assertNull(files.get(".hidden"));
        assertNull(files.get("assets/x/half.png.tmp"));
        assertNull(files.get("Bad Name.txt"));
    }

    @Test
    void directoriesAreReadAtBuildTime() throws Exception {
        Path dir = tmp.resolve("mod");
        Files.createDirectories(dir.resolve("assets/m/textures"));
        Files.writeString(dir.resolve("assets/m/textures/a.png"), "v1");
        PackSources s = new PackSources();
        s.putDirectory("m", dir);
        s.putDirectory("m", dir);                                   // повтор не дублирует
        assertEquals("v1", unzip(PackBuilder.build(s.snapshot(), null, "d", 75).zip()).get("assets/m/textures/a.png"));
        Files.writeString(dir.resolve("assets/m/textures/a.png"), "v2");
        assertEquals("v2", unzip(PackBuilder.build(s.snapshot(), null, "d", 75).zip()).get("assets/m/textures/a.png"));
        s.clear("m");
        assertEquals(List.of("pack.mcmeta"), List.copyOf(unzip(PackBuilder.build(s.snapshot(), null, "d", 75).zip()).keySet()));
    }

    @Test
    void badPathsRejected() {
        PackSources s = new PackSources();
        assertThrows(IllegalArgumentException.class, () -> s.putFile("m", "../evil", b("x")));
        assertThrows(IllegalArgumentException.class, () -> s.putFile("m", "/abs", b("x")));
        assertThrows(IllegalArgumentException.class, () -> s.putFile("m", "assets/A/Upper.png", b("x")));
        assertThrows(IllegalArgumentException.class, () -> s.putFile("m", "assets//x", b("x")));
        assertThrows(IllegalArgumentException.class, () -> s.putFile("", "assets/x", b("x")));
        s.putFile("m", "assets\\win\\path.txt", b("x"));
        assertNotNull(s.snapshot().get(0).files().get("assets/win/path.txt"));
        assertNull(PackSources.checkPath("pack.png"));
    }
}
