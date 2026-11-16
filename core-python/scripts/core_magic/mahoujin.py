# 魔法陣 — магический круг перед заклинателем: один ItemDisplay (модель azerus:moon_staff_circle, 4 плоскости —
# кольцо, руны, гексаграмма, сердцевина). Вращение, пульс и проявление рун считает шейдер по GameTime и заряду из
# цвета (R = заряд*250), поэтому сервер шлёт только: появление, один пакет роста (интерполяция на весь заряд),
# цвет раз в update-ticks и телепорт, когда игрок сдвинулся/повернулся.
#
# Поворот: билборд FIXED, у дисплея yaw/pitch глаз → его локальная +Z смотрит туда же, куда игрок; модель — в
# плоскости XY, значит круг стоит поперёк взгляда. Перенос (0, −down, forward) — в осях дисплея: круг всегда перед
# глазами. Всё — в потоке заклинателя: дисплей стоит у его глаз, то есть в его регионе (проверяется amazake.owns).

import amazake

import keisan
import kyoutsuu
from kyoutsuu import Location, ItemDisplayTransform, Billboard, Brightness
from settei import f32

#: Дисплеи, которые гаснут после конца заклинания: UUID → (дисплей, место). Для уборки при выгрузке.
残像 = {}


def 位置(eye):
    """Место дисплея: глаза игрока с его yaw/pitch."""
    return Location(eye.getWorld(), eye.getX(), eye.getY(), eye.getZ(), f32(eye.getYaw()), f32(eye.getPitch()))


def 生む(場所, item, 変形, 視距離, 種類, 主):
    """Дисплей эффекта (круг или луч) в месте 場所 — поток региона этого места."""

    def 準備(e):
        e.setPersistent(False)
        e.setItemStack(item)
        e.setItemDisplayTransform(ItemDisplayTransform.NONE)
        e.setBillboard(Billboard.FIXED)
        e.setBrightness(Brightness(15, 15))      # обязательно: UV2 = (240, 240) — часть метки шейдера
        e.setViewRange(f32(視距離))
        e.setDisplayWidth(f32(0.0))              # 0 — без отсечения по рамке (круг шире хитбокса)
        e.setDisplayHeight(f32(0.0))
        e.setShadowRadius(f32(0.0))
        e.setShadowStrength(f32(0.0))
        e.setInterpolationDelay(0)
        e.setInterpolationDuration(0)
        e.setTransformation(変形)
        d = amazake.data(e)
        d.set_string(kyoutsuu.効果鍵, 種類)
        d.set_string(kyoutsuu.主鍵, 主)

    return 場所.getWorld().spawn(場所, kyoutsuu.ItemDisplay, 準備)


def 残す(d, 場所):
    try:
        残像[str(d.getUniqueId())] = (d, 場所)
    except BaseException:
        pass


def 忘れる(d):
    try:
        残像.pop(str(d.getUniqueId()), None)
    except BaseException:
        pass


def 後で消す(d, 場所, 遅れ):
    """Убрать дисплей через 遅れ тиков в потоке его региона (дисплей больше не двигается)."""
    残す(d, 場所)

    def 消去():
        忘れる(d)
        if not d.isValid():
            return
        if amazake.owns(d):
            d.remove()
        else:
            kyoutsuu.消す(d)     # сущность всё же в другом потоке — пусть уберёт её собственный планировщик

    try:
        amazake.run_in_region(場所, 消去, delay=max(1, int(遅れ)))
    except BaseException as e:
        amazake.log.debug("задача региона: " + str(e))
        忘れる(d)
        kyoutsuu.消す(d)


