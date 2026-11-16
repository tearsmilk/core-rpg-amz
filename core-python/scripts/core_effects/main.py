# 効果 — кастомные эффекты: кровотечение (обморок, бинт), паника (фантомы, дезориентация), тепло (смерть без
# питья), проклятье (пропажа обычных вещей). Порт Java-системы core-effects на Python (core-python).
#
#   /effects give <игрок> <эффект> [время] | remove <игрок> <эффект> | clear <игрок|all> | list [игрок]
#            | bandage [игрок] [кол-во] | reload
# Псевдонимы: /coreeffects, /эффекты. Право: coreeffects.admin. Конфиг — config.yml (тот же формат, что у Java).
#
# Модули: logic.py (чистая логика, тесты — tests_core_effects.py), settings.py (разбор config.yml), items.py
# (бинт, метки предметов), fx.py (эффекты игрока, фантомы), papi.py (PlaceholderAPI), common.py (общее).
#
# Потоки: всё про игрока — в его потоке (его события, run_for_player / run_once_for_player); фантомы — поток их
# региона; взрывное зелье и удары приходят в потоке региона — игроку через run_once_for_player; команды читают
# состояние под замком 効果帳; PlaceholderAPI — Java-мост (bridge.py) читает готовые ответы common.PAPI表 без Python.

import uuid

import java
import amazake

import logic
import settings
import items
import fx
import papi
from logic import 出血, 温熱, 呪い, 種類一覧, 効果帳
from common import (Bukkit, Player, Projectile, Entity, Sound, SoundCategory, 状態鍵, 幻影鍵, 設定, 設定を置く, 送る,
                    鳴らす, PAPI表, 表を出す)

#: id экземпляра системы (метка фантомов: хвосты прошлых запусков убираются)
実例 = str(uuid.uuid4())

#: str UUID → fx.プレイヤー効果 (каждый трогается только в потоке своего игрока; читать можно отовсюду)
効果者 = {}

#: версия расширения PlaceholderAPI — как у Java-системы (context.info().version() = 1.0.0 из build.gradle.kts)
PAPI版 = "1.0.0"

#: включена Java-система core-effects (её /effects уже зарегистрирована) — тогда эта система ничего не делает:
#: иначе обе тикали бы тех же игроков из того же coreeffects:state (двойной урон, два набора фантомов)
衝突 = [False]


# =================================================================================================================
# Настройки и PlaceholderAPI
# =================================================================================================================

def 既定表を置く(s):
    """Ответы для игроков без эффектов и <эффект>_name (как у Java: имя из настроек, у всех одинаковое)."""
    表 = {"count": "0", "unconscious": "no", "warmth_thirst": "0"}
    for t in 種類一覧:
        表.update({t: "no", t + "_seconds": "0", t + "_time": "", t + "_name": s.共通[t].名前})
    表を出す("", 表)


def 設定を読む():
    s = settings.読む(amazake.config("config.yml"), lambda w: amazake.log.warn("config.yml: " + w))
    設定を置く(s)
    既定表を置く(s)


def papiを繋ぐ():
    """Глобальный поток: (пере)зарегистрировать плейсхолдеры по настройкам."""
    s = 設定()
    pl = Bukkit.getPluginManager().getPlugin(papi.PAPI)
    if not s.表示.papi or pl is None or not pl.isEnabled():
        papi.解除()
        return
    papi.登録(s.表示.papi識別子, PAPI版, PAPI表)


設定を読む()
papi.準備()


def _ジャバ版がある():
    """Включена ли Java-система core-effects (её команда /effects в карте команд)."""
    try:
        known = Bukkit.getCommandMap().getKnownCommands()
        for 名 in ("effects", "coreeffects"):
            c = known.get(名)
            if c is not None and ".coreeffects." in str(c.getClass().getName()):
                return True
    except Exception as e:
        amazake.log.debug("проверка Java-системы core-effects: " + str(e))
    return False


