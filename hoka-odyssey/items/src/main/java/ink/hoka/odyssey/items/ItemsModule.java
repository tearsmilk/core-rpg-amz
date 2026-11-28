package ink.hoka.odyssey.items;

import ink.hoka.odyssey.api.OdysseyModule;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.item.ItemDropEvent;
import net.minestom.server.event.item.PickupItemEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.inventory.TransactionOption;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.server.play.CollectItemPacket;

/**
 * items — выбрасывание и подбор предметов как в ванили.
 * <p>
 * Выброс: Minestom сам разбирает Q / Ctrl+Q, клик курсором за окно, Q над слотом, выброс из креативного меню и
 * закрытие окна с предметом на курсоре — во всех случаях зовёт {@code Player.dropItem}, то есть
 * {@link ItemDropEvent}, и сам убирает предмет из инвентаря, если событие не отменено. Не хватает только сущности
 * на земле — её и создаёт модуль.
 * <p>
 * Подбор: {@code LivingEntity.update} у игрока (canPickupItem = true по умолчанию) находит рядом ItemEntity, зовёт
 * {@link PickupItemEvent} и, если оно не отменено, шлёт CollectItemPacket (анимация и звук подбора у клиента) и
 * удаляет сущность. В инвентарь Minestom предмет не кладёт — это делает модуль; при частичном месте событие
 * отменяется, остаток остаётся лежать.
 */
public final class ItemsModule extends OdysseyModule {
    private volatile Settings settings;
    /** Сущности, созданные модулем, — убираются в onDisable. */
    private final Set<ItemEntity> spawned = ConcurrentHashMap.newKeySet();
    /**
     * Ставится первым делом в onDisable: слушатели снимаются ядром позже (после onDisable), и выброс, пришедший в
     * этот промежуток, не должен создать сущность, о которой модуль уже не знает.
     */
    private volatile boolean disabled;
    /** Насколько ниже дна мира предмет считается упавшим в пустоту и удаляется (в ванили 64). */
    private static final int VOID_DEPTH = 64;

    @Override
    protected void onEnable() {
        settings = Settings.from(config());
        events().addListener(ItemDropEvent.class, this::onDrop);
        events().addListener(PickupItemEvent.class, this::onPickup);
        // раз в секунду: убрать упавшие в пустоту, забыть подобранные/слитые/исчезнувшие
        scheduler().repeat(this::sweep, 20, 20);
        logger().info("items: выбрасывание и подбор предметов включены (подбор через {} мс, исчезновение через {} с, "
                + "без исчезновения на картах {})", settings.pickupDelayMs(),
            settings.despawnSeconds() == 0 ? "никогда" : settings.despawnSeconds(), settings.noDespawnMaps());
    }

    @Override
    protected void onDisable() {
        disabled = true;
        for (ItemEntity e : spawned) {
            if (!e.isRemoved()) {
                e.remove();
            }
        }
        spawned.clear();
    }

    /** Удалить предметы, улетевшие ниже дна мира (как в ванили), и забыть уже удалённые. */
    private void sweep() {
        for (ItemEntity e : spawned) {
            Instance in = e.getInstance();
            if (!e.isRemoved() && in != null
                && e.getPosition().y() < in.getCachedDimensionType().minY() - VOID_DEPTH) {
                e.remove();
            }
        }
        spawned.removeIf(ItemEntity::isRemoved);
    }

    private void onDrop(ItemDropEvent e) {
        if (e.isCancelled() || disabled) {
            return;
        }
        Player p = e.getPlayer();
        Instance instance = p.getInstance();
        ItemStack stack = e.getItemStack();
        if (instance == null || stack.isAir()) {
            return;
        }
        Settings s = settings;
        Pos eye = p.getPosition();
        double[] v = Toss.velocity(eye.yaw(), eye.pitch(), s.throwSpeed(), s.spread() ? ThreadLocalRandom.current() : null);
        spawn(instance, eye.add(0, p.getEyeHeight() - Toss.BELOW_EYES, 0).withView(0, 0), stack,
            new Vec(v[0], v[1], v[2]).mul(20));                // блоков/тик → блоков/сек (так в Minestom)
    }

    /** Создать лежащий предмет с настройками модуля. velocity — блоков/сек. null — модуль уже выключен. */
    ItemEntity spawn(Instance instance, Pos pos, ItemStack stack, Vec velocity) {
        if (disabled) {
            return null;
        }
        Settings s = settings;
        ItemEntity item = new ItemEntity(stack);
        item.setPickupDelay(Duration.ofMillis(s.pickupDelayMs()));
        item.setMergeable(s.merge());
        item.setMergeRange(s.mergeRange());
        item.setVelocity(velocity);                            // до setInstance — уйдёт вместе с пакетом появления
        if (s.despawnSeconds() > 0
            && !server().mapName(instance).map(s.noDespawnMaps()::contains).orElse(false)) {
            item.scheduleRemove(Duration.ofSeconds(s.despawnSeconds()));
        }
        spawned.add(item);
        item.setInstance(instance, pos);
        if (disabled) {                                        // onDisable успел пройти, пока создавали
            item.remove();
            return null;
        }
        return item;
    }

    private void onPickup(PickupItemEvent e) {
        if (e.isCancelled() || !(e.getLivingEntity() instanceof Player p)) {
            return;
        }
        if (p.getGameMode() == GameMode.SPECTATOR || p.isDead()) {
            e.setCancelled(true);
            return;
        }
        ItemEntity item = e.getItemEntity();
        ItemStack stack = item.getItemStack();
        ItemStack rest = p.getInventory().addItemStack(stack, TransactionOption.ALL);
        int left = rest.isAir() ? 0 : rest.amount();
        int taken = stack.amount() - left;
        if (left == 0) {
            return;                                            // всё влезло: Minestom покажет подбор и удалит сущность
        }
        e.setCancelled(true);                                  // целиком не влезло — сущность остаётся
        if (taken > 0) {
            // Порядок важен: клиент вычитает taken из того количества, что видит сейчас, и удаляет сущность у себя,
            // если вышло ≤ 0. setItemStack сразу шлёт метаданные, поэтому сначала collect (у клиента ещё полная
            // стопка: original − taken = rest > 0), потом новое количество.
            p.sendPacketToViewersAndSelf(new CollectItemPacket(item.getEntityId(), p.getEntityId(), taken));
            item.setItemStack(rest);
        }
    }
}
