#!/usr/bin/env python3
"""Writes the Azerus MythicHUD configs (assets + layouts + config.yml) into mythichud-azerus/.

All geometry lives here, in GUI pixels (1 texture px = 1 GUI px). MythicHUD conventions used:
  * layout element: position x/y in % of the screen (y=0 bottom, y=100 top), offset in px (y up);
  * layer offset: top-left corner of the texture relative to the element, y grows UP, so a layer
    drawn N px below the element origin has y: -N;
  * text layer: offset = top of the 8 px glyph cell; align right = text starts at x and grows right,
    align left = text ends at x, align center = centred on x;
  * the FIRST layer in the list is drawn on top;
  * condition line `<cond> true|false <action>`: hide = hide unless cond==value; texture/color =
    applied when cond==value (verified in MythicHUD 1.3.6 ConditionHolder bytecode).

Run:  python3 -I mythichud-azerus/tools/gen_yaml.py
The skill-icon mapping is copied from the production snapshot's skill_casting_bar-hud.yml.
"""
import os
import re

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
REPO = os.path.normpath(os.path.join(ROOT, ".."))
SNAP = os.path.join(REPO, "server/plugins/MythicHUD")

# ---------------------------------------------------------------------------------------------
# Placeholders you may want to change (one place).
GEMS = "%azerus_gems%"   # 2nd currency (dark gem). No such currency exists on the server yet:
#                          put e.g. %playerpoints_points% here; while it does not return a number
#                          the field shows a grey "—".
COINS_DEFAULT = "%corevault_balance_short%"   # "12 450" (core-vault); the old HUD showed
#                          %vault_eco_balance_formatted% ("12 450 монет") which does not fit the coin field
MMO_LEVEL = "%mmocore_level%"
MMO_PERCENT = "%math_1_{mmocore_experience}*100/{mmocore_next_level}%"
BOTTOM_LEVEL = "%hl_level%"   # same as the old HUD (RoyalQuestClasses level, green number)
WORLD = "%player_world%"

NOT_CREATIVE = "    - gamemode{gm=creative,spectator} false hide # hidden in creative/spectator (as the old HUD)"
CLASS_ONLY = "        - placeholder{ph=%mmocore_class_id%;v=HUMAN} false hide # bar only for players with an RQ/MMOCore skill class"
IS_ENERGY = "placeholder{ph=%mmocore_mana_name%;v=Энергия}"


def tex(name, path, x, y, conds=None, func=None, color=None, comment=None):
    s = "    %s:%s\n" % (name, ("  # " + comment) if comment else "")
    if conds:
        s += "      conditions:\n" + "".join("        - %s\n" % c for c in conds)
    s += "      texture:\n        path: %s\n" % path
    if color:
        s += '      color: "%s"\n' % color
    s += "      offset:\n        x: %d\n        y: %d\n" % (x, y)
    if func:
        lst, inc = func
        s += "      function:\n        listener: %s\n        increment: %d\n        orientation: horizontal\n" % (lst, inc)
    return s


SHADOW = "#10161dff"   # 1px dark slate drop shadow under HUD numbers (text shadow_color, 1.21.4+)


def text(name, txt, font, x, y, align="center", conds=None, comment=None, shadow=SHADOW):
    s = "    %s:%s\n" % (name, ("  # " + comment) if comment else "")
    if conds:
        s += "      conditions:\n" + "".join("        - %s\n" % c for c in conds)
    s += '      text: "%s"\n      font: "%s"\n      align: %s\n' % (txt, font, align)
    if shadow:
        s += '      shadow: "%s"\n' % shadow
    s += "      offset:\n        x: %d\n        y: %d\n" % (x, y)
    return s


def asset(name, layers, conds=(NOT_CREATIVE,), comment=None):
    s = ""
    if comment:
        s += "".join("# %s\n" % l for l in comment.split("\n"))
    s += "%s:\n" % name
    if conds:
        s += "  conditions:\n" + "".join(c + "\n" for c in conds)
    s += "  layers:\n" + "".join(layers)
    return s + "\n"


A = "assets/azerus/"

