package ink.hoka.odyssey.oxygen;

import ink.hoka.odyssey.api.OdysseyModule;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Metadata;
import net.minestom.server.entity.MetadataDef;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerRespawnEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket;
import net.minestom.server.sound.SoundEvent;

/**
 * oxygen — на картах без воздуха (maps в config.yml) у игрока без скафандра кончается кислород.
 * <p>
 * Шкала — ванильные пузырьки: метаданные «air supply» игрока (EntityMeta#setAirTicks, 0…300). Minestom сам воздух
 * не считает — каждый тик модуль убывает/восстанавливает свой запас ({@link Lungs}) и шлёт его клиенту. Когда
 * воздух кончился — урон типа drown (как от утопления). Креатив и наблюдатель урона не получают.
 * <p>
 * Скафандр ({@link Suit}) — стекло с компонентом equippable: ПКМ в воздух меняет его местами со шлемом сам Minestom
 * (UseItemListener), слот шлема в инвентаре принимает его клиент. ПКМ по блоку модуль перехватывает: скафандр не
 * ставится блоком, а надевается.
 */
public final class OxygenModule extends OdysseyModule {
    private volatile Settings settings;
    /** Игроки, у которых запас воздуха не полный (или тратится). С полным запасом вне вакуума — не храним. */
    private final Map<UUID, Lungs> lungs = new ConcurrentHashMap<>();

    @Override
    protected void onEnable() {
        settings = Settings.from(config());
        registerCommand(new HoSuitCommand(this));
        events().addListener(PlayerDisconnectEvent.class, e -> lungs.remove(e.getPlayer().getUuid()));
        events().addListener(PlayerRespawnEvent.class, e -> {
            if (lungs.remove(e.getPlayer().getUuid()) != null) {
                e.getPlayer().getEntityMeta().setAirTicks(Lungs.MAX);
            }
        });
        events().addListener(PlayerBlockPlaceEvent.class, this::onPlace);
        scheduler().repeat(this::tick, 1, 1);
        logger().info("oxygen: нет воздуха на картах {}, скафандр — /hosuit", settings.maps());
    }

    @Override
    protected void onDisable() {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            if (lungs.containsKey(p.getUuid())) {
                p.getEntityMeta().setAirTicks(Lungs.MAX);       // вернуть полный запас — шкала пропадёт
            }
        }
        lungs.clear();
    }

    Settings settings() {
        return settings;
    }

    /** На голове скафандр. */
    static boolean wearsSuit(Player p) {
        return Suit.is(p.getEquipment(EquipmentSlot.HELMET));
    }

    private boolean airless(Instance instance) {
        return instance != null && server().mapName(instance).map(settings.maps()::contains).orElse(false);
    }

    private void tick() {
        Settings s = settings;
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            Instance instance = p.getInstance();
            if (instance == null) {
                continue;
            }
            GameMode gm = p.getGameMode();
            boolean canHurt = gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
            boolean shows = canHurt || (gm == GameMode.CREATIVE && s.creativeBubbles());
            boolean breathless = shows && !p.isDead() && airless(instance) && !wearsSuit(p);
            Lungs l = lungs.get(p.getUuid());
            if (l == null) {
                if (!breathless) {
                    continue;                                   // полный запас и тратить нечего — ничего не шлём
                }
                l = new Lungs();
                lungs.put(p.getUuid(), l);
            }
            boolean hurt = l.tick(s, breathless, canHurt);
            sendAir(p, l.meta(), breathless);
            if (hurt) {
                p.damage(DamageType.DROWN, s.damage());
            }
            if (l.warnTick(breathless && canHurt && !s.warning().isEmpty(), s.warningInterval())) {
                p.sendActionBar(Suit.text(s.warning()));
            }
            if (!breathless && l.full()) {
                lungs.remove(p.getUuid());
            }
        }
    }

    /**
     * Отправить запас воздуха. setAirTicks шлёт пакет только при изменении значения, а клиент вне воды сам
     * прибавляет себе воздух — поэтому, пока воздух тратится, неизменившееся значение досылаем пакетом напрямую.
     */
    private static void sendAir(Player p, int value, boolean force) {
        var meta = p.getEntityMeta();
        if (meta.getAirTicks() != value) {
            meta.setAirTicks(value);
        } else if (force) {
            p.sendPacket(new EntityMetaDataPacket(p.getEntityId(),
                Map.of(MetadataDef.AIR_TICKS.index(), Metadata.VarInt(value))));
        }
    }

    /** ПКМ скафандром по блоку: не ставить стекло, а надеть (как шлем — поменять местами с тем, что на голове). */
    private void onPlace(PlayerBlockPlaceEvent e) {
        Player p = e.getPlayer();
        ItemStack inHand = p.getItemInHand(e.getHand());
        if (!Suit.is(inHand)) {
            return;
        }
        e.setCancelled(true);                                   // Minestom после отмены сам обновит чанк и инвентарь
        ItemStack head = p.getEquipment(EquipmentSlot.HELMET);
        p.setEquipment(EquipmentSlot.HELMET, inHand);
        p.setItemInHand(e.getHand(), head);
        p.playSound(Sound.sound(SoundEvent.ITEM_ARMOR_EQUIP_GENERIC, Sound.Source.PLAYER, 1f, 1f));
    }
}
