package ink.hoka.odyssey.spacesky;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.util.RGBLike;
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
 * Типы миров профилей неба «hoka:&lt;id&gt;» (DimensionType + Timeline с тем же ключом), см. {@link Profiles}. Профиль по
 * умолчанию — «space», тип мира {@code hoka:space} (как до профилей). Карта выбирает тип ключом
 * {@code dimension: "hoka:<id>"} в maps/&lt;карта&gt;/map.yml (это читает ядро, см. docs/core-dimension.md).
 *
 * <p>Небо и туман такого мира — МЕТКА профиля ({@link Profiles#skyMark}), туман под водой — МЕТКА ВОДЫ
 * ({@link Profiles#waterMark}). Таймлайн применяется клиентом поверх атрибутов измерения и биома (биом plains задаёт
 * свой sky_color — он перебил бы атрибут измерения), поэтому метки заданы и там, и там. Закатный веер выключен
 * (альфа 0); проход звёзд у галактики — яркость 1, угол 0 (в нём частицы параллакса, stars.vsh), у планеты — 0.
 *
 * <ul>
 *   <li>Галактика: таймлайн без периода — всё постоянно, облаков нет, свет неба всегда дневной.</li>
 *   <li>Планета: таймлайн суток (24000 тиков): фаза — в начале тумана окружения (см. {@link Profiles}), свет неба
 *       днём/ночью — как у ванильных суток, облака — цвет профиля днём, темнее ночью (или выключены), ванильные
 *       солнце и луна ходят как в ванили — по кривой суток minecraft:day, той же, что у солнца профиля в шейдере
 *       (ванильные прячет шейдер, солнце профиля рисует sky.fsh).</li>
 * </ul>
 *
 * <p>Реестры Minestom замораживаются после {@code MinecraftServer.start}: регистрировать можно только до старта
 * (модуль делает это в onLoad). Номер профиля зашит в метку типа мира, поэтому при повторной загрузке модуля номер
 * берётся из уже зарегистрированного типа (метка в его fog_color), а новый профиль появится после перезапуска.
 */
final class SpaceDimension {
    static final String KEY = Profiles.PREFIX + Profiles.DEFAULT_ID;
    /** МЕТКА профиля space (номер 0). */
    static final int MARK = Profiles.skyMark(0);
    /** МЕТКА ВОДЫ профиля space. */
    static final int WATER_MARK = Profiles.waterMark(0);
    static final int DAY = 24000;

    enum Status {
        /** Тип мира и таймлайн зарегистрированы сейчас. */
        REGISTERED,
        /** Уже были (модуль перезагружен) — не тронуты, номер взят из метки. */
        ALREADY,
        /** Сервер уже запущен, реестр заморожен — нужен перезапуск. */
        FROZEN,
        /** Зарегистрирован, но метка не разбирается (старая версия модуля) — нужен перезапуск. */
        STALE,
        /** Не хватило номеров (больше {@link Profiles#MAX}). */
        FULL
    }

    /** Итог регистрации: профили с номерами (только доступные) и статус каждого id. */
    record Registration(List<Profiles.Profile> profiles, Map<String, Status> status) {
    }

    private SpaceDimension() {
    }

    // ===== атрибуты и таймлайн =====

    /** Постоянные атрибуты профиля (в измерении и в таймлайне — Override, одна точка). */
    static Map<EnvironmentAttribute<?>, Object> attributes(Profiles.Profile p) {
        Map<EnvironmentAttribute<?>, Object> m = new LinkedHashMap<>();
        m.put(EnvironmentAttribute.SKY_COLOR, new Color(Profiles.skyMark(p.index())));
        m.put(EnvironmentAttribute.FOG_COLOR, new Color(Profiles.skyMark(p.index())));
        m.put(EnvironmentAttribute.WATER_FOG_COLOR, new Color(Profiles.waterMark(p.index())));
        m.put(EnvironmentAttribute.SUNRISE_SUNSET_COLOR, new AlphaColor(0, 0, 0, 0));
        // Проход звёзд у галактики рисуется всегда (яркость 1): в нём шейдер stars.vsh рисует частицы параллакса —
        // ближние звёзды и пыль (ванильных звёзд там нет; parallax 0 или near-stars и dust 0 — вершины схлопываются,
        // поэтому /hosky reload включает и выключает частицы без перезапуска). Угол звёзд — 0: шейдер снимает известный
        // поворот прохода звёзд с модель-вида. У планеты проход не рисуется (её звёзды ночью — в sky.fsh).
        m.put(EnvironmentAttribute.STAR_BRIGHTNESS, p.planet() ? 0f : 1f);
        if (!p.planet()) {
            m.put(EnvironmentAttribute.STAR_ANGLE, 0f);
        }
        if (p.planet()) {
            m.put(EnvironmentAttribute.CLOUD_COLOR, cloud(p, false));
            m.put(EnvironmentAttribute.FOG_START_DISTANCE, Profiles.PHASE_BASE);
            m.put(EnvironmentAttribute.FOG_END_DISTANCE, Profiles.FOG_END);
            m.put(EnvironmentAttribute.WATER_FOG_START_DISTANCE, Profiles.WATER_START);
        } else {
            m.put(EnvironmentAttribute.CLOUD_COLOR, new AlphaColor(0, 0, 0, 0));
        }
        return m;
    }

    /** Цвет облаков планеты днём (альфа 0.8, как в ванили) или ночью (×0.1, как ванильные сутки); без облаков — 0. */
    private static AlphaColor cloud(Profiles.Profile p, boolean night) {
        if (!p.clouds()) {
            return new AlphaColor(0, 0, 0, 0);
        }
        int c = p.cloudColor().rgb();
        double k = night ? 0.1 : 1.0;
        return new AlphaColor(204, (int) Math.round(((c >> 16) & 255) * k), (int) Math.round(((c >> 8) & 255) * k),
            (int) Math.round((c & 255) * k));
    }

    /** Timeline профиля: галактика — без периода, Override одной точкой; планета — сутки 24000 тиков. */
    static Timeline timeline(Profiles.Profile p) {
        Timeline.Builder b = Timeline.builder();
        attributes(p).forEach((attr, value) -> track(b, attr, List.of(new Timeline.Keyframe<>(0, value)), EaseFunction.CONSTANT));
        if (!p.planet()) {
            return b.build();
        }
        b.periodTicks(DAY);
        // Фаза суток — в начале тумана: две точки в одном тике (как sun_angle ванильных суток) — от 30000 в тике 0
        // линейно до 31000 к концу суток, потом скачок назад (фаза 1 = 0).
        track(b, EnvironmentAttribute.FOG_START_DISTANCE, List.of(
            new Timeline.Keyframe<>(0, Profiles.PHASE_BASE + Profiles.PHASE_SPAN),
            new Timeline.Keyframe<>(0, Profiles.PHASE_BASE)), EaseFunction.LINEAR);
        track(b, EnvironmentAttribute.WATER_FOG_START_DISTANCE, List.of(
            new Timeline.Keyframe<>(0, Profiles.WATER_START - 1f),
            new Timeline.Keyframe<>(0, Profiles.WATER_START)), EaseFunction.LINEAR);
        // ванильные солнце и луна (их прячет шейдер) — как в ванильных сутках minecraft:day: в полдень (6000) солнце
        // в зените, ход — по той же кривой (cubic_bezier), что и у солнца профиля в шейдере (spacesky_sun_dir)
        track(b, EnvironmentAttribute.SUN_ANGLE, List.of(new Timeline.Keyframe<>(6000, 360f),
            new Timeline.Keyframe<>(6000, 0f)), vanillaSunEase());
        track(b, EnvironmentAttribute.MOON_ANGLE, List.of(new Timeline.Keyframe<>(6000, 540f),
            new Timeline.Keyframe<>(6000, 180f)), vanillaSunEase());
        // свет неба днём/ночью — точки ванильных суток (minecraft:day), значения — для неба overworld
        track(b, EnvironmentAttribute.SKY_LIGHT_FACTOR, List.of(new Timeline.Keyframe<>(730, 1f),
            new Timeline.Keyframe<>(11270, 1f), new Timeline.Keyframe<>(13140, 0.24f),
            new Timeline.Keyframe<>(22860, 0.24f)), EaseFunction.LINEAR);
        Color day = new Color(255, 255, 255);
        Color night = new Color(0x7a7aff);
        track(b, EnvironmentAttribute.SKY_LIGHT_COLOR, List.of(new Timeline.Keyframe<>(730, day),
            new Timeline.Keyframe<>(11270, day), new Timeline.Keyframe<>(13140, night),
            new Timeline.Keyframe<>(22860, night)), EaseFunction.LINEAR);
        track(b, EnvironmentAttribute.SKY_LIGHT_LEVEL, List.of(new Timeline.Keyframe<>(133, 15f),
            new Timeline.Keyframe<>(11867, 15f), new Timeline.Keyframe<>(13670, 4f),
            new Timeline.Keyframe<>(22330, 4f)), EaseFunction.LINEAR);
        if (p.clouds()) {
            AlphaColor cd = cloud(p, false);
            AlphaColor cn = cloud(p, true);
            track(b, EnvironmentAttribute.CLOUD_COLOR, List.of(new Timeline.Keyframe<>(133, cd),
                new Timeline.Keyframe<>(11867, cd), new Timeline.Keyframe<>(13670, cn),
                new Timeline.Keyframe<>(22330, cn)), EaseFunction.LINEAR);
        }
        return b.build();
    }

    /** Кривая хода солнца и луны ванильных суток (data/minecraft/timeline/day.json 1.21.11: sun_angle, moon_angle). */
    static EaseFunction vanillaSunEase() {
        return new EaseFunction.CubicBezier(new float[] {0.362f, 0.241f, 0.638f, 0.759f});
    }

    @SuppressWarnings("unchecked")
    private static <T> void track(Timeline.Builder b, EnvironmentAttribute<T> attr, List<? extends Timeline.Keyframe<?>> kf,
                                  EaseFunction ease) {
        b.track(attr, new Timeline.Track<>(new EnvironmentAttribute.Modifier.Override<>(attr.valueCodec()),
            (List<Timeline.Keyframe<T>>) (List<?>) kf, ease));
    }

    /**
     * Тип мира: всё как у overworld (высота -64..320, небесный свет, без потолка, skybox OVERWORLD, infiniburn,
     * свет спавна мобов, звуки, кровати), атрибуты неба — метки профиля, таймлайн — только свой.
     */
    static DimensionType dimension(DimensionType overworld, Profiles.Profile p, RegistryKey<Timeline> timeline) {
        DimensionType base = overworld != null ? overworld : DimensionType.builder().build();
        Map<EnvironmentAttribute<?>, EnvironmentAttributeMap.Entry<?, ?>> attrs = new HashMap<>(base.attributes().entries());
        attributes(p).forEach((attr, value) -> attrs.put(attr, entry(attr, value)));
        return DimensionType.create(base.hasFixedTime(), base.hasSkylight(), base.hasCeiling(), base.coordinateScale(),
            base.minY(), base.height(), base.logicalHeight(), base.infiniburn(), base.ambientLight(),
            base.monsterSpawnLightLevel(), base.monsterSpawnBlockLightLimit(), DimensionType.Skybox.OVERWORLD,
            base.cardinalLight(), new EnvironmentAttributeMap(attrs), RegistryTag.direct(timeline));
    }

    @SuppressWarnings("unchecked")
    private static <T> EnvironmentAttributeMap.Entry<T, T> entry(EnvironmentAttribute<T> attr, Object value) {
        return new EnvironmentAttributeMap.Entry<>((T) value, new EnvironmentAttribute.Modifier.Override<>(attr.valueCodec()));
    }

    /** Номер профиля по зарегистрированному типу мира (метка в fog_color), -1 — не наш/старый. */
    static int indexOf(DimensionType d) {
        EnvironmentAttributeMap.Entry<?, ?> e = d.attributes().entries().get(EnvironmentAttribute.FOG_COLOR);
        if (e == null || !(e.argument() instanceof RGBLike c)) {
            return -1;
        }
        return Profiles.skyIndex(c.red() << 16 | c.green() << 8 | c.blue());
    }

    /** Вид профиля по зарегистрированному типу мира: у планеты есть fog_end_distance = FOG_END. */
    static Profiles.Kind kindOf(DimensionType d) {
        EnvironmentAttributeMap.Entry<?, ?> e = d.attributes().entries().get(EnvironmentAttribute.FOG_END_DISTANCE);
        return e != null && e.argument() instanceof Float f && f == Profiles.FOG_END ? Profiles.Kind.PLANET
            : Profiles.Kind.GALAXY;
    }

    /**
     * Зарегистрировать типы миров профилей (до старта сервера). Уже зарегистрированные (перезагрузка модуля) не
     * трогаются — их номер берётся из метки; новые после старта — только предупреждение (нужен перезапуск).
     * Номера новым — по порядку свободные (space — 0).
     */
    static Registration register(Logger log, List<Profiles.Profile> wanted) {
        DynamicRegistry<Timeline> timelines = MinecraftServer.getTimelineRegistry();
        DynamicRegistry<DimensionType> dims = MinecraftServer.getDimensionTypeRegistry();
        Map<String, Status> status = new LinkedHashMap<>();
        Map<String, Profiles.Profile> got = new LinkedHashMap<>();
        TreeSet<Integer> used = new TreeSet<>();
        List<Profiles.Profile> fresh = new ArrayList<>();
        for (Profiles.Profile p : wanted) {
            DimensionType d = dims.get(Key.key(p.key()));
            if (d == null) {
                fresh.add(p);
                continue;
            }
            int i = indexOf(d);
            if (i < 0 || used.contains(i)) {
                log.warn("тип мира {} уже зарегистрирован, но его метка не разбирается (старая версия модуля?) — "
                    + "нужен перезапуск сервера", p.key());
                status.put(p.id(), Status.STALE);
                continue;
            }
            Profiles.Kind k = kindOf(d);
            if (k != p.kind()) {
                log.warn("профиль {}: в config.yml {}, а тип мира зарегистрирован как {} — до перезапуска сервера {}",
                    p.id(), p.kind().id, k.id, k.id);
                p = p.withKind(k);
            }
            used.add(i);
            got.put(p.id(), p.withIndex(i));
            status.put(p.id(), Status.ALREADY);
        }
        boolean started = MinecraftServer.isStarted();
        for (Profiles.Profile p : fresh) {
            if (started) {
                log.warn("тип мира {} (профиль неба {}) не зарегистрирован: сервер уже запущен, реестры заморожены — "
                    + "нужен перезапуск сервера", p.key(), p.id());
                status.put(p.id(), Status.FROZEN);
                continue;
            }
            int i = 0;                                                   // space — первый в списке, ему 0
            while (used.contains(i)) {
                i++;
            }
            if (i >= Profiles.MAX) {
                log.warn("профиль неба {} пропущен: больше {} профилей", p.id(), Profiles.MAX);
                status.put(p.id(), Status.FULL);
                continue;
            }
            Profiles.Profile pi = p.withIndex(i);
            Key key = Key.key(p.key());
            try {
                RegistryKey<Timeline> tl = timelines.get(key) != null ? timelines.getKey(key)
                    : timelines.register(key, timeline(pi));
                dims.register(key, dimension(dims.get(DimensionType.OVERWORLD), pi, tl));
            } catch (UnsupportedOperationException frozen) {
                log.warn("тип мира {} не зарегистрирован: реестр заморожен ({}) — нужен перезапуск сервера", p.key(),
                    frozen.getMessage());
                status.put(p.id(), Status.FROZEN);
                continue;
            }
            used.add(i);
            got.put(p.id(), pi);
            status.put(p.id(), Status.REGISTERED);
            log.info("тип мира {} зарегистрирован: {} №{} (небо и туман {}, под водой {})", p.key(), p.kind().title, i,
                Zones.hex(Profiles.skyMark(i)), Zones.hex(Profiles.waterMark(i)));
        }
        List<Profiles.Profile> out = new ArrayList<>();
        for (Profiles.Profile p : wanted) {
            if (got.containsKey(p.id())) {
                out.add(got.get(p.id()));
            }
        }
        return new Registration(List.copyOf(out), status);
    }
}
