#version 330

#define AH_ACTIVE 1

#define AH_HOTBAR 0
#define AH_HOTBAR_SLICE 0
#define AH_HOTBAR_HIDDEN 0
#define AH_HOTBAR_ATTACK 1
const vec2 AH_HOTBAR_ANCHOR = vec2(0.5, 1.0);
const vec2 AH_HOTBAR_BASE = vec2(-91.0, -22.0);
const vec2 AH_HOTBAR_BOX = vec2(0.0, 0.0);
const vec2 AH_HOTBAR_BOX_SIZE = vec2(182.0, 22.0);
const vec2 AH_HOTBAR_ATTACK_OFFSET = vec2(0.0, 0.0);
const vec2 AH_HOTBAR_POS[9] = vec2[9](
    vec2(0.0, 0.0), vec2(20.0, 0.0), vec2(40.0, 0.0), vec2(60.0, 0.0), vec2(80.0, 0.0),
    vec2(100.0, 0.0), vec2(120.0, 0.0), vec2(140.0, 0.0), vec2(160.0, 0.0)
);

#define AH_OFFHAND 1
#define AH_OFFHAND_FOLLOW 0
const vec2 AH_OFFHAND_ANCHOR = vec2(0.5, 1.0);
const vec2 AH_OFFHAND_BASE = vec2(-120.0, -52.0);

#define AH_HEARTS 1
const vec2 AH_HEARTS_ANCHOR = vec2(0.5, 1.0);
const vec2 AH_HEARTS_BASE = vec2(-136.0, -20.0);
const vec2 AH_HEARTS_POS[40] = vec2[40](
    vec2(0.0, 10.0), vec2(8.0, 10.0), vec2(16.0, 10.0), vec2(24.0, 10.0), vec2(32.0, 10.0),
    vec2(0.0, 0.0), vec2(8.0, 0.0), vec2(16.0, 0.0), vec2(24.0, 0.0), vec2(32.0, 0.0),
    vec2(0.0, -10.0), vec2(8.0, -10.0), vec2(16.0, -10.0), vec2(24.0, -10.0), vec2(32.0, -10.0),
    vec2(0.0, -20.0), vec2(8.0, -20.0), vec2(16.0, -20.0), vec2(24.0, -20.0), vec2(32.0, -20.0),
    vec2(0.0, -30.0), vec2(8.0, -30.0), vec2(16.0, -30.0), vec2(24.0, -30.0), vec2(32.0, -30.0),
    vec2(0.0, -40.0), vec2(8.0, -40.0), vec2(16.0, -40.0), vec2(24.0, -40.0), vec2(32.0, -40.0),
    vec2(0.0, -50.0), vec2(8.0, -50.0), vec2(16.0, -50.0), vec2(24.0, -50.0), vec2(32.0, -50.0),
    vec2(0.0, -60.0), vec2(8.0, -60.0), vec2(16.0, -60.0), vec2(24.0, -60.0), vec2(32.0, -60.0)
);

#define AH_ARMOR 1
const vec2 AH_ARMOR_ANCHOR = vec2(0.5, 1.0);
const vec2 AH_ARMOR_BASE = vec2(-181.0, -20.0);
const vec2 AH_ARMOR_ROW_STEP = vec2(0.0, -10.0);
const vec2 AH_ARMOR_POS[10] = vec2[10](
    vec2(0.0, 10.0), vec2(8.0, 10.0), vec2(16.0, 10.0), vec2(24.0, 10.0), vec2(32.0, 10.0),
    vec2(0.0, 0.0), vec2(8.0, 0.0), vec2(16.0, 0.0), vec2(24.0, 0.0), vec2(32.0, 0.0)
);

#define AH_FOOD 1
const vec2 AH_FOOD_ANCHOR = vec2(0.5, 1.0);
const vec2 AH_FOOD_BASE = vec2(95.0, -20.0);
const vec2 AH_FOOD_POS[10] = vec2[10](
    vec2(32.0, 10.0), vec2(24.0, 10.0), vec2(16.0, 10.0), vec2(8.0, 10.0), vec2(0.0, 10.0),
    vec2(32.0, 0.0), vec2(24.0, 0.0), vec2(16.0, 0.0), vec2(8.0, 0.0), vec2(0.0, 0.0)
);

#define AH_AIR 1
const vec2 AH_AIR_ANCHOR = vec2(0.5, 1.0);
const vec2 AH_AIR_BASE = vec2(140.0, -20.0);
const vec2 AH_AIR_ROW_STEP = vec2(0.0, -10.0);
const vec2 AH_AIR_POS[10] = vec2[10](
    vec2(32.0, 10.0), vec2(24.0, 10.0), vec2(16.0, 10.0), vec2(8.0, 10.0), vec2(0.0, 10.0),
    vec2(32.0, 0.0), vec2(24.0, 0.0), vec2(16.0, 0.0), vec2(8.0, 0.0), vec2(0.0, 0.0)
);

#define AH_MOUNT 1
const vec2 AH_MOUNT_ANCHOR = vec2(0.5, 1.0);
const vec2 AH_MOUNT_BASE = vec2(95.0, -20.0);
const vec2 AH_MOUNT_POS[30] = vec2[30](
    vec2(32.0, 10.0), vec2(24.0, 10.0), vec2(16.0, 10.0), vec2(8.0, 10.0), vec2(0.0, 10.0),
    vec2(32.0, 0.0), vec2(24.0, 0.0), vec2(16.0, 0.0), vec2(8.0, 0.0), vec2(0.0, 0.0),
    vec2(32.0, -10.0), vec2(24.0, -10.0), vec2(16.0, -10.0), vec2(8.0, -10.0), vec2(0.0, -10.0),
    vec2(32.0, -20.0), vec2(24.0, -20.0), vec2(16.0, -20.0), vec2(8.0, -20.0), vec2(0.0, -20.0),
    vec2(32.0, -30.0), vec2(24.0, -30.0), vec2(16.0, -30.0), vec2(8.0, -30.0), vec2(0.0, -30.0),
    vec2(32.0, -40.0), vec2(24.0, -40.0), vec2(16.0, -40.0), vec2(8.0, -40.0), vec2(0.0, -40.0)
);

#define AH_XP 1
#define AH_XP_ROTATE 0
#define AH_XP_LEVEL 1
const vec2 AH_XP_ANCHOR = vec2(0.5, 1.0);
const vec2 AH_XP_BASE = vec2(-91.0, -27.0);
const vec2 AH_XP_SIZE = vec2(182.0, 5.0);
const vec2 AH_XP_LEVEL_OFFSET = vec2(0.0, 0.0);

#define AH_NAME 1
const vec2 AH_NAME_ANCHOR = vec2(0.5, 1.0);
const vec2 AH_NAME_BASE = vec2(0.0, -42.0);
