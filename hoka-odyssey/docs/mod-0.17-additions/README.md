> **Устарело (мод 0.48.0).** Почти всё отсюда уже есть в моде: `CameraState.rotation` (раздел 1), `onInput` +
> `setMove`, `onLocalTravel`, поворот тел — `onEntityPose`/`onEntityTilt` (вместо `onAvatarRotations`). Модуль
> `spaceflight` перешёл на них. Что ещё нужно добавить — только сдвиг модели в `EntityPose`:
> [`docs/mod-additions/README.md`](../mod-additions/README.md). Ниже — исторический документ, не для реализации.

# Что добавить в следующую версию мода (после 0.17.0)

Здесь только то, что нужно для **живой невесомости и свободного полёта** (клиентский модуль `spaceflight`),
включая поворот тел игроков (раздел 4).
Ничего из того, что уже есть в 0.17.0, не убирать и не менять — добавки только расширяют API.

**Модуль `spaceflight` уже работает на 0.17.0 без этих добавок.** Они убирают обходные пути и делают полёт
точнее; пока их нет, модуль работает как есть.

Исходников 0.17.0 в открытом доступе нет, поэтому список ниже составлен по jar мода с сервера
(`module/clientmods/core/hoka-odyssey-0.17.0+1.21.11.jar`, сигнатуры через `javap`).

## Что уже есть в 0.17.0 (сохранить)

`ClientContext`:
- `id`, `logger`, `dataDir`;
- `send(byte[])`, `onMessage(Consumer<byte[]>)`;
- `file`, `onFile`, `texture`, `model`;
- `onTick(Runnable)`, `onWorld(WorldHook)` (`WorldFrame`: `pose`, `buffers`, `camera`, `partialTick`, `visible`, `submits`, `cameraState`);
- `onHud(HudLayer)`, `onMouse(MouseHook)`, `onCamera(CameraHook)`;
- `hideHand(boolean)`, `onCollision(CollisionHook)` (`Body`: `toWorld`, `shapes`).

`CameraState`: `x`, `y`, `z`, `yaw`, `pitch`, `roll`, `detached`.

Миксины: `CameraMixin`, `MouseHandlerMixin`, `GameRendererMixin`, `EntityCollisionMixin`, плюс блоки, предметы и звуки
(`BlockModelShaperMixin`, `ItemBlockRenderTypesMixin`, `LeavesBlockMixin`, `ItemModelResolverMixin`,
`SoundManagerMixin`, `SoundBufferLibraryMixin`, `BlockStateSoundMixin`, `BlockItemMixin`).

## 1. Поворот камеры кватернионом (главное)

**Зачем.** Сейчас модуль отдаёт ориентацию тремя углами (yaw/pitch/roll). Вблизи «прямо вверх / прямо вниз»
(pitch ±90°) три угла вырождаются: модуль обходит это переворотом yaw и roll на 180°, но в самой точке
возможен мелкий рывок. Если камера примет готовый кватернион, крен на 360° и петли будут без особых точек.

**API** — одно новое поле в `CameraState`:

```java
/** Если задано модулем — полный поворот камеры; yaw/pitch/roll тогда не используются для поворота. */
public org.joml.Quaternionf rotation;
```

**CameraMixin** — в конце `setup`, после `Hooks.camera(s, partialTick)`:

```java
if (s.rotation != null) {
    Hooks.rotation = new Quaternionf(s.rotation);   // запомнить на этот кадр
    setRotation(s.yaw, s.pitch);                    // yRot/xRot для звука и прочего — как раньше
} else {
    Hooks.rotation = null;
    Hooks.roll = (float) Math.toRadians(s.roll);
    setRotation(s.yaw, s.pitch);
}
setPosition(s.x, s.y, s.z);
```

и в `setRotation` после того, как игра посчитала свой кватернион:

```java
@Inject(method = "setRotation", at = @At("TAIL"))
private void hokaOdyssey$rotation(float yRot, float xRot, CallbackInfo ci) {
    if (Hooks.rotation == null) return;
    this.rotation.set(Hooks.rotation);                       // @Shadow @Final Quaternionf rotation
    this.forwards.set(0, 0, -1).rotate(this.rotation);       // @Shadow @Final Vector3f forwards, up, left
    this.up.set(0, 1, 0).rotate(this.rotation);
    this.left.set(-1, 0, 0).rotate(this.rotation);
}
```

