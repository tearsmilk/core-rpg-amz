# 結界 — барьер посоха: вертикальная стена из шестигранников (соты, по умолчанию 3×5 = 15 ячеек, ~3×4 блока)
# перед заклинателем. Каждая ячейка — свой ItemDisplay (модель azerus:moon_staff_barrier, спрайт hex_cell, ключ
# шейдера A: цвет (R, 233, 253), R = g + 25·L — фаза мерцания g и уровень состояния L, см. keisan.結界R).
#
# Жизнь барьера:
#   * 結界詠唱 (поток заклинателя): ПКМ зажат — ячейки по одной вылетают от посоха и встают в соты (интерполяция
#     дисплея); через barrier.charge-ticks барьер «поднят» (形成): перезарядка, вспышка, запись в реестр;
#     отпустил раньше — ячейки рассыпаются (срыв). После поднятия ПКМ можно отпустить — барьер уже сам по себе.
#   * 結界 (живой объект): дисплеи стоят на месте (не двигаются и не телепортируются); после поднятия их трогает
#     только поток региона якоря (amazake.run_in_region(якорь)), каждый раз с проверкой amazake.owns(d).
#     Через duration-seconds — 消える(): уровень 4, сжатие, частицы, звук, удаление.
#   * Реестр (有効): неизменяемый кортеж чистых записей keisan.結界記録 (без объектов Java). Пишется под _鍵,
#     читается без блокировки из любого потока (kousen.撃つ в потоке стрелка) — так луч гасится барьером из
#     чужого региона без обращения к чужим сущностям.

import math
import threading

import amazake

import keisan
import kyoutsuu
import mahoujin
import dougu
from kyoutsuu import Location
from settei import f32

#: Реестр живых барьеров: [кортеж keisan.結界記録]. Подменяется целиком (под _鍵), никогда не меняется на месте.
有効 = [()]
_鍵 = threading.Lock()
_番号 = [0]

#: Живые барьеры: id → 結界 (дисплеи и якорь). Дисплеи трогает только поток региона якоря.
結界体 = {}
#: Последний барьер игрока: str(UUID) → id (у игрока не больше одного барьера).
主の結界 = {}
#: Перезарядка барьера: str(UUID) → момент готовности (мс, kyoutsuu.今ms). Своя, не ванильная: ванильная
#: «шторка» по группе посоха закрыла бы и луч, и переключение режима.
結界冷却 = {}

#: Уровни состояния ячейки (L): см. keisan.結界R и шейдер moonstaff_fragment.glsl (K10).
L_飛ぶ = 9          # только что вылетела
L_着いた = 7        # встала на место, барьер ещё собирается
L_普通 = 8          # барьер поднят
L_閃光 = 9          # вспышка (поднятие, попадание луча)
L_消える = 4        # угасание
L_散る = 3          # срыв


# ------------------------------------------------------------------------------------------------ реестр

def 新番号():
    with _鍵:
        _番号[0] += 1
        return _番号[0]


def 登録(rec):
    """Добавить запись (заодно выбросить истёкшие и запись с тем же id)."""
    今 = kyoutsuu.今ms()
    with _鍵:
        有効[0] = tuple(r for r in 有効[0] if r.id != rec.id and r.終ms > 今) + (rec,)


def 解除(id):
    with _鍵:
        有効[0] = tuple(r for r in 有効[0] if r.id != id)


def 一覧():
    """Снимок реестра (кортеж; читатели сами пропускают записи с 終ms ≤ сейчас)."""
    return 有効[0]


def 数():
    今 = kyoutsuu.今ms()
    return sum(1 for r in 有効[0] if r.終ms > 今)


def 記録(id):
    for r in 有効[0]:
        if r.id == id:
            return r
    return None


def 冷却残り(uid):
    終 = 結界冷却.get(uid)
    if 終 is None:
        return 0.0
    r = keisan.冷却残り(kyoutsuu.今ms(), 終)
    if r <= 0.0:
        結界冷却.pop(uid, None)
    return r


def _冷やす(uid, 秒):
    if 秒 > 0.0:
        結界冷却[uid] = kyoutsuu.今ms() + int(秒 * 1000.0)
    else:
        結界冷却.pop(uid, None)


def 片付け():
    """@on_unload: всё забыть (дисплеи убирает main через 表示一覧)."""
    with _鍵:
        有効[0] = ()
    結界体.clear()
    主の結界.clear()
    結界冷却.clear()


