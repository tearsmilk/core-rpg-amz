package ink.hoka.odyssey.guns;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Ассеты TACZ из jar (папка {@code tacz/}: models, textures, sounds, NOTICE.md — CC BY-NC-ND 4.0, без изменений).
 * <ul>
 *   <li>{@link #read} — файл для клиентского модуля (модель в руке, силуэт HUD, вспышка). Сначала
 *       {@code module/guns/files/<путь>} (своё оружие без пересборки), потом jar;</li>
 *   <li>{@link #install} — /hogun install: положить предмет и звуки в {@code module/hoitems/assets/tacz/} и
 *       {@code module/hosounds/assets/tacz/} и дописать недостающие записи в их config.yml (с копией .bak-guns).</li>
 * </ul>
 */
final class Assets {
    static final String NOTICE = "NOTICE.md";

    /** Файлы предмета для hoitems (из tacz/models и tacz/textures). */
    static final List<String> ITEM_FILES = List.of("models/ak47_lod.bbmodel", "textures/ak47_slot.png",
            "textures/762x39.png");

    /** Звуки для hosounds: id → файл в tacz/sounds; дальность (блоков); подпись. */
    record SoundEntry(String id, String file, int range, String subtitle) {}

    static final List<SoundEntry> SOUNDS = List.of(
            new SoundEntry("ak47_shoot", "ak47_shoot.ogg", 16, null),
            new SoundEntry("ak47_shoot_3p", "ak47_shoot_3p.ogg", 128, "Выстрел АКМ"),
            new SoundEntry("ak47_draw", "ak47_draw.ogg", 12, null),
            new SoundEntry("ak47_put_away", "ak47_put_away.ogg", 12, null),
            new SoundEntry("ak47_reload_raise_shoulder", "ak47_reload_raise_shoulder.ogg", 12, null),
            new SoundEntry("ak47_reload_magout", "ak47_reload_magout.ogg", 16, "Смена магазина"),
            new SoundEntry("ak47_reload_mvt", "ak47_reload_mvt.ogg", 12, null),
            new SoundEntry("ak47_reload_magin", "ak47_reload_magin.ogg", 16, null),
            new SoundEntry("ak47_reload_mvt2", "ak47_reload_mvt2.ogg", 12, null),
            new SoundEntry("ak47_reload_shoulder", "ak47_reload_shoulder.ogg", 12, null),
            new SoundEntry("ak47_reload_empty_up", "ak47_reload_empty_up.ogg", 12, null),
            new SoundEntry("ak47_reload_empty_maghit", "ak47_reload_empty_maghit.ogg", 12, null),
            new SoundEntry("ak47_reload_empty_magout", "ak47_reload_empty_magout.ogg", 16, "Смена магазина"),
            new SoundEntry("ak47_reload_empty_magbump", "ak47_reload_empty_magbump.ogg", 12, null),
            new SoundEntry("ak47_reload_empty_magin", "ak47_reload_empty_magin.ogg", 16, null),
            new SoundEntry("ak47_reload_empty_mag_drop", "ak47_reload_empty_mag_drop.ogg", 12, null),
            new SoundEntry("ak47_reload_empty_chamber", "ak47_reload_empty_chamber.ogg", 16, "Затвор"),
            new SoundEntry("ak47_reload_empty_mvt", "ak47_reload_empty_mvt.ogg", 12, null),
            new SoundEntry("ak47_reload_empty_shoulder", "ak47_reload_empty_shoulder.ogg", 12, null),
            new SoundEntry("gun_dry_fire", "dry_fire.ogg", 8, null),
            new SoundEntry("gun_fire_select", "fire_select.ogg", 8, null),
            new SoundEntry("gun_flesh_hit", "flesh_hit.ogg", 8, null),
            new SoundEntry("gun_head_hit", "head_hit.ogg", 8, null),
            new SoundEntry("gun_kill", "kill.ogg", 8, null));

    private final Path dataFolder;

    Assets(Path dataFolder) {
        this.dataFolder = dataFolder;
    }

    /** Файл из jar: tacz/<rel>. */
    static byte[] resource(String rel) {
        try (InputStream in = Assets.class.getResourceAsStream("/tacz/" + rel)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Файл для клиентского модуля по пути из config.yml (например guns/ak47.bbmodel): module/guns/files/<путь>,
     * иначе из jar по имени файла (tacz/models, tacz/textures). null — нет.
     */
    byte[] read(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Path own = dataFolder.resolve("files").resolve(path).normalize();
        if (own.startsWith(dataFolder.resolve("files")) && Files.isRegularFile(own)) {
            try {
                return Files.readAllBytes(own);
            } catch (IOException ignored) {
                // дальше — из jar
            }
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        byte[] b = resource("models/" + name);
        return b != null ? b : resource("textures/" + name);
    }

    // ---------------------------------------------------------------- /hogun install

    /** Отчёт установки: строки для игрока и YAML, если записи пришлось бы дописать руками. */
    record Report(List<String> lines, String manualYaml) {}

    Report install(GunDef gun) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder manual = new StringBuilder();
        Path modules = dataFolder.getParent();
        Path items = modules.resolve("hoitems");
        Path sounds = modules.resolve("hosounds");

        // файлы
        Path itemDir = items.resolve("assets").resolve("tacz");
        Files.createDirectories(itemDir);
        int copied = 0;
        for (String rel : ITEM_FILES) {
            copied += copy(rel, itemDir.resolve(rel.substring(rel.indexOf('/') + 1)));
        }
        copied += copy(NOTICE, itemDir.resolve(NOTICE));
        Path soundDir = sounds.resolve("assets").resolve("tacz");
        Files.createDirectories(soundDir);
        for (SoundEntry s : SOUNDS) {
            copied += copy("sounds/" + s.file(), soundDir.resolve(s.file()));
        }
        copied += copy(NOTICE, soundDir.resolve(NOTICE));
        lines.add("Файлов скопировано: " + copied + " (" + items.resolve("assets/tacz") + ", " + soundDir + ")");

        // записи config.yml
        Map<String, String> itemEntries = new LinkedHashMap<>();
        itemEntries.put(gun.item(), itemYaml(gun));
        itemEntries.put(gun.ammoItem(), ammoYaml(gun));
        Result ri = addEntries(items.resolve("config.yml"), "items", itemEntries);
        lines.add("hoitems: " + ri.message());
        if (ri.manual() != null) {
            manual.append("# module/hoitems/config.yml\n").append(ri.manual());
        }
        Map<String, String> soundEntries = new LinkedHashMap<>();
        for (SoundEntry s : SOUNDS) {
            soundEntries.put(s.id(), soundYaml(s));
        }
        Result rs = addEntries(sounds.resolve("config.yml"), "sounds", soundEntries);
        lines.add("hosounds: " + rs.message());
        if (rs.manual() != null) {
            manual.append("# module/hosounds/config.yml\n").append(rs.manual());
        }
        return new Report(lines, manual.isEmpty() ? null : manual.toString());
    }

    private static int copy(String rel, Path to) throws IOException {
        byte[] b = resource(rel);
        if (b == null) {
            return 0;
        }
        if (Files.isRegularFile(to) && java.util.Arrays.equals(Files.readAllBytes(to), b)) {
            return 0;
        }
        Files.write(to, b);
        return 1;
    }

    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String itemYaml(GunDef g) {
        StringBuilder lore = new StringBuilder();
        for (String l : g.lore()) {
            lore.append(lore.isEmpty() ? "" : ", ").append(q(l));
        }
        return "name: " + q(g.name()) + "\n"
                + "material: " + g.material() + "\n"
                + "icon: tacz/ak47_slot.png\n"
                + "model: tacz/ak47_lod.bbmodel\n"
                + "stack: 1\n"
                + "lore: [" + lore + "]\n";
    }

    static String ammoYaml(GunDef g) {
        return "name: " + q(g.ammoName()) + "\n"
                + "color: yellow\n"
                + "material: " + g.ammoMaterial() + "\n"
                + "icon: tacz/762x39.png\n"
                + "stack: 64\n"
                + "lore: [" + q("Для: " + g.name()) + ", " + q("Иконка: TACZ, CC BY-NC-ND 4.0") + "]\n";
    }

    static String soundYaml(SoundEntry s) {
        return "files: [tacz/" + s.file() + "]\n"
                + "range: " + s.range() + "\n"
                + (s.subtitle() != null ? "subtitle: " + q(s.subtitle()) + "\n" : "");
    }

    /** Итог дописывания: сообщение и YAML для ручной вставки (null — не нужно). */
    record Result(String message, String manual) {}

    private static final Pattern SECTION = Pattern.compile("(?m)^(%s):[ \\t]*(#.*)?$");

    /**
     * Дописать в config.yml в раздел section записи, которых там нет (по ключу). Текст и комментарии файла не
     * трогаются — вставка сразу после строки «section:». Перед записью — копия .bak-guns; результат проверяется
     * разбором YAML. Не получилось — файл не меняется, а YAML возвращается для ручной вставки.
     */
    static Result addEntries(Path file, String section, Map<String, String> entries) throws IOException {
        String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        Map<?, ?> root;
        try {
            Object o = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
            root = o instanceof Map<?, ?> m ? m : Map.of();
        } catch (RuntimeException e) {
            return new Result("config.yml не читается (" + e.getMessage() + ") — допишите вручную", block(entries, "  ", section, true));
        }
        Object sec = root.get(section);
        Map<?, ?> existing = sec instanceof Map<?, ?> m ? m : Map.of();
        Map<String, String> missing = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (!existing.containsKey(e.getKey())) {
                missing.put(e.getKey(), e.getValue());
            }
        }
        if (missing.isEmpty()) {
            return new Result("все записи уже есть", null);
        }
        String updated;
        Matcher m = Pattern.compile(String.format(SECTION.pattern(), Pattern.quote(section))).matcher(text);
        if (m.find()) {
            String indent = childIndent(text, m.end());
            updated = text.substring(0, m.end()) + "\n" + block(missing, indent, section, false).stripTrailing()
                    + text.substring(m.end());
        } else if (sec == null) {
            String sep = text.isEmpty() || text.endsWith("\n") ? "" : "\n";
            updated = text + sep + block(missing, "  ", section, true);
        } else {
            return new Result("раздел " + section + " записан в одну строку — допишите вручную", block(missing, "  ", section, true));
        }
        // проверка: разбирается и все записи на месте
        try {
            Object o = new Yaml(new SafeConstructor(new LoaderOptions())).load(updated);
            Object s2 = o instanceof Map<?, ?> r ? r.get(section) : null;
            if (!(s2 instanceof Map<?, ?> sm) || !sm.keySet().containsAll(missing.keySet())) {
                throw new IllegalStateException("записи не видны после вставки");
            }
        } catch (RuntimeException e) {
            return new Result("не удалось дописать автоматически (" + e.getMessage() + ") — допишите вручную",
                    block(missing, "  ", section, true));
        }
        if (Files.isRegularFile(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak-guns"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        return new Result("дописано " + missing.size() + ": " + String.join(", ", missing.keySet()), null);
    }

    /** Отступ первой вложенной строки после позиции from (по умолчанию два пробела). */
    private static String childIndent(String text, int from) {
        for (String line : text.substring(from).split("\n")) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            int n = line.length() - line.stripLeading().length();
            return n > 0 ? line.substring(0, n) : "  ";
        }
        return "  ";
    }

    static String block(Map<String, String> entries, String indent, String section, boolean withHeader) {
        StringBuilder b = new StringBuilder();
        if (withHeader) {
            b.append(section).append(":\n");
        }
        b.append(indent).append("# ---- добавлено /hogun install (ассеты TACZ, CC BY-NC-ND 4.0, см. assets/tacz/NOTICE.md)\n");
        for (Map.Entry<String, String> e : entries.entrySet()) {
            b.append(indent).append(e.getKey()).append(":\n");
            for (String line : e.getValue().split("\n")) {
                if (!line.isEmpty()) {
                    b.append(indent).append(indent).append(line).append("\n");
                }
            }
        }
        return b.toString();
    }
}
