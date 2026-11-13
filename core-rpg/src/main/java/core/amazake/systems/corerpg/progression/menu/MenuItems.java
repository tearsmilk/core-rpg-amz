package core.amazake.systems.corerpg.progression.menu;

import core.amazake.systems.corerpg.aspect.Aspect;
import core.amazake.systems.corerpg.progression.Bonus;
import core.amazake.systems.corerpg.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Предметы меню и подписи бонусов. */
public final class MenuItems {

    private static final Map<String, String> TARGETS = Map.ofEntries(
        Map.entry("fire", "огонь"), Map.entry("chaos", "хаос"), Map.entry("undead", "нежить"),
        Map.entry("humanoid", "гуманоиды"), Map.entry("water", "вода"), Map.entry("air", "воздух"),
        Map.entry("earth", "земля"), Map.entry("poison", "яд"), Map.entry("animal", "звери"),
        Map.entry("arthropod", "членистоногие"), Map.entry("monster", "монстры"));

    private static final Map<String, String> EFFECTS = Map.ofEntries(
        Map.entry("night_vision", "Ночное зрение"), Map.entry("regeneration", "Регенерация"),
        Map.entry("speed", "Скорость"), Map.entry("strength", "Сила"), Map.entry("fire_resistance", "Огнестойкость"),
        Map.entry("haste", "Спешка"), Map.entry("resistance", "Сопротивление"), Map.entry("jump_boost", "Прыгучесть"),
        Map.entry("water_breathing", "Подводное дыхание"), Map.entry("invisibility", "Невидимость"),
        Map.entry("saturation", "Насыщение"), Map.entry("luck", "Удача"), Map.entry("slow_falling", "Плавное падение"),
        Map.entry("absorption", "Поглощение"), Map.entry("health_boost", "Прилив здоровья"),
        Map.entry("dolphins_grace", "Грация дельфина"), Map.entry("conduit_power", "Сила источника"),
        Map.entry("hero_of_the_village", "Герой деревни"));

    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private MenuItems() {
    }

    /** Кнопка: название и лор в MiniMessage, без курсива, без лишних подсказок предмета. */
    public static ItemStack item(final Material material, final String name, final List<String> lore,
                                 final TagResolver... resolvers) {
        final ItemStack item = new ItemStack(material);
        final ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.item(name, resolvers));
            final List<Component> lines = new ArrayList<>(lore.size());
            for (final String line : lore) {
                lines.add(Text.item(line, resolvers));
            }
            meta.lore(lines);
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Пустое стекло фона. */
    public static ItemStack filler(final Material material) {
        final ItemStack item = new ItemStack(material);
        final ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setHideTooltip(true);
            meta.displayName(Component.text(" "));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** «• Здоровье: +4», «• Эффект: Ночное зрение I (постоянно)», «• Право: …». */
    public static String describe(final Bonus bonus) {
        if (bonus instanceof Bonus.AspectBonus a) {
            return "<gray>• " + aspectLabel(a.key()) + ": <green>" + aspectValue(a.key(), a.value()) + "</green></gray>";
        }
        if (bonus instanceof Bonus.EffectBonus e) {
            final String name = EFFECTS.getOrDefault(e.effectKey(), e.effectKey());
            final String level = e.amplifier() < ROMAN.length ? ROMAN[e.amplifier()] : String.valueOf(e.amplifier() + 1);
            final String time = e.permanent() ? "постоянно" : e.durationSeconds() + " с при выборе";
            return "<gray>• Эффект: <aqua>" + Text.escape(name) + " " + level + "</aqua> (" + time + ")</gray>";
        }
        if (bonus instanceof Bonus.PermissionBonus p) {
            return "<gray>• Право: <dark_gray>" + Text.escape(p.node()) + "</dark_gray></gray>";
        }
        return "";
    }

    /** Подпись аспекта по ключу, включая целевые. */
    public static String aspectLabel(final String key) {
        if (key.startsWith("resistance:")) {
            final String t = key.substring("resistance:".length());
            return "Сопротивление (" + Text.escape(TARGETS.getOrDefault(t, t)) + ")";
        }
        if (key.startsWith("damage_vs:")) {
            final String t = key.substring("damage_vs:".length());
            return "Урон по целям (" + Text.escape(TARGETS.getOrDefault(t, t)) + ")";
        }
        final Aspect aspect = Aspect.byKey(key);
        return aspect == null ? Text.escape(key) : aspect.label();
    }

    /** Значение со знаком и единицей: доли сопротивления — в %, проценты — с %. */
    public static String aspectValue(final String key, final double value) {
        final String sign = value >= 0 ? "+" : "";
        final String k = key.toLowerCase(Locale.ROOT);
        if (k.equals("resistance") || k.equals("resistance_pvp") || k.startsWith("resistance:")) {
            return sign + Text.num(value * 100.0) + "%";
        }
        if (k.equals("crit_chance") || k.equals("crit_damage") || k.equals("lifesteal") || k.equals("exp_bonus")
            || k.equals("gold_bonus") || k.startsWith("status_") || k.startsWith("damage_vs:")) {
            return sign + Text.num(value) + "%";
        }
        return sign + Text.num(value);
    }

    /** Название пассивки, которую бой не описал (см. ProgressionModule#passiveLabel). */
    public static String passiveLabel(final String key) {
        return switch (key) {
            case "rage" -> "Ярость: +2% урона за каждые 5% потерянного здоровья";
            case "headshot" -> "Выстрел в голову: ×1.5 урона";
            case "backstab" -> "Удар в спину всегда критический";
            case "dual_wield" -> "Два клинка: +60% атаки второй руки";
            default -> Text.escape(key);
        };
    }
}
