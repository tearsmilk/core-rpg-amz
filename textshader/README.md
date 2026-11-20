# textshader

Анимированный текст без модов: плагин красит текст особым цветом, а core-шейдер ресурспака (через Nexo) рисует по
этому цвету эффект - тряску, волну, радугу, огонь, глитч, печатную машинку и еще 49 штук. Работает везде, где текст
рисуется в интерфейсе игрока: чат, таб, заголовки, табло, боссбар, названия и описания предметов, меню. В мире
(text_display, ники над головой) шейдер не анимирует, а заменяет код на обычный читаемый цвет.

Автор: tearsmilk. Сервер: Leaf 1.21.11 (Paper API), Java 21, Nexo 1.30-dev. PlaceholderAPI - не обязателен.

Перенос системы textfx из magicruruka: протокол цвета и номера эффектов 0-45 совместимы с исходными шейдерами,
код переписан. Бюсты, аватары и социальные функции не перенесены.

## Протокол цвета

- Текст: `R = 214`, `G = эффект * 4 | биты 2-3 номера буквы`, `B = биты 4-5 номера << 6 | цвет * 4 | биты 0-1 номера`.
- Тень (ваниль: цвет * 0.25): `R = 53`, `G = эффект`, `B = цвет` - шейдер узнает и тень.
- Номер буквы (0-63) нужен эффектам с индексом: `type` (печатная машинка), `bounce`, `scramble`. Плагин нумерует
  буквы сам (`FxText`), после 63 номер не растет, `\n` начинает счет заново.
- Цвета палитры (16): white, red, orange, yellow, lime, green, aqua, cyan, blue, purple, magenta, pink, gold, gray,
  black, brown. У эффектов со своими цветами (радуга, флаги, золото, новые matrix/frost/...) цвет кроме white
  перекрашивает узор по яркости.
- Номера эффектов не менять: они хранятся у игроков и в чужих конфигах. Новые - только в конец (до 63).

## Эффекты

0-45 из исходника: shake, wave, jump, flip, skew, pulse, rainbow, gradient, shimmer, chroma, metal, fire, fade, blink,
glow, outline, thin, pride, lesbian, mlm, bi, trans, pan, ace, aro, enby, rainbowwave, neon, glitch, ice, holo, type,
heart, gold, galaxy, quake, swing, echo, jeb, toxic, vampire, ghost, lava, hue, wavyoutline, typecursor (служебный,
скрыт).

Новые (46-55):

| id | ключ | что делает |
|----|------|------------|
| 46 | `sparkle` | Искры: вокруг букв вспыхивают и гаснут четырехлучевые звездочки |
| 47 | `matrix` | Матрица: по каждой букве сверху вниз бежит яркая зеленая "капля" со шлейфом |
| 48 | `frost` | Иней: ледяной градиент с трещинками, на верхушках букв нарастает и тает иней |
| 49 | `electric` | Электро: голубое мерцание, дрожь, искры по контуру и редкие молнии вдоль строки |
| 50 | `sunset` | Закат: желто-оранжево-пурпурный градиент, переливается волной, блик солнца |
| 51 | `ocean` | Океан: волна с пеной бежит по строке, глубина темнеет книзу, буквы покачиваются |
| 52 | `bounce` | Падение с отскоком: буквы по очереди падают сверху и подпрыгивают (с номером буквы) |
| 53 | `scramble` | Расшифровка: вместо букв мелькает шум, буквы "расшифровываются" слева направо |
| 54 | `extrude` | Объём: 3D-выдавливание букв, угол медленно качается |
| 55 | `sweep` | Подчёркивание: под текстом линия, по ней бежит светлая вспышка, буквы подсвечиваются |

Превью всех эффектов - `preview/` (`fx_<ключ>.gif`, `strip_<ключ>.png` - 6 кадров, `sheet.png` - все эффекты,
`sheet_new.png` - только новые, `world_plain.png` - текст в мире).

## Команды (`/textshader`, алиас `/ts`)

| команда | права | что делает |
|---------|-------|------------|
| `/ts` или `/ts menu [chat\|nick]` | use | меню выбора: страницы эффектов (названия - живые превью), ЛКМ - выбрать, ПКМ - выбрать с цветом, переключатель чат/ник, цвет, выключить |
| `/ts set <эффект> [цвет]`, `/ts set off` | use + effect | эффект всех своих сообщений в чате |
| `/ts nick <эффект> [цвет]`, `/ts nick off` | use + effect | эффект ника (таб, плейсхолдеры) |
| `/ts off [chat\|nick]` | use | выключить (без аргумента - оба) |
| `/ts preview <эффект> [текст]` | use | примеры в чат: белым и тремя цветами + тег для копирования |
| `/ts list` | use | все эффекты (нажать - превью) и цвета |
| `/ts code <эффект> [цвет]` | use | код цвета: `#D6....`, для MiniMessage и legacy |
| `/ts give-name <эффект> [цвет] [текст]` | admin | переименовать предмет в руке (без текста - текущее имя) |
| `/ts title <игрок\|*> <эффект[:цвет]> <текст> [\| подзаголовок]` | admin | заголовок с эффектом |
| `/ts broadcast <эффект[:цвет]> <текст>` | admin | сообщение всем (внутри можно теги) |
| `/ts player <игрок> <chat\|nick> <эффект\|off> [цвет]` | admin | выставить игроку |
| `/ts reload` | admin | перечитать config.yml |

Эффект можно писать как `wave red` или `wave:red`.

