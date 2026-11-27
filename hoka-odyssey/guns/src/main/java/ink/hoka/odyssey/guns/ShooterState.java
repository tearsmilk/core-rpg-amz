package ink.hoka.odyssey.guns;

import java.util.HashMap;
import java.util.Map;

/**
 * Состояние стрелка (одно на игрока, живёт в памяти модуля): какое оружие в руке, патроны в нём, перезарядка,
 * «достаёт», режим огня, «ведро» темпа. Патроны в предмет записываются только при смене слота, выходе, конце
 * перезарядки и выключении модуля — не на каждый выстрел. Без Minestom — проверяется тестами.
 */
final class ShooterState {
    /** Оружие в руке с обвесом (null — не оружие): по нему стреляем, считаем разброс, магазин, перезарядку. */
    GunDef gun;
    /** То же оружие как в config.yml (без обвеса) — по нему узнаём, то ли оружие в руке. */
    GunDef base;
    /** Обвес оружия в руке и что он даёт. */
    Loadout loadout = Loadout.EMPTY;
    AttachmentMath.Effective fx;
    /** Открыт экран обвеса (Y): стрелять и целиться нельзя. */
    boolean refit;
    /** Темп действий с обвесом (не чаще 10 в секунду). */
    final FireValidator.Bucket refitBucket = new FireValidator.Bucket();
    /** Последний отправленный своему клиенту LOADOUT и разосланный другим GEAR (ключи — слать при смене). */
    String loadoutSent = "";
    String gearSent = "";
    /** Слот хотбара с этим оружием и серийный номер предмета (по нему ищем предмет, чтобы записать патроны). */
    int slot = -1;
    String serial;
    int ammo;
    /** Режим: 0 — очередь, 1 — одиночный. */
    int mode;

    /**
     * Перезарядка: 0 — нет, 1 — тактическая, 2 — с пустым магазином, 3 — по одному патрону (дробовик). Время начала,
     * «патроны уже вставлены» (1, 2) и вся длина, с.
     */
    int reloadKind;
    long reloadStart;
    boolean reloadFed;
    double reloadTotal;
    /** По одному патрону: когда (с от начала) входит каждый патрон и сколько уже вошло. */
    double[] reloadFeeds = new double[0];
    int reloadFedCount;
    /** Звуки перезарядки для остальных: {время с, id} по порядку и сколько уже сыграно. */
    final java.util.List<Map.Entry<Double, String>> reloadSounds = new java.util.ArrayList<>();
    int reloadSoundIndex;

    /** Номер выстрела (у дроби — общий у всех пуль) и по какому выстрелу уже сказали стрелку «попал» и как. */
    int shotNo;
    int hitShot = -1;
    int hitKindSent;

    /** Ручной затвор: когда началось передёргивание (мс, 0 — нет) и сколько его звуков для остальных уже сыграно. */
    long boltStart;
    int boltSoundIndex;
    /**
     * Ручной затвор: серийный номер оружия → когда (мс) после выстрела закончится передёргивание. Если убрать оружие
     * раньше, затвор не передёрнут: при следующем доставании к «достаёт» прибавляется время затвора (иначе сменой
     * слота туда-обратно затвор пропускался бы и AWP стрелял бы чаще цикла).
     */
    final Map<String, Long> boltDue = new HashMap<>();

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
        reloadSounds.clear();
        reloadFeeds = new double[0];
        reloadFedCount = 0;
    }

    /** Взять новое оружие в руку. */
    void equip(GunDef g, int slot, String serial, int ammo, long now) {
        this.gun = g;
        this.slot = slot;
        this.serial = serial;
        this.ammo = Math.max(0, Math.min(ammo, g.magazine() + (g.chamber() ? 1 : 0)));
        cancelReload();
        drawUntil = now + Math.round(g.draw() * 1000);
        boltStart = 0;
        boltDue.values().removeIf(t -> t <= now);
        Long due = serial == null ? null : boltDue.get(serial);
        if (g.boltTime() > 0 && due != null) {                // затвор не передёрнут — сначала передёрнуть
            drawUntil += Math.round(g.boltTime() * 1000);
            boltDue.put(serial, drawUntil);
        }
        if (!g.auto() && mode == 0) {
            mode = 1;
        }
        if (!g.semi() && mode == 1) {
            mode = 0;
        }
        bucket.reset(now);
    }

    /** Выстрел из оружия с ручным затвором: если остался патрон, затвор передёргивается до {@code now + cycleMs}. */
    void boltAfterShot(long now) {
        if (gun != null && gun.boltTime() > 0 && ammo > 0 && serial != null) {
            boltDue.put(serial, now + Math.round(gun.cycleMs()));
        }
    }

    void unequip() {
        gun = null;
        base = null;
        loadout = Loadout.EMPTY;
        fx = null;
        refit = false;
        loadoutSent = "";
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
