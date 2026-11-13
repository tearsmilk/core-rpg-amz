package core.amazake.systems.corerpg.progression.menu;

import core.amazake.systems.corerpg.aspect.Aspect;
import core.amazake.systems.corerpg.aspect.AspectSnapshot;
import core.amazake.systems.corerpg.profile.PlayerProfile;
import core.amazake.systems.corerpg.progression.ClassCatalog;
import core.amazake.systems.corerpg.progression.Leveling;
import core.amazake.systems.corerpg.progression.ProgressionModule;
import core.amazake.systems.corerpg.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/** Меню персонажа (27 слотов): класс, профессия, уровень, опыт, параметры, характеристики. */
public final class CharacterMenu extends ProgressionMenu {

    private static final int SIZE = 27;
    private static final int CLASS = 11;
    private static final int PROFESSION = 13;
    private static final int LEVEL = 15;
    private static final int STATS = 20;
    private static final int EXP = 22;
    private static final int ASPECTS = 24;
    private static final int CLOSE = 26;

    public CharacterMenu(final ProgressionModule module) {
        super(module, SIZE, "menu-character-title");
    }

    @Override
    public void render(final Player player) {
        this.frame(SIZE);
        final var inv = this.getInventory();
        inv.setItem(9, MenuItems.filler(Material.YELLOW_STAINED_GLASS_PANE));
        inv.setItem(17, MenuItems.filler(Material.YELLOW_STAINED_GLASS_PANE));

        final PlayerProfile profile = this.module.profile(player);
        final ClassCatalog catalog = this.module.catalog();
        final AspectSnapshot aspects = this.module.aspects(player);
        final int required = this.module.professionLevel();

        // класс
        final ClassCatalog.RpgClass clazz = catalog.clazz(profile.classId());
        final List<String> classLore = new ArrayList<>();
        classLore.add("");
        if (clazz != null) {
            for (final String line : clazz.description()) {
                classLore.add("<gray>" + line + "</gray>");
            }
            classLore.add("");
            classLore.add("<yellow>Главный параметр: <white>" + Text.escape(clazz.primaryStat()) + "</white></yellow>");
            classLore.add("<gold>Профессии:</gold>");
            for (final var p : catalog.professionsOf(clazz.key())) {
                classLore.add("<yellow> • " + p.displayName() + "</yellow>");
            }
        } else {
            classLore.add("<aqua>Нажмите, чтобы выбрать класс</aqua>");
            classLore.add("<gray>или введите /class</gray>");
        }
        inv.setItem(CLASS, MenuItems.item(clazz != null ? clazz.icon() : Material.IRON_SWORD,
            "<aqua><b>⚔ Класс: " + (clazz != null ? clazz.displayName() : "не выбран") + "</b></aqua>", classLore));

        // профессия
        final ClassCatalog.RpgProfession profession = catalog.profession(profile.professionId());
        final List<String> profLore = new ArrayList<>();
        profLore.add("");
        if (profession != null) {
            for (final String line : profession.description()) {
                profLore.add("<gray>" + line + "</gray>");
            }
            if (!profession.lockedAspects().isEmpty()) {
                final List<String> names = new ArrayList<>();
                for (final Aspect a : profession.lockedAspects()) {
                    names.add(a.label());
                }
                profLore.add("<red>Закрыт аспект: " + String.join(", ", names) + "</red>");
            }
        } else if (clazz == null) {
            profLore.add("<red>Сначала выберите класс</red>");
        } else if (profile.level() >= required) {
            profLore.add("<green>Доступно! Нажмите или введите /profession</green>");
        } else {
            profLore.add("<yellow>Доступно с " + required + " уровня</yellow>");
            profLore.add("<gray>Сейчас: " + profile.level() + " ур.</gray>");
        }
        inv.setItem(PROFESSION, MenuItems.item(profession != null ? profession.icon() : Material.BARRIER,
            "<light_purple><b>★ Профессия: " + (profession != null ? profession.displayName() : "не выбрана") + "</b></light_purple>",
            profLore));

        // уровень
        final List<String> levelLore = new ArrayList<>();
        levelLore.add("");
        levelLore.add("<gray>Максимальный уровень: " + this.module.maxLevel() + "</gray>");
        levelLore.add("<gray>Свободных очков параметров: <yellow>" + profile.freePoints() + "</yellow></gray>");
        if (clazz != null && profession == null && !catalog.professionsOf(clazz.key()).isEmpty()) {
            levelLore.add("");
            if (profile.level() >= required) {
                levelLore.add("<red>⚠ Выберите профессию!</red>");
                levelLore.add("<red>Опыт заблокирован</red>");
            } else {
                levelLore.add("<yellow>До профессии: " + (required - profile.level()) + " ур.</yellow>");
            }
        }
        inv.setItem(LEVEL, MenuItems.item(Material.EXPERIENCE_BOTTLE,
            "<green><b>⬆ Уровень: " + profile.level() + "</b></green>", levelLore));

        // опыт
        final long needed = this.module.expToNext(profile);
        final double progress = needed <= 0 ? 100.0 : Leveling.progress(profile.exp(), needed);
        final int bars = (int) Math.floor(progress / 10.0);
        final StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            bar.append(i < bars ? "■" : "□");
        }
        final List<String> expLore = new ArrayList<>();
        expLore.add("");
        expLore.add("<yellow>" + bar + " " + Text.num(Math.floor(progress * 10) / 10) + "%</yellow>");
        expLore.add("");
        expLore.add(needed <= 0 ? "<gold>Максимальный уровень!</gold>"
            : "<gray>До следующего уровня: " + Math.max(0, needed - profile.exp()) + "</gray>");
        inv.setItem(EXP, MenuItems.item(Material.GOLD_NUGGET,
            "<gold><b>✦ Опыт: " + profile.exp() + " / " + needed + "</b></gold>", expLore));

