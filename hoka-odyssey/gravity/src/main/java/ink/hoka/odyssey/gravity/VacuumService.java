package ink.hoka.odyssey.gravity;

import net.minestom.server.entity.Player;

/**
 * Сервис модуля gravity для других модулей (depends: [gravity]): включить/запретить вакуум отдельному игроку поверх
 * карт из config.yml. Так, например, gravzones делает «вакуумные» зоны и зоны с обычной гравитацией на вакуумной карте.
 * Брать заново через services().require(VacuumService.class) — после перезагрузки gravity это другой объект.
 * Вызывать из тика сервера; изменение применяется в ближайшем тике gravity.
 */
public interface VacuumService {

    /** Что решает за игрока: карта (как без сервиса), всегда вакуум или никогда. */
    enum Mode { MAP, VACUUM, NORMAL }

    /** Режим игрока. MAP — снять своё решение, дальше по картам из config.yml. */
    void override(Player p, Mode mode);

    /** Текущий режим игрока (MAP, если никто не задавал). */
    Mode override(Player p);

    /** Принудительный вакуум: on — всегда вакуум, off — снять принуждение (решает карта). */
    default void force(Player p, boolean on) {
        override(p, on ? Mode.VACUUM : Mode.MAP);
    }

    /** В вакууме ли игрок сейчас (модуль gravity уже держит его гравитацию = 0). */
    boolean inVacuum(Player p);

    /**
     * Толчок: прибавить к скорости клиента (vx, vy, vz) блоков/тик. Если игрок в вакууме, gravity учитывает толчок
     * в своей модели и дальше гасит его как обычную тягу (без клавиш — плавно до остановки).
     */
    void push(Player p, double vx, double vy, double vz);
}
