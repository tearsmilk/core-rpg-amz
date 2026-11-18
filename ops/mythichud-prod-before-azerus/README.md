# Бэкап MythicHUD перед Azerus HUD (снят с прода 18.11.2026)

Новый HUD — `mythichud-azerus/` (там же порядок заливки). Здесь — продовые файлы, которые заливка заменяет:

| файл | на проде | sha256 / примечание |
|---|---|---|
| `plugins/MythicHUD/config.yml` | `/plugins/MythicHUD/config.yml` | `layout.default`: `mmohud-layout`, `partyhud-mmo-layout`, `skill_casting_bar-layout` |
| `plugins/Nexo/pack/external_packs/hl-resourcepack.zip` | `/plugins/Nexo/pack/external_packs/hl-resourcepack.zip` | `eb17855e6664286f45d4eb3b92c85cb89df589090e48d791d323edca8a8eb7a4` (от 16.11) |
| `hotbar-sprites/assets/minecraft/textures/gui/sprites/hud/hotbar*.png` | внутри zip | старый хотбар Volya, для справки |

Остальное заливка только **добавляет** (`azerus*.yml`, `source-pack/.../azerus/`, `source-pack/assets/minecraft/
textures/gui/sprites/hud/hotbar*.png`). Старые `mmohud.yml`, `skill_casting_bar-hud.yml`, их раскладки и
текстуры остаются на сервере как были.

## Откат

1. Залить `plugins/MythicHUD/config.yml` → `/plugins/MythicHUD/config.yml`.
2. Удалить на сервере `/plugins/MythicHUD/hud_assets/hud/azerus-hud.yml`, `azerus-skillbar.yml`,
   `/plugins/MythicHUD/layouts/azerus-layout.yml`, `azerus-skillbar-layout.yml`, и
   `/plugins/MythicHUD/source-pack/assets/minecraft/textures/gui/sprites/hud/` (там только хотбар Azerus).
   Папку `source-pack/assets/mythichud/textures/assets/azerus/` можно оставить (на шрифты не влияет).
   Удалять yml обязательно: MythicHUD раздаёт символы шрифтов по всем раскладкам подряд, и с раскладками
   azerus символы старого HUD могут сдвинуться относительно бэкапа zip.
3. Консоль: `mythichud reload`.
4. Залить `plugins/Nexo/pack/external_packs/hl-resourcepack.zip` → `/plugins/Nexo/pack/external_packs/hl-resourcepack.zip`
   (сверить sha256 выше), консоль `nexo reload pack`.
5. Проверка, что сгенерированные шрифты совпали с бэкапом:
   `python3 -I mythichud-azerus/tools/build_hl_pack.py fetch --out /tmp/bp && python3 -I mythichud-azerus/tools/build_hl_pack.py fontdiff /tmp/bp ops/mythichud-prod-before-azerus/plugins/Nexo/pack/external_packs/hl-resourcepack.zip`
   → `changed: 0` (в бэкапе есть 5 лишних старых шрифтов `mmohud-layout/.../food|armor/text_*` — это норма,
   они были и до заливки). Если `changed` > 0 — собрать zip заново:
   `build_hl_pack.py build --built /tmp/bp --base <бэкап zip> --out /tmp/hl.zip` и залить его.
