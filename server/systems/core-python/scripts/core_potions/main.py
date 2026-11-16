# ポーション — механика зелий Nexo. Вид и предмет — Nexo, здесь — что происходит, когда зелье выпили.
# Конфиг — potions.yml (ключ — id предмета Nexo). Перечитать: /cpotions reload.
import amazake

NEXOキー = "nexo:id"
体力キー = "corepotions:health_boost"   # ключ модификатора максимального здоровья
期限キー = "health_boost_until"          # в данных игрока: до какого времени (мс) действует прибавка
量キー = "health_boost_hp"

PotionEffect = amazake.java_class("org.bukkit.potion.PotionEffect")
ItemStack = amazake.java_class("org.bukkit.inventory.ItemStack")
System = amazake.java_class("java.lang.System")

設定 = {}


def 読み込み():
    global 設定
    設定 = amazake.config("potions.yml").get("potions", {}) or {}
    amazake.log.info("зелий в potions.yml:", len(設定))


@amazake.on_load
def 起動():
    読み込み()


def nexoのID(item):
    """id предмета Nexo из PDC (nexo:id) или None."""
    if item is None or item.isEmpty():
        return None
    return amazake.data(item).get_string(NEXOキー)


@amazake.on("PlayerItemConsumeEvent", priority="high", ignore_cancelled=True)
def 飲んだ(event):
    ID = nexoのID(event.getItem())
    ポーション = 設定.get(ID) if ID else None
    if ポーション is None:
        return
    player = event.getPlayer()
    if not ポーション.get("keep-bottle", False):
        event.setReplacement(ItemStack.empty())   # бутылка не возвращается — зелье просто пропадает
    for 効果 in ポーション.get("effects", []) or []:
        秒 = float(効果.get("seconds", 30))
        段 = int(効果.get("level", 1)) - 1
        player.addPotionEffect(PotionEffect(amazake.effect_type(効果["type"]), int(秒 * 20), 段, False, True, True))
    増加 = ポーション.get("health-boost")
    if 増加:
        体力 = float(増加.get("hearts", 0)) * 2
        秒 = float(増加.get("seconds", 60))
        期限 = System.currentTimeMillis() + int(秒 * 1000)
        データ = amazake.data(player)
        データ.set(期限キー, int(期限))
        データ.set(量キー, float(体力))
        体力増加(player, 体力, 期限)
    文 = ポーション.get("message")
    if 文:
        amazake.actionbar(player, 文)


def 体力増加(player, 体力, 期限):
    """Прибавка к макс. здоровью до 期限 (мс). Повторный глоток продлевает: старый таймер видит новый срок и молчит."""
    amazake.add_modifier(player, "max_health", 体力キー, 体力)
    残り = max(1, int((期限 - System.currentTimeMillis()) / 50))

    def 終了():
        データ = amazake.data(player)
        if データ.get(期限キー, 0) != 期限:
            return   # срок продлили новым зельем — снимет новый таймер
        amazake.remove_modifier(player, "max_health", 体力キー)
        データ.remove(期限キー)
        データ.remove(量キー)
        最大 = player.getAttribute(amazake.attribute("max_health")).getValue()
        if player.getHealth() > 最大:
            player.setHealth(最大)

    amazake.run_once_for_player(player, 終了, delay=残り)


@amazake.on("PlayerJoinEvent", priority="monitor")
def 参加(event):
    """Модификатор не сохраняется в файл игрока: после перезахода возвращаем прибавку на оставшееся время."""
    player = event.getPlayer()

    def 復元():
        データ = amazake.data(player)
        期限 = データ.get(期限キー, 0)
        if 期限 and 期限 > System.currentTimeMillis():
            体力増加(player, float(データ.get(量キー, 0)), 期限)
        elif 期限:
            データ.remove(期限キー)
            データ.remove(量キー)

    amazake.run_once_for_player(player, 復元, delay=2)


@amazake.command("cpotions", permission="corepotions.admin", aliases=["ポーション"])
def コマンド(sender, args):
    if args and args[0] == "reload":
        読み込み()
        amazake.send(sender, "<green>potions.yml перечитан: зелий <n></green>", n=str(len(設定)))
    else:
        amazake.send(sender, "<gray>Зелья: <list></gray>", list=", ".join(設定.keys()) or "—")
