package dev.tearsmilk.nameshader;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Set;

public record Settings(boolean styleTextDisplays, String displayTag, int testSeconds, String testName,
                       String testDisplayText, double testDistance) {

    public static Settings from(ConfigurationSection c) {
        boolean style = c.getBoolean("nametags.style-text-displays", false);
        String tag = c.getString("nametags.text-display-tag", "");
        int seconds = Math.max(5, Math.min(600, c.getInt("test.seconds", 30)));
        String name = c.getString("test.name", "<white>Тестовый ник");
        String display = c.getString("test.display-text", "<white>Текстовый дисплей");
        double distance = Math.max(1.5, Math.min(10.0, c.getDouble("test.distance", 3.0)));
        return new Settings(style, tag == null ? "" : tag.trim(), seconds, name, display, distance);
    }

    public boolean tagAllowed(Set<String> tags) {
        return displayTag.isEmpty() || tags.contains(displayTag);
    }
}