def _開く():
    """Глобальный поток: проверить, что Java-система выключена; всех онлайн — каждому в его потоке; PlaceholderAPI."""
    if _ジャバ版がある():
        衝突[0] = True
        amazake.log.error("Включена Java-система core-effects — core_effects (Python) ничего не делает. Сначала "
                          "«amazake system disable core-effects», затем «/python reload core_effects».")
        return
    for p in Bukkit.getOnlinePlayers():
        amazake.run_once_for_player(p, lambda p=p: 付ける(p))
    papiを繋ぐ()
    amazake.log.info("core_effects включён: кровотечение, паника, тепло, проклятье.")


@amazake.on_load
def 起動():
    amazake.run_global(_開く)


@amazake.on_unload
def 停止():
    """Выгрузка: снять расширение PlaceholderAPI; каждому игроку — сохранение, заголовок, фантомы (модификаторы и
    ванильные эффекты, наложенные через amazake, снимает само ядро core-python)."""
    papi.解除()
    PAPI表.clear()
    for f in list(効果者.values()):
        try:
            f.閉店()
        except Exception as e:
            amazake.log.warn("выгрузка " + f.player.getName() + ": " + str(e))
    効果者.clear()


@amazake.on("PluginEnableEvent")
def プラグイン有効(event):
    if event.getPlugin().getName() == papi.PAPI:
        papiを繋ぐ()


@amazake.on("PluginDisableEvent")
def プラグイン無効(event):
    if event.getPlugin().getName() == papi.PAPI:
        papi.解除()


# =================================================================================================================
# Игроки (EffectManager)
# =================================================================================================================

def 付ける(player):
    """Поток игрока: загрузить эффекты из PDC (если ещё нет) и запустить. None — система не работает (衝突)."""
    if 衝突[0]:
        return None
    uid = str(player.getUniqueId())
    有 = 効果者.get(uid)
    if 有 is not None and 有.実体ID == player.getEntityId() and not 有.閉じた:
        return 有
    生 = amazake.data(player).get_string(状態鍵)
    名 = player.getName()
    帳 = 効果帳.復号(生, lambda 部, 理由: amazake.log.warn(
        "Эффекты игрока " + 名 + ": «" + 部 + "» пропущено (" + 理由 + ")"))
    f = fx.プレイヤー効果(player, 帳, 実例)
    古 = 効果者.get(uid)
    効果者[uid] = f
    if 古 is not None:
        古.退出()
    掃除(player)
    f.開始()
    return f


def 掃除(player):
    """Фантомы прошлых запусков рядом с игроком (поток игрока)."""
    for e in player.getNearbyEntities(64.0, 64.0, 64.0):
        if not Bukkit.isOwnedByCurrentRegion(e) or e.isVisibleByDefault():
            continue
        印 = amazake.data(e).get_string(幻影鍵)
        if 印 is not None and 印 != 実例:
            e.remove()


def 人で(target, fn):
    """fn(эффекты игрока) в потоке игрока. False — не запланировано (игрок ушёл; как runOnceForPlayer у Java)."""
    def 走():
        f = 付ける(target)
        if f is not None:
            fn(f)

    h = amazake.run_once_for_player(target, 走)
    return h is not None and not h.isCancelled()


def 外す(target, 種):
    """Снять один (работа — в потоке игрока)."""
    return 人で(target, lambda f: f.外す(種, True))


def 消す(target):
    """Снять все (работа — в потоке игрока)."""
    return 人で(target, lambda f: f.全消し(True))


@amazake.on("PlayerJoinEvent", priority="monitor")
def 参加(event):
    付ける(event.getPlayer())


@amazake.on("PlayerQuitEvent", priority="monitor")
def 退出(event):
    f = 効果者.pop(str(event.getPlayer().getUniqueId()), None)
    if f is not None:
        f.退出()


@amazake.on("PlayerDeathEvent", priority="high")
def 死亡(event):
    player = event.getPlayer()
    f = 効果者.get(str(player.getUniqueId()))
    if f is None:
        return
    s = 設定()
    原因 = f.死因
    if 原因 is not None:
        文 = s.温熱.死亡文 if 原因 == 温熱 and s.温熱.死亡文.strip() else s.文.取る("death-" + 原因)
        if 文.strip():
            event.deathMessage(amazake.mm(文, player=player.getName()))
    f.死亡(s)


# --- лечение ---------------------------------------------------------------------------------------------------------

