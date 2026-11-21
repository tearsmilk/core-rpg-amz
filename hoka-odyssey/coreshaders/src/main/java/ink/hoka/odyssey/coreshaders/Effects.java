package ink.hoka.odyssey.coreshaders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.util.RGBLike;
import net.minestom.server.MinecraftServer;
import net.minestom.server.color.Color;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.display.ItemDisplayMeta;
import net.minestom.server.entity.metadata.other.InteractionMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.CustomModelData;

/**
 * Эффекты на всех картах: модель (EffectWorld), сущности-виды (ItemDisplay с моделью из пака + Interaction для кликов),
 * хранение. Только из потока сервера, кроме чтения/записи файлов (scheduler().async).
 */
final class Effects {
    enum PlaceResult {
        OK,
        NOT_AIR,
        NOT_FREE,
        BLOCKED,
        NO_SUPPORT
    }

    private final CoreShadersModule module;
    private final Map<Instance, EffectWorld> worlds = new HashMap<>();
    private final Set<Instance> loading = new HashSet<>();
    private final Map<Entity, Effect> owners = new HashMap<>();

    Effects(CoreShadersModule module) {
        this.module = module;
    }

    private Settings s() {
        return module.settings();
    }

    // ───────────────────────────── карты ─────────────────────────────

    Path file(String map) {
        return module.dataFolder().resolve("effects").resolve(map + ".json");
    }

    EffectWorld world(Instance i) {
        return i == null ? null : worlds.get(i);
    }

    List<EffectWorld> worlds() {
        return List.copyOf(worlds.values());
    }

    /** Карта загружена ядром: прочитать её эффекты (в async) и показать в загруженных чанках. */
    void attach(Instance instance) {
        if (worlds.containsKey(instance) || !loading.add(instance)) {
            return;
        }
        Optional<String> name = module.server().mapName(instance);
        if (name.isEmpty()) {
            loading.remove(instance);
            return;
        }
        String map = name.get();
        Path f = file(map);
        Settings st = s();
        module.scheduler().async(() -> {
            Storage.Loaded loaded = null;
            String error = null;
            try {
                if (Files.isRegularFile(f)) {
                    loaded = Storage.decode(Files.readString(f, StandardCharsets.UTF_8), st);
                }
            } catch (IOException | RuntimeException e) {
                error = e.toString();
            }
            Storage.Loaded l = loaded;
            String err = error;
            module.scheduler().run(() -> attached(instance, map, l, err));
        });
    }

    private void attached(Instance instance, String map, Storage.Loaded l, String error) {
        loading.remove(instance);
        if (!instance.isRegistered()) {
            return;
        }
        EffectWorld w = new EffectWorld(map, instance);
        if (error != null) {
            module.logger().error("эффекты карты {} не прочитаны ({}) — файл не трогаю, новые эффекты не сохранятся до "
                + "исправления: {}", map, error, file(map));
            w.readOnly = true;
        }
        if (l != null) {
            for (String e : l.errors()) {
                module.logger().warn("{}: {}", file(map).getFileName(), e);
            }
            for (Effect e : l.effects()) {
                w.add(e);
            }
        }
        w.dirty = false;
        worlds.put(instance, w);
        int shown = 0;
        for (Effect e : w.all()) {
            if (chunkLoaded(instance, e)) {
                spawnEntities(w, e);
                shown++;
            }
        }
        module.triggers().indexWorld(w);
        if (w.size() > 0) {
            module.logger().info("карта {}: эффектов {}, показано {}", map, w.size(), shown);
        }
    }

    /** Карта выгружается: сохранить, забыть. Сущности уходят вместе с картой. */
    void detach(Instance instance) {
        loading.remove(instance);
        EffectWorld w = worlds.remove(instance);
        if (w == null) {
            return;
        }
        save(w);
        for (Effect e : w.all()) {
            forget(e);
        }
        module.triggers().unindexWorld(w);
    }

    private static boolean chunkLoaded(Instance i, Effect e) {
        return i.isChunkLoaded(e.data.bx() >> 4, e.data.bz() >> 4);
    }

