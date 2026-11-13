package core.amazake.systems.corerpg.progression;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corerpg.CoreModule;
import core.amazake.systems.corerpg.CoreRpgSystem;
import core.amazake.systems.corerpg.aspect.Aspect;
import core.amazake.systems.corerpg.aspect.AspectSnapshot;
import core.amazake.systems.corerpg.combat.CombatModule;
import core.amazake.systems.corerpg.profile.PlayerProfile;
import core.amazake.systems.corerpg.profile.ProfileStore;
import core.amazake.systems.corerpg.progression.command.ClassCommand;
import core.amazake.systems.corerpg.progression.command.MyClassCommand;
import core.amazake.systems.corerpg.progression.command.ProfessionCommand;
import core.amazake.systems.corerpg.progression.command.StatsCommand;
import core.amazake.systems.corerpg.progression.menu.CharacterMenu;
import core.amazake.systems.corerpg.progression.menu.ClassMenu;
import core.amazake.systems.corerpg.progression.menu.MenuItems;
import core.amazake.systems.corerpg.progression.menu.ProfessionMenu;
import core.amazake.systems.corerpg.progression.menu.ProgressionMenu;
import core.amazake.systems.corerpg.progression.menu.StatsMenu;
import core.amazake.systems.corerpg.util.Configs;
import core.amazake.systems.corerpg.util.Text;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Модуль A: классы, профессии, уровни 1–71, опыт и параметры (бывший RoyalQuestClasses).
 *
 * <p>Профиль игрока — в общем {@link ProfileStore} (неизменяемые снимки, атомарные изменения). Бонусы класса и
 * профессии идут в аспекты (вкладчики 0/100/200), эффекты и права живут на игроке ({@link BonusEffects}). Всё, что
 * трогает игрока, выполняется в его потоке; изменения чужого игрока (админ-команды) — через
 * {@code context.runOnceForPlayer}.</p>
 */
public final class ProgressionModule implements CoreModule {

    /** Право админских подкоманд {@code /class admin}. */
    public static final String ADMIN_PERMISSION = "core.class.admin";

    /** Итог начисления опыта. */
    public enum ExpOutcome {
        GRANTED, ZERO, NOT_LOADED, NO_CLASS, NEED_PROFESSION, MAX_LEVEL
    }

    /** Тип админского сброса. */
    public enum ResetType {
        CLASS("класс"), PROFESSION("профессия"), STATS("параметры"), ALL("всё");

        private final String label;

        ResetType(final String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }

        public static ResetType parse(final String s) {
            return switch (Names.normalize(s)) {
                case "class", "класс" -> CLASS;
                case "profession", "профессия", "prof" -> PROFESSION;
                case "stats", "параметры", "params" -> STATS;
                case "all", "все" -> ALL;
                default -> null;
            };
        }
    }

    private final CoreRpgSystem system;
    private final AtomicReference<ProgressionSettings> settings = new AtomicReference<>(ProgressionSettings.DEFAULTS);
    private final AtomicReference<ClassCatalog> catalog = new AtomicReference<>(ClassCatalog.EMPTY);
    /** Когда игроку последний раз сообщали о блокировке опыта (мс). */
    private final Map<UUID, Long> blockedMessageAt = new ConcurrentHashMap<>();
    /** Игроки, которым уже поставлен возврат постоянных эффектов (склейка событий молока). */
    private final core.amazake.systems.corerpg.aspect.RefreshGate<Player> pendingReapply = new core.amazake.systems.corerpg.aspect.RefreshGate<>();
    private volatile SystemContext context;
    private volatile BonusEffects effects;

    public ProgressionModule(final CoreRpgSystem system) {
        this.system = system;
    }

    // --- жизненный цикл ---

    @Override
    public void enable(final SystemContext context) {
        this.context = context;
        this.effects = new BonusEffects(context.plugin());
        this.loadSettings(context);

        this.system.aspects().register(ProgressionAspects.bonuses(this.catalog::get));
        this.system.aspects().register(ProgressionAspects.stats(this.settings::get));
        this.system.aspects().register(ProgressionAspects.locked(this.catalog::get));

        context.registerListener(new ProgressionListener(this));
        context.registerCommand(new ClassCommand(this));
        context.registerCommand(new ProfessionCommand(this));
        context.registerCommand(new StatsCommand(this));
        context.registerCommand(new MyClassCommand(this));
        // Игроков, которые уже онлайн, загружает ядро системы: для каждого вызовется onProfileLoaded.
        context.logger().info("Прогрессия: классов " + this.catalog.get().classes().size() + ", профессий "
            + this.catalog.get().professions().size() + ", уровней " + this.settings.get().maxLevel() + ".");
    }

