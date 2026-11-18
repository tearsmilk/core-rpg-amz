# 棒 — приёмы обычной палки (Material STICK; по желанию — только предметы с nexo:id из stick.nexo-ids):
#   ПКМ           «Удар в спину» (背刺し): мгновенно; первая живая цель впереди (луч взгляда + узкий конус) до range
#                 блоков; со спины цели (угол между её взглядом и направлением атакующий → цель < back-angle) — урон ×
#                 back-multiplier и крит-вид. Вид: огненный серп (шейдер K28: дуга нарастает 3 тика, сгорает к 9) и
#                 белая вспышка-штрих (K29), частицы FLAME / CRIT / DUST, звук. Перезарядка 6 с (промах — 1 с).
#   Shift + ПКМ   «Угли» (余燼): сотворение 1 с — перед магом растёт и разгорается красная руническая сфера (K24..27,
#                 billboard: круг виден с любой стороны); ходить можно; сменил предмет / умер / вышел / другой мир —
#                 отмена. Потом дымный тёмно-красный луч (K30..33) до первой цели (pierce: false; true — все на пути)
#                 до range блоков, блоки останавливают: урон + поджог, вспышка-круг у цели, угли и искры. 10 с.
# Урон — DamageSource (тип из реестра) с игроком как причиной → EntityDamageByEntityEvent: PvP, защита регионов и
# прочие плагины решают как обычно; сам игрок исключён по UUID; игроки — только при stick.pvp и PvP мира.
#
# ПОТОКИ (как core_magic): всё — в потоке игрока (его события и его задача run_for_player). Цели «Удара» лежат в
# 4.5 блоках — регион игрока, каждая проверяется amazake.owns. Путь луча режется на куски (piece-length): все столбцы
# чанков AABB куска должны быть загружены и нашими (ronri.使える長さ) — первый «чужой» кусок обрывает луч как стена;
# rayTraceBlocks и getNearbyEntities зовутся только внутри проверенной длины. Дисплеи: серп и вспышка у цели — в той
# же проверенной зоне (amazake.owns места), луч — у глаз мага и не двигается; отложенные шаги — run_in_region.
# Состояние — dict по UUID, его трогает только поток этого игрока (кроме @on_unload).

import math
import random

import java
import amazake

import dougu
import kouka
import ronri
import settei
from dougu import Bukkit, Particle, SoundCategory, Color
from settei import f32

Vector = java.type("org.bukkit.util.Vector")
BoundingBox = java.type("org.bukkit.util.BoundingBox")
FluidCollisionMode = java.type("org.bukkit.FluidCollisionMode")
GameMode = java.type("org.bukkit.GameMode")
LivingEntity = java.type("org.bukkit.entity.LivingEntity")
ArmorStand = java.type("org.bukkit.entity.ArmorStand")
DustOptions = java.type("org.bukkit.Particle$DustOptions")
DamageSource = java.type("org.bukkit.damage.DamageSource")
DamageType = java.type("org.bukkit.damage.DamageType")
System = java.type("java.lang.System")

#: str(UUID) → 状態 игрока (перезарядки, идущее сотворение «Углей»).
状態表 = {}
拒否間隔 = 500       # мс: строка «перезарядка» и звук отказа не чаще


def 今ms():
    return System.nanoTime() // 1000000


class 状態:
    __slots__ = ("背刺終", "余燼終", "最後", "拒否", "詠唱")

    def __init__(self):
        now = 今ms()
        self.背刺終 = now       # момент готовности (мс, монотонно)
        self.余燼終 = now
        self.最後 = now - 10 ** 6   # последнее срабатывание (двойные события одного клика)
        self.拒否 = now - 10 ** 6
        self.詠唱 = None        # 余燼詠唱 или None


# ------------------------------------------------------------------------------------------------- предмет

_素材 = {}


def _素材型(名前):
    m = _素材.get(名前)
    if m is None:
        m = dougu.Material.matchMaterial(名前)
        if m is None:
            amazake.log.warn("stick.material «" + 名前 + "» не найден — взят STICK")
            m = dougu.Material.STICK
        _素材[名前] = m
    return m


def 棒か(item, b):
    """Палка этих приёмов: материал stick.material; если stick.nexo-ids не пуст — ещё и nexo:id из списка."""
    if item is None or item.isEmpty() or item.getType() != _素材型(b.素材):
        return False
    if not b.nexo_ids:
        return True
    return dougu.武器ID(item) in b.nexo_ids


