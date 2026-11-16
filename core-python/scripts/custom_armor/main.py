# カスタム防具 — メイン（события и команды）
#
# Python-система core-python. Комплекты — sets.yml, предметы и их вид — Nexo (nexo-items/items/azerus_armor.yml).
#   /carmor give <комплект> [часть|all] [игрок]   выдать (часть: helmet, chestplate, leggings, boots)
#   /carmor list                                   комплекты и бонусы
#   /carmor reload                                 перечитать sets.yml
# Японский псевдоним: /防具. Право: customarmor.admin.

import java
import amazake
import items
import bonuses

Bukkit = amazake.Bukkit
EquipmentSlot = java.type("org.bukkit.inventory.EquipmentSlot")

# --- 読み込み（загрузка; асинхронный поток — только конфиг） --------------------------------------
セット数 = items.セットを読み込む()
amazake.log.info("комплектов брони:", セット数, "—", ", ".join(
    k + " (" + str(v.get("jp", "")) + ")" for k, v in items.全セット.items()))


@amazake.on_load
def 起動():
    """После загрузки: пересчитать бонусы всем, кто уже онлайн (каждому — в его потоке)."""
    for p in Bukkit.getOnlinePlayers():
        amazake.run_once_for_player(p, lambda p=p: bonuses.再計算(p))


# --- イベント（события） ---------------------------------------------------------------------------

@amazake.on("PlayerArmorChangeEvent")
def 防具変更(event):
    """Смена брони (поток игрока). Устаревший предмет чиним, бонусы считаем на следующем тике —
    когда инвентарь уже точно в новом состоянии."""
    player = event.getPlayer()
    新 = event.getNewItem()
    if items.能力が古い(新):
        判定 = items.部品を判定(新)
        直した = items.能力を付ける(新.clone(), *判定)
        player.getInventory().setItem(event.getSlot(), 直した)
    amazake.run_once_for_player(player, lambda: bonuses.再計算(player))


@amazake.on("PlayerJoinEvent", priority="monitor")
def 参加(event):
    player = event.getPlayer()
    # Через 5 тиков: Nexo успевает обновить предметы игрока (ItemUpdater), потом чиним и считаем.
    amazake.run_once_for_player(player, lambda: _全部直す(player), delay=5)


@amazake.on("com.destroystokyo.paper.event.player.PlayerPostRespawnEvent", priority="monitor")
def 復活(event):
    player = event.getPlayer()
    bonuses.状態を初期化(player)
    amazake.run_once_for_player(player, lambda: bonuses.再計算(player))


@amazake.on("PlayerQuitEvent", priority="monitor")
def 退出(event):
    bonuses.状態を消す(event.getPlayer())


@amazake.on("EntityDamageByEntityEvent", priority="high", ignore_cancelled=True)
def 攻撃前(event):
    bonuses.攻撃前(event)


@amazake.on("EntityDamageByEntityEvent", priority="monitor", ignore_cancelled=True)
def 攻撃後(event):
    bonuses.攻撃後(event)


def _全部直す(player):
    """Надетые части с устаревшими характеристиками — починить; затем пересчитать бонусы."""
    inv = player.getInventory()
    for 枠 in ("HEAD", "CHEST", "LEGS", "FEET"):
        slot = EquipmentSlot.valueOf(枠)
        item = inv.getItem(slot)
        if items.能力が古い(item):
            inv.setItem(slot, items.能力を付ける(item.clone(), *items.部品を判定(item)))
    bonuses.再計算(player)


# --- コマンド（команда /carmor, она же /防具） -------------------------------------------------------

@amazake.command("carmor")
def 防具コマンド(sender, args):
    sub = args[0].lower() if args else ""
    if sub == "give":
        _渡す(sender, args[1:])
    elif sub == "list":
        _一覧(sender)
    elif sub == "reload":
        _再読込(sender)
    else:
        amazake.send(sender, "<gray>/carmor give <комплект> [часть|all] [игрок] | list | reload")


@防具コマンド.tab
def 補完(sender, args):
    if len(args) == 1:
        return ["give", "list", "reload"]
    if args[0].lower() != "give":
        return []
    if len(args) == 2:
        return list(items.全セット.keys())
    if len(args) == 3:
        return items.部位一覧 + ["all"]
    if len(args) == 4:
        return [str(p.getName()) for p in Bukkit.getOnlinePlayers()]
    return []


def _渡す(sender, args):
    if not args or args[0] not in items.全セット:
        amazake.send(sender, "<red>Нет комплекта «<s>». Есть: <list>", s=args[0] if args else "",
                     list=", ".join(items.全セット))
        return
    セットID = args[0]
    部位 = args[1].lower() if len(args) > 1 else "all"
    if 部位 != "all" and 部位 not in items.全セット[セットID].get("pieces", {}):
        amazake.send(sender, "<red>Нет части «<p>»: helmet, chestplate, leggings, boots или all", p=部位)
        return
    if len(args) > 2:
        target = Bukkit.getPlayerExact(args[2])
        if target is None:
            amazake.send(sender, "<red>Игрок <p> не в сети", p=args[2])
            return
    elif java.instanceof(sender, java.type("org.bukkit.entity.Player")):
        target = sender
    else:
        amazake.send(sender, "<red>Из консоли укажите игрока")
        return
    部位たち = [p for p in items.部位一覧 if p in items.全セット[セットID]["pieces"]] if 部位 == "all" else [部位]
    物 = [items.アイテムを作る(セットID, p) for p in 部位たち]

    def 入れる():
        余り = target.getInventory().addItem(*物)
        for x in 余り.values():
            target.getWorld().dropItem(target.getLocation(), x)

    amazake.run_once_for_player(target, 入れる)
    amazake.send(sender, "<green>Выдано <n> шт. комплекта <s> игроку <p>", n=str(len(物)), s=セットID, p=target.getName())


def _一覧(sender):
    for セットID, セット in items.全セット.items():
        amazake.send(sender, "<white><id></white> <gray>(<jp>)</gray> — " + セット.get("name", セットID), id=セットID,
                     jp=str(セット.get("jp", "")))
        for 段 in ("2", "4"):
            b = (セット.get("bonus") or {}).get(段)
            if b:
                amazake.send(sender, "<gray>  <n> части: <b>", n=段, b=", ".join(k + "=" + str(v) for k, v in b.items()))


def _再読込(sender):
    def 読む():
        n = items.セットを読み込む()
        amazake.send(sender, "<green>sets.yml перечитан, комплектов: <n>", n=str(n))
        for p in Bukkit.getOnlinePlayers():
            amazake.run_once_for_player(p, lambda p=p: _全部直す(p))

    amazake.run_async(読む)   # файл — вне тиков
