# 持ち主 — один онлайн-игрок (Java runtime/Holder): замах — кадры на предмете в слоте удара и огненный след;
# кольцо частиц, пока оружие с orbit в основной руке. Все поля, кроме 掘り中 и 最後の地震, трогает только поток игрока.
#
# Отличие от Java ради интерпретатора GraalPy: в Java у каждого игрока задача КАЖДЫЙ тик (читает предмет в руке).
# Здесь постоянной задачи нет: задача замаха (раз в тик) живёт только 合計 тиков после взмаха и сама кончается;
# задача кольца (раз в period тиков) — только пока оружие с orbit в руке; кто взял такое оружие, замечает
# PlayerItemHeldEvent сразу, а «наблюдатель» раз в 10 тиков (только если у какого-то оружия включён orbit) — когда
# предмет попал в руку иначе (подобрал, переложил в инвентаре). Вид тот же.

import amazake
import dougu
import ronri
import settei
from dougu import Bukkit, Particle, SoundCategory

見張り周期 = 10


class 持ち主:
    __slots__ = ("player", "経過", "枠番", "振り武器", "見せ枠", "最後の振り", "振り数", "振り中",
                 "鏡", "枠の写し", "新鮮", "掘り中", "最後の地震", "輪中", "輪tick", "輪周期", "見張り中")

    def __init__(self, player):
        self.player = player
        # замах
        self.経過 = -1
        self.枠番 = -1
        self.振り武器 = None
        self.見せ枠 = None
        self.最後の振り = -(2 ** 62)
        self.振り数 = 0
        self.振り中 = False        # идёт ли задача замаха
        self.新鮮 = False          # swingFresh: следующий запуск идущей задачи замаха кадр не двигает
        self.鏡 = None             # живой ItemStack в слоте (зеркало) — чтобы снять кадр после выгрузки
        self.枠の写し = None        # наш предмет с кадром (копия) — из него считается «без кадра»
        # копает блок (замах не показываем): пишут события блоков
        self.掘り中 = False
        # тик сервера последней дрожи (откат); пишет поток удара
        self.最後の地震 = -(2 ** 62)
        # кольцо
        self.輪中 = False
        self.輪tick = 0
        self.輪周期 = 0           # период, с которым запущена задача кольца
        self.見張り中 = False

    # ------------------------------------------------------------------------------------ жизнь

    def 開始(self):
        """Вход / включение (поток игрока): хвосты прошлого запуска или сбоя — кадры на предметах."""
        dougu.全部外す(self.player.getInventory())
        self.輪を確かめる()

    def 停止(self):
        """Выход (поток игрока): кадры со всего инвентаря и курсора. Задачи игрока ядро снимает само."""
        self.振り終わり(True)

    # ------------------------------------------------------------------------------------ замах

    def 腕振り(self):
        """Взмах рукой (поток игрока).
        Откат min-interval считается по Bukkit.getCurrentTick(): в Java — по счётчику задачи игрока, которая шла
        каждый тик; здесь постоянной задачи нет, тик сервера — ближайшая замена (тот же счёт, пока регион не отстаёт)."""
        if dougu.終了済み[0] or self.掘り中:
            return
        s = settei.現在
        w = s.武器(dougu.武器ID(self.player.getInventory().getItemInMainHand()))
        if w is None or w.振り is None or self.掘り中:
            return
        sw = w.振り
        今 = Bukkit.getCurrentTick()
        if 今 - self.最後の振り < sw.最小間隔:
            return
        self.振り終わり(False)
        self.最後の振り = 今
        self.経過 = 0
        self.枠番 = self.player.getInventory().getHeldItemSlot()
        self.振り武器 = w.id
        self.振り数 += 1
        if sw.音 is not None:
            l = self.player.getLocation()
            l.getWorld().playSound(l, sw.音.鍵, SoundCategory.PLAYERS, sw.音.音量, sw.音.高さ)
        self._一歩(s)
        if self.経過 < 0:
            return
        if self.振り中:
            # задача уже идёт (прошлый замах): как swingFresh в Java — её следующий запуск первый кадр держит
            self.新鮮 = True
        else:
            # новая задача стартует через тик (delay 1) — эта задержка и есть удержание первого кадра, не пропускаем
            self.新鮮 = False
            self.振り中 = True
            amazake.run_for_player(self.player, self._振りの_tick, period=1)

    def _振りの_tick(self):
        if dougu.終了済み[0] or self.経過 < 0:
            self.振り中 = False
            return False
        if self.新鮮:
            self.新鮮 = False   # замах начат после прошлого запуска — первый кадр держим (swingFresh)
            return None
        self.経過 += 1
        self._一歩(settei.現在)
        if self.経過 < 0:
            self.振り中 = False
            return False
        return None

    def _一歩(self, s):
        w = s.武器(self.振り武器)
        inv = self.player.getInventory()
        item = inv.getItem(self.枠番)
        if w is None or w.振り is None or inv.getHeldItemSlot() != self.枠番 or self.振り武器 != dougu.武器ID(item):
            self.振り終わり(True)   # переложили предмет, сменили слот, перечитали настройки — снять кадры везде
            return
        コマ = w.振り.コマ
        枠 = コマ.時点(self.経過)
        if 枠 is None:
            self.振り終わり(False)
            return
        if 枠 != self.見せ枠:
            次 = dougu.枠付き(item, 枠)
            if 次 is not None:
                inv.setItem(self.枠番, 次)
                self.枠の写し = 次
                self.鏡 = inv.getItem(self.枠番)
                if dougu.終了済み[0]:
                    # выгрузка началась, пока шаг шёл: @on_unload мог взять старое зеркало — снимаем сами (поток наш)
                    self.振り終わり(True)
                    return
            self.見せ枠 = 枠
        if w.振り.軌跡:
            self._軌跡(w.振り, コマ)

    def _軌跡(self, sw, コマ):
        """Огненный след по дуге: начинается после первого кадра (замах назад) и идёт до конца замаха."""
        start = float(コマ.時間[0]) if コマ.時間 else 0.0
        len_ = コマ.合計() - start
        if len_ <= 0 or self.経過 < start:
            return
        s0 = max(0.0, (self.経過 - start) / len_)
        s1 = min(1.0, (self.経過 - start + 1) / len_)
        eye = self.player.getEyeLocation()
        d = eye.getDirection()
        look = (d.getX(), d.getY(), d.getZ())
        yaw = eye.getYaw()
        world = eye.getWorld()
        ex, ey, ez = eye.getX(), eye.getY(), eye.getZ()
        dust = _粉(sw.色1, sw.色2, _軌跡の大きさ)
        DUST = Particle.DUST_COLOR_TRANSITION
        FLAME = Particle.FLAME
        n = sw.軌跡点
        for i in range(n):
            s = s0 + (s1 - s0) * (i + 0.5) / n
            p = ronri.弧(s, sw.軌跡半径, sw.軌跡距離, look, yaw)
            x = ex + p[0]
            y = ey + p[1]
            z = ez + p[2]
            world.spawnParticle(DUST, x, y, z, 1, 0.03, 0.03, 0.03, 0.0, dust)
            if i % 2 == 0:
                world.spawnParticle(FLAME, x, y, z, 1, 0.02, 0.02, 0.02, 0.006)

    def 振り終わり(self, 全部):
        """Конец замаха: снять кадр со слота удара; 全部 — со всего инвентаря и курсора (предмет мог переехать)."""
        if self.経過 < 0 and not 全部:
            return
        inv = self.player.getInventory()
        if 全部:
            dougu.全部外す(inv)
            直した = dougu.枠付き(self.player.getItemOnCursor(), None)
            if 直した is not None:
                self.player.setItemOnCursor(直した)
        elif self.枠番 >= 0:
            直した = dougu.枠付き(inv.getItem(self.枠番), None)
            if 直した is not None:
                inv.setItem(self.枠番, 直した)
        self.経過 = -1
        self.枠番 = -1
        self.振り武器 = None
        self.見せ枠 = None
        self.鏡 = None
        self.枠の写し = None

    def 振り中か(self):
        return self.経過 >= 0

    def 後で外す(self):
        """Выгрузка из чужого потока: кадр с живого предмета снимается задачей планировщика игрока без Python
        (контекст сейчас закроется). Меняется сам стек (зеркало), а не слот — если предмет переложили, кадр снимется
        там, где он лежит, и чужой предмет не затрётся."""
        鏡 = self.鏡
        写し = self.枠の写し
        if 鏡 is None or 写し is None:
            return False
        きれい = dougu.枠付き(写し, None)
        if きれい is None:
            return False
        cmd = きれい.getData(dougu.CMD)
        if cmd is None:
            return dougu.後で実体に(self.player, 鏡, "resetData", (dougu.CMD,))
        return dougu.後で実体に(self.player, 鏡, "setData", (dougu.CMD, cmd))

    # ------------------------------------------------------------------------------------ кольцо

    def 輪を確かめる(self, item=None):
        """Поток игрока: оружие с orbit в основной руке → запустить задачу кольца (если ещё нет)."""
        if not _輪あり[0]:
            return
        self._見張り開始()
        if self.輪中:
            return
        if item is None:
            item = self.player.getInventory().getItemInMainHand()
        w = settei.現在.武器(dougu.武器ID(item))
        if w is None or w.軌道 is None:
            return
        o = w.軌道
        self.輪中 = True
        self.輪周期 = o.周期
        # тик кольца выравнивается по периоду (в Java — счётчик тиков игрока, частицы на тиках, кратных period)
        self.輪tick = (Bukkit.getCurrentTick() // o.周期) * o.周期
        amazake.run_for_player(self.player, self._輪の_tick, period=o.周期)

    def _輪の_tick(self):
        o = None
        w = settei.現在.武器(dougu.武器ID(self.player.getInventory().getItemInMainHand()))
        if w is not None:
            o = w.軌道
        if o is None or dougu.終了済み[0]:
            self.輪中 = False
            return False
        if o.周期 != self.輪周期:
            # другое оружие с другим period или /cweapons reload: Java сразу шла по новому period — перезапуск задачи
            self.輪中 = False
            self.輪を確かめる()
            return False
        self.輪tick += o.周期
        if self.player.isInvisible() or self.player.isDead():
            return None
        self._輪(o)
        return None

    def _輪(self, o):
        at = self.player.getLocation()
        world = at.getWorld()
        yaw = self.player.getBodyYaw()
        c = ronri.局所(yaw, o.右, o.上, o.前)
        cx = at.getX() + c[0]
        cy = at.getY() + c[1] - (0.3 if self.player.isSneaking() else 0.0)
        cz = at.getZ() + c[2]
        dust = _粉(o.色1, o.色2, o.大きさ)
        DUST = Particle.DUST_COLOR_TRANSITION
        tick = self.輪tick
        段 = tick // o.周期
        i = 0
        for p in ronri.輪(tick, o.点数, o.半径, o.度毎tick, o.傾き, o.揺れ, yaw):
            world.spawnParticle(DUST, cx + p[0], cy + p[1], cz + p[2], 1, 0.0, 0.0, 0.0, 0.0, dust)
            if o.炎毎 > 0 and (i + 段) % o.炎毎 == 0:
                world.spawnParticle(Particle.SMALL_FLAME, cx + p[0], cy + p[1], cz + p[2], 1, 0.0, 0.0, 0.0, 0.0)
            i += 1
        if o.手の火 and tick % 4 == 0:
            h = ronri.局所(yaw, 0.4, 1.0, 0.25)
            world.spawnParticle(Particle.SMALL_FLAME, at.getX() + h[0], at.getY() + h[1], at.getZ() + h[2],
                                1, 0.06, 0.08, 0.06, 0.004)

    def _見張り開始(self):
        if self.見張り中:
            return
        self.見張り中 = True
        amazake.run_for_player(self.player, self._見張り, period=見張り周期)

    def _見張り(self):
        if not _輪あり[0]:
            self.見張り中 = False
            return False
        if not self.輪中:
            self.輪を確かめる()
        return None


# Включён ли orbit хоть у одного оружия (пересчитывает main.読み込み). Нет — ни наблюдателей, ни задач кольца.
_輪あり = [False]

# Размер пыли следа (в Java 1.3f).
_軌跡の大きさ = settei.f32(1.3)

# DustTransition по (цвет, цвет, размер): объект неизменяемый, создаём один раз.
_粉表 = {}


def _粉(色1, 色2, 大きさ):
    k = (色1, 色2, 大きさ)
    d = _粉表.get(k)
    if d is None:
        d = dougu.粉(色1, 色2, 大きさ)
        _粉表[k] = d
    return d