# ------------------------------------------------------------------------------------------------- общее

def _文(鍵, 生=None):
    t = settei.現在.文言.get(鍵, "")
    if 生:
        for k, v in 生.items():
            t = t.replace("<" + k + ">", v)
    return t


def _帯(p, 鍵, 生=None, **素):
    t = _文(鍵, 生)
    if t.strip():
        amazake.actionbar(p, t, **素)


def 鳴らす(world, at, 音列):
    for s in 音列:
        try:
            world.playSound(at, s.鍵, SoundCategory.PLAYERS, s.音量, s.高さ)
        except BaseException as e:
            amazake.log.debug("звук " + s.鍵 + ": " + str(e))


def _拒む(p, st, 鍵, 残りms, b):
    now = 今ms()
    if now - st.拒否 < 拒否間隔:
        return
    st.拒否 = now
    _帯(p, 鍵, s=ronri.秒の文字(残りms / 1000.0))
    for s in b.拒否音:
        try:
            p.playSound(p.getEyeLocation(), s.鍵, SoundCategory.PLAYERS, s.音量, s.高さ)
        except BaseException:
            pass


_傷種 = {}


def 傷の種類(鍵):
    """DamageType по ключу реестра (кэш); не найден — MAGIC."""
    t = _傷種.get(鍵)
    if t is not None:
        return t
    try:
        RegistryAccess = java.type("io.papermc.paper.registry.RegistryAccess")
        RegistryKey = java.type("io.papermc.paper.registry.RegistryKey")
        t = RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE).get(kouka.鍵(鍵))
    except BaseException as e:
        amazake.log.debug("реестр урона: " + str(e))
        t = None
    if t is None:
        amazake.log.warn("тип урона «" + 鍵 + "» не найден — взят minecraft:magic")
        t = DamageType.MAGIC
    _傷種[鍵] = t
    return t


def 狙える(e, 対人, 自分):
    """Живая цель: не сам игрок 自分 (UUID-строка), живая, видимая всем, не стойка, не NPC core-npc; игрок — только
    если 対人 и не в творческом/наблюдателе; поток владеет ею."""
    if not java.instanceof(e, LivingEntity) or java.instanceof(e, ArmorStand):
        return False
    if str(e.getUniqueId()) == 自分:
        return False
    if not e.isValid() or e.isDead() or not e.isVisibleByDefault():
        return False
    if amazake.data(e).has("corenpc:npc"):
        return False
    if java.instanceof(e, dougu.Player):
        if not 対人:
            return False
        gm = e.getGameMode()
        if gm == GameMode.SPECTATOR or gm == GameMode.CREATIVE:
            return False
    return amazake.owns(e)


def 傷つける(p, e, 量, 種, 無敵無視):
    """Урон от имени игрока (DamageSource: причина и прямой источник — игрок). True — урон прошёл (здоровье упало
    или цель умерла): событие урона не отменили PvP/защита."""
    try:
        前 = float(e.getHealth())
        if 無敵無視:
            e.setNoDamageTicks(0)
        try:
            src = (DamageSource.builder(傷の種類(種)).withCausingEntity(p).withDirectEntity(p)
                   .withDamageLocation(p.getEyeLocation()).build())
            e.damage(float(量), src)
        except BaseException as ex:
            amazake.log.debug("DamageSource: " + str(ex))
            e.damage(float(量), p)
        return e.isDead() or float(e.getHealth()) < 前 - 1e-6
    except BaseException as ex:
        amazake.log.warn("урон палкой: " + str(ex))
        return False


def _対人(b, world):
    try:
        return b.対人 and bool(world.getPVP())
    except BaseException:
        return b.対人


def _粉(rgb, size):
    return DustOptions(Color.fromRGB(int(rgb)), f32(size))


def _粒(world, 種類, at, n, s, v=0.0, data=None):
    try:
        if data is None:
            world.spawnParticle(種類, at.getX(), at.getY(), at.getZ(), int(n), float(s), float(s), float(s), float(v))
        else:
            world.spawnParticle(種類, at.getX(), at.getY(), at.getZ(), int(n), float(s), float(s), float(s), float(v),
                                data)
    except BaseException as e:
        amazake.log.debug("частицы: " + str(e))


