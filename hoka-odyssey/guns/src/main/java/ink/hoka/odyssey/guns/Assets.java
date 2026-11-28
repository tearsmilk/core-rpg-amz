package ink.hoka.odyssey.guns;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Ассеты TACZ из jar (папка {@code tacz/}: models, textures, sounds, NOTICE.md — CC BY-NC-ND 4.0, без изменений) и
 * наши собственные (папка {@code hoka/} — плазменная винтовка ПВ-7: модель, иконки и звуки сделаны скриптами
 * tools/plasma, к TACZ отношения не имеют; в hoitems/hosounds ставятся в {@code assets/hoka/}).
 * <ul>
 *   <li>{@link #read} — файл для клиентского модуля (модель в руке, силуэт HUD, вспышка). Сначала
 *       {@code module/guns/files/<путь>} (своё оружие без пересборки), потом jar;</li>
 *   <li>{@link #install} — /hogun install: положить предметы и звуки всего оружия в {@code module/hoitems/assets/tacz/}
 *       и {@code module/hosounds/assets/tacz/} и дописать недостающие записи в их config.yml (с копией .bak-guns);
 *       {@link #addMissingGuns} — дописать в свой config.yml оружие, которого там ещё нет (AWP, Glock 17, M4A1,
 *       M16A1, Desert Eagle, M1897).</li>
 * </ul>
 */
final class Assets {
    static final String NOTICE = "NOTICE.md";

    /** Наши ассеты (не TACZ): папка в jar и в assets/ hoitems/hosounds. */
    static final String HOKA = "hoka";
    static final String TACZ = "tacz";

    /** Звуки для hosounds: id → файл в &lt;dir&gt;/sounds (tacz или hoka); дальность (блоков); подпись. */
    record SoundEntry(String id, String file, int range, String subtitle, String dir) {
        SoundEntry(String id, String file, int range, String subtitle) {
            this(id, file, range, subtitle, TACZ);
        }
    }

    /** Ассеты одного оружия: файлы предмета для hoitems (tacz/models, tacz/textures) и его звуки для hosounds. */
    record GunAssets(List<String> itemFiles, List<SoundEntry> sounds) {}

    private static SoundEntry s(String id, int range) {
        return new SoundEntry(id, id + ".ogg", range, null);
    }

    private static SoundEntry s(String id, int range, String subtitle) {
        return new SoundEntry(id, id + ".ogg", range, subtitle);
    }

    /** Наш звук (папка hoka/sounds). */
    private static SoundEntry h(String id, int range, String subtitle) {
        return new SoundEntry(id, id + ".ogg", range, subtitle, HOKA);
    }

    /** Общие звуки (осечка, режим огня, попадания). */
    static final List<SoundEntry> COMMON_SOUNDS = List.of(
            new SoundEntry("gun_dry_fire", "dry_fire.ogg", 8, null),
            new SoundEntry("gun_fire_select", "fire_select.ogg", 8, null),
            new SoundEntry("gun_flesh_hit", "flesh_hit.ogg", 8, null),
            new SoundEntry("gun_head_hit", "head_hit.ogg", 8, null),
            new SoundEntry("gun_kill", "kill.ogg", 8, null));

    /** Оружие из пака TACZ, которое модуль везёт в jar (id оружия → его файлы и звуки). */
    static final Map<String, GunAssets> GUNS = Map.of(
            // своё оружие (не TACZ): файлы предмета — по item-model/item-icon/ammo-icon вида hoka/…, звуки — hoka/sounds
            "plasma_rifle", new GunAssets(List.of(),
                    List.of(h("plasma_rifle_shoot", 16, null), h("plasma_rifle_shoot_3p", 112, "Выстрел плазмой"),
                            h("plasma_rifle_draw", 12, null), h("plasma_rifle_put_away", 12, null),
                            h("plasma_rifle_cell_out", 16, "Смена ячейки"), h("plasma_rifle_cell_in", 16, null),
                            h("plasma_rifle_charge", 16, "Заряд"))),
            "m4a1", new GunAssets(List.of("models/m4a1_lod.bbmodel", "textures/m4a1_slot.png", "textures/556x45.png"),
                    List.of(s("m4a1_shoot", 16), s("m4a1_shoot_3p", 128, "Выстрел M4A1"), s("m4a1_silence", 16),
                            s("m4a1_silence_3p", 48, "Выстрел с глушителем"), s("m4a1_draw", 12),
                            s("m4a1_put_away", 12), s("m4a1_reload_tactical_start", 12),
                            s("m4a1_reload_tactical_magout", 16, "Смена магазина"), s("m4a1_reload_tactical_magin", 16),
                            s("m4a1_reload_tactical_maghit", 12), s("m4a1_reload_tactical_end", 12),
                            s("m4a1_reload_empty_raise", 12), s("m4a1_reload_empty_magout", 16, "Смена магазина"),
                            s("m4a1_reload_empty_maghit", 12), s("m4a1_reload_empty_magin", 16),
                            s("m4a1_reload_empty_charge", 16, "Затвор"), s("m4a1_reload_empty_end", 12),
                            s("m4a1_reload_empty_xmag_start", 12))),
            "m16a1", new GunAssets(List.of("models/m16a1_lod.bbmodel", "textures/m16a1_slot.png", "textures/556x45.png"),
                    List.of(s("m16a1_shoot", 16), s("m16a1_shoot_3p", 128, "Выстрел M16A1"), s("m16a1_silence", 16),
                            s("m16a1_silence_3p", 48, "Выстрел с глушителем"), s("m16a1_draw", 12),
                            s("m16a1_put_away", 12), s("m16a1_reload_tactical", 16, "Смена магазина"),
                            s("m16a1_reload_empty", 16, "Смена магазина"))),
            "deagle", new GunAssets(List.of("models/deagle_lod.bbmodel", "textures/deagle_slot.png", "textures/50ae.png"),
                    List.of(s("deagle_shoot", 16), s("deagle_shoot_3p", 128, "Выстрел Desert Eagle"),
                            s("deagle_silence", 16), s("deagle_silence_3p", 48, "Выстрел с глушителем"),
                            s("deagle_draw", 12), s("deagle_put_away", 12), s("deagle_reload_raise", 12),
                            s("deagle_reload_magout", 16, "Смена магазина"), s("deagle_reload_magrelease", 12),
                            s("deagle_reload_magfindwell", 12), s("deagle_reload_magin", 16), s("deagle_reload_end", 12),
                            s("deagle_reload_xmag_bounce", 12), s("deagle_reload_xmag_magbang", 12),
                            s("deagle_reload_empty_raise", 12), s("deagle_reload_empty_tilt", 12),
                            s("deagle_reload_empty_magout", 16, "Смена магазина"),
                            s("deagle_reload_empty_magfindwell", 12), s("deagle_reload_empty_magin", 16),
                            s("deagle_reload_empty_slideback", 16, "Затвор"), s("deagle_reload_empty_slideclose", 16),
                            s("deagle_reload_empty_end", 12))),
            "m1897", new GunAssets(List.of("models/m1897_item.bbmodel", "textures/m1897_slot.png", "textures/12g.png"),
                    List.of(s("m1897_shoot", 16), s("m1897_shoot_3p", 128, "Выстрел M1897"), s("m1897_silence", 16),
                            s("m1897_silence_3p", 48, "Выстрел с глушителем"), s("m1897_draw", 12),
                            s("m1897_put_away", 12), s("m1897_bolt_in", 16, "Помпа"), s("m1897_bolt_out", 16),
                            s("m1897_bolt_shell_out", 12), s("m1897_shell_impact_brass", 12),
                            s("m1897_reload_empty_start", 12), s("m1897_reload_shellinsert", 16, "Зарядка патрона"),
                            s("m1897_reload_side", 12), s("m1897_reload_end", 12))),
            "ak47", new GunAssets(List.of("models/ak47_lod.bbmodel", "textures/ak47_slot.png", "textures/762x39.png"),
                    List.of(s("ak47_shoot", 16), s("ak47_shoot_3p", 128, "Выстрел АКМ"), s("ak47_silence", 16),
                            s("ak47_silence_3p", 48, "Выстрел с глушителем"), s("ak47_draw", 12),
                            s("ak47_put_away", 12), s("ak47_reload_raise_shoulder", 12),
                            s("ak47_reload_magout", 16, "Смена магазина"), s("ak47_reload_mvt", 12),
                            s("ak47_reload_magin", 16), s("ak47_reload_mvt2", 12), s("ak47_reload_shoulder", 12),
                            s("ak47_reload_empty_up", 12), s("ak47_reload_empty_maghit", 12),
                            s("ak47_reload_empty_magout", 16, "Смена магазина"), s("ak47_reload_empty_magbump", 12),
                            s("ak47_reload_empty_magin", 16), s("ak47_reload_empty_mag_drop", 12),
                            s("ak47_reload_empty_chamber", 16, "Затвор"), s("ak47_reload_empty_mvt", 12),
                            s("ak47_reload_empty_shoulder", 12))),
            "ai_awp", new GunAssets(List.of("models/ai_awp_lod.bbmodel", "textures/338.png"),
                    List.of(s("awp_shoot", 16), s("awp_shoot_3p", 160, "Выстрел AWP"), s("awp_silence", 16),
                            s("awp_silence_3p", 48, "Выстрел с глушителем"), s("awp_draw", 12),
                            s("awp_put_away", 12), s("awp_rechamber_out", 16, "Затвор"),
                            s("awp_rechember_ejectclick", 12), s("awp_rechamber_in", 16), s("awp_rechamber_end", 12),
                            s("awp_reload_raise", 12), s("awp_reload_rattle", 12), s("awp_reload_ejectclick", 12),
                            s("awp_reload_magout", 16, "Смена магазина"), s("awp_reload_empty_fast_01_rattle", 12),
                            s("awp_reload_maghit", 12), s("awp_reload_magin", 16), s("awp_reload_end", 12),
                            s("awp_raise_first_rattle", 12), s("awp_reload_empty_magout", 16, "Смена магазина"),
                            s("awp_reload_empty_mag_drop", 12), s("awp_reload_empty_rattle", 12),
                            s("awp_reload_empty_fast_maghit", 12), s("awp_reload_empty_magin", 16),
                            s("awp_reload_empty_boltclose", 16, "Затвор"), s("awp_reload_empty_end", 12))),
            "glock_17", new GunAssets(List.of("models/glock_17_lod.bbmodel", "textures/glock_17_slot.png",
                    "textures/9mm.png"),
                    List.of(s("glock17_shoot", 16), s("glock17_shoot_3p", 112, "Выстрел Glock"),
                            s("glock17_silence", 16), s("glock17_silence_3p", 48, "Выстрел с глушителем"),
                            s("glock17_draw", 12), s("glock17_reload_start", 12),
                            s("glock17_reload_magout", 16, "Смена магазина"), s("glock17_reload_magin", 16),
                            s("glock17_reload_end", 12), s("glock17_reload_empty_magout", 16, "Смена магазина"),
                            s("glock17_reload_empty_magin", 16), s("glock17_reload_empty_charge", 16, "Затвор"),
                            s("glock17_reload_empty_end", 12))));

    /** Все файлы предметов (для проверки jar). */
    static final List<String> ITEM_FILES = GUNS.values().stream().flatMap(g -> g.itemFiles().stream()).toList();

    /** Звуки установки/снятия обвеса (tacz_sounds/attachments). */
    static final List<SoundEntry> ATTACHMENT_SOUNDS = java.util.stream.Stream.of("attachment_general_a",
                    "attachment_general_b", "attachment_general_uninstall", "bayonet_install", "bayonet_uninstall",
                    "extended_mag_install", "extended_mag_uninstall", "laser_general", "muzzle_general",
                    "scope_general_a", "scope_general_b", "scope_general_c", "scope_general_d", "scope_general_e")
            .map(n -> new SoundEntry("gun_att_" + n, n + ".ogg", 8, null)).toList();

    /** Все звуки (общие и всего оружия). */
    static final List<SoundEntry> SOUNDS = java.util.stream.Stream.concat(COMMON_SOUNDS.stream(),
            GUNS.values().stream().flatMap(g -> g.sounds().stream())).toList();

    private final Path dataFolder;

    Assets(Path dataFolder) {
        this.dataFolder = dataFolder;
    }

    /** Файл из jar: tacz/<rel>. */
    static byte[] resource(String rel) {
        return resource(TACZ, rel);
    }

    /** Файл из jar: <dir>/<rel> (dir — tacz или hoka). */
    static byte[] resource(String dir, String rel) {
        try (InputStream in = Assets.class.getResourceAsStream("/" + dir + "/" + rel)) {
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
        if (path.startsWith("guns/att/")) {                   // обвесы: tacz/models/att, tacz/textures/att
            return resource((name.endsWith(".png") ? "textures/att/" : "models/att/") + name);
        }
        byte[] b = resource("models/" + name);
        if (b == null) b = resource("animations/" + name);
        if (b == null) b = resource("textures/" + name);
        if (b == null) b = resource(HOKA, "models/" + name);       // наше оружие (hoka/)
        return b != null ? b : resource(HOKA, "textures/" + name);
    }

    // ---------------------------------------------------------------- /hogun install

    /** Отчёт установки: строки для игрока и YAML, если записи пришлось бы дописать руками. */
    record Report(List<String> lines, String manualYaml) {}

    /**
     * Поставить ассеты оружия guns: файлы — в hoitems/hosounds (assets/tacz/), записи — в их config.yml (предмет,
     * патроны; звуки — общие и этого оружия). Оружие не из пака модуля (своё) — копируются файлы его item-model,
     * item-icon и ammo-icon, если они есть в jar.
     */
    Report install(Collection<GunDef> guns) throws IOException {
        return install(guns, Attachments.EMPTY, Map.of());
    }

    /**
     * То же и обвесы каталога cat: иконки (tacz/att/), предметы hoitems att_<id> (кроме встроенных) и звуки
     * установки/снятия; «Подходит:» в описании предмета — оружие, которому обвес разрешён (по guns).
     */
    Report install(Collection<GunDef> guns, Attachments cat, Map<String, Attachments.GunAtt> gunAtt) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder manual = new StringBuilder();
        Path modules = dataFolder.getParent();
        Path items = modules.resolve("hoitems");
        Path sounds = modules.resolve("hosounds");

        // файлы
        Path itemDir = items.resolve("assets").resolve("tacz");
        Files.createDirectories(itemDir);
        Path soundDir = sounds.resolve("assets").resolve("tacz");
        Files.createDirectories(soundDir);
        Set<String> files = new LinkedHashSet<>();
        Set<String> own = new LinkedHashSet<>();               // наши (hoka/) — в assets/hoka/, без NOTICE TACZ
        Map<String, SoundEntry> snd = new LinkedHashMap<>();
        for (SoundEntry e : COMMON_SOUNDS) {
            snd.put(e.id(), e);
        }
        if (!cat.all().isEmpty()) {
            for (SoundEntry e : ATTACHMENT_SOUNDS) {
                snd.put(e.id(), e);
            }
            for (Attachments.Def d : cat.all()) {
                if (d.icon().startsWith("tacz/")) {
                    files.add("textures/" + d.icon().substring(5));
                } else if (!d.builtin() && ownAttIcon(d) != null) {   // свой обвес (не TACZ) — иконка в assets/hoka/
                    own.add("textures/" + ownAttIcon(d).substring(HOKA.length() + 1));
                }
            }
        }
        for (GunDef g : guns) {
            GunAssets a = GUNS.get(g.id());
            if (a != null) {
                files.addAll(a.itemFiles());
                for (SoundEntry e : a.sounds()) {
                    snd.putIfAbsent(e.id(), e);
                }
            }
            for (String f : new String[] {g.itemModel(), g.itemIcon(), g.ammoIcon()}) {
                if (f != null && f.startsWith("tacz/")) {
                    String name = f.substring(5);
                    files.add((name.endsWith(".bbmodel") ? "models/" : "textures/") + name);
                } else if (f != null && f.startsWith(HOKA + "/")) {
                    String name = f.substring(HOKA.length() + 1);
                    own.add((name.endsWith(".bbmodel") ? "models/" : "textures/") + name);
                }
            }
        }
        int copied = 0;
        for (String rel : files) {
            Path to = itemDir.resolve(rel.substring(rel.indexOf('/') + 1));
            Files.createDirectories(to.getParent());
            copied += copy(rel, to);
        }
        copied += copy(NOTICE, itemDir.resolve(NOTICE));
        Path ownItemDir = items.resolve("assets").resolve(HOKA);
        for (String rel : own) {
            Path to = ownItemDir.resolve(rel.substring(rel.indexOf('/') + 1));
            Files.createDirectories(to.getParent());
            copied += copy(HOKA, rel, to);
        }
        boolean ownSounds = false;
        for (SoundEntry e : snd.values()) {
            Path dir = sounds.resolve("assets").resolve(e.dir());
            if (!TACZ.equals(e.dir())) {
                Files.createDirectories(dir);
                ownSounds = true;
            }
            copied += copy(e.dir(), "sounds/" + e.file(), dir.resolve(e.file()));
        }
        copied += copy(NOTICE, soundDir.resolve(NOTICE));
        lines.add("Файлов скопировано: " + copied + " (" + itemDir + ", " + soundDir
                + (own.isEmpty() && !ownSounds ? "" : "; свои, не TACZ — в assets/" + HOKA + "/") + ")");

        // записи config.yml
        Map<String, String> itemEntries = new LinkedHashMap<>();
        for (GunDef g : guns) {
            itemEntries.putIfAbsent(g.item(), itemYaml(g));
            itemEntries.putIfAbsent(g.ammoItem(), ammoYaml(g, guns));
        }
        for (Attachments.Def d : cat.all()) {
            if (!d.builtin() && !d.item().isEmpty()) {
                itemEntries.putIfAbsent(d.item(), attachmentYaml(d, guns, cat, gunAtt));
            }
        }
        Result ri = addEntries(items.resolve("config.yml"), "items", itemEntries);
        lines.add("hoitems: " + ri.message());
        if (ri.manual() != null) {
            manual.append("# module/hoitems/config.yml\n").append(ri.manual());
        }
        Map<String, String> soundEntries = new LinkedHashMap<>();
        for (SoundEntry e : snd.values()) {
            soundEntries.put(e.id(), soundYaml(e));
        }
        Result rs = addEntries(sounds.resolve("config.yml"), "sounds", soundEntries);
        lines.add("hosounds: " + rs.message());
        if (rs.manual() != null) {
            manual.append("# module/hosounds/config.yml\n").append(rs.manual());
        }
        return new Report(lines, manual.isEmpty() ? null : manual.toString());
    }

    /**
     * Оружие из config.yml в jar, которого нет в module/guns/config.yml (сервер обновили, а его config.yml создан
     * старой версией): дописать в раздел guns их записи из jar (как есть, с комментариями внутри записи).
     */
    static Result addMissingGuns(Path file) throws IOException {
        String text;
        try (InputStream in = Assets.class.getResourceAsStream("/config.yml")) {
            text = in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (text == null) {
            return new Result("config.yml в jar не найден", null);
        }
        return addEntries(file, "guns", gunBlocks(text));
    }

    /**
     * Обвес оружия, который поменялся в новой версии модуля (id → слот, которого не было): если в module/guns/config.yml
     * у оружия в attachments.slots нет этого слота, блок attachments записи заменяется блоком из jar (остальная
     * запись и файл не трогаются). plasma_rifle — удлинённая ячейка plasma_cell_extended (слот extended_mag).
     */
    static final Map<String, String> ATT_UPGRADES = Map.of("plasma_rifle", "extended_mag");

    /** Обновить блоки attachments оружия из {@link #ATT_UPGRADES}, где их ещё нет (см. там). */
    static Result upgradeGunAttachments(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new Result("обвес оружия обновлять не нужно", null);
        }
        String jar;
        try (InputStream in = Assets.class.getResourceAsStream("/config.yml")) {
            jar = in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (jar == null) {
            return new Result("config.yml в jar не найден", null);
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Map<?, ?> guns;
        try {
            Object o = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
            Object g = o instanceof Map<?, ?> r ? r.get("guns") : null;
            guns = g instanceof Map<?, ?> m ? m : Map.of();
        } catch (RuntimeException e) {
            return new Result("config.yml не читается (" + e.getMessage() + ")", null);
        }
        Map<String, String> jarBlocks = gunBlocks(jar);
        List<String> done = new ArrayList<>();
        StringBuilder manual = new StringBuilder();
        String updated = text;
        for (Map.Entry<String, String> u : ATT_UPGRADES.entrySet()) {
            if (!(guns.get(u.getKey()) instanceof Map<?, ?> gun) || !jarBlocks.containsKey(u.getKey())) {
                continue;
            }
            Object slots = gun.get("attachments") instanceof Map<?, ?> a ? a.get("slots") : null;
            if (slots instanceof List<?> l && l.contains(u.getValue())) {
                continue;                                     // уже новый (или настроен руками)
            }
            String att = attachmentsBlock(jarBlocks.get(u.getKey()));
            if (att == null) {
                continue;
            }
            String next = replaceAttachments(updated, u.getKey(), att);
            if (next == null) {
                manual.append("# guns.").append(u.getKey()).append(".attachments:\n").append(att);
                continue;
            }
            updated = next;
            done.add(u.getKey());
        }
        if (done.isEmpty()) {
            return new Result(manual.isEmpty() ? "обвес оружия уже новый" : "обвес оружия не обновлён — допишите вручную",
                    manual.isEmpty() ? null : manual.toString());
        }
        try {
            Object o = new Yaml(new SafeConstructor(new LoaderOptions())).load(updated);
            Object g = o instanceof Map<?, ?> r ? r.get("guns") : null;
            for (String id : done) {
                Object a = g instanceof Map<?, ?> gm && gm.get(id) instanceof Map<?, ?> e ? e.get("attachments") : null;
                if (!(a instanceof Map<?, ?> am) || !(am.get("slots") instanceof List<?> sl) || !sl.contains(ATT_UPGRADES.get(id))) {
                    throw new IllegalStateException("обвес " + id + " не виден после замены");
                }
            }
        } catch (RuntimeException e) {
            StringBuilder b = new StringBuilder(manual);
            for (String id : done) {
                b.append("# guns.").append(id).append(".attachments:\n").append(attachmentsBlock(jarBlocks.get(id)));
            }
            return new Result("обвес оружия не обновлён (" + e.getMessage() + ") — допишите вручную", b.toString());
        }
        Files.copy(file, file.resolveSibling(file.getFileName() + ".bak-guns"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        return new Result("обвес обновлён: " + String.join(", ", done), manual.isEmpty() ? null : manual.toString());
    }

    /** Блок «attachments:» из текста записи оружия (gunBlocks: ключи записи с начала строки) — как есть, или null. */
    static String attachmentsBlock(String gunBody) {
        StringBuilder b = null;
        for (String l : gunBody.split("\n", -1)) {
            if (b == null) {
                if (l.matches("attachments:[ \\t]*(#.*)?")) {
                    b = new StringBuilder(l).append('\n');
                }
                continue;
            }
            if (!l.isBlank() && !l.startsWith(" ") && !l.startsWith("#")) {
                break;
            }
            b.append(l).append('\n');
        }
        if (b == null) {
            return null;
        }
        String s = b.toString();
        while (s.endsWith("\n\n") || s.matches("(?s).*\n#[^\n]*\n")) {   // хвостовые пустые строки и комментарии
            s = s.endsWith("\n\n") ? s.substring(0, s.length() - 1) : s.substring(0, s.lastIndexOf('\n', s.length() - 2) + 1);
        }
        return s;
    }

    /**
     * Заменить в тексте config.yml блок attachments записи guns.&lt;id&gt; на att (ключ с начала строки; отступ — как
     * у ключей записи в файле). Нет блока — дописать в конец записи. null — запись не найдена.
     */
    static String replaceAttachments(String text, String id, String att) {
        String[] lines = text.split("\n", -1);
        int gi = -1;
        int gIndent = 0;
        boolean inGuns = false;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (!l.isBlank() && !l.startsWith(" ") && !l.startsWith("#")) {
                inGuns = l.startsWith("guns:");
                continue;
            }
            if (inGuns && l.matches(" +" + Pattern.quote(id) + ":[ \\t]*(#.*)?")) {
                gi = i;
                gIndent = indentOf(l);
                break;
            }
        }
        if (gi < 0) {
            return null;
        }
        int end = gi + 1;                                     // конец записи (не включительно)
        int lastBody = gi;
        String keyIndent = null;
        for (; end < lines.length; end++) {
            String l = lines[end];
            if (l.isBlank() || l.stripLeading().startsWith("#")) {
                continue;
            }
            if (indentOf(l) <= gIndent) {
                break;
            }
            if (keyIndent == null) {
                keyIndent = l.substring(0, indentOf(l));
            }
            lastBody = end;
        }
        if (keyIndent == null) {
            keyIndent = " ".repeat(gIndent + 2);
        }
        int ai = -1;
        for (int i = gi + 1; i <= lastBody; i++) {
            String l = lines[i];
            if (indentOf(l) == keyIndent.length() && l.substring(keyIndent.length()).matches("attachments:[ \\t]*(#.*)?")) {
                ai = i;
                break;
            }
        }
        StringBuilder ins = new StringBuilder();
        for (String l : att.split("\n")) {
            ins.append(l.isEmpty() ? "" : keyIndent + l).append('\n');
        }
        String insert = ins.toString();
        int from;
        int to;                                               // заменяются строки [from, to)
        if (ai >= 0) {
            from = ai;
            to = ai + 1;
            for (int i = ai + 1; i <= lastBody; i++) {
                String l = lines[i];
                if (l.isBlank() || l.stripLeading().startsWith("#")) {
                    continue;
                }
                if (indentOf(l) <= keyIndent.length()) {
                    break;
                }
                to = i + 1;
            }
        } else {
            from = lastBody + 1;
            to = lastBody + 1;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < from; i++) {
            out.append(lines[i]).append('\n');
        }
        out.append(insert);
        for (int i = to; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static int indentOf(String l) {
        int n = 0;
        while (n < l.length() && l.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    /**
     * Записи раздела guns из текста config.yml: id → текст записи без отступа ключа (строки на 4 пробела левее).
     * Комментарии перед ключом записи не берутся, внутри — да.
     */
    static Map<String, String> gunBlocks(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length && !lines[i].startsWith("guns:")) {
            i++;
        }
        String id = null;
        StringBuilder body = new StringBuilder();
        for (i++; i < lines.length; i++) {
            String l = lines[i];
            if (!l.isBlank() && !l.startsWith(" ") && !l.startsWith("#")) {
                break;                                        // следующий раздел верхнего уровня
            }
            Matcher m = GUN_KEY.matcher(l);
            if (m.matches()) {
                if (id != null) {
                    out.put(id, body.toString());
                }
                id = m.group(1);
                body.setLength(0);
                continue;
            }
            if (id != null && l.startsWith("    ")) {
                body.append(l.substring(4)).append('\n');
            }
        }
        if (id != null) {
            out.put(id, body.toString());
        }
        return out;
    }

    private static final Pattern GUN_KEY = Pattern.compile("^  ([A-Za-z0-9_\\-]+):[ \\t]*(#.*)?$");

    private static int copy(String rel, Path to) throws IOException {
        return copy(TACZ, rel, to);
    }

    private static int copy(String dir, String rel, Path to) throws IOException {
        byte[] b = resource(dir, rel);
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
                + (g.itemIcon() == null || g.itemIcon().isBlank() ? "" : "icon: " + g.itemIcon() + "\n")
                + "model: " + g.itemModel() + "\n"
                + "stack: 1\n"
                + "lore: [" + lore + "]\n";
    }

    static String ammoYaml(GunDef g) {
        return ammoYaml(g, List.of(g));
    }

    /** Патроны g; «Для:» — всё оружие из guns под этот калибр. */
    static String ammoYaml(GunDef g, Collection<GunDef> guns) {
        StringBuilder forGuns = new StringBuilder();
        for (GunDef o : guns) {
            if (o.ammoItem().equals(g.ammoItem())) {
                forGuns.append(forGuns.isEmpty() ? "" : ", ").append(o.name());
            }
        }
        return "name: " + q(g.ammoName()) + "\n"
                + "color: yellow\n"
                + "material: " + g.ammoMaterial() + "\n"
                + "icon: " + g.ammoIcon() + "\n"
                + "stack: 64\n"
                + "lore: [" + q("Для: " + forGuns)
                + (g.ammoIcon() != null && g.ammoIcon().startsWith("tacz/") ? ", " + q("Иконка: TACZ, CC BY-NC-ND 4.0") : "")
                + "]\n";
    }

    /** Предмет-обвес для hoitems: иконка TACZ, описание — вид обвеса и к какому оружию подходит. */
    static String attachmentYaml(Attachments.Def d, Collection<GunDef> guns, Attachments cat,
                                 Map<String, Attachments.GunAtt> gunAtt) {
        StringBuilder fits = new StringBuilder();
        for (GunDef g : guns) {
            Attachments.GunAtt ga = gunAtt.get(g.id());
            if (ga == null || ga.locked(d.slot())) {
                continue;
            }
            boolean ok = cat.allowed(ga, d, "");
            for (String a : ga.adapters()) {
                ok |= cat.allowed(ga, d, a);
            }
            if (ok) {
                fits.append(fits.isEmpty() ? "" : ", ").append(g.name());
            }
        }
        String own = ownAttIcon(d);
        String icon = own != null ? own : d.icon();
        return "name: " + q(d.name()) + "\n"
                + "color: aqua\n"
                + "material: paper\n"
                + (icon.isEmpty() ? "" : "icon: " + icon + "\n")
                + "stack: 1\n"
                + "lore: [" + q("Обвес: " + Attachments.slotRu(d.slot())) + ", "
                + q(fits.isEmpty() ? "Пока ни к чему не подходит" : "Подходит: " + fits) + ", "
                + q("Y с оружием в руке — поставить")
                + (icon.startsWith("tacz/") ? ", " + q("Иконка: TACZ, CC BY-NC-ND 4.0") : "") + "]\n";
    }

    /**
     * Иконка своего обвеса (не TACZ) для hoitems: icon вида guns/<файл>.png, файл есть в jar в hoka/textures —
     * путь hoka/<файл>.png (в hoitems — assets/hoka/). Иначе null.
     */
    static String ownAttIcon(Attachments.Def d) {
        String icon = d.icon();
        if (!icon.startsWith("guns/") || icon.startsWith("guns/att/")) {
            return null;
        }
        String name = icon.substring(5);
        return resource(HOKA, "textures/" + name) != null ? HOKA + "/" + name : null;
    }

    static String soundYaml(SoundEntry s) {
        return "files: [" + s.dir() + "/" + s.file() + "]\n"
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