# ---------------------------------------------------------------------------------------------
# TOP-LEFT player panel. Element origin = screen (4, 4) from the top-left corner.
#   portrait block 38x38 at local (0,1): gold L-brackets, slate frame, head 24x24 at (7,8)
#   info panel 122x40 at (40,0): coin field (43..104, 3..13), gem field (108..158, 3..13),
#   MMO level bar row 16..25, mana bar row 28..37.
PL = []
PL.append(text("coin_value", "<#ffd25a>%vault_eco_balance_fixed%", "mmohud-font", 55, -5, "right",
               ["placeholder{ph=%deluxemenus_meta_currency_BOOLEAN%;v=true;o==} true hide"],
               "whole number - when the /menu currency toggle (deluxemenus meta 'currency') is on, as the old HUD"))
PL.append(text("coin_value_formatted", "<#ffd25a>" + COINS_DEFAULT, "mmohud-font", 55, -5, "right",
               ["placeholder{ph=%deluxemenus_meta_currency_BOOLEAN%;v=true;o==} false hide"],
               "default: grouped number without the currency word (fits the 62 px field)"))
PL.append(text("gem_value", "<#cdb8ff>" + GEMS, "mmohud-font", 120, -5, "right",
               ["placeholder{ph=%s;v=0;o=>=} true hide" % GEMS],
               "2nd currency: shown only while the placeholder returns a number (see GEMS in tools/gen_yaml.py)"))
PL.append(text("gem_value_none", "<#5d7690>-", "mmohud-font", 120, -5, "right",
               ["placeholder{ph=%s;v=0;o=>=} false hide" % GEMS]))
PL.append(tex("coin_icon", A + "icon_coin.png", 44, -4))
PL.append(tex("gem_icon", A + "icon_gem.png", 109, -4))
PL.append("    head:  # player's face (8x8 skin pixels x3 = 24x24), MythicHUD head layer\n"
          "      head:\n        size: 3\n      offset:\n        x: 7\n        y: -8\n")
PL.append(tex("portrait", A + "portrait_frame.png", 0, -1))
PL.append(tex("panel", A + "player_panel.png", 40, 0))
player = asset("azerus_player", PL, comment=(
    "=====================================================================================\n"
    " AZERUS HUD (slate + gold) - top-left player panel: head portrait + currencies\n"
    "====================================================================================="))

XP = []
XP.append(text("label", "MMO LV.%s : %s%%" % (MMO_LEVEL, MMO_PERCENT), "mmohud-font", 107, -18))
XP.append(tex("icon", A + "icon_gem_green.png", 44, -16))
XP.append(tex("fill", A + "fill_wide_xp.png", 55, -17, func=("mmoexp", 52)))
XP.append(tex("frame", A + "bar_frame_wide.png", 54, -16))
player_xp = asset("azerus_player_xp", XP, comment="top-left: MMOCore level / exp bar (listener mmoexp = %mmocore_experience% / %mmocore_next_level%)")

MP = []
MP.append(text("label_mana", "MP.%fill-value%/%fill-max%", "mmohud-font", 107, -30,
               conds=[IS_ENERGY + " false hide"]))
MP.append(text("label_energy", "EN.%fill-value%/%fill-max%", "mmohud-font", 107, -30,
               conds=[IS_ENERGY + " true hide"]))
MP.append(tex("icon_mana", A + "icon_potion.png", 44, -28, [IS_ENERGY + " false hide"]))
MP.append(tex("icon_energy", A + "icon_potion_energy.png", 44, -28, [IS_ENERGY + " true hide"]))
MP.append(tex("fill_energy", A + "fill_wide_energy.png", 55, -29, [IS_ENERGY + " true hide"], func=("mana", 52)))
MP.append(tex("fill_mana", A + "fill_wide_mana.png", 55, -29, [IS_ENERGY + " false hide"], func=("mana", 52)))
MP.append(tex("frame", A + "bar_frame_wide.png", 54, -28))
player_mp = asset("azerus_player_mana", MP, comment=(
    "top-left: mana bar \"MP.cur/max\" (listener mana = %mmocore_mana% / %mmocore_stat_max_mana%);\n"
    "classes whose MMOCore resource is called 'Энергия' get the gold \"EN.\" variant (same condition as the old HUD)"))

