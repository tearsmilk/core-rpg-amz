package ink.hoka.odyssey.gravity;

import java.util.List;

/**
 * Сервис модуля gravity: чёрные дыры из config.yml (раздел black-holes) для других модулей — например, spacesky
 * рисует их в небе шейдером. Брать через services().get(BlackHoles.class) (модулю — depends или softdepends: [gravity];
 * при перезагрузке gravity ядро перезагружает и его, поэтому объект живёт столько же, сколько модуль-потребитель).
 */
public interface BlackHoles {

    /**
     * Дыра на карте.
     *
     * @param map            карта (папка в maps/), на которой стоит дыра
     * @param name           имя (уникально в пределах карты)
     * @param x              центр
     * @param y              центр
     * @param z              центр
     * @param horizon        радиус горизонта, блоков
     * @param diskTilt       наклон диска к горизонтали, градусы (−90…90)
     * @param diskYaw        в какую сторону опущен край диска, градусы (0 — к +x, 90 — к +z)
     * @param diskSize       внешний радиус диска в горизонтах (0 — без диска)
     * @param diskSpin       скорость вращения диска (знак — направление)
     * @param diskBrightness яркость (1 — обычно)
     * @param diskTemp       «температура» цвета (1 — обычно; меньше — краснее, больше — голубее)
     */
    record Hole(String map, String name, double x, double y, double z, double horizon, double diskTilt, double diskYaw,
                double diskSize, double diskSpin, double diskBrightness, double diskTemp) {
    }

    /** Все дыры всех карт (снимок, порядок — как в config.yml). */
    List<Hole> holes();

    /**
     * Подписка: listener вызывается в тике сервера после каждого перечтения config.yml gravity (/hograv reload,
     * hole add|remove) — список мог измениться. Возвращает отписку; отписаться — в onDisable подписчика.
     */
    Runnable onChange(Runnable listener);
}
