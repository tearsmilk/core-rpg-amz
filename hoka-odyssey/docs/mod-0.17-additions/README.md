# Что добавить в следующую версию мода (после 0.17.0)

Здесь только то, что нужно для **живой невесомости и свободного полёта** (клиентский модуль `spaceflight`).
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

---

Всё остальное (гравитация, тела, античит) — на сервере, в модуле `gravity`, мод для этого менять не нужно.