# ---------------------------------------------------------------------------------------------
# TOP-CENTRE location plate 140x15. Element origin = screen (240-70, 3).
LOC = []
LOC.append(text("text", "<#ffd25a>%s <#5d7690>| <#dce6ef>%%player_x%% %%player_y%% %%player_z%%" % WORLD,
                "mmohud-font", 76, -4))
LOC.append(tex("icon", A + "icon_compass.png", 3, -2))
LOC.append(tex("panel", A + "location_panel.png", 0, 0))
location = asset("azerus_location", LOC, comment="top-centre: compass + world name + block coordinates")

# ---------------------------------------------------------------------------------------------
# BOTTOM. Left panel (armor over health) element origin = screen (149, 220) = centre-91 / 50 up.
#   panel 81x20; icons 9x9 at x=2; bars 67x7 at x=12 (fill 65x5 at x=13), rows 2..8 and 10..16.
LP = [tex("panel", A + "bottom_panel.png", 0, 0)]
panel_left = asset("azerus_panel_left", LP, comment="bottom-left plate (armor + health bars sit on it)")
RP = [tex("panel", A + "bottom_panel.png", 0, 0)]
panel_right = asset("azerus_panel_right", RP, comment="bottom-right plate (mana/energy + hunger bars sit on it)")

AR = []
AR.append(text("value", "%fill-value%", "mmohud-font", 45, -2))
AR.append(tex("icon", A + "icon_armor.png", 2, -1))
AR.append(tex("fill", A + "fill_armor.png", 13, -3, func=("armor40", 65)))
AR.append(tex("frame", A + "bar_frame.png", 12, -2))
armor = asset("azerus_armor", AR, comment="bottom-left, upper bar: armor (listener armor40 = %mmocore_stat_armor% / 40)")

HP = []
HP.append(text("value", "%player_health_rounded%/%player_max_health_rounded%", "mmohud-font", 45, -10,
               conds=["placeholder{ph=%player_absorption%;v=0;o=>} false hide"]))
HP.append(text("value_absorption", "<#ffe066>%math_0_{player_health_rounded}+{player_absorption}%<white>/%player_max_health_rounded%",
               "mmohud-font", 45, -10, conds=["placeholder{ph=%player_absorption%;v=0;o=>} true hide"]))
HP.append(tex("icon_absorption", A + "icon_heart_absorb.png", 2, -9, ["placeholder{ph=%player_absorption%;v=0;o=>} true hide"]))
HP.append(tex("icon_burning", A + "icon_heart_burning.png", 2, -9, ["placeholder{ph=%player-j17_is_burning%;v=yes} true hide"]))
HP.append(tex("icon_freezing", A + "icon_heart_frozen.png", 2, -9, ["placeholder{ph=%player-j17_is_freezing%;v=yes} true hide"]))
HP.append(tex("icon_poison", A + "icon_heart_poison.png", 2, -9, ["effect{e=poison} true hide"]))
HP.append(tex("icon_wither", A + "icon_heart_wither.png", 2, -9, ["effect{e=wither} true hide"]))
HP.append(tex("icon", A + "icon_heart.png", 2, -9))
HP.append(tex("fill_absorption", A + "fill_absorb_strip.png", 13, -11,
              ["placeholder{ph=%player_absorption%;v=0;o=>} true hide"], func=("absorption", 65),
              comment="gold 2px strip on top of the bar = absorption / max health"))