@amazake.on("PlayerItemConsumeEvent", priority="monitor", ignore_cancelled=True)
def 食べた(event):
    f = 効果者.get(str(event.getPlayer().getUniqueId()))
    if f is None or f.帳.空():
        return
    f.品を使う(items.印(event.getItem()))


@amazake.on("PotionSplashEvent", priority="monitor", ignore_cancelled=True)
def 跳ねた(event):
    """Взрывное зелье лечения: поток региона зелья; игрока трогаем его потоком."""
    if not 効果者 or not items.治癒ポーションか(event.getPotion().getItem()):
        return
    for e in event.getAffectedEntities():
        if not java.instanceof(e, Player) or event.getIntensity(e) <= 0:
            continue
        uid = str(e.getUniqueId())
        if uid not in 効果者:
            continue

        def 効く(p=e, uid=uid):
            f = 効果者.get(uid)
            if f is not None:
                f.品を使う(["healing-potion", "splash-potion"])

        amazake.run_once_for_player(e, 効く)


def _音(p):
    鳴らす(p, p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, SoundCategory.PLAYERS, 1.0, 1.2)


def _使う(p, 手):
    """Бинт потрачен (не в творческом)."""
    if 手 is None or str(p.getGameMode().name()) == "CREATIVE":
        return
    inv = p.getInventory()
    it = inv.getItem(手)
    if it.getAmount() <= 1:
        inv.setItem(手, None)
    else:
        it.setAmount(it.getAmount() - 1)
        inv.setItem(手, it)


@amazake.on("PlayerInteractEvent", priority="low")
def 使用(event):
    """Бинт по воздуху/блоку — себе. Без сознания — ничего нельзя."""
    p = event.getPlayer()
    if fx.気絶者 and str(p.getUniqueId()) in fx.気絶者:
        event.setCancelled(True)
        return
    item = event.getItem()
    if item is None:
        return
    動作 = str(event.getAction().name())
    if 動作 != "RIGHT_CLICK_AIR" and 動作 != "RIGHT_CLICK_BLOCK":
        return
    if not items.包帯か(item):
        return
    event.setCancelled(True)
    f = 効果者.get(str(p.getUniqueId()))
    s = 設定()
    if f is None or not f.治せる("bandage"):
        送る(p, s, "bandage-useless")
        return
    if not f.包帯準備(s.包帯.待ち時間):
        return
    f.包帯印()
    f.品を使う(["bandage"])
    _使う(p, event.getHand())
    _音(p)


@amazake.on("PlayerInteractEntityEvent", priority="low")
def 他人に使用(event):
    """Бинт по другому игроку — перевязать его (его поток)."""
    p = event.getPlayer()
    if fx.気絶者 and str(p.getUniqueId()) in fx.気絶者:
        event.setCancelled(True)
        return
    target = event.getRightClicked()
    if not java.instanceof(target, Player):
        return
    if not items.包帯か(p.getInventory().getItem(event.getHand())):
        return
    自 = 効果者.get(str(p.getUniqueId()))
    s = 設定()
    event.setCancelled(True)
    if not s.包帯.他人:
        return
    他 = 効果者.get(str(target.getUniqueId()))
    if 他 is None or not 他.治せる("bandage"):
        送る(p, s, "bandage-useless")
        return
    if 自 is not None and not 自.包帯準備(s.包帯.待ち時間):
        return
    if 自 is not None:
        自.包帯印()
    _使う(p, event.getHand())
    _音(p)
    送る(p, s, "bandaged-other", player=target.getName())
    名 = p.getName()
    uid = str(target.getUniqueId())

    def 巻く():
        f = 効果者.get(uid)
        if f is not None and f.品を使う(["bandage"]):
            送る(target, s, "bandaged-by", player=名)

    amazake.run_once_for_player(target, 巻く)


# --- обморок и фантомы -------------------------------------------------------------------------------------------------

@amazake.on("BlockBreakEvent", priority="low", ignore_cancelled=True)
def 壊す(event):
    if fx.気絶者 and str(event.getPlayer().getUniqueId()) in fx.気絶者:
        event.setCancelled(True)