    @Override
    public void reload(final SystemContext context) {
        this.loadSettings(context);
        for (final Player player : Bukkit.getOnlinePlayers()) {
            context.runOnceForPlayer(player, () -> {
                this.closeMenu(player);
                if (this.system.profiles().isLoaded(player.getUniqueId())) {
                    this.effects.apply(player, this.profile(player), this.catalog.get(), false);
                }
            });
        }
    }

    @Override
    public void disable(final SystemContext context) {
        final BonusEffects current = this.effects;
        for (final Player player : Bukkit.getOnlinePlayers()) {
            context.runOnceForPlayer(player, () -> {
                this.closeMenu(player);
                if (current != null) {
                    current.remove(player);
                }
            });
        }
        this.blockedMessageAt.clear();
        this.pendingReapply.clear();
    }

    private void loadSettings(final SystemContext context) {
        final Consumer<String> warn = w -> context.logger().warning("progression.yml: " + w);
        final ProgressionSettings parsed = ProgressionSettings.from(Configs.load(context, "progression.yml"), warn);
        final Consumer<String> classWarn = w -> context.logger().warning("classes.yml: " + w);
        final ClassCatalog parsedCatalog = ClassCatalog.from(Configs.load(context, "classes.yml"), classWarn);
        this.effects.validate(parsedCatalog, classWarn);
        this.settings.set(parsed);
        this.catalog.set(parsedCatalog);
    }

    // --- фасад (контракт) ---

    /** Профиль загружен (вход или включение системы). Поток игрока. Открыть выбор класса/профессии, если нужно. */
    public void onProfileLoaded(final Player player, final PlayerProfile profile) {
        final BonusEffects fx = this.effects;
        if (fx == null || !player.isOnline()) {
            return;
        }
        fx.apply(player, profile, this.catalog.get(), false);
        final ProgressionSettings s = this.settings.get();
        if (!profile.hasClass()) {
            if (s.openClassMenuOnFirstJoin() && !this.catalog.get().classes().isEmpty()) {
                this.later(player, 20L, () -> {
                    if (!this.profile(player).hasClass() && !this.hasForeignMenuOpen(player)) {
                        this.openClassMenu(player);
                    }
                });
            }
        } else if (this.needsProfession(profile) && s.openProfessionMenuOnJoin()) {
            this.later(player, 20L, () -> {
                if (this.needsProfession(this.profile(player)) && !this.hasForeignMenuOpen(player)) {
                    this.openProfessionMenu(player, false);
                }
            });
        }
    }

    /** Начислить опыт с учётом аспекта exp_bonus, блокировки до профессии и повышений уровня. Поток игрока. */
    public void addExp(final Player player, final long amount) {
        this.grantExp(player, amount, true);
    }

    /** Опыт до следующего уровня из текущего; 0 на максимуме. Любой поток. */
    public long expToNext(final PlayerProfile profile) {
        return this.settings.get().expToNext(profile.level());
    }

    public int maxLevel() {
        return this.settings.get().maxLevel();
    }

    public int professionLevel() {
        return this.settings.get().professionLevel();
    }

    /** Отображаемое имя класса по ключу; "" для null или неизвестного. */
    public String className(final String classId) {
        final ClassCatalog.RpgClass c = this.catalog.get().clazz(classId);
        return c == null ? "" : Names.stripTags(c.displayName());
    }

    /** Отображаемое имя профессии по ключу; "" для null или неизвестного. */
    public String professionName(final String professionId) {
        final ClassCatalog.RpgProfession p = this.catalog.get().profession(professionId);
        return p == null ? "" : Names.stripTags(p.displayName());
    }

    /** Название главного параметра класса: Сила / Точность / Интеллект; «Главный параметр» без класса. */
    public String primaryStatName(final String classId) {
        final ClassCatalog.RpgClass c = this.catalog.get().clazz(classId);
        return c == null ? StatType.PRIMARY.label() : c.primaryStat();
    }

    /** Ключи пассивок класса и профессии игрока (rage, headshot, backstab, dual_wield). Любой поток. */
    public java.util.Set<String> passives(final java.util.UUID uuid) {
        final ProfileStore store = this.system.profiles();
        if (store == null || uuid == null) {
            return Set.of();
        }
        final PlayerProfile p = store.get(uuid);
        return this.catalog.get().passives(p.classId(), p.professionId());
    }

