package core.amazake.systems.coremechanics.skin;

import org.bukkit.Material;

/** Цвета тела куклы, пока нет кубиков скина: рубашка, рукав, кисть, штаны, обувь. */
public record BodyColors(Material shirt, Material sleeve, Material hand, Material pants, Material shoes) {

    /** Стив — пока скин не скачан (или его нет: офлайн-режим). */
    public static final BodyColors STEVE = new BodyColors(Material.CYAN_TERRACOTTA, Material.CYAN_CONCRETE, Material.WHITE_TERRACOTTA,
        Material.BLUE_CONCRETE, Material.GRAY_CONCRETE);
}