# ------------------------------------------------------------------------------------------------- вход

def 発動(p, 対象=None, ブロック=None):
    """ПКМ палкой (поток игрока). 対象 — существо, по которому кликнули (или None), ブロック — блок клика.
    → True, если событие наше (вызывающий может его отменить)."""
    s = settei.現在
    b = s.棒
    if b is None or dougu.終了済み[0]:
        return False
    if not 棒か(p.getInventory().getItemInMainHand(), b):
        return False
    w = p.getWorld()
    if w.getName() in b.禁止世界 or (b.権限 and not p.hasPermission(b.権限)):
        return False
    sneak = p.isSneaking()
    if ブロック is not None and not sneak:
        try:
            if ブロック.getType().isInteractable():
                return False          # двери, сундуки, кнопки — как обычно
        except BaseException:
            pass
    uid = str(p.getUniqueId())
    st = 状態表.get(uid)
    if st is None:
        st = 状態()
        状態表[uid] = st
    now = 今ms()
    if now - st.最後 < b.連打:
        return True                   # то же нажатие вторым событием (по существу + в воздух, обе руки)
    st.最後 = now
    if st.詠唱 is not None:
        return True                   # «Угли» уже творятся
    if sneak:
        if b.余燼 is not None:
            余燼開始(p, b, st, uid)
    elif b.背刺 is not None:
        背刺し(p, b, st, uid, 対象)
    return True


# ------------------------------------------------------------------------------------------------- «Удар в спину»

def 背刺し(p, b, st, uid, 対象):
    c = b.背刺
    now = 今ms()
    if st.背刺終 > now:
        _拒む(p, st, "stick-backstab-cooldown", st.背刺終 - now, b)
        return
    eye = p.getEyeLocation()
    world = eye.getWorld()
    E = (eye.getX(), eye.getY(), eye.getZ())
    向き = ronri.視線(eye.getYaw(), eye.getPitch())
    対人 = _対人(b, world)
    r = c.届き + 1.5
    候補 = []
    実体 = {}
    for e in p.getNearbyEntities(r, r, r):
        try:
            if not 狙える(e, 対人, uid):
                continue
            bb = e.getBoundingBox()
            k = str(e.getUniqueId())
            実体[k] = e
            候補.append((k, (bb.getMinX(), bb.getMinY(), bb.getMinZ(), bb.getMaxX(), bb.getMaxY(), bb.getMaxZ())))
        except BaseException as ex:
            amazake.log.debug("цель удара: " + str(ex))
    hit = ronri.近接の的(E, 向き, 候補, c.届き, c.円錐, c.余白)
    if 対象 is not None:
        # клик по существу: оно и есть цель, если годится и в досягаемости (клиент сам проверил взгляд)
        k = str(対象.getUniqueId())
        箱 = dict(候補).get(k)
        if 箱 is not None:
            m = ronri.箱の中心(箱)
            d = math.sqrt(sum((m[i] - E[i]) ** 2 for i in range(3)))
            if d <= c.届き + (箱[3] - 箱[0]) / 2.0 + 0.5:
                t = ronri.線分と箱(E, 向き, c.届き + 1.0, ronri.広げる(箱, c.余白))
                hit = (d if t is None else t, k)
    tgt = None
    t = 2.0
    if hit is not None:
        t, k = hit
        tgt = 実体.get(k)
        # между глазами и центром цели нет твёрдого блока
        m = ronri.箱の中心(dict(候補)[k])
        v = (m[0] - E[0], m[1] - E[1], m[2] - E[2])
        dist = math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if dist > 1e-6:
            blk = world.rayTraceBlocks(eye, Vector(v[0] / dist, v[1] / dist, v[2] / dist), dist,
                                       FluidCollisionMode.NEVER, True)
            if blk is not None:
                hp = blk.getHitPosition()
                if math.sqrt((hp.getX() - E[0]) ** 2 + (hp.getY() - E[1]) ** 2 + (hp.getZ() - E[2]) ** 2) < dist - 0.35:
                    tgt = None
                    t = 2.0
    会心 = False
    if tgt is None:
        st.背刺終 = now + int(c.外れ冷却 * 1000.0)
    else:
        tl = tgt.getLocation()
        pl = p.getLocation()
        会心 = ronri.背後か(tl.getYaw(), (pl.getX(), pl.getY(), pl.getZ()), (tl.getX(), tl.getY(), tl.getZ()), c.背角)
        量 = c.傷 * (c.倍率 if 会心 else 1.0)
        通った = 傷つける(p, tgt, 量, c.傷種, c.無敵無視)
        st.背刺終 = now + int(c.冷却 * 1000.0)
        if 会心 and 通った:
            _帯(p, "stick-backstab-crit", dmg=ronri.秒の文字(量))
    # --- вид: серп у цели (или в воздухе) ---------------------------------------------------------------------------
    try:
        _斬撃を見せる(p, eye, 向き, max(0.9, t + 0.3 - c.前), 会心, c, uid, tgt)
    except BaseException as ex:
        amazake.log.warn("серп удара: " + str(ex))
    鳴らす(world, eye, c.音)
    if 会心:
        鳴らす(world, eye, c.会心音)


