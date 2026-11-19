package dev.tearsmilk.coreshaders;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class Effects {
    public static final String NAMESPACE = "coreshaders";

    public enum PlaceResult {
        OK,
        NOT_AIR,
        NOT_FREE,
        BLOCKED,
        NO_SUPPORT
    }

    private final CoreShadersPlugin plugin;
    private final Keys keys;

    public Effects(CoreShadersPlugin plugin, Keys keys) {
        this.plugin = plugin;
        this.keys = keys;
    }

    private Settings s() {
        return plugin.settings();
    }

    public boolean isEffect(Entity e) {
        return e instanceof ItemDisplay && e.getPersistentDataContainer().has(keys.effect, PersistentDataType.BYTE);
    }

    public boolean isHitbox(Entity e) {
        return e instanceof Interaction && e.getPersistentDataContainer().has(keys.owner, PersistentDataType.STRING);
    }

    public ItemDisplay effectOf(Entity e) {
        if (isEffect(e)) {
            return (ItemDisplay) e;
        }
        if (isHitbox(e)) {
            String id = e.getPersistentDataContainer().get(keys.owner, PersistentDataType.STRING);
            Entity d = entity(id);
            if (d != null && isEffect(d)) {
                return (ItemDisplay) d;
            }
        }
        return null;
    }

    private static Entity entity(String id) {
        if (id == null) {
            return null;
        }
        try {
            Entity e = Bukkit.getEntity(UUID.fromString(id));
            return e != null && e.isValid() ? e : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public EffectData data(ItemDisplay d) {
        PersistentDataContainer pdc = d.getPersistentDataContainer();
        Shape shape = Shape.parse(pdc.get(keys.shape, PersistentDataType.STRING));
        String image = pdc.get(keys.image, PersistentDataType.STRING);
        Integer color = pdc.get(keys.color, PersistentDataType.INTEGER);
        Dir face = Dir.parse(pdc.get(keys.face, PersistentDataType.STRING));
        int[] b = pdc.get(keys.block, PersistentDataType.INTEGER_ARRAY);
        if (shape == null) {
            shape = Shape.CUBE;
        }
        if (image == null) {
            image = s().defaultImage;
        }
        if (face == null) {
            face = Dir.UP;
        }
        if (b == null || b.length != 3) {
            Location l = d.getLocation();
            b = new int[] {l.getBlockX(), l.getBlockY(), l.getBlockZ()};
        }
        return new EffectData(shape, image, color == null ? 0xFFFFFF : color, face, b[0], b[1], b[2]);
    }

    public ItemStack displayItem(EffectData e) {
        ItemStack item = new ItemStack(Material.PAPER);
        item.setData(DataComponentTypes.ITEM_MODEL, Key.key(NAMESPACE, e.modelKey()));
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
            CustomModelData.customModelData().addColor(Color.fromRGB(e.color() & 0xFFFFFF)).build());
        return item;
    }

    private Location displayLocation(World w, EffectData e) {
        double[] c = Geometry.center(e.shape(), e.bx(), e.by(), e.bz(), e.face(), s().planeOffset);
        return new Location(w, c[0], c[1], c[2], 0f, 0f);
    }

    private Transformation transformation(EffectData e) {
        float[] q = e.shape() == Shape.CUBE ? new float[] {0f, 0f, 0f, 1f} : Geometry.planeRotation(e.face());
        return new Transformation(new Vector3f(), new Quaternionf(q[0], q[1], q[2], q[3]), new Vector3f(1f, 1f, 1f),
            new Quaternionf());
    }

    private void writeData(ItemDisplay d, EffectData e) {
        PersistentDataContainer pdc = d.getPersistentDataContainer();
        pdc.set(keys.effect, PersistentDataType.BYTE, (byte) 1);
        pdc.set(keys.shape, PersistentDataType.STRING, e.shape().id());
        pdc.set(keys.image, PersistentDataType.STRING, e.image());
        pdc.set(keys.color, PersistentDataType.INTEGER, e.color());
        pdc.set(keys.face, PersistentDataType.STRING, e.face().id());
        pdc.set(keys.block, PersistentDataType.INTEGER_ARRAY, new int[] {e.bx(), e.by(), e.bz()});
    }

    public ItemDisplay spawn(World w, EffectData e) {
        Settings st = s();
        ItemDisplay d = w.spawn(displayLocation(w, e), ItemDisplay.class, disp -> {
            disp.setItemStack(displayItem(e));
            disp.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            disp.setTransformation(transformation(e));
            disp.setBrightness(new Display.Brightness(st.brightness, st.brightness));
            disp.setPersistent(true);
            disp.setViewRange(2.0f);
            writeData(disp, e);
        });
        spawnHitbox(d, e);
        return d;
    }

    private Interaction spawnHitbox(ItemDisplay d, EffectData e) {
        double[] h = Geometry.hitbox(e.shape(), e.bx(), e.by(), e.bz(), e.face(), s().planeOffset);
        Location l = new Location(d.getWorld(), h[0], h[1], h[2]);
        Interaction i = d.getWorld().spawn(l, Interaction.class, it -> {
            it.setInteractionWidth((float) h[3]);
            it.setInteractionHeight((float) h[4]);
            it.setResponsive(true);
            it.setPersistent(true);
            it.getPersistentDataContainer().set(keys.owner, PersistentDataType.STRING, d.getUniqueId().toString());
        });
        d.getPersistentDataContainer().set(keys.link, PersistentDataType.STRING, i.getUniqueId().toString());
        return i;
    }

    public Interaction hitbox(ItemDisplay d) {
        Entity e = entity(d.getPersistentDataContainer().get(keys.link, PersistentDataType.STRING));
        return e instanceof Interaction i ? i : null;
    }

    public void update(ItemDisplay d, EffectData e) {
        writeData(d, e);
        d.setItemStack(displayItem(e));
        d.teleport(displayLocation(d.getWorld(), e));
        d.setTransformation(transformation(e));
        Interaction i = hitbox(d);
        if (i != null) {
            i.remove();
        }
        spawnHitbox(d, e);
    }

    public void remove(ItemDisplay d) {
        Interaction i = hitbox(d);
        if (i != null) {
            i.remove();
        }
        d.remove();
    }

    public List<ItemDisplay> near(Location center, double radius) {
        List<ItemDisplay> out = new ArrayList<>();
        for (ItemDisplay d : center.getWorld().getNearbyEntitiesByType(ItemDisplay.class, center, radius)) {
            if (isEffect(d)) {
                out.add(d);
            }
        }
        return out;
    }

    public List<ItemDisplay> all(World w) {
        List<ItemDisplay> out = new ArrayList<>();
        for (ItemDisplay d : w.getEntitiesByClass(ItemDisplay.class)) {
            if (isEffect(d)) {
                out.add(d);
            }
        }
        return out;
    }

    public List<ItemDisplay> at(World w, int bx, int by, int bz) {
        List<ItemDisplay> out = new ArrayList<>();
        for (ItemDisplay d : near(new Location(w, bx + 0.5, by + 0.5, bz + 0.5), 1.0)) {
            EffectData e = data(d);
            if (e.bx() == bx && e.by() == by && e.bz() == bz) {
                out.add(d);
            }
        }
        return out;
    }

    public PlaceResult check(World w, EffectData e, ItemDisplay ignore) {
        Block b = w.getBlockAt(e.bx(), e.by(), e.bz());
        for (ItemDisplay other : at(w, e.bx(), e.by(), e.bz())) {
            if (other.equals(ignore)) {
                continue;
            }
            EffectData o = data(other);
            if (e.shape() == Shape.CUBE || o.shape() == Shape.CUBE || o.face() == e.face()) {
                return PlaceResult.NOT_FREE;
            }
        }
        if (e.shape() == Shape.CUBE) {
            return b.getType().isAir() ? PlaceResult.OK : PlaceResult.NOT_AIR;
        }
        if (b.getType().isOccluding()) {
            return PlaceResult.BLOCKED;
        }
        Block support = b.getRelative(-e.face().dx, -e.face().dy, -e.face().dz);
        return support.getType().isAir() ? PlaceResult.NO_SUPPORT : PlaceResult.OK;
    }

    public PlaceResult place(Block clicked, Dir face, Wand.State st) {
        Block b = clicked.getRelative(face.dx, face.dy, face.dz);
        EffectData e = new EffectData(st.shape(), st.image(), st.color(), face, b.getX(), b.getY(), b.getZ());
        PlaceResult r = check(clicked.getWorld(), e, null);
        if (r == PlaceResult.OK) {
            spawn(clicked.getWorld(), e);
        }
        return r;
    }

    public ItemDisplay target(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        double max = s().maxDistance;
        double[] o = {eye.getX(), eye.getY(), eye.getZ()};
        double[] dv = {dir.getX(), dir.getY(), dir.getZ()};
        ItemDisplay best = null;
        double bestT = Double.MAX_VALUE;
        for (ItemDisplay d : near(eye, max + 1.5)) {
            EffectData e = data(d);
            double t = Geometry.rayBox(o, dv, Geometry.box(e.shape(), e.bx(), e.by(), e.bz(), e.face()), max);
            if (t >= 0 && t < bestT) {
                bestT = t;
                best = d;
            }
        }
        if (best == null) {
            return null;
        }
        RayTraceResult r = eye.getWorld().rayTraceBlocks(eye, dir, max, FluidCollisionMode.NEVER, true);
        if (r != null && r.getHitPosition().distance(eye.toVector()) + 0.08 < bestT) {
            return null;
        }
        return best;
    }

    public int verify(Collection<? extends Entity> entities) {
        int fixed = 0;
        for (Entity en : entities) {
            if (!en.isValid()) {
                continue;
            }
            if (isHitbox(en)) {
                Entity owner = entity(en.getPersistentDataContainer().get(keys.owner, PersistentDataType.STRING));
                if (owner == null || !isEffect(owner)) {
                    en.remove();
                    fixed++;
                }
            } else if (isEffect(en)) {
                ItemDisplay d = (ItemDisplay) en;
                Interaction i = hitbox(d);
                if (i == null) {
                    spawnHitbox(d, data(d));
                    fixed++;
                }
            }
        }
        return fixed;
    }

    public int verifyAll() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            List<Entity> list = new ArrayList<>();
            list.addAll(w.getEntitiesByClass(Interaction.class));
            list.addAll(w.getEntitiesByClass(ItemDisplay.class));
            n += verify(list);
        }
        return n;
    }
}
