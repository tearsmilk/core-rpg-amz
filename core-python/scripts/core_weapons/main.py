# 武器 — core_weapons: эффекты кастомного оружия Nexo (перенос Java-системы core-weapons на Python).
#
# Оружие узнаётся по id предмета Nexo (PDC nexo:id) или по своей метке coreweapons:id. Каждому оружию в config.yml
# назначаются поведения: orbit (кольцо частиц вокруг клинка), swing (кадры замаха — строка custom_model_data
# cw_swing_N, модель кадра выбирает пак; огненный след), crit-tremor (дрожь земли при крите). Общий core-шейдер не
# трогается. Команда: /cweapons give <оружие> [игрок] | list | reload (алиас /coreweapons, право coreweapons.admin).
#
# Потоки: игрок (инвентарь, скорость, частицы у игрока) — только его поток; дрожь (частицы, копии блоков) — поток
# региона точки удара. Состояние игроков — dict по UUID, каждый 持ち主 трогает только поток своего игрока.
#
# Палка (bou.py, секция stick): ПКМ — «Удар в спину», Shift+ПКМ — «Угли»; дисплеи эффектов — kouka.py (шейдер
# moonstaff). Java-система core-weapons таких приёмов не имеет, поэтому они работают и во время «спячки».

import uuid

import java
import amazake
import bou
import dougu
import jishin
import kouka
import mochinushi
import settei
from dougu import Bukkit, EquipmentSlot, Material, ItemStack, Key, DataComponentTypes

#: UUID (строка) → 持ち主. Запись/удаление — в потоке игрока (вход, выход, включение), чтение — где угодно.
持ち主表 = {}

#: Id этого экземпляра системы — значение метки coreweapons:tremor на копиях блоков (как в Java).
個体 = str(uuid.uuid4())


# ---------------------------------------------------------------------------------------------- настройки

def 読み込み():
    """Перечитать config.yml (любой поток: только разбор). Снимок подменяется целиком."""
    生 = amazake.config("config.yml")
    if not 生:
        amazake.log.warn("config.yml нет или пуст — оружия не настроены")
    新 = settei.解析(生, lambda w: amazake.log.warn("config.yml: " + w))
    settei.現在 = 新
    輪 = any(w.軌道 is not None for w in 新.武器表.values())
    前 = mochinushi._輪あり[0]
    mochinushi._輪あり[0] = 輪
    if 輪 and not _持ち替え登録[0]:
        _持ち替え登録[0] = True
        amazake.on("PlayerItemHeldEvent", priority="monitor", ignore_cancelled=True)(持ち替え)
    if 輪 and not 前 and _起動済み[0]:
        # orbit включили перечитыванием: у Java задача каждого тика подхватила бы сама — здесь заводим наблюдателей
        for h in list(持ち主表.values()):
            amazake.run_once_for_player(h.player, h.輪を確かめる)
    return 新


_持ち替え登録 = [False]
_起動済み = [False]


def _UUID(p):
    return str(p.getUniqueId())


def _付ける(p):
    """Поток игрока: завести 持ち主 (снимает хвосты кадров с инвентаря)."""
    k = _UUID(p)
    有 = 持ち主表.get(k)
    if 有 is not None and 有.player == p:
        return 有
    h = mochinushi.持ち主(p)
    古 = 持ち主表.get(k)
    持ち主表[k] = h
    if 古 is not None:
        古.停止()
    h.開始()
    return h


def _Javaの武器がある():
    """Включена ли ещё Java-система core-weapons. Amazake регистрирует команды систем с префиксом имени системы, так
    что ключ «core-weapons:cweapons» в карте команд есть всё время, пока она включена, кто бы ни занял «/cweapons»
    (это зависит от порядка включения)."""
    try:
        # getCommand, а не getKnownCommands().get(…): у Java Map в GraalPy .get — питоновский и тут давал None
        return Bukkit.getCommandMap().getCommand("core-weapons:cweapons") is not None
    except Exception:
        return False


def _目覚める():
    """Снять «спячку»: все, кто уже онлайн, — в их потоках."""
    dougu.停止中[0] = False
    _起動済み[0] = True
    for p in Bukkit.getOnlinePlayers():
        amazake.run_once_for_player(p, lambda p=p: _付ける(p))


def _眠るべきか():
    if _Javaの武器がある():
        dougu.停止中[0] = True
        amazake.log.warn("core_weapons: Java-система core-weapons ещё включена — эффекты не "
                         "включаю, чтобы замах и дрожь не шли дважды. Выключите её (amazake system disable "
                         "core-weapons) и выполните /py-core_weapons:cweapons reload или /python reload core_weapons.")
        return True
    return False


@amazake.on_load
def 起動():
    """После загрузки: все, кто уже онлайн, — в их потоках."""
    if dougu.プラグイン() is None:   # заранее: понадобится при выгрузке
        amazake.log.warn("core_weapons: нет Plugin для планировщика — кадры в чужих потоках при выгрузке не снимутся "
                         "(снимутся при следующем входе)")
    if not _眠るべきか():
        _目覚める()
    for p in Bukkit.getOnlinePlayers():   # хвосты дисплеев палки после сбоя — каждому в его потоке
        amazake.run_once_for_player(p, lambda p=p: _掃除(p))


