package ink.hoka.odyssey.spacesky;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.color.AlphaColor;
import net.minestom.server.color.Color;
import net.minestom.server.registry.DynamicRegistry;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.registry.RegistryTag;
import net.minestom.server.utils.EaseFunction;
import net.minestom.server.world.DimensionType;
import net.minestom.server.world.attribute.EnvironmentAttribute;
import net.minestom.server.world.attribute.EnvironmentAttributeMap;
import net.minestom.server.world.timeline.Timeline;
import org.slf4j.Logger;

/**
 * Тип мира «hoka:space» (DimensionType) и его Timeline «hoka:space». Карта выбирает его ключом
 * {@code dimension: "hoka:space"} в maps/&lt;карта&gt;/map.yml (это читает ядро, см. docs/core-dimension.md).
 *
 * <p>Небо и туман такого мира — МЕТКА: тёмный цвет {@link #MARK}, которого в ванили не бывает. По ней шейдеры
 * (режим {@code everywhere: false}) узнают космический мир. Таймлайн применяется клиентом поверх атрибутов измерения
 * и биома (биом plains задаёт свой sky_color — он перебил бы атрибут измерения), поэтому МЕТКА задана и там, и там.
 * Облака, закатный веер и ванильные звёзды выключены атрибутами (альфа 0, яркость 0).
 *
 * <p>Реестры Minestom замораживаются после {@code MinecraftServer.start}: регистрировать можно только до старта
 * (модуль делает это в onLoad). При горячей загрузке после старта — только предупреждение: нужен перезапуск.
 */
final class SpaceDimension {
    static final String KEY = "hoka:space";
    /** МЕТКА — цвет неба и тумана мира hoka:space (#020205); тот же цвет — SPACESKY_MARK в spacesky:mode.glsl. */
    static final int MARK = 0x020205;

    enum Result {
        /** Тип мира и таймлайн зарегистрированы. */
        REGISTERED,
        /** Уже были (модуль перезагружен) — не тронуты. */
        ALREADY,
        /** Сервер уже запущен, реестр заморожен — нужен перезапуск. */
        FROZEN
    }

    private SpaceDimension() {
    }

    /** Атрибуты космоса: МЕТКА в небе и тумане, без облаков, веера и ванильных звёзд. */
    static Map<EnvironmentAttribute<?>, Object> spaceAttributes() {
        Map<EnvironmentAttribute<?>, Object> m = new HashMap<>();
        m.put(EnvironmentAttribute.SKY_COLOR, new Color(MARK));
        m.put(EnvironmentAttribute.FOG_COLOR, new Color(MARK));
        m.put(EnvironmentAttribute.CLOUD_COLOR, new AlphaColor(0, 0, 0, 0));
        m.put(EnvironmentAttribute.SUNRISE_SUNSET_COLOR, new AlphaColor(0, 0, 0, 0));
        m.put(EnvironmentAttribute.STAR_BRIGHTNESS, 0f);
        return m;
    }

    /** Timeline «hoka:space»: без периода, на каждый атрибут одна точка (tick 0) с Override — значение постоянно. */
    static Timeline timeline() {
        Timeline.Builder b = Timeline.builder();
        spaceAttributes().forEach((attr, value) -> addTrack(b, attr, value));
        return b.build();
    }

    @SuppressWarnings("unchecked")
    private static <T> void addTrack(Timeline.Builder b, EnvironmentAttribute<T> attr, Object value) {
        T v = (T) value;
        b.track(attr, new Timeline.Track<>(new EnvironmentAttribute.Modifier.Override<>(attr.valueCodec()),
            List.of(new Timeline.Keyframe<>(0, v)), EaseFunction.CONSTANT));
    }

    /**
     * Тип мира: всё как у overworld (высота -64..320, небесный свет, без потолка, skybox OVERWORLD, infiniburn,
     * свет спавна мобов, звуки, кровати), атрибуты неба — МЕТКА, таймлайны — только hoka:space (смены дня и ночи
     * нет: свет неба всегда дневной, космос одинаковый в любое время).
     */
    static DimensionType dimension(DimensionType overworld, RegistryKey<Timeline> timeline) {
        DimensionType base = overworld != null ? overworld : DimensionType.builder().build();
        Map<EnvironmentAttribute<?>, EnvironmentAttributeMap.Entry<?, ?>> attrs = new HashMap<>(base.attributes().entries());
        spaceAttributes().forEach((attr, value) -> attrs.put(attr, entry(attr, value)));
        return DimensionType.create(base.hasFixedTime(), base.hasSkylight(), base.hasCeiling(), base.coordinateScale(),
            base.minY(), base.height(), base.logicalHeight(), base.infiniburn(), base.ambientLight(),
            base.monsterSpawnLightLevel(), base.monsterSpawnBlockLightLimit(), DimensionType.Skybox.OVERWORLD,
            base.cardinalLight(), new EnvironmentAttributeMap(attrs), RegistryTag.direct(timeline));
    }

    @SuppressWarnings("unchecked")
    private static <T> EnvironmentAttributeMap.Entry<T, T> entry(EnvironmentAttribute<T> attr, Object value) {
        return new EnvironmentAttributeMap.Entry<>((T) value, new EnvironmentAttribute.Modifier.Override<>(attr.valueCodec()));
    }

    /** Зарегистрировать таймлайн и тип мира (до старта сервера). */
    static Result register(Logger log) {
        DynamicRegistry<Timeline> timelines = MinecraftServer.getTimelineRegistry();
        DynamicRegistry<DimensionType> dims = MinecraftServer.getDimensionTypeRegistry();
        Key key = Key.key(KEY);
        boolean hasTimeline = timelines.get(key) != null;
        boolean hasDim = dims.get(key) != null;
        if (hasTimeline && hasDim) {
            return Result.ALREADY;
        }
        if (MinecraftServer.isStarted()) {
            log.warn("тип мира {} не зарегистрирован: сервер уже запущен, реестры заморожены — нужен перезапуск сервера",
                KEY);
            return Result.FROZEN;
        }
        try {
            RegistryKey<Timeline> tl = hasTimeline ? timelines.getKey(key) : timelines.register(key, timeline());
            if (!hasDim) {
                dims.register(key, dimension(dims.get(DimensionType.OVERWORLD), tl));
            }
        } catch (UnsupportedOperationException frozen) {
            log.warn("тип мира {} не зарегистрирован: реестр заморожен ({}) — нужен перезапуск сервера", KEY,
                frozen.getMessage());
            return Result.FROZEN;
        }
        log.info("тип мира {} зарегистрирован (небо и туман {}, без облаков, заката и ванильных звёзд)", KEY,
            Zones.hex(MARK));
        return Result.REGISTERED;
    }
}
