package core.amazake.systems.corerpg.papi;

import core.amazake.systems.corerpg.aspect.Aspect;
import core.amazake.systems.corerpg.aspect.AspectSnapshot;
import core.amazake.systems.corerpg.profile.PlayerProfile;
import core.amazake.systems.corerpg.util.Text;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Чистое разрешение плейсхолдеров по неизменяемым снимкам: профиль, аспекты, предмет в руке. Без обращения к игроку и
 * миру, поэтому вызывается из любого потока (PlaceholderAPI часто зовут из асинхронных потоков табов и скорбордов).
 */
public final class Placeholders {

    /** Сведения о классах и уровнях из модуля progression. Реализация обязана быть потокобезопасной. */
    public interface Source {
        int maxLevel();

        int professionLevel();

        /** Опыт, нужный на текущий уровень целиком; 0 на максимуме. */
        long expToNext(PlayerProfile profile);

        String className(String classId);

        String professionName(String professionId);

        String primaryStatName(String classId);
    }

    /** Всё, что известно об игроке на момент запроса. {@code profile == null} — профиль не загружен. */
    public record View(PlayerProfile profile, AspectSnapshot aspects, HandSnapshot hand) {
        public View {
            aspects = aspects == null ? AspectSnapshot.EMPTY : aspects;
            hand = hand == null ? HandSnapshot.EMPTY : hand;
        }
    }

    /** Имена без параметров. */
    static final Set<String> NAMES = Set.of(
        "class", "class_key", "profession", "profession_key", "level", "max_level", "exp", "exp_needed", "exp_left",
        "exp_progress", "points", "stat_primary", "stat_agility", "stat_stamina", "stat_luck",
        "stat_primary_total", "stat_agility_total", "stat_stamina_total", "stat_luck_total",
        "stat_primary_name", "stat_agility_name", "stat_stamina_name", "stat_luck_name",
        "has_class", "has_profession", "needs_profession", "crit_chance",
        "sharpen", "sharpen_max", "sharpen_bonus", "sharpen_text", "seal", "seal_color");

    /** Старые имена RoyalQuestClasses (%royalquest_stats_…%) → новые. */
    static final Map<String, String> LEGACY = Map.of(
        "stats_available", "points",
        "stats_primary", "stat_primary",
        "stats_agility", "stat_agility",
        "stats_stamina", "stat_stamina",
        "stats_luck", "stat_luck",
        "stats_primary_name", "stat_primary_name");

    private static final String ASPECT_PREFIX = "aspect_";

    private Placeholders() {
    }

    /** Нормализованное имя (нижний регистр, старые имена → новые) или null, если такого плейсхолдера нет. */
    public static String normalize(final String params, final PapiSettings settings) {
        if (params == null) {
            return null;
        }
        String name = params.trim().toLowerCase(Locale.ROOT);
        if (settings.legacyNames()) {
            name = LEGACY.getOrDefault(name, name);
        }
        if (NAMES.contains(name)) {
            return name;
        }
        if (name.startsWith(ASPECT_PREFIX)) {
            final String key = name.substring(ASPECT_PREFIX.length());
            if (Aspect.byKey(key) != null || isTargeted(key)) {
                return name;
            }
        }
        return null;
    }