    // --- для меню и команд ---

    public ProgressionSettings settings() {
        return this.settings.get();
    }

    public ClassCatalog catalog() {
        return this.catalog.get();
    }

    public CoreRpgSystem system() {
        return this.system;
    }

    public PlayerProfile profile(final Player player) {
        return this.system.profiles().get(player.getUniqueId());
    }

    public boolean isLoaded(final Player player) {
        return this.system.profiles().isLoaded(player.getUniqueId());
    }

    /**
     * Строка пассивки для меню: описание от боя (числа из combat.yml, выключенные помечены); если бой такую
     * пассивку не знает — название из меню.
     */
    public String passiveLabel(final String key) {
        final CombatModule combat = this.system.combat();
        final String fromCombat = combat == null ? null : combat.passiveDescription(key);
        return fromCombat != null ? fromCombat : MenuItems.passiveLabel(key);
    }

    public AspectSnapshot aspects(final Player player) {
        return this.system.aspects().get(player.getUniqueId());
    }

    /** Нужна ли игроку профессия прямо сейчас (класс есть, профессии нет, уровень достигнут). Любой поток. */
    public boolean needsProfession(final PlayerProfile profile) {
        return profile.hasClass() && !profile.hasProfession() && profile.level() >= this.professionLevel()
            && !this.catalog.get().professionsOf(profile.classId()).isEmpty();
    }

    /** Сообщение из progression.yml с префиксом системы. Поток получателя (консоль — любой). */
    public void send(final CommandSender to, final String key, final TagResolver... resolvers) {
        Text.send(to, this.settings.get().messages().get(key), resolvers);
    }

    /** Ответ отправителю команды из любого потока: игроку — в его потоке, консоли — сразу. */
    public void reply(final CommandSender to, final String key, final TagResolver... resolvers) {
        if (to instanceof final Player p) {
            this.inPlayerThread(p, () -> this.send(p, key, resolvers));
        } else {
            this.send(to, key, resolvers);
        }
    }

    /** Ответ готовым компонентом из любого потока. */
    public void reply(final CommandSender to, final Component message) {
        if (to instanceof final Player p) {
            this.inPlayerThread(p, () -> p.sendMessage(message));
        } else {
            to.sendMessage(message);
        }
    }

    /** Выполнить в потоке игрока через {@code ticks} тиков (1 и меньше — в ближайший тик). */
    public void later(final Player player, final long ticks, final Runnable task) {
        final SystemContext ctx = this.context;
        if (ctx == null) {
            return;
        }
        if (ticks <= 1L) {
            ctx.runOnceForPlayer(player, task);
            return;
        }
        // runForPlayer: первый запуск через 1 тик, дальше каждый тик — считаем запуски до нужной задержки.
        final AtomicLong runs = new AtomicLong();
        ctx.runForPlayer(player, scheduled -> {
            if (runs.incrementAndGet() < ticks) {
                return;
            }
            scheduled.cancel();
            if (player.isOnline()) {
                task.run();
            }
        }, 1L);
    }

    /** Поставить задачу в поток игрока; false — игрок уже невалиден. */
    public boolean inPlayerThread(final Player player, final Runnable task) {
        final SystemContext ctx = this.context;
        return ctx != null && ctx.runOnceForPlayer(player, task);
    }

    // --- меню (поток игрока) ---

    public void openClassMenu(final Player player) {
        if (!this.isLoaded(player)) {
            this.send(player, "profile-loading");
            return;
        }
        if (this.profile(player).hasClass()) {
            this.send(player, "class-already-chosen");
            return;
        }
        new ClassMenu(this).open(player);
    }

    /** Меню профессии; {@code explain} — сообщить, почему не открылось. */
    public void openProfessionMenu(final Player player, final boolean explain) {
        if (!this.isLoaded(player)) {
            if (explain) {
                this.send(player, "profile-loading");
            }
            return;
        }
        final PlayerProfile profile = this.profile(player);
        if (!profile.hasClass()) {
            if (explain) {
                this.send(player, "must-choose-class");
            }
            return;
        }
        if (profile.hasProfession()) {
            if (explain) {
                this.send(player, "profession-already-chosen");
            }
            return;
        }
        new ProfessionMenu(this).open(player);
    }

    public void openCharacterMenu(final Player player) {
        if (!this.isLoaded(player)) {
            this.send(player, "profile-loading");
            return;
        }
        new CharacterMenu(this).open(player);
    }

