# 魔法 — メイン（события и команды）
#
# Python-система core-python «Посох Лунного Странника». Посох (azerus_moon_staff) — предмет Nexo с компонентом
# consumable (анимация лука, consume_seconds 3600): игрок ЗАЖИМАЕТ ПКМ — перед ним появляется голубой магический
# круг и за 3 с растёт и разгорается; ОТПУСТИЛ после 3 с — из круга бьёт белый луч (урон всем на пути); раньше —
# круг гаснет без выстрела.
#   /cmagic give [ник] [кол-во]   выдать посох
#   /cmagic reload                перечитать config.yml
#   /cmagic info                  заклинания, перезарядка, главные числа
# Японский псевдоним: /魔法. Право: coremagic.admin.
#
# Состояние — в памяти: 詠唱 (UUID → идущее заклинание), 冷却 (UUID → момент готовности, мс). Всё про игрока
# трогается только в его потоке: обработчики его событий и его задача-«надзор» (run_for_player, только пока идёт
# заклинание). Ни PlayerMoveEvent, ни глобальных циклов по игрокам.

import java
import amazake

import settei
import keisan
import kyoutsuu
import dougu
import mahoujin
import kousen
from kyoutsuu import Bukkit, EventResult

Player = kyoutsuu.Player


# --- 読み込み（загрузка; асинхронный поток — только конфиг） ----------------------------------------------------

def _設定を読む():
    警告 = []
    s = settei.解析(amazake.config("config.yml"), 警告.append)
    for m in 警告:
        amazake.log.warn("config.yml: " + m)
    settei.現在[0] = s
    return s


_設定を読む()

#: Идущие заклинания: str(UUID) → 詠唱中.
詠唱 = {}
#: Перезарядка: str(UUID) → момент (мс, монотонно), когда посох снова готов.
冷却 = {}
#: Игроки, у которых через тик проверяется начало заклинания (чтобы не запускать дважды).
確認待ち = set()
#: Последний отказ «посох восстанавливается» (мс): зажатый ПКМ повторяет попытку каждые 4 тика — сообщение и звук
#: не чаще 拒否間隔 мс.
拒否 = {}
拒否間隔 = 500


def _文(cfg, 鍵, 生=None, **素):
    """Шаблон messages.<鍵>; 生 — подстановки с MiniMessage внутри (вставляются в текст до разбора)."""
    t = cfg.文(鍵)
    if 生:
        for k, v in 生.items():
            t = t.replace("<" + k + ">", v)
    return t, 素


def _言う(target, cfg, 鍵, 生=None, **素):
    t, 素 = _文(cfg, 鍵, 生, **素)
    if t.strip():
        amazake.send(target, t, **素)


def _帯(p, cfg, 鍵, 生=None, **素):
    t, 素 = _文(cfg, 鍵, 生, **素)
    if t.strip():
        amazake.actionbar(p, t, **素)


def _残り秒(uid):
    終 = 冷却.get(uid)
    if 終 is None:
        return 0.0
    r = keisan.冷却残り(kyoutsuu.今ms(), 終)
    if r <= 0.0:
        冷却.pop(uid, None)
    return r


def _冷やす(uid, 秒, player=None):
    """Перезарядка: своя (в памяти, проверяется на ПКМ) и ванильная по группе use_cooldown посоха — тогда клиент
    сам не начинает удержание, а на слоте видна «шторка». Поток игрока."""
    if 秒 > 0.0:
        冷却[uid] = kyoutsuu.今ms() + int(秒 * 1000.0)
    else:
        冷却.pop(uid, None)
    if player is not None:
        try:
            player.setCooldown(kyoutsuu.鍵(dougu.冷却組), max(0, int(round(秒 * 20.0))))
        except BaseException as e:
            amazake.log.debug("ванильная перезарядка: " + str(e))


# --- 詠唱（заклинание одного игрока） ------------------------------------------------------------------------------