        // параметры
        inv.setItem(STATS, MenuItems.item(Material.ANVIL, "<aqua><b>Параметры</b></aqua>", List.of(
            "",
            "<gray>" + Text.escape(this.module.primaryStatName(profile.classId())) + ": <white>" + profile.primary() + "</white></gray>",
            "<gray>Ловкость: <white>" + profile.agility() + "</white></gray>",
            "<gray>Выносливость: <white>" + profile.stamina() + "</white></gray>",
            "<gray>Удача: <white>" + profile.luck() + "</white></gray>",
            "",
            "<yellow>Свободно очков: " + profile.freePoints() + "</yellow>",
            "<green>▶ Нажмите, чтобы распределить</green>")));

        // характеристики
        final List<String> aspectLore = new ArrayList<>();
        aspectLore.add("");
        for (final Map.Entry<Aspect, Double> e : aspects.values().entrySet()) {
            if (e.getValue() != 0.0) {
                aspectLore.add("<gray>" + e.getKey().label() + ": <white>"
                    + MenuItems.aspectValue(e.getKey().key(), e.getValue()) + "</white></gray>");
            }
        }
        for (final Map.Entry<String, Double> e : new java.util.TreeMap<>(aspects.targeted()).entrySet()) {
            if (e.getValue() != 0.0) {
                aspectLore.add("<gray>" + MenuItems.aspectLabel(e.getKey()) + ": <white>"
                    + MenuItems.aspectValue(e.getKey(), e.getValue()) + "</white></gray>");
            }
        }
        if (aspectLore.size() == 1) {
            aspectLore.add("<gray>Пока никаких бонусов.</gray>");
        }
        final var passives = catalog.passives(profile.classId(), profile.professionId());
        if (!passives.isEmpty()) {
            aspectLore.add("");
            aspectLore.add("<gold>Пассивки:</gold>");
            for (final String p : passives) {
                aspectLore.add("<gray>• " + this.module.passiveLabel(p) + "</gray>");
            }
        }
        inv.setItem(ASPECTS, MenuItems.item(Material.IRON_CHESTPLATE, "<gold><b>Характеристики</b></gold>", aspectLore));

        inv.setItem(CLOSE, MenuItems.item(Material.BARRIER, "<red>Закрыть</red>", List.of()));
    }

    @Override
    public void click(final Player player, final int slot, final ClickType click) {
        final PlayerProfile profile = this.module.profile(player);
        switch (slot) {
            case CLOSE -> this.module.later(player, 1L, player::closeInventory);
            case STATS -> this.module.later(player, 1L, () -> this.module.openStatsMenu(player));
            case CLASS -> {
                if (!profile.hasClass()) {
                    this.module.later(player, 1L, () -> this.module.openClassMenu(player));
                }
            }
            case PROFESSION -> {
                if (profile.hasClass() && !profile.hasProfession()) {
                    this.module.later(player, 1L, () -> this.module.openProfessionMenu(player, true));
                }
            }
            default -> {
            }
        }
    }
}