    void chunkLoaded(Instance instance, int cx, int cz) {
        EffectWorld w = worlds.get(instance);
        if (w == null) {
            return;
        }
        for (Effect e : w.inChunk(cx, cz)) {
            if (e.display == null || e.display.isRemoved()) {
                spawnEntities(w, e);
            }
        }
    }

    void chunkUnloaded(Instance instance, int cx, int cz) {
        EffectWorld w = worlds.get(instance);
        if (w == null) {
            return;
        }
        for (Effect e : w.inChunk(cx, cz)) {
            removeEntities(e);
        }
    }

    // ───────────────────────────── хранение ─────────────────────────────

    /** Сохранить карту, если менялась. Снимок — здесь (в тике), запись — в async (или сразу, если async нельзя). */
    void save(EffectWorld w) {
        if (!w.dirty || w.readOnly) {
            return;
        }
        w.dirty = false;
        String json = Storage.encode(w.all());
        Path f = file(w.map);
        try {
            module.scheduler().async(() -> writeQuietly(f, json));
        } catch (IllegalStateException off) {
            writeQuietly(f, json);
        }
    }

    void saveAll() {
        for (EffectWorld w : worlds.values()) {
            save(w);
        }
    }

    /** При выключении модуля: записать сразу, без async (потоки модуля уже останавливаются). */
    void saveAllNow() {
        for (EffectWorld w : worlds.values()) {
            if (w.dirty && !w.readOnly) {
                w.dirty = false;
                writeQuietly(file(w.map), Storage.encode(w.all()));
            }
        }
    }

    private synchronized void writeQuietly(Path f, String json) {
        try {
            Storage.write(f, json);
        } catch (IOException e) {
            module.logger().error("не записать {}: {}", f, e.toString());
        }
    }

    /** Убрать все сущности (onDisable). Модель остаётся на диске. */
    void shutdown() {
        for (EffectWorld w : worlds.values()) {
            for (Effect e : w.all()) {
                removeEntities(e);
            }
        }
        worlds.clear();
        owners.clear();
        loading.clear();
    }

    // ───────────────────────────── сущности ─────────────────────────────

    Effect effectOf(Entity en) {
        return en == null ? null : owners.get(en);
    }

    EffectWorld worldOf(Effect e) {
        for (EffectWorld w : worlds.values()) {
            if (w.get(e.id) == e) {
                return w;
            }
        }
        return null;
    }

