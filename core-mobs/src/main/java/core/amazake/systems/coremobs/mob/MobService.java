package core.amazake.systems.coremobs.mob;

import core.amazake.systems.coremobs.util.Keys;
import core.amazake.systems.coremobs.util.Text;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

/**
 * Спавн и настройка мобов из mobs.yml, учёт мобов регионов.
 *
 * <p>{@link #spawn} и всё, что трогает сущность, вызывается в потоке региона, где стоит моб. Учёт ({@link #tracked})
 * — потокобезопасная карта неизменяемых записей.</p>
 */
public final class MobService {

    /**
     * Моб, заспавненный регионом. Координаты — последние известные, по ним ставится задача проверки в нужный регион.
     */
    public record Tracked(UUID id, String mob, String region, UUID world, int x, int z) {
    }

    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
        EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final Supplier<MobCatalog> catalog;
    private final Supplier<String> nameFormat;
    private final Map<UUID, Tracked> tracked = new ConcurrentHashMap<>();

    public MobService(final Supplier<MobCatalog> catalog, final Supplier<String> nameFormat) {
        this.catalog = catalog;
        this.nameFormat = nameFormat;
    }

    public Map<UUID, Tracked> tracked() {
        return this.tracked;
    }

    public void track(final Tracked t) {
        this.tracked.put(t.id(), t);
    }

    public void untrack(final UUID id) {
        this.tracked.remove(id);
    }

    public int alive(final String region) {
        int n = 0;
        for (final Tracked t : this.tracked.values()) {
            if (t.region().equals(region)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Заспавнить моба. Поток региона {@code at}. {@code region} — имя региона-хозяина или null (моб из команды или
     * яйца: он сохраняется в мире, как обычный).
     */
    public Mob spawn(final MobDef def, final Location at, final String region) {
        final World world = at.getWorld();
        @SuppressWarnings("unchecked")
        final Class<Mob> type = (Class<Mob>) def.type().getEntityClass();
        final Mob mob = world.spawn(at, type, CreatureSpawnEvent.SpawnReason.CUSTOM, e -> this.configure(e, def, region));
        if (region != null && mob.isValid()) {
            this.track(new Tracked(mob.getUniqueId(), def.id(), region, world.getUID(), at.getBlockX(), at.getBlockZ()));
        }
        return mob;
    }

    private void configure(final Mob e, final MobDef def, final String region) {
        // Размер — до атрибутов: Slime#setSize сам выставляет здоровье, урон и скорость под размер.
        if (def.size() > 0) {
            if (e instanceof final org.bukkit.entity.Slime slime) {          // слизень и магмовый куб
                slime.setSize(def.size());
            } else if (e instanceof final org.bukkit.entity.Phantom phantom) {
                phantom.setSize(def.size());
            }
        }
        setBase(e, Attribute.MAX_HEALTH, def.health());
        setBase(e, Attribute.ATTACK_DAMAGE, def.damage());
        setBase(e, Attribute.MOVEMENT_SPEED, def.speed());
        setBase(e, Attribute.ARMOR, def.armor());
        setBase(e, Attribute.FOLLOW_RANGE, def.followRange());
        setBase(e, Attribute.KNOCKBACK_RESISTANCE, def.knockbackResistance());
        setBase(e, Attribute.SCALE, def.scale());
        e.setHealth(maxHealth(e));

        if (def.baby() && e instanceof final Ageable ageable) {
            ageable.setBaby();
        }
        e.setGlowing(def.glowing());
        e.setSilent(def.silent());

        final EntityEquipment equipment = e.getEquipment();
        def.equipment().forEach((slot, material) -> equipment.setItem(slot, new ItemStack(material)));
        final float dropChance = def.dropEquipment() ? 1.0f : 0.0f;
        for (final EquipmentSlot slot : SLOTS) {
            equipment.setDropChance(slot, dropChance);
        }
        for (final MobDef.Effect effect : def.effects()) {
            e.addPotionEffect(new PotionEffect(effect.type(), PotionEffect.INFINITE_DURATION, effect.amplifier(), false, false));
        }

        final PersistentDataContainer pdc = e.getPersistentDataContainer();
        pdc.set(Keys.MOB, PersistentDataType.STRING, def.id());
        if (region != null) {
            pdc.set(Keys.REGION, PersistentDataType.STRING, region);
            // Моб региона не сохраняется в чанк: выгрузился чанк или перезапустился сервер — регион заспавнит нового.
            e.setPersistent(false);
        } else {
            e.setPersistent(true);
        }
        e.setRemoveWhenFarAway(false);
        this.refreshName(e, def);
    }

    private static void setBase(final LivingEntity e, final Attribute attribute, final Double value) {
        if (value == null) {
            return;
        }
        final AttributeInstance instance = e.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private static double maxHealth(final LivingEntity e) {
        final AttributeInstance instance = e.getAttribute(Attribute.MAX_HEALTH);
        return instance == null ? e.getHealth() : instance.getValue();
    }

    /** Имя над головой с текущим здоровьем. Поток моба. */
    public void refreshName(final LivingEntity e, final MobDef def) {
        if (!def.showName()) {
            e.setCustomNameVisible(false);
            e.customName(Text.mm(def.name()));
            return;
        }
        final Component name = Text.mm(def.name());
        final Component full = def.showHealth()
            ? Text.mm(this.nameFormat.get(), Placeholder.component("name", name),
                Placeholder.unparsed("health", Text.num(Math.ceil(Math.max(0, e.getHealth()) * 10) / 10)),
                Placeholder.unparsed("max_health", Text.num(maxHealth(e))))
            : name;
        e.customName(full);
        e.setCustomNameVisible(true);
    }

    /** id моба из метки; null — это не наш моб. */
    public static String mobId(final Entity e) {
        return e.getPersistentDataContainer().get(Keys.MOB, PersistentDataType.STRING);
    }

    public static String regionOf(final Entity e) {
        return e.getPersistentDataContainer().get(Keys.REGION, PersistentDataType.STRING);
    }

    public MobDef defOf(final Entity e) {
        final String id = mobId(e);
        return id == null ? null : this.catalog.get().get(id);
    }

    /** Яйцо призыва моба. */
    public static ItemStack egg(final MobDef def, final int amount) {
        Material material = Bukkit.getItemFactory().getSpawnEgg(def.type());
        if (material == null) {
            material = Material.EGG;
        }
        final ItemStack item = new ItemStack(material, Math.max(1, Math.min(64, amount)));
        item.editMeta(meta -> {
            meta.displayName(Text.item("<yellow>Яйцо призыва:</yellow> ").append(Text.item(def.name())));
            meta.lore(Text.lore(java.util.List.of("<gray>ПКМ по блоку — призвать моба</gray>",
                "<dark_gray>" + Text.escape(def.id()) + "</dark_gray>")));
            meta.getPersistentDataContainer().set(Keys.EGG, PersistentDataType.STRING, def.id());
        });
        return item;
    }

    public static String eggId(final ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(Keys.EGG, PersistentDataType.STRING);
    }

    /** Поток моба: моб ещё жив и принадлежит текущему потоку. */
    public static boolean ownedAndValid(final Entity e) {
        return e != null && e.isValid() && Bukkit.isOwnedByCurrentRegion(e);
    }
}
