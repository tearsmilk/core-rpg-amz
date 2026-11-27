package ink.hoka.odyssey.guns;

/**
 * Состояние стрелка (одно на игрока, живёт в памяти модуля): какое оружие в руке, патроны в нём, перезарядка,
 * «достаёт», режим огня, «ведро» темпа. Патроны в предмет записываются только при смене слота, выходе, конце
 * перезарядки и выключении модуля — не на каждый выстрел. Без Minestom — проверяется тестами.
 */
final class ShooterState {
    /** Оружие в руке (null — не оружие). */
    GunDef gun;
    /** Слот хотбара с этим оружием и серийный номер предмета (по нему ищем предмет, чтобы записать патроны). */
    int slot = -1;
    String serial;
    int ammo;
    /** Режим: 0 — очередь, 1 — одиночный. */
    int mode;

    /** Перезарядка: 0 — нет, 1 — тактическая, 2 — с пустым магазином. Время начала и «патроны уже вставлены». */
    int reloadKind;
    long reloadStart;
    boolean reloadFed;
    /** Звуки перезарядки для остальных — уже сыгранные (индекс в списке). */
    int reloadSoundIndex;

    /** Ручной затвор: когда началось передёргивание (мс, 0 — нет) и сколько его звуков для остальных уже сыграно. */
    long boltStart;
    int boltSoundIndex;

    /** До этого момента (мс) оружие достают — стрелять нельзя. */
    long drawUntil;
    /** Прицел: момент начала (мс), 0 — не целится. */
    long aimSince;
    /**
     * Своим (с клиентским модулем) в прицеле сервер ставит «использование» предмета: у ванильного клиента от этого
     * поза лука (обе руки вперёд), оружие вдоль руки — стволом вперёд. Стоит ли сейчас.
     */
    boolean aimPose;
    /**
     * Кто уже видел позу прицела. Новому зрителю (подошёл, зашёл) метаданные приходят при появлении стрелка раньше
     * предмета в руке — клиент «использование» без предмета сбрасывает; ему шлём метаданные ещё раз.
     */
    final java.util.Set<java.util.UUID> aimPoseSeen = new java.util.HashSet<>();
    /** Стоит ли замедление прицела атрибутом (когда позы лука нет: с позой замедляет USE_EFFECTS предмета). */
    boolean aimSlow;
    /** Угол обзора до прицела (вернуть по окончании); NaN — не меняли. */
    float savedFov = Float.NaN;

    final FireValidator.Bucket bucket = new FireValidator.Bucket();
    int ackSeq;

    /** Скорость по позициям (блоков/тик) — для разброса «в движении»; прошлая позиция. */
    double lastX = Double.NaN;
    double lastY;
    double lastZ;
    double speed;
    /** Сдвиг за прошлый тик (блоков/тик) — пуля наследует скорость стрелка. */
    double[] motion;
    boolean vacuum;
    /** Патроны изменились, а в предмет ещё не записаны. */
    boolean ammoDirty;

    /** Последняя разосланная другим поза (флаги POSE и начало перезарядки) — слать только при смене. */
    String poseKey = "";

    /** Последнее отправленное клиенту состояние (чтобы не слать одно и то же). */
    String sentKey = "";
    /** Толчок отдачи в вакууме, накопленный за тик (блоков/тик). */
    double pushX;
    double pushY;
    double pushZ;

    boolean reloading() {
        return reloadKind != 0;
    }

    boolean drawing(long now) {
        return now < drawUntil;
    }

    void cancelReload() {
        reloadKind = 0;
        reloadFed = false;
        reloadSoundIndex = 0;
    }

    /** Взять новое оружие в руку. */
    void equip(GunDef g, int slot, String serial, int ammo, long now) {
        this.gun = g;
        this.slot = slot;
        this.serial = serial;
        this.ammo = Math.max(0, Math.min(ammo, g.magazine() + (g.chamber() ? 1 : 0)));
        cancelReload();
        drawUntil = now + Math.round(g.draw() * 1000);
        if (!g.auto() && mode == 0) {
            mode = 1;
        }
        if (!g.semi() && mode == 1) {
            mode = 0;
        }
        bucket.reset(now);
    }

    void unequip() {
        gun = null;
        slot = -1;
        serial = null;
        ammo = 0;
        cancelReload();
        aimSince = 0;
        boltStart = 0;
        ammoDirty = false;
        sentKey = "";
    }
}
