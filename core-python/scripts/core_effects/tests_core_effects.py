# Тесты чистой логики core_effects — повторяют Java-тесты core-effects (src/test/java/...): EffectBookTest,
# OverlayTest, CurseFilterTest, RulesTest, SettingsTest — с теми же ожидаемыми значениями.
# Запуск (без сервера и без amazake):  python3 tests_core_effects.py

import math
import os
import random
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import logic  # noqa: E402
import settings  # noqa: E402
from logic import (出血, 恐慌, 温熱, 呪い, 効果帳, 品の事実, 普通の品, 取る数, 範囲, 範囲を解析, 秒を解析,  # noqa: E402
                   時間表示, 残り秒, 無限)

ここ = os.path.dirname(os.path.abspath(__file__))
JAVA設定 = os.path.join(ここ, "..", "..", "..", "core-effects", "src", "main", "resources", "config.yml")


def _yaml(道):
    try:
        import yaml
    except ImportError:
        return None
    with open(道, encoding="utf-8") as f:
        return yaml.safe_load(f)


class 効果帳テスト(unittest.TestCase):   # EffectBookTest

    def test_延長は上にだけ(self):     # giveExtendsOnlyUp
        b = 効果帳()
        self.assertTrue(b.与える(出血, 100, 0))
        self.assertFalse(b.与える(出血, 50, 0))
        self.assertEqual(100, b.取る(出血).残り)
        b.与える(出血, 300, 0)
        self.assertEqual(300, b.取る(出血).残り)
        b.与える(出血, -1, 0)
        self.assertTrue(b.取る(出血).無限か())
        b.与える(出血, 10, 0)
        self.assertTrue(b.取る(出血).無限か())

    def test_進めると終わる(self):     # advanceEnds
        b = 効果帳()
        b.与える(恐慌, 3, 0)
        b.与える(呪い, -1, 400)
        self.assertEqual([], b.進める(2))
        self.assertEqual([恐慌], b.進める(1))
        self.assertFalse(b.ある(恐慌))
        self.assertTrue(b.ある(呪い))
        self.assertEqual(3, b.取る(呪い).経過)
        self.assertEqual(400, b.取る(呪い).a)

    def test_符号往復(self):           # codecRoundTrip
        b = 効果帳()
        b.与える(出血, 1200, 0)
        with b.錠:
            a = b.生(出血)
            a.a = 900
            a.b = 40
        b.与える(温熱, -1, 0)
        b.進める(5)
        s = b.符号化()
        self.assertEqual("1|bleeding:1195:5:900:40|warmth:-1:5:0:0", s)

        def 失敗(p, w):
            raise AssertionError(p + " " + w)

        self.assertEqual(s, 効果帳.復号(s, 失敗).符号化())

    def test_ゴミは飛ばす(self):       # decodeSkipsGarbage
        警告 = []
        b = 効果帳.復号("1|bleeding:100:0:0:0|nope:1:1:1:1|panic:x:0:0:0|curse:0:0:0:0|warmth:5:0:0",
                      lambda p, w: 警告.append(p))
        self.assertTrue(b.ある(出血))
        self.assertFalse(b.ある(恐慌))
        self.assertFalse(b.ある(呪い))
        self.assertFalse(b.ある(温熱))
        self.assertEqual(4, len(警告))
        self.assertTrue(効果帳.復号("2|bleeding:1:0:0:0", lambda p, w: None).空())
        self.assertTrue(効果帳.復号(None, lambda p, w: None).空())
        self.assertTrue(効果帳.復号("", lambda p, w: None).空())

    def test_名前(self):               # parseNames
        self.assertEqual(出血, logic.種類を解析("Кровотечение"))
        self.assertEqual(恐慌, logic.種類を解析("panic"))
        self.assertEqual(温熱, logic.種類を解析("тепло"))
        self.assertEqual(呪い, logic.種類を解析("ПРОКЛЯТИЕ"))
        self.assertIsNone(logic.種類を解析("x"))


class 重ねテスト(unittest.TestCase):    # OverlayTest

    def test_幅はいつも同じ(self):     # everyCompositionHasTheSameWidth
        self.assertEqual("", logic.重ねる([]))
        for 層 in logic.層一覧:
            for n in range(1, 9):
                s = logic.重ねる([(層, n)])
                self.assertEqual(logic.全幅, logic.幅(s), 層 + "×" + str(n))
        混 = logic.重ねる([(logic.層_暗, 2), (logic.層_恐, 2), (logic.層_血, 3), (logic.層_熱, 1), (logic.層_呪, 1)])
        self.assertEqual(logic.全幅, logic.幅(混))

    def test_マスの計算(self):         # tileArithmeticMatchesFont
        self.assertEqual(145, int(0.5 + 256 * (72.0 / 128)) + 1)
        self.assertEqual(289, int(0.5 + 16 * (144.0 / 8)) + 1)
        self.assertEqual(logic.全幅, 2 * logic.マス送り - 1)

    def test_文字(self):               # коды глифов — как в Overlay.java
        self.assertEqual("", logic.重ねる([(logic.層_縁, 1)]))
        self.assertEqual("", logic.重ねる([(logic.層_暗, 1), (logic.層_血, 1)])[:3])


