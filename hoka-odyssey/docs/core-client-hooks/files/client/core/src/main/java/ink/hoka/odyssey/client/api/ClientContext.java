package ink.hoka.odyssey.client.api;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Всё, что ядро даёт клиентскому модулю. Всё зарегистрированное снимается при выгрузке модуля.
 * Все обратные вызовы — в клиентском потоке Minecraft.
 */
public interface ClientContext {

    /** id модуля (из client-module.json) — он же имя канала до серверного модуля. */
    String id();

    Logger logger();

    /** Папка модуля для своих файлов: {@code .minecraft/hoka_odyssey/data/<id>/}. */
    Path dataDir();

    // ───── связь с сервером (серверная сторона: ClientModsApi.send / on в модуле clientmods) ─────

    /** Отправить серверу байты от имени модуля (серверный модуль слушает тот же id). */
    void send(byte[] data);

    /** Сообщения сервера для этого модуля. */
    void onMessage(Consumer<byte[]> handler);

    // ───── файлы с сервера (мимо ресурсов игры: без пака и без перезагрузок) ─────

    /** Файл с сервера (путь как в ClientModsApi.putFile / папке module/clientmods/files). Пусто — нет или ещё качается. */
    java.util.Optional<byte[]> file(String path);

    /** Сейчас (если файл уже есть) и при каждом изменении файла; null — файл убрали. */
    void onFile(String path, Consumer<byte[]> listener);

    /**
     * Текстура из PNG-файла для рисования (GuiGraphics.blit, свои рендеры). id постоянный; поменяли файл на
     * сервере — картинка на экране обновится сама, без перезагрузки ресурсов. Пока файла нет — прозрачная.
     */
    net.minecraft.resources.Identifier texture(String path);

    // ───── крючки ─────

    /** Модель .bbmodel из файлов сервера в мире (рисует ядро, обновляется вместе с файлом). */
    ModelHandle model(String path);

    /** Каждый тик клиента (20 раз в секунду). */
    void onTick(Runnable r);

    /** Рисовать поверх HUD каждый кадр. */
    void onHud(HudLayer layer);

    /**
     * Движение мыши (в градусах поворота, уже с чувствительностью игрока). Вернуть true — движение забрано модулем:
     * игрок не повернётся (так делается свой прицел мыши без задержки сервера).
     */
    void onMouse(MouseHook hook);

    /** Камера: после расчёта игрой — поменять курс, наклон, крен, положение. */
    void onCamera(CameraHook hook);

    // ───── мир и рендер (с версии ядра 0.10) ─────
    // Модуль, которому нужно работать и на старом ядре, проверяет наличие метода рефлексией:
    // ClientContext.class.getMethod("onSky", SkyHook.class) — NoSuchMethodException значит «ядро старое».

    /**
     * Своё небо. Крючок вызывается в отдельном проходе кадра ПЕРЕД ванильным небом (в основной буфер кадра, до
     * террейна). Вернуть true — ванильное небо (диск, солнце, луна, звёзды, End) в этом кадре не рисовать.
     * Вызывается и тогда, когда ваниль неба не рисует ({@link SkyFrame#vanillaSkip} != NONE) — модуль решает сам.
     * При включённом шейдерпаке Iris крючок не вызывается (небо рисует шейдерпак).
     */
    void onSky(SkyHook hook);

    /**
     * Туман кадра: цвет (он же цвет очистки кадра) и расстояния — после расчёта игрой, перед записью в шейдеры.
     * В космосе: чёрный цвет и далёкие расстояния.
     */
    void onFog(FogHook hook);

    /** Спрятать облака, пока supplier возвращает true (опрашивается каждый кадр). */
    void onClouds(java.util.function.BooleanSupplier hide);

    /** Спрятать дождь и снег (частицы-полосы погоды; граница мира остаётся), пока supplier возвращает true. */
    void onWeather(java.util.function.BooleanSupplier hide);

    /**
     * Этап рендера мира (обёртка над Fabric WorldRenderEvents: ядро регистрирует события один раз и снимает
     * крючки модуля при выгрузке — слушатели Fabric не утекают). Для START_MAIN контекст —
     * {@code WorldTerrainRenderContext}, для остальных — {@code WorldRenderContext} (можно привести тип).
     */
    void onWorldPass(WorldStage stage, WorldPassHook hook);

