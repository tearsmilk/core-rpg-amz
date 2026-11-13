package core.amazake.systems.corerpg.progression.menu;

import core.amazake.systems.corerpg.aspect.Aspect;
import core.amazake.systems.corerpg.profile.PlayerProfile;
import core.amazake.systems.corerpg.progression.Bonus;
import core.amazake.systems.corerpg.progression.ClassCatalog;
import core.amazake.systems.corerpg.progression.ProgressionModule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/** Выбор профессии (27 слотов): профессии класса, по центру — сведения о классе, внизу — «Закрыть». */
public final class ProfessionMenu extends ProgressionMenu {

    private static final int SIZE = 27;
    private static final int CLOSE = 22;

    private final Map<Integer, String> slots = new HashMap<>();

    public ProfessionMenu(final ProgressionModule module) {
        super(module, SIZE, "menu-profession-title");
    }

    @Override
    public void render(final Player player) {
        this.frame(SIZE);
        final var inv = this.getInventory();
        inv.setItem(9, MenuItems.filler(Material.PURPLE_STAINED_GLASS_PANE));
        inv.setItem(17, MenuItems.filler(Material.PURPLE_STAINED_GLASS_PANE));

        final PlayerProfile profile = this.module.profile(player);
        final ClassCatalog catalog = this.module.catalog();
        final ClassCatalog.RpgClass clazz = catalog.clazz(profile.classId());
        this.slots.clear();
        if (clazz == null) {
            return;
        }
        final List<ClassCatalog.RpgProfession> list = catalog.professionsOf(clazz.key());
        final int[] positions;
        final int info;
        if (list.size() == 2) {
            positions = new int[] {11, 15};
            info = 13;
        } else {
            positions = centered(list.size(), 1);
            info = 4;
        }
        final int required = this.module.professionLevel();
        final boolean levelOk = profile.level() >= required;
        for (int i = 0; i < positions.length; i++) {
            final ClassCatalog.RpgProfession p = list.get(i);
            this.slots.put(positions[i], p.key());
            inv.setItem(positions[i], this.professionItem(p, i % 2 == 0, levelOk, required, profile));
        }
        inv.setItem(info, MenuItems.item(Material.NETHER_STAR, "<gold><b>Ваш класс: " + clazz.displayName() + "</b></gold>", List.of(
            "",
            "<white>Выберите одну профессию.</white>",
            "<gray>Это определит ваши навыки</gray>",
            "<gray>на всю игру.</gray>",
            "",
            levelOk ? "<green>Выбор доступен.</green>" : "<yellow>Доступно с " + required + " уровня (сейчас " + profile.level() + ").</yellow>",
            "<red>⚠ Выбор окончательный!</red>")));
        inv.setItem(CLOSE, MenuItems.item(Material.BARRIER, "<red>Закрыть</red>", List.of(
            "<gray>Вы можете выбрать профессию позже</gray>",
            "<aqua>командой /profession</aqua>",
            "",
            "<red>⚠ Пока профессия не выбрана,</red>",
            "<red>опыт после " + required + " уровня не начисляется!</red>")));
    }

    private org.bukkit.inventory.ItemStack professionItem(final ClassCatalog.RpgProfession p, final boolean left,
                                                          final boolean levelOk, final int required,
                                                          final PlayerProfile profile) {
        final List<String> lore = new ArrayList<>();
        lore.add("");
        for (final String line : p.description()) {
            lore.add("<gray>" + line + "</gray>");
        }
        if (!p.bonuses().isEmpty()) {
            lore.add("");
            lore.add("<gold>Бонусы профессии:</gold>");
            for (final Bonus b : p.bonuses()) {
                lore.add(MenuItems.describe(b));
            }
        }
        if (!p.passives().isEmpty()) {
            lore.add("<gold>Пассивки:</gold>");
            for (final String s : p.passives()) {
                lore.add("<gray>• " + this.module.passiveLabel(s) + "</gray>");
            }
        }
        if (!p.lockedAspects().isEmpty()) {
            final List<String> names = new ArrayList<>();
            for (final Aspect a : p.lockedAspects()) {
                names.add(a.label());
            }
            lore.add("<red>Закрыт аспект: " + String.join(", ", names) + "</red>");
        }
        lore.add("");
        if (profile.hasProfession()) {
            lore.add("<gray>Профессия уже выбрана.</gray>");
        } else if (levelOk) {
            lore.add("<green><b>▶ Нажмите, чтобы выбрать</b></green>");
            lore.add("<red>Выбор нельзя изменить!</red>");
        } else {
            lore.add("<yellow>Доступно с " + required + " уровня</yellow>");
        }
        final String color = p.color().isEmpty() ? (left ? "<aqua>" : "<light_purple>") : p.color();
        return MenuItems.item(p.icon(), color + "<b>★ " + p.displayName() + " ★</b>", lore);
    }

    @Override
    public void click(final Player player, final int slot, final ClickType click) {
        if (slot == CLOSE) {
            this.module.later(player, 1L, player::closeInventory);
            return;
        }
        final String key = this.slots.get(slot);
        if (key == null) {
            return;
        }
        final ClassCatalog.RpgProfession p = this.module.catalog().profession(key);
        if (p == null) {
            return;
        }
        this.module.later(player, 1L, () -> {
            player.closeInventory();
            this.module.chooseProfession(player, p);
        });
    }
}