class 詠唱中:
    __slots__ = ("player", "uid", "cfg", "陣", "ticks", "準備", "task", "世界", "終わった", "棒", "色tick", "唸りtick",
                 "準備tick")

    def __init__(self, player, cfg, ticks):
        self.player = player
        self.uid = str(player.getUniqueId())
        self.cfg = cfg
        self.陣 = mahoujin.魔法陣(player, cfg)
        self.ticks = ticks
        self.準備 = False
        self.task = None
        self.世界 = player.getWorld().getName()
        self.終わった = False
        self.棒 = None
        self.色tick = ticks
        self.唸りtick = ticks
        self.準備tick = 0

    def 始める(self):
        """Поток игрока: круг, звук, надзор."""
        p = self.player
        c = self.cfg
        eye = p.getEyeLocation()
        charge = keisan.充填率(self.ticks, c.詠唱.充填)
        if not self.陣.出す(eye, charge, c.詠唱.充填 - self.ticks):
            return False
        詠唱[self.uid] = self
        kyoutsuu.鳴らす(p.getWorld(), eye, c.音表["start"])
        self.task = amazake.run_for_player(p, self.見張り, period=c.詠唱.見張り)
        return True

    def 見張り(self):
        """Каждые watch-period-ticks в потоке игрока. False — остановить задачу."""
        if self.終わった or 詠唱.get(self.uid) is not self or kyoutsuu.終了済み[0]:
            return False
        p = self.player
        c = self.cfg
        if not p.isOnline() or p.isDead() or p.getWorld().getName() != self.世界:
            self.終える("即")
            return False
        if not p.isHandRaised() or not dougu.杖か(p.getActiveItem()):
            # ПКМ отпущен без события отпускания (сервер сам прервал использование) — тихий срыв
            self.終える("散", c.冷却.失敗秒)
            return False
        self.ticks += c.詠唱.見張り
        充填 = c.詠唱.充填
        charge = keisan.充填率(self.ticks, 充填)
        eye = p.getEyeLocation()
        self.陣.追う(eye, charge, 充填 - self.ticks)
        if self.ticks - self.色tick >= c.円.更新 or (charge >= 1.0 and not self.準備):
            self.色tick = self.ticks
            self.陣.色(charge)
        if self.ticks - self.唸りtick >= c.唸り周期:
            self.唸りtick = self.ticks
            kyoutsuu.鳴らす(p.getWorld(), eye, c.音表["hum"], 0.4 * charge)
        if self.ticks >= 充填:
            if not self.準備:
                self.準備 = True
                self.準備tick = self.ticks
                kyoutsuu.鳴らす(p.getWorld(), eye, c.音表["ready"])
                _帯(p, c, "ready")
            elif (self.ticks - self.準備tick) % 20 < c.詠唱.見張り:
                _帯(p, c, "ready")
        elif c.詠唱.棒:
            棒 = keisan.充填棒(charge, c.詠唱.棒長)
            if 棒 != self.棒:
                self.棒 = 棒
                _帯(p, c, "charge", {"bar": 棒})
        if self.ticks >= c.詠唱.最長 * 20.0:
            self.終える("散", c.冷却.失敗秒)
            try:
                p.clearActiveItem()
            except BaseException:
                pass
            _帯(p, c, "too-long")
            return False
        return True

    def 終える(self, 方法, 冷却秒=None):
        """Конец заклинания: 方法 — «撃» (выстрел), «散» (срыв: круг сжимается), «即» (убрать сразу)."""
        if self.終わった:
            return
        self.終わった = True
        if 詠唱.get(self.uid) is self:
            del 詠唱[self.uid]
        t = self.task
        self.task = None
        if t is not None:
            try:
                t.cancel()
            except BaseException:
                pass
        if 冷却秒 is not None:
            _冷やす(self.uid, 冷却秒, self.player)
        if 方法 == "撃":
            self.陣.放つ()
        elif 方法 == "散":
            self.陣.散る()
            kyoutsuu.鳴らす(self.player.getWorld(), self.player.getEyeLocation(), self.cfg.音表["fizzle"])
        else:
            self.陣.即消す()


def _止める(player, 方法):
    s = 詠唱.get(str(player.getUniqueId()))
    if s is not None:
        s.終える(方法, s.cfg.冷却.失敗秒 if 方法 == "散" else None)


# --- 開始（ПКМ с посохом） ------------------------------------------------------------------------------------

@amazake.on("PlayerInteractEvent", ignore_cancelled=False)
def 右クリック(event):
    """ПКМ в воздух/по блоку с посохом (поток игрока). Клик в воздух приходит «отменённым» — поэтому
    ignore_cancelled=False. Начало заклинания проверяется через тик: держит ли игрок посох поднятым."""
    a = event.getAction().name()
    if a != "RIGHT_CLICK_AIR" and a != "RIGHT_CLICK_BLOCK":
        return
    item = event.getItem()
    if not dougu.杖か(item):
        return
    p = event.getPlayer()
    cfg = settei.今()
    if dougu.直すべきか(item) and event.getHand() is not None:
        # посох без consumable (старый предмет): чиним — заработает со следующего клика
        copy = item.clone()
        dougu.直す(copy)
        p.getInventory().setItem(event.getHand(), copy)
    if p.getWorld().getName() in cfg.禁止世界 or (cfg.杖.権限 and not p.hasPermission(cfg.杖.権限)):
        event.setUseItemInHand(EventResult.DENY)
        return
    uid = str(p.getUniqueId())
    if uid in 詠唱:
        return
    残り = _残り秒(uid)
    if 残り > 0.0:
        event.setUseItemInHand(EventResult.DENY)     # без анимации натягивания
        今 = kyoutsuu.今ms()
        if 今 - 拒否.get(uid, -拒否間隔) >= 拒否間隔:
            拒否[uid] = 今
            _帯(p, cfg, "cooldown", s=keisan.秒の文字(残り))
            kyoutsuu.鳴らす(p.getWorld(), p.getEyeLocation(), cfg.音表["denied"])
        return
    if uid in 確認待ち:
        return
    確認待ち.add(uid)
    amazake.run_once_for_player(p, lambda: _確認(p), delay=1)


