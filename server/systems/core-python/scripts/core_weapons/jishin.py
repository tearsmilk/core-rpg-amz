# 地震 — дрожь земли от цели крита (Java runtime/Tremor): кольцо расходится на radius за duration тиков. На каждом
# шаге — частицы верхнего блока каждого столбца кольца; копии этих блоков (BlockDisplay, не сохраняются в чанк)
# подпрыгивают на несколько пикселей и исчезают; мир не меняется. Всё — в потоке региона точки удара (событие
# удара, потом задача региона раз в тик); столбцы чужих регионов и незагруженных чанков пропускаются.
#
# Для интерпретатора: столбцы каждого шага волны посчитаны заранее (ronri.波の表); копии блоков разложены по тику
# рождения, и каждый тик трогаются только те, кому сейчас подниматься (1-й тик), опускаться (3-й) или исчезать (6-й),
# а не все подряд, как в Java.

import math
import random

import java
import amazake
import dougu
import ronri
import settei
from dougu import Bukkit, Particle, SoundCategory, Location

BlockDisplay = dougu.クラス("org.bukkit.entity.BlockDisplay")
Brightness = java.type("org.bukkit.entity.Display$Brightness")
Transformation = java.type("org.bukkit.util.Transformation")
Vector3f = java.type("org.joml.Vector3f")
Quaternionf = java.type("org.joml.Quaternionf")
AxisAngle4f = java.type("org.joml.AxisAngle4f")
BlockFace = java.type("org.bukkit.block.BlockFace")

_ずれ = settei.f32(-0.005)
_大 = settei.f32(1.01)
_視距離 = settei.f32(0.6)

#: Идущие дрожи (для /cweapons list и уборки при выгрузке). Пишут потоки регионов — под GIL, операции атомарны.
進行中 = set()


def 表示数():
    n = 0
    for t in list(進行中):
        n += t.表示数
    return n


def _変形(高さ, 傾き):
    return Transformation(Vector3f(_ずれ, 高さ, _ずれ), 傾き if 傾き is not None else Quaternionf(),
                          Vector3f(_大, _大, _大), Quaternionf())


def 地表(world, x, y, z):
    """Верхний твёрдый непрозрачный блок столбца у высоты удара (на 1 выше … 4 ниже), над которым не непрозрачный.
    Сверху вниз: признак «над ним непрозрачно» берётся из прошлого шага — вдвое меньше вызовов Java, чем в Java-версии."""
    上が塞ぐ = world.getType(x, y + 2, z).isOccluding()
    for yy in range(y + 1, y - 5, -1):
        m = world.getType(x, yy, z)
        塞ぐ = m.isOccluding()
        if 塞ぐ and not 上が塞ぐ and m.isSolid():
            return world.getBlockAt(x, yy, z)
        上が塞ぐ = 塞ぐ
    return None


