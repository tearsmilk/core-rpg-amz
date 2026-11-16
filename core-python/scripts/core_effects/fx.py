# 演出 — эффекты одного онлайн-игрока (порт runtime/PlayerFx.java и runtime/Phantoms.java).
#
# Всё здесь — поток игрока (его события, run_for_player / run_once_for_player), кроме помеченного «любой поток».
#
# Отличие от Java ради интерпретатора GraalPy: задача игрока идёт ТОЛЬКО пока на нём есть эффекты (Java тикала всех
# онлайн-игроков всегда) и с шагом 2 тика (刻み), а пока идёт паника с дезориентацией или игрок без сознания — каждый
# тик (толчки WASD и гашение скольжения должны идти каждый тик). Первый запуск задачи — через 1 тик: считается за 1. Счётчики при шаге 2 считаются по каждому тику внутри шага (часы
# кровотечения, жажда, приступы тошноты, интервалы), поэтому время, урон, обморок, предупреждения — те же.

import math
import random
import time

import java
import amazake

import logic
import items
from logic import 出血, 恐慌, 温熱, 呪い, 種類一覧
from common import (Bukkit, Material, Sound, SoundCategory, Particle, Location, Vector, Component, Key,
                    NamedTextColor, ShadowColor, Title, TitleTimes, Duration, PotionEffectType, PersistentDataType,
                    LivingEntity, NK, 属性, 全属性, 全修飾鍵, 状態鍵, 幻影鍵, 恐慌攻撃鍵, 恐慌体力鍵, 恐慌速度鍵,
                    気絶速度鍵, 気絶跳躍鍵, 設定, 送る, プラグイン, ジャバ処理, ジャバで実体へ, ジャバで人へ,
                    f32, 角, 鳴らす, PAPI表, 表を出す)

Color = java.type("org.bukkit.Color")
DustOptions = java.type("org.bukkit.Particle$DustOptions")
EntityType = java.type("org.bukkit.entity.EntityType")
SpawnReason = java.type("org.bukkit.event.entity.CreatureSpawnEvent$SpawnReason")
Zombie = java.type("org.bukkit.entity.Zombie")
AbstractSkeleton = java.type("org.bukkit.entity.AbstractSkeleton")
Mannequin = java.type("org.bukkit.entity.Mannequin")
ResolvableProfile = java.type("io.papermc.paper.datacomponent.item.ResolvableProfile")

重ね字体 = Key.key("coreeffects", "overlay")
血 = Material.REDSTONE_BLOCK.createBlockData()
血の粉 = DustOptions(Color.fromRGB(120, 0, 8), f32(1.1))
_暗闇 = PotionEffectType.DARKNESS
_盲目 = PotionEffectType.BLINDNESS
_吐き気 = PotionEffectType.NAUSEA

#: id всех живых фантомов этого экземпляра (str UUID) — быстрая проверка в событиях урона
幻影ID = set()

#: str UUID игроков без сознания (меняется только в потоке игрока) — быстрый выход в частых событиях
気絶者 = set()


def 現刻():
    """Номер тика сервера (для перезарядки бинта — в тиках, как у Java); нет — по часам."""
    try:
        return int(Bukkit.getCurrentTick())
    except Exception:
        return int(time.monotonic() * 20)

基本刻み = 2


# =================================================================================================================
# 幻影群 (Phantoms): мобы и «игроки» (манекены со скином игрока), которых видит только он
# =================================================================================================================

def _幻影の音(型名):
    if 型名 in ("ZOMBIE", "HUSK", "DROWNED"):
        return Sound.ENTITY_ZOMBIE_AMBIENT
    if 型名 in ("SKELETON", "STRAY"):
        return Sound.ENTITY_SKELETON_AMBIENT
    if 型名 == "WITHER_SKELETON":
        return Sound.ENTITY_WITHER_SKELETON_AMBIENT
    if 型名 == "ENDERMAN":
        return Sound.ENTITY_ENDERMAN_STARE
    if 型名 == "CREEPER":
        return Sound.ENTITY_CREEPER_PRIMED
    if 型名 in ("SPIDER", "CAVE_SPIDER"):
        return Sound.ENTITY_SPIDER_AMBIENT
    return Sound.AMBIENT_CAVE


def 他所で消す(e):
    """Удалить сущность в потоке её владельца — чистой Java-задачей на планировщике сущности (работает и при
    выгрузке системы, когда Python-задачи уже отменены). Не вышло — сущность и так не сохраняется в мир."""
    try:
        ジャバで実体へ(e, ジャバ処理("org.bukkit.entity.Entity", "remove", [], e))
    except Exception:
        pass


def _安全(名, 関数, *引数):
    """Шаг тика, который не должен сорвать весь тик (ошибка взаимодействия с Java — в лог, тик идёт дальше)."""
    try:
        return 関数(*引数)
    except Exception as e:
        amazake.log.warn("core_effects: " + 名 + ": " + str(e))
        return None


