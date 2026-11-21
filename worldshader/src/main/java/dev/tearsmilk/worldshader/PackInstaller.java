package dev.tearsmilk.worldshader;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PackInstaller {

    static final int VERSION = 1;
    static final String MARK = "#define WORLDSHADER_VERSION ";
    static final String HASH = "#define WORLDSHADER_HASH h";
    static final String LOCK = "dev.tearsmilk.shaderhooks.install";
    static final String BACKUP_DIR = "pack-backup";
    static final String SHARED = "minecraft/";
    static final String JAR_ASSETS = "shaderpack/assets/";
    static final String OWN_REF = Zones.NAMESPACE + ":";
    static final List<String> PROGRAM = List.of("minecraft/shaders/core/rendertype_outline.vsh",
            "minecraft/shaders/core/rendertype_outline.fsh");
    static final String CHAIN = "minecraft/post_effect/entity_outline.json";
    static final List<String> GROUP = List.of(PROGRAM.get(0), PROGRAM.get(1), CHAIN);
    private static final Pattern VERSION_LINE = Pattern.compile(Pattern.quote(MARK) + "(\\d+)");
    private static final Pattern HASH_LINE = Pattern.compile("(?m)^" + Pattern.quote(HASH) + "([0-9a-f]+)\n");
    static final int ABSENT = 0;
    static final int OURS = 1;
    static final int FOREIGN = 2;

    private PackInstaller() {
    }

    record Report(Path dir, List<String> written, List<String> foreign, List<String> kept, List<String> conflict,
                  List<String> backups, Path backupDir, String skipped) {

        static Report skipped(Path dir, String why) {
            return new Report(dir, List.of(), List.of(), List.of(), List.of(), List.of(), null, why);
        }

        boolean changed() {
            return !written.isEmpty();
        }

        boolean active() {
            return skipped == null && foreign.isEmpty() && kept.isEmpty() && conflict.isEmpty();
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

    static int[] classify(String rel, byte[] data) {
        if (data == null) {
            return new int[]{ABSENT, 0};
        }
        String text = new String(data, StandardCharsets.UTF_8);
        Matcher m = VERSION_LINE.matcher(text);
        if (m.find()) {
            return new int[]{OURS, Integer.parseInt(m.group(1))};
        }
        if (rel.endsWith(".json") && text.contains(OWN_REF)) {
            return new int[]{OURS, 0};
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
        final List<String> written = new ArrayList<>();
        final List<String> backups = new ArrayList<>();
        Path backupDir;

        Writer(Path dir, Path work) {
            this.dir = dir;
            this.work = work;
        }

        void put(String rel, byte[] data) throws IOException {
            Path target = dir.resolve(rel);
            byte[] old = Files.isRegularFile(target) ? Files.readAllBytes(target) : null;
            if (old != null && Arrays.equals(old, data)) {
                return;
            }
            if (old != null && work != null && rel.startsWith(SHARED)) {
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

    static Report install(Path dir, Map<String, byte[]> own, Path work) throws IOException {
        synchronized (LOCK) {
            return installLocked(dir, own, work);
        }
    }

    private static Report installLocked(Path dir, Map<String, byte[]> own, Path work) throws IOException {
        Writer out = new Writer(dir, work);
        List<String> foreign = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        List<String> conflict = new ArrayList<>();
        boolean bad = false;
        boolean newer = false;
        List<String> clash = new ArrayList<>();
        for (String f : GROUP) {
            if (!own.containsKey(f)) {
                throw new IOException("нет " + f + " в jar");
            }
            byte[] existing = read(dir, f);
            int[] s = classify(f, existing);
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
        if (!bad && newer) {
            kept.addAll(GROUP);
        } else if (!bad && !clash.isEmpty()) {
            conflict.addAll(clash);
        } else if (!bad) {
            for (String f : GROUP) {
                out.put(f, own.get(f));
            }
        }
        for (Map.Entry<String, byte[]> e : new TreeMap<>(own).entrySet()) {
            String rel = e.getKey();
            if (GROUP.contains(rel)) {
                continue;
            }
            if (rel.startsWith(SHARED) && classify(rel, read(dir, rel))[0] == FOREIGN) {
                foreign.add(rel);
                continue;
            }
            out.put(rel, e.getValue());
        }
        return new Report(dir, List.copyOf(out.written), List.copyOf(foreign), List.copyOf(kept), List.copyOf(conflict),
                List.copyOf(out.backups), out.backupDir, null);
    }

    static void loadJar(Path jar, Map<String, byte[]> own) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory() || !e.getName().startsWith(JAR_ASSETS)) {
                    continue;
                }
                try (InputStream in = jf.getInputStream(e)) {
                    own.put(e.getName().substring(JAR_ASSETS.length()), in.readAllBytes());
                }
            }
        }
    }

    record Job(Path dir, Path work, Map<String, byte[]> own) {
    }

    static Report run(JavaPlugin plugin, Settings s) {
        Object prepared = prepare(plugin, s);
        if (prepared instanceof Report r) {
            return r;
        }
        Job job = (Job) prepared;
        return finish(plugin, job, execute(job), s.reloadNexo());
    }

    static void runAsync(JavaPlugin plugin, Settings s, Consumer<Report> done) {
        Object prepared = prepare(plugin, s);
        if (prepared instanceof Report r) {
            done.accept(r);
            return;
        }
        Job job = (Job) prepared;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Object result = execute(job);
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> done.accept(finish(plugin, job, result, s.reloadNexo())));
            }
        });
    }

    static Path packDir(JavaPlugin plugin, String dirSetting) {
        return dirSetting == null || dirSetting.isBlank()
                ? plugin.getDataFolder().toPath().toAbsolutePath().getParent().resolve("Nexo").resolve("pack").resolve("assets")
                : Path.of(dirSetting).toAbsolutePath();
    }

    private static Object prepare(JavaPlugin plugin, Settings s) {
        Logger log = plugin.getLogger();
        if (!s.packInstall()) {
            log.info("Шейдеры в пак Nexo не ставятся: pack.install: false.");
            return Report.skipped(null, "выключено (pack.install: false)");
        }
        boolean nexoDefault = s.packDir() == null || s.packDir().isBlank();
        Path dir = packDir(plugin, s.packDir());
        if (nexoDefault && !Files.isDirectory(dir.getParent())) {
            log.warning("Нет папки " + dir.getParent() + ": шейдеры не установлены. Поставьте Nexo или укажите pack.dir.");
            return Report.skipped(dir, "нет папки пака Nexo: " + dir.getParent());
        }
        try {
            Map<String, byte[]> own = new TreeMap<>();
            Path jar = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            loadJar(jar, own);
            return new Job(dir, plugin.getDataFolder().toPath().toAbsolutePath(), own);
        } catch (Exception e) {
            log.warning("Не удалось прочитать шейдеры из jar " + plugin.getName() + ": " + e);
            return Report.skipped(dir, "ошибка: " + e.getMessage());
        }
    }

    private static Object execute(Job job) {
        try {
            return install(job.dir(), job.own(), job.work());
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
            log.severe("Чужой шейдер " + dir.resolve(f) + " не заменен: эффект " + plugin.getName()
                    + " с ним не работает. Объедините его с нашим вручную или уберите.");
        }
        for (String f : r.kept()) {
            log.warning("В паке более новая версия " + f + " - оставлена, обновите " + plugin.getName() + ".");
        }
        for (String f : r.conflict()) {
            log.severe("КОНФЛИКТ: " + f + " той же версии " + VERSION + ", но другой сборки - оставлен как есть. "
                    + "Поднимите WORLDSHADER_VERSION в новой сборке или удалите старый файл из пака.");
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
