package ink.hoka.odyssey.coreshaders;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Хранение эффектов карты: module/coreshaders/effects/&lt;карта&gt;.json. В Minestom сущности не сохраняются вместе с
 * миром, поэтому эффекты живут здесь, а ItemDisplay создаются заново при загрузке чанка. Кодирование — в потоке сервера
 * (снимок), запись файла — в async.
 */
final class Storage {
    static final int VERSION = 1;

    record Loaded(List<Effect> effects, List<String> errors) {
    }

    private Storage() {
    }

    static String encode(Collection<Effect> effects) {
        JsonArray arr = new JsonArray();
        for (Effect e : effects) {
            EffectData d = e.data;
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id.toString());
            o.addProperty("shape", d.shape().id());
            o.addProperty("image", d.image());
            o.addProperty("color", Colors.hex(d.color()));
            o.addProperty("face", d.face().id());
            o.addProperty("x", d.bx());
            o.addProperty("y", d.by());
            o.addProperty("z", d.bz());
            o.addProperty("rim", d.rim());
            if (e.actions != null) {
                Actions.Data a = e.actions;
                JsonObject ao = new JsonObject();
                JsonArray cmds = new JsonArray();
                for (Actions.Action act : a.actions()) {
                    cmds.add(act.encode());
                }
                ao.add("commands", cmds);
                ao.addProperty("trigger", a.trigger().id());
                ao.addProperty("cooldown", a.cooldown());
                if (a.permission() != null) {
                    ao.addProperty("permission", a.permission());
                }
                if (a.message() != null) {
                    ao.addProperty("message", a.message());
                }
                ao.addProperty("group", a.group());
                o.add("actions", ao);
            }
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.add("effects", arr);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
    }

    static Loaded decode(String json, Settings s) {
        List<Effect> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("effects")) {
            errors.add("нет списка effects");
            return new Loaded(out, errors);
        }
        int i = 0;
        for (JsonElement el : root.getAsJsonObject().getAsJsonArray("effects")) {
            i++;
            try {
                JsonObject o = el.getAsJsonObject();
                UUID id = o.has("id") ? UUID.fromString(o.get("id").getAsString()) : UUID.randomUUID();
                Shape shape = Shape.parse(str(o, "shape"));
                Dir face = Dir.parse(str(o, "face"));
                Integer color = Colors.parse(str(o, "color"), null);
                String image = str(o, "image");
                if (image == null || (!s.images.contains(image) && !Settings.DEFAULT_IMAGES.contains(image))) {
                    image = s.defaultImage;
                }
                EffectData d = new EffectData(shape == null ? Shape.CUBE : shape, image, color == null ? 0xFFFFFF : color,
                    face == null ? Dir.UP : face, o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                    o.has("rim") ? o.get("rim").getAsBoolean() : s.rimDefault);
                Actions.Data a = null;
                if (o.has("actions") && o.get("actions").isJsonObject()) {
                    JsonObject ao = o.getAsJsonObject("actions");
                    List<Actions.Action> list = new ArrayList<>();
                    if (ao.has("commands")) {
                        for (JsonElement c : ao.getAsJsonArray("commands")) {
                            Actions.Action act = Actions.Action.decode(c.getAsString());
                            if (act != null) {
                                list.add(act);
                            }
                        }
                    }
                    Actions.Trigger t = Actions.Trigger.parse(str(ao, "trigger"));
                    double cd = ao.has("cooldown") ? ao.get("cooldown").getAsDouble() : s.defaultCooldown;
                    String group = str(ao, "group");
                    a = new Actions.Data(list, t == null ? s.defaultTrigger : t, cd, str(ao, "permission"),
                        str(ao, "message"), group == null ? id.toString() : group);
                }
                out.add(new Effect(id, d, a));
            } catch (RuntimeException ex) {
                errors.add("эффект №" + i + ": " + ex.getMessage());
            }
        }
        return new Loaded(out, errors);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    static void write(Path file, String json) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