    static ItemStack displayItem(EffectData e, int[] masks) {
        List<RGBLike> colors = new ArrayList<>(masks.length);
        for (int m : masks) {
            colors.add(new Color(Faces.encode(e.color() & 0xFFFFFF, m)));
        }
        return ItemStack.builder(Material.PAPER)
            .set(DataComponents.ITEM_MODEL, PackFiles.NAMESPACE + ":" + e.modelKey())
            .set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(), colors))
            .build();
    }

    private Pos displayPos(EffectData e) {
        double[] c = Geometry.center(e.shape(), e.bx(), e.by(), e.bz(), e.face(), s().planeOffset);
        return new Pos(c[0], c[1], c[2], 0f, 0f);
    }

    private static float[] rotation(EffectData e) {
        return e.shape() == Shape.CUBE ? new float[] {0f, 0f, 0f, 1f} : Geometry.planeRotation(e.face());
    }

    private void spawnEntities(EffectWorld w, Effect e) {
        removeEntities(e);
        EffectData d = e.data;
        Entity disp = new Entity(EntityType.ITEM_DISPLAY);
        disp.setNoGravity(true);
        disp.setHasPhysics(false);
        int light = s().brightness;
        disp.editEntityMeta(ItemDisplayMeta.class, m -> {
            m.setItemStack(displayItem(d, w.masks(d)));
            m.setDisplayContext(ItemDisplayMeta.DisplayContext.NONE);
            m.setLeftRotation(rotation(d));
            m.setScale(new Vec(1, 1, 1));
            m.setBrightness(light, light);
            m.setViewRange(2.0f);
        });
        double[] h = Geometry.hitbox(d.shape(), d.bx(), d.by(), d.bz(), d.face(), s().planeOffset);
        Entity hit = new Entity(EntityType.INTERACTION);
        hit.setNoGravity(true);
        hit.setHasPhysics(false);
        hit.setBoundingBox(h[3], h[4], h[3]);
        hit.editEntityMeta(InteractionMeta.class, m -> {
            m.setWidth((float) h[3]);
            m.setHeight((float) h[4]);
            m.setResponse(true);
        });
        e.display = disp;
        e.hitbox = hit;
        owners.put(disp, e);
        owners.put(hit, e);
        disp.setInstance(w.instance, displayPos(d));
        hit.setInstance(w.instance, new Pos(h[0], h[1], h[2]));
    }

    private void forget(Effect e) {
        if (e.display != null) {
            owners.remove(e.display);
        }
        if (e.hitbox != null) {
            owners.remove(e.hitbox);
        }
        e.display = null;
        e.hitbox = null;
    }

    private void removeEntities(Effect e) {
        Entity d = e.display;
        Entity h = e.hitbox;
        forget(e);
        if (d != null && !d.isRemoved()) {
            d.remove();
        }
        if (h != null && !h.isRemoved()) {
            h.remove();
        }
    }

    /** Обновить предмет (цвет и флаги обода) у эффекта, если изменился. */
    private void applyItem(EffectWorld w, Effect e) {
        Entity d = e.display;
        if (d == null || d.isRemoved()) {
            return;
        }
        ItemStack item = displayItem(e.data, w.masks(e.data));
        ItemDisplayMeta m = (ItemDisplayMeta) d.getEntityMeta();
        if (!item.equals(m.getItemStack())) {
            m.setItemStack(item);
        }
    }

    /** Пересчитать обод у эффектов в блоке и вокруг. */
    void refreshAround(EffectWorld w, int x, int y, int z) {
        for (Effect e : w.near(x + 0.5, y + 0.5, z + 0.5, 1.8)) {
            EffectData d = e.data;
            if (Math.abs(d.bx() - x) <= 1 && Math.abs(d.by() - y) <= 1 && Math.abs(d.bz() - z) <= 1) {
                applyItem(w, e);
            }
        }
    }

    /** Раз в 30 с: вернуть сущности, которые кто-то убрал (например, чужой модуль), пока чанк загружен. */
    int verify() {
        int fixed = 0;
        for (EffectWorld w : worlds.values()) {
            for (Effect e : w.all()) {
                boolean gone = e.display == null || e.display.isRemoved() || e.hitbox == null || e.hitbox.isRemoved();
                if (gone && chunkLoaded(w.instance, e)) {
                    spawnEntities(w, e);
                    fixed++;
                } else if (gone) {
                    removeEntities(e);
                }
            }
        }
        return fixed;
    }

    // ───────────────────────────── изменения ─────────────────────────────

    PlaceResult check(EffectWorld w, EffectData e, Effect ignore) {
        for (Effect other : w.at(e.bx(), e.by(), e.bz())) {
            if (other == ignore) {
                continue;
            }
            EffectData o = other.data;
            if (e.shape() == Shape.CUBE || o.shape() == Shape.CUBE || o.face() == e.face()) {
                return PlaceResult.NOT_FREE;
            }
        }
        Block b = w.instance.getBlock(e.bx(), e.by(), e.bz());
        if (e.shape() == Shape.CUBE) {
            return b.isAir() ? PlaceResult.OK : PlaceResult.NOT_AIR;
        }
        if (b.registry().occludes() && b.isSolid()) {
            return PlaceResult.BLOCKED;
        }
        Block support = w.instance.getBlock(e.bx() - e.face().dx, e.by() - e.face().dy, e.bz() - e.face().dz);
        return support.isAir() ? PlaceResult.NO_SUPPORT : PlaceResult.OK;
    }

    PlaceResult place(EffectWorld w, int cx, int cy, int cz, Dir face, Wand.State st) {
        EffectData e = new EffectData(st.shape(), st.image(), st.color(), face, cx + face.dx, cy + face.dy, cz + face.dz,
            s().rimDefault);
        PlaceResult r = check(w, e, null);
        if (r == PlaceResult.OK) {
            add(w, new Effect(UUID.randomUUID(), e, null));
        }
        return r;
    }

    void add(EffectWorld w, Effect e) {
        w.add(e);
        if (chunkLoaded(w.instance, e)) {
            spawnEntities(w, e);
        }
        refreshAround(w, e.data.bx(), e.data.by(), e.data.bz());
        module.triggers().index(w, e);
    }

    void update(EffectWorld w, Effect e, EffectData n) {
        EffectData old = e.data;
        w.change(e, n);
        boolean moved = old.shape() != n.shape() || old.face() != n.face() || old.bx() != n.bx() || old.by() != n.by()
            || old.bz() != n.bz();
        if (moved || e.display == null || e.display.isRemoved()) {
            if (chunkLoaded(w.instance, e)) {
                spawnEntities(w, e);
            } else {
                removeEntities(e);
            }
            refreshAround(w, old.bx(), old.by(), old.bz());
        }
        refreshAround(w, n.bx(), n.by(), n.bz());
        module.triggers().index(w, e);
    }

    void setActions(EffectWorld w, Effect e, Actions.Data a) {
        e.actions = a.actions().isEmpty() && a.permission() == null && a.message() == null ? null : a;
        w.dirty = true;
        module.triggers().index(w, e);
    }

    Actions.Data actions(Effect e) {
        if (e.actions != null) {
            return e.actions;
        }
        return new Actions.Data(List.of(), s().defaultTrigger, s().defaultCooldown, null, null, e.id.toString());
    }

    void remove(EffectWorld w, Effect e) {
        removeEntities(e);
        w.remove(e);
        module.triggers().unindex(e);
        refreshAround(w, e.data.bx(), e.data.by(), e.data.bz());
    }

    /** Соседние (по грани) эффекты, начиная с этого. */
    List<Effect> group(EffectWorld w, Effect start, int max) {
        List<Effect> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        ArrayDeque<Effect> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start.id);
        while (!queue.isEmpty() && out.size() < max) {
            Effect e = queue.poll();
            out.add(e);
            EffectData d = e.data;
            for (Dir dir : Dir.values()) {
                for (Effect o : w.at(d.bx() + dir.dx, d.by() + dir.dy, d.bz() + dir.dz)) {
                    if (seen.add(o.id)) {
                        queue.add(o);
                    }
                }
            }
            for (Effect o : w.at(d.bx(), d.by(), d.bz())) {
                if (seen.add(o.id)) {
                    queue.add(o);
                }
            }
        }
        return out;
    }

    /** Эффект под прицелом игрока (луч до max-distance, не сквозь блоки). */
    Effect target(Player p) {
        EffectWorld w = world(p.getInstance());
        if (w == null) {
            return null;
        }
        Pos pos = p.getPosition();
        double[] o = {pos.x(), pos.y() + p.getEyeHeight(), pos.z()};
        Vec dir = pos.direction();
        double[] dv = {dir.x(), dir.y(), dir.z()};
        double max = s().maxDistance;
        Effect best = null;
        double bestT = Double.MAX_VALUE;
        for (Effect e : w.near(o[0], o[1], o[2], max + 1.5)) {
            EffectData d = e.data;
            double t = Geometry.rayBox(o, dv, Geometry.box(d.shape(), d.bx(), d.by(), d.bz(), d.face()), max);
            if (t >= 0 && t < bestT) {
                bestT = t;
                best = e;
            }
        }
        if (best == null) {
            return null;
        }
        double wall = Ray.firstSolid(w.instance, o, dv, max);
        if (wall >= 0 && wall + 0.08 < bestT) {
            return null;
        }
        return best;
    }

    int count() {
        int n = 0;
        for (EffectWorld w : worlds.values()) {
            n += w.size();
        }
        return n;
    }

    /** Все карты, где игрок (или все) — для list/removeall из консоли. */
    static List<Instance> instances() {
        return List.copyOf(MinecraftServer.getInstanceManager().getInstances());
    }
}