    /**
     * Свой RenderPipeline со своими шейдерами (исходники — строки от модуля, например из {@link #file}).
     * {@code #moj_import <minecraft:fog.glsl>} и другие include из ресурсов игры работают. Перед каждым
     * использованием вызывать {@link ModulePipeline#prepare()}: после F3+T и смены ресурспака игра сбрасывает
     * кэш шейдеров — prepare соберёт заново. При выгрузке модуля pipeline становится недействительным.
     * Кэш шейдеров игры — по (id, тип, defines): новая версия исходника — новый id шейдера.
     */
    ModulePipeline pipeline(com.mojang.blaze3d.pipeline.RenderPipeline pipeline, com.mojang.blaze3d.shaders.ShaderSource source);

    /** Закрыть ресурс при выгрузке модуля (GpuBuffer, GpuTexture, GpuTextureView, что угодно AutoCloseable). */
    <T extends AutoCloseable> T track(T resource);

    /**
     * Мир клиента: id измерения ({@code level.dimension().identifier()}; у Minestom свой у каждого мира) — сразу
     * при регистрации, если игрок в мире, и при каждой смене мира (в том числе тот же id, если мир пересоздан);
     * null — мира нет (выход, экран загрузки).
     */
    void onWorld(Consumer<net.minecraft.resources.Identifier> handler);

    /**
     * Поворот тела игрока при рисовании (другие игроки, сам игрок в F5, манекены): вызывается в начале
     * {@code AvatarRenderer.setupRotations}. Вернуть true — ванильный поворот не делать (тогда модуль сам
     * поворачивает {@code pose}, включая курс тела: ваниль делает {@code rotateY(180 - bodyRot)}).
     * Вернуть false — модуль только добавил свои преобразования, ваниль применит свои после них.
     */
    void onAvatarRotations(AvatarRotHook hook);

    /** Состояние для рисования игрока (поза, наклон, руки…) — после расчёта игрой, можно менять поля. */
    void onAvatarState(AvatarStateHook hook);

    /**
     * Принудительная поза своего игрока (хитбокс, высота глаз, вид в F5): например {@code Pose.SWIMMING} —
     * лёжа, хитбокс 0,6. null — снять. Если позу задали несколько модулей, действует последняя заданная.
     * Сервер позу игрока не видит — для других игроков позу ставит серверный модуль.
     */
    void forceLocalPose(net.minecraft.world.entity.@org.jspecify.annotations.Nullable Pose pose);

    /** Ввод с клавиатуры своего игрока — после чтения клавиш игрой, до того как его увидит движение. */
    void onInput(InputHook hook);

    /** Перед расчётом движения своего игрока (LivingEntity.travel), в тот же тик: тяга, гравитация. */
    void onLocalTravel(TravelHook hook);

    interface HudLayer { void render(GuiGraphics g, DeltaTracker delta); }

    interface MouseHook { boolean onMouse(double yawDeg, double pitchDeg); }

    interface CameraHook { void apply(CameraState cam, float partialTick); }

    /** true — ванильное небо в этом кадре не рисовать. */
    interface SkyHook { boolean render(SkyFrame frame); }

    interface FogHook { void apply(FogState fog, net.minecraft.client.Camera camera, float partialTick); }

    interface WorldPassHook { void render(net.fabricmc.fabric.api.client.rendering.v1.world.AbstractWorldRenderContext context); }

    /** true — ванильный поворот тела не делать. entityId — id сущности на клиенте ({@code state.id}). */
    interface AvatarRotHook {
        boolean apply(int entityId, net.minecraft.client.renderer.entity.state.AvatarRenderState state,
                      com.mojang.blaze3d.vertex.PoseStack pose, float bodyRot, float scale);
    }

    interface AvatarStateHook {
        void apply(net.minecraft.world.entity.Avatar entity, net.minecraft.client.renderer.entity.state.AvatarRenderState state, float partialTick);
    }

    interface InputHook { void apply(InputState input); }

    interface TravelHook { void before(net.minecraft.client.player.LocalPlayer player); }
}
