# カスタム防具 — セットボーナス（бонусы комплектов）
#
# Сколько частей каждого комплекта надето — считается в потоке игрока при смене брони, входе и возрождении.
# Бонус за 2 и за 4 части: атрибуты игрока и эффекты (через API amazake — снимаются сами при выгрузке),
# прибавка к урону и особые свойства (поджог, вампиризм, замедление обидчика, реген на траве).
#
# Потоки: всё про игрока — в его потоке. Урон: событие приходит в потоке региона жертвы; ударивший в ближнем бою
# почти всегда в том же регионе, но это проверяется (amazake.owns) — иначе работа уходит в его поток / регион.

import random
import java
import amazake
import items

Player = java.type("org.bukkit.entity.Player")
LivingEntity = java.type("org.bukkit.entity.LivingEntity")
Projectile = java.type("org.bukkit.entity.Projectile")
Attribute = java.type("org.bukkit.attribute.Attribute")
Particle = java.type("org.bukkit.Particle")

効果の周期 = 40          # тиков между обновлениями эффектов (эффект живёт 100 тиков — с запасом)
効果の長さ = 100

# 状態: UUID игрока → {"段階": {(セットID, 段): True}, "数": {セットID: n}, "タスク": Task|None}
# Каждую запись трогает только поток этого игрока.
状態 = {}


def _段階(セット, 段):
    """Бонус комплекта за 段 частей (ключи в YAML — числа, после JSON — строки)."""
    ボーナス = セット.get("bonus") or {}
    return ボーナス.get(str(段)) or ボーナス.get(段)


def 装備を数える(player):
    """{セットID: сколько частей надето}."""
    数 = {}
    for item in player.getInventory().getArmorContents():
        判定 = items.部品を判定(item)
        if 判定:
            数[判定[0]] = 数.get(判定[0], 0) + 1
    return 数


def 有効な段階(数):
    """Какие бонусы действуют: {(セットID, 段)}."""
    結果 = set()
    for セットID, n in 数.items():
        セット = items.全セット.get(セットID)
        if not セット:
            continue
        for 段 in (2, 4):
            if n >= 段 and _段階(セット, 段):
                結果.add((セットID, 段))
    return 結果


def _属性キー(セットID, 段, i):
    return "bonus_" + セットID + "_" + str(段) + "_" + str(i)


def _付ける(player, セットID, 段):
    ボーナス = _段階(items.全セット[セットID], 段)
    for i, a in enumerate(ボーナス.get("attributes") or []):
        amazake.add_modifier(player, a["attribute"], _属性キー(セットID, 段, i), a.get("amount", 0), a.get("operation", "add"))


def _外す(player, セットID, 段):
    ボーナス = _段階(items.全セット.get(セットID) or {}, 段) or {}
    for i, a in enumerate(ボーナス.get("attributes") or []):
        amazake.remove_modifier(player, a["attribute"], _属性キー(セットID, 段, i))
    for e in ボーナス.get("effects") or []:
        amazake.remove_effect(player, e["type"])


def 再計算(player):
    """Пересчитать бонусы игрока (в его потоке)."""
    if not player.isOnline():
        return
    uid = str(player.getUniqueId())
    記録 = 状態.setdefault(uid, {"段階": set(), "数": {}, "タスク": None})
    数 = 装備を数える(player)
    新 = 有効な段階(数)
    旧 = 記録["段階"]
    for セットID, 段 in sorted(旧 - 新):
        _外す(player, セットID, 段)
    for セットID, 段 in sorted(新 - 旧):
        _付ける(player, セットID, 段)
        amazake.actionbar(player, "<gray>Бонус комплекта <set> (<n> части)", set=_表示名(セットID), n=str(段))
    記録["段階"] = 新
    記録["数"] = 数
    if 新:
        _効果を更新(player)
        if 記録["タスク"] is None or 記録["タスク"].isCancelled():
            記録["タスク"] = amazake.run_for_player(player, lambda: _周期(player), 効果の周期)
    else:
        if 記録["タスク"] is not None:
            記録["タスク"].cancel()
        状態.pop(uid, None)


def 状態を消す(player):
    """Игрок вышел: задача и так отменится сама, модификаторы transient."""
    記録 = 状態.pop(str(player.getUniqueId()), None)
    if 記録 and 記録["タスク"] is not None:
        記録["タスク"].cancel()


def 状態を初期化(player):
    """После возрождения у игрока новые атрибуты/эффекты — бонусы надо наложить заново."""
    記録 = 状態.get(str(player.getUniqueId()))
    if 記録:
        記録["段階"] = set()


def _表示名(セットID):
    import re
    return re.sub(r"<[^>]+>", "", items.全セット.get(セットID, {}).get("name", セットID))