def _斬撃を見せる(p, eye, 向き, d, 会心, c, uid, tgt):
    world = eye.getWorld()
    pt = ronri.点((eye.getX(), eye.getY(), eye.getZ()), 向き, d)
    at = kouka.場所(world, pt[0], pt[1] - 0.15, pt[2], eye.getYaw(), eye.getPitch())
    S = c.会心大きさ if 会心 else c.大きさ
    sign = random.choice((-1.0, 1.0))
    r0 = math.radians(sign * c.回転)
    r1 = math.radians(sign * (c.回転 + c.回転増))
    R = ronri.斬撃R(world.getGameTime(), 会心)
    s0 = S * c.始倍
    dsp = kouka.生む(at, kouka.効果品(kouka.斬撃, R), kouka.変形(0, 0, 0, s0, s0, s0, r0), c.視距離, "slash", uid)
    if dsp is not None:
        kouka.動かす(dsp, 1, 4, kouka.変形(0, 0, 0, S, S, S, r1))
        kouka.後で消す(dsp, c.寿命 + 1)
    # частицы — у цели (или на точке серпа)
    if tgt is not None:
        h = tgt.getHeight()
        tl = tgt.getLocation().add(0.0, h * 0.55, 0.0)
        w = max(0.25, tgt.getWidth() * 0.6)
    else:
        tl = at
        w = 0.5
    _粒(world, Particle.FLAME, tl, 14 if 会心 else 8, w, 0.04)
    _粒(world, Particle.CRIT, tl, 22 if 会心 else 10, w, 0.35)
    _粒(world, Particle.DUST, tl, 14 if 会心 else 8, w, 0.0, _粉(0xFF3A1E, 1.4))
    _粒(world, Particle.DUST, tl, 10 if 会心 else 6, w, 0.0, _粉(0xFF9A2E, 1.1))
    if 会心:
        _粒(world, Particle.DUST, tl, 10, w, 0.0, _粉(0xFF5AA0, 1.6))
        _粒(world, Particle.ENCHANTED_HIT, tl, 16, w, 0.4)
        _粒(world, Particle.LAVA, tl, 3, w * 0.5, 0.0)


# ------------------------------------------------------------------------------------------------- «Угли»

def 余燼開始(p, b, st, uid):
    c = b.余燼
    now = 今ms()
    if st.余燼終 > now:
        _拒む(p, st, "stick-embers-cooldown", st.余燼終 - now, b)
        return
    s = 余燼詠唱(p, b, st, uid)
    if s.始める():
        st.詠唱 = s


def _球の場所(eye, c):
    v = ronri.視線(eye.getYaw(), eye.getPitch())
    return kouka.場所(eye.getWorld(), eye.getX() + v[0] * c.球前, eye.getY() + v[1] * c.球前 - c.球下,
                      eye.getZ() + v[2] * c.球前, eye.getYaw(), eye.getPitch())