NBT = ["MMOITEMS_ITEM_ID", "nexo:id"]


def 素(m):
    return 品の事実(m)


def 可(f):
    return 普通の品(f, {"NETHER_STAR"}, NBT, False, True)


class 呪いテスト(unittest.TestCase):    # CurseFilterTest

    def test_普通は取る(self):
        for m in ["DIRT", "COBBLESTONE", "ROTTEN_FLESH", "BONE", "IRON_INGOT", "DIAMOND", "BREAD", "ARROW", "TORCH",
                  "OAK_LOG", "STRING", "GOLDEN_APPLE", "ENDER_PEARL", "POTION"]:
            self.assertTrue(可(素(m)), m)

    def test_装備は取らない(self):
        for m in ["DIAMOND_SWORD", "NETHERITE_AXE", "IRON_PICKAXE", "WOODEN_SHOVEL", "STONE_HOE", "DIAMOND_HELMET",
                  "IRON_CHESTPLATE", "GOLDEN_LEGGINGS", "LEATHER_BOOTS", "IRON_SPEAR", "BOW", "CROSSBOW", "TRIDENT",
                  "MACE", "SHIELD", "ELYTRA", "SHEARS", "FISHING_ROD", "FLINT_AND_STEEL", "TOTEM_OF_UNDYING",
                  "TURTLE_HELMET", "DIAMOND_HORSE_ARMOR", "WOLF_ARMOR", "SHULKER_BOX", "RED_SHULKER_BOX", "BUNDLE",
                  "RED_BUNDLE", "COMPASS", "SPYGLASS"]:
            self.assertFalse(可(素(m)), m)

    def test_部品で守る(self):
        self.assertFalse(可(品の事実("STICK", 最大耐久=250)))
        self.assertFalse(可(品の事実("CARVED_PUMPKIN", 装備可=True)))
        self.assertFalse(可(品の事実("PAPER", 属性=True)))
        self.assertFalse(可(品の事実("APPLE", 死の守り=True)))
        self.assertFalse(可(品の事実("SOMETHING", 道具=True)))
        self.assertFalse(可(品の事実("SOMETHING", 容器=True)))
        self.assertFalse(可(品の事実("PAPER", 独自モデル=True)))

    def test_プラグインと設定(self):
        self.assertFalse(可(品の事実("PAPER", nbt="[minecraft:custom_data={MMOITEMS_ITEM_ID:\"RING\"}]")))
        self.assertFalse(可(品の事実("PAPER", nbt="{PublicBukkitValues:{\"nexo:id\":\"ruby\"}}")))
        self.assertFalse(可(素("NETHER_STAR")))
        self.assertFalse(可(素("AIR")))
        self.assertFalse(可(素("")))
        self.assertFalse(可(None))
        名前付き = 品の事実("DIRT", 名前付き=True)
        self.assertTrue(普通の品(名前付き, set(), NBT, False, True))
        self.assertFalse(普通の品(名前付き, set(), NBT, True, True))

    def test_取る数(self):
        self.assertEqual(2, 取る数(64, 2, False))
        self.assertEqual(3, 取る数(3, 5, False))
        self.assertEqual(1, 取る数(5, 0, False))
        self.assertEqual(64, 取る数(64, 2, True))
        self.assertEqual(0, 取る数(0, 2, False))


