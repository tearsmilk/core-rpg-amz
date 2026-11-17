# アイテム — используемые предметы Nexo (core-python). Сейчас: свиток телепортации.
# ПКМ свитком → круг телепорта под ногами (шейдер moonstaff, виды K20..23 — фиолетовый круг), отсчёт заголовком,
# фиолетовые частицы; сдвинулся — отмена. Конец отсчёта → затемнение экрана (глиф azerus:screen во весь экран в
# заголовке, плавное появление/исчезновение) → команда переноса (по умолчанию /warp 123) → затемнение уходит.
# Всё про игрока — в его потоке; круг стоит там, где стоит игрок (тот же регион), убирается при отмене/выходе/выгрузке.

import math
import struct
import java
import amazake

Bukkit = amazake.Bukkit
Material = java.type("org.bukkit.Material")
Particle = java.type("org.bukkit.Particle")
Sound = java.type("org.bukkit.Sound")
SoundCategory = java.type("org.bukkit.SoundCategory")
Color = java.type("org.bukkit.Color")
ItemStack = java.type("org.bukkit.inventory.ItemStack")
DustOptions = java.type("org.bukkit.Particle$DustOptions")
ItemDisplay = java.type("org.bukkit.entity.ItemDisplay")
ItemDisplayTransform = java.type("org.bukkit.entity.ItemDisplay$ItemDisplayTransform")
Billboard = java.type("org.bukkit.entity.Display$Billboard")
Brightness = java.type("org.bukkit.entity.Display$Brightness")
Transformation = java.type("org.bukkit.util.Transformation")
Vector3f = java.type("org.joml.Vector3f")
Quaternionf = java.type("org.joml.Quaternionf")
DataComponentTypes = java.type("io.papermc.paper.datacomponent.DataComponentTypes")
CustomModelData = java.type("io.papermc.paper.datacomponent.item.CustomModelData")
Key = java.type("net.kyori.adventure.key.Key")
Component = java.type("net.kyori.adventure.text.Component")
Title = java.type("net.kyori.adventure.title.Title")
TitleTimes = java.type("net.kyori.adventure.title.Title$Times")
Duration = java.type("java.time.Duration")

ネクソ鍵 = "nexo:id"
円モデル = Key.key("azerus", "teleport_circle")
画面字体 = Key.key("azerus", "screen")
黒 = ""                       # глиф azerus:screen — чёрный прямоугольник во весь экран


def f32(x):
    return struct.unpack("f", struct.pack("f", float(x)))[0]


紫 = DustOptions(Color.fromRGB(150, 60, 230), f32(1.4))


設定 = {}
文 = {}
詠唱 = {}          # UUID → 転移
待ち = {}          # UUID → время (мс), до которого нельзя снова


def 読み込み():
    global 設定, 文
    c = amazake.config("config.yml") or {}
    設定 = c.get("scrolls", {}) or {}
    文 = c.get("messages", {}) or {}


読み込み()


def 今ms():
    return java.type("java.lang.System").currentTimeMillis()


def 円の品(charge):
    item = ItemStack(Material.PAPER)
    item.setData(DataComponentTypes.ITEM_MODEL, 円モデル)
    r = max(0, min(250, int(round(charge * 250))))
    item.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                 CustomModelData.customModelData().addColor(Color.fromRGB(r, 233, 253)).build())
    return item


def 円の形(d):
    # модель круга стоит вертикально (плоскость XY) — кладём её на землю поворотом вокруг X
    return Transformation(Vector3f(f32(0), f32(0.03), f32(0)), Quaternionf().rotationX(f32(-math.pi / 2)),
                          Vector3f(f32(d), f32(d), f32(d)), Quaternionf())


def 消す(e):
    if e is None:
        return
    try:
        if amazake.owns(e):
            e.remove()
        else:
            amazake.remove_entity(e)
    except BaseException as ex:
        amazake.log.debug("круг: " + str(ex))


