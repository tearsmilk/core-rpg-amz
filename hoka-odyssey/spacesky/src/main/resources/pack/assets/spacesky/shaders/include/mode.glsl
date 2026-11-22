// spacesky: режим неба — файл пишет модуль spacesky из config.yml, руками не менять.
#ifndef SPACESKY_MODE_GLSL
#define SPACESKY_MODE_GLSL
// 1 — космос во всех мирах с обычным небом (миры без метки — профиль space); 0 — только в мирах профилей
#define SPACESKY_EVERYWHERE 1
// прятать солнце / луну в мирах профилей-галактик (шейдер position_tex; у планет — всегда)
#define SPACESKY_HIDE_SUN 1
#define SPACESKY_HIDE_MOON 1
// цвет космоса у горизонта по умолчанию (профиль space)
#define SPACESKY_BASE vec3(0.006, 0.007, 0.018)
#endif
