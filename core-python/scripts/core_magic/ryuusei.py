# 流星 — «Огненный шар», третье заклинание посоха: зажать ПКМ на 3 с — высоко в небе над целью раскрывается огромный
# горизонтальный огненный круг (ItemDisplay, модель azerus:moon_staff_fire_circle, спрайты K11..14); отпустить — из
# круга на землю вокруг цели падают кометы (ItemDisplay, модель azerus:moon_staff_comet, спрайты K15..19): взрыв
# (только частицы и звук — ни блоков, ни огня в мире), урон, поджог и лёгкое отталкивание в радиусе hit-radius.
#
# ПОТОКИ.
#   * 流星詠唱 (заряд) — поток заклинателя: прицел (лучи по блокам и поиск существ — только в пределах пути,
#     принадлежащего его региону, как у луча), огненный круг (дисплей в точке C; рождается, растёт, догоняет цель и
#     меняет цвет только с проверкой amazake.owns), частицы, звук, полоска.
#   * 流星群 (залп после отпускания) — от заклинателя не зависит (как поднятый барьер). Огненный круг трогает только
#     поток региона C (run_in_region(C)), каждую комету — только поток региона её точки удара I (run_in_region(I)):
#     луч до земли, рождение дисплея, падение, след, удар. Дисплей кометы стоит в I и НЕ двигается (полёт — перенос
#     трансформации с интерполяцией), поэтому никогда не пересекает границу регионов.
#   * Урон — в потоке региона I, только существам, которыми этот поток владеет (kousen.狙える), и только если поток
#     владеет и самим заклинателем (иначе удар только виден: смерть цели начисляет заклинателю статистику и
#     достижения — трогать чужого игрока из чужого региона нельзя). Счёт попаданий по существу (max-hits-per-entity)
#     — общий словарь залпа под threading.Lock (удары идут в разных потоках).
#   * Барьеры (kekkai.py) кометы НЕ останавливают.
#
# Ничего не ждём и не блокируем; каждая задача сначала проверяет kyoutsuu.終了済み и флаг 止 залпа. Дисплеи не
# сохраняются в мир; @on_unload (main.終了 → 片付け) убирает их kyoutsuu.消す, @on_load — меткой py_core_magic:fx.

import math
import random
import threading

import java
import amazake

import keisan
import kyoutsuu
import mahoujin
import kousen
import dougu
from kyoutsuu import Location, Vector, BoundingBox, FluidCollisionMode
from settei import f32

#: Перезарядка «Огненного шара»: str(UUID) → момент готовности (мс, kyoutsuu.今ms). Своя, без ванильной «шторки».
流星冷却 = {}
#: Живые залпы: id → 流星群.
群 = {}
_番号 = [0]
_番号鍵 = threading.Lock()
#: Вспышка огненного круга при отпускании (тиков).
閃光tick = 3
#: Разброс точки старта кометы по X и Z (блоков).
出発揺れ = 0.3
_下 = Vector(0.0, -1.0, 0.0)
_上 = Vector(0.0, 1.0, 0.0)


def _新番号():
    with _番号鍵:
        _番号[0] += 1
        return _番号[0]


def 冷却残り(uid):
    終 = 流星冷却.get(uid)
    if 終 is None:
        return 0.0
    r = keisan.冷却残り(kyoutsuu.今ms(), 終)
    if r <= 0.0:
        流星冷却.pop(uid, None)
    return r


def _冷やす(uid, 秒):
    if 秒 > 0.0:
        流星冷却[uid] = kyoutsuu.今ms() + int(秒 * 1000.0)
    else:
        流星冷却.pop(uid, None)


def 表示数():
    return sum(len(g.表示) for g in list(群.values()))


def 片付け():
    """@on_unload: все залпы — стоп, их дисплеи (огненный круг и кометы) — kyoutsuu.消す, всё забыть."""
    for g in list(群.values()):
        g.止 = True
        for d in list(g.表示.values()):
            kyoutsuu.消す(d)
        g.表示.clear()
    群.clear()
    流星冷却.clear()


