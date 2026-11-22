package ink.hoka.odyssey.gravity;

import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;

/**
 * Сервис модуля gravity для других модулей (depends: [gravity]): включить/запретить вакуум отдельному игроку поверх
 * карт из config.yml. Так, например, gravzones делает «вакуумные» зоны и зоны с обычной гравитацией на вакуумной карте.
 * Брать заново через services().require(VacuumService.class) — после перезагрузки gravity это другой объект.
 * Вызывать из тика сервера; изменение применяется в ближайшем тике gravity.
 * <p>
 * Предметы (ItemEntity): модуль-владелец зон регистрирует {@link ZoneProvider}, и gravity спрашивает у него режим
 * точки, где лежит/летит предмет. NORMAL — gravity предмет не трогает (не выключает гравитацию, дыры его не тянут и не
 * поглощают; гравитацию зоны ставит сам владелец зон), остальное — как решает карта.
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

    /**
     * Поставить источник зон для предметов (null — убрать). Один на сервер: новый заменяет прежний. Снимать в onDisable
     * через {@link #removeZoneProvider(ZoneProvider)}. После перезагрузки gravity регистрировать заново.
     */
    void zoneProvider(ZoneProvider provider);

    /** Убрать источник зон, если стоит именно он (чужой не трогает). */
    void removeZoneProvider(ZoneProvider provider);

    /**
     * Зоны для предметов. gravity вызывает {@link #zones(Instance)} раз на карту за проход по предметам (и при появлении
     * предмета), а {@link ZoneLookup#modeAt} — для каждого предмета. Вызов — из тика сервера, должен быть дешёвым.
     */
    @FunctionalInterface
    interface ZoneProvider {
        /** Зоны карты instance; null — на этой карте зон нет (везде решает карта). */
        ZoneLookup zones(Instance instance);
    }

    /** Режим в точке карты: MAP — решает карта, NORMAL — обычная гравитация зоны, VACUUM — вакуумная зона. */
    @FunctionalInterface
    interface ZoneLookup {
        Mode modeAt(double x, double y, double z);
    }

    /**
     * В вакууме ли предмет: только на вакуумной карте и не в зоне NORMAL. Вакуумная зона на обычной карте предметы не
     * трогает (как и раньше — вакуум предметов только на картах из config.yml).
     */
    static boolean itemInVacuum(boolean mapVacuum, Mode zone) {
        return mapVacuum && zone != Mode.NORMAL;
    }
}
