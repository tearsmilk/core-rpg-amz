package dev.tearsmilk.nameshader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderHooksTest {

    private static final Pattern IMPORT = Pattern.compile("#moj_import <([a-z_]+):([^>]+)>");
    private static final String SELF = "nameshader";

    @TempDir
    Path tmp;

    private static Path root() {
        return Path.of(System.getProperty(SELF + ".root", "."));
    }

    private static Map<String, byte[]> tree(Path base) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        ShaderHooks.loadTree(base, out);
        return out;
    }

    private static Map<String, byte[]> stubs() throws IOException {
        return tree(root().resolve("pack/stubs"));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> synthetic(String plugin) throws IOException {
        Map<String, byte[]> assets = tree(root().resolve("pack/assets"));
        Map<String, byte[]> stubs = stubs();
        Map<String, byte[]> own = new TreeMap<>();
        for (String c : ShaderHooks.canonicalPaths()) {
            own.put(c, assets.get(c));
        }
        int index = ShaderHooks.PLUGINS.indexOf(plugin);
        for (String program : ShaderHooks.PROGRAMS) {
            boolean real = index < 2 || (index == 3 && program.equals("text"));
            for (String stage : ShaderHooks.STAGES) {
                String h = ShaderHooks.hook(plugin, program, stage);
                String s = new String(stubs.get(h), StandardCharsets.UTF_8);
                own.put(h, bytes(real ? s.replace("#version 330\n", "#version 330\n\n#moj_import <" + plugin + ":lib.glsl>\n") : s));
            }
        }
        own.put(plugin + "/shaders/include/lib.glsl", bytes("#version 330\n\nfloat " + plugin + "_lib() {\n    return 1.0;\n}\n"));
        own.put(ShaderHooks.CORE_DIR + "only_" + plugin + ".vsh", bytes("#version 330\n\n#moj_import <" + plugin + ":lib.glsl>\n"));
        return own;
    }

    private static ShaderHooks.Report install(Path dir, String plugin, Set<String> present) throws IOException {
        return ShaderHooks.install(dir, plugin, synthetic(plugin), stubs(), present::contains);
    }

    private static List<List<String>> permutations(List<String> items) {
        if (items.size() <= 1) {
            return List.of(items);
        }
        List<List<String>> out = new ArrayList<>();
        for (String first : items) {
            List<String> rest = new ArrayList<>(items);
            rest.remove(first);
            for (List<String> tail : permutations(rest)) {
                List<String> p = new ArrayList<>();
                p.add(first);
                p.addAll(tail);
                out.add(p);
            }
        }
        return out;
    }

    private static List<String> missingImports(Path dir) throws IOException {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : tree(dir).entrySet()) {
            Matcher m = IMPORT.matcher(new String(e.getValue(), StandardCharsets.UTF_8));
            while (m.find()) {
                if (!m.group(1).equals("minecraft") && !Files.isRegularFile(dir.resolve(m.group(1) + "/shaders/include/" + m.group(2)))) {
                    missing.add(e.getKey() + " -> " + m.group(1) + ":" + m.group(2));
                }
            }
        }
        return missing;
    }

    private static void assertSameTree(Map<String, byte[]> a, Map<String, byte[]> b, String what) {
        assertEquals(a.keySet(), b.keySet(), what);
        for (String k : a.keySet()) {
            assertArrayEquals(a.get(k), b.get(k), what + " " + k);
        }
    }

    @Test
    void anySetOfPluginsInAnyOrderGivesTheSamePack() throws IOException {
        int run = 0;
        for (int mask = 1; mask < 16; mask++) {
            List<String> subset = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                if ((mask & (1 << i)) != 0) {
                    subset.add(ShaderHooks.PLUGINS.get(i));
                }
            }
            Map<String, byte[]> first = null;
            for (List<String> order : permutations(subset)) {
                Path dir = tmp.resolve("p" + run++);
                for (String p : order) {
                    install(dir, p, Set.copyOf(subset));
                }
                for (String p : order) {
                    assertEquals(List.of(), install(dir, p, Set.copyOf(subset)).written(), "second run " + order);
                }
                assertEquals(List.of(), missingImports(dir), order.toString());
                Map<String, byte[]> t = tree(dir);
                if (first == null) {
                    first = t;
                } else {
                    assertSameTree(first, t, order.toString());
                }
            }
        }
    }

    @Test
    void realHooksOnlyFromInstalledPluginsStubsForTheRest() throws IOException {
        Map<String, byte[]> stubs = stubs();
        for (int mask = 1; mask < 16; mask++) {
            Path dir = tmp.resolve("m" + mask);
            for (String p : ShaderHooks.PLUGINS) {
                install(dir, p, Set.copyOf(ShaderHooks.PLUGINS));
            }
            List<String> subset = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                if ((mask & (1 << i)) != 0) {
                    subset.add(ShaderHooks.PLUGINS.get(i));
                }
            }
            for (String p : subset) {
                install(dir, p, Set.copyOf(subset));
            }
            Path clean = tmp.resolve("c" + mask);
            for (String p : subset) {
                install(clean, p, Set.copyOf(subset));
            }
            for (String p : ShaderHooks.PLUGINS) {
                Map<String, byte[]> own = synthetic(p);
                for (String h : ShaderHooks.hooks(p)) {
                    byte[] want = subset.contains(p) ? own.get(h) : stubs.get(h);
                    assertArrayEquals(want, ShaderHooks.read(dir, h), subset + " " + h);
                }
            }
            assertEquals(List.of(), missingImports(dir), subset.toString());
            assertEquals(List.of(), missingImports(clean), subset.toString());
        }
    }

    @Test
    void foreignCoreFileIsNeverReplaced() throws IOException {
        byte[] foreign = bytes("#version 330\n\nvoid main() {\n}\n");
        Files.createDirectories(tmp.resolve(ShaderHooks.CORE_DIR));
        Files.write(tmp.resolve(ShaderHooks.core("gui", "vsh")), foreign);
        ShaderHooks.Report r = install(tmp, "tabshader", Set.of("tabshader"));
        assertArrayEquals(foreign, ShaderHooks.read(tmp, ShaderHooks.core("gui", "vsh")));
        assertNull(ShaderHooks.read(tmp, ShaderHooks.core("gui", "fsh")));
        assertTrue(r.foreign().contains(ShaderHooks.core("gui", "vsh")));
        assertTrue(r.summary().contains("ЧУЖИЕ"));
        assertTrue(r.written().contains(ShaderHooks.core("text", "vsh")));
    }

    @Test
    void newerSharedVersionIsKeptAndOlderOrUnmarkedIsReplaced() throws IOException {
        Map<String, byte[]> own = synthetic("textshader");
        String v = ShaderHooks.core("text", "vsh");
        String text = new String(own.get(v), StandardCharsets.UTF_8);
        byte[] newer = bytes(text.replace(ShaderHooks.MARK + ShaderHooks.VERSION, ShaderHooks.MARK + (ShaderHooks.VERSION + 1)));
        Files.createDirectories(tmp.resolve(ShaderHooks.CORE_DIR));
        Files.write(tmp.resolve(v), newer);
        ShaderHooks.Report r = install(tmp, "textshader", Set.of("textshader"));
        assertArrayEquals(newer, ShaderHooks.read(tmp, v));
        assertTrue(r.kept().contains(v));
        Path old = tmp.resolve("old");
        Files.createDirectories(old.resolve(ShaderHooks.CORE_DIR));
        Files.write(old.resolve(v), bytes("#version 330\n\n#moj_import <textshader:ts_vertex.glsl>\n"));
        install(old, "textshader", Set.of("textshader"));
        assertArrayEquals(own.get(v), ShaderHooks.read(old, v));
    }

    @Test
    void pluginWithoutRealSharedHooksWritesNoSharedFiles() throws IOException {
        install(tmp, "nameshader", Set.of("nameshader"));
        for (String c : ShaderHooks.canonicalPaths()) {
            assertFalse(Files.exists(tmp.resolve(c)), c);
        }
        for (String p : ShaderHooks.PLUGINS) {
            if (!p.equals("nameshader")) {
                for (String h : ShaderHooks.hooks(p)) {
                    assertFalse(Files.exists(tmp.resolve(h)), h);
                }
            }
        }
    }

    @Test
    void ownPackInstallsAloneAndResolvesEveryImport() throws IOException {
        Map<String, byte[]> own = tree(root().resolve("pack/assets"));
        Map<String, byte[]> stubs = stubs();
        for (String p : ShaderHooks.PLUGINS) {
            for (String h : ShaderHooks.hooks(p)) {
                assertTrue(stubs.containsKey(h), h);
            }
        }
        for (String h : ShaderHooks.hooks(SELF)) {
            assertTrue(own.containsKey(h), h);
        }
        ShaderHooks.Report r = ShaderHooks.install(tmp, SELF, own, stubs, Set.of(SELF)::contains);
        assertTrue(r.foreign().isEmpty());
        assertEquals(List.of(), missingImports(tmp));
        assertEquals(List.of(), ShaderHooks.install(tmp, SELF, own, stubs, Set.of(SELF)::contains).written());
        for (String c : ShaderHooks.canonicalPaths()) {
            String text = new String(own.get(c), StandardCharsets.UTF_8);
            assertTrue(text.contains(ShaderHooks.MARK + ShaderHooks.VERSION), c);
            for (String p : ShaderHooks.PLUGINS) {
                assertTrue(text.contains("#moj_import <" + p + ":"), c + " " + p);
            }
        }
    }
}