class 規則テスト(unittest.TestCase):    # RulesTest

    def test_出血の傷(self):           # bleedingDamageEveryInterval
        u = f = 0
        回 = 0
        for t in range(1, 201):
            u, f, 事 = logic.出血の刻(u, f, t, 80, 0, 600, 160)
            if 事 == logic.傷:
                回 += 1
        self.assertEqual(2, 回)
        self.assertEqual(200, u)
        self.assertEqual(0, f)

    def test_気絶と覚醒(self):         # faintThenWakeThenRefaint
        u = f = 0
        出来事 = []
        for t in range(1, 201):
            u, f, 事 = logic.出血の刻(u, f, t, 0, 100, 30, 20)
            if 事 in (logic.気絶, logic.覚醒):
                出来事.append(事 + "@" + str(t))
        self.assertEqual(["FAINT@100", "WAKE@120", "FAINT@150", "WAKE@170", "FAINT@200"], 出来事)

    def test_気絶なし(self):           # faintDisabled
        u, f, 事 = logic.出血の刻(1_000_000, 0, 5, 0, 0, 30, 20)
        self.assertEqual(logic.無, 事)
        self.assertEqual(0, f)

    def test_許容ダメージ(self):
        self.assertEqual(1.0, logic.許容ダメージ(20, 1, 2))
        self.assertAlmostEqual(0.5, logic.許容ダメージ(2.5, 1, 2), delta=1e-9)
        self.assertEqual(0.0, logic.許容ダメージ(2, 1, 2))
        self.assertEqual(1.5, logic.許容ダメージ(1.5, 3, 0))
        self.assertEqual(0.0, logic.許容ダメージ(0, 3, 0))

    def test_血の濃さ(self):
        self.assertEqual(1, logic.血の濃さ(10, 100))
        self.assertEqual(2, logic.血の濃さ(50, 100))
        self.assertEqual(3, logic.血の濃さ(90, 100))
        self.assertEqual(1, logic.血の濃さ(90, 0))

    def test_温熱(self):               # warmthStagesAndDeath
        w = [50, 75, 90]
        self.assertEqual(0, logic.温熱段階(0, 2400, w))
        self.assertEqual(0, logic.温熱段階(1199, 2400, w))
        self.assertEqual(1, logic.温熱段階(1200, 2400, w))
        self.assertEqual(2, logic.温熱段階(1800, 2400, w))
        self.assertEqual(3, logic.温熱段階(2160, 2400, w))
        self.assertFalse(logic.致命(2399, 2400))
        self.assertTrue(logic.致命(2400, 2400))
        self.assertFalse(logic.致命(1_000_000, 0))
        self.assertEqual(0, logic.温熱段階(1_000_000, 0, w))
        self.assertEqual(0.5, logic.渇き割合(1200, 2400))
        self.assertTrue(logic.回復時(80, 80))
        self.assertFalse(logic.回復時(81, 80))
        self.assertFalse(logic.回復時(0, 80))

    def test_方向(self):               # disorientDirections
        def 近い(期待, 実):
            self.assertEqual(len(期待), len(実))
            for a, b in zip(期待, 実):
                self.assertAlmostEqual(a, b, delta=1e-9)

        近い((0, 1), logic.狂った方向(1, 0, 0.0, 0))
        近い((1, 0), logic.狂った方向(0, 1, 0.0, 0))
        近い((0, -1), logic.狂った方向(-1, 0, 0.0, 0))
        近い((-1, 0), logic.狂った方向(1, 0, 90.0, 0))
        近い((0, -1), logic.狂った方向(1, 0, 0.0, 180))
        d = logic.狂った方向(1, 1, 0.0, 0)
        self.assertAlmostEqual(1.0, math.hypot(d[0], d[1]), delta=1e-9)
        self.assertIsNone(logic.狂った方向(0, 0, 0.0, 90))
        近い((0.1, 0.1), logic.混ぜる(0.2, 0, (0, 1), 0.2, 0.5))

    def test_継続時間(self):           # durations
        self.assertEqual(90, 秒を解析("90"))
        self.assertEqual(90, 秒を解析("1m30s"))
        self.assertEqual(120, 秒を解析("2m"))
        self.assertEqual(3600, 秒を解析("1h"))
        self.assertEqual(150, 秒を解析("2м30с"))
        self.assertEqual(無限, 秒を解析("inf"))
        for 悪 in ["0", "-5", "abc", "5x", "", "99999999999"]:
            self.assertIsNone(秒を解析(悪), 悪)
        self.assertEqual("1:30", 時間表示(1800))
        self.assertEqual("0:01", 時間表示(1))
        self.assertEqual("1:00:05", 時間表示(20 * 3605))
        self.assertEqual("∞", 時間表示(-1))
        self.assertEqual(2, 残り秒(21))

    def test_範囲(self):               # ranges
        self.assertEqual(範囲(2, 5), 範囲を解析("2-5", 0, 10))
        self.assertEqual(範囲(3, 3), 範囲を解析(3, 0, 10))
        self.assertEqual(範囲(2, 5), 範囲を解析("5..2", 0, 10))
        self.assertIsNone(範囲を解析("2-50", 0, 10))
        self.assertIsNone(範囲を解析("x", 0, 10))
        r = random.Random(1)
        for _ in range(100):
            v = 範囲(2, 4).選ぶ(r)
            self.assertTrue(2 <= v <= 4)

    def test_跨ぎ(self):               # шаг задачи 2 тика == проверки «now % N == 0» каждый тик
        for 間隔 in (1, 2, 3, 5, 10, 20, 100):
            毎刻 = [t for t in range(1, 401) if t % 間隔 == 0]
            for 刻み in (1, 2):
                回 = sum(logic.跨いだ回数(t, 刻み, 間隔) for t in range(刻み, 401, 刻み))
                self.assertEqual(len(毎刻), 回, (間隔, 刻み))
                if 間隔 >= 刻み:
                    回 = sum(1 for t in range(刻み, 401, 刻み) if logic.跨いだ(t, 刻み, 間隔))
                    self.assertEqual(len(毎刻), 回, (間隔, 刻み))


