package ink.hoka.odyssey.client.guns;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Оружие за спиной: оружие в быстрой панели, но не в руке, висит у игрока на спине наискось, как автомат на ремне.
 * Рисуется слоем модели игрока (как плащ и элитры): слой ставится в рендеры игроков игры (у каждого типа скина —
 * свой рендер, после перезагрузки ресурсов — новые), а значит, едет вместе с телом — поворот, присед, плавание,
 * элитры, наклон и сдвиг модели от ядра (onEntityTilt / onEntityPose: невесомость, стены, корабли).
 * <ul>
 *   <li>чужие — что за спиной, говорит сервер (op BACK; инвентаря чужих клиент не знает);</li>
 *   <li>свой (F5) — по своей быстрой панели сразу, без ожидания сервера; от первого лица не рисуется;</li>
 *   <li>вид — тот же предмет hoitems, что в руке (модель с сервера рисует ядро), без перекраски и правок.</li>
 * </ul>
 */
final class BackGun {
    /** Положение на спине (части тела — пиксели модели / 16, ось y — вниз, z — от спины назад). */
    private static final float CENTER_Y = 6f / 16f;
    private static final float BACK = 2f / 16f + 0.06f;
    private static final float ARMOR = 1.1f / 16f;
    private static final float SCALE = 0.6f;                  // как в руке (display thirdperson у модели TACZ)

    private final Logger log;
    /** id сущности → id оружия за спиной (от сервера). */
    private final Map<Integer, String> remote = new HashMap<>();
    /** id оружия → id предмета hoitems (хвост CONFIG). */
    private final Map<String, String> gunItems = new HashMap<>();
    private final Map<String, ItemStack> stacks = new HashMap<>();
    /** Свои слои в рендерах игры (рендер → слой). */
    private final Map<LivingEntityRenderer<?, ?, ?>, Layer> installed = new WeakHashMap<>();
    private final Map<Integer, ItemStackRenderState> states = new HashMap<>();
    private Field layersField;
    private boolean failed;
    private boolean warned;
    /** Подстройка положения: угол°, x, y, z, масштаб, поворот предмета x°, y°, z° (ключ back_gun_tune). */
    final float[] tune;

    BackGun(Logger log, float[] tune) {
        this.log = log;
        this.tune = tune;
    }

    void setItems(Map<String, String> items) {
        gunItems.clear();
        gunItems.putAll(items);
        stacks.clear();
    }

    void set(int entityId, String gun) {
        if (gun == null || gun.isEmpty()) remote.remove(entityId);
        else remote.put(entityId, gun);
    }

    /** Раз в тик: слой — во все рендеры игроков в мире; забыть ушедших. */
    void tick(Minecraft mc) {
        if (mc.level == null) {
            remote.clear();
            states.clear();
            return;
        }
        for (AbstractClientPlayer p : mc.level.players()) {
            EntityRenderer<?, ?> r = mc.getEntityRenderDispatcher().getPlayerRenderer(p);
            if (r instanceof LivingEntityRenderer<?, ?, ?> lr) install(lr);
        }
        for (Iterator<Integer> it = remote.keySet().iterator(); it.hasNext(); ) {
            if (mc.level.getEntity(it.next()) == null) it.remove();
        }
        states.keySet().removeIf(id -> mc.level.getEntity(id) == null);
    }

    /** Снять свои слои (выгрузка модуля). */
    void close() {
        for (Map.Entry<LivingEntityRenderer<?, ?, ?>, Layer> e : installed.entrySet()) {
            List<?> l = layers(e.getKey());
            if (l != null) l.remove(e.getValue());
        }
        installed.clear();
        remote.clear();
        states.clear();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void install(LivingEntityRenderer<?, ?, ?> r) {
        if (failed) return;
        Layer have = installed.get(r);
        List l = layers(r);
        if (l == null) return;
        if (have != null && l.contains(have)) return;
        Layer layer = new Layer((RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) r);
        l.add(layer);
        installed.put(r, layer);
    }

    /** Список слоёв рендера (поле layers — по типу: имя поля в игре другое, чем в исходниках). */
    private List<?> layers(LivingEntityRenderer<?, ?, ?> r) {
        try {
            if (layersField == null) {
                for (Field f : LivingEntityRenderer.class.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        layersField = f;
                        break;
                    }
                }
                if (layersField == null) throw new NoSuchFieldException("layers");
            }
            return (List<?>) layersField.get(r);
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            log.warn("guns: оружие за спиной не показать — слой модели игрока не поставлен ({})", e.toString());
            return null;
        }
    }

