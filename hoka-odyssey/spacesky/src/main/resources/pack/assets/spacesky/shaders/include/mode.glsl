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
// цвет космоса у горизонта — общий для неба (sky.fsh) и тумана дальности (fog.glsl режима 1)
#define SPACESKY_BASE vec3(0.006, 0.007, 0.018)

// Цвет — МЕТКА? Ровно (допуск 0,6/255: клиент передаёт цвет как байты /255) или МЕТКА, умноженная на
// число: так клиент затемняет туман у дна мира (пустота) и осветляет ночным зрением.
bool spacesky_marked(vec3 c) {
    if (all(lessThan(abs(c - SPACESKY_MARK), vec3(0.6 / 255.0)))) return true;
    float l = length(c);
    return l > 1e-5 && distance(c / l, normalize(SPACESKY_MARK)) < 0.004;
}
#endif