class 設定テスト(unittest.TestCase):    # SettingsTest

    def _確認(self, s):
        self.assertEqual(120, s.共通[出血].既定秒)
        self.assertEqual({"bandage"}, set(s.共通[出血].治療))
        self.assertEqual({"milk", "healing-potion"}, set(s.温熱.渇き癒し))
        self.assertEqual(2400, s.温熱.期限)
        self.assertEqual(80, s.出血.ダメージ間隔)
        self.assertIn("PLAYER", s.恐慌.幻影.種類)
        self.assertIn("", s.表示.書式[出血])
        self.assertIn("NETHER_STAR", s.呪い.保護素材)

    def test_同梱の設定(self):         # bundledConfigParsesWithoutWarnings (Java-ресурс и наш config.yml = прод v2)
        for 道 in (JAVA設定, os.path.join(ここ, "config.yml")):
            if not os.path.exists(道):
                continue
            根 = _yaml(道)
            if 根 is None:
                self.skipTest("нет PyYAML")
            警告 = []
            s = settings.読む(根, 警告.append)
            self.assertEqual([], 警告, 道)
            self._確認(s)

    def test_既定(self):
        s = settings.既定   # пустой config.yml: значения по умолчанию Settings.java (protect-materials пуст)
        self.assertEqual(120, s.共通[出血].既定秒)
        self.assertEqual(2400, s.温熱.期限)
        self.assertEqual(80, s.出血.ダメージ間隔)
        self.assertEqual(0.5, s.温熱.回復)
        self.assertEqual((50, 75, 90), s.温熱.警告率)
        self.assertEqual(frozenset(), s.呪い.保護素材)
        self.assertEqual(8, len(s.呪い.保護NBT))

    def test_悪い値(self):             # badValuesFallBack
        根 = {"bleeding": {"damage": -3, "default-seconds": "abc"}, "panic": {"twist-degrees": "10-999"},
             "hud": {"placeholderapi-identifier": "Плохо!"}, "warmth": {"warn-at-percent": [50, 150, "x"]}}
        警告 = []
        s = settings.読む(根, 警告.append)
        self.assertEqual(1.0, s.出血.ダメージ)
        self.assertEqual(120, s.共通[出血].既定秒)
        self.assertEqual(60, s.恐慌.ねじれ角.最小)
        self.assertEqual("coreeffects", s.表示.papi識別子)
        self.assertEqual((50,), s.温熱.警告率)
        self.assertEqual(6, len(警告), 警告)

    def test_文(self):                 # defaultsHaveAllMessages
        for k in ["usage", "given", "cleared", "faint", "curse-took", "warmth-warn-3", "death-bleeding"]:
            self.assertTrue(settings.既定.文.取る(k), k)

    def test_空の文(self):             # Bukkit: «faint: ~» — ключа нет, остаётся текст по умолчанию; «» — выключено
        s = settings.読む({"messages": {"faint": None, "wake": ""}})
        self.assertEqual(settings.既定.文.取る("faint"), s.文.取る("faint"))
        self.assertEqual("", s.文.取る("wake"))


class 橋Test(unittest.TestCase):
    """Класс-мост PlaceholderAPI (байты; загрузка с проверкой байткода — -Xverify:all, см. README)."""

    def test_形(self):
        import struct
        import bridge
        b = bridge.作る()
        self.assertEqual(b, bridge.作る())                       # детерминированно (файл не переписывается зря)
        magic, minor, major = struct.unpack(">IHH", b[:8])
        self.assertEqual((0xCAFEBABE, 0, 52), (magic, minor, major))
        for s in (b"core/amazake/systems/coreeffects/pybridge/CoreEffectsPyExpansion",
                  b"me/clip/placeholderapi/expansion/PlaceholderExpansion", b"onRequest", b"StackMapTable",
                  b"(Lorg/bukkit/OfflinePlayer;Ljava/lang/String;)Ljava/lang/String;"):
            self.assertIn(s, b)


if __name__ == "__main__":
    unittest.main(verbosity=2)