class 余燼詠唱:
    """Сотворение «Углей» одного игрока. Все методы — в потоке игрока (кроме 片付け при выгрузке)."""

    __slots__ = ("p", "b", "c", "st", "uid", "ticks", "d", "場所", "R", "task", "世界", "終わった")

    def __init__(self, p, b, st, uid):
        self.p = p
        self.b = b
        self.c = b.余燼
        self.st = st
        self.uid = uid
        self.ticks = 0
        self.d = None
        self.場所 = None
        self.R = -1
        self.task = None
        self.世界 = p.getWorld().getName()
        self.終わった = False

    def _球形(self, s):
        return kouka.変形(0, 0, 0, s, s, s)

    def _出す(self, charge):
        """Сфера у глаз размера заряда charge; рост до полного — одной интерполяцией на оставшееся время."""
        c = self.c
        at = _球の場所(self.p.getEyeLocation(), c)
        self.R = ronri.充填R(charge)
        s = c.球大きさ * (c.球始倍 + (1.0 - c.球始倍) * charge)
        d = kouka.生む(at, kouka.効果品(kouka.球, self.R), self._球形(s), c.視距離, "orb", self.uid, 看板=True,
                      移動=2)
        if d is None:
            return False
        self.d = d
        self.場所 = at
        残り = c.詠唱 - self.ticks
        if 残り > 1:
            full = self._球形(c.球大きさ)

            def 伸ばす():
                if d is self.d and d.isValid() and amazake.owns(d):
                    d.setInterpolationDelay(0)
                    d.setInterpolationDuration(max(1, 残り - 1))
                    d.setTransformation(full)

            amazake.run_once_for_player(self.p, 伸ばす, delay=1)
        return True

    def 始める(self):
        if not self._出す(0.0):
            return False
        鳴らす(self.p.getWorld(), self.p.getEyeLocation(), self.c.始音)
        self.task = amazake.run_for_player(self.p, self.歩む, period=2)
        return True

    def 歩む(self):
        """Каждые 2 тика в потоке игрока. False — стоп."""
        if self.終わった or dougu.終了済み[0]:
            return False
        p = self.p
        c = self.c
        if not p.isOnline() or p.isDead() or p.getWorld().getName() != self.世界:
            self.終える("即")
            return False
        if not 棒か(p.getInventory().getItemInMainHand(), self.b):
            self.終える("散")          # сменил предмет (слот, вторая рука, выбросил)
            return False
        self.ticks += 2
        charge = min(1.0, self.ticks / float(c.詠唱))
        eye = p.getEyeLocation()
        self._追う(eye, charge)
        if self.ticks % c.色周期 < 2 or charge >= 1.0:
            self._色(charge)
        n = 10
        k = int(round(charge * n))
        _帯(p, "stick-embers-cast", {"bar": "<#ff3a1e>" + "|" * k + "</#ff3a1e><dark_gray>" + "|" * (n - k)
                                           + "</dark_gray>"})
        if self.ticks % 10 < 2:
            鳴らす(p.getWorld(), eye, c.唸り音)
        if self.ticks >= c.詠唱:
            self.放つ()
            return False
        return True

    def _追う(self, eye, charge):
        d = self.d
        if d is None or not d.isValid() or not amazake.owns(d):
            if d is not None:
                kouka.消す(d)
                self.d = None
            self._出す(charge)
            return
        to = _球の場所(eye, self.c)
        at = self.場所
        if (at is None or (at.getX() - to.getX()) ** 2 + (at.getY() - to.getY()) ** 2
                + (at.getZ() - to.getZ()) ** 2 > 0.0025):
            if not amazake.owns(to):
                kouka.消す(d)
                self.d = None
                return
            d.teleportAsync(to)
            self.場所 = to

    def _色(self, charge):
        d = self.d
        R = ronri.充填R(charge)
        if d is None or R == self.R or not d.isValid() or not amazake.owns(d):
            return
        self.R = R
        d.setItemStack(kouka.効果品(kouka.球, R))

    def _手放す(self):
        self.終わった = True
        if self.st.詠唱 is self:
            self.st.詠唱 = None
        t = self.task
        self.task = None
        if t is not None:
            try:
                t.cancel()
            except BaseException:
                pass
        d = self.d
        self.d = None
        return d

    def 終える(self, 方法):
        """Отмена: «散» — сфера сжимается и гаснет (перезарядка отмены), «即» — убрать сразу (выход, смерть,
        другой мир, выгрузка)."""
        if self.終わった:
            return
        d = self._手放す()
        if 方法 == "即":
            kouka.消す(d)
            return
        self.st.余燼終 = 今ms() + int(self.c.中断冷却 * 1000.0)
        if d is not None and d.isValid() and amazake.owns(d):
            kouka.動かす(d, 0, 3, self._球形(0.01))
            kouka.後で消す(d, 4)
        else:
            kouka.消す(d)
        try:
            鳴らす(self.p.getWorld(), self.p.getEyeLocation(), self.c.中断音)
            _帯(self.p, "stick-embers-cancelled")
        except BaseException:
            pass

    def 放つ(self):
        """Заряд полон: вспышка сферы, луч, перезарядка."""
        if self.終わった:
            return
        d = self._手放す()
        c = self.c
        self.st.余燼終 = 今ms() + int(c.冷却 * 1000.0)
        if d is not None and d.isValid() and amazake.owns(d):
            kouka.動かす(d, 0, 2, self._球形(c.球大きさ * 1.25), kouka.効果品(kouka.球, ronri.R上限))
            kouka.動かす(d, 2 + c.保持, c.消え, self._球形(0.01))
            kouka.後で消す(d, 2 + c.保持 + c.消え + 1)
        else:
            kouka.消す(d)
        try:
            撃つ(self.p, self.b, self.uid)
        except BaseException as e:
            amazake.log.warn("луч «Углей»: " + str(e))


