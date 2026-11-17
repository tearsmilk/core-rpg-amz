# 光線 — выстрел посоха: белый луч из круга вперёд до range блоков (до первого твёрдого блока), каждая живая цель
# на пути получает урон один раз (луч пронзает). Всё — в потоке заклинателя, в момент отпускания ПКМ.
#
# ПОТОКИ. Сущности и блоки можно трогать только в потоке их региона. Поэтому путь [0, range] режется на куски по
# piece-length; у каждого куска берётся AABB (отрезок, расширенный на hit-radius + 1) и все столбцы чанков, которых он
# касается, должны быть загружены и принадлежать нашему региону (amazake.owns). Первый непрошедший кусок обрывает луч,
# как стена. На практике это почти никогда не режет луч: регионы (как в Folia) сливают все соседние загруженные
# секции, а чанки в 29 блоках от игрока держит его же билет загрузки — весь путь лежит в регионе заклинателя;
# «чужой» столбец означает незагруженную землю. Так нет ни задач в чужих регионах, ни ожидания других потоков, ни
# обращения к сущности из чужого потока. rayTraceBlocks и getNearbyEntities зовутся только внутри проверенной длины.
#
# Урон: DamageSource(тип из реестра, по умолчанию minecraft:magic) с заклинателем как причиной и прямым источником →
# EntityDamageByEntityEvent с атакующим-игроком: PvP, защита регионов и прочие плагины решают как обычно. Сам
# заклинатель исключён (по UUID), даже если стоит внутри собственного луча.
#
# Барьеры (kekkai.py): после блоков луч проверяется по реестру барьеров (чистые данные, keisan.遮断) — первый
# барьер на пути обрывает луч: урона за ним нет (и цели «за плоскостью» барьера отсекаются, даже если их хитбокс с
# запасом hit-radius до неё дотягивается), луч короче, в точке удара — частицы barrier-hit, звук и вспышка ячейки
# (задачей в регионе барьера). Барьер гасит лучи всех, и своего заклинателя тоже.
#
# Вид: дисплей луча стоит у глаз и НЕ двигается (никогда не пересекает границу региона): луч растёт масштабом по
# локальной +Z (модель — от z=8 до z=16, т.е. 0.5 блока, масштаб Z = 2*Lv) с интерполяцией дисплея, потом гаснет.

import java
import amazake

import keisan
import kyoutsuu
import mahoujin
import kekkai
import damage_bridge
from kyoutsuu import Location, Vector, BoundingBox, FluidCollisionMode, LivingEntity, ArmorStand, Player, GameMode

DamageSource = java.type("org.bukkit.damage.DamageSource")
DamageType = java.type("org.bukkit.damage.DamageType")

_傷種 = {}


def 傷の種類(鍵):
    """DamageType по ключу из реестра (кэш); не найден — MAGIC."""
    t = _傷種.get(鍵)
    if t is not None:
        return t
    try:
        RegistryAccess = java.type("io.papermc.paper.registry.RegistryAccess")
        RegistryKey = java.type("io.papermc.paper.registry.RegistryKey")
        reg = RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE)
        t = reg.get(kyoutsuu.鍵(鍵))
    except BaseException as e:
        amazake.log.debug("реестр урона: " + str(e))
        t = None
    if t is None:
        amazake.log.warn("тип урона «" + 鍵 + "» не найден — взят minecraft:magic")
        t = DamageType.MAGIC
    _傷種[鍵] = t
    return t


def _狙えるか(e, 光, 自分):
    """Живая цель, которую луч может задеть (без точной проверки пути)."""
    if not java.instanceof(e, LivingEntity) or java.instanceof(e, ArmorStand):
        return False
    if str(e.getUniqueId()) == 自分:
        return False
    if not e.isValid() or e.isDead():
        return False
    if not e.isVisibleByDefault():
        return False          # личные «иллюзии» для одного игрока (фантомы и т.п.)
    if amazake.data(e).has("corenpc:npc"):
        return False          # NPC core-npc
    if java.instanceof(e, Player):
        if not 光.対人:
            return False
        gm = e.getGameMode()
        if gm == GameMode.SPECTATOR or gm == GameMode.CREATIVE:
            return False
    return amazake.owns(e)


