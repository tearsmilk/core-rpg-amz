package core.amazake.systems.corerpg.progression.menu;

import core.amazake.systems.corerpg.aspect.AspectSnapshot;
import core.amazake.systems.corerpg.profile.PlayerProfile;
import core.amazake.systems.corerpg.progression.ProgressionModule;
import core.amazake.systems.corerpg.progression.StatFormulas;
import core.amazake.systems.corerpg.progression.StatSettings;
import core.amazake.systems.corerpg.progression.StatType;
import core.amazake.systems.corerpg.util.Text;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/**
 * Меню параметров (27 слотов): ЛКМ +1, Shift+ЛКМ +10. Очки вкладываются только вверх (сброс — у админа). Лор
 * показывает, что даёт параметр и сколько даёт сейчас.
 */
public final class StatsMenu extends ProgressionMenu {

    private static final int SIZE = 27;
    private static final int PRIMARY = 10;
    private static final int AGILITY = 12;
    private static final int STAMINA = 14;
    private static final int LUCK = 16;
    private static final int BACK = 18;
    private static final int FREE = 22;
    private static final int CLOSE = 26;

    public StatsMenu(final ProgressionModule module) {
        super(module, SIZE, "menu-stats-title");
    }

    @Override
    public void render(final Player player) {
        this.frame(SIZE);
        final var inv = this.getInventory();
        final PlayerProfile profile = this.module.profile(player);
        final AspectSnapshot aspects = this.module.aspects(player);
        final StatSettings s = this.module.settings().stats();
        final String primaryName = this.module.primaryStatName(profile.classId());

        inv.setItem(PRIMARY, this.statItem(StatType.PRIMARY, primaryName, Material.IRON_SWORD, profile.primary(),
            aspects.get(StatType.PRIMARY.aspect()), profile, s, List.of(
                "<gray>+" + Text.num(s.primaryDamagePerPoint()) + " к урону за очко, +" + Text.num(s.primaryDamagePer10())
                    + " за каждые 10</gray>",
                "<gray>+1 к попаданию за каждые " + Text.num(s.primaryAccuracyDivisor()) + " очков</gray>")));
        inv.setItem(AGILITY, this.statItem(StatType.AGILITY, "Ловкость", Material.FEATHER, profile.agility(),
            aspects.get(StatType.AGILITY.aspect()), profile, s, List.of(
                "<gray>+" + Text.num(s.agilitySpeedPerPoint()) + " к скорости атаки за очко, +"
                    + Text.num(s.agilitySpeedPer10()) + " за каждые 10</gray>",
                "<gray>+" + Text.num(s.agilityEvasionPer10()) + " к увороту за каждые 10</gray>")));
        inv.setItem(STAMINA, this.statItem(StatType.STAMINA, "Выносливость", Material.GOLDEN_APPLE, profile.stamina(),
            aspects.get(StatType.STAMINA.aspect()), profile, s, List.of(
                "<gray>+" + Text.num(s.staminaHealthPerPoint()) + " к здоровью за очко (2 = одно сердце)</gray>")));
        inv.setItem(LUCK, this.statItem(StatType.LUCK, "Удача", Material.EMERALD, profile.luck(),
            aspects.get(StatType.LUCK.aspect()), profile, s, List.of(
                "<gray>Рейтинг крита: " + Text.num(s.luckRatingPerPoint()) + " за очко, +" + Text.num(s.luckRatingPer10())
                    + " за каждые 10</gray>",
                "<gray>Шанс крита растёт от рейтинга и падает с уровнем (до " + Text.num(s.critChanceCap()) + "%)</gray>",
                "<gray>+" + Text.num(s.luckAccuracyPer10()) + " к попаданию и +" + Text.num(s.critDamagePer10())
                    + "% к силе крита за каждые 10</gray>")));

        inv.setItem(FREE, MenuItems.item(Material.EXPERIENCE_BOTTLE,
            "<yellow><b>Свободные очки: " + profile.freePoints() + "</b></yellow>", List.of(
                "<gray>Каждые " + this.module.settings().pointsEveryLevels() + " уровней = +"
                    + this.module.settings().pointsPerStep() + " очка</gray>",
                "<gray>Вложено всего: " + profile.spentPoints() + "</gray>",
                "<gray>Базовая сила крита у всех: " + Text.num(s.critDamageBase()) + "%</gray>")));
        inv.setItem(BACK, MenuItems.item(Material.ARROW, "<yellow>Назад к персонажу</yellow>", List.of()));
        inv.setItem(CLOSE, MenuItems.item(Material.BARRIER, "<red>Закрыть</red>", List.of()));
    }

    private org.bukkit.inventory.ItemStack statItem(final StatType type, final String name, final Material icon,
                                                    final int invested, final double bonus, final PlayerProfile profile,
                                                    final StatSettings s, final List<String> rules) {
        final double effective = invested + bonus;
        final StatFormulas.Bonuses now = StatFormulas.of(s, type, effective, profile.level());
        final List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("<gray>Вложено: <white>" + invested + "</white>"
            + (bonus != 0.0 ? "  ·  от предметов: <white>" + (bonus > 0 ? "+" : "") + Text.num(bonus) + "</white>" : "")
            + "</gray>");
        lore.add("");
        lore.add("<gold>Что даёт:</gold>");
        lore.addAll(rules);
        lore.add("");
        lore.add("<gold>Сейчас даёт:</gold>");
        final List<String> current = describe(now);
        if (current.isEmpty()) {
            lore.add("<gray>пока ничего</gray>");
        } else {
            lore.addAll(current);
        }
        lore.add("");
        if (profile.freePoints() > 0) {
            lore.add("<green>ЛКМ — +1</green>");
            lore.add("<green>Shift+ЛКМ — +10</green>");
        } else {
            lore.add("<red>Нет свободных очков</red>");
        }
        return MenuItems.item(icon, "<aqua><b>" + Text.escape(name) + ": " + Text.num(effective) + "</b></aqua>", lore);
    }

    static List<String> describe(final StatFormulas.Bonuses b) {
        final List<String> out = new ArrayList<>();
        add(out, "Урон", b.attackDamage(), "");
        add(out, "Попадание", b.accuracy(), "");
        add(out, "Здоровье", b.maxHealth(), "");
        add(out, "Скорость атаки", b.attackSpeed(), "");
        add(out, "Уворот", b.evasion(), "");
        add(out, "Шанс крита", b.critChance(), "%");
        add(out, "Сила крита", b.critDamage(), "%");
        return out;
    }

    private static void add(final List<String> out, final String label, final double value, final String unit) {
        if (value != 0.0) {
            out.add("<gray>• " + label + ": <green>+" + Text.num(value) + unit + "</green></gray>");
        }
    }

    @Override
    public void click(final Player player, final int slot, final ClickType click) {
        final StatType type = switch (slot) {
            case PRIMARY -> StatType.PRIMARY;
            case AGILITY -> StatType.AGILITY;
            case STAMINA -> StatType.STAMINA;
            case LUCK -> StatType.LUCK;
            default -> null;
        };
        if (slot == CLOSE) {
            this.module.later(player, 1L, player::closeInventory);
            return;
        }
        if (slot == BACK) {
            this.module.later(player, 1L, () -> this.module.openCharacterMenu(player));
            return;
        }
        if (type == null || !click.isLeftClick()) {
            return;
        }
        final int amount = click.isShiftClick() ? 10 : 1;
        final int added = this.module.addStat(player, type, amount, false);
        if (added > 0) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
        } else {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
        }
        this.render(player);
    }
}
