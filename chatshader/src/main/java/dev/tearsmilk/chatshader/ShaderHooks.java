package dev.tearsmilk.chatshader;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class ShaderHooks {

    static final int VERSION = 2;
    static final String MARK = "#define SHADERHOOKS_VERSION ";
    static final String HASH = "#define SHADERHOOKS_HASH h";
    static final String LOCK = "dev.tearsmilk.shaderhooks.install";
    static final String BACKUP_DIR = "pack-backup";
    static final List<String> PLUGINS = List.of("textshader", "tabshader", "nameshader", "chatshader", "aurahud");
    static final List<String> PREFIXES = List.of("ts", "th", "ns", "cs", "ah");
    static final List<String> PROGRAMS = List.of("gui", "sprite", "text");
    static final List<String> CORES = List.of("gui", "position_tex_color", "rendertype_text");
    static final List<String> STAGES = List.of("vertex", "fragment");
    static final String CORE_DIR = "minecraft/shaders/core/";
    static final String JAR_ASSETS = "shaderpack/assets/";
    static final String JAR_STUBS = "shaderpack/stubs/";
    private static final Pattern VERSION_LINE = Pattern.compile(Pattern.quote(MARK) + "(\\d+)");
    private static final Pattern HASH_LINE = Pattern.compile("(?m)^" + Pattern.quote(HASH) + "([0-9a-f]+)\n");
    private static final int ABSENT = 0;
    private static final int OURS = 1;
    private static final int FOREIGN = 2;

    private ShaderHooks() {
    }

    record Report(Path dir, List<String> written, List<String> foreign, List<String> kept, List<String> conflict,
                  List<String> backups, Path backupDir, String skipped) {

        static Report skipped(Path dir, String why) {
            return new Report(dir, List.of(), List.of(), List.of(), List.of(), List.of(), null, why);
        }

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
            if (!conflict.isEmpty()) {
                b.append(", КОНФЛИКТ сборок одной версии, оставлено как есть: ").append(String.join(", ", conflict));
            }
            if (!backups.isEmpty()) {
                b.append(", старые файлы сохранены в ").append(backupDir);
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

    static String contentHash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(HASH_LINE.matcher(text).replaceAll("").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String declaredHash(String text) {
        Matcher m = HASH_LINE.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    static String intactHash(byte[] data) {
        if (data == null) {
            return null;
        }
        String text = new String(data, StandardCharsets.UTF_8);
        String h = declaredHash(text);
        return h != null && h.equals(contentHash(text)) ? h : null;
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

    static final class Writer {

        final Path dir;
        final Path work;
        final String self;
        final Collection<byte[]> stubs;
        final List<String> written = new ArrayList<>();
        final List<String> backups = new ArrayList<>();
        Path backupDir;

        Writer(Path dir, Path work, String self, Collection<byte[]> stubs) {
            this.dir = dir;
            this.work = work;
            this.self = self;
            this.stubs = stubs;
        }

        boolean worthBackup(String rel, byte[] old) {
            boolean shared = rel.startsWith(CORE_DIR) || (rel.contains("_hook_") && !rel.startsWith(self + "/"));
            return shared && !isStub(old);
        }

        boolean isStub(byte[] data) {
            for (byte[] s : stubs) {
                if (Arrays.equals(s, data)) {
                    return true;
                }
            }
            return false;
        }

        void put(String rel, byte[] data) throws IOException {
            Path target = dir.resolve(rel);
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".shaderhooks.tmp"));
            byte[] old = Files.isRegularFile(target) ? Files.readAllBytes(target) : null;
            if (old != null && Arrays.equals(old, data)) {
                return;
            }
            if (old != null && work != null && worthBackup(rel, old)) {
                if (backupDir == null) {
                    backupDir = work.resolve(BACKUP_DIR).resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
                }
                Path b = backupDir.resolve(rel);
                Files.createDirectories(b.getParent());
                Files.write(b, old);
                backups.add(rel);
            }
            Files.createDirectories(target.getParent());
            Path tmpDir = work == null ? target.getParent() : work;
            Files.createDirectories(tmpDir);
            Path tmp = tmpDir.resolve(target.getFileName() + ".shaderhooks.tmp");
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            written.add(rel);
        }
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
        return install(dir, self, own, stubs, present, null);
    }

    static Report install(Path dir, String self, Map<String, byte[]> own, Map<String, byte[]> stubs,
                          Predicate<String> present, Path work) throws IOException {
        if (!PLUGINS.contains(self)) {
            throw new IllegalArgumentException("unknown plugin " + self);
        }
        synchronized (LOCK) {
            return installLocked(dir, self, own, stubs, present, work);
        }
    }

    private static Report installLocked(Path dir, String self, Map<String, byte[]> own, Map<String, byte[]> stubs,
                                        Predicate<String> present, Path work) throws IOException {
        Writer out = new Writer(dir, work, self, stubs.values());
        List<String> foreign = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        List<String> conflict = new ArrayList<>();
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
            List<String> clash = new ArrayList<>();
            for (String f : files) {
                byte[] existing = read(dir, f);
                int[] s = classify(existing);
                if (s[0] == FOREIGN) {
                    foreign.add(f);
                    bad = true;
                }
                if (s[0] == OURS && s[1] > VERSION) {
                    newer = true;
                }
                String theirs = intactHash(existing);
                if (s[0] == OURS && s[1] == VERSION && theirs != null
                        && !theirs.equals(declaredHash(new String(own.get(f), StandardCharsets.UTF_8)))) {
                    clash.add(f);
                }
            }
            if (bad) {
                continue;
            }
            if (newer) {
                kept.addAll(files);
                continue;
            }
            if (!clash.isEmpty()) {
                conflict.addAll(clash);
                continue;
            }
            for (String f : files) {
                out.put(f, own.get(f));
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
            out.put(rel, e.getValue());
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
                        out.put(h, s);
                    }
                }
            }
        }
        return new Report(dir, List.copyOf(out.written), foreign, kept, conflict, List.copyOf(out.backups), out.backupDir, null);
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

    record Job(Path dir, Path work, Map<String, byte[]> own, Map<String, byte[]> stubs, Set<String> present, String self) {
    }

    static Report run(JavaPlugin plugin, boolean enabled, String dirSetting, boolean reloadNexo) {
        return run(plugin, enabled, dirSetting, reloadNexo, Map.of());
    }

    static Report run(JavaPlugin plugin, boolean enabled, String dirSetting, boolean reloadNexo, Map<String, byte[]> generated) {
        Object prepared = prepare(plugin, enabled, dirSetting, generated);
        if (prepared instanceof Report r) {
            return r;
        }
        Job job = (Job) prepared;
        return finish(plugin, job, execute(job), reloadNexo);
    }

    static void runAsync(JavaPlugin plugin, boolean enabled, String dirSetting, boolean reloadNexo, Map<String, byte[]> generated,
                         Consumer<Report> done) {
        Object prepared = prepare(plugin, enabled, dirSetting, generated);
        if (prepared instanceof Report r) {
            done.accept(r);
            return;
        }
        Job job = (Job) prepared;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Object result = execute(job);
            Bukkit.getScheduler().runTask(plugin, () -> done.accept(finish(plugin, job, result, reloadNexo)));
        });
    }

    private static Object prepare(JavaPlugin plugin, boolean enabled, String dirSetting, Map<String, byte[]> generated) {
        Logger log = plugin.getLogger();
        if (!enabled) {
            log.info("Шейдеры в пак Nexo не ставятся: pack.install: false.");
            return Report.skipped(null, "выключено (pack.install: false)");
        }
        boolean nexoDefault = dirSetting == null || dirSetting.isBlank();
        Path dir = nexoDefault
                ? plugin.getDataFolder().toPath().toAbsolutePath().getParent().resolve("Nexo").resolve("pack").resolve("assets")
                : Path.of(dirSetting).toAbsolutePath();
        if (nexoDefault && !Files.isDirectory(dir.getParent())) {
            log.warning("Нет папки " + dir.getParent() + ": шейдеры не установлены. Поставьте Nexo или укажите pack.dir.");
            return Report.skipped(dir, "нет папки пака Nexo: " + dir.getParent());
        }
        try {
            Map<String, byte[]> own = new TreeMap<>();
            Map<String, byte[]> stubs = new TreeMap<>();
            Path jar = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            loadJar(jar, own, stubs);
            own.putAll(generated);
            Set<String> present = new LinkedHashSet<>();
            for (String p : PLUGINS) {
                if (Bukkit.getPluginManager().getPlugin(p) != null) {
                    present.add(p);
                }
            }
            Path work = plugin.getDataFolder().toPath().toAbsolutePath();
            return new Job(dir, work, own, stubs, Set.copyOf(present), plugin.getName().toLowerCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            log.warning("Не удалось прочитать шейдеры из jar " + plugin.getName() + ": " + e);
            return Report.skipped(dir, "ошибка: " + e.getMessage());
        }
    }

    private static Object execute(Job job) {
        try {
            return install(job.dir(), job.self(), job.own(), job.stubs(), job.present()::contains, job.work());
        } catch (Exception e) {
            return e;
        }
    }

    private static Report finish(JavaPlugin plugin, Job job, Object result, boolean reloadNexo) {
        Logger log = plugin.getLogger();
        Path dir = job.dir();
        if (result instanceof Exception e) {
            log.severe("Не удалось поставить шейдеры в " + dir + ": " + e);
            return Report.skipped(dir, "ошибка: " + e.getMessage());
        }
        Report r = (Report) result;
        for (String f : r.foreign()) {
            log.severe("Чужой шейдер " + dir.resolve(f) + " не заменен: хуки " + plugin.getName()
                    + " в нем не работают. Объедините его с нашим вручную или уберите.");
        }
        for (String f : r.kept()) {
            log.warning("В паке более новая версия общего шейдера " + f + " - оставлена, обновите " + plugin.getName() + ".");
        }
        for (String f : r.conflict()) {
            log.severe("Общий шейдер " + f + " той же версии " + VERSION + ", но другой сборки - оставлен как есть. "
                    + "Обновите textshader, tabshader, nameshader, chatshader и aurahud до одной сборки.");
        }
        if (!r.backups().isEmpty()) {
            log.warning("Замененные файлы пака сохранены в " + r.backupDir() + ": " + String.join(", ",
                    r.backups().subList(0, Math.min(8, r.backups().size()))) + (r.backups().size() > 8 ? " ..." : ""));
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
    }
}