def _掃除(p):
    n = kouka.掃除(p)
    if n:
        amazake.log.info("core_weapons: убрано старых дисплеев палки: " + str(n))


@amazake.on_unload
def 終了():
    """Выгрузка: кадры с предметов (свой поток — сразу, чужой — задачей игрока без Python), копии блоков.
    Задачи (замах, кольцо, дрожь) ядро уже отменило, но шаг, уже вошедший в Python, может доработать (GIL
    переключается между байткодами) — флаг 終了済み ставится первым, и такие шаги сами убирают то, что успели сделать."""
    dougu.終了済み[0] = True
    bou.片付け()
    for h in list(持ち主表.values()):
        try:
            if not h.振り中か():
                continue
            if amazake.owns(h.player):
                h.振り終わり(True)
            elif not h.後で外す():
                amazake.log.warn("кадр замаха у " + str(h.player.getName()) + " не снят — снимется при следующем входе")
        except Exception as e:
            amazake.log.warn("кадр замаха:", e)
    持ち主表.clear()
    for t in list(jishin.進行中):
        t.全部消す()
    jishin.進行中.clear()


# ------------------------------------------------------------------------------------------------ события

@amazake.on("PlayerJoinEvent", priority="monitor")
def 参加(event):
    if dougu.停止中[0]:
        return
    _付ける(event.getPlayer())


@amazake.on("PlayerQuitEvent", priority="monitor")
def 退出(event):
    bou.去る(event.getPlayer())
    h = 持ち主表.pop(_UUID(event.getPlayer()), None)
    if h is not None:
        h.停止()   # кадр не уезжает в файл игрока


@amazake.on("PlayerArmSwingEvent", priority="monitor", ignore_cancelled=True)
def 腕振り(event):
    # Горячий путь: пока игрок держит атаку по блоку, клиент шлёт взмах каждый тик — при копании выходим до чтения
    # предмета (в Java то же условие проверялось после чтения; результат тот же).
    if event.getHand() != EquipmentSlot.HAND:
        return
    h = 持ち主表.get(_UUID(event.getPlayer()))
    if h is not None and not h.掘り中:
        h.腕振り()


@amazake.on("BlockDamageEvent", priority="monitor", ignore_cancelled=True)
def 掘り始め(event):
    h = 持ち主表.get(_UUID(event.getPlayer()))
    if h is not None and not event.getInstaBreak():
        h.掘り中 = True


@amazake.on("BlockDamageAbortEvent", priority="monitor")
def 掘りやめ(event):
    h = 持ち主表.get(_UUID(event.getPlayer()))
    if h is not None:
        h.掘り中 = False


@amazake.on("BlockBreakEvent", priority="monitor")
def 壊した(event):
    h = 持ち主表.get(_UUID(event.getPlayer()))
    if h is not None:
        h.掘り中 = False


def 持ち替え(event):
    """Смена слота хотбара: взял оружие с orbit — кольцо сразу (без ожидания наблюдателя). Слушатель ставится только
    когда orbit включён хоть у одного оружия (см. 読み込み) — иначе это лишний вызов Python на каждую прокрутку."""
    if not mochinushi._輪あり[0]:
        return
    p = event.getPlayer()
    h = 持ち主表.get(_UUID(p))
    if h is not None:
        h.輪を確かめる(p.getInventory().getItem(event.getNewSlot()))


# Предмет с кадром не должен уйти из рук с кадром: выброс и смерть.
@amazake.on("PlayerDropItemEvent", priority="monitor", ignore_cancelled=True)
def 捨てた(event):
    drop = event.getItemDrop()
    直した = dougu.枠付き(drop.getItemStack(), None)
    if 直した is not None:
        drop.setItemStack(直した)


@amazake.on("PlayerDeathEvent", priority="high")
def 死んだ(event):
    bou.止める(event.getEntity(), "即")
    drops = event.getDrops()
    for i in range(drops.size()):
        直した = dougu.枠付き(drops.get(i), None)
        if 直した is not None:
            drops.set(i, 直した)


@amazake.on("EntityDamageByEntityEvent", priority="monitor", ignore_cancelled=True)
def 打撃(event):
    """Поток региона удара: атакующий и цель стоят рядом, поток владеет обоими. Крит → дрожь."""
    if not event.isCritical() or dougu.停止中[0] or dougu.終了済み[0]:
        return
    p = event.getDamager()
    if not java.instanceof(p, dougu.Player):
        return
    w = settei.現在.武器(dougu.武器ID(p.getInventory().getItemInMainHand()))
    if w is None or w.地震 is None:
        return
    h = 持ち主表.get(_UUID(p))
    今 = Bukkit.getCurrentTick()
    if h is not None:
        if 今 - h.最後の地震 < w.地震.待ち:
            return
        h.最後の地震 = 今
    jishin.揺れ(w.地震, event.getEntity().getLocation(), _UUID(p), 個体).開始()


