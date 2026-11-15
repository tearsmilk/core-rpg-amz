package core.amazake.systems.corenpc.npc;

import core.amazake.systems.corenpc.dialog.Action;
import java.util.List;
import java.util.Locale;

/**
 * NPC, как он записан в npcs.yml. Неизменяемый: любое изменение — новый объект (with…), его кладут в карту целиком.
 *
 * @param type        тип сущности (mannequin — «игрок» со скином; любой живой моб)
 * @param name        имя (MiniMessage); пусто — без имени
 * @param hologram    name — имя над головой (custom name), display — TextDisplay над NPC (name + lines), none
 * @param lines       доп. строки TextDisplay (hologram: display)
 * @param skin        скин манекена (null — стандартный)
 * @param model       модель ModelEngine (null — нет)
 * @param commands    команды при клике (player:/console:/message:/sound:)
 * @param cooldown    секунд между кликами одного игрока (&lt;0 — из config.yml)
 * @param lookRadius  блоков для «смотреть на ближайшего» (&lt;0 — из config.yml)
 */
public record NpcDef(String id, String type, String world, double x, double y, double z, float yaw, float pitch,
                     String name, String hologram, List<String> lines, Skin skin, ModelSpec model, String dialog,
                     List<Action> commands, String permission, double cooldown, boolean leftClick, boolean lookAt,
                     double lookRadius, boolean gravity, boolean collidable, double scale) {

    public NpcDef {
        type = type == null ? "mannequin" : type.toLowerCase(Locale.ROOT);
        lines = lines == null ? List.of() : List.copyOf(lines);
        commands = commands == null ? List.of() : List.copyOf(commands);
        name = name == null ? "" : name;
        hologram = hologram == null ? "name" : hologram.toLowerCase(Locale.ROOT);
        dialog = dialog == null ? "" : dialog;
        permission = permission == null ? "" : permission;
    }

    /** Скин: имя (для профиля и памяти, откуда он) и текстура с подписью Mojang. */
    public record Skin(String name, String value, String signature) {
    }

    /** Модель ModelEngine: id, масштаб, анимация по умолчанию (зациклена в blueprint) и при клике. */
    public record ModelSpec(String id, double scale, String idle, String interact) {
    }

    public static NpcDef create(final String id, final String type, final String world, final double x, final double y,
                                final double z, final float yaw, final float pitch) {
        return new NpcDef(id, type, world, x, y, z, yaw, pitch, "<white>" + id, "name", List.of(), null, null, "",
            List.of(), "", -1, false, true, -1, false, false, 1.0);
    }

    public boolean mannequin() {
        return this.type.equals("mannequin");
    }

    public long chunkKey() {
        return chunkKey((int) Math.floor(this.x) >> 4, (int) Math.floor(this.z) >> 4);
    }

    public static long chunkKey(final int cx, final int cz) {
        return ((long) cx & 0xffffffffL) | (((long) cz & 0xffffffffL) << 32);
    }

    public NpcDef withLocation(final String w, final double nx, final double ny, final double nz, final float nyaw,
                               final float npitch) {
        return new NpcDef(this.id, this.type, w, nx, ny, nz, nyaw, npitch, this.name, this.hologram, this.lines, this.skin,
            this.model, this.dialog, this.commands, this.permission, this.cooldown, this.leftClick, this.lookAt,
            this.lookRadius, this.gravity, this.collidable, this.scale);
    }

    public NpcDef withName(final String v) {
        return new NpcDef(this.id, this.type, this.world, this.x, this.y, this.z, this.yaw, this.pitch, v, this.hologram,
            this.lines, this.skin, this.model, this.dialog, this.commands, this.permission, this.cooldown, this.leftClick,
            this.lookAt, this.lookRadius, this.gravity, this.collidable, this.scale);
    }

    public NpcDef withType(final String v) {
        return new NpcDef(this.id, v, this.world, this.x, this.y, this.z, this.yaw, this.pitch, this.name, this.hologram,
            this.lines, this.skin, this.model, this.dialog, this.commands, this.permission, this.cooldown, this.leftClick,
            this.lookAt, this.lookRadius, this.gravity, this.collidable, this.scale);
    }

    public NpcDef withSkin(final Skin v) {
        return new NpcDef(this.id, this.type, this.world, this.x, this.y, this.z, this.yaw, this.pitch, this.name,
            this.hologram, this.lines, v, this.model, this.dialog, this.commands, this.permission, this.cooldown,
            this.leftClick, this.lookAt, this.lookRadius, this.gravity, this.collidable, this.scale);
    }

    public NpcDef withModel(final ModelSpec v) {
        return new NpcDef(this.id, this.type, this.world, this.x, this.y, this.z, this.yaw, this.pitch, this.name,
            this.hologram, this.lines, this.skin, v, this.dialog, this.commands, this.permission, this.cooldown,
            this.leftClick, this.lookAt, this.lookRadius, this.gravity, this.collidable, this.scale);
    }

    public NpcDef withDialog(final String v) {
        return new NpcDef(this.id, this.type, this.world, this.x, this.y, this.z, this.yaw, this.pitch, this.name,
            this.hologram, this.lines, this.skin, this.model, v, this.commands, this.permission, this.cooldown,
            this.leftClick, this.lookAt, this.lookRadius, this.gravity, this.collidable, this.scale);
    }

    public NpcDef withCommands(final List<Action> v) {
        return new NpcDef(this.id, this.type, this.world, this.x, this.y, this.z, this.yaw, this.pitch, this.name,
            this.hologram, this.lines, this.skin, this.model, this.dialog, v, this.permission, this.cooldown,
            this.leftClick, this.lookAt, this.lookRadius, this.gravity, this.collidable, this.scale);
    }

    /** Поменять одну настройку из /npc set (значение уже разобрано); null — нет такой настройки. */
    public NpcDef withOption(final String key, final Object v) {
        final String hologramV = key.equals("hologram") ? (String) v : this.hologram;
        final List<String> linesV = key.equals("lines") ? castList(v) : this.lines;
        final String permV = key.equals("permission") ? (String) v : this.permission;
        final double cdV = key.equals("cooldown") ? (Double) v : this.cooldown;
        final boolean leftV = key.equals("left-click") ? (Boolean) v : this.leftClick;
        final boolean lookV = key.equals("look-at") ? (Boolean) v : this.lookAt;
        final double radiusV = key.equals("look-radius") ? (Double) v : this.lookRadius;
        final boolean gravV = key.equals("gravity") ? (Boolean) v : this.gravity;
        final boolean collV = key.equals("collidable") ? (Boolean) v : this.collidable;
        final double scaleV = key.equals("scale") ? (Double) v : this.scale;
        return new NpcDef(this.id, this.type, this.world, this.x, this.y, this.z, this.yaw, this.pitch, this.name,
            hologramV, linesV, this.skin, this.model, this.dialog, this.commands, permV, cdV, leftV, lookV, radiusV,
            gravV, collV, scaleV);
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(final Object v) {
        return (List<String>) v;
    }
}
