package core.amazake.systems.coremobs.mob;

import core.amazake.systems.coremobs.util.IntRange;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffectType;

/**
 * Моб из mobs.yml. Числа {@code null} — оставить как у ванильного моба.
 *
 * @param exp опыт за убийство; null — ванильный (его, как и любой ванильный опыт, core-rpg переводит в свой)
 * @param model модель ModelEngine; null — без модели
 */
public record MobDef(
    String id,
    EntityType type,
    String name,
    boolean showName,
    boolean showHealth,
    double health,
    Double damage,
    Double speed,
    Double armor,
    Double followRange,
    Double knockbackResistance,
    Double scale,
    Double projectileDamage,
    boolean baby,
    boolean glowing,
    boolean silent,
    boolean preventSunburn,
    boolean vanillaDrops,
    boolean dropEquipment,
    IntRange exp,
    Map<EquipmentSlot, Material> equipment,
    List<Effect> effects,
    List<Effect> onHitEffects,
    int size,
    boolean noFire,
    Model model
) {

    /**
     * Модель ModelEngine поверх моба.
     *
     * @param id id модели (blueprint) ModelEngine
     * @param scale масштаб модели (1 — как в blueprint)
     * @param hitboxScale масштаб хитбокса модели (по умолчанию = scale; 1 — как в blueprint)
     * @param hideBase прятать ванильного моба под моделью
     * @param attack анимация атаки (выстрел и удар); null — не проигрывать
     * @param hit анимация получения урона; null — не проигрывать
     */
    public record Model(String id, double scale, double hitboxScale, boolean hideBase, String attack, String hit) {

        /** Хитбокс в том же масштабе, что и модель. */
        public Model(final String id, final double scale, final boolean hideBase, final String attack, final String hit) {
            this(id, scale, scale, hideBase, attack, hit);
        }
    }

    /**
     * Эффект зелья. Для постоянных эффектов длительность бесконечная, шанс не используется.
     *
     * @param amplifier уровень − 1, как в Bukkit
     * @param chance шанс 0..100 (для эффектов при ударе)
     */
    public record Effect(PotionEffectType type, int amplifier, int durationTicks, double chance) {
    }
}
