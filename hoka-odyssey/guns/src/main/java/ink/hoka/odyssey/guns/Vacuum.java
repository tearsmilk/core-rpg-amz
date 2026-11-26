package ink.hoka.odyssey.guns;

import ink.hoka.odyssey.gravity.VacuumService;
import net.minestom.server.entity.Player;

/**
 * Вакуум модуля gravity (softdepends): в вакууме ли игрок и толчок отдачи. Класс VacuumService трогается только
 * здесь: если gravity на сервере нет, первое обращение даёт LinkageError — тогда считаем, что вакуума нет нигде.
 */
final class Vacuum {
    private final GunsModule mod;
    private boolean missing;

    Vacuum(GunsModule mod) {
        this.mod = mod;
    }

    private Object service() {
        if (missing) {
            return null;
        }
        try {
            return mod.service(VacuumService.class);
        } catch (LinkageError e) {
            missing = true;
            return null;
        }
    }

    boolean inVacuum(Player p) {
        Object s = service();
        if (s == null) {
            return false;
        }
        try {
            return ((VacuumService) s).inVacuum(p);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Толчок (блоков/тик). */
    void push(Player p, double vx, double vy, double vz) {
        Object s = service();
        if (s == null) {
            return;
        }
        try {
            ((VacuumService) s).push(p, vx, vy, vz);
        } catch (RuntimeException | LinkageError ignored) {
            // gravity выгрузили посреди тика — толчка не будет
        }
    }
}
