package core.amazake.systems.corerpg.progression.menu;

import core.amazake.systems.corerpg.progression.Bonus;
import core.amazake.systems.corerpg.progression.ClassCatalog;
import core.amazake.systems.corerpg.progression.ProgressionModule;
import core.amazake.systems.corerpg.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/** Выбор базового класса (45 слотов): классы во второй строке, подсказки под ними, «Закрыть» внизу. */
public final class ClassMenu extends ProgressionMenu {

    private static final int SIZE = 45;
    private static final int INFO = 4;
    private static final int CLOSE = 40;

    private final Map<Integer, String> slots = new HashMap<>();

    public ClassMenu(final ProgressionModule module) {
        super(module, SIZE, "menu-class-title");
    }

    @Override
    public void render(final Player player) {
        this.frame(SIZE);
        final var inv = this.getInventory();
        inv.setItem(9, MenuItems.filler(Material.YELLOW_STAINED_GLASS_PANE));
        inv.setItem(17, MenuItems.filler(Material.YELLOW_STAINED_GLASS_PANE));
        inv.setItem(18, MenuItems.filler(Material.ORANGE_STAINED_GLASS_PANE));
        inv.setItem(26, MenuItems.filler(Material.ORANGE_STAINED_GLASS_PANE));
        inv.setItem(27, MenuItems.filler(Material.YELLOW_STAINED_GLASS_PANE));
        inv.setItem(35, MenuItems.filler(Material.YELLOW_STAINED_GLASS_PANE));

        final ClassCatalog catalog = this.module.catalog();
        final int professionLevel = this.module.professionLevel();
        final List<ClassCatalog.RpgClass> classes = new ArrayList<>(catalog.classes().values());
        final int[] positions = centered(classes.size(), 2);
        this.slots.clear();
        for (int i = 0; i < positions.length; i++) {
            final ClassCatalog.RpgClass c = classes.get(i);
            this.slots.put(positions[i], c.key());
            inv.setItem(positions[i], this.classItem(catalog, c, professionLevel));
            if (!c.hint().isEmpty()) {
                inv.setItem(positions[i] + 9, MenuItems.item(c.hintIcon(), "<yellow>" + Text.escape(c.hint()) + "</yellow>",
                    List.of("<gray>" + Text.escape(Text.plain(Text.mm(c.displayName()))) + "</gray>")));
            }
        }

        inv.setItem(INFO, MenuItems.item(Material.NETHER_STAR, "<gold><b>Информация</b></gold>", List.of(
            "",
            "<white>Выберите один базовый класс.</white>",
            "<gray>Это определит ваш стиль игры.</gray>",
            "",
            "<aqua>На " + professionLevel + " уровне вы сможете</aqua>",
            "<aqua>выбрать одну из профессий класса.</aqua>",
            "",
            "<red>⚠ Выбор окончательный!</red>")));
        inv.setItem(CLOSE, MenuItems.item(Material.BARRIER, "<red>Закрыть</red>", List.of(
            "<gray>Вы сможете открыть меню</gray>",
            "<gray>снова командой <aqua>/class</aqua></gray>")));
    }

    private org.bukkit.inventory.ItemStack classItem(final ClassCatalog catalog, final ClassCatalog.RpgClass c,
                                                     final int professionLevel) {
        final List<String> lore = new ArrayList<>();
        lore.add("");
        for (final String line : c.description()) {
            lore.add("<gray>" + line + "</gray>");
        }
        lore.add("");
        lore.add("<yellow>Главный параметр: <white>" + Text.escape(c.primaryStat()) + "</white></yellow>");
        if (!c.bonuses().isEmpty()) {
            lore.add("<gold>Бонусы класса:</gold>");
            for (final Bonus b : c.bonuses()) {
                lore.add(MenuItems.describe(b));
            }
        }
        if (!c.passives().isEmpty()) {
            lore.add("<gold>Пассивки:</gold>");
            for (final String p : c.passives()) {
                lore.add("<gray>• " + MenuItems.passiveLabel(p) + "</gray>");
            }
        }
        final var professions = catalog.professionsOf(c.key());
        if (!professions.isEmpty()) {
            lore.add("");
            lore.add("<gold><u>Профессии на " + professionLevel + " уровне:</u></gold>");
            for (final var p : professions) {
                lore.add("<yellow>  ▸ " + p.displayName() + "</yellow>");
                if (!p.description().isEmpty()) {
                    lore.add("<dark_gray>     " + p.description().get(0) + "</dark_gray>");
                }
            }
        }
        lore.add("");
        lore.add("<green><b>▶ Нажмите, чтобы выбрать</b></green>");
        lore.add("<red>Выбор нельзя изменить!</red>");
        return MenuItems.item(c.icon(), c.color() + "<b>★ " + c.displayName() + " ★</b>", lore);
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
        final ClassCatalog.RpgClass c = this.module.catalog().clazz(key);
        if (c == null) {
            return;
        }
        this.module.later(player, 1L, () -> {
            player.closeInventory();
            this.module.chooseClass(player, c);
        });
    }
}