HP.append(tex("fill_burning", A + "fill_burning.png", 13, -11, ["placeholder{ph=%player-j17_is_burning%;v=yes} true hide"], func=("health", 65)))
HP.append(tex("fill_freezing", A + "fill_frozen.png", 13, -11, ["placeholder{ph=%player-j17_is_freezing%;v=yes} true hide"], func=("health", 65)))
HP.append(tex("fill_poison", A + "fill_poison.png", 13, -11, ["effect{e=poison} true hide"], func=("health", 65)))
HP.append(tex("fill_wither", A + "fill_wither.png", 13, -11, ["effect{e=wither} true hide"], func=("health", 65)))
HP.append(tex("fill", A + "fill_health.png", 13, -11, func=("health", 65)))
HP.append(tex("frame", A + "bar_frame.png", 12, -10))
health = asset("azerus_health", HP, comment=(
    "bottom-left, lower bar: health. Status variants (burning / freezing / poison / wither / absorption) use the\n"
    "same conditions and placeholders as the old Volya HUD; the first visible layer in the list wins."))

# Right panel origin = screen (250, 220) = centre+10 / 50 up. Bars 67x7 at x=2, icons at x=70.
MN = []
MN.append(text("value", "%fill-value%/%fill-max%", "mmohud-font", 35, -2))
MN.append(tex("icon", A + "icon_mana.png", 70, -1))
MN.append(tex("fill", A + "fill_mana.png", 3, -3, func=("mana", 65)))
MN.append(tex("frame", A + "bar_frame.png", 2, -2))
mana = asset("azerus_mana", MN, conds=(NOT_CREATIVE, "    - " + IS_ENERGY + " false hide # energy classes use azerus_energy"),
             comment="bottom-right, upper bar: mana (blue)")
EN = []
EN.append(text("value", "%fill-value%/%fill-max%", "mmohud-font", 35, -2))
EN.append(tex("icon", A + "icon_energy.png", 70, -1))
EN.append(tex("fill", A + "fill_energy.png", 3, -3, func=("mana", 65)))
EN.append(tex("frame", A + "bar_frame.png", 2, -2))
energy = asset("azerus_energy", EN, conds=(NOT_CREATIVE, "    - " + IS_ENERGY + " true hide # only for energy classes"),
               comment="bottom-right, upper bar for energy classes (MMOCore resource 'Энергия'): gold")
FD = []
FD.append(text("value", "%fill-value%/20", "mmohud-font", 35, -10))
FD.append(tex("icon", A + "icon_food.png", 70, -9,
              ["effect{e=hunger} true texture " + A + "icon_food_hunger.png",
               "placeholder{ph=%deluxemenus_meta_foodicon_BOOLEAN%;v=true;o==} true hide"]))
FD.append(tex("icon2", A + "icon_food2.png", 70, -9,
              ["effect{e=hunger} true texture " + A + "icon_food2_hunger.png",
               "placeholder{ph=%deluxemenus_meta_foodicon_BOOLEAN%;v=true;o==} false hide"],
              comment="bread icon - the /menu food-icon toggle of the old HUD"))
FD.append(tex("fill_hunger", A + "fill_hungry.png", 3, -11, ["effect{e=hunger} true hide"], func=("food", 65)))
FD.append(tex("fill", A + "fill_hunger.png", 3, -11, func=("food", 65)))
FD.append(tex("frame", A + "bar_frame.png", 2, -10))
food = asset("azerus_food", FD, comment="bottom-right, lower bar: hunger (orange; olive while the Hunger effect is on)")

XB = []
XB.append(tex("fill", A + "fill_xp.png", 1, -1, func=("rqexp", 90)))
XB.append(tex("frame", A + "xp_frame.png", 0, 0))
xpbar = asset("azerus_xp", XB, comment="bottom-centre XP bar right above the hotbar (RoyalQuestClasses exp, listener rqexp, as the old HUD)")
LV = []
LV.append(text("value", "<#80e848>" + BOTTOM_LEVEL, "mmohud-font", 10, -3))
LV.append(tex("badge", A + "level_badge.png", 0, 0))
level = asset("azerus_level", LV, comment="green level number between the two plates, above the XP bar (%hl_level% as the old HUD)")

