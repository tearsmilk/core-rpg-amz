package core.amazake.systems.coreportals.portal;

import core.amazake.systems.coreportals.Settings;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/**
 * Вид портала изнутри: сущности, расписание анимации, эффекты и объём входа. Все методы, кроме
 * {@link #stage(long)}, вызывает только поток региона портала.
 */
interface PortalBody {

    /** Поставить сущности в начальном виде и сыграть эффекты тика 0. */
    void spawn(Settings s, String instance);

    /** Все сущности на месте (никто не убил дисплей командой). */
    boolean valid();

    /** Начать закрытие с тика {@code t} (если ещё не началось). */
    void closeAt(long t);

    /** Анимация закрытия закончена: пора убирать. */
    boolean done(long t);

    /** В портал можно войти. */
    boolean enterable(long t);

    /** Стадия для /portals list. Любой поток. */
    String stage(long t);

    /** Тик: ключевые кадры дисплеев и эффекты. */
    void tick(long t, Settings s);

    /** Хитбокс задевает объём входа. */
    boolean touches(BoundingBox box);

    /** Середина портала в мире: от неё ищутся игроки для входа. */
    double[] center();

    /** Радиус поиска игроков для входа вокруг {@link #center()}, блоков. */
    default double scanRadius() {
        return 3.5;
    }

    /** Эффект входа игрока (его место — регион портала). */
    void enter(Player player, Settings s);

    /** Убрать сущности. */
    void remove();
}