    public void openStatsMenu(final Player player) {
        if (!this.isLoaded(player)) {
            this.send(player, "profile-loading");
            return;
        }
        new StatsMenu(this).open(player);
    }

    /** Закрыть меню модуля, если оно открыто. Поток игрока. */
    void closeMenu(final Player player) {
        final InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder(false);
        if (holder instanceof ProgressionMenu) {
            player.closeInventory();
        }
    }

    /** Открыто чужое окно (не модуля и не собственный инвентарь): не перебиваем его своим. */
    private boolean hasForeignMenuOpen(final Player player) {
        final Inventory top = player.getOpenInventory().getTopInventory();
        final InventoryType type = top.getType();
        if (type == InventoryType.CRAFTING || type == InventoryType.CREATIVE) {
            return false;
        }
        return !(top.getHolder(false) instanceof ProgressionMenu);
    }

    // --- выбор класса и профессии (поток игрока) ---

    public boolean chooseClass(final Player player, final ClassCatalog.RpgClass clazz) {
        if (!this.isLoaded(player)) {
            this.send(player, "profile-loading");
            return false;
        }
        final AtomicBoolean changed = new AtomicBoolean();
        final PlayerProfile updated = this.system.profiles().update(player.getUniqueId(), p -> {
            if (p.hasClass()) {
                return p;
            }
            changed.set(true);
            return p.withClass(clazz.key());
        });
        if (updated == null) {
            this.send(player, "profile-loading");
            return false;
        }
        if (!changed.get()) {
            this.send(player, "class-already-chosen");
            return false;
        }
        this.effects.apply(player, updated, this.catalog.get(), true);
        this.system.aspects().refresh(player);
        this.send(player, "class-chosen", Placeholder.component("class", Text.mm(clazz.displayName())));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        if (this.needsProfession(updated)) {
            this.notifyProfession(player, updated);
        }
        return true;
    }

    public boolean chooseProfession(final Player player, final ClassCatalog.RpgProfession profession) {
        if (!this.isLoaded(player)) {
            this.send(player, "profile-loading");
            return false;
        }
        final PlayerProfile before = this.profile(player);
        if (!before.hasClass()) {
            this.send(player, "must-choose-class");
            return false;
        }
        if (before.hasProfession()) {
            this.send(player, "profession-already-chosen");
            return false;
        }
        final int required = this.professionLevel();
        if (before.level() < required) {
            this.send(player, "level-too-low", Placeholder.unparsed("level", String.valueOf(required)),
                Placeholder.unparsed("current", String.valueOf(before.level())));
            return false;
        }
        if (!profession.parent().equals(before.classId())) {
            this.send(player, "invalid-profession", Placeholder.unparsed("input", Names.stripTags(profession.displayName())),
                Placeholder.unparsed("list", this.professionList(before.classId())));
            return false;
        }
        final AtomicBoolean changed = new AtomicBoolean();
        final PlayerProfile updated = this.system.profiles().update(player.getUniqueId(), p -> {
            if (p.hasProfession() || !profession.parent().equals(p.classId()) || p.level() < required) {
                return p;
            }
            changed.set(true);
            return p.withProfession(profession.key());
        });
        if (updated == null || !changed.get()) {
            this.send(player, "profession-already-chosen");
            return false;
        }
        this.effects.apply(player, updated, this.catalog.get(), true);
        this.system.aspects().refresh(player);
        this.send(player, "profession-chosen", Placeholder.component("profession", Text.mm(profession.displayName())));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
        return true;
    }

    /** «Паладин, Берсерк» — профессии класса. */
    public String professionList(final String classId) {
        final List<String> names = new ArrayList<>();
        for (final ClassCatalog.RpgProfession p : this.catalog.get().professionsOf(classId)) {
            names.add(Names.stripTags(p.displayName()));
        }
        return String.join(", ", names);
    }

    /** «Мечник, Лучник, …» — все классы. */
    public String classList() {
        final List<String> names = new ArrayList<>();
        for (final ClassCatalog.RpgClass c : this.catalog.get().classes().values()) {
            names.add(Names.stripTags(c.displayName()));
        }
        return String.join(", ", names);
    }

    /** Сообщить, что пора выбрать профессию, и открыть меню. Поток игрока. */
    void notifyProfession(final Player player, final PlayerProfile profile) {
        this.send(player, "need-profession",
            Placeholder.unparsed("level", String.valueOf(this.professionLevel())),
            Placeholder.unparsed("professions", this.professionList(profile.classId())));
        this.later(player, 15L, () -> {
            if (this.needsProfession(this.profile(player)) && !this.hasForeignMenuOpen(player)) {
                this.openProfessionMenu(player, false);
            }
        });
    }