AI = []
AI.append(tex("icon", A + "icon_air.png", 0, 0, ["placeholder{ph=%player_remaining_air%;v=%player_max_air%;o=<} true hide"]))
AI.append(tex("fill", A + "fill_air.png", 10, -2, ["placeholder{ph=%player_remaining_air%;v=%player_max_air%;o=<} true hide"], func=("air", 54)))
AI.append(tex("frame", A + "air_frame.png", 9, -1, ["placeholder{ph=%player_remaining_air%;v=%player_max_air%;o=<} true hide"]))
air = asset("azerus_air", AI, comment="air bubbles under water: small bar under the crosshair (only while air < max)")

with open(os.path.join(ROOT, "hud_assets/hud/azerus-hud.yml"), "w", encoding="utf-8") as f:
    f.write("### AZERUS HUD - slate blue + gold. Generated by mythichud-azerus/tools/gen_yaml.py (edit there).\n")
    f.write("### Textures: source-pack/assets/mythichud/textures/assets/azerus/*.png (tools/gen_textures.py).\n\n")
    for a in (player, player_xp, player_mp, location, panel_left, panel_right, armor, health, mana, energy,
              food, xpbar, level, air):
        f.write(a)

# ---------------------------------------------------------------------------------------------
# Skill bar: 6 slots 22x22, pitch 25, on a 153x28 backing plate. Element origin = backing top-left.
old = open(os.path.join(SNAP, "hud_assets/hud/skill_casting_bar-hud.yml"), encoding="utf-8").read()
ids = re.findall(r"placeholder\{ph=%mmocore_id_bound_1%;v=([A-Z0-9_]+)\} true texture (\S+)", old)
assert len(ids) >= 50, len(ids)
SB = []
SB.append(text("skillnames",
               re.search(r'skillnames:.*?\n\s+conditions:\n.*?\n\s+text: "(.*?)"\n', old, re.S).group(1).replace('"', '\\"'),
               "default", 76, 11, conds=["placeholder{ph=%mmocore_class_id%;v=HUMAN} false hide"],
               comment="name of the skill just cast (~2 s), same placeholder chain as the old bar"))
for i in range(1, 7):
    sx = 3 + 25 * (i - 1)
    # key hint (hotbar casting: keys skip the held slot) - same logic as the old input1..6 layers
    conds = ["placeholder{ph=%%mmocore_cooldown_bound_%d%%;v=noskill} false hide" % i,
             "placeholder{ph=%mmocore_is_casting%;v=true} true hide"]
    for held in range(0, i):
        conds.append("placeholder{ph=%%player_held_item_slot%%;v=%d} true texture %skey_%d.png" % (held, A, i + 1))
    SB.append(tex("key%d" % i, A + "key_%d.png" % i, sx + 16, -(3 + 15), conds,
                  comment="hotbar key for skill %d (shown in casting mode)" % i))
    SB.append(text("cooldown%d" % i, "%%math_0:_{utils_shortcut_noskillcd_check:%d}+1%%" % i, "mmohud-font",
                   sx + 11, -(3 + 7), conds=["placeholder{ph=%%mmocore_cooldown_bound_%d%%;v=0;o=>} true hide" % i]))
    SB.append(tex("cooldown%d_veil" % i, A + "skill_cooldown.png", sx + 3, -(3 + 3),
                  ["placeholder{ph=%%mmocore_cooldown_bound_%d%%;v=0;o=>} true hide" % i]))
    SB.append(tex("oom%d" % i, A + "skill_oom.png", sx + 3, -(3 + 3),
                  ["placeholder{1=%%utils_shortcut_mana_check:%d%%;o=>;v=%%mmocore_mana%%} true hide" % i],
                  comment="not enough mana for this skill"))
    SB.append(tex("skill%d" % i, A + "skill_empty.png", sx + 3, -(3 + 3),
                  ["placeholder{ph=%%mmocore_id_bound_%d%%;v=%s} true texture %s" % (i, sid, path) for sid, path in ids],
                  comment="skill icon by MMOCore skill id (icons: assets/skill_casting_bar/skills/*.png, unchanged)"))
    SB.append(tex("slot%d" % i, A + "skill_slot.png", sx, -3,
                  ["placeholder{ph=%mmocore_is_casting%;v=true} true texture " + A + "skill_slot_active.png"],
                  comment="gold corner brackets in casting mode"))
