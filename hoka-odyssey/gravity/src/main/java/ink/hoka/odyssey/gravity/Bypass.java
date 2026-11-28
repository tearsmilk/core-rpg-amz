package ink.hoka.odyssey.gravity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Админ-обход вакуума (/hograv bypass): игроки, для которых gravity не включает невесомость нигде — ни по карте, ни
 * по решению другого модуля через {@link VacuumService}. Хранится в module/gravity/bypass.yml (переживает выход и
 * перезапуск): uuid → последний известный ник (ник — только для списка). Потокобезопасно.
 */
final class Bypass {
    static final String FILE = "bypass.yml";
    private static final Pattern LINE = Pattern.compile(
        "^\\s+\"?([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\"?\\s*:\\s*\"?([^\"#]*?)\"?\\s*(#.*)?$");

    private final Map<UUID, String> players = Collections.synchronizedMap(new LinkedHashMap<>());

    boolean contains(UUID id) {
        return players.containsKey(id);
    }

    /** Включить (true) или выключить; true — что-то поменялось. */
    boolean set(UUID id, String name, boolean on) {
        synchronized (players) {
            if (on) {
                String prev = players.put(id, name == null ? "" : name);
                return prev == null;
            }
            return players.remove(id) != null;
        }
    }

    /** Обновить ник (игрок зашёл под новым именем); true — поменялся. */
    boolean rename(UUID id, String name) {
        synchronized (players) {
            String prev = players.get(id);
            if (prev == null || prev.equals(name)) {
                return false;
            }
            players.put(id, name);
            return true;
        }
    }

    /** Снимок: uuid → ник. */
    Map<UUID, String> snapshot() {
        synchronized (players) {
            return new LinkedHashMap<>(players);
        }
    }

    /** UUID по нику из списка (без учёта регистра), null — нет. */
    UUID byName(String name) {
        synchronized (players) {
            for (var e : players.entrySet()) {
                if (e.getValue().equalsIgnoreCase(name)) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    void replaceAll(Map<UUID, String> all) {
        synchronized (players) {
            players.clear();
            players.putAll(all);
        }
    }

    /** Разобрать bypass.yml (раздел players: uuid: ник). Битые строки пропускаются. */
    static Map<UUID, String> parse(String text) {
        Map<UUID, String> out = new LinkedHashMap<>();
        boolean section = false;
        for (String line : text.split("\\R")) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            if (!Character.isWhitespace(line.charAt(0))) {
                section = line.startsWith("players:");
                continue;
            }
            if (!section) {
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (m.matches()) {
                out.put(UUID.fromString(m.group(1)), m.group(2).strip());
            }
        }
        return out;
    }

    /** Текст bypass.yml. */
    static String render(Map<UUID, String> all) {
        StringBuilder sb = new StringBuilder();
        sb.append("# gravity: админ-обход вакуума (/hograv bypass). Пишет команда; uuid: ник (ник — только для списка).\n");
        sb.append("# Игроки отсюда (если они операторы) никогда не попадают в невесомость.\n");
        sb.append("players:").append(all.isEmpty() ? " {}" : "").append('\n');
        all.forEach((id, name) -> sb.append("  \"").append(id).append("\": \"")
            .append(name.replace("\"", "").replace("\\", "")).append("\"\n"));
        return sb.toString();
    }

    /** Прочитать файл (нет файла — пусто). */
    void load(Path file) throws IOException {
        replaceAll(Files.isRegularFile(file) ? parse(Files.readString(file, StandardCharsets.UTF_8)) : Map.of());
    }

    /** Записать текущее состояние (через временный файл — не оставить половину при сбое). */
    synchronized void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, render(snapshot()), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