    // --- параметры (поток игрока) ---

    /**
     * Вложить до {@code amount} свободных очков в параметр. Возвращает, сколько вложено. {@code message} — написать
     * игроку результат (команда); меню перерисовывается само.
     */
    public int addStat(final Player player, final StatType type, final int amount, final boolean message) {
        if (!this.isLoaded(player)) {
            this.send(player, "profile-loading");
            return 0;
        }
        if (amount <= 0) {
            return 0;
        }
        final int[] added = {0};
        final PlayerProfile updated = this.system.profiles().update(player.getUniqueId(), p -> {
            final int n = Math.min(amount, p.freePoints());
            if (n <= 0) {
                return p;
            }
            added[0] = n;
            return p.withFreePoints(p.freePoints() - n).withStats(
                p.primary() + (type == StatType.PRIMARY ? n : 0),
                p.agility() + (type == StatType.AGILITY ? n : 0),
                p.stamina() + (type == StatType.STAMINA ? n : 0),
                p.luck() + (type == StatType.LUCK ? n : 0));
        });
        if (updated == null) {
            this.send(player, "profile-loading");
            return 0;
        }
        if (added[0] <= 0) {
            if (message) {
                this.send(player, "stats-no-points",
                    Placeholder.unparsed("every", String.valueOf(this.settings.get().pointsEveryLevels())));
            }
            return 0;
        }
        this.system.aspects().refresh(player);
        if (message) {
            final String name = type == StatType.PRIMARY ? this.primaryStatName(updated.classId()) : type.label();
            this.send(player, "stats-added", Placeholder.unparsed("stat", name),
                Placeholder.unparsed("amount", String.valueOf(added[0])),
                Placeholder.unparsed("free", String.valueOf(updated.freePoints())));
        }
        return added[0];
    }

    // --- опыт (поток игрока) ---

    /** Начислить опыт: {@code withBonus} — с аспектом exp_bonus. Поток игрока. */
    public ExpOutcome grantExp(final Player player, final long amount, final boolean withBonus) {
        if (amount <= 0) {
            return ExpOutcome.ZERO;
        }
        final UUID uuid = player.getUniqueId();
        final ProfileStore store = this.system.profiles();
        if (store == null || !store.isLoaded(uuid) || this.effects == null) {
            return ExpOutcome.NOT_LOADED;
        }
        final ProgressionSettings s = this.settings.get();
        final PlayerProfile current = store.get(uuid);
        if (!current.hasClass() && s.requireClassForExp()) {
            this.throttled(player, "exp-blocked-no-class", s);
            return ExpOutcome.NO_CLASS;
        }
        final int gate = this.gateLevel(s, current);
        if (gate > 0 && current.level() >= gate) {
            this.throttled(player, "exp-blocked", s);
            return ExpOutcome.NEED_PROFESSION;
        }
        if (current.level() >= s.maxLevel()) {
            return ExpOutcome.MAX_LEVEL;
        }
        long gain = amount;
        if (withBonus) {
            final double bonus = this.system.aspects().get(uuid).get(Aspect.EXP_BONUS);
            gain = ExpMath.withBonus(amount, bonus);
        }
        if (gain <= 0) {
            return ExpOutcome.ZERO;
        }
        return this.process(player, gain, s) ? ExpOutcome.GRANTED : ExpOutcome.NOT_LOADED;
    }

    /** Уровень, выше которого без профессии не подняться; 0 — без ограничения. */
    private int gateLevel(final ProgressionSettings s, final PlayerProfile p) {
        if (s.blockExpUntilProfession() && p.hasClass() && !p.hasProfession()
            && !this.catalog.get().professionsOf(p.classId()).isEmpty()) {
            return s.professionLevel();
        }
        return 0;
    }

