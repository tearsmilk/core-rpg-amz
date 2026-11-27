# Задача для агента мода Hoka Odyssey: части модели гуманоида от модулей (`onModelParts`)

В клиентское ядро мода (`client/core`, сейчас 0.69.0, коммит `8097358` в `SakuraMesd/hokaodyssey`) нужно добавить
один необязательный крючок. Модуль `guns` с ним показывает игрока с оружием от третьего лица как в TACZ: винтовка
двумя руками у плеча, поза прицеливания, анимация перезарядки (свой игрок в F5 и чужие игроки).

**Зачем.** Сейчас у модулей нет способа повернуть руки, голову, тело или ноги модели игрока: `onEntityPose` даёт только
поворот всей модели и углы тела/головы. Ванильные позы рук (BOW, CROSSBOW_HOLD) двух рук «у плеча» и перезарядки не
дают, а CROSSBOW_HOLD игра ставит только настоящему заряженному арбалету.

**Что сделать.** Применить патч `hoka-odyssey/docs/mod-additions/model-parts-0.69.patch` из репозитория
`tearsmilk/core-rpg-amz` (ветка `claude/gifted-turing-g07zrr`) к корню `hokaodyssey`. Проверка: `git apply --check`
на `8097358` (старые патчи `model-parts-0.64.patch` — против `63ba753`, `model-parts-0.55.patch` — против `9e8351b`). Патч меняет 7 файлов в `client/core`:

1. `api/ClientContext.java`: default-метод `onModelParts(ModelPartsHook)` (в старом ядре пустой — модуль это видит) и
   интерфейс `ModelPartsHook { void apply(Entity entity, HumanoidModel<?> model, HumanoidRenderState state); }`.
   Остальные методы и записи не меняются — уже собранные модули работают.
2. `Hooks.java`: список `PARTS` (снимается при выгрузке модуля), ключ состояния `ENTITY_ID`
   (`RenderStateDataKey<Integer>`, `hoka_odyssey:entity_id`), `Hooks.hasParts()` и `Hooks.parts(model, state)` —
   находит сущность по id в клиентском мире и зовёт крючки (через `safe`, как остальные).
3. `ModuleManager.java` (`Ctx`): одна строка — регистрация крючка.
4. `mixin/HumanoidPartsMixin.java` (новый, `@Mixin(HumanoidModel.class)`): `setupAnim(HumanoidRenderState)` TAIL →
   `Hooks.parts(this, state)`. Это конец расстановки поз игры: `PlayerModel.setupAnim(AvatarRenderState)` ставит
   видимость частей и последним зовёт `super.setupAnim` (проверено `javap -c` по 1.21.11). Модели брони
   расставляются тем же вызовом с тем же состоянием — броня идёт за частями. `ItemInHandLayer` берёт руку через
   `translateToHand` — предмет идёт за рукой сам.
5. `mixin/EntityTiltMixin.java`: одна строка в начале `extractRenderState` TAIL — если есть крючки, положить id
   сущности в состояние (`s.setData(Hooks.ENTITY_ID, e.getId())`).
6. `hoka_odyssey.mixins.json`: новый миксин.
7. `build.gradle.kts`: версия 0.70.0.

Без крючков миксин сразу выходит (проверка пустого списка), id в состояние не кладётся. Имена проверены `javap` по
Minecraft 1.21.11 (имена Mojang): `HumanoidModel.setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V`
(у `PlayerModel` — мост `setupAnim(HumanoidRenderState)` → `setupAnim(AvatarRenderState)` → `super`), поля `head`,
`body`, `rightArm`, `leftArm`, `rightLeg`, `leftLeg` (public final), `ModelPart.xRot/yRot/zRot/x/y/z` (public).

**Про `HeldArmPoseMixin` (0.69).** В 0.69 на тот же `HumanoidModel.setupAnim` TAIL уже висит `HeldArmPoseMixin`
(позы рук предметов-моделей из `held.json`). Наш `HumanoidPartsMixin` с ним не спорит: оружие модуля `guns` в
`held.json` не прописано, так что для него `HeldModels.pose` ничего не делает. Если админ всё же пропишет туда
оружие — части модели поставит тот миксин, что отработает последним; лучше оружие в `held.json` не добавлять.

**Проверено.** На чистой выгрузке `8097358` с патчем полная сборка `client` (ядро + все модули из `client/modules`, с `gen-api`) проходит
(нужен `client/libs/sodium-fabric-0.8.12+mc1.21.11.jar`, его нет в git). На стенде (dev-ключ, тот же патч): в логе
клиента `guns: … поза тела от третьего лица — есть (onModelParts)`, второй клиент видит позу, прицел и перезарядку
первого; F5 — то же у себя; модули `spaceflight` (onEntityPose) и `guns` вместе работают.

**Как проверить в игре.** Мод 0.70.0; на сервере модули `guns` (сервер и клиент) из `tearsmilk/core-rpg-amz`, затем
`hogun install` (обновит положение модели в руке у hoitems). Два игрока с модом, у одного АКМ:
- второй видит: винтовка двумя руками у плеча, корпус развёрнут, ноги в стойке; ПКМ — поза прицела (голова к
  прикладу); R — перезарядка (левая рука к магазину и обратно); выстрел — небольшая отдача корпуса;
- F5 у себя — то же; без оружия в руке — обычная поза игры;
- в броне — броня на руках идёт за руками.

**Версию** ядра поднять до 0.70.0 и выложить jar на сервер: `module/clientmods/core/`.