def _確認(p):
    uid = str(p.getUniqueId())
    確認待ち.discard(uid)
    if kyoutsuu.終了済み[0] or uid in 詠唱:
        return
    if not p.isOnline() or p.isDead():
        return
    if not p.isHandRaised() or not dougu.杖か(p.getActiveItem()):
        return
    try:
        ticks = max(1, int(p.getActiveItemUsedTime()))
    except BaseException:
        ticks = 1
    詠唱中(p, settei.今(), ticks).始める()


# --- 解放（отпустил ПКМ） -------------------------------------------------------------------------------------

@amazake.on("io.papermc.paper.event.player.PlayerStopUsingItemEvent")
def 手を離す(event):
    p = event.getPlayer()
    s = 詠唱.get(str(p.getUniqueId()))
    if s is None or not dougu.杖か(event.getItem()):
        return
    c = s.cfg
    try:
        held = int(event.getTicksHeldFor())
    except BaseException:
        held = s.ticks
    if held >= c.詠唱.充填:
        s.終える("撃", c.冷却.秒)
        try:
            kousen.撃つ(p, c, s.陣)
        except BaseException as e:
            amazake.log.warn("выстрел посоха: " + str(e))
    else:
        s.終える("散", c.冷却.失敗秒)
        _帯(p, c, "too-early", need=keisan.秒の文字(c.詠唱.充填 / 20.0))


# --- 中断（прервать заклинание） -----------------------------------------------------------------------------------

@amazake.on("PlayerItemHeldEvent", priority="monitor")
def 持ち替え(event):
    p = event.getPlayer()
    if not event.isCancelled() and str(p.getUniqueId()) in 詠唱:
        # смена слота прерывает только заклинание из основной руки (из второй руки ваниль его не прерывает)
        if not p.isHandRaised() or p.getActiveItemHand() == kyoutsuu.EquipmentSlot.HAND:
            _止める(p, "散")
    if settei.今().杖.修理:
        # задачу ставим только когда в новом слоте посох с неверными компонентами (а не на каждую прокрутку)
        新 = event.getNewSlot()
        if dougu.直すべきか(p.getInventory().getItem(新)):
            amazake.run_once_for_player(p, lambda: dougu.枠を直す(p, 新), delay=1)


@amazake.on("PlayerSwapHandItemsEvent", priority="monitor", ignore_cancelled=True)
def 持ち替え2(event):
    _止める(event.getPlayer(), "散")


@amazake.on("PlayerDropItemEvent", priority="monitor", ignore_cancelled=True)
def 落とす(event):
    if dougu.杖か(event.getItemDrop().getItemStack()):
        _止める(event.getPlayer(), "散")


@amazake.on("PlayerDeathEvent", priority="monitor")
def 死亡(event):
    _止める(event.getEntity(), "即")


@amazake.on("PlayerQuitEvent", priority="monitor")
def 退出(event):
    p = event.getPlayer()
    _止める(p, "即")
    uid = str(p.getUniqueId())
    確認待ち.discard(uid)
    拒否.pop(uid, None)
    _残り秒(uid)          # истёкшую перезарядку — забыть


@amazake.on("PlayerChangedWorldEvent", priority="monitor")
def 世界移動(event):
    _止める(event.getPlayer(), "即")


@amazake.on("PlayerTeleportEvent", priority="monitor", ignore_cancelled=True)
def 瞬間移動(event):
    p = event.getPlayer()
    if str(p.getUniqueId()) not in 詠唱:
        return
    f = event.getFrom()
    t = event.getTo()
    if t is None or not f.getWorld().equals(t.getWorld()) or f.distanceSquared(t) > 16.0:
        _止める(p, "即")


@amazake.on("PlayerItemConsumeEvent", priority="lowest")
def 食べる(event):
    """Страховка: посох никогда не «съедается» (consume_seconds 3600 и стоп на max-hold-seconds до этого)."""
    if dougu.杖か(event.getItem()):
        event.setCancelled(True)


