import json
import sys

dest, gui, pack, lang = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
bg = sys.argv[5] if len(sys.argv) > 5 else "0.5"
packs = ["vanilla"] if pack == "none" else ["vanilla", "file/" + pack]
opts = {
    "version": "4671",
    "onboardAccessibility": "false",
    "skipMultiplayerWarning": "true",
    "joinedFirstServer": "true",
    "hideServerAddress": "false",
    "narrator": "0",
    "narratorHotkey": "false",
    "tutorialStep": "none",
    "telemetryOptInExtra": "false",
    "hideSplashTexts": "true",
    "realmsNotifications": "false",
    "guiScale": gui,
    "fullscreen": "false",
    "pauseOnLostFocus": "false",
    "renderDistance": "3",
    "simulationDistance": "5",
    "maxFps": "30",
    "enableVsync": "false",
    "graphicsMode": "0",
    "renderClouds": "\"false\"",
    "particles": "2",
    "entityShadows": "false",
    "soundDevice": "\"\"",
    "soundCategory_master": "0.0",
    "lang": lang,
    "resourcePacks": json.dumps(packs),
    "incompatibleResourcePacks": "[]",
    "chatVisibility": "0",
    "textBackgroundOpacity": bg,
    "backgroundForChatOnly": "true",
    "fov": "0.0",
    "advancedItemTooltips": "false",
    "attackIndicator": "1",
    "showSubtitles": "false",
    "darkMojangStudiosBackground": "false",
    "rawMouseInput": "false",
    "autoJump": "false",
    "key_key.playerlist": "key.keyboard.tab",
    "key_key.screenshot": "key.keyboard.f2",
    "key_key.inventory": "key.keyboard.e",
}
with open(dest, "w") as f:
    for k, v in opts.items():
        f.write(k + ":" + v + "\n")