# ------------------------------------------------------------------------------------------------ палка

@amazake.on("PlayerInteractEvent", priority="high", ignore_cancelled=False)
def 棒の右クリック(event):
    """ПКМ палкой в воздух / по блоку (поток игрока). Клик в воздух приходит «отменённым» — ignore_cancelled=False.
    Только основная рука. Клик по двери/сундуку без Shift — как обычно (bou.発動 сам пропускает)."""
    a = event.getAction().name()
    if a != "RIGHT_CLICK_AIR" and a != "RIGHT_CLICK_BLOCK":
        return
    if event.getHand() != EquipmentSlot.HAND or settei.現在.棒 is None:
        return
    blk = event.getClickedBlock() if a == "RIGHT_CLICK_BLOCK" else None
    if blk is not None and event.useInteractedBlock().name() == "DENY" and not event.getPlayer().isSneaking():
        blk = None
    bou.発動(event.getPlayer(), None, blk)


@amazake.on("PlayerInteractEntityEvent", priority="high", ignore_cancelled=True)
def 棒で生き物(event):
    """ПКМ палкой по существу (поток игрока): цель — оно (если годится). Взаимодействие (торговля и т.п.) отменяется."""
    if event.getHand() != EquipmentSlot.HAND or settei.現在.棒 is None:
        return
    if bou.発動(event.getPlayer(), event.getRightClicked(), None):
        event.setCancelled(True)


@amazake.on("PlayerChangedWorldEvent", priority="monitor")
def 世界移動(event):
    bou.止める(event.getPlayer(), "即")


@amazake.on("PlayerTeleportEvent", priority="monitor", ignore_cancelled=True)
def 瞬間移動(event):
    p = event.getPlayer()
    st = bou.状態表.get(_UUID(p))
    if st is None or st.詠唱 is None:
        return
    f = event.getFrom()
    t = event.getTo()
    if t is None or not f.getWorld().equals(t.getWorld()) or f.distanceSquared(t) > 16.0:
        bou.止める(p, "即")


# ------------------------------------------------------------------------------------------------ команда

def _送る(to, 鍵, **ph):
    s = settei.現在
    t = s.文言.get(鍵, "")
    if t.strip():
        amazake.send(to, s.接頭 + t, **ph)


@amazake.command("cweapons")
def 武器コマンド(sender, args):
    sub = args[0].lower() if args else ""
    if sub == "reload":
        新 = 読み込み()
        amazake.log.info("core_weapons: настройки перечитаны, оружий — " + str(len(新.武器表)) + ".")
        if dougu.停止中[0] and not _眠るべきか():
            amazake.log.info("core_weapons: Java-система core-weapons выключена — эффекты включены.")
            _目覚める()   # Java-систему выключили — эффекты включаются без перезагрузки скрипта
        _送る(sender, "reloaded")
    elif sub == "list":
        _送る(sender, "list", list=", ".join(settei.現在.武器表.keys()) + " | игроков " + str(len(持ち主表))
              + ", копий блоков " + str(jishin.表示数()) + " | " + bou.情報())
    elif sub == "give":
        _渡す(sender, args)
    else:
        _送る(sender, "usage")


def _渡す(sender, args):
    """Предмет для проверки без Nexo: материал, item_model, имя и метка nexo:id из test-item."""
    if len(args) < 2:
        _送る(sender, "usage")
        return
    w = settei.現在.武器(args[1])
    if w is None or w.試験品 is None:
        _送る(sender, "no-weapon", weapon=args[1])
        return
    if len(args) >= 3:
        target = Bukkit.getPlayerExact(args[2])
        if target is None:
            _送る(sender, "no-player", player=args[2])
            return
    elif java.instanceof(sender, dougu.Player):
        target = sender
    else:
        _送る(sender, "console")
        return
    m = Material.matchMaterial(w.試験品.素材)
    item = ItemStack(Material.NETHERITE_SWORD if m is None or not m.isItem() else m)
    if w.試験品.モデル.strip():
        item.setData(DataComponentTypes.ITEM_MODEL, Key.key(w.試験品.モデル))
    item.setData(DataComponentTypes.ITEM_NAME, amazake.item_text(w.試験品.名前))
    amazake.data(item).set_string("nexo:id", w.id)
    amazake.run_once_for_player(target, lambda: target.getInventory().addItem(item))
    _送る(sender, "given", weapon=w.id, player=str(target.getName()))


@武器コマンド.tab
def 補完(sender, args):
    if len(args) == 1:
        return ["give", "list", "reload"]
    if len(args) == 2 and args[0].lower() == "give":
        return list(settei.現在.武器表.keys())
    if len(args) == 3:
        return [str(p.getName()) for p in Bukkit.getOnlinePlayers()]
    return []


# --- загрузка настроек (асинхронный поток загрузки: только разбор файла и объявление слушателей) ------------------
読み込み()
amazake.log.info("core_weapons включён: оружия в config.yml — " + str(len(settei.現在.武器表)) + ".")