class 幻影群:

    def __init__(self, player, 実例):
        self.player = player
        self.実例 = 実例
        self.幽霊 = []          # [сущность, тик смерти, звук, str UUID]
        self.次出現 = -1
        self._警告済み = False

    def 数(self):
        return len(self.幽霊)

    def 刻(self, 今, s, 刻み):
        """Тик паники (s — настройки фантомов)."""
        if self.幽霊:
            self._動かす(今, s, 刻み)
        if not s.有効 or s.最大数 <= 0 or not s.種類:
            return
        if self.次出現 < 0:
            self.次出現 = 今 + 20 * s.出現間隔.選ぶ(random) // 2
        if 今 >= self.次出現:
            self.次出現 = 今 + 20 * s.出現間隔.選ぶ(random)
            群れ = s.群れ.選ぶ(random)
            i = 0
            while i < 群れ and len(self.幽霊) < s.最大数:
                self._出す(今, s)
                i += 1

    def _動かす(self, 今, s, 刻み):
        eye = self.player.getEyeLocation()
        world = eye.getWorld()
        ex = eye.getX()
        ez = eye.getZ()
        動く = s.接近速度 > 0 and logic.跨いだ(今, 刻み, 2)
        残す = []
        for g in self.幽霊:
            e = g[0]
            if not e.isValid():
                幻影ID.discard(g[3])
                continue
            if not Bukkit.isOwnedByCurrentRegion(e) or not e.getWorld().equals(world):
                幻影ID.discard(g[3])
                他所で消す(e)
                continue
            at = e.getLocation()
            tx = ex - at.getX()
            tz = ez - at.getZ()
            距離 = math.hypot(tx, tz)
            if 今 >= g[1] or 距離 < s.消失距離:
                self._消える(e, g[3], s.音 and 距離 < s.消失距離)
                continue
            残す.append(g)
            if e.getFireTicks() > 0:
                e.setFireTicks(0)   # вместо слушателя EntityCombustEvent: фантом не горит (на солнце, в огне)
            yaw = math.degrees(math.atan2(-tx, tz))
            if 動く:
                k = s.接近速度 * 2 / 距離
                次 = at.clone().add(tx * k, 0.0, tz * k)
                次.setYaw(角(yaw))
                次.setPitch(0.0)
                if Bukkit.isOwnedByCurrentRegion(次):
                    e.teleportAsync(次)
            else:
                e.setRotation(角(yaw), 0.0)
            if s.音 and g[2] is not None and random.random() < 刻み / 50.0:
                鳴らす(self.player, at, g[2], SoundCategory.HOSTILE, 0.7, 0.6)
        self.幽霊 = 残す

    def _出す(self, 今, s):
        p = プラグイン()
        if p is None:
            return
        base = self.player.getLocation()
        world = base.getWorld()
        型名 = random.choice(s.種類)
        for _ in range(6):
            yaw = math.radians(base.getYaw() + random.uniform(-40, 40))
            d = s.距離.選ぶ(random) + random.random()
            x = base.getX() - math.sin(yaw) * d
            z = base.getZ() + math.cos(yaw) * d
            at = self._床(world, x, base.getY(), z)
            if at is None or not Bukkit.isOwnedByCurrentRegion(at):
                continue
            at.setYaw(角(math.degrees(math.atan2(-(base.getX() - at.getX()), base.getZ() - at.getZ()))))
            e = self._作る(world, at, 型名)
            if e is None:
                return
            self.player.showEntity(p, e)
            id_ = str(e.getUniqueId())
            幻影ID.add(id_)
            音 = _幻影の音(str(e.getType().name()))
            self.幽霊.append([e, 今 + 20 * s.寿命.選ぶ(random), 音, id_])
            if s.音 and 音 is not None:
                鳴らす(self.player, at, 音, SoundCategory.HOSTILE, 0.9, 0.5)
            return

    def _作る(self, world, at, 型名):
        if 型名 in ("PLAYER", "MANNEQUIN"):
            t = EntityType.MANNEQUIN
        else:
            try:
                t = EntityType.valueOf(型名)
            except Exception:
                return None
            if t.getEntityClass() is None or not t.isAlive() or not t.isSpawnable():
                return None
        player = self.player
        実例 = self.実例

        def 準備(raw):
            raw.setVisibleByDefault(False)
            raw.setPersistent(False)
            raw.setSilent(True)
            raw.setInvulnerable(True)
            raw.getPersistentDataContainer().set(NK[幻影鍵], PersistentDataType.STRING, 実例)
            if java.instanceof(raw, LivingEntity):
                raw.setAI(False)
                raw.setCollidable(False)
                raw.setRemoveWhenFarAway(True)
                raw.setCanPickupItems(False)
            # Вместо слушателя EntityCombustEvent (оно идёт каждый тик на каждом горящем на солнце мобе — для
            # Python слишком часто): фантомы просто не горят днём. Цели без ИИ они и так не выбирают.
            if java.instanceof(raw, Zombie) or java.instanceof(raw, AbstractSkeleton):
                try:
                    raw.setShouldBurnInDay(False)
                except Exception:
                    pass
            if java.instanceof(raw, Mannequin):
                raw.setProfile(ResolvableProfile.resolvableProfile(player.getPlayerProfile()))
                raw.setDescription(Component.empty())
                raw.setImmovable(True)

        e = world.spawn(at, t.getEntityClass(), SpawnReason.CUSTOM, 準備)
        return e if e is not None and java.instanceof(e, LivingEntity) else None

    @staticmethod
    def _床(world, x, y, z):
        """Пол под точкой: от y+3 вниз до y−6 первый твёрдый блок с двумя проходимыми над ним."""
        bx = math.floor(x)
        bz = math.floor(z)
        if not world.isChunkLoaded(bx >> 4, bz >> 4):
            return None
        if not Bukkit.isOwnedByCurrentRegion(Location(world, x, y, z)):
            return None
        by = math.floor(y) + 3
        while by >= math.floor(y) - 6:
            床 = world.getBlockAt(bx, by, bz)
            if 床.getType().isSolid():
                上1 = 床.getRelative(0, 1, 0)
                if 上1.isPassable() and not 上1.isLiquid() and 床.getRelative(0, 2, 0).isPassable():
                    return Location(world, bx + 0.5, float(by + 1), bz + 0.5)
            by -= 1
        return None

    def _消える(self, e, id_, 脅す):
        at = e.getLocation().add(0.0, 1.0, 0.0)
        self.player.spawnParticle(Particle.LARGE_SMOKE, at, 18, 0.25, 0.5, 0.25, 0.02)
        self.player.spawnParticle(Particle.SQUID_INK, at, 8, 0.2, 0.4, 0.2, 0.02)
        if 脅す:
            鳴らす(self.player, at, Sound.ENTITY_WARDEN_NEARBY_CLOSE, SoundCategory.HOSTILE, 0.8, 1.4)
        幻影ID.discard(id_)
        e.remove()

    def 叩かれた(self, id_):
        """Ударили фантома (по str UUID): растворить сразу."""
        for i, g in enumerate(self.幽霊):
            if g[3] == id_:
                del self.幽霊[i]
                if Bukkit.isOwnedByCurrentRegion(g[0]):
                    self._消える(g[0], id_, False)
                else:
                    幻影ID.discard(id_)
                    他所で消す(g[0])
                return True
        return False

    def 全消し(self):
        """Убрать всех (конец паники, выход)."""
        for g in self.幽霊:
            幻影ID.discard(g[3])
            if Bukkit.isOwnedByCurrentRegion(g[0]):
                g[0].remove()
            else:
                他所で消す(g[0])
        self.幽霊 = []
        self.次出現 = -1

    def 閉店(self):
        """Выгрузка системы (любой поток): свои — сразу, чужие — чистыми Java-задачами в потоках сущностей."""
        for g in self.幽霊:
            幻影ID.discard(g[3])
            try:
                if Bukkit.isOwnedByCurrentRegion(g[0]):
                    g[0].remove()
                else:
                    他所で消す(g[0])
            except Exception:
                pass
        self.幽霊 = []
        self.次出現 = -1


