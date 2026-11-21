package dev.tearsmilk.aurahud.layout;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class HudSettings {

    private final Map<Element, ElementSettings> elements;
    private final List<String> warnings;

    private HudSettings(Map<Element, ElementSettings> elements, List<String> warnings) {
        this.elements = elements;
        this.warnings = Collections.unmodifiableList(warnings);
    }

    public static HudSettings vanilla() {
        return read(null);
    }

    public static HudSettings read(ConfigurationSection hud) {
        Map<Element, ElementSettings> map = new EnumMap<>(Element.class);
        List<String> warnings = new ArrayList<>();
        if (hud != null) {
            for (String key : hud.getKeys(false)) {
                if (Element.parse(key) == null || !Element.parse(key).id().equals(key)) {
                    warnings.add("hud." + key + ": неизвестный элемент, пропущен");
                }
            }
        }
        for (Element e : Element.values()) {
            ConfigurationSection sec = hud == null ? null : hud.getConfigurationSection(e.id());
            map.put(e, ElementSettings.read(e, sec, warnings));
        }
        return new HudSettings(map, warnings);
    }

    public ElementSettings get(Element e) {
        return elements.get(e);
    }

    public List<String> warnings() {
        return warnings;
    }
}