class 揺れ:
    __slots__ = ("cfg", "world", "cx", "cz", "baseY", "攻撃者", "印", "段", "tick", "数", "前半径", "触れた",
                 "生まれ", "表示数", "task", "表", "ox", "oz")

    def __init__(self, cfg, 中心, 攻撃者, 印):
        self.cfg = cfg
        self.world = 中心.getWorld()
        self.cx = 中心.getBlockX() + 0.5
        self.cz = 中心.getBlockZ() + 0.5
        self.baseY = 中心.getBlockY()
        self.ox = int(math.floor(self.cx))
        self.oz = int(math.floor(self.cz))
        self.攻撃者 = 攻撃者
        self.印 = 印
        self.段 = 0
        self.tick = 0
        self.数 = 0
        self.前半径 = 0.0
        self.触れた = set()
        self.生まれ = {}     # тик рождения → [(display, наклон)]
        self.表示数 = 0
        self.task = None
        self.表 = ronri.波の表(cfg.長さ, cfg.半径)

    def 開始(self):
        """Поток региона центра: звук, первый шаг, задача на остальные."""
        c = Location(self.world, self.cx, float(self.baseY), self.cz)
        for s in self.cfg.音:
            self.world.playSound(c, s.鍵, SoundCategory.PLAYERS, s.音量, s.高さ)
        self.world.spawnParticle(Particle.EXPLOSION, c.clone().add(0.0, 0.2, 0.0), 1)
        進行中.add(self)
        self._波()
        self.task = amazake.run_in_region(c, self._毎tick, delay=1, period=1)

    def _毎tick(self):
        if dougu.終了済み[0]:
            return False   # выгрузка: копии убирает 全部消す
        self.tick += 1
        if self.段 < self.cfg.長さ:
            self._波()
        self._動かす()
        if self.段 >= self.cfg.長さ and self.表示数 == 0:
            進行中.discard(self)
            return False
        return None

    def _波(self):
        から, r, 列 = self.表[self.段]
        self.前半径 = r
        self.段 += 1
        cfg = self.cfg
        world = self.world
        ox, oz, baseY = self.ox, self.oz, self.baseY
        粒 = cfg.粒
        揺らす = cfg.揺らす
        BLOCK = Particle.BLOCK
        PILLAR = Particle.DUST_PILLAR
        for dx, dz in 列:
            if dougu.終了済み[0]:
                return
            x = ox + dx
            z = oz + dz
            if not world.isChunkLoaded(x >> 4, z >> 4) or not Bukkit.isOwnedByCurrentRegion(world, x >> 4, z >> 4):
                continue
            top = 地表(world, x, baseY, z)
            if top is None:
                continue
            px = x + 0.5
            py = top.getY() + 1.02
            pz = z + 0.5
            data = None
            if 粒 > 0:
                data = top.getBlockData()
                world.spawnParticle(BLOCK, px, py, pz, 粒, 0.3, 0.05, 0.3, 0.08, data)
            if cfg.砂柱 and random.randrange(3) == 0:
                if data is None:
                    data = top.getBlockData()
                world.spawnParticle(PILLAR, px, py, pz, 2, 0.25, 0.0, 0.25, 0.1, data)
            if 揺らす and self.数 < cfg.最大表示:
                self._揺らす(top, data)
        self._触る(から, r)

    def _揺らす(self, top, data):
        above = top.getRelative(BlockFace.UP)
        角 = settei.f32(math.radians(random.uniform(-5.0, 5.0)))
        傾き = Quaternionf(AxisAngle4f(角, 1 if random.random() < 0.5 else 0, 0, 0 if random.random() < 0.5 else 1))
        if data is None:
            data = top.getBlockData()
        光 = Brightness(above.getLightFromBlocks(), above.getLightFromSky())
        印 = self.印

        def 準備(e):
            e.setPersistent(False)
            e.setBlock(data)
            # свет блока над поверхностью: внутри твёрдого блока копия была бы чёрной
            e.setBrightness(光)
            e.setTransformation(_変形(0.0, None))
            e.setViewRange(_視距離)
            e.getPersistentDataContainer().set(dougu.印, dougu.文字型, 印)

        d = self.world.spawn(top.getLocation(), BlockDisplay, 準備)
        self.生まれ.setdefault(self.tick, []).append((d, 傾き))
        self.表示数 += 1
        self.数 += 1
        if dougu.終了済み[0]:
            # выгрузка началась во время шага: 全部消す мог пройти раньше, чем копия попала в 生まれ — убираем сами
            # (копия только что создана в этом потоке; двойной remove безвреден)
            d.remove()

    def _動かす(self):
        """Подскок копий: вверх за 2 тика, вниз за 3, удалить на 6-м."""
        t = self.tick
        上 = self.生まれ.get(t - 1)
        if 上:
            h = settei.f32(self.cfg.揺れ高さ)
            if self._生きている(t - 1, 上):
                for d, 傾き in self.生まれ[t - 1]:
                    d.setInterpolationDelay(0)
                    d.setInterpolationDuration(2)
                    d.setTransformation(_変形(h, 傾き))
        下 = self.生まれ.get(t - 3)
        if 下:
            if self._生きている(t - 3, 下):
                for d, _ in self.生まれ[t - 3]:
                    d.setInterpolationDelay(0)
                    d.setInterpolationDuration(3)
                    d.setTransformation(_変形(0.0, None))
        消 = self.生まれ.pop(t - 6, None)
        if 消:
            for d, _ in 消:
                if d.isValid():
                    d.remove()
                self.表示数 -= 1

    def _生きている(self, 鍵, 列):
        """Убрать из корзины копии, которых уже нет (убиты, чанк выгружен) — как registry.remove в Java, чтобы
        /cweapons list считал только живые. True — в корзине кто-то остался."""
        残り = [x for x in 列 if x[0].isValid()]
        if len(残り) != len(列):
            self.表示数 -= len(列) - len(残り)
            if 残り:
                self.生まれ[鍵] = 残り
            else:
                del self.生まれ[鍵]
        return bool(残り)

    def _触る(self, から, まで):
        """Толчок игрокам и (если задан) урон существам на кольце — каждому один раз за дрожь."""
        cfg = self.cfg
        蹴る = cfg.跳ね > 0
        痛む = cfg.傷 > 0
        if not 蹴る and not 痛む:
            return
        c = Location(self.world, self.cx, float(self.baseY), self.cz)
        for e in self.world.getNearbyLivingEntities(c, まで + 1.0, 3.0):
            if not Bukkit.isOwnedByCurrentRegion(e):
                continue
            uid = str(e.getUniqueId())
            if uid in self.触れた:
                continue
            dx = e.getX() - self.cx
            dz = e.getZ() - self.cz
            d = math.sqrt(dx * dx + dz * dz)
            if d < から - 0.7 or d > まで + 0.7:
                continue
            自分 = uid == self.攻撃者
            if 自分 and not cfg.自分も:
                continue
            self.触れた.add(uid)
            if 痛む and not 自分:
                e.damage(cfg.傷)
            if 蹴る and java.instanceof(e, dougu.Player) and e.isOnGround():
                _蹴る(e, cfg.跳ね)

    def 全部消す(self):
        """Выгрузка: свои копии — сразу, если поток владеет ими, иначе задачей планировщика сущности без Python."""
        for 列 in list(self.生まれ.values()):
            for d, _ in 列:
                try:
                    if amazake.owns(d):
                        d.remove()
                    else:
                        dougu.後で実体に(d, d, "remove")
                except Exception as e:
                    amazake.log.warn("не удалось убрать копию блока:", e)
        self.生まれ.clear()
        self.表示数 = 0


def _蹴る(p, k):
    def 押す():
        v = p.getVelocity()
        p.setVelocity(v.setY(max(v.getY(), k)))

    amazake.run_once_for_player(p, 押す)
