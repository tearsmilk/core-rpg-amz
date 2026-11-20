package dev.tearsmilk.textshader;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Choices {

    public enum Target {
        CHAT("chat", "Чат"),
        NICK("nick", "Ник");

        private final String key;
        private final String title;

        Target(String key, String title) {
            this.key = key;
            this.title = title;
        }

        public String key() {
            return key;
        }

        public String title() {
            return title;
        }

        public Target other() {
            return this == CHAT ? NICK : CHAT;
        }

        public static Target byKey(String s) {
            for (Target t : values()) {
                if (t.key.equalsIgnoreCase(s)) {
                    return t;
                }
            }
            return null;
        }
    }

    private final Map<Target, NamespacedKey> keys = new EnumMap<>(Target.class);
    private final Map<UUID, FxSpec> chat = new ConcurrentHashMap<>();
    private final Map<UUID, FxSpec> nick = new ConcurrentHashMap<>();

    public Choices(Plugin plugin) {
        for (Target t : Target.values()) {
            keys.put(t, new NamespacedKey(plugin, t.key()));
        }
    }

    private Map<UUID, FxSpec> map(Target t) {
        return t == Target.CHAT ? chat : nick;
    }

    public FxSpec get(UUID id, Target t) {
        return map(t).get(id);
    }

    public void load(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        for (Target t : Target.values()) {
            FxSpec spec = FxSpec.parse(pdc.get(keys.get(t), PersistentDataType.STRING));
            if (spec == null) {
                map(t).remove(p.getUniqueId());
            } else {
                map(t).put(p.getUniqueId(), spec);
            }
        }
    }

    public void forget(UUID id) {
        chat.remove(id);
        nick.remove(id);
    }

    public void set(Player p, Target t, FxSpec spec) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (spec == null) {
            pdc.remove(keys.get(t));
            map(t).remove(p.getUniqueId());
        } else {
            pdc.set(keys.get(t), PersistentDataType.STRING, spec.serialize());
            map(t).put(p.getUniqueId(), spec);
        }
    }
}
