package core.amazake.systems.coremobs.region;

import core.amazake.systems.coremobs.mob.MobDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code all-models} в {@code /mobs region addmob|removemob}: все мобы из mobs.yml с моделью ModelEngine. Чистая логика
 * без сервера — команда только вызывает её внутри {@link RegionStore#update}.
 */
public final class ModelMobs {

    /** Слово вместо id моба. Моба с таким id в mobs.yml быть не может — MobCatalog его отбрасывает. */
    public static final String ALL_MODELS = "all-models";

    private ModelMobs() {
    }

    /** id мобов с моделью (model в mobs.yml задан и корректен), в порядке mobs.yml. */
    public static List<String> withModels(final Map<String, MobDef> mobs) {
        final List<String> out = new ArrayList<>();
        mobs.forEach((id, def) -> {
            if (def != null && def.model() != null) {
                out.add(id);
            }
        });
        return List.copyOf(out);
    }

    /**
     * Для removemob all-models: мобы с моделью и ещё буквальный ключ {@code all-models} — вдруг он остался в старом
     * regions.yml (моба с таким id в каталоге нет, иначе его никак не убрать).
     */
    public static List<String> withModelsAndLiteral(final Map<String, MobDef> mobs) {
        final List<String> out = new ArrayList<>(withModels(mobs));
        out.add(ALL_MODELS);
        return List.copyOf(out);
    }

    /**
     * Результат: новая карта мобов региона и какие id добавлены (их не было) / обновлены (были — вес заменён) /
     * убраны.
     */
    public record Change(Map<String, Integer> mobs, List<String> added, List<String> updated, List<String> removed) {
    }

    /** Добавить ids с весом weight; кто уже есть — получает этот вес, порядок прежних не меняется, дублей нет. */
    public static Change add(final Map<String, Integer> current, final List<String> ids, final int weight) {
        final Map<String, Integer> m = new LinkedHashMap<>(current);
        final List<String> added = new ArrayList<>();
        final List<String> updated = new ArrayList<>();
        for (final String id : ids) {
            if (m.put(id, weight) == null) {
                added.add(id);
            } else {
                updated.add(id);
            }
        }
        return new Change(m, List.copyOf(added), List.copyOf(updated), List.of());
    }

    /** Убрать ids; в removed — только те, что были в регионе. Остальные мобы региона не трогаются. */
    public static Change remove(final Map<String, Integer> current, final List<String> ids) {
        final Map<String, Integer> m = new LinkedHashMap<>(current);
        final List<String> removed = new ArrayList<>();
        for (final String id : ids) {
            if (m.remove(id) != null) {
                removed.add(id);
            }
        }
        return new Change(m, List.of(), List.of(), List.copyOf(removed));
    }
}
