#version 330

#define AH_ACTIVE 1

#define AH_HOTBAR 1
#define AH_HOTBAR_SLICE 1
#define AH_HOTBAR_HIDDEN 0
#define AH_HOTBAR_ATTACK 1
#define AH_HOTBAR_EDGE_L 0
#define AH_HOTBAR_EDGE_R 8
const vec2 AH_HOTBAR_ANCHOR = vec2(1.0, 1.0);
const vec2 AH_HOTBAR_BASE = vec2(-64.0, -64.0);
const vec2 AH_HOTBAR_BOX = vec2(0.0, 0.0);
const vec2 AH_HOTBAR_BOX_SIZE = vec2(62.0, 62.0);
const vec2 AH_HOTBAR_ATTACK_OFFSET = vec2(-24.0, -90.0);
const vec2 AH_HOTBAR_POS[9] = vec2[9](
    vec2(0.0, 0.0), vec2(20.0, 0.0), vec2(40.0, 0.0), vec2(0.0, 20.0), vec2(20.0, 20.0),
    vec2(40.0, 20.0), vec2(0.0, 40.0), vec2(20.0, 40.0), vec2(40.0, 40.0)
);

#define AH_OFFHAND 1
#define AH_OFFHAND_FOLLOW 0
const vec2 AH_OFFHAND_ANCHOR = vec2(1.0, 1.0);
const vec2 AH_OFFHAND_BASE = vec2(-31.0, -90.0);
const vec2 AH_OFFHAND_FOLLOW_OFFSET = vec2(0.0, 0.0);

#define AH_HEARTS 1
const vec2 AH_HEARTS_ANCHOR = vec2(0.0, 1.0);
const vec2 AH_HEARTS_BASE = vec2(2.0, -11.0);
const vec2 AH_HEARTS_POS[40] = vec2[40](
    vec2(0.0, 0.0), vec2(8.0, 0.0), vec2(16.0, 0.0), vec2(24.0, 0.0), vec2(32.0, 0.0),
    vec2(40.0, 0.0), vec2(48.0, 0.0), vec2(56.0, 0.0), vec2(64.0, 0.0), vec2(72.0, 0.0),
    vec2(0.0, -10.0), vec2(8.0, -10.0), vec2(16.0, -10.0), vec2(24.0, -10.0), vec2(32.0, -10.0),
    vec2(40.0, -10.0), vec2(48.0, -10.0), vec2(56.0, -10.0), vec2(64.0, -10.0), vec2(72.0, -10.0),
    vec2(0.0, -20.0), vec2(8.0, -20.0), vec2(16.0, -20.0), vec2(24.0, -20.0), vec2(32.0, -20.0),
    vec2(40.0, -20.0), vec2(48.0, -20.0), vec2(56.0, -20.0), vec2(64.0, -20.0), vec2(72.0, -20.0),
    vec2(0.0, -30.0), vec2(8.0, -30.0), vec2(16.0, -30.0), vec2(24.0, -30.0), vec2(32.0, -30.0),
    vec2(40.0, -30.0), vec2(48.0, -30.0), vec2(56.0, -30.0), vec2(64.0, -30.0), vec2(72.0, -30.0)
);

#define AH_ARMOR 1
const vec2 AH_ARMOR_ANCHOR = vec2(0.0, 1.0);
const vec2 AH_ARMOR_BASE = vec2(2.0, -21.0);
const vec2 AH_ARMOR_ROW_STEP = vec2(0.0, -10.0);
const vec2 AH_ARMOR_POS[10] = vec2[10](
    vec2(0.0, 0.0), vec2(8.0, 0.0), vec2(16.0, 0.0), vec2(24.0, 0.0), vec2(32.0, 0.0),
    vec2(40.0, 0.0), vec2(48.0, 0.0), vec2(56.0, 0.0), vec2(64.0, 0.0), vec2(72.0, 0.0)
);

#define AH_FOOD 1
const vec2 AH_FOOD_ANCHOR = vec2(0.0, 1.0);
const vec2 AH_FOOD_BASE = vec2(88.0, -11.0);
const vec2 AH_FOOD_POS[10] = vec2[10](
    vec2(72.0, 0.0), vec2(64.0, 0.0), vec2(56.0, 0.0), vec2(48.0, 0.0), vec2(40.0, 0.0),
    vec2(32.0, 0.0), vec2(24.0, 0.0), vec2(16.0, 0.0), vec2(8.0, 0.0), vec2(0.0, 0.0)
);

#define AH_AIR 1
const vec2 AH_AIR_ANCHOR = vec2(0.0, 1.0);
const vec2 AH_AIR_BASE = vec2(88.0, -21.0);
const vec2 AH_AIR_ROW_STEP = vec2(0.0, -10.0);
const vec2 AH_AIR_POS[10] = vec2[10](
    vec2(72.0, 0.0), vec2(64.0, 0.0), vec2(56.0, 0.0), vec2(48.0, 0.0), vec2(40.0, 0.0),
    vec2(32.0, 0.0), vec2(24.0, 0.0), vec2(16.0, 0.0), vec2(8.0, 0.0), vec2(0.0, 0.0)
);

#define AH_MOUNT 1
const vec2 AH_MOUNT_ANCHOR = vec2(0.0, 1.0);
const vec2 AH_MOUNT_BASE = vec2(88.0, -11.0);
const vec2 AH_MOUNT_POS[30] = vec2[30](
    vec2(72.0, 0.0), vec2(64.0, 0.0), vec2(56.0, 0.0), vec2(48.0, 0.0), vec2(40.0, 0.0),
    vec2(32.0, 0.0), vec2(24.0, 0.0), vec2(16.0, 0.0), vec2(8.0, 0.0), vec2(0.0, 0.0),
    vec2(72.0, -10.0), vec2(64.0, -10.0), vec2(56.0, -10.0), vec2(48.0, -10.0), vec2(40.0, -10.0),
    vec2(32.0, -10.0), vec2(24.0, -10.0), vec2(16.0, -10.0), vec2(8.0, -10.0), vec2(0.0, -10.0),
    vec2(72.0, -20.0), vec2(64.0, -20.0), vec2(56.0, -20.0), vec2(48.0, -20.0), vec2(40.0, -20.0),
    vec2(32.0, -20.0), vec2(24.0, -20.0), vec2(16.0, -20.0), vec2(8.0, -20.0), vec2(0.0, -20.0)
);

#define AH_XP 1
#define AH_XP_ROTATE 3
#define AH_XP_LEVEL 1
const vec2 AH_XP_ANCHOR = vec2(1.0, 1.0);
const vec2 AH_XP_BASE = vec2(-73.0, -184.0);
const vec2 AH_XP_SIZE = vec2(5.0, 182.0);
const vec2 AH_XP_LEVEL_OFFSET = vec2(-10.0, 0.0);

#define AH_NAME 1
#define AH_NAME_GLYPH_COUNT 1
const vec2 AH_NAME_ANCHOR = vec2(0.5, 1.0);
const vec2 AH_NAME_BASE = vec2(0.0, -30.0);
const vec2 AH_NAME_GLYPHS[1] = vec2[1](
    vec2(0.0, 8.0)
);