def 撃つ(p, b, uid):
    """Луч из потока игрока. → (дальность D, число целей с прошедшим уроном)."""
    c = b.余燼
    eye = p.getEyeLocation()
    world = eye.getWorld()
    E = (eye.getX(), eye.getY(), eye.getZ())
    向き = ronri.視線(eye.getYaw(), eye.getPitch())

    def 持つか(cx, cz):
        return world.isChunkLoaded(cx, cz) and amazake.owns(kouka.場所(world, cx * 16.0 + 8.0, E[1], cz * 16.0 + 8.0))

    使える = ronri.使える長さ(E, 向き, c.射程, c.片長, c.半径 + 1.0, 持つか)
    D = 使える
    if 使える > 0.0:
        hit = world.rayTraceBlocks(eye, Vector(向き[0], 向き[1], 向き[2]), 使える, FluidCollisionMode.NEVER, True)
        if hit is not None:
            hp = hit.getHitPosition()
            D = min(使える, math.sqrt((hp.getX() - E[0]) ** 2 + (hp.getY() - E[1]) ** 2 + (hp.getZ() - E[2]) ** 2))
    対人 = _対人(b, world)
    候補 = {}
    実体 = {}
    if D > 0.0:
        for a, z in ronri.区切る(D, c.片長):
            箱 = ronri.区間の箱(E, 向き, a, z, c.半径 + 0.5)
            for e in world.getNearbyEntities(BoundingBox(箱[0], 箱[1], 箱[2], 箱[3], 箱[4], 箱[5])):
                k = str(e.getUniqueId())
                if k in 実体:
                    continue
                実体[k] = None
                try:
                    if not 狙える(e, 対人, uid):
                        continue
                    bb = e.getBoundingBox()
                    実体[k] = e
                    候補[k] = (bb.getMinX(), bb.getMinY(), bb.getMinZ(), bb.getMaxX(), bb.getMaxY(), bb.getMaxZ())
                except BaseException as ex:
                    amazake.log.debug("цель луча: " + str(ex))
    当たり = []
    for k, 箱 in 候補.items():
        t = ronri.線分と箱(E, 向き, D, ronri.広げる(箱, c.半径))
        if t is not None:
            当たり.append((t, k))
    当たり.sort(key=lambda x: x[0])
    if 当たり and not c.貫通:
        当たり = 当たり[:1]
        D = min(D, 当たり[0][0] + 0.3)        # луч кончается на первой цели
    n = 0
    for _, k in 当たり:
        e = 実体.get(k)
        if e is None or not e.isValid() or e.isDead():
            continue
        if 傷つける(p, e, c.傷, c.傷種, c.無敵無視):
            n += 1
            if c.火秒 > 0.0 and not e.isDead():
                e.setFireTicks(max(int(e.getFireTicks()), int(c.火秒 * 20.0)))
    # --- вид -----------------------------------------------------------------------------------------------------------
    try:
        _光を見せる(eye, c, D, uid, 向き)
    except BaseException as ex:
        amazake.log.warn("дисплей луча: " + str(ex))
    終 = ronri.点(E, 向き, D)
    終 = (終[0], 終[1] - c.球下, 終[2])
    try:
        for x, y, z in ronri.道の点(E, 向き, min(c.球前, D), D, 0.6):
            at = kouka.場所(world, x, y - c.球下, z)
            _粒(world, Particle.DUST, at, 2, 0.12, 0.0, _粉(0x8A0A06, 1.5))
            _粒(world, Particle.SMOKE, at, 1, 0.1, 0.01)
            _粒(world, Particle.SMALL_FLAME, at, 1, 0.12, 0.01)
        end = kouka.場所(world, 終[0], 終[1], 終[2])
        _粒(world, Particle.FLAME, end, 18, 0.35, 0.06)
        _粒(world, Particle.LAVA, end, 4, 0.25, 0.0)
        _粒(world, Particle.DUST, end, 16, 0.4, 0.0, _粉(0xD0201A, 1.6))
        _粒(world, Particle.LARGE_SMOKE, end, 6, 0.3, 0.02)
        _粒(world, Particle.CRIMSON_SPORE, end, 20, 0.6, 0.0)
        _衝撃(world, end, c, uid, eye)
    except BaseException as ex:
        amazake.log.debug("частицы луча: " + str(ex))
    鳴らす(world, eye, c.撃音)
    鳴らす(world, kouka.場所(world, 終[0], 終[1], 終[2]), c.着弾音)
    return D, n


