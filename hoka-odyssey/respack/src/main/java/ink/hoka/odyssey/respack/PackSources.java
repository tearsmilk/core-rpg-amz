package ink.hoka.odyssey.respack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Что кладут в пак модули: файлы из памяти и папки. Порядок владельцев — порядок первой регистрации
 * (кто раньше — тот приоритетнее при совпадении путей). Потокобезопасно: снимок для сборки — {@link #snapshot()}.
 */
final class PackSources {

    /** Путь в zip: латиница, цифры, _ - . /, без «..», не с «/». Как требует клиент для assets. */
    private static final Pattern PATH = Pattern.compile("[a-z0-9_.\\-/]+");

    /** Слой одного владельца на момент сборки. */
    record Layer(String owner, Map<String, byte[]> files, List<Path> dirs) { }

    private static final class Entry {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final List<Path> dirs = new ArrayList<>();
    }

    private final Map<String, Entry> owners = new LinkedHashMap<>();

    /** null — путь подходит, иначе причина. */
    static String checkPath(String path) {
        if (path == null || path.isEmpty()) return "пустой путь";
        if (!PATH.matcher(path).matches()) return "недопустимые символы (можно a-z 0-9 _ - . /): " + path;
        if (path.startsWith("/") || path.endsWith("/")) return "путь не может начинаться или кончаться на /: " + path;
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return "неверный путь: " + path;
        }
        return null;
    }

    static String normalize(String path) {
        return path == null ? null : path.replace('\\', '/');
    }

    private static void checkOwner(String owner) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("владелец (id модуля) не указан");
    }

    synchronized void putFile(String owner, String path, byte[] data) {
        checkOwner(owner);
        String p = normalize(path);
        String bad = checkPath(p);
        if (bad != null) throw new IllegalArgumentException(bad);
        if (data == null) throw new IllegalArgumentException("нет данных для " + p);
        owners.computeIfAbsent(owner, k -> new Entry()).files.put(p, data.clone());
    }

    synchronized void removeFile(String owner, String path) {
        Entry e = owners.get(owner);
        if (e != null) e.files.remove(normalize(path));
    }

    synchronized void putDirectory(String owner, Path dir) {
        checkOwner(owner);
        if (dir == null) throw new IllegalArgumentException("папка не указана");
        Path d = dir.toAbsolutePath().normalize();
        Entry e = owners.computeIfAbsent(owner, k -> new Entry());
        if (!e.dirs.contains(d)) e.dirs.add(d);
    }

    synchronized void clear(String owner) {
        owners.remove(owner);
    }

    synchronized List<Layer> snapshot() {
        List<Layer> out = new ArrayList<>();
        for (Map.Entry<String, Entry> en : owners.entrySet()) {
            out.add(new Layer(en.getKey(), new LinkedHashMap<>(en.getValue().files), List.copyOf(en.getValue().dirs)));
        }
        return out;
    }

    synchronized List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Entry> en : owners.entrySet()) {
            out.add(en.getKey() + ": файлов " + en.getValue().files.size() + ", папок " + en.getValue().dirs.size());
        }
        return out;
    }
}
