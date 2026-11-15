package core.amazake.systems.rezusmenu.profiles;

import com.destroystokyo.paper.profile.PlayerProfile;
import core.amazake.systems.rezusmenu.rpg.SkinService;
import core.amazake.systems.rezusmenu.screen.PanelMath;
import core.amazake.systems.rezusmenu.screen.Screen;
import core.amazake.systems.rezusmenu.screen.SkinFront;
import core.amazake.systems.rezusmenu.util.Text;
import java.net.URL;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerTextures;

/**
 * Меню выбора профилей — второй экран rezusmenu на том же холсте 704 × 400, курсоре и кликах, что и RPG-меню.
 * Фон — нарисованная поляна, по центру — персонаж игрока (его скин в три четверти, «пикселями»), слева вверху —
 * вывеска AZERUS QXCER, слева внизу — «В лобби», справа — панель «Ваши профили»: карточки по две (прокрутка
 * стрелками), под каждой кнопка, внизу — покупка слота. Новый профиль — выбор одного из классов. Поток игрока.
 */
public final class ProfilesScreen implements ProfileService.Host {

    // холст, px (совпадает с tools/profiles_art.py и tools/profiles_preview.py)
    static final int LOGO_X = 8;
    static final int LOGO_Y = 6;
    static final int LOBBY_X = 10;
    static final int LOBBY_Y = 366;
    static final int PANEL_X = 472;
    static final int PANEL_Y = 36;
    static final int CARD_X = 480;
    static final int[] CARD_Y = {72, 196};
    static final int BTN_DY = 98;
    static final int BUY_Y = 324;
    static final int ARROW_X = 452;
    static final int ARROW_UP_Y = 72;
    static final int ARROW_DOWN_Y = 296;
    static final int ROW_Y0 = 72;
    static final int ROW_STEP = 62;
    static final int FEET_X = 300;
    static final int FEET_Y = 346;
    static final int CHAR_PX = 3;
    static final double CHAR_SCALE = 2.25;
    static final int BUST_PX = 4;

    private static final int L_BG = 0;
    private static final int L_PANEL = 1;
    private static final int L_CARD = 2;
    private static final int L_ICON = 3;
    private static final int L_TEXT = 6;
    private static final String STATIC = "pf_static";
    private static final String LIST = "pf_list";
    private static final String CHAR = "pf_char";

    private enum Mode { LIST, PICK_NEW, PICK_CLASS, BUSY }

    private final Screen screen;
    private final Player player;
    private final ProfileService service;
    private final SkinService skins;
    private final Consumer<Runnable> playerThread;
    private final Runnable closeAction;
    private final Runnable lobbyAction;
    private final boolean shaderCursor;
    private Mode mode = Mode.LIST;
    private int offset;
    private int buyConfirmUntil = -1;
    private String skinUrl;
    private boolean slim;

    public ProfilesScreen(final Screen screen, final ProfileService service, final SkinService skins,
                          final boolean shaderCursor, final Consumer<Runnable> playerThread, final Runnable closeAction,
                          final Runnable lobbyAction) {
        this.screen = screen;
        this.player = screen.player();
        this.service = service;
        this.skins = skins;
        this.shaderCursor = shaderCursor;
        this.playerThread = playerThread;
        this.closeAction = closeAction;
        this.lobbyAction = lobbyAction;
        screen.hoverLayer("pf_btn_hover", 4);
        screen.hoverLayer("pf_buy_hover", 4);
        screen.hoverLayer("pf_row_hover", 4);
        screen.hoverLayer("pf_lobby_hover", 4);
        screen.hoverLayer("pf_arrow_hover", 4);
    }

    // --- постройка ---