    /** Применить {@code gain} опыта (0 — только досчитать накопленный) и сообщить о повышениях. Поток игрока. */
    private boolean process(final Player player, final long gain, final ProgressionSettings s) {
        final AtomicReference<Leveling.Result> result = new AtomicReference<>();
        final AtomicReference<PlayerProfile> before = new AtomicReference<>();
        final PlayerProfile updated = this.system.profiles().update(player.getUniqueId(), p -> {
            before.set(p);
            final Leveling.Result r = Leveling.apply(s.expCurve(), s.maxLevel(), p.level(), p.exp(), gain,
                this.gateLevel(s, p), s.pointsEveryLevels(), s.pointsPerStep());
            result.set(r);
            return p.withLevel(r.level(), r.exp()).withFreePoints(p.freePoints() + r.pointsGained());
        });
        if (updated == null || result.get() == null) {
            return false;
        }
        final Leveling.Result r = result.get();
        final long needed = s.expToNext(updated.level());
        if (s.showExpGain() && gain > 0) {
            player.sendActionBar(Text.mm(s.messages().get("exp-gained"),
                Placeholder.unparsed("amount", String.valueOf(gain)),
                Placeholder.unparsed("exp", String.valueOf(updated.exp())),
                Placeholder.unparsed("needed", String.valueOf(needed))));
        }
        if (r.levelsGained() <= 0) {
            return true;
        }
        final int from = before.get().level();
        // Не заваливаем чат: при скачке больше чем на 3 уровня — одно сообщение об итоговом.
        if (r.levelsGained() <= 3) {
            for (int l = from + 1; l <= updated.level(); l++) {
                this.send(player, "level-up", Placeholder.unparsed("level", String.valueOf(l)));
            }
        } else {
            this.send(player, "level-up", Placeholder.unparsed("level", String.valueOf(updated.level())));
        }
        if (r.pointsGained() > 0) {
            this.send(player, "points-gained", Placeholder.unparsed("points", String.valueOf(r.pointsGained())));
        }
        if (updated.level() >= s.maxLevel()) {
            this.send(player, "max-level", Placeholder.unparsed("level", String.valueOf(updated.level())));
        }
        if (!s.levelUpSound().isEmpty()) {
            player.playSound(player.getLocation(), s.levelUpSound().toLowerCase(Locale.ROOT), s.levelUpVolume(), s.levelUpPitch());
        }
        if (s.levelUpTitle()) {
            final TagResolver lvl = Placeholder.unparsed("level", String.valueOf(updated.level()));
            player.showTitle(Title.title(Text.mm(s.messages().get("level-up-title"), lvl),
                Text.mm(s.messages().get("level-up-subtitle"), lvl),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(1800), Duration.ofMillis(500))));
        }
        this.system.aspects().refresh(player);
        // Дошли до уровня профессии без профессии (с блокировкой опыта или без неё) — подсказать и открыть меню.
        if (from < s.professionLevel() && this.needsProfession(updated)) {
            this.notifyProfession(player, updated);
        }
        return true;
    }

    private void throttled(final Player player, final String key, final ProgressionSettings s) {
        final long now = System.currentTimeMillis();
        final Long last = this.blockedMessageAt.get(player.getUniqueId());
        if (!ExpMath.cooldownPassed(last, now, s.blockedMessageCooldownSeconds())) {
            return;
        }
        this.blockedMessageAt.put(player.getUniqueId(), now);
        this.send(player, key);
    }

    // --- события (вызывает ProgressionListener) ---

    /** Ванильная сфера опыта → кастомный опыт. Возвращает, сколько ванильного опыта оставить. Поток игрока. */
    int convertVanilla(final Player player, final int amount) {
        final ProgressionSettings s = this.settings.get();
        if (amount <= 0) {
            return amount;
        }
        if (!s.convertVanillaExp()) {
            // Ванильный опыт не конвертируется, но блокировка до выбора профессии действует и на него.
            final ProfileStore store = this.system.profiles();
            if (store != null && store.isLoaded(player.getUniqueId())) {
                final PlayerProfile p = store.get(player.getUniqueId());
                final int gate = this.gateLevel(s, p);
                if (gate > 0 && p.level() >= gate) {
                    this.throttled(player, "exp-blocked", s);
                    return 0;
                }
            }
            return amount;
        }
        final long custom = Math.round(amount * s.vanillaExpMultiplier());
        if (custom > 0) {
            this.grantExp(player, custom, true);
        }
        return 0;
    }

    /** Вернуть постоянные эффекты в ближайший тик (после молока, тотема, респавна). Поток события игрока. */
    void reapplyLater(final Player player) {
        final BonusEffects fx = this.effects;
        if (fx == null || !fx.hasPermanent(player.getUniqueId()) || !this.pendingReapply.claim(player.getUniqueId(), player)) {
            return;
        }
        final boolean queued = this.inPlayerThread(player, () -> {
            this.pendingReapply.release(player.getUniqueId(), player);
            fx.reapplyPermanent(player);
        });
        if (!queued) {
            this.pendingReapply.release(player.getUniqueId(), player);
        }
    }

    /** Выход: снять наложенное (эффекты не должны сохраниться в файле игрока) и забыть. Поток игрока. */
    void onQuit(final Player player) {
        final BonusEffects fx = this.effects;
        if (fx != null) {
            fx.remove(player);
            fx.forget(player.getUniqueId());
        }
        this.blockedMessageAt.remove(player.getUniqueId());
        this.pendingReapply.forget(player.getUniqueId());
    }

    // --- админ (вызывается в потоке отправителя; цель меняется в её потоке) ---

    public void adminReset(final CommandSender sender, final Player target, final ResetType type) {
        this.onTarget(sender, target, () -> {
            final PlayerProfile updated = this.system.profiles().update(target.getUniqueId(), p -> switch (type) {
                case CLASS -> refund(p).withClass(null).withProfession(null);
                case PROFESSION -> p.withProfession(null);
                case STATS -> refund(p);
                case ALL -> PlayerProfile.fresh(p.uuid());
            });
            if (updated == null) {
                this.reply(sender, "profile-loading");
                return;
            }
            this.effects.apply(target, updated, this.catalog.get(), false);
            this.system.aspects().refresh(target);
            this.closeMenu(target);
            final TagResolver t = Placeholder.unparsed("type", type.label());
            this.send(target, "admin-reset-notify", t);
            this.reply(sender, "admin-reset", Placeholder.unparsed("player", target.getName()), t);
        });
    }

    /** Вернуть вложенные очки в свободные. */
    static PlayerProfile refund(final PlayerProfile p) {
        return p.withFreePoints(p.freePoints() + p.spentPoints()).withStats(0, 0, 0, 0);
    }

    public void adminSetLevel(final CommandSender sender, final Player target, final int level) {
        this.onTarget(sender, target, () -> {
            final ProgressionSettings s = this.settings.get();
            final int lvl = Math.max(1, Math.min(level, s.maxLevel()));
            final PlayerProfile updated = this.system.profiles().update(target.getUniqueId(), p -> {
                final int entitled = Leveling.pointsForLevel(lvl, s.pointsEveryLevels(), s.pointsPerStep());
                return p.withLevel(lvl, 0L).withFreePoints(Math.max(0, entitled - p.spentPoints()));
            });
            if (updated == null) {
                this.reply(sender, "profile-loading");
                return;
            }
            this.system.aspects().refresh(target);
            final TagResolver l = Placeholder.unparsed("level", String.valueOf(lvl));
            this.send(target, "admin-setlevel-notify", l);
            this.reply(sender, "admin-setlevel", Placeholder.unparsed("player", target.getName()), l);
            if (this.needsProfession(updated)) {
                this.notifyProfession(target, updated);
            }
        });
    }

    public void adminGiveExp(final CommandSender sender, final Player target, final long amount) {
        this.onTarget(sender, target, () -> {
            final ExpOutcome outcome = this.grantExp(target, amount, false);
            final TagResolver name = Placeholder.unparsed("player", target.getName());
            if (outcome == ExpOutcome.GRANTED) {
                this.reply(sender, "admin-giveexp", name, Placeholder.unparsed("amount", String.valueOf(amount)));
                return;
            }
            final String reason = switch (outcome) {
                case NOT_LOADED -> "профиль не загружен";
                case NO_CLASS -> "не выбран класс";
                case NEED_PROFESSION -> "нужно выбрать профессию";
                case MAX_LEVEL -> "максимальный уровень";
                default -> "нечего начислять";
            };
            this.reply(sender, "admin-giveexp-blocked", name, Placeholder.unparsed("reason", reason));
        });
    }

    public void adminSetClass(final CommandSender sender, final Player target, final ClassCatalog.RpgClass clazz) {
        this.onTarget(sender, target, () -> {
            final PlayerProfile updated = this.system.profiles().update(target.getUniqueId(), p -> {
                final ClassCatalog.RpgProfession prof = this.catalog.get().profession(p.professionId());
                final PlayerProfile next = p.withClass(clazz.key());
                return prof != null && prof.parent().equals(clazz.key()) ? next : next.withProfession(null);
            });
            this.afterAdminSet(sender, target, updated, "класс", Names.stripTags(clazz.displayName()));
        });
    }

    public void adminSetProfession(final CommandSender sender, final Player target, final ClassCatalog.RpgProfession profession) {
        this.onTarget(sender, target, () -> {
            final PlayerProfile updated = this.system.profiles().update(target.getUniqueId(),
                p -> p.withClass(profession.parent()).withProfession(profession.key()));
            this.afterAdminSet(sender, target, updated, "профессия", Names.stripTags(profession.displayName()));
        });
    }

    private void afterAdminSet(final CommandSender sender, final Player target, final PlayerProfile updated,
                               final String type, final String value) {
        if (updated == null) {
            this.reply(sender, "profile-loading");
            return;
        }
        this.effects.apply(target, updated, this.catalog.get(), true);
        this.system.aspects().refresh(target);
        this.closeMenu(target);
        final TagResolver t = Placeholder.unparsed("type", type);
        final TagResolver v = Placeholder.unparsed("value", value);
        this.send(target, "admin-set-notify", t, v);
        this.reply(sender, "admin-set", Placeholder.unparsed("player", target.getName()), t, v);
        if (this.needsProfession(updated)) {
            this.notifyProfession(target, updated);
        }
    }

    /** Сводка по игроку (из снимков, любой поток). */
    public Component adminInfo(final Player target) {
        final PlayerProfile p = this.system.profiles().get(target.getUniqueId());
        final ProgressionSettings s = this.settings.get();
        final Messages m = s.messages();
        final Set<String> passives = this.passives(target.getUniqueId());
        final String cls = this.className(p.classId());
        final String prof = this.professionName(p.professionId());
        return Text.mm(Text.prefix() + m.get("admin-info"),
            Placeholder.unparsed("player", target.getName()),
            Placeholder.unparsed("class", cls.isEmpty() ? m.get("no-class") : cls + " (" + p.classId() + ")"),
            Placeholder.unparsed("profession", prof.isEmpty() ? m.get("no-profession") : prof + " (" + p.professionId() + ")"),
            Placeholder.unparsed("level", String.valueOf(p.level())),
            Placeholder.unparsed("max", String.valueOf(s.maxLevel())),
            Placeholder.unparsed("exp", String.valueOf(p.exp())),
            Placeholder.unparsed("needed", String.valueOf(s.expToNext(p.level()))),
            Placeholder.unparsed("primary-name", this.primaryStatName(p.classId())),
            Placeholder.unparsed("primary", String.valueOf(p.primary())),
            Placeholder.unparsed("agility", String.valueOf(p.agility())),
            Placeholder.unparsed("stamina", String.valueOf(p.stamina())),
            Placeholder.unparsed("luck", String.valueOf(p.luck())),
            Placeholder.unparsed("free", String.valueOf(p.freePoints())),
            Placeholder.unparsed("passives", passives.isEmpty() ? "нет" : String.join(", ", passives)));
    }

    /** Краткая сводка игроку о себе (бывший /myclass в тексте). */
    public Component selfInfo(final Player player) {
        final PlayerProfile p = this.profile(player);
        final ProgressionSettings s = this.settings.get();
        final Messages m = s.messages();
        final String cls = this.className(p.classId());
        final String prof = this.professionName(p.professionId());
        return Text.mm(Text.prefix() + m.get("info"),
            Placeholder.unparsed("class", cls.isEmpty() ? m.get("no-class") : cls),
            Placeholder.unparsed("profession", prof.isEmpty() ? m.get("no-profession") : prof),
            Placeholder.unparsed("level", String.valueOf(p.level())),
            Placeholder.unparsed("exp", String.valueOf(p.exp())),
            Placeholder.unparsed("needed", String.valueOf(s.expToNext(p.level()))));
    }

    /** Перечитать всю систему (в глобальном потоке), ответ — отправителю. */
    public void adminReload(final CommandSender sender) {
        final SystemContext ctx = this.context;
        Bukkit.getGlobalRegionScheduler().execute(ctx.plugin(), () -> {
            try {
                this.system.reload(ctx);
                this.reply(sender, "admin-reload");
            } catch (final Exception e) {
                ctx.logger().log(Level.WARNING, "Перезагрузка CoreRpg по команде не удалась", e);
                this.reply(sender, "admin-reload-failed", Placeholder.unparsed("error", String.valueOf(e.getMessage())));
            }
        });
    }

    /** Выполнить изменение цели в её потоке; если цель вышла — сообщить отправителю. */
    private void onTarget(final CommandSender sender, final Player target, final Runnable change) {
        if (!this.inPlayerThread(target, change)) {
            this.reply(sender, "player-not-found", Placeholder.unparsed("input", target.getName()));
        }
    }
}
