package ink.hoka.odyssey.coreshaders;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Файлы пака из папки (или папки внутри jar): путь в zip («assets/…») → содержимое. Без Minestom — для async и тестов. */
final class PackFilesCollector {
    private PackFilesCollector() {
    }

    /** Все обычные файлы под root, кроме служебных (.*, *.tmp). root может не существовать — тогда пусто. */
    static Map<String, byte[]> collect(Path root) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        if (root == null || !Files.isDirectory(root)) {
            return out;
        }
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        for (Path f : files) {
            String name = f.getFileName().toString();
            if (name.startsWith(".") || name.endsWith(".tmp")) {
                continue;
            }
            String rel = root.relativize(f).toString().replace('\\', '/');
            out.put(rel, Files.readAllBytes(f));
        }
        return out;
    }
}