Имена полей `Camera` (Mojang-маппинги 1.21.11): `rotation`, `forwards`, `up`, `left` — проверить при сборке.
Когда хуков камеры нет, сбрасывать `Hooks.rotation = null` (как сейчас сбрасывается `roll`).

**Модуль `spaceflight`** проверит поле рефлексией: есть `CameraState.rotation` — отдаёт кватернион, нет — углы, как сейчас.

## 2. Крючок перед движением своего игрока (по желанию)

**Зачем.** Модуль считает инерцию и притяжение в `onTick`, то есть после того, как игра уже сдвинула игрока
в этом тике. Получается задержка на один тик (50 мс) между силой и движением. Если вызывать модуль прямо перед
`LivingEntity.travel` своего игрока, сила применяется в тот же тик.

**API:**

```java
void onLocalTravel(TravelHook hook);
interface TravelHook { void before(net.minecraft.client.player.LocalPlayer player); }
```

**Миксин** на `LivingEntity.travel(Vec3)` с `@At("HEAD")`, только когда `(Object) this instanceof LocalPlayer`;
вызывать хуки владельцев, снимать их при выгрузке модуля, как остальные крючки.

## 3. Ввод движения (по желанию)

**Зачем.** В вакууме модуль сам читает клавиши через GLFW и каждый тик «отпускает» ванильные ходьбу, прыжок,
присед и бег, чтобы игра не шла и не приседала. Крючок ввода делает это чище и уважает переназначенные клавиши.

**API:**

```java
void onInput(InputHook hook);
interface InputHook { void apply(net.minecraft.client.player.ClientInput input); }   // можно менять keyPresses и moveVector
```

**Миксин** на `KeyboardInput.tick()` с `@At("TAIL")`.

## 4. Поворот тела других игроков

**Зачем.** В невесомости игрок крутится как угодно: лежит, висит вниз головой, стоит на стене. Ваниль рисует
тело только по курсу (yaw), наклон головы ±90° и несколько поз (плавание, полёт на элитрах, сон). Модуль
`spaceflight` уже шлёт серверу свой поворот (кватернион взгляда и «лёжа/стоя»), а сервер (модуль `gravity`)
раздаёт его другим игрокам с модулем (S2C op 3/op 4). Не хватает одного — **крючка, в котором модуль сам
поворачивает модель игрока**. Модуль ищет его рефлексией: как только он появится в моде, повёрнутые тела
заработают без пересборки модуля. Пока крючка нет, сервер показывает лежащих позой полёта на элитрах (тело вдоль
взгляда) — грубее, но видно, что игрок лежит.

**API** — в `ClientContext`:

```java
/**
 * Поворот тела игрока при рисовании (другие игроки, сам игрок в F5, манекены): вызывается в начале
 * AvatarRenderer.setupRotations. Вернуть true — ванильный поворот не делать (тогда модуль сам поворачивает
 * pose, включая курс тела: ваниль делает rotateY(180 - bodyRot)). Вернуть false — модуль только добавил свои
 * преобразования, ваниль применит свои после них.
 */
void onAvatarRotations(AvatarRotHook hook);

/** true — ванильный поворот тела не делать. entityId — id сущности на клиенте (state.id). */
interface AvatarRotHook {
    boolean apply(int entityId, net.minecraft.client.renderer.entity.state.AvatarRenderState state,
                  com.mojang.blaze3d.vertex.PoseStack pose, float bodyRot, float scale);
}
```

Имена важны: модуль ищет метод `onAvatarRotations` с одним параметром-интерфейсом и вызывает у него `apply`
с ровно такими пятью параметрами.

**Hooks** (как остальные крючки — с владельцем, снимаются при выгрузке модуля):

