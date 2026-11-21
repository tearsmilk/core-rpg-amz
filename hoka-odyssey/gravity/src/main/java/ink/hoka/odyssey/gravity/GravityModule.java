package ink.hoka.odyssey.gravity;

import ink.hoka.odyssey.api.OdysseyModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.attribute.AttributeModifier;
import net.minestom.server.entity.attribute.AttributeOperation;
import net.minestom.server.entity.vehicle.PlayerInputs;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.network.packet.server.play.ExplosionPacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.sound.SoundEvent;
import net.minestom.server.utils.WeightedList;

/**
 * gravity — вакуум/невесомость на выбранных картах. Игроку ставятся модификаторы атрибутов: гравитация = 0 (клиент
 * перестаёт падать), прыжок с земли = 0, урон от падения = 0. Тягу (пробел/Shift/WASD) сервер каждый тик добавляет
 * к скорости клиента пакетом взрыва с отдачей игроку: в отличие от setVelocity он не затирает скорость, которую
 * клиент набрал сам, а прибавляется к ней — управление остаётся плавным при любом пинге. Взрыв «пустой»: радиус 0,
 * звук intentionally_empty, частица далеко под игроком (клиент её не рисует).
 */
public final class GravityModule extends OdysseyModule {
    /** id модификаторов атрибутов модуля — по нему же снимаем. */
    static final Key MODIFIER = Key.key("hoka", "gravity_vacuum");
    private static final AttributeModifier ZERO = new AttributeModifier(MODIFIER, -1, AttributeOperation.ADD_MULTIPLIED_TOTAL);
    /** Насколько ниже игрока «взрыв» (частица и звук за пределами слышимости и видимости). */
    private static final double FAR_BELOW = 4096;

    private volatile Settings settings;
    private final Map<UUID, Floater> floaters = new ConcurrentHashMap<>();

    @Override
    protected void onEnable() {
        settings = Settings.from(config());
        registerCommand(new HoGravCommand(this));
        // PlayerSpawnEvent срабатывает и при входе, и при каждой смене карты (setInstance)
        events().addListener(PlayerSpawnEvent.class, e -> refresh(e.getPlayer(), e.getInstance(), true));
        events().addListener(PlayerDisconnectEvent.class, e -> floaters.remove(e.getPlayer().getUuid()));
        scheduler().repeat(this::tick, 1, 1);
        refreshAll();
        logger().info("gravity: вакуум на картах {}, команда /hograv", settings.maps());
    }

    @Override
    protected void onDisable() {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (floaters.containsKey(p.getUuid())) {
                clearAttributes(p);
            }
        }
        floaters.clear();
    }

    Settings settings() {
        return settings;
    }

    /** Перечитать config.yml и применить ко всем игрокам. */
    void reloadSettings() {
        config().reload();
        settings = Settings.from(config());
        refreshAll();
    }

    /** Сколько игроков сейчас в вакууме. */
    int floating() {
        return floaters.size();
    }

    /**
     * Включить/выключить вакуум на карте: записать список карт в config.yml (с сохранением комментариев) в
     * отдельном потоке, затем в тике перечитать и применить к игрокам. done получает null или текст ошибки.
     */
    void setMap(String map, boolean on, Consumer<String> done) {
        Set<String> maps = new LinkedHashSet<>(settings.maps());
        if (on) {
            maps.add(map);
        } else {
            maps.remove(map);
        }
        Path file = config().file();
        scheduler().async(() -> {
            String error = null;
            try {
                String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
                Files.createDirectories(file.getParent());
                Files.writeString(file, YamlList.replace(text, "maps", maps), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                error = e.toString();
                logger().warn("не записать {}: {}", file, e.toString());
            }
            String err = error;
            scheduler().run(() -> {
                if (err == null) {
                    reloadSettings();
                }
                done.accept(err);
            });
        });
    }

    private void refreshAll() {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            refresh(p, p.getInstance(), false);
        }
    }

    private boolean inVacuum(Instance instance) {
        return instance != null && server().mapName(instance).map(settings.maps()::contains).orElse(false);
    }

    /** Привести игрока в соответствие с картой: включить/выключить вакуум. reset — игрок только что появился. */
    private void refresh(Player p, Instance instance, boolean reset) {
        boolean should = inVacuum(instance);
        Floater f = floaters.get(p.getUuid());
        if (should) {
            if (f == null) {
                floaters.put(p.getUuid(), new Floater());
                if (!settings.enterHint().isEmpty()) {
                    p.sendActionBar(Component.text(settings.enterHint(), NamedTextColor.AQUA));
                }
            } else if (reset) {
                f.reset();
            }
            applyAttributes(p);
        } else if (f != null) {
            floaters.remove(p.getUuid());
            clearAttributes(p);
        }
    }

    private void applyAttributes(Player p) {
        Settings s = settings;
        set(p, Attribute.GRAVITY, true);
        set(p, Attribute.JUMP_STRENGTH, !s.groundJump());
        set(p, Attribute.FALL_DAMAGE_MULTIPLIER, !s.fallDamage());
    }

    private static void clearAttributes(Player p) {
        set(p, Attribute.GRAVITY, false);
        set(p, Attribute.JUMP_STRENGTH, false);
        set(p, Attribute.FALL_DAMAGE_MULTIPLIER, false);
    }

    /** Поставить (on) или снять модификатор «×0» модуля. Повторный вызов ничего не дублирует. */
    private static void set(Player p, Attribute a, boolean on) {
        var inst = p.getAttribute(a);
        boolean has = inst.modifiers().stream().anyMatch(m -> m.id().equals(MODIFIER));
        if (on && !has) {
            inst.addModifier(ZERO);
        } else if (!on && has) {
            inst.removeModifier(MODIFIER);
        }
    }

    private void tick() {
        Settings s = settings;
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            Instance instance = p.getInstance();
            boolean should = inVacuum(instance);
            if (should != floaters.containsKey(p.getUuid())) {
                refresh(p, instance, true);                     // карту включили/выключили, а игрок уже там
            }
            Floater f = floaters.get(p.getUuid());
            if (f == null) {
                continue;
            }
            Pos pos = p.getPosition();
            PlayerInputs in = p.inputs();
            Floater.Keys keys = new Floater.Keys(in.forward(), in.backward(), in.left(), in.right(), in.jump(), in.shift());
            boolean flying = p.isFlying() || p.getGameMode() == GameMode.SPECTATOR;
            Floater.Impulse imp = f.tick(s, pos.x(), pos.y(), pos.z(), pos.yaw(), keys, p.isOnGround(), flying);
            if (imp != null) {
                push(p, imp);
            }
        }
    }

    /** Прибавить импульс к скорости клиента (отдача «пустого» взрыва). */
    private static void push(Player p, Floater.Impulse imp) {
        Pos pos = p.getPosition();
        p.sendPacket(new ExplosionPacket(new Vec(pos.x(), pos.y() - FAR_BELOW, pos.z()), 0f, 0,
            new Vec(imp.x(), imp.y(), imp.z()), Particle.BUBBLE, SoundEvent.INTENTIONALLY_EMPTY, WeightedList.of()));
    }
}