def 降ろされた(entities):
    """EntitiesUnloadEvent: дисплеи залпа из выгружаемого чанка (непостоянные — их не станет) — забыть."""
    if not 群:
        return
    for e in entities:
        try:
            if not java.instanceof(e, kyoutsuu.ItemDisplayType):
                continue
            dd = amazake.data(e)
            if not dd.has(kyoutsuu.流星鍵):
                continue
            g = 群.get(int(dd.get_string(kyoutsuu.流星鍵)))
            if g is not None:
                g.表示.pop(str(e.getUniqueId()), None)
        except BaseException:
            pass


def _古いのを掃く(今):
    """Страховка от утечки: залп, чья последняя задача потерялась (регион выгружен), — забыть, дисплеи убрать."""
    for id, g in list(群.items()):
        if 今 < g.期限ms:
            continue
        g.止 = True
        群.pop(id, None)
        for d in list(g.表示.values()):
            kyoutsuu.消す(d)
        g.表示.clear()


# ------------------------------------------------------------------------------------------------ мелочи

def _帯(p, cfg, 鍵, 生=None, **素):
    t = cfg.文(鍵)
    if 生:
        for k, v in 生.items():
            t = t.replace("<" + k + ">", v)
    if t.strip():
        amazake.actionbar(p, t, **素)


def _場所(world, x, y, z):
    """Место дисплея: yaw 0, pitch 0 — оси дисплея = оси мира."""
    return Location(world, float(x), float(y), float(z), f32(0.0), f32(0.0))


def _読込済(world, x, z):
    try:
        return bool(world.isChunkLoaded(int(math.floor(x)) >> 4, int(math.floor(z)) >> 4))
    except BaseException:
        return False


def _持つ(world, x, y, z):
    """Чанк загружен и поток владеет местом."""
    return _読込済(world, x, z) and bool(amazake.owns(Location(world, float(x), float(y), float(z))))


def _散らす列(world, 列, x, y, z):
    for 粒 in 列:
        kyoutsuu.散らす(world, 粒, x, y, z)