    /**
     * Значение плейсхолдера; null — плейсхолдер неизвестен (PlaceholderAPI оставит текст как есть). Пока профиль не
     * загружен — {@link PapiSettings#notLoaded()} (по умолчанию пустая строка).
     */
    public static String resolve(final String params, final View view, final Source source, final PapiSettings settings) {
        final String name = normalize(params, settings);
        if (name == null) {
            return null;
        }
        final PlayerProfile p = view == null ? null : view.profile();
        if (p == null) {
            return settings.notLoaded();
        }
        final AspectSnapshot aspects = view.aspects();
        final HandSnapshot hand = view.hand();
        if (name.startsWith(ASPECT_PREFIX)) {
            return Text.num(aspect(aspects, name.substring(ASPECT_PREFIX.length())));
        }
        return switch (name) {
            case "class" -> p.hasClass() ? nameOr(source.className(p.classId()), p.classId()) : settings.noneName();
            case "class_key" -> p.hasClass() ? p.classId() : settings.noneKey();
            case "profession" -> p.hasProfession() ? nameOr(source.professionName(p.professionId()), p.professionId()) : settings.noneName();
            case "profession_key" -> p.hasProfession() ? p.professionId() : settings.noneKey();
            case "level" -> Integer.toString(p.level());
            case "max_level" -> Integer.toString(source.maxLevel());
            case "exp" -> Long.toString(p.exp());
            case "exp_needed" -> Long.toString(Math.max(0L, source.expToNext(p)));
            case "exp_left" -> Long.toString(Math.max(0L, source.expToNext(p) - p.exp()));
            case "exp_progress" -> progress(p.exp(), source.expToNext(p), settings.progressDigits());
            case "points" -> Integer.toString(p.freePoints());
            case "stat_primary" -> Integer.toString(p.primary());
            case "stat_agility" -> Integer.toString(p.agility());
            case "stat_stamina" -> Integer.toString(p.stamina());
            case "stat_luck" -> Integer.toString(p.luck());
            case "stat_primary_total" -> Text.num(p.primary() + aspects.get(Aspect.STAT_PRIMARY));
            case "stat_agility_total" -> Text.num(p.agility() + aspects.get(Aspect.STAT_AGILITY));
            case "stat_stamina_total" -> Text.num(p.stamina() + aspects.get(Aspect.STAT_STAMINA));
            case "stat_luck_total" -> Text.num(p.luck() + aspects.get(Aspect.STAT_LUCK));
            case "stat_primary_name" -> nameOr(source.primaryStatName(p.classId()), Aspect.STAT_PRIMARY.label());
            case "stat_agility_name" -> Aspect.STAT_AGILITY.label();
            case "stat_stamina_name" -> Aspect.STAT_STAMINA.label();
            case "stat_luck_name" -> Aspect.STAT_LUCK.label();
            case "has_class" -> Boolean.toString(p.hasClass());
            case "has_profession" -> Boolean.toString(p.hasProfession());
            case "needs_profession" -> Boolean.toString(needsProfession(p, source.professionLevel()));
            case "crit_chance" -> Text.num(aspects.get(Aspect.CRIT_CHANCE));
            case "sharpen" -> Integer.toString(hand.sharpen());
            case "sharpen_max" -> Integer.toString(hand.sharpenMax());
            case "sharpen_bonus" -> Text.num(hand.sharpenBonus());
            case "sharpen_text" -> hand.sharpen() > 0 ? "+" + hand.sharpen() : "";
            case "seal" -> hand.sealName().isEmpty() ? settings.noSeal() : hand.sealName();
            case "seal_color" -> hand.sealColor().isEmpty() ? settings.noSeal() : hand.sealColor();
            default -> null;
        };
    }

    /** Класс выбран, профессии нет, уровень профессии достигнут. */
    public static boolean needsProfession(final PlayerProfile profile, final int professionLevel) {
        return profile.hasClass() && !profile.hasProfession() && profile.level() >= professionLevel;
    }

    /** Прогресс уровня в процентах 0..100 с {@code digits} знаками после запятой; на максимуме (needed ≤ 0) — 100. */
    public static String progress(final long exp, final long needed, final int digits) {
        final double percent = needed <= 0 ? 100.0 : Math.max(0.0, Math.min(100.0, exp * 100.0 / needed));
        // Отбрасываем, а не округляем: 99.96% не должно показываться как 100.0 до повышения уровня.
        final double scale = Math.pow(10, digits);
        final double truncated = needed <= 0 ? percent : Math.floor(percent * scale + 1e-9) / scale;
        return String.format(Locale.ROOT, "%." + digits + "f", truncated);
    }

    private static double aspect(final AspectSnapshot aspects, final String key) {
        final Aspect aspect = Aspect.byKey(key);
        return aspect != null ? aspects.get(aspect) : aspects.targeted(key);
    }

    /** «resistance:fire», «damage_vs:monster» — непустая цель из a-z, 0-9, _. */
    private static boolean isTargeted(final String key) {
        if (!Aspect.isTargetedKey(key)) {
            return false;
        }
        final String target = key.substring(key.indexOf(':') + 1);
        return target.matches("[a-z0-9_]+");
    }

    private static String nameOr(final String name, final String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }
}
