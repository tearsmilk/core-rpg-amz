# Route the beam through the existing MythicLib magical skill pipeline, retaining Bukkit's magic DamageSource.
import java
import dougu
from kyoutsuu import Bukkit

_ObjectArray = java.type("java.lang.Object[]")
_Double = java.type("java.lang.Double")
_Arrays = java.type("java.util.Arrays")


def _objects(values):
    result = _ObjectArray(len(values))
    for i, value in enumerate(values):
        result[i] = value
    return result


def _method(cls, name, signature):
    for method in cls.getMethods():
        if method.getName() == name and [str(t.getName()) for t in method.getParameterTypes()] == signature:
            return method
    raise RuntimeError("Missing MythicLib method: " + name + str(signature))


def _constructor(cls, signature):
    for constructor in cls.getConstructors():
        if [str(t.getName()) for t in constructor.getParameterTypes()] == signature:
            return constructor
    raise RuntimeError("Missing MythicLib constructor: " + str(cls.getName()))


_plugin = Bukkit.getPluginManager().getPlugin("MythicLib")
if _plugin is None or not _plugin.isEnabled():
    raise RuntimeError("core_magic requires MythicLib for magical damage")
_loader = _plugin.getClass().getClassLoader()
_data_class = _loader.loadClass("io.lumine.mythic.lib.api.player.MMOPlayerData")
_get_data = _method(_data_class, "get", ["java.util.UUID"])
_damage_constructor = _constructor(_loader.loadClass("io.lumine.mythic.lib.damage.DamageMetadata"), ["double", "java.util.List"])
_attack_constructor = _constructor(_loader.loadClass("io.lumine.mythic.lib.damage.AttackMetadata"),
    ["io.lumine.mythic.lib.damage.DamageMetadata", "org.bukkit.entity.LivingEntity", "io.lumine.mythic.lib.api.stat.provider.StatProvider"])
_type_class = _loader.loadClass("io.lumine.mythic.lib.damage.DamageType")
_types = _Arrays.asList(_objects([_type_class.getField("MAGIC").get(None), _type_class.getField("SKILL").get(None)]))
_slot_class = _loader.loadClass("io.lumine.mythic.lib.api.player.EquipmentSlot")
_main_hand = _slot_class.getField("MAIN_HAND").get(None)
_off_hand = _slot_class.getField("OFF_HAND").get(None)


def hit(player, target, amount, source):
    """Exactly one Bukkit hit. Native MythicLib handles talent/skill/magic modifiers and protection hooks."""
    data = _get_data.invoke(None, _objects([player.getUniqueId()]))
    inv = player.getInventory()
    hand = _main_hand if dougu.杖か(inv.getItemInMainHand()) else _off_hand
    attacker = data.getStatMap().cache(hand)
    damage = _damage_constructor.newInstance(_objects([_Double(float(amount)), _types]))
    attack = _attack_constructor.newInstance(_objects([damage, target, attacker]))
    manager = _plugin.getDamage()
    previous = manager.getRegisteredAttackMetadata(target)
    manager.markAsMetadata(attack)
    try:
        if source is not None:
            target.damage(float(amount), source)
        else:
            target.damage(float(amount), player)
    finally:
        manager.unmarkAsMetadata(target)
        if previous is not None:
            manager.markAsMetadata(previous)