def _効果を更新(player):
    記録 = 状態.get(str(player.getUniqueId()))
    if not 記録:
        return
    for セットID, 段 in 記録["段階"]:
        ボーナス = _段階(items.全セット[セットID], 段)
        for e in ボーナス.get("effects") or []:
            amazake.add_effect(player, e["type"], 効果の長さ, int(e.get("level", 1)) - 1, ambient=True, particles=False)
        草 = ボーナス.get("grass_regen")
        if 草 and _草の上(player, 草.get("blocks") or ["GRASS_BLOCK"]):
            amazake.add_effect(player, "regeneration", 60, int(草.get("level", 1)) - 1, ambient=True, particles=True)


def _草の上(player, 種類):
    足元 = player.getLocation().clone().subtract(0.0, 0.2, 0.0).getBlock()
    return str(足元.getType().name()) in 種類


def _周期(player):
    """Каждые 効果の周期 тиков в потоке игрока: обновить эффекты. False — остановить задачу."""
    記録 = 状態.get(str(player.getUniqueId()))
    if not 記録 or not 記録["段階"]:
        return False
    _効果を更新(player)
    return True


def _合計(uid, 名前):
    記録 = 状態.get(uid)
    if not 記録:
        return 0.0
    s = 0.0
    for セットID, 段 in 記録["段階"]:
        s += float((_段階(items.全セット[セットID], 段) or {}).get(名前) or 0)
    return s


def _特性(uid, 名前):
    記録 = 状態.get(uid)
    if not 記録:
        return None
    for セットID, 段 in sorted(記録["段階"], key=lambda x: -x[1]):
        v = (_段階(items.全セット[セットID], 段) or {}).get(名前)
        if v:
            return v
    return None


def 攻撃者(event):
    """Кто ударил: игрок/моб или стрелявший."""
    d = event.getDamager()
    if java.instanceof(d, Projectile):
        s = d.getShooter()
        return s if s is not None and java.instanceof(s, LivingEntity) else None
    return d if java.instanceof(d, LivingEntity) else None


def 攻撃前(event):
    """HIGH: прибавка к урону атакующего и поджог цели (цель — в потоке события)."""
    攻 = 攻撃者(event)
    if 攻 is None or not java.instanceof(攻, Player):
        return
    uid = str(攻.getUniqueId())
    増加 = _合計(uid, "damage")
    if 増加 > 0:
        event.setDamage(event.getDamage() * (1.0 + 増加))
    確率 = _合計(uid, "ignite_chance")
    if 確率 > 0 and random.random() < 確率:
        標的 = event.getEntity()
        秒 = _特性(uid, "ignite_seconds") or 3
        標的.setFireTicks(max(標的.getFireTicks(), int(float(秒) * 20)))


def 攻撃後(event):
    """MONITOR: вампиризм от итогового урона; замедление того, кто ударил владельца лунного комплекта."""
    攻 = 攻撃者(event)
    被 = event.getEntity()
    if 攻 is not None and java.instanceof(攻, Player):
        吸収 = _合計(str(攻.getUniqueId()), "lifesteal")
        if 吸収 > 0 and event.getFinalDamage() > 0:
            量 = event.getFinalDamage() * 吸収
            if amazake.owns(攻):
                _回復(攻, 量)
            else:
                amazake.run_once_for_player(攻, lambda: _回復(攻, 量))
    if java.instanceof(被, Player) and 攻 is not None:
        遅 = _特性(str(被.getUniqueId()), "slow_attackers")
        if 遅:
            if amazake.owns(攻):
                _凍らせる(攻, 遅)
            elif java.instanceof(攻, Player):
                amazake.run_once_for_player(攻, lambda: _凍らせる(攻, 遅))
            else:
                amazake.run_in_region(攻, lambda: _凍らせる(攻, 遅) if amazake.owns(攻) else None)


def _回復(player, 量):
    if player.isDead():
        return
    最大 = player.getAttribute(Attribute.MAX_HEALTH).getValue()
    player.setHealth(min(最大, player.getHealth() + 量))
    player.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, player.getLocation().add(0.0, 1.2, 0.0), 2, 0.2, 0.2, 0.2, 0.0)


def _凍らせる(entity, 遅):
    PotionEffect = java.type("org.bukkit.potion.PotionEffect")
    種類 = amazake.effect_type("slowness")
    秒 = float(遅.get("seconds", 3))
    entity.addPotionEffect(PotionEffect(種類, int(秒 * 20), int(遅.get("level", 1)) - 1, False, True, True))
    if 遅.get("freeze"):
        entity.setFreezeTicks(max(entity.getFreezeTicks(), int(秒 * 20) + 140))
    entity.getWorld().spawnParticle(Particle.SNOWFLAKE, entity.getLocation().add(0.0, 1.0, 0.0), 12, 0.3, 0.5, 0.3, 0.02)
