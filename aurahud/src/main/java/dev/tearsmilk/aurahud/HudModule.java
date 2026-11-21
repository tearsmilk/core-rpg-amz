package dev.tearsmilk.aurahud;

import java.util.List;
import java.util.Map;

public interface HudModule {

    String id();

    default void enable(AuraHudPlugin plugin) {
    }

    default void reload(AuraHudPlugin plugin) {
    }

    default void disable() {
    }

    default Map<String, byte[]> packFiles(PackTarget target) {
        return Map.of();
    }

    default List<String> status() {
        return List.of();
    }
}
