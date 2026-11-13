package core.amazake.systems.corerpg.progression;

import core.amazake.systems.api.SystemContext;
import core.amazake.systems.corerpg.CoreModule;
import core.amazake.systems.corerpg.CoreRpgSystem;
import core.amazake.systems.corerpg.profile.PlayerProfile;
import org.bukkit.entity.Player;

/** ЗАГЛУШКА (контракт). Модуль A заменяет тело, сохраняя сигнатуры публичных методов. */
public final class ProgressionModule implements CoreModule {

    private final CoreRpgSystem system;

    public ProgressionModule(final CoreRpgSystem system) {
        this.system = system;
    }

    @Override public void enable(final SystemContext context) { }
    @Override public void reload(final SystemContext context) { }
    @Override public void disable(final SystemContext context) { }

    /** Профиль загружен (вход или включение системы). Поток игрока. Открыть выбор класса/профессии, если нужно. */
    public void onProfileLoaded(final Player player, final PlayerProfile profile) { }

    /** Начислить опыт с учётом аспекта exp_bonus, блокировки до профессии и повышений уровня. Поток игрока. */
    public void addExp(final Player player, final long amount) { }

    /** Опыт до следующего уровня из текущего; 0 на максимуме. Любой поток. */
    public long expToNext(final PlayerProfile profile) { return 0L; }

    public int maxLevel() { return 71; }

    public int professionLevel() { return 21; }

    /** Отображаемое имя класса по ключу; "" для null или неизвестного. */
    public String className(final String classId) { return ""; }

    /** Отображаемое имя профессии по ключу; "" для null или неизвестного. */
    public String professionName(final String professionId) { return ""; }

    /** Название главного параметра класса: Сила / Точность / Интеллект; «Главный параметр» без класса. */
    public String primaryStatName(final String classId) { return "Главный параметр"; }

    /** Ключи пассивок класса и профессии игрока (rage, headshot, backstab, dual_wield). Любой поток. */
    public java.util.Set<String> passives(final java.util.UUID uuid) { return java.util.Set.of(); }
}