@amazake.on("BlockPlaceEvent", priority="low", ignore_cancelled=True)
def 置く(event):
    if fx.気絶者 and str(event.getPlayer().getUniqueId()) in fx.気絶者:
        event.setCancelled(True)


@amazake.on("EntityDamageByEntityEvent", priority="lowest")
def 殴る(event):
    """Поток региона жертвы. Без сознания — не бьёт; удар по фантому — урона нет, фантом растворяется (в потоке его
    хозяина). Любой урон от сущности по фантому отменяется (остальной урон фантомам не страшен: они неуязвимы).
    Частое событие (каждый удар на сервере): пока никто не без сознания и фантомов нет — сразу выход."""
    if not fx.気絶者 and not fx.幻影ID:
        return
    被id = None
    if fx.幻影ID:
        被id = str(event.getEntity().getUniqueId())
        if 被id not in fx.幻影ID:
            被id = None
    幻 = 被id is not None
    if not 幻 and not fx.気絶者:
        return
    d = event.getDamager()
    if java.instanceof(d, Projectile):
        射手 = d.getShooter()
        if 射手 is not None and java.instanceof(射手, Entity):
            d = 射手
    if java.instanceof(d, Player):
        uid = str(d.getUniqueId())
        if uid in fx.気絶者:
            event.setCancelled(True)
            return
        f = 効果者.get(uid) if 幻 else None
        if 幻 and f is not None:
            event.setCancelled(True)
            amazake.run_once_for_player(d, lambda: f.幻影.叩かれた(被id))
            return
    if 幻:
        event.setCancelled(True)


# =================================================================================================================
# PlaceholderAPI: %coreeffects_<эффект>% yes/no, _<эффект>_seconds|_time|_name, _count, _unconscious, _warmth_thirst
# =================================================================================================================

# =================================================================================================================
# /effects
# =================================================================================================================

権限 = "coreeffects.admin"
下位 = ["give", "remove", "clear", "list", "bandage", "reload"]


def _プレイヤー(sender, s, 名):
    p = Bukkit.getPlayerExact(名)
    if p is None:
        送る(sender, s, "no-player", player=名)
    return p


def _種類(sender, s, 生):
    t = logic.種類を解析(生)
    if t is None:
        送る(sender, s, "no-effect", effect=生)
    return t


def _自分か(sender):
    return java.instanceof(sender, Player)


@amazake.command("effects")
def 効果コマンド(sender, args):
    s = 設定()
    if 衝突[0]:
        amazake.send(sender, "<red>core_effects (Python) выключен: включена Java-система core-effects.</red>")
        return
    sub = args[0].lower() if args else ""
    if sub == "give":
        _与える(sender, s, args)
    elif sub == "remove":
        _外す(sender, s, args)
    elif sub == "clear":
        _消す(sender, s, args)
    elif sub == "list":
        _一覧(sender, s, args)
    elif sub == "bandage":
        _包帯(sender, s, args)
    elif sub == "reload":
        _読み直す(sender)
    else:
        送る(sender, s, "usage")


def _読み直す(sender):
    """config.yml читается вне тиков (run_async); ответ — в поток отправителя (игрок) или сразу (консоль)."""
    自分 = _自分か(sender)

    def 読む():
        設定を読む()
        amazake.run_global(papiを繋ぐ)
        amazake.log.info("core_effects: настройки перечитаны.")
        if 自分:
            amazake.run_once_for_player(sender, lambda: 送る(sender, 設定(), "reloaded"))
        else:
            送る(sender, 設定(), "reloaded")

    amazake.run_async(読む)


def _与える(sender, s, args):
    if len(args) < 3:
        送る(sender, s, "usage")
        return
    target = _プレイヤー(sender, s, args[1])
    種 = None if target is None else _種類(sender, s, args[2])
    if 種 is None:
        return
    秒 = s.共通[種].既定秒
    if len(args) >= 4:
        v = logic.秒を解析(args[3])
        if v is None:
            送る(sender, s, "bad-time", time=args[3])
            return
        秒 = v
    刻 = logic.無限 if 秒 < 0 else 秒 * 20
    if not 人で(target, lambda f: f.与える(種, 刻)):
        送る(sender, s, "no-player", player=args[1])
        return
    送る(sender, s, "given", 解析={"effect": s.共通[種].名前}, player=target.getName(), time=logic.時間表示(刻))


