// spacesky: режим неба — файл пишет модуль spacesky из config.yml, руками не менять.
#ifndef SPACESKY_MODE_GLSL
#define SPACESKY_MODE_GLSL
// 1 — космос во всех мирах с обычным небом; 0 — только в мирах типа hoka:space (по МЕТКЕ)
#define SPACESKY_EVERYWHERE 1
// режим 0: прятать солнце / луну в мире hoka:space (шейдер position_tex)
#define SPACESKY_HIDE_SUN 1
#define SPACESKY_HIDE_MOON 1
// МЕТКА — цвет неба и тумана мира hoka:space (#020205), в ванили такого нет
#define SPACESKY_MARK (vec3(2.0, 2.0, 5.0) / 255.0)
// МЕТКА ВОДЫ — цвет тумана под водой в мире hoka:space (#050634)
#define SPACESKY_WATER_MARK (vec3(5.0, 6.0, 52.0) / 255.0)
// цвет космоса у горизонта — общий для неба (sky.fsh) и тумана дальности (fog.glsl режима 1)
#define SPACESKY_BASE vec3(0.006, 0.007, 0.018)

// Цвет c — метка mark? Ровно (допуск 0,6/255: клиент передаёт цвет как байты /255) или метка, умноженная на
// число: так клиент затемняет туман у дна мира (пустота) и осветляет ночным зрением.
bool spacesky_is(vec3 c, vec3 mark) {
    if (all(lessThan(abs(c - mark), vec3(0.6 / 255.0)))) return true;
    float l = length(c);
    return l > 1e-5 && distance(c / l, normalize(mark)) < 0.004;
}

// Цвет неба или тумана — МЕТКА мира hoka:space?
bool spacesky_marked(vec3 c) {
    return spacesky_is(c, SPACESKY_MARK);
}

// Цвет тумана (FogColor) — мира hoka:space? МЕТКА или, когда камера под водой, МЕТКА ВОДЫ.
bool spacesky_fog_marked(vec3 c) {
    return spacesky_marked(c) || spacesky_is(c, SPACESKY_WATER_MARK);
}
#endif