Права: `textshader.use` (по умолчанию всем), `textshader.admin` (оп), `textshader.effect.<ключ>` - каждый эффект,
`textshader.effect.*` - все. По умолчанию все эффекты доступны всем (`effects-default: true`), при `false` - только
опам, остальным выдавать через LuckPerms.

В чате игрок может писать теги: `<wave>текст</wave>`, `<wave:red>текст`, `<fx:rainbow>`, `<textshader:fire:gold>`,
`</>` закрывает последний. Неизвестные теги остаются текстом. Тег с недоступным эффектом заменяется выбранным
эффектом игрока.

Выбор игрока хранится в PDC игрока (`textshader:chat`, `textshader:nick`, строка `эффект:цвет`), переживает
рестарт и не требует файлов.

## Для других плагинов

- MiniMessage: резолвер `TextShaderApi.tags()` дает теги `<fx:wave>`, `<fx:wave:red>`, `<textshader:type:aqua>`
  (у type/bounce/scramble буквы нумеруются сами, через вложенные теги тоже).
- Готовый текст: `TextShaderApi.render("Текст", "wave", "red")` - Component, `legacy(...)` - строка `&#D6....`,
  `miniMessage(...)` - строка с `<#D6....>`, `code("wave", "red")` - число RGB, `nick(player)`.
- Плагины без зависимости могут просто ставить цвет `#D60404` (это wave + red), коды - `/ts code`.
- PlaceholderAPI (если стоит): `%textshader_nick%` (ник с эффектом, legacy `&#`), `%textshader_nick_mm%`
  (MiniMessage), `%textshader_nick_color%`, `%textshader_nick_effect%`, `%textshader_chat_effect%`,
  `%textshader_chat_color%`, `%textshader_fx_<эффект>[_<цвет>]%` (ник с указанным эффектом), `%textshader_fxmm_...%`,
  `%textshader_code_<эффект>[_<цвет>]%` (`&#D6....`), `%textshader_hex_...%` (`#D6....`).

## config.yml

`chat.enabled`, `chat.tags` - эффект и теги в чате. `nick.tab` - ставить ник с эффектом в таб (playerListName),
`nick.display-name` - еще и displayName (по умолчанию нет, чтобы не мешать чат-плагинам). `effects-default`.
`menu-sample`, `preview-text`. `messages` - все сообщения (MiniMessage, русские, только короткие дефисы).

## Ресурспак

- `pack/assets/minecraft/shaders/core/rendertype_text.{vsh,fsh}` - ванильные 1.21.11 + хук (импорт и одна строка в
  main), `rendertype_text_see_through.vsh` - то же для ников сквозь стены (только цвет в мире). Фрагментный
  `rendertype_text_see_through.fsh` и остальные `rendertype_text_*` (background, intensity) не трогаются.
- `pack/assets/textshader/shaders/include/`: `ts_common.glsl` (палитра, таблицы, декодирование, отступ квада),
  `ts_vertex.glsl` (GUI: квад буквы расширяется на 4 GUI px, у bounce - на 12, углы 0 и 2 передаются во фрагмент;
  мир: цвет вершины = цвет палитры), `ts_fragment.glsl` (восстановление координат внутри буквы, сборка цвета),
  `ts_effects.glsl` (все эффекты, `ts_apply` по номеру).
- `rendertype_item_entity_translucent_cull.*` (coreshaders) не пересекается с нами.
- Ограничения: курсив у буквы с эффектом не наклоняется (плагин ставит italic false), шрифты TrueType
  (rendertype_text_intensity) не анимируются. Цвет тени (R = 53) может совпасть с тенью редкого цвета с R 212-215.

## Сборка и проверки

```
cd textshader
./gradlew --offline build                      # тесты + build/libs/textshader-1.0.0.jar
python3 -I tools/glsl_check.py                 # glslangValidator: компиляция и линковка GLSL 330 и 410, дифф с ванилью
python3 -I tools/preview.py                    # превью настоящими шейдерами (moderngl, EGL) в preview/
python3 -I tools/preview.py --only ocean,sweep # отдельные эффекты
python3 -I tools/preview.py --world            # текст в мире
```

Тесты: коды цвета (все эффекты x цвета x номера, тень), разбор тегов чата и MiniMessage, нумерация печатной машинки,
сверка таблиц шейдера с Java (число эффектов, свои цвета, палитра, ветки `ts_apply`), запрет комментариев в Java и
шейдерах и длинных тире во всем проекте.

## Деплой

1. Скопировать содержимое `textshader/pack/assets/` в `plugins/Nexo/pack/assets/` (папки `minecraft/shaders/core` и
   `textshader/shaders/include`). Перед этим проверить, что в `plugins/Nexo/pack/assets/minecraft/shaders/core/` нет
   чужих `rendertype_text.vsh`, `rendertype_text.fsh`, `rendertype_text_see_through.vsh` - если есть, их надо
   объединить, а не перезаписывать.
2. Положить `build/libs/textshader-1.0.0.jar` в `plugins/`.
3. Рестарт сервера (Nexo пересоберет пак, Hermes раздаст). Без пака игроки увидят обычный цвет `#D6....` вместо
   анимации - плагин работает, но эффектов не видно.
4. Проверить в игре: `/ts preview sparkle`, `/ts list`, `/ts`. Если включена обфускация SIMPLE - открыть zip, который
   раздает Hermes, и убедиться, что `assets/minecraft/shaders/core/rendertype_text.*` на месте, а импорт
   `textshader:ts_vertex.glsl` находит файл (Nexo шейдеры не переименовывает, у coreshaders тот же способ работает).
5. Для таба через TAB-плагин (если он есть): в формате имени `%textshader_nick%`; без TAB плагин ставит ник в таб сам.