class 転移:
    def __init__(self, player, sid, c, item_slot):
        self.p = player
        self.sid = sid
        self.c = c
        self.slot = item_slot
        self.起点 = player.getLocation()
        self.tick = 0
        self.全 = int(float(c.get("seconds", 5)) * 20)
        self.円 = None
        self.task = None
        self.終 = False

    def 始める(self):
        loc = self.起点.clone()
        loc.setPitch(f32(0))
        loc.setYaw(f32(0))
        d = float(self.c.get("circle-diameter", 3.2))

        def 準備(e):
            e.setPersistent(False)
            e.setItemStack(円の品(0.05))
            e.setItemDisplayTransform(ItemDisplayTransform.NONE)
            e.setBillboard(Billboard.FIXED)
            e.setBrightness(Brightness(15, 15))
            e.setViewRange(f32(1.5))
            e.setTransformation(円の形(d * 0.4))

        self.円 = loc.getWorld().spawn(loc, ItemDisplay, 準備)
        self.円.setInterpolationDelay(0)
        self.円.setInterpolationDuration(min(self.全, 40))
        self.円.setTransformation(円の形(d))
        self.p.playSound(self.p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, f32(0.8), f32(1.4))
        self.task = amazake.run_for_player(self.p, self.歩む, 2)

    def 歩む(self):
        if self.終:
            return False
        p = self.p
        if not p.isOnline() or p.isDead():
            self.やめる(None)
            return False
        here = p.getLocation()
        tol = float(self.c.get("move-tolerance", 0.35))
        if here.getWorld() != self.起点.getWorld() or here.distanceSquared(self.起点) > tol * tol:
            self.やめる(文.get("cancelled"))
            return False
        self.tick += 2
        charge = min(1.0, self.tick / float(self.全))
        if self.tick % 10 == 0 and self.円 is not None and self.円.isValid():
            self.円.setItemStack(円の品(charge))
        if (self.tick - 2) % 20 == 0:
            n = max(1, int(math.ceil((self.全 - self.tick + 2) / 20.0)))
            p.showTitle(Title.title(amazake.mm(文.get("countdown", "<green>Телепортация через <n>"), n=str(n)),
                                    amazake.mm(文.get("dont-move", "<red>Не двигайтесь!")),
                                    TitleTimes.times(Duration.ZERO, Duration.ofMillis(1300), Duration.ZERO)))
            p.playSound(here, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, f32(0.7), f32(0.6 + 0.8 * charge))
        self.粒(here, charge)
        if self.tick >= self.全:
            self.飛ぶ()
            return False
        return True

    def 粒(self, at, charge):
        w = at.getWorld()
        r = float(self.c.get("circle-diameter", 3.2)) * 0.5
        for i in range(3):
            a = self.tick * 0.35 + i * 2.094
            x = at.getX() + math.cos(a) * r * (0.6 + 0.4 * charge)
            z = at.getZ() + math.sin(a) * r * (0.6 + 0.4 * charge)
            y = at.getY() + 0.2 + (self.tick % 20) * 0.08
            w.spawnParticle(Particle.DUST, x, y, z, 2, 0.08, 0.08, 0.08, 0.0, 紫)
        w.spawnParticle(Particle.PORTAL, at.getX(), at.getY() + 1.0, at.getZ(), int(6 + 14 * charge), 0.6, 0.8, 0.6, 0.6)
        if charge > 0.6:
            w.spawnParticle(Particle.WITCH, at.getX(), at.getY() + 0.3, at.getZ(), 3, r * 0.5, 0.1, r * 0.5, 0.0)

    def 飛ぶ(self):
        self.終 = True
        p, c = self.p, self.c
        fi = int(c.get("dark-fade-in-ticks", 10))
        hold = int(c.get("dark-hold-ticks", 30))
        fo = int(c.get("dark-fade-out-ticks", 20))
        sub = amazake.mm(文.get("moving-title", "<#4f8dff>Перемещение") + "<white> · " + str(c.get("place", "")))
        black = Component.text(黒).font(画面字体)
        p.showTitle(Title.title(black, sub, TitleTimes.times(Duration.ofMillis(50 * fi), Duration.ofMillis(50 * (hold + 4)),
                                                           Duration.ofMillis(50 * fo))))
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, f32(1.0), f32(0.8))
        消す(self.円)
        self.円 = None
        amazake.run_once_for_player(p, self.移す, max(1, fi))

    def 移す(self):
        p, c = self.p, self.c
        詠唱.pop(str(p.getUniqueId()), None)
        待ち[str(p.getUniqueId())] = 今ms() + int(float(c.get("cooldown-seconds", 3)) * 1000)
        if not p.isOnline():
            return
        if c.get("consume", True) and not 使う(p, self.sid):
            return   # свитка уже нет в инвентаре — не переносим
        cmd = str(c.get("command", "warp 123"))
        if str(c.get("run-as", "player")) == "console":
            amazake.run_global(lambda: Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName())))
        else:
            p.performCommand(cmd.replace("{player}", p.getName()))

    def やめる(self, msg):
        self.終 = True
        消す(self.円)
        self.円 = None
        uid = str(self.p.getUniqueId())
        詠唱.pop(uid, None)
        待ち[uid] = 今ms() + int(float(self.c.get("cooldown-seconds", 3)) * 1000)
        if msg and self.p.isOnline():
            self.p.clearTitle()
            amazake.actionbar(self.p, msg)
            self.p.playSound(self.p.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, f32(0.6), f32(1.2))