    public void build() {
        final Screen s = this.screen;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                s.quad("pf_bg_" + r + "_" + c, PanelMath.BACK_X[c], PanelMath.BACK_Y[r], L_BG, STATIC);
            }
        }
        s.quad("pf_logo", LOGO_X, LOGO_Y, L_PANEL, STATIC);
        s.quad("pf_lobby", LOBBY_X, LOBBY_Y, L_PANEL, STATIC);
        s.button(LOBBY_X, LOBBY_Y, 80, 22, "pf_lobby_hover", STATIC, "в лобби", b -> this.lobbyAction.run());
        s.text(LOBBY_X, LOBBY_Y, 80, 20, 9, Text.item("<white>⌂ В лобби</white>"), L_TEXT, STATIC);
        s.quad("pf_panel_0", PANEL_X, PANEL_Y, L_PANEL, STATIC);
        s.quad("pf_panel_1", PANEL_X, PANEL_Y + 160, L_PANEL, STATIC);
        this.character();
        s.arrowCursor(this.shaderCursor);
        this.showList();
    }

    /** Строка, прижатая влево (или вправо) — по ширине шрифта. */
    private void label(final double x, final double y, final double h, final double lh, final String mini,
                       final String plain, final boolean right, final int layer, final String group) {
        this.screen.label(x, y, h, lh, Text.item(mini), FontWidth.of(plain), right, layer, group);
    }

    private void center(final double x, final double y, final double w, final double h, final double lh,
                        final String mini) {
        this.screen.text(x, y, w, h, lh, Text.item(mini), L_TEXT, LIST);
    }

    private void header(final String title, final String right) {
        this.label(PANEL_X + 10, PANEL_Y + 4, 22, 11, "<white>" + Text.escape(title) + "</white>", title, false, L_TEXT, LIST);
        if (right != null) {
            this.label(PANEL_X + 214, PANEL_Y + 4, 22, 11, "<white>" + Text.escape(right) + "</white>", right, true,
                L_TEXT, LIST);
        }
    }

    // --- список профилей ---

    private void showList() {
        this.mode = Mode.LIST;
        final Screen s = this.screen;
        s.clear(LIST);
        final ProfileConfig c = this.service.config();
        final Account a = this.service.ensure(this.player);
        final List<ProfileService.Card> cards = this.service.cards(this.player);
        final int slots = a.slots(c.freeSlots(), c.maxSlots());
        final int entries = Math.max(cards.size(), slots);
        this.offset = Math.max(0, Math.min(this.offset, Math.max(0, entries - 2)));
        this.header("Ваши профили", cards.size() + " из " + c.maxSlots());
        for (int k = 0; k < 2; k++) {
            final int i = this.offset + k;
            if (i < cards.size()) {
                this.card(cards.get(i), CARD_X, CARD_Y[k]);
            } else if (i < entries) {
                this.emptyCard(CARD_X, CARD_Y[k]);
            }
        }
        if (entries > 2) {
            if (this.offset > 0) {
                s.quad("pf_arrow_up", ARROW_X, ARROW_UP_Y, L_CARD, LIST);
                s.button(ARROW_X, ARROW_UP_Y, 16, 16, "pf_arrow_hover", LIST, "вверх", b -> {
                    this.offset--;
                    this.showList();
                });
            }
            if (this.offset + 2 < entries) {
                s.quad("pf_arrow_down", ARROW_X, ARROW_DOWN_Y, L_CARD, LIST);
                s.button(ARROW_X, ARROW_DOWN_Y, 16, 16, "pf_arrow_hover", LIST, "вниз", b -> {
                    this.offset++;
                    this.showList();
                });
            }
        }
        if (a.canBuy(c.freeSlots(), c.maxSlots())) {
            s.quad("pf_buy", CARD_X, BUY_Y, L_CARD, LIST);
            final boolean confirm = Bukkit.getCurrentTick() < this.buyConfirmUntil;
            final String text = confirm ? "Купить слот за " + c.priceLabel() + "? Ещё раз" : "+ " + c.priceLabel();
            final double fw = FontWidth.of(text) * 10 / 10.0;
            final double left = CARD_X + 104 - (fw + 14) / 2;
            this.label(left, BUY_Y + 2, 20, 10, (confirm ? "<yellow>" : "<white>") + Text.escape(text) + "</white>",
                text, false, L_TEXT, LIST);
            s.item(left + fw + 8, BUY_Y + 12, 11, new ItemStack(c.priceIcon()), L_ICON, LIST);
            s.button(CARD_X, BUY_Y, 208, 24, "pf_buy_hover", LIST, "купить слот", b -> this.buy());
        } else {
            this.center(CARD_X, BUY_Y, 208, 24, 8, "<dark_gray>Открыты все слоты</dark_gray>");
        }
    }

    private void card(final ProfileService.Card card, final int x, final int y) {
        final Screen s = this.screen;
        final ProfileConfig c = this.service.config();
        s.quad("pf_card", x, y, L_CARD, LIST);
        this.bust(x + 4, y + 12);
        final ProfileConfig.ClassChoice cls = c.classChoice(card.classKey());
        final Account.Summary sum = card.summary();
        final String className = cls != null ? cls.name() : card.classKey().isBlank() ? "Без класса" : card.classKey();
        final String color = cls != null ? cls.color() : "<gray>";
        s.item(x + 80, y + 10, 10, new ItemStack(cls != null ? cls.icon() : Material.BOOK), L_ICON, LIST);
        this.label(x + 88, y + 4, 12, 8, "<gold>" + Text.escape(className) + "</gold>", className, false, L_TEXT, LIST);
        final String lvl = "Уровень:";
        this.label(x + 88, y + 16, 12, 8, "<white>" + lvl + "</white>", lvl, false, L_TEXT, LIST);
        final String lv = String.valueOf(sum.level());
        this.label(x + 201, y + 16, 12, 8, "<yellow>" + lv + "</yellow>", lv, true, L_TEXT, LIST);
        final String loc = fit(sum.location().isBlank() ? "—" : sum.location(), 112 * 10 / 8);
        this.label(x + 88, y + 30, 12, 8, "<white>" + Text.escape(loc) + "</white>", loc, false, L_TEXT, LIST);
        final List<Account.Stat> stats = sum.stats();
        for (int i = 0; i < Math.min(3, stats.size()); i++) {
            final String n = fit(stats.get(i).name(), 80 * 10 / 8) + ":";
            final String v = stats.get(i).value();
            this.label(x + 88, y + 52 + 14 * i, 12, 8, "<white>" + Text.escape(n) + "</white>", n, false, L_TEXT, LIST);
            this.label(x + 201, y + 52 + 14 * i, 12, 8, "<green>" + Text.escape(v) + "</green>", v, true, L_TEXT, LIST);
        }
        final boolean noClass = card.active() && card.classKey().isBlank();
        final String btn = card.active() ? (noClass ? "Выбрать класс" : "Продолжить") : "Выбрать профиль";
        s.quad(card.active() ? "pf_btn_green" : "pf_btn", x, y + BTN_DY, L_CARD, LIST);
        this.center(x, y + BTN_DY, 208, 18, 9, "<white>" + btn + "</white>");
        s.button(x, y + BTN_DY, 208, 20, "pf_btn_hover", LIST, "профиль " + card.id(), b -> {
            if (!card.active()) {
                this.service.switchTo(this.player, card.id(), this);
            } else if (noClass) {
                this.showPick(Mode.PICK_CLASS);
            } else {
                this.closeAction.run();
            }
        });
    }

    private void emptyCard(final int x, final int y) {
        final Screen s = this.screen;
        s.quad("pf_card_empty", x, y, L_CARD, LIST);
        this.center(x + 72, y + 26, 128, 14, 9, "<white>Свободный слот</white>");
        this.center(x + 72, y + 44, 128, 24, 7, "<gray>Новый персонаж\nс нуля</gray>");
        s.quad("pf_btn_green", x, y + BTN_DY, L_CARD, LIST);
        this.center(x, y + BTN_DY, 208, 18, 9, "<white>Создать профиль</white>");
        s.button(x, y + BTN_DY, 208, 20, "pf_btn_hover", LIST, "создать профиль", b -> this.showPick(Mode.PICK_NEW));
    }

    /** Обрезать строку до ширины (пиксели шрифта) с многоточием. */
    static String fit(final String s, final int maxFontPx) {
        if (FontWidth.of(s) <= maxFontPx) {
            return s;
        }
        String t = s;
        while (!t.isEmpty() && FontWidth.of(t + "…") > maxFontPx) {
            t = t.substring(0, t.length() - 1);
        }
        return t.strip() + "…";
    }

    private void buy() {
        final int now = Bukkit.getCurrentTick();
        if (now >= this.buyConfirmUntil) {
            this.buyConfirmUntil = now + 100;
            this.showList();
            return;
        }
        this.buyConfirmUntil = -1;
        final String err = this.service.buySlot(this.player);
        if (err != null) {
            this.message("<red>" + Text.escape(err) + "</red>");
        } else {
            this.message("<green>Слот профиля открыт!</green>");
        }
        this.showList();
    }

    // --- выбор класса ---

    private void showPick(final Mode m) {
        this.mode = m;
        final Screen s = this.screen;
        s.clear(LIST);
        this.header(m == Mode.PICK_NEW ? "Новый профиль: класс" : "Выберите класс", null);
        final List<ProfileConfig.ClassChoice> classes = this.service.config().classes();
        for (int i = 0; i < Math.min(4, classes.size()); i++) {
            final ProfileConfig.ClassChoice c = classes.get(i);
            final int y = ROW_Y0 + ROW_STEP * i;
            s.quad("pf_row", CARD_X, y, L_CARD, LIST);
            s.item(CARD_X + 26, y + 28, 28, new ItemStack(c.icon()), L_ICON, LIST);
            this.label(CARD_X + 54, y + 7, 14, 10, c.color() + Text.escape(c.name()), c.name(), false, L_TEXT, LIST);
            for (int j = 0; j < Math.min(2, c.description().size()); j++) {
                final String d = fit(c.description().get(j), 148 * 10 / 7);
                this.label(CARD_X + 54, y + 24 + j * 11, 10, 7, "<gray>" + Text.escape(d) + "</gray>", d, false,
                    L_TEXT, LIST);
            }
            s.button(CARD_X, y, 208, 56, "pf_row_hover", LIST, "класс " + c.key(), b -> this.pick(m, c));
        }
        s.quad("pf_buy", CARD_X, BUY_Y, L_CARD, LIST);
        this.center(CARD_X, BUY_Y, 208, 22, 9, "<white>Назад</white>");
        s.button(CARD_X, BUY_Y, 208, 24, "pf_buy_hover", LIST, "назад", b -> this.showList());
    }

    private void pick(final Mode m, final ProfileConfig.ClassChoice c) {
        if (m == Mode.PICK_NEW) {
            this.service.create(this.player, c.key(), this);
        } else {
            if (this.service.chooseClass(this.player, c.key())) {
                this.message("<green>Класс выбран: " + Text.escape(c.name()) + "</green>");
            } else {
                this.message("<red>Класс выдать не удалось.</red>");
            }
            this.showList();
        }
    }

    // --- персонаж и портреты ---

    private void skin() {
        if (this.skinUrl != null) {
            return;
        }
        try {
            final PlayerProfile profile = this.player.getPlayerProfile();
            final URL url = profile.getTextures().getSkin();
            this.skinUrl = url == null ? "" : url.toString();
            this.slim = profile.getTextures().getSkinModel() == PlayerTextures.SkinModel.SLIM;
        } catch (final RuntimeException e) {
            this.skinUrl = "";
        }
    }

    /** Персонаж по центру: из кэша сразу, иначе — голова, пока качается скин. */
    private void character() {
        this.skin();
        final int[] raw = this.skinUrl.isEmpty() ? null : this.skins.rawCached(this.skinUrl);
        if (raw != null) {
            this.drawCharacter(raw);
            return;
        }
        this.screen.item(FEET_X, FEET_Y - 150, 72, this.head(), L_ICON, CHAR);
        if (!this.skinUrl.isEmpty()) {
            this.skins.fetchRaw(this.skinUrl, this.slim, skin -> {
                if (skin != null) {
                    this.playerThread.accept(() -> {
                        if (!this.screen.closed()) {
                            this.screen.clear(CHAR);
                            this.drawCharacter(skin);
                            if (this.mode == Mode.LIST) {
                                this.showList(); // портреты на карточках — из того же скина
                            }
                        }
                    });
                }
            });
        }
    }

    private void drawCharacter(final int[] raw) {
        final ProfileConfig c = this.service.config();
        final CharacterRender.Sprite sp = CharacterRender.render(raw, this.slim, c.characterYaw(), c.characterPitch(),
            CHAR_SCALE);
        this.screen.pixels(sp.argb(), sp.w(), sp.h(), FEET_X - sp.feetX() * CHAR_PX, FEET_Y - sp.feetY() * CHAR_PX,
            CHAR_PX, L_ICON, CHAR);
    }

    /** Портрет на карточке: голова и плечи (верхние 20 строк вида спереди), 64 × 80 px; без скина — голова. */
    private void bust(final int x, final int y) {
        this.skin();
        final int[] front = this.skinUrl.isEmpty() ? null : this.skins.cached(this.skinUrl, this.slim);
        if (front == null) {
            this.screen.item(x + 32, y + 36, 44, this.head(), L_ICON, LIST);
            return;
        }
        final int rows = 20;
        final int[] crop = new int[SkinFront.W * rows];
        System.arraycopy(front, 0, crop, 0, crop.length);
        this.screen.pixels(crop, SkinFront.W, rows, x, y, BUST_PX, L_ICON, LIST);
    }

    private ItemStack head() {
        final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, m -> m.setOwningPlayer(this.player));
        return head;
    }

    // --- ProfileService.Host ---

    @Override
    public void close() {
        this.closeAction.run();
    }

    @Override
    public void busy(final boolean busy) {
        if (this.screen.closed()) {
            return;
        }
        if (busy) {
            this.mode = Mode.BUSY;
            this.screen.clear(LIST);
            this.header("Ваши профили", null);
            this.center(CARD_X, 160, 208, 40, 10, "<yellow>Загрузка профиля…</yellow>");
        } else {
            this.showList();
        }
    }

    @Override
    public void message(final String mini) {
        this.player.sendActionBar(Text.mm(mini));
        this.player.sendMessage(Text.mm(Text.prefix() + mini));
    }

    public boolean busy() {
        return this.mode == Mode.BUSY;
    }

}
