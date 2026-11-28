package ink.hoka.odyssey.starmap;

import ink.hoka.odyssey.api.OdysseyModule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.timer.Task;

/**
 * Данные для карты-радара (клиентский модуль starmap, клавиша M). Пока у игрока открыта карта (C2S op 1), раз в
 * {@code period-ticks} шлём ему корабли его карты (модуль ships, рефлексией) и игроков рядом. Протокол —
 * {@link StarmapProtocol}. Всё своё (подписка clientmods, задача, список открытых карт) снимается в onDisable.
 */
public final class StarmapModule extends OdysseyModule {
    private ClientLink link;
    private ShipsProbe probe;
    private Task task;
    /** Игроки с открытой картой → время последнего «открыта» (мс). Пишет поток clientmods, читает тик. */
    private final Map<UUID, Long> open = new ConcurrentHashMap<>();
    /** Когда игрок последний раз спрашивал «на какой я карте» (мс) — не чаще раза в секунду. */
    private final Map<UUID, Long> asked = new ConcurrentHashMap<>();

    private int period;
    private double playerRadius;
    private double shipRadius;
    private boolean showPlayers;
    private boolean showShips;
    private boolean showStowed;
    private int maxContacts;
    private long timeoutMs;
    private long ticks;

    @Override
    protected void onEnable() {
        readConfig();
        link = new ClientLink(this, logger());
        probe = new ShipsProbe(logger());
        link.resolve(this::message);
        events().addListener(PlayerDisconnectEvent.class, e -> {
            open.remove(e.getPlayer().getUuid());
            asked.remove(e.getPlayer().getUuid());
        });
        task = scheduler().repeat(this::tick, period, 1);
        logger().info("starmap: включён (раз в {} тиков, игроки в радиусе {}, clientmods {})", period,
                (long) playerRadius, link.connected() ? "есть" : "нет — карта ни у кого не откроется");
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (link != null) {
            link.close();
        }
        open.clear();
        asked.clear();
    }

    private void readConfig() {
        var c = config();
        period = clamp(c.getInt("period-ticks", 10), 2, 200);
        playerRadius = Math.max(0, c.getDouble("player-radius", 2000));
        shipRadius = Math.max(0, c.getDouble("ship-radius", 0));
        showPlayers = c.getBoolean("show-players", true);
        showShips = c.getBoolean("show-ships", true);
        showStowed = c.getBoolean("show-stowed", false);
        maxContacts = clamp(c.getInt("max-contacts", 256), 1, 4096);
        timeoutMs = clamp(c.getInt("open-timeout-seconds", 15), 6, 600) * 1000L;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Object service(Class<?> type) {
        return services().get((Class) type).orElse(null);
    }

    /** Сообщение клиентского модуля (поток clientmods): только разобрать и запомнить. */
    private void message(Player p, byte[] data) {
        if (p == null) {
            return;
        }
        if (StarmapProtocol.isWhere(data)) {
            long now = System.currentTimeMillis();
            Long last = asked.put(p.getUuid(), now);
            if (last == null || now - last >= 1000) {
                scheduler().run(() -> {
                    Instance inst = p.getInstance();
                    if (p.isOnline() && inst != null) {
                        link.send(p, StarmapProtocol.where(server().mapName(inst).orElse("")));
                    }
                });
            }
            return;
        }
        int o = StarmapProtocol.decodeOpen(data);
        if (o == 1) {
            boolean fresh = open.put(p.getUuid(), System.currentTimeMillis()) == null;
            if (fresh) {
                scheduler().run(() -> sendTo(p));               // сразу, не дожидаясь очередного раза
            }
        } else if (o == 0) {
            open.remove(p.getUuid());
        }
    }

    private void tick() {
        ticks++;
        if (ticks % 100 == 0) {
            link.resolve(this::message);                        // clientmods могли перезагрузить
        }
        if (ticks % period != 0 || open.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        open.entrySet().removeIf(e -> now - e.getValue() > timeoutMs);
        List<ShipsProbe.RawShip> ships = showShips ? probe.ships(this) : List.of();
        for (UUID id : open.keySet()) {
            Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(id);
            if (p == null) {
                open.remove(id);
                continue;
            }
            sendTo(p, ships);
        }
    }

    private void sendTo(Player p) {
        if (p.isOnline()) {
            sendTo(p, showShips ? probe.ships(this) : List.of());
        }
    }

    private void sendTo(Player p, List<ShipsProbe.RawShip> all) {
        Instance inst = p.getInstance();
        if (inst == null) {
            return;
        }
        String map = server().mapName(inst).orElse("");
        Pos me = p.getPosition();
        String myId = p.getUuid().toString();
        List<StarmapProtocol.ShipContact> ships = new ArrayList<>();
        for (ShipsProbe.RawShip s : all) {
            if (map.isEmpty() || !map.equals(s.map())) {
                continue;
            }
            boolean stowed = "STOWED".equals(s.state());
            if (stowed && !showStowed) {
                continue;
            }
            if (shipRadius > 0 && dist(me, s.x(), s.y(), s.z()) > shipRadius) {
                continue;
            }
            int flags = 0;
            if (myId.equals(s.owner())) {
                flags |= StarmapProtocol.SHIP_MINE;
            }
            if ("FLYING".equals(s.state())) {
                flags |= StarmapProtocol.SHIP_FLYING;
            }
            if (s.pilot() == p) {
                flags |= StarmapProtocol.SHIP_PILOT_ME;
            }
            if (stowed) {
                flags |= StarmapProtocol.SHIP_STOWED;
            }
            ships.add(new StarmapProtocol.ShipContact(s.id(), s.name(), s.ownerName(), flags, s.x(), s.y(), s.z(), s.yaw()));
        }
        ships.sort(Comparator.comparingDouble(s -> dist(me, s.x(), s.y(), s.z())));
        if (ships.size() > maxContacts) {
            ships = new ArrayList<>(ships.subList(0, maxContacts));
        }
        List<StarmapProtocol.PlayerContact> players = new ArrayList<>();
        if (showPlayers) {
            List<Object> pilots = new ArrayList<>();
            for (ShipsProbe.RawShip s : all) {
                if (s.pilot() != null) {
                    pilots.add(s.pilot());
                }
            }
            for (Player o : inst.getPlayers()) {
                if (o == p) {
                    continue;
                }
                Pos op = o.getPosition();
                if (dist(me, op.x(), op.y(), op.z()) > playerRadius) {
                    continue;
                }
                int flags = pilots.contains(o) ? StarmapProtocol.PLAYER_PILOT : 0;
                players.add(new StarmapProtocol.PlayerContact(o.getUuid(), o.getUsername(), op.x(), op.y(), op.z(), op.yaw(), flags));
            }
            players.sort(Comparator.comparingDouble(o -> dist(me, o.x(), o.y(), o.z())));
            if (players.size() > maxContacts) {
                players = new ArrayList<>(players.subList(0, maxContacts));
            }
        }
        link.send(p, StarmapProtocol.contacts(map, ships, players));
    }

    private static double dist(Pos a, double x, double y, double z) {
        double dx = a.x() - x;
        double dy = a.y() - y;
        double dz = a.z() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
