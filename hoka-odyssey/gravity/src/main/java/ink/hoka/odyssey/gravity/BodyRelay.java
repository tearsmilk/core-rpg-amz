package ink.hoka.odyssey.gravity;

import ink.hoka.odyssey.gravity.SpaceflightLink.Orientation;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minestom.server.MinecraftServer;
import net.minestom.server.collision.BoundingBox;
import net.minestom.server.entity.EntityPose;
import net.minestom.server.entity.Metadata;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket;

/**
 * Поворот тел игроков с модом spaceflight для остальных. Клиент шлёт кватернион своего тела (без «лёжа») и флаги —
 * лёжа/стоя и грань, на которой стоит (C2S op 3), сервер проверяет и раздаёт их зрителям с модулем (S2C op 3,
 * op 4 — «больше не действует»); позу по ним строит клиент зрителя.
 * <p>
 * Зрителям без модуля (и с модом без крючка поворота тел onEntityPose) показываем запасную позу: игрок, который
 * лежит (флаг «лёжа» или тело наклонено больше чем на {@link #LIE_TILT}°), выглядит летящим «как на элитрах» —
 * тело вдоль взгляда. Позу SWIMMING использовать нельзя: клиент вне воды сам сбрасывает её чужим игрокам каждый тик,
 * а флаг полёта на элитрах клиент чужим игрокам не трогает. Флаг шлётся только зрителям (не самому игроку — иначе
 * его клиент включил бы физику элитр) и не меняет метаданные на сервере; Minestom при своих изменениях флагов
 * затирает его, поэтому повторяем раз в {@link #FALLBACK_REPEAT} тиков.
 * <p>
 * Рамка: пока клиент говорит «рамка повёрнута по грани» (бит рамки в op 3 — стоит на стене или потолке), игроку на
 * сервере ставится такая же рамка ({@link PlayerBox}) — столкновения, установка блоков рядом, удары и касания
 * считаются по ней. Флаг пропал, поворот не действует, игрок ушёл или модуль выключается — рамка обычная.
 */
final class BodyRelay {
    /** Лежит, если тело наклонено сильнее (градусы); встаёт обратно — когда меньше {@link #STAND_TILT}. */
    static final double LIE_TILT = 60;
    static final double STAND_TILT = 50;
    /** Не чаще, чем раз в столько тиков, раздавать поворот (≈10 раз в секунду). */
    static final int RELAY_MIN = 2;
    /** Без изменений — всё равно повторять раз в столько тиков (новые зрители, потерянные пакеты). */
    static final int RELAY_REPEAT = 20;
    /** Запасную позу повторять раз в столько тиков. */
    static final int FALLBACK_REPEAT = 10;
    /** Больше сообщений за секунду от одного игрока — лишние отбрасываем. */
    static final int MAX_PER_SECOND = 30;
    private static final int TPS = Settings.TPS;
    /** Бит «полёт на элитрах» во флагах сущности (метаданные, индекс 0). */
    private static final int ELYTRA_BIT = 0x80;
    private static final int FLAGS_INDEX = 0;
    private static final int POSE_INDEX = 6;

    private final SpaceflightLink link;
    /** Игрок сменил рамку (поток clientmods, сразу при пакете): позиция прыгнет — проверке скорости дать запас. */
    private final Consumer<Player> boxShifted;
    private final Map<UUID, Body> bodies = new ConcurrentHashMap<>();
    /** Игроки, чей клиент сам рисует повёрнутые тела (запасную позу им не шлём). */
    private final Set<UUID> rotating = ConcurrentHashMap.newKeySet();
    private volatile long ticks;

    BodyRelay(SpaceflightLink link) {
        this(link, p -> { });
    }

    BodyRelay(SpaceflightLink link, Consumer<Player> boxShifted) {
        this.link = link;
        this.boxShifted = boxShifted;
    }

    /** Состояние одного игрока. Поля пишет поток clientmods (receive) и тик — только простые значения. */
    private static final class Body {
        volatile Orientation orientation;
        volatile boolean dirty;
        long window;
        int count;
        long lastRelay = Long.MIN_VALUE / 2;
        long lastFallback = Long.MIN_VALUE / 2;
        boolean relayed;
        boolean lying;
        boolean fallbackOn;
        /** Рамка, поставленная игроку на сервере (грань стены/потолка), −1 — обычная. */
        int boxApplied = -1;
        /** Рамка по последнему пакету (поток clientmods) и тик последнего запаса проверки скорости. */
        int boxReceived = -1;
        long lastCredit = Long.MIN_VALUE / 2;
    }

    /**
     * Сообщение клиентского модуля spaceflight (C2S). Вызывается не обязательно в тике — только запоминаем.
     */
    void receive(Player p, byte[] data) {
        if (p == null || data == null || data.length == 0) {
            return;
        }
        int op = data[0] & 0xFF;
        if (op == SpaceflightLink.OP_CAPS) {
            int caps = SpaceflightLink.decodeCaps(data);
            if (caps >= 0 && (caps & SpaceflightLink.CAP_AVATAR_ROTATION) != 0) {
                rotating.add(p.getUuid());
            } else if (caps >= 0) {
                rotating.remove(p.getUuid());
            }
            return;
        }
        if (op != SpaceflightLink.OP_ORIENT) {
            return;                                          // незнакомые операции — от новой версии клиента
        }
        Orientation o = SpaceflightLink.decodeOrientation(data);
        if (o == null) {
            return;
        }
        Body b = bodies.computeIfAbsent(p.getUuid(), k -> new Body());
        synchronized (b) {
            long t = ticks;
            if (t - b.window >= TPS) {
                b.window = t;
                b.count = 0;
            }
            if (++b.count > MAX_PER_SECOND) {
                return;                                      // слишком часто — отбрасываем
            }
            b.orientation = o;
            b.dirty = true;
            int box = o.active() ? o.boxSurface() : -1;
            if (box != b.boxReceived) {
                b.boxReceived = box;
                if (t - b.lastCredit >= PlayerBox.CREDIT_COOLDOWN) {
                    b.lastCredit = t;
                    boxShifted.accept(p);                    // до пакета движения со сдвигом позиции
                }
            }
        }
    }

