package core.amazake.systems.rezusmenu.rpg;

import core.amazake.systems.rezusmenu.Settings;
import core.amazake.systems.rezusmenu.profiles.FontWidth;
import core.amazake.systems.rezusmenu.screen.PanelMath;
import core.amazake.systems.rezusmenu.screen.Screen;
import core.amazake.systems.rezusmenu.screen.SkinFront;
import core.amazake.systems.rezusmenu.util.Papi;
import core.amazake.systems.rezusmenu.util.Text;
import com.destroystokyo.paper.profile.PlayerProfile;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerTextures;

/**
 * RPG-меню игрока по контракту арта v2: холст 704 × 400 (фон {@code back_r_c}, слева крыло «Класс», справа —
 * «Очки», сверху лента, снизу подсказка), в нём панель 512 × 288 в (96, 56). На панели вкладки «Мой персонаж» /
 * «Снаряжение» / «Навыки» / «Задания»; слева персонаж (скин во весь рост), полоски и параметры.
 *
 * <ul>
 *   <li>«Мой персонаж» — только сведения о персонаже: слева скин в арке (слоты снаряжения закрыты накладкой
 *   {@code char_left}), справа две колонки ({@code char_bg_0/1}): персонаж (класс, уровень, опыт, здоровье, ресурс,
 *   очки, профиль, баланс, время в игре) и характеристики (rpg.character.stats).</li>
 *   <li>«Снаряжение» — инвентарь: слева броня, бижутерия и вторая рука, справа сумка (3 ряда кармана, под ними ряды
 *   хранилища, прокрутка ▲/▼) и панель быстрого доступа. В хранилище стакается до 99 (только то, что стакается;
 *   см. {@link StackMath}).</li>
 * </ul>
 * Клик — выбрать предмет, клик по другой клетке — переложить (сложить, поменять местами, надеть, положить в
 * бижутерию). Предметы никогда не покидают инвентарь, хранилище и слоты бижутерии.
 *
 * <p>Координаты в коде — пиксели панели (как в арте), на холст их переводит {@link #X}/{@link #Y}. Всё — в потоке
 * игрока.</p>
 */
public final class RpgScreen {

    private static final String STATIC = "static";
    private static final String CONTENT = "content";
    private static final String PORTRAIT = "portrait";
    /** Броня, бижутерия, вторая рука: на всех вкладках, кроме «Мой персонаж». */
    private static final String EQUIP = "equip";

    // Слои (больше — ближе к глазам).
    private static final int L_BACK = 0;
    private static final int L_PANEL = 1;
    private static final int L_CONTENT_BG = 2;
    private static final int L_ACTIVE = 2;
    private static final int L_SELECTED = 3;
    private static final int L_FILL = 3;
    private static final int L_ICON = 6;
    private static final int L_LOCKED = 7;
    private static final int L_HOVER_SKILL = 8;
    private static final int L_TEXT = 9;

    // Вкладки.
    private static final int[] TAB_X = {12, 136, 260, 384};
    private static final int TAB_Y = 10;
    private static final int TAB_W = 118;
    private static final int TAB_H = 32;

    /** Колодец слота 28 × 28. */
    private static final int WELL = 28;
    private static final int[] ARMOR_Y = {74, 106, 138, 170};
    private static final int ARMOR_X = 18;
    private static final int JEWEL_X = 172;
    private static final int GRID_X = 218;
    private static final int GRID_STEP = 31;
    private static final int[] GRID_Y = {74, 105, 136};
    private static final int HOTBAR_Y = 172;

    /** Полоски: колодец x 18..200, высота 10; заливка 166 × 6 с (32, верх + 2). */
    private static final int BAR_FILL_X = 32;
    private static final int BAR_FILL_W = 166;
    private static final int BAR_TEX_W = 176;
    private static final int EXP_Y = 206;
    private static final int HP_Y = 220;
    private static final int MANA_Y = 234;

    // Правая секция.
    private static final int RIGHT_X = 216;
    private static final int RIGHT_W = 280;

    // «Мой персонаж»: накладки и две колонки строк (пиксели панели).
    private static final int CHAR_LEFT_X = 14;
    private static final int CHAR_LEFT_Y = 66;
    private static final int CHAR_BG_Y = 52;
    private static final int[] CHAR_NAME_X = {222, 364};
    private static final int[] CHAR_VALUE_R = {348, 490};
    private static final int CHAR_ROW_Y = 94;
    private static final int CHAR_ROW_DY = 12;
    private static final int CHAR_ROWS = 12;
    private static final int CHAR_COL_W = 126;

    // Сумка «Снаряжения»: строка прокрутки на планке под панелью быстрого доступа.
    private static final int SCROLL_Y = 204;
    private static final int SCROLL_UP_X = 222;
    private static final int SCROLL_DOWN_X = 476;

    // Навыки: карточки 64 × 76.
    private static final int SKILL_X = 220;
    private static final int SKILL_Y = 74;
    private static final int SKILL_DX = 68;
    private static final int SKILL_DY = 80;
    private static final int SKILL_MAX = 8;
    private static final int SKILL_BAR_W = 52;

    /** Портрет: 16 × 32 пикселя скина по 3 px, левый верхний угол (82, 84). */
    private static final int PORTRAIT_X = 82;
    private static final int PORTRAIT_Y = 84;
    private static final int PORTRAIT_PX = 3;

    private static final String HELP = "<gray>ЛКМ — нажать · Пробел — курсор в центр · Shift — выйти</gray>";

    /** Броня: индекс слота инвентаря → слот снаряжения. 39 — шлем … 36 — ботинки, 40 — вторая рука. */
    private static final Map<Integer, EquipmentSlot> ARMOR = Map.of(39, EquipmentSlot.HEAD, 38, EquipmentSlot.CHEST,
        37, EquipmentSlot.LEGS, 36, EquipmentSlot.FEET);
    private static final int OFFHAND = 40;

    enum Tab { CHARACTER, EQUIPMENT, SKILLS, QUESTS }

    /** Клетка на экране (инвентарь или бижутерия): левый верхний угол колодца (x, y) в пикселях панели. */
    private record Slot(int index, int x, int y, Screen.Button button, ItemDisplay icon, TextDisplay count) {
    }

    private final Screen screen;
    private final Player player;
    private final Settings settings;
    private final SkinService skins;
    private final Consumer<Runnable> playerThread;
    private final Runnable closeAction;
    private final Runnable calibrateAction;
    private final Jewelry jewelry;
    private final Stash stash;
    /** «Профиль №N из M» (null — профилей нет). */
    private final Supplier<String> profileLabel;
    /** Верхний показанный ряд сумки (карман + хранилище). */
    private int bagOffset;
    private TextDisplay bagText;
    /** Показаны ли сейчас слоты брони и бижутерии (нет — на «Мой персонаж»). */
    private Boolean equipShown;
    /** Строки «Мой персонаж»: группа строки → что в ней показано (пересоздаётся только при изменении). */
    private final Map<String, String> charRows = new HashMap<>();
    /** Плавающая подсказка предмета (полный тултип) — своя группа, пересоздаётся при смене наведения. */
    private static final String TIP = "tip";
    private static final int L_TIP = 10;
    private final Map<Integer, Slot> slots = new HashMap<>();
    private final Map<Screen.Button, Integer> slotOf = new HashMap<>();
    private final Map<Screen.Button, Mmocore.Skill> skillOf = new HashMap<>();
    private final Map<Integer, ItemStack> shown = new HashMap<>();
    private Tab tab = Tab.CHARACTER;
    private Integer picked;
    private Screen.Button lastHover;
    private String skillSignature = "";