# =================================================================================================================
# プレイヤー効果 (PlayerFx)
# =================================================================================================================

def _我らの(e):
    """Наш вспомогательный ванильный эффект: скрытый, «окружающий», не длиннее 80 тиков."""
    return e.isAmbient() and not e.hasParticles() and not e.hasIcon() and e.getDuration() <= 80


class プレイヤー効果:

    def __init__(self, player, 帳, 実例):
        self.player = player
        self.uid = str(player.getUniqueId())
        self.実体ID = player.getEntityId()
        self.帳 = 帳
        self.幻影 = 幻影群(player, 実例)
        self.課題 = None
        self.刻み = 基本刻み
        self.閉じた = False
        self.死因 = None
        self.今 = 0
        self.汚れ = False
        self.ねじれ = 0.0
        self.次ねじれ = 0
        self.吐き気まで = 0
        self.血脈まで = 0
        self.重ね送信 = ""
        self.重ね部品 = None
        self.重ね時刻 = -10000
        self.自暗闇 = False
        self.自盲目 = False
        self.自吐き気 = False
        self.最後包帯 = None
        self.初回 = False
        self.表 = None        # последние ответы PlaceholderAPI (dict), отданные в common.PAPI表
        self.表古い = True

    # --- любой поток ---------------------------------------------------------------------------------------------

    def 気絶中(self):
        a = self.帳.取る(出血)
        return a is not None and a.b > 0

    # --- запуск и задача ------------------------------------------------------------------------------------------

    def 開始(self):
        self._古い修飾を外す()
        self._適用(設定())
        self._気絶を記す()
        self._表を作る(設定())
        self._起動()

    def _必要刻み(self, s):
        # Каждый тик: толчки WASD при панике и гашение скольжения без сознания (у Java — каждый тик).
        if self.気絶中() or (self.帳.ある(恐慌) and s.恐慌.方向狂い):
            return 1
        return 基本刻み

    def _気絶を記す(self):
        if self.気絶中():
            気絶者.add(self.uid)
        else:
            気絶者.discard(self.uid)

    def _表を作る(self, s):
        """Ответы PlaceholderAPI заранее: Java-мост читает их из PAPI表 без Python. В Java — только при изменении
        (секунды меняются раз в секунду), пересчёт — раз в 10 тиков и при каждом изменении эффектов."""
        写 = self.帳.写し()
        表 = {"count": str(len(写))}
        b = 写.get(出血)
        表["unconscious"] = "yes" if b is not None and b.b > 0 else "no"
        w = 写.get(温熱)
        期限 = s.温熱.期限
        表["warmth_thirst"] = "0" if w is None or 期限 <= 0 else str(min(100, w.a * 100 // 期限))
        for t in 種類一覧:
            a = 写.get(t)
            表[t] = "no" if a is None else "yes"
            表[t + "_seconds"] = "0" if a is None else str(logic.残り秒(a.残り))
            表[t + "_time"] = "" if a is None else logic.時間表示(a.残り)
        self.表古い = False
        if 表 != self.表:
            self.表 = 表
            if self.閉じた:
                return
            表を出す(self.uid, 表)

    def _起動(self):
        """Задача игрока — только пока есть эффекты."""
        if self.閉じた or self.帳.空():
            return
        if self.課題 is not None and not self.課題.isCancelled():
            return
        self.刻み = self._必要刻み(設定())
        self.初回 = True   # первый запуск задачи — через 1 тик после планирования, а не через 刻み
        self.課題 = amazake.run_for_player(self.player, self._刻, self.刻み)

    # --- управление -------------------------------------------------------------------------------------------------

    def 与える(self, 種, 刻):
        """Наложить/продлить; 刻 −1 — бесконечно."""
        s = 設定()
        初a = 20 * s.呪い.間隔.選ぶ(random) if 種 == 呪い else 0
        p = self.player
        if self.帳.与える(種, 刻, 初a):
            送る(p, s, "got-" + 種)
            if 種 == 恐慌:
                self.次ねじれ = 0
            _安全("звук", self._与えた音, 種)
        self.汚れ = True
        self._適用(s)
        self.保存()
        self._表を作る(s)
        if self.課題 is not None and not self.課題.isCancelled() and self._必要刻み(s) != self.刻み:
            self.課題.cancel()
            self.課題 = None
        self._起動()

    def _与えた音(self, 種):
        p = self.player
        if 種 == 恐慌:
            鳴らす(p, p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.0, 0.8)
        elif 種 == 出血:
            鳴らす(p, p.getLocation(), Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, SoundCategory.PLAYERS, 1.0, 0.7)
        elif 種 == 呪い:
            鳴らす(p, p.getLocation(), Sound.ENTITY_WITHER_AMBIENT, SoundCategory.PLAYERS, 0.5, 1.6)

    def 外す(self, 種, 治った):
        """Снять один; 治った — «снят» вместо «прошёл»."""
        if not self.帳.外す(種):
            return False
        s = 設定()
        self._終わり(種, 治った, s)
        self.汚れ = True
        self.保存()
        self._気絶を記す()
        self._表を作る(s)
        return True

    def 全消し(self, 告知):
        あった = self.帳.全消し()
        s = 設定()
        if 告知:
            for t in あった:
                self._終わり(t, True, s)
        self._全部戻す()
        self.汚れ = True
        self.保存()
        self._気絶を記す()
        self._表を作る(s)
        return len(あった)

    def 品を使う(self, 印):
        """Съеден/выпит/применён предмет с метками 印: снять то, что им лечится, «утолить» тепло. Список снятых."""
        s = 設定()
        治 = []
        印 = set(印)
        for t in 種類一覧:
            if self.帳.ある(t) and not s.共通[t].治療.isdisjoint(印):
                if self.外す(t, True):
                    治.append(t)
        if self.帳.ある(温熱) and not s.温熱.渇き癒し.isdisjoint(印):
            with self.帳.錠:
                a = self.帳.生(温熱)
                if a is not None:
                    a.a = 0
                    a.b = 0
            self._自吐き気を消す()
            送る(self.player, s, "quenched")
            _安全("звук", 鳴らす, self.player, self.player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH,
                SoundCategory.PLAYERS, 0.6, 1.4)
            self.汚れ = True
            self.表古い = True
        return 治

    def 包帯準備(self, 待ち刻):
        return self.最後包帯 is None or 現刻() - self.最後包帯 >= 待ち刻

    def 包帯印(self):
        self.最後包帯 = 現刻()

    def 治せる(self, 印):
        s = 設定()
        for t in 種類一覧:
            if self.帳.ある(t) and 印 in s.共通[t].治療:
                return True
        return False

    # --- тик ------------------------------------------------------------------------------------------------------

    def _刻(self):
        if self.閉じた:
            self.課題 = None
            return False
        p = self.player
        if not p.isValid():
            return None   # мёртв (ждём возрождения) — как у Java: тик пропускается
        刻み = self.刻み
        if self.初回:
            刻み = 1          # первый запуск задачи идёт через 1 тик (run_for_player) — считаем ровно 1
            self.初回 = False
        self.今 += 刻み
        s = 設定()
        for t in self.帳.進める(刻み):
            self._終わり(t, False, s)
            self.汚れ = True
        if self.帳.空():
            self._全部戻す()
            if self.汚れ:
                self.保存()
            self._気絶を記す()
            self._表を作る(s)
            self.課題 = None
            return False   # эффектов нет — задача больше не нужна (вернётся при новом эффекте)
        if p.isDead():
            return None
        モード = str(p.getGameMode().name())
        生存 = モード == "SURVIVAL" or モード == "ADVENTURE"
        if self.帳.ある(出血):
            _安全("кровотечение", self._出血, s, 生存, 刻み)
        if p.isDead() or self.帳.空():
            return None
        if self.帳.ある(恐慌):
            _安全("паника", self._恐慌, s, 生存, 刻み)
        if self.帳.ある(温熱):
            _安全("тепло", self._温熱, s, 生存, 刻み)
        if p.isDead() or self.帳.空():
            return None
        if self.帳.ある(呪い):
            _安全("проклятье", self._呪い, s, 生存, 刻み)
        今 = self.今
        if logic.跨いだ(今, 刻み, 10):
            self._適用(s)
            self.表古い = True
        if self.表古い:
            self._表を作る(s)
        _安全("экран", self._重ね, s, self._重ね部品(s))
        if s.表示.アクションバー and logic.跨いだ(今, 刻み, s.表示.バー間隔):
            _安全("actionbar", self._アクションバー, s)
        if logic.跨いだ(今, 刻み, 100):
            self.保存()
        必要 = self._必要刻み(s)
        if 必要 != self.刻み:
            # Началась/кончилась паника с дезориентацией или обморок — перезапуск задачи с другим шагом.
            self.課題 = None
            self.刻み = 必要
            self.初回 = True
            self.課題 = amazake.run_for_player(p, self._刻, 必要)
            return False
        return None

    def _出血(self, s, 生存, 刻み):
        b = s.出血
        出来事 = []
        with self.帳.錠:
            a = self.帳.生(出血)
            if a is None:
                return
            経過0 = a.経過 - 刻み
            気絶まで = b.気絶まで if 生存 else 0
            for i in range(1, 刻み + 1):
                a.a, a.b, 事 = logic.出血の刻(a.a, a.b, 経過0 + i, b.ダメージ間隔, 気絶まで, b.再気絶まで, b.気絶時間)
                if 事 != logic.無:
                    出来事.append(事)
            気絶 = a.b > 0
        if 出来事:
            self._気絶を記す()
            self.表古い = True
        p = self.player
        for 事 in 出来事:
            if 事 == logic.傷:
                if 生存:
                    self._血を流す(b)
                    if p.isDead():
                        return
            elif 事 == logic.気絶:
                送る(p, s, "faint")
                p.setSprinting(False)
                鳴らす(p, p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.0, 0.6)
                self._適用(s)
                self.汚れ = True
            elif 事 == logic.覚醒:
                送る(p, s, "wake")
                self._適用(s)
                self.汚れ = True
        if 気絶:
            v = p.getVelocity()
            if p.isOnGround() and (v.getX() != 0 or v.getZ() != 0):
                p.setVelocity(Vector(0.0, v.getY(), 0.0))
        if b.粒子:
            回 = logic.跨いだ回数(self.今, 刻み, b.粒子間隔)
            if 回 > 0:
                l = p.getLocation()
                w = p.getWorld()
                for _ in range(回):
                    w.spawnParticle(Particle.FALLING_DUST, l.clone().add(0.0, 0.9, 0.0), 2, 0.18, 0.25, 0.18, 0.0, 血)
                    if random.randrange(3) == 0:
                        w.spawnParticle(Particle.DUST, l.clone().add(0.0, 0.05, 0.0), 3, 0.25, 0.02, 0.25, 0.0, 血の粉)
                    if random.randrange(4) == 0:
                        w.spawnParticle(Particle.BLOCK, l.clone().add(0.0, 0.1, 0.0), 4, 0.15, 0.05, 0.15, 0.0, 血)

    def _血を流す(self, b):
        p = self.player
        h = p.getHealth()
        量 = logic.許容ダメージ(h, b.ダメージ, b.最低体力)
        if 量 <= 0:
            return
        self.血脈まで = self.今 + 10
        if h - 量 <= 0.0001:
            self._殺す(出血)
            return
        p.setHealth(h - 量)
        p.playHurtAnimation(0.0)
        鳴らす(p, p.getLocation(), Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 0.6, 0.8)

    def _殺す(self, 原因):
        self.死因 = 原因
        try:
            self.player.setHealth(0.0)
        finally:
            self.死因 = None

    def _恐慌(self, s, 生存, 刻み):
        pc = s.恐慌
        p = self.player
        if pc.吐き気間隔 > 0 and s.画面.吐き気:
            a = self.帳.取る(恐慌)
            if a is not None:
                目標 = pc.吐き気間隔 // 3
                for 経過 in range(a.経過 - 刻み + 1, a.経過 + 1):
                    if 経過 % pc.吐き気間隔 == 目標:
                        self.吐き気まで = self.今 + pc.吐き気時間
        if self.今 >= self.次ねじれ:
            self.ねじれ = pc.ねじれ角.選ぶ(random)
            self.次ねじれ = self.今 + 20 * pc.ねじれ変更.選ぶ(random)
        if (pc.方向狂い and 生存 and not self.気絶中() and not p.isFlying() and not p.isGliding()
                and not p.isInsideVehicle() and not p.isSwimming() and p.isOnGround()):
            inp = p.getCurrentInput()
            前 = (1 if inp.isForward() else 0) - (1 if inp.isBackward() else 0)
            左 = (1 if inp.isLeft() else 0) - (1 if inp.isRight() else 0)
            if 前 or 左:
                方向 = logic.狂った方向(前, 左, p.getLocation().getYaw(), self.ねじれ)
                v = p.getVelocity()
                nx, nz = logic.混ぜる(v.getX(), v.getZ(), 方向, pc.狂い速さ, pc.狂い強さ)
                p.setVelocity(Vector(nx, v.getY(), nz))
        self.幻影.刻(self.今, pc.幻影, 刻み)

    def _温熱(self, s, 生存, 刻み):
        w = s.温熱
        段 = -1
        致 = False
        癒 = False
        with self.帳.錠:
            a = self.帳.生(温熱)
            if a is None:
                return
            経過0 = a.経過 - 刻み
            for i in range(1, 刻み + 1):
                if 生存:
                    a.a += 1
                st = logic.温熱段階(a.a, w.期限, w.警告率)
                if st > a.b:
                    段 = st
                    a.b = st
                if logic.回復時(経過0 + i, w.回復間隔):
                    癒 = True
            致 = logic.致命(a.a, w.期限)
        p = self.player
        if 段 > 0:
            送る(p, s, "warmth-warn-" + str(min(3, 段)))
            鳴らす(p, p.getLocation(), Sound.BLOCK_FIRE_AMBIENT, SoundCategory.PLAYERS, 1.0, 0.7)
            self.汚れ = True
        if 致 and 生存:
            self._殺す(温熱)
            return
        if 癒 and w.回復 > 0:
            inst = p.getAttribute(属性["max_health"])
            上限 = 20.0 if inst is None else inst.getValue()
            h = p.getHealth()
            if 0 < h < 上限:
                p.setHealth(min(上限, h + w.回復))

    def _呪い(self, s, 生存, 刻み):
        c = s.呪い
        発 = False
        with self.帳.錠:
            a = self.帳.生(呪い)
            if a is None:
                return
            a.a -= 刻み
            if a.a <= 0:
                a.a = 20 * c.間隔.選ぶ(random)
                発 = True
        if 発 and 生存:
            self._品を取る(s)
            self.汚れ = True

    def _品を取る(self, s):
        """Проклятье: забрать немного случайного обычного предмета."""
        c = s.呪い
        p = self.player
        inv = p.getInventory()
        枠 = []
        for i in range(0 if c.ホットバー else 9, 36):
            if items.呪いが取れる(inv.getItem(i), c):
                枠.append(i)
        if c.オフハンド and items.呪いが取れる(inv.getItemInOffHand(), c, 包帯除外=False):
            枠.append(40)
        if not 枠:
            return
        i = random.choice(枠)
        it = inv.getItem(i)
        if it is None or it.isEmpty():
            return
        n = logic.取る数(it.getAmount(), c.量.選ぶ(random), random.random() < c.全部確率)
        取った = it.clone()
        取った.setAmount(n)
        名前 = it.effectiveName()
        if n >= it.getAmount():
            inv.setItem(i, None)
        else:
            it.setAmount(it.getAmount() - n)
            inv.setItem(i, it)
        l = p.getLocation()
        if c.落とす:
            # Вылетает из рук в случайную сторону; поднять обратно можно не сразу (pickup-delay).
            角 = random.uniform(0, math.pi * 2)
            速 = random.uniform(0.12, 0.22)
            元 = p.getEyeLocation().subtract(0.0, 0.35, 0.0)
            遅延 = c.拾い遅延

            def 準備(item):
                item.setPickupDelay(遅延)
                item.setVelocity(Vector(math.cos(角) * 速, 0.28, math.sin(角) * 速))

            落ちた = p.getWorld().dropItem(元, 取った, 準備)
            落ちた.setThrower(None)
        p.getWorld().spawnParticle(Particle.WITCH, l.clone().add(0.0, 1.0, 0.0), 14, 0.3, 0.5, 0.3, 0.02)
        鳴らす(p, l, Sound.ENTITY_ALLAY_ITEM_TAKEN, SoundCategory.PLAYERS, 0.8, 0.5)
        鳴らす(p, l, Sound.ENTITY_VEX_AMBIENT, SoundCategory.PLAYERS, 0.5, 0.6)
        送る(p, s, "curse-dropped" if c.落とす else "curse-took", item=名前, amount=str(n))

    # --- атрибуты и ванильные эффекты --------------------------------------------------------------------------------

    def _適用(self, s):
        """Привести атрибуты и вспомогательные эффекты к нужным (раз в 10 тиков и при изменениях)."""
        恐 = self.帳.ある(恐慌)
        気 = self.気絶中()
        pc = s.恐慌
        self._修飾("attack_damage", 恐慌攻撃鍵, pc.追加攻撃, "add", "ADD_NUMBER", 恐 and pc.追加攻撃 > 0)
        減 = pc.体力減少
        inst = self.player.getAttribute(属性["max_health"])
        if inst is not None:
            mine = inst.getModifier(NK[恐慌体力鍵])
            無し = inst.getValue() - (0.0 if mine is None else mine.getAmount())
            減 = min(減, max(0.0, 無し - 2))   # не меньше 1 сердца
        self._修飾("max_health", 恐慌体力鍵, -減, "add", "ADD_NUMBER", 恐 and 減 > 0)
        self._修飾("movement_speed", 恐慌速度鍵, -pc.鈍足, "multiply", "MULTIPLY_SCALAR_1",
                  恐 and pc.鈍足 > 0 and not 気)
        self._修飾("movement_speed", 気絶速度鍵, -1.0, "multiply", "MULTIPLY_SCALAR_1", 気)
        self._修飾("jump_strength", 気絶跳躍鍵, -1.0, "multiply", "MULTIPLY_SCALAR_1", 気)

        # Тьма — пока паника.
        if 恐 and pc.暗闇 and s.画面.暗闇:
            self.自暗闇 = self._確保("darkness", _暗闇) or self.自暗闇
        elif self.自暗闇:
            self._落とす("darkness", _暗闇)
            self.自暗闇 = False
        # Слепота — пока без сознания.
        if 気 and s.画面.気絶盲目:
            self.自盲目 = self._確保("blindness", _盲目) or self.自盲目
        elif self.自盲目:
            self._落とす("blindness", _盲目)
            self.自盲目 = False
        # Тошнота — приступы паники и сильный жар.
        吐 = 恐 and self.今 < self.吐き気まで
        w = self.帳.取る(温熱)
        if (w is not None and s.温熱.吐き気率 > 0
                and logic.渇き割合(w.a, s.温熱.期限) * 100 >= s.温熱.吐き気率):
            吐 = True
        if 吐 and s.画面.吐き気:
            self.自吐き気 = self._確保("nausea", _吐き気) or self.自吐き気
        else:
            self._自吐き気を消す()

    def _自吐き気を消す(self):
        if self.自吐き気:
            self._落とす("nausea", _吐き気)
            self.自吐き気 = False

    def _修飾(self, 属名, 鍵, 量, 演算, 演算名, 欲しい):
        inst = self.player.getAttribute(属性[属名])
        if inst is None:
            return
        今 = inst.getModifier(NK[鍵])
        if not 欲しい:
            if 今 is not None:
                amazake.remove_modifier(self.player, 属名, 鍵)
            return
        if 今 is not None and 今.getAmount() == 量 and str(今.getOperation().name()) == 演算名:
            return
        amazake.add_modifier(self.player, 属名, 鍵, 量, 演算)   # transient, снимается ядром при выгрузке

    def _確保(self, 名, 型):
        """Наш ванильный эффект (скрытый, ambient). True — наложен нами; чужой (зелье, плагин) не трогаем."""
        今 = self.player.getPotionEffect(型)
        if 今 is not None and not _我らの(今):
            return False
        if 今 is None or 今.getDuration() < 30:
            amazake.add_effect(self.player, 名, duration=80, amplifier=0, ambient=True, particles=False, icon=False)
        return True

    def _落とす(self, 名, 型):
        今 = self.player.getPotionEffect(型)
        if 今 is not None and _我らの(今):
            if not amazake.remove_effect(self.player, 名):
                self.player.removePotionEffect(型)   # наложен прошлым запуском

    def _古い修飾を外す(self):
        """Модификаторы с нашими ключами, оставшиеся от прошлого запуска (вход, включение, уборка)."""
        p = self.player
        for 属名 in 全属性:
            inst = p.getAttribute(属性[属名])
            if inst is None:
                continue
            for 鍵 in 全修飾鍵:
                if inst.getModifier(NK[鍵]) is not None:
                    amazake.remove_modifier(p, 属名, 鍵)

    def _終わり(self, 種, 治った, s):
        """Конец одного эффекта: сообщение и уборка того, что он держал."""
        送る(self.player, s, "cured" if 治った else "ended", 解析={"effect": s.共通[種].名前})
        if 種 == 恐慌:
            self.幻影.全消し()
            self.吐き気まで = 0
        if 種 == 温熱:
            self._自吐き気を消す()
        self._適用(s)

    def _全部戻す(self):
        """Вернуть игроку всё: атрибуты, ванильные эффекты, заголовок, фантомы."""
        self._古い修飾を外す()
        if self.自暗闇:
            self._落とす("darkness", _暗闇)
        if self.自盲目:
            self._落とす("blindness", _盲目)
        if self.自吐き気:
            self._落とす("nausea", _吐き気)
        self.自暗闇 = self.自盲目 = self.自吐き気 = False
        self.幻影.全消し()
        if self.重ね送信:
            self.player.clearTitle()
            self.重ね送信 = ""
            self.重ね部品 = None

    # --- экран ----------------------------------------------------------------------------------------------------

    def _重ね部品(self, s):
        if not s.画面.有効:
            return ()
        出 = []
        b = self.帳.取る(出血)
        if b is not None and b.b > 0:
            return ((logic.層_暗, 7), (logic.層_血, 2))
        if self.帳.ある(恐慌):
            出.append((logic.層_暗, 2))
            出.append((logic.層_恐, 2))
        if b is not None:
            濃 = logic.血の濃さ(b.a, s.出血.気絶まで)
            if self.今 < self.血脈まで:
                濃 += 1
            出.append((logic.層_血, 濃))
        w = self.帳.取る(温熱)
        if w is not None:
            出.append((logic.層_熱, 1 + logic.温熱段階(w.a, s.温熱.期限, s.温熱.警告率)))
        if self.帳.ある(呪い):
            出.append((logic.層_呪, 1))
        return tuple(出)

    def _重ね(self, s, 部品):
        更新 = s.画面.更新刻
        if 部品 == self.重ね部品 and self.今 - self.重ね時刻 < 更新:
            return   # то же самое и ещё не пора обновлять — строку даже не собираем
        文 = logic.重ねる(部品)
        if not 文:
            if self.重ね送信:
                self.player.clearTitle()
                self.重ね送信 = ""
            self.重ね部品 = 部品
            return
        if 文 == self.重ね送信 and self.今 - self.重ね時刻 < 更新:
            self.重ね部品 = 部品
            return
        初 = not self.重ね送信
        self.重ね送信 = 文
        self.重ね部品 = 部品
        self.重ね時刻 = self.今
        c = Component.text(文).font(重ね字体).color(NamedTextColor.WHITE).shadowColor(ShadowColor.none())
        self.player.showTitle(Title.title(c, Component.empty(), TitleTimes.times(
            Duration.ofMillis(250) if 初 else Duration.ZERO, Duration.ofMillis(50 * (更新 + 40)),
            Duration.ofMillis(500))))

    def _アクションバー(self, s):
        写 = self.帳.写し()
        if not 写:
            return
        b = 写.get(出血)
        if b is not None and b.b > 0:
            self.player.sendActionBar(amazake.mm(s.表示.気絶書式, time=logic.時間表示(b.b)))
            return
        棒 = Component.empty()
        最初 = True
        for t, a in 写.items():
            書 = s.表示.書式.get(t)
            if not 書 or not 書.strip():
                continue
            渇 = ""
            if t == 温熱 and s.温熱.期限 > 0:
                渇 = str(int(math.floor(logic.渇き割合(a.a, s.温熱.期限) * 100 + 0.5))) + "%"
            if not 最初:
                棒 = 棒.append(amazake.mm(s.表示.区切り))
            最初 = False
            棒 = 棒.append(amazake.mm(書, time=logic.時間表示(a.残り), thirst=渇))
        self.player.sendActionBar(棒)

    # --- сохранение ----------------------------------------------------------------------------------------------------

    def 保存(self):
        """Записать состояние в PDC игрока (coreeffects:state)."""
        d = amazake.data(self.player)
        if self.帳.空():
            d.remove(状態鍵)
        else:
            d.set_string(状態鍵, self.帳.符号化())
        self.汚れ = False

    def 退出(self):
        """Выход игрока (его поток): сохранить и убрать всё показанное."""
        self.閉じた = True
        if self.課題 is not None:
            self.課題.cancel()
            self.課題 = None
        気絶者.discard(self.uid)
        PAPI表.remove(self.uid)
        self.保存()
        self._全部戻す()

    def 死亡(self, s):
        if s.死亡時解除:
            self.帳.全消し()
            self._全部戻す()
            self.保存()
            self._気絶を記す()
            self._表を作る(s)

    def 閉店(self):
        """Выгрузка системы (@on_unload, любой поток). Модификаторы и ванильные эффекты снимает ядро core-python
        (они наложены через amazake.add_modifier / add_effect). Остальное — сохранение в PDC, заголовок, фантомы.
        Фантомы — всегда чистыми Java-задачами (Python-задачи при выгрузке отменяются)."""
        self.閉じた = True
        気絶者.discard(self.uid)
        PAPI表.remove(self.uid)
        self.幻影.閉店()
        p = self.player
        if amazake.owns(p):
            self.保存()
            self._全部戻す()
            return
        # Чужой поток. Как у Java-системы: SystemContext.runOnceForPlayer — при остановке сервера выполняется сразу,
        # иначе в потоке игрока. Не вышло — планировщик сущности; при остановке сервера — запись напрямую.
        pdc = p.getPersistentDataContainer()
        if self.帳.空():
            処理 = ジャバ処理("org.bukkit.persistence.PersistentDataContainer", "remove",
                         ["org.bukkit.NamespacedKey"], pdc, NK[状態鍵])
        else:
            処理 = ジャバ処理("org.bukkit.persistence.PersistentDataContainer", "set",
                         ["org.bukkit.NamespacedKey", "org.bukkit.persistence.PersistentDataType", "java.lang.Object"],
                         pdc, NK[状態鍵], PersistentDataType.STRING, self.帳.符号化())
        if not ジャバで人へ(p, 処理) and not ジャバで実体へ(p, 処理) and _止まる():
            処理.run()
        if self.重ね送信:
            消 = ジャバ処理("net.kyori.adventure.audience.Audience", "clearTitle", [], p)
            if not ジャバで人へ(p, 消):
                ジャバで実体へ(p, 消)


def _止まる():
    try:
        return bool(Bukkit.isStopping())
    except Exception:
        return False