def 撃つ(player, cfg, 陣=None):
    """Выстрел из потока заклинателя. Возвращает (дальность D, число целей)."""
    光 = cfg.光
    eye = player.getEyeLocation()
    world = eye.getWorld()
    yaw = eye.getYaw()
    pitch = eye.getPitch()
    E = (eye.getX(), eye.getY(), eye.getZ())
    向き = keisan.視線(yaw, pitch)
    向きV = Vector(向き[0], 向き[1], 向き[2])
    自分 = str(player.getUniqueId())

    # --- 1. сколько пути наше (владение регионом по кускам) ---------------------------------------------------------
    def 持つか(cx, cz):
        return world.isChunkLoaded(cx, cz) and amazake.owns(Location(world, cx * 16.0 + 8.0, E[1], cz * 16.0 + 8.0))

    使える = keisan.使える長さ(E, 向き, 光.射程, 光.片長, 光.半径 + 1.0, 持つか)

    # --- 2. блоки: до первого твёрдого -------------------------------------------------------------------------------
    D = 使える
    if 使える > 0.0:
        hit = world.rayTraceBlocks(eye, 向きV, 使える, FluidCollisionMode.NEVER, True)
        if hit is not None:
            hp = hit.getHitPosition()
            D = min(使える, ((hp.getX() - E[0]) ** 2 + (hp.getY() - E[1]) ** 2 + (hp.getZ() - E[2]) ** 2) ** 0.5)

    # --- 2b. барьеры: ближайший на [0, D] обрывает луч (чистая математика по снимку реестра) ---------------------
    遮 = None
    遮rec = None
    if D > 0.0:
        try:
            recs = kekkai.一覧()
            遮 = keisan.遮断(E, 向き, D, recs, world.getName(), kyoutsuu.今ms())
            if 遮 is not None:
                D = 遮[0]
                for r in recs:
                    if r.id == 遮[1]:
                        遮rec = r
                        break
        except BaseException as ex:
            amazake.log.debug("барьеры на пути луча: " + str(ex))
            遮 = None

    # --- 3. цели вдоль [0, D] ----------------------------------------------------------------------------------------
    候補 = []
    実体 = {}
    if D > 0.0:
        for a, b in keisan.区切る(D, 光.片長):
            箱 = keisan.区間の箱(E, 向き, a, b, 光.半径)
            for e in world.getNearbyEntities(BoundingBox(箱[0], 箱[1], 箱[2], 箱[3], 箱[4], 箱[5])):
                uid = str(e.getUniqueId())
                if uid in 実体:
                    continue
                実体[uid] = None
                try:
                    if not _狙えるか(e, 光, 自分):
                        continue
                    bb = e.getBoundingBox()
                    実体[uid] = e
                    候補.append((uid, (bb.getMinX(), bb.getMinY(), bb.getMinZ(), bb.getMaxX(), bb.getMaxY(), bb.getMaxZ())))
                except BaseException as ex:
                    amazake.log.debug("цель луча: " + str(ex))
    当たり = keisan.当たり(E, 向き, D, 候補, 自分, 光.半径)
    if 遮rec is not None and 当たり:
        # только цели перед барьером (центр хитбокса по ту же сторону плоскости, что и глаза стрелка)
        箱表 = dict(候補)
        当たり = [(t, uid) for t, uid in 当たり
                if keisan.手前か(遮rec, ((箱表[uid][0] + 箱表[uid][3]) / 2.0, (箱表[uid][1] + 箱表[uid][4]) / 2.0,
                                        (箱表[uid][2] + 箱表[uid][5]) / 2.0), E)]

    # --- 4. урон ------------------------------------------------------------------------------------------------------
    n = 0
    if 当たり and 光.傷 > 0.0:
        src = None
        try:
            src = (DamageSource.builder(傷の種類(光.傷種)).withCausingEntity(player).withDirectEntity(player)
                   .withDamageLocation(eye).build())
        except BaseException as ex:
            amazake.log.debug("DamageSource: " + str(ex))
        for _, uid in 当たり:
            e = 実体.get(uid)
            if e is None or not e.isValid() or e.isDead():
                continue
            try:
                if 光.無敵無視:
                    e.setNoDamageTicks(0)
                damage_bridge.hit(player, e, float(光.傷), src)
                n += 1
            except BaseException as ex:
                amazake.log.warn("урон лучом " + uid + ": " + str(ex))

    # --- 5. вид: дисплей луча, частицы, звук ---------------------------------------------------------------------------
    try:
        if 遮 is None or D > cfg.円.前 + 0.05:      # барьер вплотную (ближе круга) — дисплея луча нет
            _光を見せる(eye, cfg, D, 自分)
    except BaseException as ex:
        amazake.log.warn("дисплей луча: " + str(ex))
    終 = keisan.点(E, 向き, D)
    try:
        前 = cfg.円.前
        for x, y, z in keisan.道の点(E, 向き, min(前, D), D, cfg.跡粒.毎):
            kyoutsuu.散らす(world, cfg.跡粒, x, y, z)
        kyoutsuu.散らす(world, cfg.打粒 if 遮 is not None else cfg.終粒, 終[0], 終[1], 終[2])
    except BaseException as ex:
        amazake.log.debug("частицы луча: " + str(ex))
    kyoutsuu.鳴らす(world, eye, cfg.音表["fire"])
    if 遮 is not None:
        kyoutsuu.鳴らす(world, Location(world, 終[0], 終[1], 終[2]), cfg.音表["barrier-hit"])
        kekkai.閃かせる(遮[1], 遮[2])
    return D, n


def _光を見せる(eye, cfg, D, 主):
    if kyoutsuu.終了済み[0]:
        return
    光 = cfg.光
    c = cfg.円
    at = mahoujin.位置(eye)
    if not amazake.owns(at):
        return
    Lv = keisan.見た目の長さ(D, c.前)
    R = keisan.光線R(Lv)
    W = 光.幅
    始 = W * 光.始幅倍
    d = mahoujin.生む(at, kyoutsuu.効果品(光.モデル, R), kyoutsuu.変形(c.前, c.下, 始, 始, 0.02, True), 光.視距離,
                     "beam", 主)
    伸び = kyoutsuu.変形(c.前, c.下, W, W, 2.0 * Lv, True)
    消え = kyoutsuu.変形(c.前, c.下, 0.0, 0.0, 2.0 * Lv, True)

    def 伸ばす():
        if d.isValid():
            d.setInterpolationDelay(0)
            d.setInterpolationDuration(光.伸び)
            d.setTransformation(伸び)

    def 消す():
        if d.isValid():
            d.setInterpolationDelay(0)
            d.setInterpolationDuration(光.消え)
            d.setTransformation(消え)

    amazake.run_in_region(at, 伸ばす, delay=1)
    amazake.run_in_region(at, 消す, delay=1 + 光.伸び + 光.保持)
    mahoujin.後で消す(d, at, 1 + 光.伸び + 光.保持 + 光.消え + 1)
    if kyoutsuu.終了済み[0]:
        d.remove()
    return d