class 魔法陣:
    """Круг одного заклинания. Все методы — в потоке заклинателя."""

    __slots__ = ("player", "主", "cfg", "d", "場所", "R", "解放済み")

    def __init__(self, player, cfg):
        self.player = player
        self.主 = str(player.getUniqueId())
        self.cfg = cfg          # снимок settei на время заклинания
        self.d = None
        self.場所 = None
        self.R = -1
        self.解放済み = False

    # ----------------------------------------------------------------------------------------- размер

    def 大きさ(self, charge):
        c = self.cfg.円
        return c.直径 * (c.始倍 + (1.0 - c.始倍) * keisan.挟む(charge, 0.0, 1.0))

    def _変形(self, s):
        c = self.cfg.円
        return kyoutsuu.変形(c.前, c.下, s, s, s)

    # ----------------------------------------------------------------------------------------- жизнь

    def 出す(self, eye, charge, 残り):
        """Поставить дисплей у глаз с размером заряда charge и запустить рост до полного за 残り тиков."""
        if kyoutsuu.終了済み[0]:
            return False
        at = 位置(eye)
        if not amazake.owns(at):
            return False
        c = self.cfg.円
        self.R = keisan.充填R(charge)
        self.d = 生む(at, kyoutsuu.効果品(c.モデル, self.R), self._変形(self.大きさ(charge)), c.視距離, "circle", self.主)
        self.d.setTeleportDuration(c.移動時間)
        self.場所 = at
        if kyoutsuu.終了済み[0]:
            # выгрузка началась, пока дисплей рождался — убираем сами
            self.d.remove()
            self.d = None
            return False
        if 残り > 0:
            d = self.d
            全 = self.大きさ(1.0)

            def 伸ばす():
                # следующий тик: одна интерполяция на весь оставшийся заряд (в тик рождения она бы не сработала)
                if d is self.d and d.isValid() and amazake.owns(d):
                    d.setInterpolationDelay(0)
                    d.setInterpolationDuration(max(1, int(残り) - 1))
                    d.setTransformation(self._変形(全))

            amazake.run_once_for_player(self.player, 伸ばす, delay=1)
        return True

    def 生きている(self):
        d = self.d
        return d is not None and d.isValid()

    def 追う(self, eye, charge, 残り):
        """Шаг заряда: дисплей догоняет глаза (телепорт с плавностью), а если он пропал или ушёл в чужой регион —
        бросаем его (убирает регион) и ставим новый с текущим размером."""
        d = self.d
        if d is None or not d.isValid() or not amazake.owns(d):
            if d is not None:
                kyoutsuu.消す(d)
                self.d = None
            return self.出す(eye, charge, 残り)
        at = self.場所
        c = self.cfg.円
        動いた = (at is None or not at.getWorld().equals(eye.getWorld())
                 or (at.getX() - eye.getX()) ** 2 + (at.getY() - eye.getY()) ** 2 + (at.getZ() - eye.getZ()) ** 2
                 > c.追従距離 * c.追従距離
                 or keisan.角度差(at.getYaw(), eye.getYaw()) > c.追従角
                 or abs(at.getPitch() - eye.getPitch()) > c.追従角)
        if 動いた:
            to = 位置(eye)
            if not amazake.owns(to):
                kyoutsuu.消す(d)
                self.d = None
                return False
            d.teleportAsync(to)
            self.場所 = to
        return True

    def 色(self, charge):
        """Заряд → цвет предмета (R), только если поменялся."""
        d = self.d
        R = keisan.充填R(charge)
        if d is None or R == self.R or not d.isValid() or not amazake.owns(d):
            return
        self.R = R
        d.setItemStack(kyoutsuu.効果品(self.cfg.円.モデル, R))

    def _手放す(self):
        d = self.d
        self.d = None
        self.解放済み = True
        return d

    def 散る(self):
        """Срыв (отпустил рано, сменил предмет, слишком долго): круг сжимается за fizzle-ticks и исчезает."""
        d = self._手放す()
        if d is None or not d.isValid():
            return
        if not amazake.owns(d):
            kyoutsuu.消す(d)
            return
        c = self.cfg.円
        d.setInterpolationDelay(0)
        d.setInterpolationDuration(c.消散)
        d.setTransformation(self._変形(0.01))
        後で消す(d, self.場所, c.消散 + 1)

    def 放つ(self):
        """Выстрел: вспышка (круг чуть больше), потом угасание; дисплей уже не двигается."""
        d = self._手放す()
        if d is None or not d.isValid():
            return
        if not amazake.owns(d):
            kyoutsuu.消す(d)
            return
        c = self.cfg.円
        d.setItemStack(kyoutsuu.効果品(c.モデル, keisan.R上限))
        d.setInterpolationDelay(0)
        d.setInterpolationDuration(c.閃光)
        d.setTransformation(self._変形(c.直径 * c.閃光倍))
        場所 = self.場所
        変形 = self._変形(0.01)

        def 消えゆく():
            if d.isValid() and amazake.owns(d):
                d.setInterpolationDelay(0)
                d.setInterpolationDuration(c.消える)
                d.setTransformation(変形)

        amazake.run_in_region(場所, 消えゆく, delay=c.閃光)
        後で消す(d, 場所, c.閃光 + c.消える + 1)

    def 即消す(self):
        """Сразу (выход, смерть, другой мир, телепорт, выгрузка): свой регион — удалить, иначе — задачей региона."""
        d = self._手放す()
        if d is not None:
            kyoutsuu.消す(d)

    def 表示(self):
        return self.d