def _外す(sender, s, args):
    if len(args) < 3:
        送る(sender, s, "usage")
        return
    target = _プレイヤー(sender, s, args[1])
    種 = None if target is None else _種類(sender, s, args[2])
    if 種 is None:
        return
    f = 効果者.get(str(target.getUniqueId()))
    if f is None or not f.帳.ある(種):
        送る(sender, s, "not-active", 解析={"effect": s.共通[種].名前}, player=target.getName())
        return
    外す(target, 種)
    送る(sender, s, "removed", 解析={"effect": s.共通[種].名前}, player=target.getName())


def _消す(sender, s, args):
    if len(args) < 2:
        送る(sender, s, "usage")
        return
    if args[1].lower() == "all" or args[1] == "*":
        n = 0
        for p in Bukkit.getOnlinePlayers():
            f = 効果者.get(str(p.getUniqueId()))
            if f is not None and not f.帳.空() and 消す(p):
                n += 1
        送る(sender, s, "cleared-all", count=str(n))
        return
    target = _プレイヤー(sender, s, args[1])
    if target is None:
        return
    f = 効果者.get(str(target.getUniqueId()))
    数 = 0 if f is None else len(f.帳.写し())
    消す(target)
    送る(sender, s, "cleared", player=target.getName(), count=str(数))


def _一覧(sender, s, args):
    if len(args) >= 2:
        target = _プレイヤー(sender, s, args[1])
        if target is None:
            return
    elif _自分か(sender):
        target = sender
    else:
        送る(sender, s, "usage")
        return
    f = 効果者.get(str(target.getUniqueId()))
    写 = {} if f is None else f.帳.写し()
    if not 写:
        送る(sender, s, "list-empty", player=target.getName())
        return
    部 = []
    for t, a in 写.items():
        追 = ""
        if t == 出血:
            追 = (" без сознания " + logic.時間表示(a.b)) if a.b > 0 else (" без перевязки " + logic.時間表示(a.a))
        elif t == 温熱:
            追 = " без питья " + logic.時間表示(a.a)
        elif t == 呪い:
            追 = " потеря через " + logic.時間表示(a.a)
        部.append(s.共通[t].名前 + " <white>" + logic.時間表示(a.残り) + "</white><dark_gray>" + 追 + "</dark_gray>")
    送る(sender, s, "list", 解析={"list": "<gray>, </gray>".join(部)}, player=target.getName())


def _包帯(sender, s, args):
    if len(args) >= 2:
        target = _プレイヤー(sender, s, args[1])
        if target is None:
            return
    elif _自分か(sender):
        target = sender
    else:
        送る(sender, s, "usage")
        return
    量 = 1
    if len(args) >= 3:
        try:
            量 = max(1, min(64 * 36, logic.java_int(args[2])))
        except ValueError:
            送る(sender, s, "usage")
            return
    合計 = 量

    def 渡す():
        残 = 合計
        while 残 > 0:
            一 = items.包帯(s, 1)
            n = min(一.getMaxStackSize(), 残)
            残 -= n
            for 余り in target.getInventory().addItem(items.包帯(s, n)).values():
                target.getWorld().dropItem(target.getLocation(), 余り)

    amazake.run_once_for_player(target, 渡す)
    送る(sender, s, "bandage-given", player=target.getName(), amount=str(合計))


@効果コマンド.tab
def 補完(sender, args):
    """Вариант последнего аргумента (фильтр по началу слова и право проверяет ядро)."""
    出 = []
    n = len(args)
    first = args[0].lower() if args else ""
    if n == 1:
        出 += 下位
    elif n == 2 and first != "reload":
        出 += [str(p.getName()) for p in Bukkit.getOnlinePlayers()]
        if first == "clear":
            出.append("all")
    elif n == 3 and first in ("give", "remove"):
        出 += list(種類一覧)
    elif n == 4 and first == "give":
        出 += ["30", "60", "2m", "5m", "inf"]
    return 出