@amazake.on("PlayerJoinEvent", priority="monitor")
def 参加(event):
    if not settei.今().杖.修理:
        return
    p = event.getPlayer()
    # через 5 тиков: Nexo успевает обновить предметы игрока, потом возвращаем consumable
    amazake.run_once_for_player(p, lambda: dougu.全部直す(p), delay=5)


# --- 起動・終了（загрузка и выгрузка） -----------------------------------------------------------------------------

@amazake.on_load
def 起動():
    """Остатки прошлой загрузки (дисплеи с меткой py_core_magic:fx) рядом с игроками — убрать, каждому в его потоке."""
    for p in Bukkit.getOnlinePlayers():
        amazake.run_once_for_player(p, lambda p=p: _掃除(p))


def _掃除(p):
    n = 0
    try:
        近く = p.getNearbyEntities(48.0, 48.0, 48.0)
    except BaseException:
        近く = p.getNearbyEntities(16.0, 16.0, 16.0)
    for e in 近く:
        try:
            if (java.instanceof(e, kyoutsuu.ItemDisplayType) and amazake.owns(e)
                    and amazake.data(e).has(kyoutsuu.効果鍵)):
                e.remove()
                n += 1
        except BaseException:
            pass
    if n:
        amazake.log.info("убрано старых эффектов посоха:", n)


@amazake.on_unload
def 終了():
    """Python-задачи уже отменены: дисплеи чужих регионов убираем чистыми Java-задачами региона."""
    kyoutsuu.終了済み[0] = True
    for s in list(詠唱.values()):
        s.終わった = True
        d = s.陣.表示()
        if d is not None:
            kyoutsuu.消す(d)
    for d, 場所 in list(mahoujin.残像.values()):
        kyoutsuu.消す(d)
    詠唱.clear()
    mahoujin.残像.clear()
    冷却.clear()
    確認待ち.clear()
    拒否.clear()


# --- コマンド（/cmagic, /魔法） --------------------------------------------------------------------------------------

@amazake.command("cmagic")
def 魔法コマンド(sender, args):
    sub = args[0].lower() if args else ""
    cfg = settei.今()
    if sub == "give":
        _渡す(sender, args[1:], cfg)
    elif sub == "reload":
        _再読込(sender)
    elif sub == "info":
        _情報(sender, cfg)
    else:
        _言う(sender, cfg, "usage")


@魔法コマンド.tab
def 補完(sender, args):
    if len(args) == 1:
        return ["give", "reload", "info"]
    if args[0].lower() != "give":
        return []
    if len(args) == 2:
        return [str(p.getName()) for p in Bukkit.getOnlinePlayers()]
    if len(args) == 3:
        return ["1", "2", "4", "8"]
    return []


def _渡す(sender, args, cfg):
    数 = 1
    名 = None
    if args:
        名 = args[0]
    if len(args) > 1:
        try:
            数 = int(args[1])
        except ValueError:
            数 = 0
        if 数 < 1 or 数 > 64:
            _言う(sender, cfg, "bad-amount")
            return
    if 名 is not None:
        target = Bukkit.getPlayerExact(名)
        if target is None:
            _言う(sender, cfg, "no-player", player=名)
            return
    elif java.instanceof(sender, Player):
        target = sender
    else:
        _言う(sender, cfg, "console-needs-player")
        return

    def 入れる():
        物 = [dougu.作る() for _ in range(数)]
        余り = target.getInventory().addItem(*物)
        for x in 余り.values():
            target.getWorld().dropItem(target.getLocation(), x)

    amazake.run_once_for_player(target, 入れる)
    _言う(sender, cfg, "given", amount=str(数), player=str(target.getName()))


def _再読込(sender):
    def 読む():
        _設定を読む()
        _言う(sender, settei.今(), "reloaded")

    amazake.run_async(読む)   # файл — вне тиков


def _情報(sender, cfg):
    fx = len(mahoujin.残像) + sum(1 for s in list(詠唱.values()) if s.陣.表示() is not None)
    cd = 0.0
    if java.instanceof(sender, Player):
        cd = _残り秒(str(sender.getUniqueId()))
    _言う(sender, cfg, "info", casts=str(len(詠唱)), fx=str(fx), cd=keisan.秒の文字(cd),
         charge=keisan.秒の文字(cfg.詠唱.充填 / 20.0), range=keisan.秒の文字(cfg.光.射程),
         damage=keisan.秒の文字(cfg.光.傷), cooldown=keisan.秒の文字(cfg.冷却.秒))