# ------------------------------------------------------------------------------------------------ мелочи

def _帯(p, cfg, 鍵, 生=None, **素):
    t = cfg.文(鍵)
    if 生:
        for k, v in 生.items():
            t = t.replace("<" + k + ">", v)
    if t.strip():
        amazake.actionbar(p, t, **素)


def _世界の点(場所, 右, u, v):
    """Центр ячейки (u, v) в мире: якорь + 右·u + (0, v, 0)."""
    return (場所.getX() + 右[0] * u, 場所.getY() + v, 場所.getZ() + 右[2] * u)


def _品(cfg, g, L):
    return kyoutsuu.効果品(cfg.結界.モデル, keisan.結界R(g, L))


def _色(d, cfg, g, L):
    """Уровень L ячейке d (поток, владеющий d). False — дисплея больше нет / он в чужом потоке (тогда убран)."""
    if d is None:
        return False
    try:
        if not d.isValid():
            return False
        if not amazake.owns(d):
            kyoutsuu.消す(d)
            return False
        d.setItemStack(_品(cfg, g, L))
        return True
    except BaseException as e:
        amazake.log.debug("ячейка барьера: " + str(e))
        return False


# ------------------------------------------------------------------------------------------------ живой барьер

class 結界:
    """Поднятый барьер. Всё, кроме создания, — в потоке региона якоря."""

    __slots__ = ("id", "rec", "場所", "cfg", "表示", "g", "細胞", "消え中", "主", "閃番")

    def __init__(self, id, rec, 場所, cfg, 表示, g):
        self.id = id
        self.rec = rec
        self.場所 = 場所
        self.cfg = cfg
        self.表示 = 表示          # список по k (None — ячейки нет); после поднятия — только поток региона якоря
        self.g = g
        self.細胞 = rec.細胞
        self.消え中 = False
        self.主 = rec.主
        self.閃番 = [0] * len(表示)

    def 表示一覧(self):
        return [d for d in list(self.表示) if d is not None]

    def 落ち着く(self):
        """Конец вспышки поднятия: все ячейки — обычный уровень."""
        if self.消え中 or kyoutsuu.終了済み[0]:
            return
        for k, d in enumerate(self.表示):
            if d is not None and self.閃番[k] == 0:
                _色(d, self.cfg, self.g[k], L_普通)

    def 閃く(self, k):
        """Луч попал в ячейку k: вспышка на hit-flash-ticks (поток региона якоря)."""
        if self.消え中 or kyoutsuu.終了済み[0] or not (0 <= k < len(self.表示)):
            return
        d = self.表示[k]
        if not _色(d, self.cfg, self.g[k], L_閃光):
            return
        self.閃番[k] += 1
        番 = self.閃番[k]

        def 戻す():
            if self.消え中 or kyoutsuu.終了済み[0] or self.閃番[k] != 番:
                return
            self.閃番[k] = 0
            _色(self.表示[k], self.cfg, self.g[k], L_普通)

        try:
            amazake.run_in_region(self.場所, 戻す, delay=max(1, self.cfg.結界.打閃光))
        except BaseException as e:
            amazake.log.debug("вспышка барьера: " + str(e))

    def 消える(self):
        """Конец барьера (поток региона якоря, повторный вызов ничего не делает): из реестра, ячейки гаснут и
        сжимаются за fade-ticks, частицы и звук, потом дисплеи удаляются."""
        if self.消え中:
            return
        self.消え中 = True
        解除(self.id)
        if 主の結界.get(self.主) == self.id:
            主の結界.pop(self.主, None)
        結界体.pop(self.id, None)
        if kyoutsuu.終了済み[0]:
            return
        c = self.cfg.結界
        world = self.場所.getWorld()
        for k in range(len(self.表示)):
            d = self.表示[k]
            self.表示[k] = None
            if d is None:
                continue
            try:
                if not d.isValid():
                    continue
                if not amazake.owns(d):
                    kyoutsuu.消す(d)
                    continue
                u, v = self.細胞[k]
                d.setItemStack(_品(self.cfg, self.g[k], L_消える))
                d.setInterpolationDelay(0)
                d.setInterpolationDuration(c.消える)
                d.setTransformation(kyoutsuu.変形3(-u * 1.06, v * 1.06, 0.1, 0.02, 0.02, 1.0))
                mahoujin.後で消す(d, self.場所, c.消える + 1)
                x, y, z = _世界の点(self.場所, self.rec.右, u, v)
                kyoutsuu.散らす(world, self.cfg.終結粒, x, y, z)
            except BaseException as e:
                amazake.log.debug("угасание барьера: " + str(e))
                kyoutsuu.消す(d)
        kyoutsuu.鳴らす(world, self.場所, self.cfg.音表["barrier-end"])