    /**
     * Тик сервера: раздать повороты и запасную позу. active — игрок сейчас в вакууме с модом (иначе его поворот
     * не действует, зрителям — op 4 и обычная поза).
     */
    void tick(Predicate<Player> active) {
        long t = ++ticks;
        for (Iterator<Map.Entry<UUID, Body>> it = bodies.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(e.getKey());
            Body b = e.getValue();
            if (p == null) {
                it.remove();                                 // вышел: клиенты зрителей убрали его сами
                continue;
            }
            Orientation o = b.orientation;
            if (o == null || !o.active() || !active.test(p)) {
                stop(p, b);
                it.remove();
                continue;
            }
            applyBox(p, b, o.boxSurface());
            b.lying = lying(o, b.lying);
            if ((b.dirty && t - b.lastRelay >= RELAY_MIN) || t - b.lastRelay >= RELAY_REPEAT) {
                b.dirty = false;                             // сначала сбросить, потом взять последний поворот
                Orientation cur = b.orientation;
                b.lastRelay = t;
                b.relayed = true;
                for (Player v : p.getViewers()) {
                    if (link.ready(v)) {
                        link.sendOrientation(v, p.getEntityId(), cur);
                    }
                }
            }
            if (b.lying != b.fallbackOn || (b.lying && t - b.lastFallback >= FALLBACK_REPEAT)) {
                sendFallback(p, b.lying);
                b.fallbackOn = b.lying;
                b.lastFallback = t;
            }
        }
    }

    /** Игрок вышел из вакуума / с сервера / модуль выключается: зрителям — op 4 и обычная поза. */
    private void stop(Player p, Body b) {
        applyBox(p, b, -1);
        if (b.relayed) {
            for (Player v : p.getViewers()) {
                if (link.ready(v)) {
                    link.sendOrientationStop(v, p.getEntityId());
                }
            }
            b.relayed = false;
        }
        if (b.fallbackOn) {
            sendFallback(p, false);
            b.fallbackOn = false;
        }
    }

    /** Поставить игроку рамку грани surface (−1 — обычную), если она другая. */
    private static void applyBox(Player p, Body b, int surface) {
        int want = PlayerBox.turned(surface) ? surface : -1;
        if (want == b.boxApplied) {
            return;
        }
        b.boxApplied = want;
        if (want < 0) {
            p.setBoundingBox(p.getEntityType().registry().boundingBox());   // обычная рамка игрока
        } else {
            double[] sz = PlayerBox.size(want);
            p.setBoundingBox(new BoundingBox(sz[0], sz[1], sz[2]));          // низ-середина — позиция, как у клиента
        }
    }

    /** Убрать игрока (вышел с сервера). */
    void forget(UUID id) {
        bodies.remove(id);
        rotating.remove(id);
    }

    /** onDisable: всем вернуть обычный вид. */
    void close() {
        for (var e : bodies.entrySet()) {
            Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(e.getKey());
            if (p != null) {
                stop(p, e.getValue());
            }
        }
        bodies.clear();
        rotating.clear();
    }

    /**
     * Запасная поза зрителям без своего поворота тел: lying — флаг «полёт на элитрах» и поза FALL_FLYING, иначе —
     * настоящие флаги и поза игрока с сервера.
     */
    private void sendFallback(Player p, boolean lying) {
        Map<Integer, Metadata.Entry<?>> real = p.getMetadataPacket().entries();
        byte flags = real.get(FLAGS_INDEX) != null && real.get(FLAGS_INDEX).value() instanceof Byte f ? f : 0;
        EntityPose pose = real.get(POSE_INDEX) != null && real.get(POSE_INDEX).value() instanceof EntityPose ps
            ? ps : EntityPose.STANDING;
        Map<Integer, Metadata.Entry<?>> m = new HashMap<>();
        m.put(FLAGS_INDEX, Metadata.Byte(fallbackFlags(flags, lying)));
        m.put(POSE_INDEX, Metadata.Pose(lying ? EntityPose.FALL_FLYING : pose));
        EntityMetaDataPacket packet = new EntityMetaDataPacket(p.getEntityId(), m);
        for (Player v : p.getViewers()) {
            if (!rotating.contains(v.getUuid())) {
                v.sendPacket(packet);
            }
        }
    }

    /** Флаги сущности для запасной позы (для тестов). */
    static byte fallbackFlags(byte real, boolean lying) {
        return lying ? (byte) (real | ELYTRA_BIT) : real;
    }

    /** Лежит ли тело: с учётом прошлого состояния (гистерезис). */
    static boolean lying(Orientation o, boolean was) {
        double tilt = o.tilt();
        return o.prone() || (was ? tilt > STAND_TILT : tilt > LIE_TILT);
    }
}