    private Screen.Quad tabActive;
    private Screen.Quad slotSelected;
    private TextDisplay nick;
    private TextDisplay help;
    private ItemDisplay classIcon;
    private Material classIconShown;
    private TextDisplay className;
    private TextDisplay profession;
    private TextDisplay levelText;
    private TextDisplay expShort;
    private TextDisplay skillPoints;
    private TextDisplay attributePoints;
    private final List<TextDisplay> classList = new ArrayList<>();
    private final List<ItemDisplay> classListIcons = new ArrayList<>();
    private final List<Material> classListShown = new ArrayList<>();
    private TextDisplay expText;
    private TextDisplay hpText;
    private TextDisplay manaText;
    private Screen.Quad expFill;
    private Screen.Quad hpFill;
    private Screen.Quad manaFill;
    private Screen.Quad energyFill;
    private final List<TextDisplay> statTexts = new ArrayList<>();
    private TextDisplay tooltip;
    private TextDisplay tooltip2;
    private TextDisplay skillPointsLine;

    public RpgScreen(final Screen screen, final Settings settings, final SkinService skins,
                     final Supplier<String> profileLabel, final Consumer<Runnable> playerThread,
                     final Runnable closeAction, final Runnable calibrateAction) {
        this.screen = screen;
        screen.hoverLayer("skill_hover", L_HOVER_SKILL);
        this.player = screen.player();
        this.settings = settings;
        this.skins = skins;
        this.playerThread = playerThread;
        this.closeAction = closeAction;
        this.calibrateAction = calibrateAction;
        this.profileLabel = profileLabel;
        // 1.0.1: сначала снять бижутерию, что не подходит по классу/уровню, и только потом читать слоты — иначе
        // меню показало бы уже снятый (и возвращённый в инвентарь) предмет.
        Jewelry.enforce(this.player);
        this.jewelry = new Jewelry(this.player);
        this.stash = new Stash(this.player, settings.stashRows());
    }

    /** Слоты, которые видны на всех вкладках (броня, вторая рука, бижутерия): их не убирает смена вкладки. */
    private static boolean persistent(final int index) {
        return index >= 36 && index <= Jewelry.ARTIFACT;
    }

    /** Пиксель панели → пиксель холста. */
    private static double X(final double panelX) {
        return panelX + PanelMath.PANEL_X;
    }

    private static double Y(final double panelY) {
        return panelY + PanelMath.PANEL_Y;
    }

    // --- обёртки: координаты панели ---

    private Screen.Quad quad(final String id, final double x, final double y, final int layer, final String group) {
        return this.screen.quad(id, X(x), Y(y), layer, group);
    }

    private TextDisplay text(final double x, final double y, final double w, final double h, final double lh,
                             final Component c, final int layer, final String group) {
        return this.screen.text(X(x), Y(y), w, h, lh, c, layer, group);
    }

    private ItemDisplay item(final double cx, final double cy, final double size, final ItemStack it, final int layer,
                             final String group) {
        return this.screen.item(X(cx), Y(cy), size, it, layer, group);
    }

    private Screen.Button button(final double x, final double y, final double w, final double h, final String hover,
                                 final String group, final String name, final Consumer<Screen.Button> action) {
        return this.screen.button(X(x), Y(y), w, h, hover, group, name, action);
    }

    // --- постройка ---