def _距離(a, b):
    return math.sqrt((a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 + (a[2] - b[2]) ** 2)


# ------------------------------------------------------------------------------------------------ прицел

def 狙う(player, cfg):
    """Поток заклинателя: (P, C) — точка цели на земле и центр огненного круга (кортежи), или None («здесь круг не
    раскроется»). Лучи и поиск существ — только по пути, принадлежащему региону заклинателя (как у луча)."""
    m = cfg.流星
    光 = cfg.光
    eye = player.getEyeLocation()
    world = eye.getWorld()
    E = (eye.getX(), eye.getY(), eye.getZ())
    d = keisan.視線(eye.getYaw(), eye.getPitch())
    dV = Vector(d[0], d[1], d[2])
    自分 = str(player.getUniqueId())

    def 持つか(cx, cz):
        return world.isChunkLoaded(cx, cz) and amazake.owns(Location(world, cx * 16.0 + 8.0, E[1], cz * 16.0 + 8.0))

    Lu = keisan.使える長さ(E, d, m.射程, 光.片長, m.狙半径 + 1.0, 持つか)
    tB = None
    上面 = False
    法線 = None
    if Lu > 0.0:
        hit = world.rayTraceBlocks(eye, dV, Lu, FluidCollisionMode.NEVER, True)
        if hit is not None:
            hp = hit.getHitPosition()
            tB = min(Lu, _距離((hp.getX(), hp.getY(), hp.getZ()), E))
            f = hit.getHitBlockFace()
            if f is not None:
                上面 = f.name() == "UP"
                法線 = (float(f.getModX()), float(f.getModY()), float(f.getModZ()))
    # существа на [0, min(tB, Lu)]
    tE = None
    足 = None
    範囲 = Lu if tB is None else min(tB, Lu)
    if 範囲 > 0.0:
        候補 = []
        見た = set()
        for a, b in keisan.区切る(範囲, 光.片長):
            箱 = keisan.区間の箱(E, d, a, b, m.狙半径)
            for e in world.getNearbyEntities(BoundingBox(箱[0], 箱[1], 箱[2], 箱[3], 箱[4], 箱[5])):
                uid = str(e.getUniqueId())
                if uid in 見た:
                    continue
                見た.add(uid)
                try:
                    if not kousen.狙える(e, True, 自分):
                        continue
                    bb = e.getBoundingBox()
                    候補.append((uid, (bb.getMinX(), bb.getMinY(), bb.getMinZ(), bb.getMaxX(), bb.getMaxY(),
                                       bb.getMaxZ())))
                except BaseException as ex:
                    amazake.log.debug("цель огненного шара: " + str(ex))
        当 = keisan.当たり(E, d, 範囲, 候補, 自分, m.狙半径)
        if 当:
            tE, uid = 当[0]
            b = dict(候補)[uid]
            足 = ((b[0] + b[3]) / 2.0, b[1], (b[2] + b[5]) / 2.0)
    P, 落とす = keisan.狙い(E, d, Lu, tB, 上面, tE, 足, 法線)
    if 落とす and _持つ(world, P[0], P[1], P[2]):
        hit = world.rayTraceBlocks(Location(world, P[0], P[1], P[2]), _下, m.地探, FluidCollisionMode.NEVER, True)
        if hit is not None:
            hp = hit.getHitPosition()
            P = (P[0], hp.getY(), P[2])
    天井 = None
    if _持つ(world, P[0], P[1], P[2]):
        hit = world.rayTraceBlocks(Location(world, P[0], P[1] + 0.1, P[2]), _上, m.高さ + m.天井隙,
                                   FluidCollisionMode.NEVER, True)
        if hit is not None:
            天井 = hit.getHitPosition().getY()
    Cy = keisan.炎陣の高さ(P[1], m.高さ, 天井, world.getMaxHeight(), m.天井隙, m.最低)
    if Cy is None:
        return None
    C = (P[0], Cy, P[2])
    if not _持つ(world, C[0], C[1], C[2]):
        return None
    return P, C


# ------------------------------------------------------------------------------------------------ огненный круг

class 炎陣:
    """Огненный круг одного заклинания. Пока идёт заряд — только поток заклинателя (каждый раз amazake.owns);
    после отпускания дисплей отдаётся залпу (手放す) и его трогает только поток региона C."""

    __slots__ = ("player", "主", "cfg", "d", "場所", "R")

    def __init__(self, player, cfg):
        self.player = player
        self.主 = str(player.getUniqueId())
        self.cfg = cfg
        self.d = None
        self.場所 = None
        self.R = -1

    def 大きさ(self, charge):
        m = self.cfg.流星
        return keisan.炎陣の大きさ(m.直径, m.始倍, charge)

    @staticmethod
    def 変形(s):
        return kyoutsuu.変形Q(0.0, 0.0, 0.0, keisan.炎陣回転, s)

    def 出す(self, world, C, charge, 残り):
        """Дисплей в C с размером заряда charge и одна интерполяция роста до полного за 残り тиков."""
        if kyoutsuu.終了済み[0] or not _持つ(world, C[0], C[1], C[2]):
            return False
        m = self.cfg.流星
        at = _場所(world, C[0], C[1], C[2])
        self.R = keisan.充填R(charge)
        d = mahoujin.生む(at, kyoutsuu.効果品(m.円模型, self.R), self.変形(self.大きさ(charge)), m.視距離,
                         "meteor-circle", self.主)
        d.setTeleportDuration(m.移動時間)
        if kyoutsuu.終了済み[0]:
            d.remove()
            return False
        self.d = d
        self.場所 = at
        if 残り > 0:
            全 = self.大きさ(1.0)

            def 伸ばす():
                if d is self.d and d.isValid() and amazake.owns(d):
                    d.setInterpolationDelay(0)
                    d.setInterpolationDuration(max(1, int(残り) - 1))
                    d.setTransformation(self.変形(全))

            amazake.run_once_for_player(self.player, 伸ばす, delay=1)
        return True

    def 追う(self, world, C, charge, 残り):
        """Цель сдвинулась: дисплей телепортом в новый C (если поток владеет и им, и новым местом), иначе — на месте.
        Дисплей пропал — поставить заново."""
        d = self.d
        if d is None or not d.isValid():
            self.d = None
            return self.出す(world, C, charge, 残り)
        if not amazake.owns(d):
            return False
        at = self.場所
        m = self.cfg.流星
        if _距離((at.getX(), at.getY(), at.getZ()), C) <= m.追従距離:
            return False
        if not _持つ(world, C[0], C[1], C[2]):
            return False
        to = _場所(world, C[0], C[1], C[2])
        d.teleportAsync(to)
        self.場所 = to
        return True

    def 色(self, charge):
        d = self.d
        R = keisan.充填R(charge)
        if d is None or R == self.R or not d.isValid() or not amazake.owns(d):
            return
        self.R = R
        d.setItemStack(kyoutsuu.効果品(self.cfg.流星.円模型, R))

    def 手放す(self):
        d = self.d
        self.d = None
        return d, self.場所

    def 散る(self):
        """Срыв: круг сжимается за fizzle-ticks и исчезает."""
        d, 場所 = self.手放す()
        if d is None or not d.isValid():
            return
        if not amazake.owns(d):
            kyoutsuu.消す(d)
            return
        m = self.cfg.流星
        d.setInterpolationDelay(0)
        d.setInterpolationDuration(m.消散)
        d.setTransformation(self.変形(0.01))
        mahoujin.後で消す(d, 場所, m.消散 + 1)

    def 即消す(self):
        d, _ = self.手放す()
        if d is not None:
            kyoutsuu.消す(d)


# ------------------------------------------------------------------------------------------------ заряд

class 流星詠唱:
    """Заряд «Огненного шара» (поток заклинателя). Интерфейс как у main.詠唱中 и kekkai.結界詠唱: uid, cfg, ticks,
    終わった, 失敗秒, 始める(), 見張り(), 放す(held), 終える(方法, 冷却秒=None), 表示一覧()."""

    __slots__ = ("player", "uid", "cfg", "詠唱表", "ticks", "task", "世界", "終わった", "陣", "準備", "準備tick",
                 "色tick", "唸りtick", "棒", "失敗秒", "P", "C", "rng")

    def __init__(self, player, cfg, ticks, 詠唱表):
        self.player = player
        self.uid = str(player.getUniqueId())
        self.cfg = cfg
        self.詠唱表 = 詠唱表
        self.ticks = ticks
        self.task = None
        self.世界 = player.getWorld().getName()
        self.終わった = False
        self.陣 = 炎陣(player, cfg)
        self.準備 = False
        self.準備tick = 0
        self.色tick = ticks
        self.唸りtick = ticks
        self.棒 = None
        self.失敗秒 = cfg.流星.失敗秒
        self.P = None
        self.C = None
        self.rng = random.Random()

    def 表示一覧(self):
        d = self.陣.d
        return [] if d is None else [d]

    def _狙い直す(self):
        try:
            return 狙う(self.player, self.cfg)
        except BaseException as e:
            amazake.log.debug("прицел огненного шара: " + str(e))
            return None

    def 始める(self):
        p = self.player
        m = self.cfg.流星
        world = p.getWorld()
        t = None if kyoutsuu.終了済み[0] else self._狙い直す()
        charge = keisan.充填率(self.ticks, m.充填)
        ok = False
        if t is not None:
            self.P, self.C = t
            try:
                ok = self.陣.出す(world, self.C, charge, m.充填 - self.ticks)
            except BaseException as e:
                amazake.log.warn("огненный круг: " + str(e))
                ok = False
        if not ok:
            try:
                p.clearActiveItem()
            except BaseException:
                pass
            _帯(p, self.cfg, "meteor-no-room")
            _冷やす(self.uid, m.失敗秒)
            self.終わった = True
            return False
        self.詠唱表[self.uid] = self
        kyoutsuu.鳴らす(world, p.getEyeLocation(), self.cfg.音表["meteor-start"])
        self.task = amazake.run_for_player(p, self.見張り, period=self.cfg.詠唱.見張り)
        return True

    def 見張り(self):
        """Каждые cast.watch-period-ticks в потоке заклинателя. False — остановить задачу."""
        if self.終わった or self.詠唱表.get(self.uid) is not self or kyoutsuu.終了済み[0]:
            return False
        p = self.player
        c = self.cfg
        m = c.流星
        if not p.isOnline() or p.isDead() or p.getWorld().getName() != self.世界:
            self.終える("即")
            return False
        if not p.isHandRaised() or not dougu.杖か(p.getActiveItem()):
            self.終える("散", self.失敗秒)
            return False
        self.ticks += c.詠唱.見張り
        charge = keisan.充填率(self.ticks, m.充填)
        world = p.getWorld()
        # цель: прицел заново; круг догоняет, если сдвинулась заметно и место наше (иначе — прежняя цель)
        t = self._狙い直す()
        d = self.陣.d
        残り = m.充填 - self.ticks
        if t is not None and _距離(t[1], self.C) > m.追従距離:
            if d is None or not d.isValid():
                if self.陣.出す(world, t[1], charge, 残り):
                    self.P, self.C = t
            elif self.陣.追う(world, t[1], charge, 残り):
                self.P, self.C = t
        elif d is None or not d.isValid():
            self.陣.出す(world, self.C, charge, 残り)
        if self.ticks - self.色tick >= m.更新 or (charge >= 1.0 and not self.準備):
            self.色tick = self.ticks
            self.陣.色(charge)
        # искры в круге (радиус растёт с зарядом)
        C = self.C
        if c.炎粒 and _持つ(world, C[0], C[1], C[2]):
            r = self.陣.大きさ(charge) / 2.0
            for 粒 in c.炎粒:
                dx, dz = keisan.円内の点(r, self.rng)
                kyoutsuu.散らす(world, 粒, C[0] + dx, C[1] - 0.1, C[2] + dz)
        eye = p.getEyeLocation()
        if self.ticks - self.唸りtick >= c.唸り周期:
            self.唸りtick = self.ticks
            kyoutsuu.鳴らす(world, eye, c.音表["meteor-hum"], 0.4 * charge)
        if self.ticks >= m.充填:
            if not self.準備:
                self.準備 = True
                self.準備tick = self.ticks
                kyoutsuu.鳴らす(world, eye, c.音表["meteor-ready"])
                _帯(p, c, "meteor-ready")
            elif (self.ticks - self.準備tick) % 20 < c.詠唱.見張り:
                _帯(p, c, "meteor-ready")
        elif c.詠唱.棒:
            棒 = keisan.充填棒(charge, c.詠唱.棒長, "#ffa040")
            if 棒 != self.棒:
                self.棒 = 棒
                _帯(p, c, "meteor-charge", {"bar": 棒})
        if self.ticks >= c.詠唱.最長 * 20.0:
            self.終える("散", self.失敗秒)
            try:
                p.clearActiveItem()
            except BaseException:
                pass
            _帯(p, c, "meteor-too-long")
            return False
        return True

    def 放す(self, held):
        """ПКМ отпущен: заряжен — залп, иначе — срыв."""
        if self.終わった:
            return
        p = self.player
        c = self.cfg
        m = c.流星
        if held >= m.充填:
            d, 場所 = self.陣.手放す()
            self.終える("撃", m.冷却秒)
            try:
                g = 流星群(_新番号(), p, c, self.P, self.C, p.getWorld(), d, 場所, self.rng)
                g.始める()
            except BaseException as e:
                amazake.log.warn("залп огненного шара: " + str(e))
                kyoutsuu.消す(d)
        else:
            self.終える("散", self.失敗秒)
            _帯(p, c, "meteor-too-early", need=keisan.秒の文字(m.充填 / 20.0))

    def 終える(self, 方法, 冷却秒=None):
        """Конец заряда: «撃» (залп — круг уже отдан), «散» (срыв: круг сжимается), «即» (убрать сразу)."""
        if self.終わった:
            return
        self.終わった = True
        if self.詠唱表.get(self.uid) is self:
            del self.詠唱表[self.uid]
        t = self.task
        self.task = None
        if t is not None:
            try:
                t.cancel()
            except BaseException:
                pass
        if 冷却秒 is not None:
            _冷やす(self.uid, 冷却秒)
        if 方法 == "撃":
            return
        if 方法 == "散":
            self.陣.散る()
            try:
                kyoutsuu.鳴らす(self.player.getWorld(), self.player.getEyeLocation(), self.cfg.音表["meteor-fizzle"])
            except BaseException:
                pass
        else:
            self.陣.即消す()


# ------------------------------------------------------------------------------------------------ залп

class 流星群:
    """Залп комет после отпускания. Не зависит от заклинателя: огненный круг — поток региона C, каждая комета —
    поток региона своей точки удара."""

    __slots__ = ("id", "player", "uid", "cfg", "world", "P", "C", "円", "円場所", "表示", "回数", "鍵", "止",
                 "期限ms", "rng")

    def __init__(self, id, player, cfg, P, C, world, 円, 円場所, rng=None):
        self.id = id
        self.player = player
        self.uid = str(player.getUniqueId())
        self.cfg = cfg
        self.world = world
        self.P = P
        self.C = C
        self.円 = 円
        self.円場所 = 円場所 if 円場所 is not None else _場所(world, C[0], C[1], C[2])
        self.表示 = {}           # str(UUID) → дисплей (огненный круг и живые кометы)
        self.回数 = {}           # str(UUID) цели → попаданий за залп
        self.鍵 = threading.Lock()
        self.止 = False
        self.期限ms = 0
        self.rng = rng or random.Random()

    def _生きている(self):
        return not self.止 and not kyoutsuu.終了済み[0]

    def _覚える(self, d):
        try:
            self.表示[str(d.getUniqueId())] = d
        except BaseException:
            pass

    def _消す(self, d):
        """Убрать дисплей (поток его региона или любой — kyoutsuu.消す сам уйдёт в поток сущности)."""
        if d is None:
            return
        try:
            self.表示.pop(str(d.getUniqueId()), None)
        except BaseException:
            pass
        try:
            if d.isValid() and amazake.owns(d):
                d.remove()
            elif d.isValid():
                kyoutsuu.消す(d)
        except BaseException:
            kyoutsuu.消す(d)

    # -------------------------------------------------------------------------------------------- начало

    def 始める(self):
        """Поток заклинателя, тик отпускания (0). Все случайные числа — здесь (rng не делится между потоками)."""
        m = self.cfg.流星
        今 = kyoutsuu.今ms()
        _古いのを掃く(今)
        n = m.数
        点 = keisan.落下点(n, m.散半径, m.中心から, self.rng)
        k = keisan.出発係数(m.出広がり, m.直径, m.散半径)
        発 = keisan.発射tick(n, m.間隔)
        最後 = keisan.最後の着弾(n, m.間隔, m.落下)
        円終り = 最後 + m.消える + 1
        終り = 最後 + max(m.消える, m.破 + m.衝) + 2      # круг и последняя комета уже убраны
        self.期限ms = 今 + 終り * 50 + 10000
        群[self.id] = self
        # звук отпускания — у круга (если он наш), иначе у глаз
        C = self.C
        try:
            at = self.円場所 if amazake.owns(self.円場所) else self.player.getEyeLocation()
            kyoutsuu.鳴らす(self.world, at, self.cfg.音表["meteor-release"])
        except BaseException:
            pass
        # огненный круг: R = 250, вспышка, держится до последнего удара, гаснет, удаляется
        d = self.円
        if d is not None:
            self._覚える(d)
            try:
                if d.isValid() and amazake.owns(d):
                    self._閃く(d)
                else:
                    amazake.run_in_region(self.円場所, lambda: self._閃く(d), delay=1)
                amazake.run_in_region(self.円場所, lambda: self._円消えゆく(d), delay=max(1, 最後))
                amazake.run_in_region(self.円場所, lambda: self._消す(d), delay=円終り)
            except BaseException as e:
                amazake.log.warn("огненный круг после отпускания: " + str(e))
                self._消す(d)
        try:
            amazake.run_in_region(self.円場所, self._終わり, delay=終り)
        except BaseException as e:
            amazake.log.debug("конец залпа: " + str(e))
        # кометы
        for i in range(n):
            dx, dz = 点[i]
            S = keisan.出発点(C, self.P, (dx, dz), k, 出発揺れ, self.rng)
            g = self.rng.randint(0, keisan.流星g上限)
            x = self.P[0] + dx
            z = self.P[2] + dz
            at = Location(self.world, x, self.P[1], z)
            try:
                amazake.run_in_region(at, lambda x=x, z=z, S=S, g=g: self._星を生む(x, z, S, g), delay=1 + 発[i])
            except BaseException as e:
                amazake.log.debug("комета: " + str(e))

    # -------------------------------------------------------------------------------------------- огненный круг

    def _閃く(self, d):
        if not self._生きている() or not d.isValid() or not amazake.owns(d):
            return
        m = self.cfg.流星
        d.setItemStack(kyoutsuu.効果品(m.円模型, keisan.R上限))
        d.setInterpolationDelay(0)
        d.setInterpolationDuration(閃光tick)
        d.setTransformation(炎陣.変形(m.直径 * m.閃光倍))

    def _円消えゆく(self, d):
        if not self._生きている() or not d.isValid() or not amazake.owns(d):
            return
        m = self.cfg.流星
        d.setInterpolationDelay(0)
        d.setInterpolationDuration(m.消える)
        d.setTransformation(炎陣.変形(0.01))

    def _終わり(self):
        """Последняя задача залпа (регион C): залп забыть; что осталось (задача кометы потерялась) — убрать."""
        群.pop(self.id, None)
        for d in list(self.表示.values()):
            self._消す(d)

    # -------------------------------------------------------------------------------------------- кометы

    def _星を生む(self, x, z, S, g):
        """Поток региона точки удара: луч до земли, дисплей кометы (стоит в I, вид — в S), звук запуска; через тик —
        падение, след; через 1 + fall-ticks — удар."""
        if not self._生きている():
            return
        w = self.world
        m = self.cfg.流星
        C = self.C
        if not _読込済(w, x, z):
            return
        Iy = self.P[1]
        try:
            start = Location(w, x, C[1] - 0.3, z)
            if amazake.owns(start):
                hit = w.rayTraceBlocks(start, _下, (C[1] - self.P[1]) + 6.0, FluidCollisionMode.NEVER, True)
                if hit is not None:
                    Iy = hit.getHitPosition().getY()
        except BaseException as e:
            amazake.log.debug("земля под кометой: " + str(e))
        I = (x, Iy, z)
        at = _場所(w, x, Iy, z)
        if not amazake.owns(at):
            return
        u = keisan.正規化((S[0] - I[0], S[1] - I[1], S[2] - I[2])) or (0.0, 1.0, 0.0)
        q = keisan.流星回転(u)
        s = m.星倍
        T0 = (S[0] - I[0], S[1] - I[1], S[2] - I[2])
        T1 = (0.0, m.地上, 0.0)
        try:
            d = mahoujin.生む(at, kyoutsuu.効果品(m.星模型, keisan.流星R(g, 0)), kyoutsuu.変形Q(T0[0], T0[1], T0[2], q, s),
                             m.星視距離, "comet", self.uid)
            amazake.data(d).set_string(kyoutsuu.流星鍵, str(self.id))
        except BaseException as e:
            amazake.log.warn("комета: " + str(e))
            return
        if not self._生きている():
            d.remove()
            return
        self._覚える(d)
        try:
            kyoutsuu.鳴らす(w, Location(w, x, C[1], z), self.cfg.音表["meteor-launch"])
        except BaseException:
            pass
        F = m.落下

        def 落ちる():
            if not self._生きている() or not d.isValid() or not amazake.owns(d):
                return
            d.setInterpolationDelay(0)
            d.setInterpolationDuration(F)
            d.setTransformation(kyoutsuu.変形Q(T1[0], T1[1], T1[2], q, s))

        終点 = (I[0] + T1[0], I[1] + T1[1], I[2] + T1[2])
        歩 = [0]

        def 跡():
            if not self._生きている() or 歩[0] > F:
                return False
            pt = keisan.線形(S, 終点, 歩[0] / float(F))
            歩[0] += 2
            try:
                if self.cfg.星跡粒 and amazake.owns(Location(w, pt[0], pt[1], pt[2])):
                    _散らす列(w, self.cfg.星跡粒, pt[0], pt[1], pt[2])
            except BaseException as e:
                amazake.log.debug("след кометы: " + str(e))
            return 歩[0] <= F

        try:
            amazake.run_in_region(at, 落ちる, delay=1)
            if self.cfg.星跡粒:
                amazake.run_in_region(at, 跡, delay=1, period=2)
            amazake.run_in_region(at, lambda: self._着弾(d, I, q, g), delay=1 + F)
        except BaseException as e:
            amazake.log.warn("задачи кометы: " + str(e))
            self._消す(d)

    def _着弾(self, d, I, q, g):
        """Удар (поток региона I): вспышка кометы, частицы, звук, урон; потом сжатие и удаление."""
        if not self._生きている():
            return
        m = self.cfg.流星
        w = self.world
        at = _場所(w, I[0], I[1], I[2])
        if not amazake.owns(at):
            self._消す(d)
            return
        T1 = (0.0, m.地上, 0.0)
        try:
            if d.isValid() and amazake.owns(d):
                d.setItemStack(kyoutsuu.効果品(m.星模型, keisan.流星R(g, 1)))
                d.setInterpolationDelay(0)
                d.setInterpolationDuration(m.破)
                d.setTransformation(kyoutsuu.変形Q(T1[0], T1[1], T1[2], q, m.星倍 * m.破倍))

                def 縮む():
                    if self._生きている() and d.isValid() and amazake.owns(d):
                        d.setInterpolationDelay(0)
                        d.setInterpolationDuration(m.衝)
                        d.setTransformation(kyoutsuu.変形Q(T1[0], T1[1], T1[2], q, 0.01))

                amazake.run_in_region(at, 縮む, delay=m.破)
                amazake.run_in_region(at, lambda: self._消す(d), delay=m.破 + m.衝 + 1)
            else:
                self._消す(d)
        except BaseException as e:
            amazake.log.debug("вспышка кометы: " + str(e))
            self._消す(d)
        # вид и звук взрыва — без createExplosion: ни блоков, ни огня в мире
        try:
            _散らす列(w, self.cfg.着弾粒, I[0], I[1] + 0.3, I[2])
            kyoutsuu.鳴らす(w, at, self.cfg.音表["meteor-impact"])
        except BaseException as e:
            amazake.log.debug("взрыв кометы: " + str(e))
        try:
            self._打つ(I, at)
        except BaseException as e:
            amazake.log.warn("урон кометы: " + str(e))

    def _打つ(self, I, at):
        """Урон в радиусе hit-radius (поток региона I). Только если поток владеет заклинателем (иначе удар только
        виден) и всеми столбцами чанков взрывной волны."""
        m = self.cfg.流星
        if m.傷 <= 0.0 or m.半径 <= 0.0:
            return
        p = self.player
        if not amazake.owns(p) or not p.isOnline() or not p.isValid():
            return
        w = self.world
        r = m.半径
        箱 = (I[0] - r, I[1] - r, I[2] - r, I[0] + r, I[1] + r, I[2] + r)
        for cx, cz in keisan.箱の列(箱):
            if not w.isChunkLoaded(cx, cz) or not amazake.owns(Location(w, cx * 16.0 + 8.0, I[1], cz * 16.0 + 8.0)):
                return
        候補 = []
        実体 = {}
        for e in w.getNearbyEntities(BoundingBox(箱[0], 箱[1], 箱[2], 箱[3], 箱[4], 箱[5])):
            try:
                uid = str(e.getUniqueId())
                if uid in 実体 or not kousen.狙える(e, m.対人, self.uid):
                    continue
                bb = e.getBoundingBox()
                実体[uid] = e
                候補.append((uid, (bb.getMinX(), bb.getMinY(), bb.getMinZ(), bb.getMaxX(), bb.getMaxY(), bb.getMaxZ())))
            except BaseException as ex:
                amazake.log.debug("цель кометы: " + str(ex))
        targets = keisan.爆風(I, r, 候補, self.uid)
        if not targets:
            return
        src = None
        try:
            src = (kousen.DamageSource.builder(kousen.傷の種類(m.傷種)).withCausingEntity(p).withDirectEntity(p)
                   .withDamageLocation(at).build())
        except BaseException as ex:
            amazake.log.debug("DamageSource: " + str(ex))
        for uid in targets:
            e = 実体[uid]
            with self.鍵:
                if not keisan.数える(self.回数, uid, m.最多):
                    continue
            try:
                if not e.isValid() or e.isDead():
                    continue
                前 = float(e.getHealth()) + float(e.getAbsorptionAmount())
                if m.無敵無視:
                    e.setNoDamageTicks(0)
                if src is not None:
                    e.damage(float(m.傷), src)
                else:
                    e.damage(float(m.傷), p)
                死 = e.isDead()
                後 = float(e.getHealth()) + float(e.getAbsorptionAmount())
                if not (死 or 後 < 前 - 1e-6):
                    continue            # отменено (PvP, защита) — ни огня, ни отталкивания
                if m.火秒 > 0.0:
                    e.setFireTicks(max(int(e.getFireTicks()), int(round(m.火秒 * 20.0))))
                if not 死 and (m.押し > 0.0 or m.押し上 > 0.0):
                    c = e.getBoundingBox().getCenter()
                    v = keisan.押し出し(I, (c.getX(), c.getY(), c.getZ()), m.押し, m.押し上)
                    e.setVelocity(e.getVelocity().add(Vector(v[0], v[1], v[2])))
            except BaseException as ex:
                amazake.log.warn("урон кометой " + uid + ": " + str(ex))
