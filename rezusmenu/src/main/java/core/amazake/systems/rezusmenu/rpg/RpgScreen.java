package core.amazake.systems.rezusmenu.rpg;

import core.amazake.systems.rezusmenu.Settings;
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
 * «Очки», сверху лента, снизу подсказка), в нём панель 512 × 288 в (96, 56). На панели вкладки Инвентарь /
 * Снаряжение / Навыки / Задания, слева персонаж (скин во весь рост), броня, бижутерия, полоски и параметры, справа —
 * содержимое вкладки. Инвентарь управляется: клик — выбрать предмет, клик по другой клетке — переложить (сложить,
 * поменять местами, надеть, положить в бижутерию). Предметы никогда не покидают инвентарь и слоты бижутерии.
 *
 * <p>Координаты в коде — пиксели панели (как в арте), на холст их переводит {@link #X}/{@link #Y}. Всё — в потоке
 * игрока.</p>
 */
public final class RpgScreen {

    private static final String STATIC = "static";
    private static final String CONTENT = "content";
    private static final String PORTRAIT = "portrait";

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

    enum Tab { INVENTORY, EQUIPMENT, SKILLS, QUESTS }

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
    private final Map<Integer, Slot> slots = new HashMap<>();
    private final Map<Screen.Button, Integer> slotOf = new HashMap<>();
    private final Map<Screen.Button, Mmocore.Skill> skillOf = new HashMap<>();
    private final Map<Integer, ItemStack> shown = new HashMap<>();
    private Tab tab = Tab.INVENTORY;
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
    private TextDisplay equipmentText;
    private TextDisplay skillPointsLine;

    public RpgScreen(final Screen screen, final Settings settings, final SkinService skins,
                     final Consumer<Runnable> playerThread, final Runnable closeAction, final Runnable calibrateAction) {
        this.screen = screen;
        screen.hoverLayer("skill_hover", L_HOVER_SKILL);
        this.player = screen.player();
        this.settings = settings;
        this.skins = skins;
        this.playerThread = playerThread;
        this.closeAction = closeAction;
        this.calibrateAction = calibrateAction;
        this.jewelry = new Jewelry(this.player);
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
        final String[] names = {"Инвентарь", "Снаряжение", "Навыки", "Задания"};
        final Material[] icons = {Material.BUNDLE, Material.IRON_SWORD, Material.ENCHANTED_BOOK, Material.FILLED_MAP};
        for (int i = 0; i < 4; i++) {
            final Tab t = Tab.values()[i];
            final int x = TAB_X[i];
            this.button(x, TAB_Y, TAB_W, TAB_H, "tab_hover", STATIC, "вкладка " + names[i], b -> this.switchTab(t));
            this.item(x + 16, TAB_Y + TAB_H / 2.0, 16, new ItemStack(icons[i]), L_ICON, STATIC);
            this.text(x + 26, TAB_Y, TAB_W - 30, TAB_H, 10, Text.item("<white>" + names[i] + "</white>"), L_TEXT, STATIC);
        }

        this.buildWings();

        // левая секция: персонаж
        this.nick = this.text(16, 52, 186, 16, 9, Component.empty(), L_TEXT, STATIC);
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
        this.switchTab(Tab.INVENTORY);
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
            this.classList.add(s.text(620, 224 + i * 16, 72, 16, 7, Component.empty(), L_TEXT, STATIC));
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
        final String group = index >= 36 ? STATIC : CONTENT;
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
        this.slots.values().removeIf(s -> s.index() < 36);
        this.slotOf.values().removeIf(i -> i < 36);
        this.skillOf.clear();
        this.equipmentText = null;
        this.tooltip = null;
        this.tooltip2 = null;
        this.skillPointsLine = null;
        this.skillSignature = "";
        this.tabActive.place(X(TAB_X[next.ordinal()]), Y(TAB_Y));
        if (this.picked != null && this.picked < 36 && next != Tab.INVENTORY) {
            this.picked = null;
        }
        switch (next) {
            case INVENTORY -> {
                this.heading("Инвентарь");
                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < 9; col++) {
                        this.slot(9 + row * 9 + col, GRID_X + col * GRID_STEP, GRID_Y[row]);
                    }
                }
                for (int col = 0; col < 9; col++) {
                    this.slot(col, GRID_X + col * GRID_STEP, HOTBAR_Y);
                }
                this.rightCommon();
            }
            case EQUIPMENT -> {
                this.heading("Снаряжение и характеристики");
                this.equipmentText = this.text(RIGHT_X + 4, 74, RIGHT_W - 8, 128, 9, Component.empty(), L_TEXT, CONTENT);
                this.rightCommon();
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

    /** Подсказка предмета (две строки) и кнопки — у всех вкладок, кроме «Навыков» (её фон закрывает это место). */
    private void rightCommon() {
        this.tooltip = this.text(RIGHT_X, 206, RIGHT_W, 14, 9, Component.empty(), L_TEXT, CONTENT);
        this.tooltip2 = this.text(RIGHT_X, 224, RIGHT_W, 12, 7, Component.empty(), L_TEXT, CONTENT);
        this.button(218, 244, 134, 28, "button_hover", CONTENT, "Калибровка", b -> this.calibrateAction.run());
        this.text(218, 244, 134, 28, 10, Text.item("<yellow>Калибровка</yellow>"), L_TEXT, CONTENT);
        this.button(360, 244, 134, 28, "button_hover", CONTENT, "Закрыть", b -> this.closeAction.run());
        this.text(360, 244, 134, 28, 10, Text.item("<white>Закрыть</white>"), L_TEXT, CONTENT);
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
                    s.icon().setItemStack(norm == null ? null : norm.clone());
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
        final Settings.ClassInfo info = classKey == null ? null : st.classes().get(classKey);
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

        if (this.equipmentText != null) {
            Screen.setText(this.equipmentText, Text.item(this.equipmentLines()));
        }
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
        if (info == null) {
            lines.add(Text.item("<gold>Классы</gold>"));
            for (final Settings.ClassInfo c : this.settings.classes().values()) {
                lines.add(Text.item("<white><n></white>", Placeholder.unparsed("n", c.name())));
            }
        } else {
            lines.add(Text.item("<gold>Профессии</gold>"));
            for (final String key : info.professions()) {
                final String name = this.settings.professions().getOrDefault(key, key);
                lines.add(Text.item(key.equals(profKey) ? "<yellow>▶ <n></yellow>" : "<gray><n></gray>",
                    Placeholder.unparsed("n", name)));
            }
            if (profKey == null) {
                final boolean can = "true".equalsIgnoreCase(this.ph(this.settings.ph().needsProfession()));
                lines.add(Text.item(can ? "<green>Можно выбрать!</green>" : "<dark_gray>с 20 ур.</dark_gray>"));
            }
        }
        for (int i = 0; i < this.classList.size(); i++) {
            Screen.setText(this.classList.get(i), i < lines.size() ? lines.get(i) : Component.empty());
        }
    }

    private String equipmentLines() {
        final StringBuilder b = new StringBuilder();
        final AttributeInstance armor = this.player.getAttribute(Attribute.ARMOR);
        final AttributeInstance dmg = this.player.getAttribute(Attribute.ATTACK_DAMAGE);
        b.append("<gray>Броня (всего)</gray> <white>").append(armor == null ? "—" : Text.num(armor.getValue())).append("</white>\n");
        b.append("<gray>Урон рукой (всего)</gray> <white>").append(dmg == null ? "—" : Text.num(dmg.getValue())).append("</white>");
        for (final Settings.Stat stat : this.settings.equipmentLines()) {
            final String value = this.ph(stat.value());
            if (value == null && stat.value().contains("%")) {
                continue;
            }
            b.append("\n<gray>").append(Text.escape(stat.name())).append("</gray> <white>")
                .append(Text.escape(value == null ? stat.value() : value)).append("</white>");
        }
        return b.toString();
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
    }

    private static String slotName(final int index) {
        if (Jewelry.isJewel(index)) {
            return Jewelry.slotName(index);
        }
        return switch (index) {
            case 39 -> "Шлем";
            case 38 -> "Нагрудник";
            case 37 -> "Поножи";
            case 36 -> "Ботинки";
            case OFFHAND -> "Вторая рука";
            default -> index < 9 ? "Панель быстрого доступа" : "Пусто";
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
        return Jewelry.isJewel(index) ? this.jewelry.get(index) : this.player.getInventory().getItem(index);
    }

    private void set(final int index, final ItemStack item) {
        if (Jewelry.isJewel(index)) {
            this.jewelry.set(index, item);
        } else {
            this.player.getInventory().setItem(index, item == null || item.isEmpty() ? null : item);
        }
    }

    private void clickSlot(final int index) {
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

    /** Переложить из {@code from} в {@code to}: сложить одинаковые или поменять местами. null — получилось. */
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
            return Jewelry.isJewel(to) ? "Это не " + Jewelry.slotName(to).toLowerCase(Locale.ROOT) : "Этот предмет сюда не надеть";
        }
        if (!bEmpty && !this.fits(from, b)) {
            return "Нельзя поменять местами: " + slotName(from).toLowerCase(Locale.ROOT) + " не подходит";
        }
        // В слот бижутерии кладётся один предмет.
        if (Jewelry.isJewel(to) && a.getAmount() > 1) {
            if (!bEmpty) {
                return "Сначала уберите предмет из слота: сюда кладётся один";
            }
            final ItemStack one = a.clone();
            one.setAmount(1);
            a.setAmount(a.getAmount() - 1);
            this.set(to, one);
            this.set(from, a);
            return null;
        }
        if (!bEmpty && a.isSimilar(b) && !Jewelry.isJewel(to) && b.getAmount() < b.getMaxStackSize()) {
            final int moved = Math.min(a.getAmount(), b.getMaxStackSize() - b.getAmount());
            b.setAmount(b.getAmount() + moved);
            a.setAmount(a.getAmount() - moved);
            this.set(to, b);
            this.set(from, a.getAmount() <= 0 ? null : a);
            return null;
        }
        // Обмен кладёт b в слот бижутерии — туда можно только один предмет.
        if (Jewelry.isJewel(from) && !bEmpty && b.getAmount() > 1) {
            return "Нельзя: в " + Jewelry.slotName(from).toLowerCase(Locale.ROOT) + " кладётся один предмет";
        }
        this.set(to, a);
        this.set(from, bEmpty ? null : b);
        return null;
    }

    /** Подходит ли предмет в слот: броню — только в свой слот, бижутерию — по правилам config.yml. */
    private boolean fits(final int slot, final ItemStack item) {
        if (item == null || item.isEmpty()) {
            return true;
        }
        if (Jewelry.isJewel(slot)) {
            return Jewelry.allowed(this.settings, slot, item);
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
