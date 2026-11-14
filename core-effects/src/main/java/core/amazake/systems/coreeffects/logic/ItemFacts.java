package core.amazake.systems.coreeffects.logic;

/**
 * Что известно о предмете для фильтра проклятья — без Bukkit, чтобы фильтр проверялся юнит-тестом.
 *
 * @param material       имя материала (DIAMOND_SWORD)
 * @param maxDurability  наибольшая прочность (0 — не ломается)
 * @param equippable     есть компонент equippable (надевается: броня, тыква, кастомная броня)
 * @param attributes     есть модификаторы атрибутов (оружие/снаряжение плагинов)
 * @param deathProtection есть компонент death_protection (тотем и его аналоги)
 * @param toolOrWeapon   материал в тегах инструментов/оружия/брони (мечи, топоры, кирки, копья, ...)
 * @param container      шалкер или мешок (в нём много вещей)
 * @param named          у предмета своё имя
 * @param customModel    своя модель (item_model не ванильный или custom_model_data)
 * @param nbt            предмет строкой (для поиска меток плагинов), может быть пустым
 */
public record ItemFacts(String material, int maxDurability, boolean equippable, boolean attributes,
                        boolean deathProtection, boolean toolOrWeapon, boolean container, boolean named,
                        boolean customModel, String nbt) {
}
