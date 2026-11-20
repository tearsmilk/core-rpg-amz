package dev.tearsmilk.coreshaders;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
        if (image == null || (!s().images.contains(image) && !Settings.DEFAULT_IMAGES.contains(image))) {
            image = s().defaultImage;
        }
        if (face == null) {
            face = Dir.UP;
        }
        if (b == null || b.length != 3) {
            Location l = d.getLocation();
            b = new int[] {l.getBlockX(), l.getBlockY(), l.getBlockZ()};
        }
        Byte rim = pdc.get(keys.rim, PersistentDataType.BYTE);
        boolean r = rim == null ? s().rimDefault : rim != 0;
        return new EffectData(shape, image, color == null ? 0xFFFFFF : color, face, b[0], b[1], b[2], r);
    }

    public String storedImage(ItemDisplay d) {
        return d.getPersistentDataContainer().get(keys.image, PersistentDataType.STRING);
    }

    public ItemStack displayItem(EffectData e, int[] masks) {
        ItemStack item = new ItemStack(Material.PAPER);
        item.setData(DataComponentTypes.ITEM_MODEL, Key.key(NAMESPACE, e.modelKey()));
        CustomModelData.Builder b = CustomModelData.customModelData();
        for (int m : masks) {
            b.addColor(Color.fromRGB(Faces.encode(e.color() & 0xFFFFFF, m)));
        }
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, b.build());
        return item;
    }

    private static long blockKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    private final class Area implements Faces.Lookup {
        private final Set<Long> cubes = new HashSet<>();
        private final Set<Long> planes = new HashSet<>();
        private final Map<ItemDisplay, EffectData> all = new LinkedHashMap<>();

        Area(World w, double x, double y, double z, double radius) {
            for (ItemDisplay d : near(new Location(w, x, y, z), radius)) {
                EffectData e = data(d);
                all.put(d, e);
                long k = blockKey(e.bx(), e.by(), e.bz());
                if (e.shape() == Shape.CUBE) {
                    cubes.add(k);
                } else {
                    planes.add(k * 8 + e.face().ordinal());
                }
            }
        }

        @Override
        public boolean cube(int x, int y, int z) {
            return cubes.contains(blockKey(x, y, z));
        }

        @Override
        public boolean plane(int x, int y, int z, Dir face) {
            return planes.contains(blockKey(x, y, z) * 8 + face.ordinal());
        }

        int[] masks(EffectData e) {
            return e.shape() == Shape.CUBE ? Faces.cubeMasks(e.rim(), e.bx(), e.by(), e.bz(), this)
                : Faces.planeMasks(e.rim(), e.bx(), e.by(), e.bz(), e.face(), this);
        }
    }

    private boolean apply(ItemDisplay d, EffectData e, Area a) {
        ItemStack item = displayItem(e, a.masks(e));
        if (item.isSimilar(d.getItemStack())) {
            return false;
        }
        d.setItemStack(item);
        return true;
    }

    public void refreshAround(World w, int x, int y, int z) {
        Area a = new Area(w, x + 0.5, y + 0.5, z + 0.5, 4.2);
        for (Map.Entry<ItemDisplay, EffectData> en : a.all.entrySet()) {
            EffectData e = en.getValue();
            if (Math.abs(e.bx() - x) <= 1 && Math.abs(e.by() - y) <= 1 && Math.abs(e.bz() - z) <= 1) {
                apply(en.getKey(), e, a);
            }
        }
    }

    public boolean refresh(ItemDisplay d) {
        EffectData e = data(d);
        return apply(d, e, new Area(d.getWorld(), e.bx() + 0.5, e.by() + 0.5, e.bz() + 0.5, 2.8));
    }

    public List<ItemDisplay> group(ItemDisplay start, int max) {
        List<ItemDisplay> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        ArrayDeque<ItemDisplay> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start.getUniqueId());
        while (!queue.isEmpty() && out.size() < max) {
            ItemDisplay d = queue.poll();
            out.add(d);
            EffectData e = data(d);
            for (ItemDisplay o : near(new Location(d.getWorld(), e.bx() + 0.5, e.by() + 0.5, e.bz() + 0.5), 1.9)) {
                if (seen.contains(o.getUniqueId())) {
                    continue;
                }
                EffectData oe = data(o);
                int dist = Math.abs(oe.bx() - e.bx()) + Math.abs(oe.by() - e.by()) + Math.abs(oe.bz() - e.bz());
                if (dist <= 1) {
                    seen.add(o.getUniqueId());
                    queue.add(o);
                }
            }
        }
        return out;
    }

    public Actions.Data actions(ItemDisplay d) {
        PersistentDataContainer pdc = d.getPersistentDataContainer();
        List<Actions.Action> list = new ArrayList<>();
        List<String> raw = pdc.get(keys.commands, PersistentDataType.LIST.strings());
        if (raw != null) {
            for (String r : raw) {
                Actions.Action a = Actions.Action.decode(r);
                if (a != null) {
                    list.add(a);
                }
            }
        }
        Actions.Trigger t = Actions.Trigger.parse(pdc.get(keys.trigger, PersistentDataType.STRING));
        Double cd = pdc.get(keys.cooldown, PersistentDataType.DOUBLE);
        String perm = pdc.get(keys.permission, PersistentDataType.STRING);
        String msg = pdc.get(keys.message, PersistentDataType.STRING);
        String group = pdc.get(keys.group, PersistentDataType.STRING);
        return new Actions.Data(list, t == null ? s().defaultTrigger : t, cd == null ? s().defaultCooldown : cd,
            perm, msg, group == null ? d.getUniqueId().toString() : group);
    }

    public void setActions(ItemDisplay d, Actions.Data a) {
        PersistentDataContainer pdc = d.getPersistentDataContainer();
        List<String> raw = new ArrayList<>();
        for (Actions.Action x : a.actions()) {
            raw.add(x.encode());
        }
        if (raw.isEmpty()) {
            pdc.remove(keys.commands);
        } else {
            pdc.set(keys.commands, PersistentDataType.LIST.strings(), raw);
        }
        pdc.set(keys.trigger, PersistentDataType.STRING, a.trigger().id());
        pdc.set(keys.cooldown, PersistentDataType.DOUBLE, a.cooldown());
        setOrRemove(pdc, keys.permission, a.permission());
        setOrRemove(pdc, keys.message, a.message());
        setOrRemove(pdc, keys.group, a.group());
        plugin.triggers().index(d);
    }

    private static void setOrRemove(PersistentDataContainer pdc, NamespacedKey k, String v) {
        if (v == null || v.isEmpty()) {
            pdc.remove(k);
        } else {
            pdc.set(k, PersistentDataType.STRING, v);
        }
    }

    public boolean hasActions(ItemDisplay d) {
        return d.getPersistentDataContainer().has(keys.commands);
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
        pdc.set(keys.rim, PersistentDataType.BYTE, (byte) (e.rim() ? 1 : 0));
    }

    public ItemDisplay spawn(World w, EffectData e) {
        Settings st = s();
        int n = e.shape() == Shape.CUBE ? Faces.CUBE_TINTS : Faces.PLANE_TINTS;
        ItemDisplay d = w.spawn(displayLocation(w, e), ItemDisplay.class, disp -> {
            disp.setItemStack(displayItem(e, new int[n]));
            disp.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            disp.setTransformation(transformation(e));
            disp.setBrightness(new Display.Brightness(st.brightness, st.brightness));
            disp.setPersistent(true);
            disp.setViewRange(2.0f);
            writeData(disp, e);
        });
        spawnHitbox(d, e);
        refreshAround(w, e.bx(), e.by(), e.bz());
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
        d.teleport(displayLocation(d.getWorld(), e));
        d.setTransformation(transformation(e));
        Interaction i = hitbox(d);
        if (i != null) {
            i.remove();
        }
        spawnHitbox(d, e);
        refreshAround(d.getWorld(), e.bx(), e.by(), e.bz());
        plugin.triggers().index(d);
    }

    public void remove(ItemDisplay d) {
        Interaction i = hitbox(d);
        if (i != null) {
            i.remove();
        }
        EffectData e = data(d);
        World w = d.getWorld();
        plugin.triggers().unindex(d);
        d.remove();
        refreshAround(w, e.bx(), e.by(), e.bz());
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
        EffectData e = new EffectData(st.shape(), st.image(), st.color(), face, b.getX(), b.getY(), b.getZ(),
            s().rimDefault);
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
        return verify(entities, false);
    }

    public int verify(Collection<? extends Entity> entities, boolean refreshItems) {
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
                if (refreshItems) {
                    String stored = d.getPersistentDataContainer().get(keys.image, PersistentDataType.STRING);
                    EffectData e = data(d);
                    if (stored != null && !stored.equals(e.image())) {
                        d.getPersistentDataContainer().set(keys.image, PersistentDataType.STRING, e.image());
                        fixed++;
                    }
                    refresh(d);
                    plugin.triggers().index(d);
                }
            }
        }
        return fixed;
    }

    public int verifyAll() {
        return verifyAll(false);
    }

    public int verifyAll(boolean refreshItems) {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            List<Entity> list = new ArrayList<>();
            list.addAll(w.getEntitiesByClass(Interaction.class));
            list.addAll(w.getEntitiesByClass(ItemDisplay.class));
            n += verify(list, refreshItems);
        }
        return n;
    }
}
