package dev.tearsmilk.aurahud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModuleRegistry {

    private final Map<String, HudModule> modules = new LinkedHashMap<>();

    public void register(HudModule module) {
        if (modules.putIfAbsent(module.id(), module) != null) {
            throw new IllegalArgumentException("duplicate module " + module.id());
        }
    }

    public List<HudModule> all() {
        return Collections.unmodifiableList(new ArrayList<>(modules.values()));
    }

    public <T extends HudModule> T get(Class<T> type) {
        for (HudModule m : modules.values()) {
            if (type.isInstance(m)) {
                return type.cast(m);
            }
        }
        return null;
    }

    public Map<String, byte[]> packFiles(PackTarget target) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (HudModule m : modules.values()) {
            out.putAll(m.packFiles(target));
        }
        return out;
    }

    public void enableAll(AuraHudPlugin plugin) {
        for (HudModule m : modules.values()) {
            m.enable(plugin);
        }
    }

    public void reloadAll(AuraHudPlugin plugin) {
        for (HudModule m : modules.values()) {
            m.reload(plugin);
        }
    }

    public void disableAll() {
        List<HudModule> list = new ArrayList<>(modules.values());
        Collections.reverse(list);
        for (HudModule m : list) {
            m.disable();
        }
    }
}