def _光を見せる(eye, c, D, uid, 向き):
    """Дисплей луча у сферы (не двигается): модель вдоль +Z от z=8 до 16 px (0.5 блока) → масштаб Z = 2·Lv;
    ширина растёт за beam-grow-ticks, держится, сходится в ноль за beam-fade-ticks."""
    at = _球の場所(eye, c)
    Lv = max(0.5, D - c.球前)
    R = ronri.光線R(Lv)
    W = c.幅
    s0 = W * c.始幅倍
    d = kouka.生む(at, kouka.効果品(kouka.光線, R), kouka.変形(0, 0, 0, s0, s0, 2.0 * Lv), c.視距離, "beam", uid)
    if d is None:
        return
    kouka.動かす(d, 1, c.伸び, kouka.変形(0, 0, 0, W, W, 2.0 * Lv))
    kouka.動かす(d, 1 + c.伸び + c.保持, c.消え, kouka.変形(0, 0, 0, 0.0, 0.0, 2.0 * Lv))
    kouka.後で消す(d, 1 + c.伸び + c.保持 + c.消え + 1)


def _衝撃(world, end, c, uid, eye):
    """Вспышка-круг у цели (сфера на полном заряде, billboard): растёт за impact-ticks и сжимается."""
    at = kouka.場所(world, end.getX(), end.getY(), end.getZ(), eye.getYaw(), eye.getPitch())
    if not amazake.owns(at):
        return
    S = c.爆大きさ
    d = kouka.生む(at, kouka.効果品(kouka.球, ronri.R上限), kouka.変形(0, 0, 0, S * 0.25, S * 0.25, S * 0.25), c.視距離,
                  "impact", uid, 看板=True)
    if d is None:
        return
    kouka.動かす(d, 1, c.爆時間, kouka.変形(0, 0, 0, S, S, S))
    kouka.動かす(d, 1 + c.爆時間, 3, kouka.変形(0, 0, 0, 0.01, 0.01, 0.01))
    kouka.後で消す(d, 1 + c.爆時間 + 4)


# ------------------------------------------------------------------------------------------------- прерывания

def 止める(p, 方法):
    st = 状態表.get(str(p.getUniqueId()))
    if st is not None and st.詠唱 is not None:
        st.詠唱.終える(方法)


def 去る(p):
    """Выход игрока: сотворение — убрать сразу; истёкшие перезарядки — забыть."""
    uid = str(p.getUniqueId())
    st = 状態表.get(uid)
    if st is None:
        return
    if st.詠唱 is not None:
        st.詠唱.終える("即")
    now = 今ms()
    if st.背刺終 <= now and st.余燼終 <= now:
        状態表.pop(uid, None)


def 片付け():
    """@on_unload: все сотворения — закончены; дисплеи убирает kouka.片付け."""
    for st in list(状態表.values()):
        s = st.詠唱
        if s is not None:
            s.終わった = True
            st.詠唱 = None
    状態表.clear()
    kouka.片付け()


def 情報():
    return "палка: игроков " + str(len(状態表)) + ", дисплеев " + str(kouka.数())