    // ---------------------------------------------------------------- что за спиной

    /** Своё: первое оружие быстрой панели не в выбранном слоте (как считает сервер). */
    private ItemStack own(Minecraft mc) {
        if (mc.player == null) return null;
        Inventory inv = mc.player.getInventory();
        int sel = inv.getSelectedSlot();
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (i == sel) continue;
            ItemStack s = inv.getItem(i);
            if (isGun(s)) return s;
        }
        return null;
    }

    private boolean isGun(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag t = cd.copyTag();
        String gun = t.getStringOr("hoka_gun", "");
        if (!gun.isEmpty()) return gunItems.containsKey(gun);   // пусто — старый сервер или back-gun: false
        String item = t.getStringOr("hoka_item", "");
        return !item.isEmpty() && gunItems.containsValue(item);
    }

    /** Чужое: предмет hoitems оружия (метки, по которым его рисует ядро). */
    private ItemStack stackOf(String gun) {
        String item = gunItems.get(gun);
        if (item == null || item.isEmpty()) return null;
        return stacks.computeIfAbsent(gun, g -> {
            CompoundTag t = new CompoundTag();
            t.putString("hoka_item", item);
            t.putString("hoka_gun", g);
            ItemStack s = new ItemStack(Items.IRON_HORSE_ARMOR);
            s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
            return s;
        });
    }

    // ---------------------------------------------------------------- рисование

    private void submit(Layer layer, PoseStack ps, SubmitNodeCollector out, int light, AvatarRenderState s) {
        if (s.isInvisible || s.isSpectator || s.hasPose(Pose.SLEEPING)) return;
        Minecraft mc = Minecraft.getInstance();
        boolean self = mc.player != null && s.id == mc.player.getId();
        ItemStack stack;
        if (self) {
            if (mc.options.getCameraType().isFirstPerson() && mc.getCameraEntity() == mc.player) return;
            stack = own(mc);
        } else {
            String gun = remote.get(s.id);
            stack = gun == null ? null : stackOf(gun);
        }
        if (stack == null || mc.level == null) return;
        ItemStackRenderState rs = states.computeIfAbsent(s.id, k -> new ItemStackRenderState());
        mc.getItemModelResolver().updateForTopItem(rs, stack, ItemDisplayContext.NONE, mc.level, null, s.id);
        if (rs.isEmpty()) return;
        float[] t = tune;
        boolean armor = s.chestEquipment != null && !s.chestEquipment.isEmpty();
        ps.pushPose();
        try {
            layer.getParentModel().body.translateAndRotate(ps);
            ps.translate(t[1], CENTER_Y + t[2], BACK + (armor ? ARMOR : 0) + t[3]);
            ps.mulPose(Axis.ZP.rotationDegrees(t[0]));
            ps.mulPose(Axis.XP.rotationDegrees(t[5]));
            ps.mulPose(Axis.YP.rotationDegrees(t[6]));
            ps.mulPose(Axis.ZP.rotationDegrees(t[7]));
            float k = SCALE * t[4];
            ps.scale(k, k, k);
            rs.submit(ps, out, light, OverlayTexture.NO_OVERLAY, s.outlineColor);
        } finally {
            ps.popPose();
        }
    }

    /** Слой модели игрока (как плащ): зовёт {@link #submit}. */
    private final class Layer extends RenderLayer<AvatarRenderState, PlayerModel> {
        Layer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
            super(parent);
        }

        @Override
        public void submit(PoseStack ps, SubmitNodeCollector out, int light, AvatarRenderState s, float yRot, float xRot) {
            try {
                BackGun.this.submit(this, ps, out, light, s);
            } catch (RuntimeException e) {
                if (!warned) log.warn("guns: оружие за спиной не нарисовано: {}", e.toString());
                warned = true;
            }
        }
    }
}
