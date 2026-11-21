package ink.hoka.odyssey.respack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Сборка zip пака. Без Minestom — чтобы проверялось тестами. Zip детерминированный (файлы по алфавиту, одно время у
 * всех записей), поэтому одинаковое содержимое даёт одинаковый SHA-1 — клиент берёт пак из своего кэша, а после
 * перезагрузки модулей пак не рассылается заново, если ничего не поменялось.
 */
final class PackBuilder {

    static final String MCMETA = "pack.mcmeta";
    /** 2000-01-01 00:00 — время всех записей zip (у DOS-времени нет нуля). */
    private static final long ENTRY_TIME = 946_684_800_000L;

    /** Готовый пак. */
    record Built(byte[] zip, ResourcePackApi.PackInfo info, List<String> warnings) { }

    /** Один файл пака и откуда он: владелец и источник (для сообщений о конфликтах). */
    private record Item(String owner, Source source) { }

    private interface Source { byte[] read() throws IOException; }

    private PackBuilder() { }

    /**
     * @param layers  слои модулей по приоритету (первый важнее)
     * @param admin   папка администратора — перекрывает всех (может не существовать)
     * @param format  версия формата пака (для 1.21.11 — 75)
     */
    static Built build(List<PackSources.Layer> layers, Path admin, String description, int format) throws IOException {
        List<String> warnings = new ArrayList<>();
        TreeMap<String, Item> files = new TreeMap<>();
        for (PackSources.Layer l : layers) {
            for (Map.Entry<String, byte[]> f : l.files().entrySet()) {
                byte[] data = f.getValue();
                add(files, f.getKey(), new Item(l.owner(), () -> data), false, warnings);
            }
            for (Path dir : l.dirs()) walk(dir, l.owner(), files, false, warnings);
        }
        if (admin != null) walk(admin, "администратор", files, true, warnings);
        if (!files.containsKey(MCMETA)) {
            files.put(MCMETA, new Item("respack", () -> mcmeta(description, format).getBytes(StandardCharsets.UTF_8)));
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 20);
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setLevel(9);
            for (Map.Entry<String, Item> f : files.entrySet()) {
                byte[] data = f.getValue().source().read();
                ZipEntry e = new ZipEntry(f.getKey());
                e.setTime(ENTRY_TIME);
                zip.putNextEntry(e);
                zip.write(data);
                zip.closeEntry();
                if (Thread.interrupted()) throw new IOException("сборка прервана");
            }
        }
        byte[] zip = bytes.toByteArray();
        return new Built(zip, new ResourcePackApi.PackInfo(sha1(zip), zip.length, files.size()), warnings);
    }

    private static void add(TreeMap<String, Item> files, String path, Item item, boolean override, List<String> warnings) {
        if (!override && path.equals(MCMETA)) {
            warnings.add(item.owner() + ": pack.mcmeta собирает respack — свой можно положить только в module/respack/pack/");
            return;
        }
        Item prev = files.get(path);
        if (prev == null || override) {
            files.put(path, item);
        } else if (!prev.owner().equals(item.owner())) {
            warnings.add("файл " + path + " есть у " + prev.owner() + " и у " + item.owner() + " — взят у " + prev.owner());
        }
    }

    private static void walk(Path dir, String owner, TreeMap<String, Item> files, boolean override, List<String> warnings)
            throws IOException {
        if (!Files.isDirectory(dir)) return;
        List<Path> list;
        try (Stream<Path> s = Files.walk(dir)) {
            list = s.filter(Files::isRegularFile).sorted().toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        for (Path f : list) {
            String rel = PackSources.normalize(dir.relativize(f).toString());
            String name = f.getFileName().toString();
            if (name.startsWith(".") || name.endsWith(".tmp")) continue;                 // служебные и недописанные
            String bad = PackSources.checkPath(rel);
            if (bad != null) {
                warnings.add(owner + ": пропущен " + rel + " — " + bad);
                continue;
            }
            add(files, rel, new Item(owner, () -> Files.readAllBytes(f)), override, warnings);
        }
    }

    static String mcmeta(String description, int format) {
        String d = description == null ? "" : description;
        StringBuilder esc = new StringBuilder();
        for (char c : d.toCharArray()) {
            switch (c) {
                case '"' -> esc.append("\\\"");
                case '\\' -> esc.append("\\\\");
                case '\n' -> esc.append("\\n");
                default -> {
                    if (c < 0x20) esc.append(String.format("\\u%04x", (int) c)); else esc.append(c);
                }
            }
        }
        return "{\n  \"pack\": {\n    \"description\": \"" + esc + "\",\n    \"pack_format\": " + format
                + ",\n    \"min_format\": " + format + ",\n    \"max_format\": " + format + "\n  }\n}\n";
    }

    static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