    public void build() {
        final Screen s = this.screen;
        // Холст: фон 3 × 3 тайла, поверх — панель 2 × 2.
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                s.quad("back_" + r + "_" + c, PanelMath.BACK_X[c], PanelMath.BACK_Y[r], L_BACK, STATIC);
            }
        }
        for (int i = 0; i < 4; i++) {
            this.quad("panel_" + i, (i % 2) * 256, (i / 2) * 144, L_PANEL, STATIC);
        }
        this.tabActive = s.marker("tab_active", L_ACTIVE);
        this.slotSelected = s.marker("slot_selected", L_SELECTED);

        // верх и низ холста
        s.text(240, 12, 224, 32, 11, Text.item("<gold><b>Персонаж</b></gold>"), L_TEXT, STATIC);
        this.help = s.text(160, 358, 384, 24, 8, Text.item(HELP), L_TEXT, STATIC);

        // вкладки
        final String[] names = {"Мой персонаж", "Снаряжение", "Навыки", "Задания"};
        final ItemStack[] icons = {this.head(), new ItemStack(Material.IRON_SWORD),
            new ItemStack(Material.ENCHANTED_BOOK), new ItemStack(Material.FILLED_MAP)};
        for (int i = 0; i < 4; i++) {
            final Tab t = Tab.values()[i];
            final int x = TAB_X[i];
            this.button(x, TAB_Y, TAB_W, TAB_H, "tab_hover", STATIC, "вкладка " + names[i], b -> this.switchTab(t));
            this.item(x + 16, TAB_Y + TAB_H / 2.0, 16, icons[i], L_ICON, STATIC);
            this.text(x + 26, TAB_Y, TAB_W - 30, TAB_H, 10, Text.item("<white>" + names[i] + "</white>"), L_TEXT, STATIC);
        }

        this.buildWings();

        // левая секция: персонаж (слоты снаряжения — по вкладке, см. showEquip)
        this.nick = this.text(16, 52, 186, 16, 9, Component.empty(), L_TEXT, STATIC);
        this.portrait();

        // полоски: заливка в натуральную величину, ширина режется масштабом
        this.expFill = s.quad("bar_exp", X(BAR_FILL_X), Y(EXP_Y + 2), 0, L_FILL, STATIC);
        this.hpFill = s.quad("bar_hp", X(BAR_FILL_X), Y(HP_Y + 2), 0, L_FILL, STATIC);
        this.manaFill = s.quad("bar_mana", X(BAR_FILL_X), Y(MANA_Y + 2), 0, L_FILL, STATIC);
        this.energyFill = s.quad("bar_energy", X(BAR_FILL_X), Y(MANA_Y + 2), 0, L_FILL, STATIC);
        this.expText = this.text(BAR_FILL_X, EXP_Y, BAR_FILL_W, 10, 7, Component.empty(), L_TEXT, STATIC);
        this.hpText = this.text(BAR_FILL_X, HP_Y, BAR_FILL_W, 10, 7, Component.empty(), L_TEXT, STATIC);
        this.manaText = this.text(BAR_FILL_X, MANA_Y, BAR_FILL_W, 10, 7, Component.empty(), L_TEXT, STATIC);
        // параметры 2 × 2, колонки делятся на x 108
        final double[][] cells = {{16, 248, 92}, {108, 248, 94}, {16, 261, 92}, {108, 261, 94}};
        for (final double[] c : cells) {
            this.statTexts.add(this.text(c[0], c[1], c[2], 13, 7, Component.empty(), L_TEXT, STATIC));
        }

        s.arrowCursor(this.settings.shaderCursor());
        this.switchTab(Tab.CHARACTER);
    }

    /** Броня, бижутерия и вторая рука слева — или накладка «Мой персонаж» на их месте. */
    private void showEquip(final boolean show) {
        if (Boolean.valueOf(show).equals(this.equipShown)) {
            return;
        }
        this.equipShown = show;
        this.screen.clear(EQUIP);
        this.slots.values().removeIf(s -> persistent(s.index()));
        this.slotOf.values().removeIf(RpgScreen::persistent);
        if (!show) {
            this.quad("char_left", CHAR_LEFT_X, CHAR_LEFT_Y, L_CONTENT_BG, EQUIP);
            return;
        }
        final int[] armorSlots = {39, 38, 37, 36};
        for (int i = 0; i < 4; i++) {
            this.slot(armorSlots[i], ARMOR_X, ARMOR_Y[i]);
        }
        this.slot(Jewelry.AMULET, JEWEL_X, 74);
        this.slot(Jewelry.RING_1, JEWEL_X, 106);
        this.slot(Jewelry.RING_2, JEWEL_X, 138);
        this.slot(OFFHAND, JEWEL_X, 170);
        this.slot(Jewelry.TOTEM, 52, 168);
        this.slot(Jewelry.ARTIFACT, 132, 168);
    }

    /** Крылья холста: слева класс, профессия, уровень; справа очки и список классов/профессий. */
    private void buildWings() {
        final Screen s = this.screen;
        this.classIconShown = this.settings.noClassIcon();
        this.classIcon = s.item(48, 96, 40, new ItemStack(this.classIconShown), L_ICON, STATIC);
        this.className = s.text(12, 128, 72, 16, 8, Component.empty(), L_TEXT, STATIC);
        this.profession = s.text(12, 152, 72, 16, 7, Component.empty(), L_TEXT, STATIC);
        this.levelText = s.text(12, 176, 72, 16, 8, Component.empty(), L_TEXT, STATIC);
        this.expShort = s.text(12, 192, 72, 16, 7, Component.empty(), L_TEXT, STATIC);

        this.skillPoints = s.text(634, 78, 44, 28, 18, Component.empty(), L_TEXT, STATIC);
        s.text(620, 116, 72, 16, 7, Text.item("<gold>Очки навыков</gold>"), L_TEXT, STATIC);
        this.attributePoints = s.text(634, 154, 44, 28, 18, Component.empty(), L_TEXT, STATIC);
        s.text(620, 192, 72, 16, 7, Text.item("<gold>Очки параметров</gold>"), L_TEXT, STATIC);
        for (int i = 0; i < 7; i++) {
            // строка 0 — заголовок во всю ширину; в остальных слева иконка класса, справа название
            this.classList.add(i == 0 ? s.text(620, 224, 72, 16, 7, Component.empty(), L_TEXT, STATIC)
                : s.text(634, 224 + i * 16, 58, 16, 7, Component.empty(), L_TEXT, STATIC));
            this.classListIcons.add(i == 0 ? null : s.item(627, 232 + i * 16, 12, null, L_ICON, STATIC));
        }
    }

    /** Портрет: скин во весь рост из «пикселей» текста; пока качается или если не вышло — голова игрока. */
    private void portrait() {
        String url = null;
        boolean slim = false;
        try {
            final PlayerProfile profile = this.player.getPlayerProfile();
            final URL skin = profile.getTextures().getSkin();
            url = skin == null ? null : skin.toString();
            slim = profile.getTextures().getSkinModel() == PlayerTextures.SkinModel.SLIM;
        } catch (final RuntimeException e) {
            url = null;
        }
        final int[] cached = url == null ? null : this.skins.cached(url, slim);
        if (cached != null) {
            this.drawSkin(cached);
            return;
        }
        this.item(106, 132, 56, this.head(), L_ICON, PORTRAIT);
        if (url != null) {
            this.skins.fetch(url, slim, front -> {
                if (front != null) {
                    this.playerThread.accept(() -> {
                        if (!this.screen.closed()) {
                            this.screen.clear(PORTRAIT);
                            this.drawSkin(front);
                        }
                    });
                }
            });
        }
    }

    private void drawSkin(final int[] front) {
        this.screen.pixels(front, SkinFront.W, SkinFront.H, X(PORTRAIT_X), Y(PORTRAIT_Y), PORTRAIT_PX, L_ICON, PORTRAIT);
    }

    private ItemStack head() {
        final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, m -> m.setOwningPlayer(this.player));
        return head;
    }

    /** Клетка (броня, вторая рука, бижутерия — в левой секции, всегда). (x, y) — левый верхний угол колодца. */
    private void slot(final int index, final int x, final int y) {
        final String group = persistent(index) ? EQUIP : CONTENT;
        final Screen.Button b = this.button(x, y, WELL, WELL, "slot_hover", group, "слот " + index,
            z -> this.clickSlot(index));
        final ItemDisplay icon = this.item(x + WELL / 2.0, y + WELL / 2.0, 22, null, L_ICON, group);
        final TextDisplay count = this.text(x + 12, y + 18, 16, 10, 8, Component.empty(), L_TEXT, group);
        this.slots.put(index, new Slot(index, x, y, b, icon, count));
        this.slotOf.put(b, index);
        this.shown.remove(index);
    }

    // --- вкладки ---

    private void switchTab(final Tab next) {
        this.tab = next;
        this.screen.clear(CONTENT);
        this.screen.clear(TIP);
        this.clearCharacter();
        this.tipSignature = "";
        this.slots.values().removeIf(s -> !persistent(s.index()));
        this.slotOf.values().removeIf(i -> !persistent(i));
        this.bagText = null;
        this.skillOf.clear();
        this.tooltip = null;
        this.tooltip2 = null;
        this.skillPointsLine = null;
        this.skillSignature = "";
        this.showEquip(next != Tab.CHARACTER);
        this.tabActive.place(X(TAB_X[next.ordinal()]), Y(TAB_Y));
        // Выбранный предмет остаётся выбранным при смене вкладки (например, выбрать на «Снаряжении», посмотреть
        // «Навыки» и вернуться).
        switch (next) {
            case CHARACTER -> this.buildCharacter();
            case EQUIPMENT -> {
                this.heading("Инвентарь и хранилище");
                this.buildBag();
                this.tooltip = this.text(RIGHT_X, 224, RIGHT_W, 14, 8, Component.empty(), L_TEXT, CONTENT);
                this.buttons();
            }
            case SKILLS -> this.buildSkills(Mmocore.read(this.player, SKILL_MAX));
            case QUESTS -> {
                this.heading("Задания");
                this.item(RIGHT_X + RIGHT_W / 2.0, 120, 48, new ItemStack(Material.FILLED_MAP), L_ICON, CONTENT);
                this.text(RIGHT_X, 160, RIGHT_W, 20, 12, Text.item("<gray>Скоро будет</gray>"), L_TEXT, CONTENT);
                this.rightCommon();
            }
        }
        this.repaintSlots();
        this.refresh();
    }

    /**
     * Сумка: три видимых ряда — окно в «карман (3 ряда ванильного инвентаря) + ряды хранилища», под ними —
     * панель быстрого доступа (всегда), на планке ниже — прокрутка ▲ ▼ и какие ряды видны.
     */
    private void buildBag() {
        final int bagRows = Stash.bagRows(this.stash.rows());
        this.bagOffset = Stash.clampOffset(this.bagOffset, bagRows);
        for (int row = 0; row < Stash.VISIBLE_ROWS && this.bagOffset + row < bagRows; row++) {
            for (int col = 0; col < Stash.COLS; col++) {
                this.slot(Stash.bagSlot(this.bagOffset + row, col), GRID_X + col * GRID_STEP, GRID_Y[row]);
            }
        }
        for (int col = 0; col < 9; col++) {
            this.slot(col, GRID_X + col * GRID_STEP, HOTBAR_Y);
        }
        this.quad("pf_arrow_up", SCROLL_UP_X, SCROLL_Y, L_ICON, CONTENT);
        this.button(SCROLL_UP_X, SCROLL_Y, 16, 16, "pf_arrow_hover", CONTENT, "сумка вверх", b -> this.scrollBag(-1));
        this.quad("pf_arrow_down", SCROLL_DOWN_X, SCROLL_Y, L_ICON, CONTENT);
        this.button(SCROLL_DOWN_X, SCROLL_Y, 16, 16, "pf_arrow_hover", CONTENT, "сумка вниз", b -> this.scrollBag(1));
        this.bagText = this.text(SCROLL_UP_X + 18, SCROLL_Y, SCROLL_DOWN_X - SCROLL_UP_X - 20, 16, 8,
            Text.item(bagLabel(this.bagOffset, bagRows)), L_TEXT, CONTENT);
    }

    /** «Ряды 3–5 из 9 · карман + хранилище». */
    static String bagLabel(final int offset, final int bagRows) {
        final int first = offset + 1;
        final int last = Math.min(bagRows, offset + Stash.VISIBLE_ROWS);
        final boolean pocket = offset < Stash.POCKET_ROWS;
        final boolean stash = last > Stash.POCKET_ROWS;
        final String what = pocket && stash ? "<white>карман</white> + <gold>хранилище</gold>"
            : pocket ? "<white>карман</white>" : "<gold>хранилище</gold>";
        return "<gray>Ряды <white>" + first + "–" + last + "</white> из <white>" + bagRows + "</white> · " + what
            + "</gray>";
    }

    private void scrollBag(final int delta) {
        final int next = Stash.clampOffset(this.bagOffset + delta, Stash.bagRows(this.stash.rows()));
        if (next == this.bagOffset) {
            this.sound(Sound.BLOCK_NOTE_BLOCK_BASS);
            return;
        }
        this.bagOffset = next;
        this.sound(Sound.UI_BUTTON_CLICK);
        this.switchTab(Tab.EQUIPMENT);
    }

    /** Кнопки «Калибровка» и «Закрыть» внизу правой секции. */
    private void buttons() {
        this.button(218, 244, 134, 28, "button_hover", CONTENT, "Калибровка", b -> this.calibrateAction.run());
        this.text(218, 244, 134, 28, 10, Text.item("<yellow>Калибровка</yellow>"), L_TEXT, CONTENT);
        this.button(360, 244, 134, 28, "button_hover", CONTENT, "Закрыть", b -> this.closeAction.run());
        this.text(360, 244, 134, 28, 10, Text.item("<white>Закрыть</white>"), L_TEXT, CONTENT);
    }

    // --- «Мой персонаж» ---

    /** Строка колонки: название и значение (MiniMessage); значение null — строки нет. */
    record InfoRow(String name, String value) {
    }

    private void buildCharacter() {
        this.quad("char_bg_0", 212, CHAR_BG_Y, L_CONTENT_BG, CONTENT);
        this.quad("char_bg_1", 356, CHAR_BG_Y, L_CONTENT_BG, CONTENT);
        this.text(212, 54, 288, 20, 10, Text.item("<gold>Сведения о персонаже</gold>"), L_TEXT, CONTENT);
        this.text(216, 76, 138, 16, 8, Text.item("<gold>Персонаж</gold>"), L_TEXT, CONTENT);
        this.text(358, 76, 138, 16, 8, Text.item("<gold>Характеристики</gold>"), L_TEXT, CONTENT);
        this.buttons();
        this.updateCharacter();
    }

    private void clearCharacter() {
        for (final String g : this.charRows.keySet()) {
            this.screen.clear(g);
        }
        this.charRows.clear();
    }

    /** Перерисовать строки, которые изменились (каждая строка — своя группа: название слева, значение справа). */
    private void updateCharacter() {
        final List<List<InfoRow>> cols = List.of(this.characterRows(), this.statRows());
        for (int c = 0; c < 2; c++) {
            final List<InfoRow> rows = cols.get(c);
            for (int i = 0; i < CHAR_ROWS; i++) {
                final InfoRow r = i < rows.size() ? rows.get(i) : null;
                final String group = "char_" + c + "_" + i;
                final String sig = r == null ? "" : r.name() + "\u0000" + r.value();
                if (sig.equals(this.charRows.getOrDefault(group, ""))) {
                    continue;
                }
                this.screen.clear(group);
                this.charRows.put(group, sig);
                if (r == null) {
                    continue;
                }
                final double y = CHAR_ROW_Y + i * CHAR_ROW_DY;
                final String valuePlain = Text.plain(Text.item(r.value()));
                final int valuePx = (int) Math.ceil(FontWidth.of(valuePlain) * 0.8);
                final String name = fit(r.name(), (int) ((CHAR_COL_W - valuePx - 6) / 0.8));
                this.screen.label(X(CHAR_NAME_X[c]), Y(y), CHAR_ROW_DY, 8,
                    Text.item("<gray><n></gray>", Placeholder.unparsed("n", name)), FontWidth.of(name), false,
                    L_TEXT, group);
                this.screen.label(X(CHAR_VALUE_R[c]), Y(y), CHAR_ROW_DY, 8, Text.item(r.value()),
                    FontWidth.of(valuePlain), true, L_TEXT, group);
            }
        }
    }

    /** Обрезать строку до {@code maxFontPx} пикселей шрифта (с «…»). */
    private static String fit(final String s, final int maxFontPx) {
        if (FontWidth.of(s) <= maxFontPx) {
            return s;
        }
        String t = s;
        while (!t.isEmpty() && FontWidth.of(t + "…") > maxFontPx) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "…";
    }

    /** Левая колонка: кто это и как он прокачан. */
    private List<InfoRow> characterRows() {
        final Settings st = this.settings;
        final Settings.Placeholders p = st.ph();
        final List<InfoRow> out = new ArrayList<>();
        final String level = or(this.ph(p.level()), Integer.toString(this.player.getLevel()));
        final String cls = known(this.ph(p.className()));
        final String prof = known(this.ph(p.profession()));
        out.add(new InfoRow("Ник", "<yellow>" + Text.escape(this.player.getName()) + "</yellow>"));
        out.add(new InfoRow("Класс", cls == null ? "<dark_gray>нет</dark_gray>" : "<gold>" + Text.escape(plainName(cls)) + "</gold>"));
        out.add(new InfoRow("Профессия", prof == null ? "<dark_gray>нет</dark_gray>" : "<white>" + Text.escape(plainName(prof)) + "</white>"));
        out.add(new InfoRow("Уровень", "<green>" + Text.escape(level) + "</green>"));
        final double exp = number(this.ph(p.exp()), -1);
        final double need = number(this.ph(p.expNeeded()), -1);
        out.add(new InfoRow("Опыт", exp >= 0 && need > 0
            ? "<white>" + Text.num(Math.floor(exp)) + "/" + Text.num(need) + "</white> <gray>"
                + Math.round(clamp01(exp / need) * 100) + "%</gray>"
            : "<white>" + Math.round(this.player.getExp() * 100) + "%</white>"));
        final AttributeInstance maxHp = this.player.getAttribute(Attribute.MAX_HEALTH);
        out.add(new InfoRow("Здоровье", "<red>" + Text.num(Math.ceil(this.player.getHealth())) + "</red><gray>/"
            + Text.num(maxHp == null ? 20 : maxHp.getValue()) + "</gray>"));
        final Settings.ResourceType res = this.resource(key(this.ph(p.classKey())));
        final double cur = res == null ? -1 : number(this.ph(res.current()), -1);
        final double cap = res == null ? -1 : number(this.ph(res.max()), -1);
        if (cur >= 0 && cap > 0) {
            out.add(new InfoRow(res.label(), "<aqua>" + Text.num(Math.floor(cur)) + "</aqua><gray>/"
                + Text.num(Math.floor(cap)) + "</gray>"));
        }
        final String sp = this.ph(p.skillPoints());
        if (sp != null) {
            out.add(new InfoRow("Очки навыков", "<gold>" + Text.escape(sp) + "</gold>"));
        }
        final String ap = this.ph(p.attributePoints());
        if (ap != null) {
            out.add(new InfoRow("Очки параметров", "<gold>" + Text.escape(ap) + "</gold>"));
        }
        String profile = null;
        try {
            profile = this.profileLabel == null ? null : this.profileLabel.get();
        } catch (final RuntimeException e) {
            profile = null;
        }
        if (profile != null) {
            out.add(new InfoRow("Профиль", "<white>" + Text.escape(profile) + "</white>"));
        }
        final String balance = this.ph(st.balancePlaceholder());
        if (balance != null) {
            out.add(new InfoRow("Баланс", "<yellow>" + Text.escape(balance) + "</yellow>"));
        }
        out.add(new InfoRow("В игре", "<white>" + playtime(this.playTicks()) + "</white>"));
        return out;
    }

    private long playTicks() {
        try {
            return this.player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE);
        } catch (final RuntimeException e) {
            return 0;
        }
    }

    /** Тики игры → «3 д 4 ч», «5 ч 12 мин», «7 мин». */
    static String playtime(final long ticks) {
        final long min = Math.max(0, ticks) / 20 / 60;
        final long d = min / 1440;
        final long h = min / 60 % 24;
        final long m = min % 60;
        if (d > 0) {
            return d + " д " + h + " ч";
        }
        if (h > 0) {
            return h + " ч " + m + " мин";
        }
        return m + " мин";
    }

    /** Правая колонка: характеристики из rpg.character.stats (что не подставилось — скрыто). */
    private List<InfoRow> statRows() {
        final List<InfoRow> out = new ArrayList<>();
        for (final Settings.InfoLine line : this.settings.characterStats()) {
            final String value = this.infoValue(line.value());
            if (value == null) {
                continue;
            }
            final String rawName = this.ph(line.name());
            final String name = rawName != null ? rawName : line.name().contains("%") ? line.fallbackName() : line.name();
            if (name == null || name.isBlank()) {
                continue;
            }
            out.add(new InfoRow(plainName(name), "<white>" + Text.escape(value + line.suffix()) + "</white>"));
        }
        return out;
    }

    /** Значение строки: встроенное ($armor…), плейсхолдер или готовый текст; числа — до десятых. null — нет. */
    private String infoValue(final String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        if (spec.startsWith("$")) {
            final double v = switch (spec.toLowerCase(Locale.ROOT)) {
                case "$armor" -> this.attr(Attribute.ARMOR);
                case "$armor_toughness" -> this.attr(Attribute.ARMOR_TOUGHNESS);
                case "$attack_damage" -> this.attr(Attribute.ATTACK_DAMAGE);
                case "$attack_speed" -> this.attr(Attribute.ATTACK_SPEED);
                case "$move_speed" -> this.moveSpeedPercent();
                case "$max_health" -> this.attr(Attribute.MAX_HEALTH);
                case "$luck" -> this.attr(Attribute.LUCK);
                default -> -1;
            };
            return v < 0 ? null : tenths(v);
        }
        final String raw = spec.contains("%") ? this.ph(spec) : spec;
        if (raw == null) {
            return null;
        }
        final String plain = plainName(raw);
        try {
            return tenths(Double.parseDouble(plain.replace(',', '.').strip()));
        } catch (final NumberFormatException e) {
            return plain;
        }
    }

    /**
     * Скорость передвижения в % от обычной. В меню игрок стоит (скорость ходьбы 0), поэтому база — скорость до меню
     * (walkSpeed / 2 = база атрибута), а модификаторы (зелья, предметы) берутся с атрибута.
     */
    private double moveSpeedPercent() {
        final AttributeInstance i = this.player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (i == null) {
            return -1;
        }
        double add = 0;
        double scalar = 0;
        double mul = 1;
        for (final org.bukkit.attribute.AttributeModifier m : i.getModifiers()) {
            switch (m.getOperation()) {
                case ADD_NUMBER -> add += m.getAmount();
                case ADD_SCALAR -> scalar += m.getAmount();
                case MULTIPLY_SCALAR_1 -> mul *= 1 + m.getAmount();
            }
        }
        final double base = this.screen.saved().walkSpeed() / 2.0;
        return Math.round(Math.max(0, (base + add) * (1 + scalar) * mul) / 0.1 * 100);
    }

    private double attr(final Attribute a) {
        final AttributeInstance i = this.player.getAttribute(a);
        return i == null ? -1 : i.getValue();
    }

    private static String tenths(final double v) {
        return Text.num(Math.round(v * 10) / 10.0);
    }

    /** Без цветовых кодов (§/& и теги). */
    private static String plainName(final String s) {
        return s.replaceAll("(?i)[§&][0-9a-fk-orx]", "").replaceAll("<[^>]*>", "").strip();
    }

    /** Подсказка предмета (две строки) и кнопки — у всех вкладок, кроме «Навыков» (её фон закрывает это место). */
    private void rightCommon() {
        this.tooltip = this.text(RIGHT_X, 206, RIGHT_W, 14, 9, Component.empty(), L_TEXT, CONTENT);
        this.tooltip2 = this.text(RIGHT_X, 224, RIGHT_W, 12, 7, Component.empty(), L_TEXT, CONTENT);
        this.buttons();
    }

    private void heading(final String name) {
        this.text(RIGHT_X, 52, RIGHT_W, 16, 10, Text.item("<gold>" + Text.escape(name) + "</gold>"), L_TEXT, CONTENT);
    }

    /** Вкладка «Навыки»: фон, до 8 карточек навыков класса из MMOCore, строка очков. Без прокачки. */
    private void buildSkills(final Mmocore.View view) {
        this.skillSignature = signature(view);
        this.quad("skills_bg_0", 212, 52, L_CONTENT_BG, CONTENT);
        this.quad("skills_bg_1", 356, 52, L_CONTENT_BG, CONTENT);
        final String cls = Papi.value(this.player, this.settings.ph().className());
        this.heading(cls == null || cls.equals("???") ? "Навыки" : "Навыки: " + cls);
        this.skillPointsLine = this.text(RIGHT_X, 238, RIGHT_W, 16, 8, Component.empty(), L_TEXT, CONTENT);
        if (view.error() != null) {
            this.text(RIGHT_X, 140, RIGHT_W, 20, 10, Text.item("<red>" + Text.escape(view.error()) + "</red>"),
                L_TEXT, CONTENT);
            Screen.setText(this.skillPointsLine, Component.empty());
            return;
        }
        Screen.setText(this.skillPointsLine, Text.item("<gray>Очки навыков:</gray> <gold>" + view.skillPoints() + "</gold>"));
        if (view.skills().isEmpty()) {
            this.text(RIGHT_X, 140, RIGHT_W, 20, 10, Text.item("<gray>У класса нет навыков</gray>"), L_TEXT, CONTENT);
            return;
        }
        for (int i = 0; i < view.skills().size(); i++) {
            final Mmocore.Skill sk = view.skills().get(i);
            final int x = SKILL_X + (i % 4) * SKILL_DX;
            final int y = SKILL_Y + (i / 4) * SKILL_DY;
            final Screen.Button b = this.button(x, y, 64, 76, "skill_hover", CONTENT, "навык " + sk.id(), z -> { });
            this.skillOf.put(b, sk);
            this.item(x + 32, y + 22, 26, sk.icon(), L_ICON, CONTENT);
            this.text(x + 2, y + 42, 60, 14, 6,
                Text.item((sk.unlocked() ? "<white>" : "<gray>") + Text.escape(sk.name())), L_TEXT, CONTENT);
            if (sk.unlocked()) {
                final double frac = sk.maxLevel() > 0 ? clamp01((double) sk.level() / sk.maxLevel()) : 0;
                this.screen.quad("bar_exp", X(x + 6), Y(y + 62), BAR_TEX_W * SKILL_BAR_W * frac / BAR_FILL_W,
                    L_FILL, CONTENT);
                this.text(x + 2, y + 60, 60, 10, 5,
                    Text.item("<white>" + sk.level() + "/" + sk.maxLevel() + "</white>"), L_TEXT, CONTENT);
            } else {
                this.quad("skill_locked", x, y, L_LOCKED, CONTENT);
                this.text(x + 2, y + 60, 60, 10, 5,
                    Text.item("<red>закрыт с " + sk.unlockLevel() + " ур.</red>"), L_TEXT, CONTENT);
            }
        }
    }

    private static String signature(final Mmocore.View v) {
        final StringBuilder b = new StringBuilder(String.valueOf(v.error())).append('|').append(v.skillPoints());
        for (final Mmocore.Skill s : v.skills()) {
            b.append('|').append(s.id()).append(':').append(s.level()).append(':').append(s.unlocked());
        }
        return b.toString();
    }

    // --- обновление ---

    /** Такт меню: подсказка под курсором. */
    public void tick() {
        final Screen.Button h = this.screen.hovered();
        if (h != this.lastHover) {
            this.lastHover = h;
            this.updateTooltip();
        }
    }

    /** Перерисовать предметы и значения (раз в refresh-ticks и после каждого действия). */
    public void refresh() {
        for (final Slot s : this.slots.values()) {
            final ItemStack item = this.get(s.index());
            final ItemStack norm = item == null || item.isEmpty() ? null : item;
            if (!Objects.equals(this.shown.get(s.index()), norm)) {
                this.shown.put(s.index(), norm == null ? null : norm.clone());
                if (s.icon().isValid()) {
                    // Иконке количество не нужно (его пишет текст), а стопка хранилища бывает больше ванильного
                    // предела — клиенту такое не шлём.
                    s.icon().setItemStack(norm == null ? null : norm.asOne());
                }
                Screen.setText(s.count(), norm == null || norm.getAmount() <= 1 ? Component.empty()
                    : Text.item("<white>" + norm.getAmount() + "</white>"));
            }
        }
        if (this.picked != null) {
            final ItemStack p = this.get(this.picked);
            if (p == null || p.isEmpty()) {
                this.picked = null;
                this.repaintSlots();
            }
        }
        if (this.tab == Tab.SKILLS) {
            final Mmocore.View v = Mmocore.read(this.player, SKILL_MAX);
            if (!signature(v).equals(this.skillSignature)) {
                this.switchTab(Tab.SKILLS);
                return;
            }
        }
        this.updateStats();
        if (this.tab == Tab.CHARACTER) {
            this.updateCharacter();
        }
        this.updateTooltip();
    }

    private String ph(final String placeholder) {
        return Papi.value(this.player, placeholder);
    }

    private void updateStats() {
        final Settings st = this.settings;
        final Settings.Placeholders p = st.ph();
        final String level = or(this.ph(p.level()), Integer.toString(this.player.getLevel()));
        final String cls = known(this.ph(p.className()));
        final String classKey = key(this.ph(p.classKey()));
        final String prof = known(this.ph(p.profession()));
        final String profKey = key(this.ph(p.professionKey()));

        Screen.setText(this.nick, Text.item("<yellow><name></yellow>" + (cls == null ? "" : "<gray> · <cls></gray>"),
            Placeholder.unparsed("name", this.player.getName()), Placeholder.unparsed("cls", cls == null ? "" : cls)));

        // крыло «Класс»
        final Settings.ClassInfo info = classInfo(st, classKey, cls);
        final Material icon = info == null ? st.noClassIcon() : info.icon();
        if (icon != this.classIconShown && this.classIcon.isValid()) {
            this.classIconShown = icon;
            this.classIcon.setItemStack(new ItemStack(icon));
        }
        Screen.setText(this.className, Text.item(cls == null ? "<gray>Без класса</gray>" : "<gold><c></gold>",
            Placeholder.unparsed("c", cls == null ? "" : cls)));
        Screen.setText(this.profession, Text.item(prof == null ? "<dark_gray>нет профессии</dark_gray>" : "<white><c></white>",
            Placeholder.unparsed("c", prof == null ? "" : prof)));
        Screen.setText(this.levelText, Text.item("<green>Ур. <l></green>", Placeholder.unparsed("l", level)));

        final double exp = number(this.ph(p.exp()), -1);
        final double need = number(this.ph(p.expNeeded()), -1);
        final boolean rqExp = exp >= 0 && need > 0;
        final double expPart = rqExp ? exp / need : this.player.getExp();
        final String expLine = rqExp ? Text.num(exp) + " / " + Text.num(need) : Math.round(this.player.getExp() * 100) + "%";
        Screen.setText(this.expShort, Text.item("<gray><e></gray>", Placeholder.unparsed("e", expLine)));
        this.expFill.width(BAR_TEX_W * clamp01(expPart));
        Screen.setText(this.expText, Text.item("<white>Ур. <l> · <e></white>",
            Placeholder.unparsed("l", level), Placeholder.unparsed("e", expLine)));

        // здоровье
        final AttributeInstance maxHp = this.player.getAttribute(Attribute.MAX_HEALTH);
        final double max = maxHp == null ? 20 : maxHp.getValue();
        this.hpFill.width(BAR_TEX_W * clamp01(this.player.getHealth() / max));
        Screen.setText(this.hpText, Text.item("<white>Здоровье " + Text.num(Math.ceil(this.player.getHealth())) + " / "
            + Text.num(max) + "</white>"));

        // мана или энергия — как в MythicHUD
        final Settings.ResourceType res = this.resource(classKey);
        final double cur = res == null ? -1 : number(this.ph(res.current()), -1);
        final double cap = res == null ? -1 : number(this.ph(res.max()), -1);
        final boolean energy = res != null && res.id().equals("energy");
        final double resPart = cur >= 0 && cap > 0 ? clamp01(cur / cap) : 0;
        this.manaFill.width(energy ? 0 : BAR_TEX_W * resPart);
        this.energyFill.width(energy ? BAR_TEX_W * resPart : 0);
        final String label = res == null ? "Мана" : res.label();
        Screen.setText(this.manaText, Text.item(cur >= 0 && cap > 0
            ? "<white><r> " + Text.num(Math.floor(cur)) + " / " + Text.num(Math.floor(cap)) + "</white>"
            : "<gray><r> —</gray>", Placeholder.unparsed("r", label)));

        // параметры 2 × 2
        for (int i = 0; i < this.statTexts.size(); i++) {
            Screen.setText(this.statTexts.get(i), i < st.stats().size() ? this.statLine(st.stats().get(i)) : Component.empty());
        }

        // крыло «Очки»
        Screen.setText(this.skillPoints, Text.item("<gold><v></gold>",
            Placeholder.unparsed("v", or(this.ph(p.skillPoints()), "—"))));
        Screen.setText(this.attributePoints, Text.item("<gold><v></gold>",
            Placeholder.unparsed("v", or(this.ph(p.attributePoints()), "—"))));
        this.updateClassList(info, profKey, level);
    }

    /** Ресурс класса: по ключу класса из config.yml, иначе по имени ресурса MMOCore (как MythicHUD). */
    private Settings.ResourceType resource(final String classKey) {
        final Settings.Resources r = this.settings.resources();
        final String byClass = classKey == null ? null : r.classes().get(classKey);
        if (byClass != null && r.type(byClass) != null) {
            return r.type(byClass);
        }
        final String name = this.ph(r.namePlaceholder());
        if (name != null) {
            return r.type(name.strip().equalsIgnoreCase(r.energyName()) ? "energy" : "mana");
        }
        return r.type("mana");
    }

    private Component statLine(final Settings.Stat stat) {
        final String rawName = this.ph(stat.name());
        final String name = rawName != null ? rawName : stat.name().contains("%") ? stat.fallbackName() : stat.name();
        final String value = or(this.ph(stat.value()), stat.value().contains("%") ? "—" : stat.value());
        return Text.item("<gray><n></gray> <white><v></white>", Placeholder.unparsed("n", name),
            Placeholder.unparsed("v", value));
    }

    /** Список под очками: профессии своего класса (своя — золотом) или, без класса, все классы. */
    private void updateClassList(final Settings.ClassInfo info, final String profKey, final String level) {
        final List<Component> lines = new ArrayList<>();
        final List<Material> icons = new ArrayList<>();
        icons.add(null);
        if (info == null) {
            lines.add(Text.item("<gold>Классы</gold>"));
            for (final Settings.ClassInfo c : this.settings.classes().values()) {
                lines.add(Text.item("<white><n></white>", Placeholder.unparsed("n", c.name())));
                icons.add(c.icon());
            }
        } else {
            lines.add(Text.item("<gold>Профессии</gold>"));
            for (final String key : info.professions()) {
                final String name = this.settings.professions().getOrDefault(key, key);
                lines.add(Text.item(key.equals(profKey) ? "<yellow>▶ <n></yellow>" : "<gray><n></gray>",
                    Placeholder.unparsed("n", name)));
                icons.add(info.icon());
            }
            if (profKey == null) {
                final boolean can = "true".equalsIgnoreCase(this.ph(this.settings.ph().needsProfession()));
                lines.add(Text.item(can ? "<green>Можно выбрать!</green>" : "<dark_gray>с 20 ур.</dark_gray>"));
            }
        }
        for (int i = 0; i < this.classList.size(); i++) {
            Screen.setText(this.classList.get(i), i < lines.size() ? lines.get(i) : Component.empty());
            final ItemDisplay d = this.classListIcons.get(i);
            final Material m = i < icons.size() ? icons.get(i) : null;
            while (this.classListShown.size() <= i) {
                this.classListShown.add(null);
            }
            if (d != null && d.isValid() && m != this.classListShown.get(i)) {
                this.classListShown.set(i, m);
                d.setItemStack(m == null ? null : new ItemStack(m));
            }
        }
    }

    private void updateTooltip() {
        final Mmocore.Skill skill = this.lastHover == null ? null : this.skillOf.get(this.lastHover);
        if (skill != null) {
            Component line = Text.item("<gold><n></gold>", Placeholder.unparsed("n", skill.name()));
            if (!skill.loreFirst().isBlank()) {
                line = line.append(Text.item("<gray> — </gray>")).append(
                    LegacyComponentSerializer.legacySection().deserialize(skill.loreFirst().replace('&', '§')));
            }
            Screen.setText(this.help, line);
        } else {
            Screen.setText(this.help, Text.item(HELP));
        }
        final Integer index = this.lastHover == null ? null : this.slotOf.get(this.lastHover);
        Component text = Component.empty();
        Component second = Component.empty();
        if (index != null) {
            final ItemStack item = this.get(index);
            if (item != null && !item.isEmpty()) {
                text = item.effectiveName().append(Text.item(item.getAmount() > 1 ? " <gray>×" + item.getAmount() + "</gray>" : ""));
                second = Text.item("<dark_gray>" + slotName(index) + "</dark_gray>");
            } else {
                text = Text.item("<dark_gray>" + slotName(index) + "</dark_gray>");
                if (Jewelry.isJewel(index)) {
                    second = Text.item("<dark_gray>Характеристики бижутерии пока не действуют</dark_gray>");
                }
            }
        } else if (this.picked != null) {
            final ItemStack item = this.get(this.picked);
            if (item != null && !item.isEmpty()) {
                text = Text.item("<gray>Выбрано:</gray> ").append(item.effectiveName());
                second = Text.item("<dark_gray>Нажмите на клетку, куда положить</dark_gray>");
            }
        }
        Screen.setText(this.tooltip, text);
        Screen.setText(this.tooltip2, second);
        this.updateFloatingTip(index);
    }

    /** Подпись последней всплывающей подсказки: пересоздаём только когда она меняется. */
    private String tipSignature = "";

    /**
     * Всплывающая подсказка как в ванильном инвентаре: полный тултип предмета под курсором (название, описание,
     * зачарования, характеристики) — справа от слота (или слева, если не влезает), прижата к холсту.
     */
    private void updateFloatingTip(final Integer index) {
        List<Component> lines = List.of();
        double ax = 0;
        double ay = 0;
        if (index != null) {
            final ItemStack item = this.get(index);
            final Slot slot = this.slots.get(index);
            if (item != null && !item.isEmpty() && slot != null) {
                lines = tooltipLines(item, this.player);
                ax = slot.x();
                ay = slot.y();
            }
        }
        final String sig = lines.isEmpty() ? "" : ax + "," + ay + ":" + lines;
        if (sig.equals(this.tipSignature)) {
            return;
        }
        this.tipSignature = sig;
        this.screen.clear(TIP);
        if (lines.isEmpty()) {
            return;
        }
        final TipLayout t = TipLayout.of(lines.stream().map(c ->
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c)).toList(),
            ax, ay, WELL);
        Component text = Component.empty();
        for (int i = 0; i < t.lines(); i++) {
            text = text.append(i == 0 ? lines.get(i) : Component.newline().append(lines.get(i)));
        }
        if (t.cut()) {
            text = text.append(Component.newline()).append(Text.item("<dark_gray>…</dark_gray>"));
        }
        this.screen.textBox(t.left(), t.bottom(), t.width(), TipLayout.WRAP_W, TipLayout.LH, text, L_TIP, TIP_BG, TIP);
    }

    private static final org.bukkit.Color TIP_BG = org.bukkit.Color.fromARGB(236, 16, 6, 24);

    /** Полный тултип предмета (Paper), а если не вышло — название и описание. */
    static List<Component> tooltipLines(final ItemStack item, final Player player) {
        try {
            final List<Component> l = item.computeTooltipLines(
                io.papermc.paper.inventory.tooltip.TooltipContext.create(false, false), player);
            if (!l.isEmpty()) {
                return l;
            }
        } catch (final RuntimeException | LinkageError e) {
            // ниже — запасной вариант
        }
        final List<Component> out = new ArrayList<>();
        out.add(item.effectiveName());
        final List<Component> lore = item.lore();
        if (lore != null) {
            out.addAll(lore);
        }
        return out;
    }

    private static String slotName(final int index) {
        if (Stash.isStash(index)) {
            return "Хранилище · ряд " + ((index - Stash.BASE) / Stash.COLS + 1);
        }
        if (Jewelry.isJewel(index)) {
            return Jewelry.slotName(index);
        }
        return switch (index) {
            case 39 -> "Шлем";
            case 38 -> "Нагрудник";
            case 37 -> "Поножи";
            case 36 -> "Ботинки";
            case OFFHAND -> "Вторая рука";
            default -> index < 9 ? "Панель быстрого доступа" : "Карман";
        };
    }

    private void repaintSlots() {
        final Slot sel = this.picked == null ? null : this.slots.get(this.picked);
        if (sel == null) {
            this.slotSelected.hide();
        } else {
            this.slotSelected.place(X(sel.x()), Y(sel.y()));
        }
    }

    // --- управление инвентарём и бижутерией ---

    private ItemStack get(final int index) {
        if (Stash.isStash(index)) {
            return this.stash.open(index) ? this.stash.get(index) : null;
        }
        return Jewelry.isJewel(index) ? this.jewelry.get(index) : this.player.getInventory().getItem(index);
    }

    private void set(final int index, final ItemStack item) {
        if (Stash.isStash(index)) {
            this.stash.set(index, item);
        } else if (Jewelry.isJewel(index)) {
            this.jewelry.set(index, item);
        } else {
            this.player.getInventory().setItem(index, item == null || item.isEmpty() ? null : item);
        }
    }

    private void clickSlot(final int index) {
        if (Stash.isStash(index) && (!this.stash.open(index) || this.stash.locked(index))) {
            this.player.sendActionBar(Text.mm("<red>Слот хранилища не прочитался — обратитесь к администрации</red>"));
            return;
        }
        if (this.picked != null && Stash.isStash(this.picked) && (!this.stash.open(this.picked) || this.stash.locked(this.picked))) {
            this.picked = null;
        }
        if (Jewelry.isJewel(index) && this.jewelry.locked(index)) {
            this.player.sendActionBar(Text.mm("<red>Слот не прочитался — обратитесь к администрации</red>"));
            return;
        }
        if (this.picked == null) {
            final ItemStack item = this.get(index);
            if (item == null || item.isEmpty()) {
                return;
            }
            this.picked = index;
        } else if (this.picked == index) {
            this.picked = null;
        } else {
            final String error = this.move(this.picked, index);
            if (error != null) {
                this.player.sendActionBar(Text.mm("<red>" + error + "</red>"));
                this.sound(Sound.ENTITY_VILLAGER_NO);
            } else {
                this.sound(Sound.ITEM_ARMOR_EQUIP_GENERIC);
            }
            this.picked = null;
        }
        this.repaintSlots();
        this.refresh();
    }

    /**
     * Сколько штук предмета {@code item} помещается в клетку {@code index}: хранилище — до 99 (то, что стакается,
     * см. {@link StackMath}), бижутерия и броня — 1, карман и вторая рука — ванильный предел.
     */
    private static int limit(final int index, final ItemStack item) {
        if (Jewelry.isJewel(index) || ARMOR.containsKey(index)) {
            return 1;
        }
        final int max = Math.max(1, item.getMaxStackSize());
        return Stash.isStash(index) ? StackMath.stashLimit(max) : max;
    }

    /**
     * Переложить из {@code from} в {@code to}: в пустую клетку (сколько влезет, остальное остаётся), сложить
     * одинаковые (до предела клетки) или поменять разные местами (если обе стопки влезают целиком). Штуки никогда не
     * появляются и не пропадают. null — получилось, иначе текст ошибки.
     */
    private String move(final int from, final int to) {
        final ItemStack a = this.get(from);
        final ItemStack b = this.get(to);
        final boolean bEmpty = b == null || b.isEmpty();
        if (a == null || a.isEmpty()) {
            return "Предмета уже нет";
        }
        if (this.stuck(from, a) || (!bEmpty && this.stuck(to, b))) {
            return "Проклятие несъёмности: снять нельзя";
        }
        if (!this.fits(to, a)) {
            if (Jewelry.isJewel(to)) {
                final String deny = Jewelry.denyReason(this.settings, this.player, to, a);
                return deny != null ? deny : "Это не " + Jewelry.slotName(to).toLowerCase(Locale.ROOT);
            }
            return "Этот предмет сюда не надеть";
        }
        final boolean similar = !bEmpty && a.isSimilar(b);
        if (!bEmpty && !similar && !this.fits(from, b)) {
            return "Нельзя поменять местами: " + slotName(from).toLowerCase(Locale.ROOT) + " не подходит";
        }
        final StackMath.Plan plan = StackMath.plan(a.getAmount(), bEmpty ? 0 : b.getAmount(), similar,
            limit(to, a), bEmpty ? 1 : limit(from, b));
        switch (plan.kind()) {
            case MOVE, MERGE -> {
                final ItemStack put = a.clone();
                put.setAmount(plan.to());
                final ItemStack left = a.clone();
                left.setAmount(Math.max(1, plan.from()));
                this.set(to, put);
                this.set(from, plan.from() <= 0 ? null : left);
                return null;
            }
            case SWAP -> {
                this.set(to, a);
                this.set(from, b);
                return null;
            }
            case FULL -> {
                return Jewelry.isJewel(to) ? "Здесь уже лежит такой же: сюда кладётся один"
                    : "Здесь уже полный стак (" + b.getAmount() + ")";
            }
            default -> {
                if (Jewelry.isJewel(to) && a.getAmount() > 1) {
                    return "Сначала уберите предмет из слота: сюда кладётся один";
                }
                if (Jewelry.isJewel(from) && !bEmpty && b.getAmount() > 1) {
                    return "Нельзя: в " + Jewelry.slotName(from).toLowerCase(Locale.ROOT) + " кладётся один предмет";
                }
                if (a.getAmount() > limit(to, a)) {
                    return "Не помещается: сюда не больше " + limit(to, a) + " — положите в пустую клетку";
                }
                return "Нельзя поменять местами: туда не больше " + limit(from, b);
            }
        }
    }

    /** Подходит ли предмет в слот: броню — только в свой слот, бижутерию — по правилам config.yml. */
    private boolean fits(final int slot, final ItemStack item) {
        if (item == null || item.isEmpty()) {
            return true;
        }
        if (Jewelry.isJewel(slot)) {
            return Jewelry.denyReason(this.settings, this.player, slot, item) == null;
        }
        final EquipmentSlot need = ARMOR.get(slot);
        return need == null || item.getType().getEquipmentSlot() == need;
    }

    /** Надетый предмет с проклятием несъёмности (в творческом снимать можно). */
    private boolean stuck(final int slot, final ItemStack item) {
        // Сейчас игрок — наблюдатель: решает режим, в котором он был до меню.
        return ARMOR.containsKey(slot) && this.screen.saved().mode() != GameMode.CREATIVE
            && item.containsEnchantment(Enchantment.BINDING_CURSE);
    }

    private void sound(final Sound sound) {
        this.player.playSound(this.player.getLocation(), sound, 0.6f, 1.2f);
    }

    // --- мелочи ---

    private static String or(final String value, final String fallback) {
        return value == null ? fallback : value;
    }

    /** RoyalQuestClasses отвечает «???», если класса или профессии нет. */
    /** Класс по ключу (%hl_class_key%), а если ключ не подставился — по названию (%hl_class%), без учёта регистра. */
    static Settings.ClassInfo classInfo(final Settings st, final String classKey, final String className) {
        if (classKey != null) {
            final Settings.ClassInfo byKey = st.classes().get(classKey);
            if (byKey != null) {
                return byKey;
            }
        }
        if (className != null) {
            final String plain = className.replaceAll("(?i)[§&][0-9a-fk-orx]", "").replaceAll("<[^>]*>", "").strip();
            for (final Settings.ClassInfo c : st.classes().values()) {
                if (c.name().equalsIgnoreCase(plain) || c.key().equalsIgnoreCase(plain)) {
                    return c;
                }
            }
        }
        return null;
    }

    private static String known(final String value) {
        return value == null || value.isBlank() || value.equals("???") ? null : value;
    }

    /** Ключ класса/профессии в нижнем регистре; «none» — нет. */
    private static String key(final String value) {
        final String v = known(value);
        return v == null || v.equalsIgnoreCase("none") ? null : v.strip().toLowerCase(Locale.ROOT);
    }

    private static double number(final String raw, final double fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.replace(',', '.').replaceAll("[^0-9.\\-]", ""));
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    private static double clamp01(final double v) {
        return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }
}