```java
static final List<Owned<ClientContext.AvatarRotHook>> AVATAR_ROT = new ArrayList<>();
// removeOwner: AVATAR_ROT.removeIf(o -> o.owner().equals(owner));   clear: AVATAR_ROT.clear();

public static boolean hasAvatarRot() { return !AVATAR_ROT.isEmpty(); }

/** true — ванильный поворот тела не делать. */
public static boolean avatarRotations(AvatarRenderState s, PoseStack pose, float bodyRot, float scale) {
    boolean cancel = false;
    for (var o : List.copyOf(AVATAR_ROT)) {
        boolean[] r = {false};
        safe(o.owner(), () -> r[0] = o.hook().apply(s.id, s, pose, bodyRot, scale));
        cancel |= r[0];
    }
    return cancel;
}
```

Контекст модуля (`ModuleManager`):

```java
@Override public void onAvatarRotations(AvatarRotHook hook) { Hooks.AVATAR_ROT.add(new Hooks.Owned<>(id, hook)); }
```

**Миксин** `AvatarRendererMixin` (добавить в `hoka_odyssey.mixins.json`, раздел `client`). Дескриптор указан явно:
у `AvatarRenderer` есть мосты (bridge) с типами родителей, без дескриптора миксин может встать не туда. Дескриптор
проверен на 1.21.11 (Mojang-маппинги) в старом патче ядра:

```java
package ink.hoka.odyssey.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import ink.hoka.odyssey.client.Hooks;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Поворот тел игроков для модулей: крен, «вверх ногами», «лёжа вдоль взгляда» — то, чего ваниль не умеет. */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

    @Inject(method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
            at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$rotations(AvatarRenderState state, PoseStack pose, float bodyRot, float scale, CallbackInfo ci) {
        if (Hooks.hasAvatarRot() && Hooks.avatarRotations(state, pose, bodyRot, scale)) ci.cancel();
    }
}
```

`setupRotations` вызывается в `LivingEntityRenderer.submit` до `setupAnim` модели, поэтому модуль может в том же
крючке поправить и `state.xRot`/`state.yRot` (голова вместе с телом) — модуль `spaceflight` так и делает.

**Что делает модуль с крючком.** Для игроков, о которых сервер прислал поворот (и для себя в F5, пока в
невесомости), поворачивает модель вокруг середины тела: стоя — верх тела вдоль верха камеры, лёжа — макушка по
взгляду; между пакетами (~10 в секунду) поворот доводится плавно (slerp). Остальных игроков не трогает (возвращает
false — ваниль как есть). Ещё модуль сообщает серверу «умею поворачивать тела» (C2S op 5), и сервер перестаёт
слать такому игроку запасную позу полёта.

**По желанию: поза своего игрока** (`forceLocalPose`). Клиент сам выбирает позу своего игрока каждый тик
(`Player.updatePlayerPose`), поза от сервера перетирается, а SWIMMING вне воды ваниль не даёт. Если понадобится
лёжа-хитбокс (0,6 блока) своего игрока, нужен ещё один крючок:

```java
/** Принудительная поза своего игрока (хитбокс, высота глаз, вид в F5); null — снять. Действует последняя заданная. */
void forceLocalPose(net.minecraft.world.entity.@org.jspecify.annotations.Nullable Pose pose);
```

```java
// Hooks
static final List<Owned<Pose>> POSE = new ArrayList<>();          // последний — действующий; снимать по владельцу
public static Pose forcedPose() { return POSE.isEmpty() ? null : POSE.getLast().hook(); }
static void forcePose(String owner, Pose pose) {
    POSE.removeIf(o -> o.owner().equals(owner));
    if (pose != null) POSE.add(new Owned<>(owner, pose));
}
// ModuleManager
@Override public void forceLocalPose(Pose pose) { Hooks.forcePose(id, pose); }
```

```java
@Mixin(net.minecraft.world.entity.player.Player.class)
public abstract class PlayerPoseMixin {
    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void hokaOdyssey$pose(CallbackInfo ci) {
        if (!((Object) this instanceof net.minecraft.client.player.LocalPlayer)) return;
        Pose forced = Hooks.forcedPose();
        if (forced == null) return;
        ((Entity) (Object) this).setPose(forced);
        ci.cancel();
    }
}
```

Модуль `spaceflight` этот крючок пока не использует (в невесомости рамка игрока не поворачивается вместе с телом,
и низкая рамка мешала бы ходить по стенам) — это задел на будущее.

---

Всё остальное (гравитация, тела, античит) — на сервере, в модуле `gravity`, мод для этого менять не нужно.