def 使う(p, sid):
    """Списать один свиток с id sid (рука, затем инвентарь). True — списан."""
    inv = p.getInventory()
    for slot in [inv.getHeldItemSlot()] + list(range(inv.getSize())):
        it = inv.getItem(slot)
        if it is not None and not it.isEmpty() and amazake.data(it).get_string(ネクソ鍵) == sid:
            it.setAmount(it.getAmount() - 1)
            inv.setItem(slot, it if it.getAmount() > 0 else None)
            return True
    return False


@amazake.on("PlayerInteractEvent", ignore_cancelled=False)
def 右クリック(event):
    a = event.getAction().name()
    if a != "RIGHT_CLICK_AIR" and a != "RIGHT_CLICK_BLOCK":
        return
    item = event.getItem()
    if item is None or item.isEmpty():
        return
    sid = amazake.data(item).get_string(ネクソ鍵)
    if not sid or sid not in 設定:
        return
    event.setCancelled(True)
    p = event.getPlayer()
    uid = str(p.getUniqueId())
    if uid in 詠唱:
        amazake.actionbar(p, 文.get("busy", "<gray>Телепортация уже идёт."))
        return
    left = 待ち.get(uid, 0) - 今ms()
    if left > 0:
        amazake.actionbar(p, 文.get("cooldown", "<gray>Подождите <s> с."), s="%.1f" % (left / 1000.0))
        return
    t = 転移(p, sid, 設定[sid], None)
    詠唱[uid] = t
    t.始める()


@amazake.on("PlayerQuitEvent", priority="monitor")
def 退出(event):
    t = 詠唱.pop(str(event.getPlayer().getUniqueId()), None)
    if t is not None:
        t.やめる(None)


@amazake.on("PlayerDeathEvent", priority="monitor")
def 死亡(event):
    t = 詠唱.get(str(event.getEntity().getUniqueId()))
    if t is not None:
        t.やめる(None)


@amazake.on_unload
def 片付け():
    for t in list(詠唱.values()):
        t.終 = True
        消す(t.円)
    詠唱.clear()


@amazake.command("citems")
def 命令(sender, args):
    if args and args[0].lower() == "reload":
        def 読む():
            読み込み()
            amazake.send(sender, "<green>core_items: config.yml перечитан, свитков: <n>", n=str(len(設定)))
        amazake.run_async(読む)
    else:
        amazake.send(sender, "<gray>/citems reload")


@命令.tab
def 補完(sender, args):
    return ["reload"] if len(args) == 1 else []
