# Эталон для JapaneseModeTest: прежний (английский) main.py системы core_potions до перевода на японский режим.
# Тест прогоняет его и японский scripts/core_potions/main.py по одному сценарию и сравнивает результаты.
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
全体 = {}


def 読み込み():
    global 設定, 全体
    全体 = amazake.config("potions.yml") or {}
    設定 = 全体.get("potions", {}) or {}
    amazake.log.info("зелий в potions.yml:", len(設定))


def MMOCoreのデータ(player):
    """PlayerData MMOCore или None (если MMOCore нет)."""
    try:
        return amazake.plugin_class("MMOCore", "net.Indyuce.mmocore.api.player.PlayerData").get(player)
    except Exception:
        return None


def 職業ID(データ):
    try:
        return str(データ.getProfess().getId()).upper()
    except Exception:
        return None


def 回復(player, 種類, 量):
    """Восстановить сразу. Возвращает (можно_ли_пить, сообщение_об_отказе)."""
    文 = 全体.get("messages", {}) or {}
    if 種類 == "health":
        最大 = player.getAttribute(amazake.attribute("max_health")).getValue()
        if player.getHealth() >= 最大:
            return False, 文.get("full")
        player.setHealth(min(最大, player.getHealth() + 量))
        return True, None
    データ = MMOCoreのデータ(player)
    if データ is None:
        return False, None
    職業 = 職業ID(データ)
    魔法職 = [str(x).upper() for x in 全体.get("mana-classes", [])]
    無職 = [str(x).upper() for x in 全体.get("no-class", ["HUMAN"])]
    if 種類 == "mana" and 職業 not in 魔法職:
        return False, 文.get("only-mages")
    if 種類 == "energy" and (職業 is None or 職業 in 魔法職 or 職業 in 無職):
        return False, 文.get("only-energy")
    最大 = データ.getStats().getStat("MAX_MANA")
    if データ.getMana() >= 最大:
        return False, 文.get("full")
    理由 = amazake.java_class("net.Indyuce.mmocore.api.event.PlayerResourceUpdateEvent$UpdateReason").OTHER
    データ.giveMana(float(量), 理由)
    return True, None


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
    種類 = ポーション.get("restore")
    if 種類:
        量 = float(ポーション.get("amount", 0))
        できる, 拒否 = 回復(player, 種類, 量)
        if not できる:
            event.setCancelled(True)   # не выпито — зелье остаётся в руке
            if 拒否:
                amazake.actionbar(player, 拒否)
            return
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
        amazake.actionbar(player, 文, amount=str(int(float(ポーション.get("amount", 0)))))


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