SB.append(tex("backing", A + "skill_backing.png", 0, 0))
skillbar = asset("azerus_skillbar", SB, conds=("    - placeholder{ph=%mmocore_class_id%;v=HUMAN} false hide # bar only for players with an RQ/MMOCore skill class",),
                 comment=(
                     "AZERUS SKILL BAR - 6 MMOCore skills in the slate+gold style. Same logic as skill_casting_bar-hud.yml:\n"
                     "icons by %mmocore_id_bound_N%, cooldown veil + seconds (%mmocore_cooldown_bound_N%), not-enough-mana tint,\n"
                     "hotbar key hints in casting mode (%player_held_item_slot% shift), gold brackets while %mmocore_is_casting%."))
with open(os.path.join(ROOT, "hud_assets/hud/azerus-skillbar.yml"), "w", encoding="utf-8") as f:
    f.write("### Generated by mythichud-azerus/tools/gen_yaml.py (edit there).\n\n")
    f.write(skillbar)

# ---------------------------------------------------------------------------------------------
def element(name, asset_name, px_, py_, ox, oy):
    return ("    %s:\n      asset: '%s'\n      position:\n        x: %s\n        y: %s\n"
            "      offset:\n        x: %d\n        y: %d\n" % (name, asset_name, px_, py_, ox, oy))


lay = "### Generated by mythichud-azerus/tools/gen_yaml.py. GUI 480x270 (1920x1080 @ GUI scale 4) reference.\n"
lay += "azerus-layout:\n  elements:\n"
lay += "    # top-left: origin 4 px from the left/top edge\n"
lay += element("player", "azerus_player", 0, 100, 4, -4)
lay += element("player_xp", "azerus_player_xp", 0, 100, 4, -4)
lay += element("player_mana", "azerus_player_mana", 0, 100, 4, -4)
lay += "    # top-centre plate (140 px wide)\n"
lay += element("location", "azerus_location", 50, 100, -70, -3)
lay += "    # bottom: hotbar is 182 px wide (centre-91..centre+91), 22 px tall\n"
lay += element("panel_left", "azerus_panel_left", 50, 0, -91, 50)
lay += element("armor", "azerus_armor", 50, 0, -91, 50)
lay += element("health", "azerus_health", 50, 0, -91, 50)
lay += element("panel_right", "azerus_panel_right", 50, 0, 10, 50)
lay += element("mana", "azerus_mana", 50, 0, 10, 50)
lay += element("energy", "azerus_energy", 50, 0, 10, 50)
lay += element("food", "azerus_food", 50, 0, 10, 50)
lay += element("level", "azerus_level", 50, 0, -10, 47)
lay += element("experience", "azerus_xp", 50, 0, -91, 29)
lay += element("air", "azerus_air", 50, 50, -32, -12)
with open(os.path.join(ROOT, "layouts/azerus-layout.yml"), "w", encoding="utf-8") as f:
    f.write(lay)
with open(os.path.join(ROOT, "layouts/azerus-skillbar-layout.yml"), "w", encoding="utf-8") as f:
    f.write("### Generated by mythichud-azerus/tools/gen_yaml.py\nazerus-skillbar-layout:\n  elements:\n")
    f.write("    # 153 px plate centred above the hotbar; slots at 172..193 from the top on a 270 px GUI,\n"
            "    # above the action bar text (198..205) and the held-item name (211..218)\n")
    f.write(element("skills", "azerus_skillbar", 50, 0, -76, 101))

# config.yml: production copy with the default layout list switched to the Azerus layouts.
cfg = open(os.path.join(SNAP, "config.yml"), encoding="utf-8").read()
new_cfg = cfg.replace("  default:\n  - mmohud-layout\n  - partyhud-mmo-layout\n  - skill_casting_bar-layout\n",
                      "  default:\n  - azerus-layout\n  - partyhud-mmo-layout\n  - azerus-skillbar-layout\n")
assert new_cfg != cfg, "config.yml layout block not found"
with open(os.path.join(ROOT, "config.yml"), "w", encoding="utf-8") as f:
    f.write(new_cfg)
print("ok")