def 閃かせる(id, k):
    """Из любого потока (kousen.撃つ — поток стрелка): вспышка ячейки k барьера id в потоке его региона."""
    b = 結界体.get(id)
    if b is None or b.消え中 or k is None:
        return
    try:
        amazake.run_in_region(b.場所, lambda: b.閃く(k), delay=1)
    except BaseException as e:
        amazake.log.debug("вспышка барьера: " + str(e))


def _消させる(b):
    """Убрать барьер b из любого потока: из реестра сразу, дисплеи — в потоке его региона."""
    解除(b.id)
    try:
        amazake.run_in_region(b.場所, b.消える)
    except BaseException as e:
        amazake.log.debug("конец барьера: " + str(e))
        b.消え中 = True
        結界体.pop(b.id, None)
        for d in b.表示一覧():
            kyoutsuu.消す(d)


# ------------------------------------------------------------------------------------------------ заклинание

class 結界詠唱:
    """Сборка барьера одним игроком (поток игрока). Интерфейс как у main.詠唱中: uid, cfg, 終わった, 見張り(),
    放す(held), 終える(方法, 冷却秒=None), 表示一覧(), 失敗秒."""

    __slots__ = ("player", "uid", "cfg", "詠唱表", "ticks", "task", "世界", "終わった", "形成済", "id", "場所",
                 "eye", "yaw", "右", "細胞", "順", "順位", "出", "g", "Q", "表示", "着", "棒", "失敗秒")

    def __init__(self, player, cfg, ticks, 詠唱表):
        c = cfg.結界
        self.player = player
        self.uid = str(player.getUniqueId())
        self.cfg = cfg
        self.詠唱表 = 詠唱表
        self.ticks = ticks
        self.task = None
        self.世界 = player.getWorld().getName()
        self.終わった = False
        self.形成済 = False
        self.id = 新番号()
        self.場所 = None
        self.eye = None
        self.yaw = 0.0
        self.右 = (1.0, 0.0, 0.0)
        self.細胞 = keisan.蜂巣(c.列, c.行, c.半径)
        N = len(self.細胞)
        self.順 = keisan.組順(self.細胞)
        self.順位 = [0] * N
        self.出 = [0] * N
        for m, k in enumerate(self.順):
            self.順位[k] = m
            self.出[k] = keisan.出現tick(m, N, c.充填, c.飛)
        self.g = keisan.輝き番号(self.細胞)
        self.Q = keisan.表示倍率(c.半径, c.隙間)
        self.表示 = [None] * N
        self.着 = [None] * N          # тик, когда ячейка долетит (None — ещё не вылетела, -1 — уже на месте)
        self.棒 = None
        self.失敗秒 = c.失敗秒

    # -------------------------------------------------------------------------------------------- трансформации

    def _終変形(self, k):
        u, v = self.細胞[k]
        return kyoutsuu.変形3(-u, v, 0.0, self.Q, self.Q, 1.0)

    def _始変形(self, k):
        c = self.cfg.結界
        u, v = self.細胞[k]
        s = self.Q * c.始倍
        # примерно головка посоха в осях дисплея (+X — левая рука заклинателя, +Z — от него)
        return kyoutsuu.変形3(-0.3 - u * c.広がり, -c.上 - 0.2 + v * c.広がり, 0.9 - c.前, s, s, 1.0)

    def 表示一覧(self):
        return [d for d in self.表示 if d is not None]

    # -------------------------------------------------------------------------------------------- жизнь

    def 始める(self):
        """Поток игрока: якорь перед глазами (фиксирован до конца), звук, надзор."""
        p = self.player
        c = self.cfg.結界
        eye = p.getEyeLocation()
        world = eye.getWorld()
        self.yaw = float(eye.getYaw())
        self.eye = (eye.getX(), eye.getY(), eye.getZ())
        self.右 = keisan.結界の軸(self.yaw)[0]
        x, y, z = keisan.結界の中心(self.eye, self.yaw, c.前, c.上)
        at = Location(world, x, y, z, f32(self.yaw), f32(0.0))
        ok = False
        try:
            ok = (world.isChunkLoaded(int(math.floor(x)) >> 4, int(math.floor(z)) >> 4)
                  and bool(amazake.owns(at)))
        except BaseException:
            ok = False
        if not ok or kyoutsuu.終了済み[0]:
            try:
                p.clearActiveItem()
            except BaseException:
                pass
            _帯(p, self.cfg, "barrier-no-room")
            _冷やす(self.uid, c.失敗秒)
            self.終わった = True
            return False
        self.場所 = at
        self.詠唱表[self.uid] = self
        kyoutsuu.鳴らす(world, eye, self.cfg.音表["barrier-start"])
        self._進む()
        self.task = amazake.run_for_player(p, self.見張り, period=self.cfg.詠唱.見張り)
        return True

    def _生む(self, k, 束):
        """Ячейка k вылетает: дисплей у якоря со стартовой трансформацией (у посоха, маленькая)."""
        if kyoutsuu.終了済み[0] or self.表示[k] is not None or self.着[k] is not None:
            return
        self.着[k] = -1            # больше не пытаться, даже если не вышло
        try:
            if not amazake.owns(self.場所):
                return
            c = self.cfg.結界
            d = mahoujin.生む(self.場所, _品(self.cfg, self.g[k], L_飛ぶ), self._始変形(k), c.視距離, "barrier",
                             self.uid)
            dd = amazake.data(d)
            dd.set_string("barrier", str(self.id))
            dd.set("cell", k)
            if kyoutsuu.終了済み[0]:
                d.remove()
                return
            self.表示[k] = d
            self.着[k] = self.ticks + 1 + c.飛
            束.append(k)
        except BaseException as e:
            amazake.log.warn("ячейка барьера: " + str(e))

    def _飛ばす(self, 束):
        """Через тик после появления (в тик рождения интерполяция не работает): полёт на место за fly-ticks.
        Список дисплеев берётся тот же (после поднятия он принадлежит барьеру; убранная ячейка — None)."""
        if not 束:
            return
        表示 = self.表示
        飛 = self.cfg.結界.飛

        def 飛ぶ():
            for k in 束:
                d = 表示[k]
                if d is None:
                    continue
                try:
                    if d.isValid() and amazake.owns(d):
                        d.setInterpolationDelay(0)
                        d.setInterpolationDuration(飛)
                        d.setTransformation(self._終変形(k))
                except BaseException as e:
                    amazake.log.debug("полёт ячейки: " + str(e))

        try:
            amazake.run_in_region(self.場所, 飛ぶ, delay=1)
        except BaseException as e:
            amazake.log.debug("полёт ячейки: " + str(e))

    def _進む(self):
        """Шаг сборки: вылетают ячейки, чей тик пришёл; долетевшие — уровень 7 и звук (выше с каждой)."""
        world = self.場所.getWorld()
        束 = []
        for k in self.順:
            if self.着[k] is None and self.出[k] <= self.ticks:
                self._生む(k, 束)
        self._飛ばす(束)
        for k in self.順:
            t = self.着[k]
            if t is not None and t >= 0 and self.ticks >= t:
                self.着[k] = -1
                _色(self.表示[k], self.cfg, self.g[k], L_着いた)
                kyoutsuu.鳴らす(world, self.場所, self.cfg.音表["barrier-cell"], 0.05 * self.順位[k])

    def 見張り(self):
        """Каждые cast.watch-period-ticks в потоке игрока. False — остановить задачу."""
        if self.終わった or self.詠唱表.get(self.uid) is not self or kyoutsuu.終了済み[0]:
            return False
        p = self.player
        c = self.cfg
        if not p.isOnline() or p.isDead() or p.getWorld().getName() != self.世界:
            self.終える("即")
            return False
        if not p.isHandRaised() or not dougu.杖か(p.getActiveItem()):
            # ПКМ отпущен без события отпускания: до поднятия — срыв, после — просто конец
            self.終える("散", self.失敗秒)
            return False
        self.ticks += c.詠唱.見張り
        if not self.形成済:
            self._進む()
            if self.ticks >= c.結界.充填:
                self.形成()
            elif c.詠唱.棒:
                棒 = keisan.充填棒(keisan.充填率(self.ticks, c.結界.充填), c.詠唱.棒長)
                if 棒 != self.棒:
                    self.棒 = 棒
                    _帯(p, c, "barrier-charge", {"bar": 棒})
        if self.ticks >= c.詠唱.最長 * 20.0:
            # барьер уже стоит — молча опускаем посох (до поднятия — срыв)
            self.終える("散", self.失敗秒)
            try:
                p.clearActiveItem()
            except BaseException:
                pass
            return False
        return True

    def 放す(self, held):
        """ПКМ отпущен (PlayerStopUsingItemEvent): поднят — просто конец; держал достаточно — поднять и конец;
        раньше — срыв."""
        if self.終わった:
            return
        c = self.cfg.結界
        if self.形成済:
            self.終える("済")
        elif held >= c.充填 or self.ticks >= c.充填:
            self.形成()
            self.終える("済")
        else:
            self.終える("散", self.失敗秒)
            _帯(self.player, self.cfg, "barrier-too-early", need=keisan.秒の文字(c.充填 / 20.0))

    def 形成(self):
        """Барьер поднят (поток игрока): перезарядка, оставшиеся ячейки, вспышка, частицы, звук, реестр."""
        if self.形成済 or self.終わった:
            return
        self.形成済 = True
        cfg = self.cfg
        c = cfg.結界
        p = self.player
        今 = kyoutsuu.今ms()
        結界冷却[self.uid] = 今 + int(c.冷却秒 * 1000.0)
        world = self.場所.getWorld()
        束 = []
        for k in self.順:
            if self.着[k] is None:
                self._生む(k, 束)
        self._飛ばす(束)
        for k, d in enumerate(self.表示):
            _色(d, cfg, self.g[k], L_閃光)
            u, v = self.細胞[k]
            x, y, z = _世界の点(self.場所, self.右, u, v)
            kyoutsuu.散らす(world, cfg.形成粒, x, y, z)
        kyoutsuu.鳴らす(world, self.場所, cfg.音表["barrier-formed"])
        _帯(p, cfg, "barrier-ready", s=keisan.秒の文字(c.秒))

        # у игрока не больше одного барьера: прежний гаснет сейчас
        前id = 主の結界.get(self.uid)
        前 = 結界体.get(前id) if 前id is not None else None
        if 前 is not None:
            _消させる(前)

        rec = keisan.結界を作る(self.id, world.getName(), self.eye, self.yaw, c, 今, self.uid)
        b = 結界(self.id, rec, self.場所, cfg, self.表示, self.g)
        self.表示 = [None] * len(self.細胞)       # дисплеи теперь у барьера
        結界体[self.id] = b
        主の結界[self.uid] = self.id
        登録(rec)
        try:
            if c.閃光 > 0:
                amazake.run_in_region(self.場所, b.落ち着く, delay=c.閃光)
            else:
                b.落ち着く()
            amazake.run_in_region(self.場所, b.消える, delay=max(1, int(round(c.秒 * 20.0))))
        except BaseException as e:
            amazake.log.warn("задача барьера: " + str(e))

    def 終える(self, 方法, 冷却秒=None):
        """Конец заклинания: «済» (барьер поднят или просто опустил посох), «散» (срыв: ячейки рассыпаются),
        «即» (убрать сразу). Поднятый барьер не трогается — он живёт сам."""
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
        if self.形成済:
            return
        if 冷却秒 is not None:
            _冷やす(self.uid, 冷却秒)
        if 方法 == "散":
            self._散る()
            try:
                kyoutsuu.鳴らす(self.player.getWorld(), self.player.getEyeLocation(), self.cfg.音表["barrier-fizzle"])
            except BaseException:
                pass
        else:
            for k in range(len(self.表示)):
                d = self.表示[k]
                self.表示[k] = None
                kyoutsuu.消す(d)

    def _散る(self):
        """Срыв: каждая вылетевшая ячейка тускнеет (уровень 3) и летит обратно к посоху, сжимаясь."""
        c = self.cfg.結界
        for k in range(len(self.表示)):
            d = self.表示[k]
            self.表示[k] = None
            if d is None:
                continue
            try:
                if not d.isValid():
                    continue
                if not amazake.owns(d):
                    kyoutsuu.消す(d)
                    continue
                d.setItemStack(_品(self.cfg, self.g[k], L_散る))
                d.setInterpolationDelay(0)
                d.setInterpolationDuration(c.消散)
                d.setTransformation(self._始変形(k))
                mahoujin.後で消す(d, self.場所, c.消散 + 1)
            except BaseException as e:
                amazake.log.debug("срыв барьера: " + str(e))
                kyoutsuu.消す(d)
