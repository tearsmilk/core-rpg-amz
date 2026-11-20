package dev.tearsmilk.textshader;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Choices {

    public enum Target {
        CHAT("chat", "Сообщения", List.of("msg", "message", "messages")),
        NICK("nick", "Ник", List.of("name", "tab"));

        private final String key;
        private final String title;
        private final List<String> aliases;

        Target(String key, String title, List<String> aliases) {
            this.key = key;
            this.title = title;
            this.aliases = aliases;
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
            if (s == null) {
                return null;
            }
            String k = s.toLowerCase(Locale.ROOT);
            for (Target t : values()) {
                if (t.key.equals(k) || t.aliases.contains(k)) {
                    return t;
                }
            }
            return null;
        }
    }

    public interface Store {
        String read(Target t);

        void write(Target t, String value);
    }

    private final Map<Target, NamespacedKey> keys = new EnumMap<>(Target.class);
    private final Map<Target, Map<UUID, FxSpec>> maps = new EnumMap<>(Target.class);

    public Choices(String namespace) {
        for (Target t : Target.values()) {
            keys.put(t, new NamespacedKey(namespace.toLowerCase(Locale.ROOT), t.key()));
            maps.put(t, new ConcurrentHashMap<>());
        }
    }

    public NamespacedKey key(Target t) {
        return keys.get(t);
    }

    public FxSpec get(UUID id, Target t) {
        return maps.get(t).get(id);
    }

    public void load(UUID id, Store store) {
        for (Target t : Target.values()) {
            FxSpec spec = FxSpec.parse(store.read(t));
            if (spec == null) {
                maps.get(t).remove(id);
            } else {
                maps.get(t).put(id, spec);
            }
        }
    }

    public void set(UUID id, Store store, Target t, FxSpec spec) {
        if (spec == null) {
            store.write(t, null);
            maps.get(t).remove(id);
        } else {
            store.write(t, spec.serialize());
            maps.get(t).put(id, spec);
        }
    }

    public void forget(UUID id) {
        for (Map<UUID, FxSpec> m : maps.values()) {
            m.remove(id);
        }
    }

    public Store store(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        return new Store() {
            @Override
            public String read(Target t) {
                return pdc.get(keys.get(t), PersistentDataType.STRING);
            }

            @Override
            public void write(Target t, String value) {
                if (value == null) {
                    pdc.remove(keys.get(t));
                } else {
                    pdc.set(keys.get(t), PersistentDataType.STRING, value);
                }
            }
        };
    }

    public void load(Player p) {
        load(p.getUniqueId(), store(p));
    }

    public void set(Player p, Target t, FxSpec spec) {
        set(p.getUniqueId(), store(p), t, spec);
    }
}
