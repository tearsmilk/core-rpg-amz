package dev.tearsmilk.nameshader;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class ShaderHooks {

    static final int VERSION = 1;
    static final String MARK = "#define SHADERHOOKS_VERSION ";
    static final List<String> PLUGINS = List.of("textshader", "tabshader", "nameshader", "chatshader");
    static final List<String> PREFIXES = List.of("ts", "th", "ns", "cs");
    static final List<String> PROGRAMS = List.of("gui", "sprite", "text");
    static final List<String> CORES = List.of("gui", "position_tex_color", "rendertype_text");
    static final List<String> STAGES = List.of("vertex", "fragment");
    static final String CORE_DIR = "minecraft/shaders/core/";
    static final String JAR_ASSETS = "shaderpack/assets/";
    static final String JAR_STUBS = "shaderpack/stubs/";
    private static final Pattern VERSION_LINE = Pattern.compile(Pattern.quote(MARK) + "(\\d+)");
    private static final int ABSENT = 0;
    private static final int OURS = 1;
    private static final int FOREIGN = 2;

    private ShaderHooks() {
    }

    record Report(Path dir, List<String> written, List<String> foreign, List<String> kept, String skipped) {

        boolean changed() {
            return !written.isEmpty();
        }

        String summary() {
            if (skipped != null) {
                return skipped;
            }
            StringBuilder b = new StringBuilder();
            b.append(written.isEmpty() ? "все файлы на месте" : "записано файлов: " + written.size());
            if (!foreign.isEmpty()) {
                b.append(", ЧУЖИЕ файлы не заменены: ").append(String.join(", ", foreign));
            }
            if (!kept.isEmpty()) {
                b.append(", оставлена более новая версия: ").append(String.join(", ", kept));
            }
            return b.append(" (").append(dir).append(")").toString();
        }
    }

    static String hook(String plugin, String program, String stage) {
        String prefix = PREFIXES.get(PLUGINS.indexOf(plugin));
        return plugin + "/shaders/include/" + prefix + "_hook_" + program + "_" + stage + ".glsl";
    }

    static List<String> hooks(String plugin) {
        List<String> out = new ArrayList<>();
        for (String program : PROGRAMS) {
            for (String stage : STAGES) {
                out.add(hook(plugin, program, stage));
            }
        }
        return out;
    }

    static String core(String program, String ext) {
        return CORE_DIR + CORES.get(PROGRAMS.indexOf(program)) + "." + ext;
    }

    static List<String> canonicalPaths() {
        List<String> out = new ArrayList<>();
        for (String program : PROGRAMS) {
            out.add(core(program, "vsh"));
            out.add(core(program, "fsh"));
        }
        return out;
    }

    static int[] classify(byte[] data) {
        if (data == null) {
            return new int[]{ABSENT, 0};
        }
        String text = new String(data, StandardCharsets.UTF_8);
        Matcher m = VERSION_LINE.matcher(text);
        if (m.find()) {
            return new int[]{OURS, Integer.parseInt(m.group(1))};
        }
        for (String p : PLUGINS) {
            if (text.contains("<" + p + ":")) {
                return new int[]{OURS, 0};
            }
        }
        return new int[]{FOREIGN, 0};
    }

    static byte[] read(Path dir, String rel) throws IOException {
        Path p = dir.resolve(rel);
        return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
    }

    static void put(Path dir, String rel, byte[] data, List<String> written) throws IOException {
        Path target = dir.resolve(rel);
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), data)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".shaderhooks.tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        written.add(rel);
    }

    static boolean real(String plugin, String program, Map<String, byte[]> own, Map<String, byte[]> stubs) {
        for (String stage : STAGES) {
            String h = hook(plugin, program, stage);
            byte[] mine = own.get(h);
            if (mine != null && !Arrays.equals(mine, stubs.get(h))) {
                return true;
            }
        }
        return false;
    }

    static boolean canonicalPresent(Path dir) throws IOException {
        for (String rel : canonicalPaths()) {
            byte[] data = read(dir, rel);
            if (data != null && new String(data, StandardCharsets.UTF_8).contains(MARK)) {
                return true;
            }
        }
        return false;
    }

    static Report install(Path dir, String self, Map<String, byte[]> own, Map<String, byte[]> stubs,
                          Predicate<String> present) throws IOException {
        if (!PLUGINS.contains(self)) {
            throw new IllegalArgumentException("unknown plugin " + self);
        }
        List<String> written = new ArrayList<>();
        List<String> foreign = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        Set<String> handled = new LinkedHashSet<>();
        for (String program : PROGRAMS) {
            List<String> files = List.of(core(program, "vsh"), core(program, "fsh"));
            if (!own.containsKey(files.get(0)) || !own.containsKey(files.get(1))) {
                continue;
            }
            handled.addAll(files);
            if (!real(self, program, own, stubs)) {
                continue;
            }
            boolean bad = false;
            boolean newer = false;
            for (String f : files) {
                int[] s = classify(read(dir, f));
                if (s[0] == FOREIGN) {
                    foreign.add(f);
                    bad = true;
                }
                if (s[0] == OURS && s[1] > VERSION) {
                    newer = true;
                }
            }
            if (bad) {
                continue;
            }
            if (newer) {
                kept.addAll(files);
                continue;
            }
            for (String f : files) {
                put(dir, f, own.get(f), written);
            }
        }
        for (Map.Entry<String, byte[]> e : new TreeMap<>(own).entrySet()) {
            String rel = e.getKey();
            if (handled.contains(rel)) {
                continue;
            }
            if (rel.startsWith(CORE_DIR) && classify(read(dir, rel))[0] == FOREIGN) {
                foreign.add(rel);
                continue;
            }
            put(dir, rel, e.getValue(), written);
        }
        if (canonicalPresent(dir)) {
            for (String other : PLUGINS) {
                if (other.equals(self)) {
                    continue;
                }
                List<String> hs = hooks(other);
                boolean missing = false;
                for (String h : hs) {
                    missing |= read(dir, h) == null;
                }
                if (!present.test(other) || missing) {
                    for (String h : hs) {
                        byte[] s = stubs.get(h);
                        if (s == null) {
                            throw new IOException("no stub " + h);
                        }
                        put(dir, h, s, written);
                    }
                }
            }
        }
        return new Report(dir, written, foreign, kept, null);
    }

    static void loadTree(Path base, Map<String, byte[]> into) throws IOException {
        if (!Files.isDirectory(base)) {
            return;
        }
        try (Stream<Path> s = Files.walk(base)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                into.put(base.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
    }

    static void loadJar(Path jar, Map<String, byte[]> own, Map<String, byte[]> stubs) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName();
                Map<String, byte[]> into = name.startsWith(JAR_ASSETS) ? own : name.startsWith(JAR_STUBS) ? stubs : null;
                if (into == null) {
                    continue;
                }
                try (InputStream in = jf.getInputStream(e)) {
                    into.put(name.substring(name.startsWith(JAR_ASSETS) ? JAR_ASSETS.length() : JAR_STUBS.length()), in.readAllBytes());
                }
            }
        }
    }

    static Report run(JavaPlugin plugin, boolean enabled, String dirSetting, boolean reloadNexo) {
        Logger log = plugin.getLogger();
        if (!enabled) {
            log.info("Шейдеры в пак Nexo не ставятся: pack.install: false.");
            return new Report(null, List.of(), List.of(), List.of(), "выключено (pack.install: false)");
        }
        boolean nexoDefault = dirSetting == null || dirSetting.isBlank();
        Path dir = nexoDefault
                ? plugin.getDataFolder().toPath().toAbsolutePath().getParent().resolve("Nexo").resolve("pack").resolve("assets")
                : Path.of(dirSetting).toAbsolutePath();
        if (nexoDefault && !Files.isDirectory(dir.getParent())) {
            log.warning("Нет папки " + dir.getParent() + ": шейдеры не установлены. Поставьте Nexo или укажите pack.dir.");
            return new Report(dir, List.of(), List.of(), List.of(), "нет папки пака Nexo: " + dir.getParent());
        }
        try {
            Map<String, byte[]> own = new TreeMap<>();
            Map<String, byte[]> stubs = new TreeMap<>();
            Path jar = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            loadJar(jar, own, stubs);
            Report r = install(dir, plugin.getName(), own, stubs, n -> Bukkit.getPluginManager().getPlugin(n) != null);
            for (String f : r.foreign()) {
                log.severe("Чужой шейдер " + dir.resolve(f) + " не заменен: хуки " + plugin.getName()
                        + " в нем не работают. Объедините его с нашим вручную или уберите.");
            }
            for (String f : r.kept()) {
                log.warning("В паке более новая версия общего шейдера " + f + " - оставлена, обновите " + plugin.getName() + ".");
            }
            if (r.changed()) {
                log.info("Шейдеры в " + dir + ": записано файлов " + r.written().size() + ": "
                        + String.join(", ", r.written().subList(0, Math.min(8, r.written().size())))
                        + (r.written().size() > 8 ? " ..." : ""));
            } else {
                log.info("Шейдеры в " + dir + ": все файлы на месте.");
            }
            if (r.changed() && reloadNexo && Bukkit.getCurrentTick() > 0 && Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "nexo reload pack"));
                log.info("Пак изменился: nexo reload pack.");
            }
            return r;
        } catch (Exception e) {
            log.warning("Не удалось поставить шейдеры в " + dir + ": " + e);
            return new Report(dir, List.of(), List.of(), List.of(), "ошибка: " + e.getMessage());
        }
    }
}
