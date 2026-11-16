# 試験 — тесты чистой логики core_magic (settei, keisan). Запуск обычным python3 из любой папки:
#   python3 -I core-python/scripts/core_magic/tests_core_magic.py
# amazake не нужен; разбор config.yml из папки — через PyYAML (без него этот тест пропускается).

import json
import math
import os
import sys
import unittest

ここ = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ここ)

import keisan   # noqa: E402
import settei   # noqa: E402


def _yaml(文字):
    import yaml
    # amazake.config: YAML (SnakeYAML) → JSON (Gson) → dict — повторяем путь через json
    return json.loads(json.dumps(yaml.safe_load(文字)))


def 近い(a, b, eps=1e-9):
    return all(abs(x - y) <= eps for x, y in zip(a, b))


class 設定の試験(unittest.TestCase):

    def test_defaults(self):
        s = settei.解析({})
        self.assertEqual("azerus_moon_staff", s.杖.nexo_id)
        self.assertTrue(s.杖.修理)
        self.assertEqual(3600.0, s.杖.秒)
        self.assertEqual("", s.杖.権限)
        self.assertEqual(60, s.詠唱.充填)
        self.assertEqual(30.0, s.詠唱.最長)
        self.assertEqual(2, s.詠唱.見張り)
        self.assertEqual(3.0, s.冷却.秒)
        self.assertEqual(0.5, s.冷却.失敗秒)
        self.assertEqual("azerus:moon_staff_circle", s.円.モデル)
        self.assertEqual(3.0, s.円.直径)
        self.assertEqual(2.4, s.円.前)
        self.assertEqual("azerus:moon_staff_beam", s.光.モデル)
        self.assertEqual(29.0, s.光.射程)
        self.assertEqual(10.0, s.光.傷)
        self.assertEqual("minecraft:magic", s.光.傷種)
        self.assertEqual(0.6, s.光.半径)
        self.assertEqual(4.0, s.光.片長)
        self.assertEqual(20, s.唸り周期)
        self.assertEqual(3, len(s.音表["fire"]))
        self.assertEqual(settei.音("entity.warden.sonic_boom", settei.f32(0.55), settei.f32(1.7)), s.音表["fire"][0])
        self.assertEqual("END_ROD", s.終粒.種類)
        self.assertEqual(1.5, s.跡粒.毎)
        self.assertIn("<s>", s.文("cooldown"))
        self.assertIn("<bar>", s.文("charge"))

    def test_bundled_config_parses_without_warnings_and_equals_defaults(self):
        try:
            import yaml  # noqa: F401
        except ImportError:
            self.skipTest("нет PyYAML")
        with open(os.path.join(ここ, "config.yml"), encoding="utf-8") as f:
            c = _yaml(f.read())
        warns = []
        s = settei.解析(c, warns.append)
        self.assertEqual([], warns)
        self.assertEqual(settei.既定, s)   # файл = значения по умолчанию в коде

    def test_validation_clamps_and_warns(self):
        warns = []
        s = settei.解析({
            "cast": {"charge-ticks": 0, "watch-period-ticks": "fast", "max-hold-seconds": 1},
            "beam": {"range": 100, "damage": -5, "hit-players": "yes", "item-model": "no key"},
            "circle": {"diameter": 3},
            "sounds": {"fire": [{"sound": "Bad Sound!"}, {"sound": "entity.blaze.shoot", "pitch": 9}], "hum": "x"},
            "particles": {"end": {"type": "end rod"}},
            "disabled-worlds": ["world_nether", 5],
            "messages": {"cooldown": "<red>cd <s>", "info": ["a", "b"]},
        }, warns.append)
        self.assertEqual(1, s.詠唱.充填)
        self.assertEqual(2, s.詠唱.見張り)
        self.assertEqual(31.0, s.光.射程)
        self.assertEqual(0.0, s.光.傷)
        self.assertTrue(s.光.対人)
        self.assertEqual("azerus:moon_staff_beam", s.光.モデル)
        self.assertEqual(3.0, s.円.直径)            # целое число из YAML — тоже число
        self.assertEqual((settei.音("entity.blaze.shoot", 1.0, 2.0),), s.音表["fire"])
        self.assertEqual(settei.解析({}).音表["hum"], s.音表["hum"])
        self.assertEqual("END_ROD", s.終粒.種類)
        self.assertEqual(frozenset({"world_nether", "5"}), s.禁止世界)
        self.assertEqual("<red>cd <s>", s.文("cooldown"))
        self.assertEqual("a<newline>b", s.文("info"))
        # каждое исправление названо
        for 道 in ("cast.charge-ticks", "cast.watch-period-ticks", "beam.range", "beam.damage", "beam.hit-players",
                  "beam.item-model", "sounds.fire[0].sound", "sounds.fire[1].pitch", "sounds.hum",
                  "particles.end.type"):
            self.assertTrue(any(w.startswith(道) for w in warns), 道 + " не в " + repr(warns))

    def test_max_hold_must_exceed_charge(self):
        warns = []
        s = settei.解析({"cast": {"charge-ticks": 100, "max-hold-seconds": 3}}, warns.append)
        self.assertGreater(s.詠唱.最長 * 20.0, s.詠唱.充填)
        self.assertTrue(warns)

    def test_f32(self):
        self.assertEqual(0.550000011920929, settei.f32(0.55))
        self.assertEqual(3600.0, settei.f32(3600))


class 計算の試験(unittest.TestCase):

    def test_charge_encoding(self):
        self.assertEqual(0, keisan.充填R(0.0))
        self.assertEqual(125, keisan.充填R(0.5))
        self.assertEqual(250, keisan.充填R(1.0))
        self.assertEqual(250, keisan.充填R(7.0))
        self.assertEqual(0, keisan.充填R(-1.0))
        # R ≤ 250 и метка G/B — шейдер (ключ A) не путает с rezusmenu (B=254) и порталами/небом (R=255)
        self.assertLessEqual(keisan.充填R(1.0), 250)
        self.assertEqual((233, 253), (keisan.印G, keisan.印B))

    def test_beam_encoding(self):
        self.assertEqual(1, keisan.光線R(0.0))
        self.assertEqual(4, keisan.光線R(0.5))
        self.assertEqual(213, keisan.光線R(26.6))
        self.assertEqual(250, keisan.光線R(31.25))
        self.assertEqual(250, keisan.光線R(100))
        # шейдер: L = R / 8
        for Lv in (0.5, 3.0, 12.25, 26.6):
            self.assertLessEqual(abs(keisan.光線R(Lv) / 8.0 - Lv), 1.0 / 16.0 + 1e-12)
        self.assertEqual(26.6, keisan.見た目の長さ(29.0, 2.4))
        self.assertEqual(0.5, keisan.見た目の長さ(1.0, 2.4))

    def test_charge_timing(self):
        self.assertEqual(0.0, keisan.充填率(0, 60))
        self.assertEqual(0.5, keisan.充填率(30, 60))
        self.assertEqual(1.0, keisan.充填率(60, 60))
        self.assertEqual(1.0, keisan.充填率(90, 60))
        # надзор раз в 2 тика с 1-го тика: готов ровно на 60-м (а не позже)
        t, шаги = 1, 0
        while keisan.充填率(t, 60) < 1.0:
            t += 2
            шаги += 1
        self.assertEqual(61, t)
        self.assertEqual(30, шаги)

    def test_look_vector(self):
        self.assertTrue(近い((0.0, 0.0, 1.0), keisan.視線(0, 0)))       # юг, +Z
        self.assertTrue(近い((-1.0, 0.0, 0.0), keisan.視線(90, 0)))     # запад, −X
        self.assertTrue(近い((1.0, 0.0, 0.0), keisan.視線(-90, 0)))
        self.assertTrue(近い((0.0, -1.0, 0.0), keisan.視線(0, 90)))     # вниз
        v = keisan.視線(37.0, -21.0)
        self.assertAlmostEqual(1.0, math.sqrt(sum(x * x for x in v)))
        self.assertGreater(v[1], 0.0)                                  # pitch < 0 — вверх
        self.assertAlmostEqual(1.5, keisan.角度差(359.0, 0.5))
        self.assertAlmostEqual(180.0, keisan.角度差(90.0, -90.0))

    def test_pieces(self):
        self.assertEqual([(0.0, 4.0), (4.0, 8.0), (8.0, 9.0)], keisan.区切る(9.0, 4.0))
        p = keisan.区切る(29.0, 4.0)
        self.assertEqual(8, len(p))
        self.assertEqual(29.0, p[-1][1])
        self.assertEqual([], keisan.区切る(0.0, 4.0))
        for (a, b), (c, _) in zip(p, p[1:]):
            self.assertEqual(b, c)

    def test_piece_box_and_chunks(self):
        E = (0.5, 64.0, 0.5)
        d = keisan.視線(0, 0)
        箱 = keisan.区間の箱(E, d, 4.0, 8.0, 1.6)
        self.assertTrue(近い((-1.1, 62.4, 2.9, 2.1, 65.6, 10.1), 箱))
        self.assertEqual({(-1, 0), (0, 0)}, keisan.箱の列(箱))
        # граница чанка: x = 15.9 и 16.1
        self.assertEqual({(0, 0), (1, 0)}, keisan.箱の列((15.9, 0, 1, 16.1, 1, 2)))
        self.assertEqual({(-1, -1)}, keisan.箱の列((-0.5, 0, -0.5, -0.1, 1, -0.1)))

    def test_owned_length_stops_at_first_foreign_piece(self):
        E = (8.0, 64.0, 8.0)
        d = keisan.視線(0, 0)                       # на +Z
        # «свой» только чанк z=0..15 и z=16..31 (cz 0 и 1)
        自分 = lambda cx, cz: cz in (0, 1)
        L = keisan.使える長さ(E, d, 29.0, 4.0, 1.6, 自分)
        # кусок [16,20] → z до 28+1.6=29.6 → cz 1; [20,24] → z 33.6 → cz 2 — чужой
        self.assertEqual(20.0, L)
        self.assertEqual(29.0, keisan.使える長さ(E, d, 29.0, 4.0, 1.6, lambda cx, cz: True))
        self.assertEqual(0.0, keisan.使える長さ(E, d, 29.0, 4.0, 1.6, lambda cx, cz: False))
        # кэш: каждый столбец спрашивается один раз
        вызовы = []
        keisan.使える長さ(E, d, 29.0, 4.0, 1.6, lambda cx, cz: вызовы.append((cx, cz)) or True)
        self.assertEqual(len(вызовы), len(set(вызовы)))

    def test_slab(self):
        E = (0.0, 0.0, 0.0)
        d = (0.0, 0.0, 1.0)
        箱 = (-0.3, -1.0, 5.0, 0.3, 1.0, 5.6)
        self.assertAlmostEqual(5.0, keisan.線分と箱(E, d, 29.0, 箱))
        self.assertIsNone(keisan.線分と箱(E, d, 4.9, 箱))               # не дотянулся (стена раньше)
        self.assertIsNone(keisan.線分と箱(E, d, 29.0, (0.5, -1, 5, 1.1, 1, 6)))   # в стороне
        self.assertIsNone(keisan.線分と箱(E, (0.0, 0.0, -1.0), 29.0, 箱))         # позади
        self.assertEqual(0.0, keisan.線分と箱(E, d, 29.0, (-1, -1, -1, 1, 1, 1)))  # начало внутри
        # по диагонали
        v = keisan.視線(-45.0, 0.0)
        t = keisan.線分と箱(E, v, 29.0, (9.5, -1, 9.5, 10.5, 1, 10.5))
        self.assertIsNotNone(t)
        self.assertAlmostEqual(9.5 * math.sqrt(2.0), t, places=6)

    def test_hits_pierce_once_and_exclude_caster(self):
        E = (0.5, 65.62, 0.5)
        d = keisan.視線(0, 0)
        def 箱(x, z, w=0.6, h=1.95, y=64.0):
            return (x - w / 2, y, z - w / 2, x + w / 2, y + h, z + w / 2)
        候補 = [
            ("自分", 箱(0.5, 0.5, h=1.8)),       # заклинатель — сам в своём луче
            ("z1", 箱(0.5, 10.5)),
            ("z2", 箱(0.5, 20.5)),
            ("z1", 箱(0.5, 10.5)),               # дубль — один раз
            ("side", 箱(3.5, 12.0)),             # 3 блока в сторону — мимо
            ("near", 箱(1.6, 15.0)),             # край хитбокса в 0.8 от оси луча: запас 0.6 — мимо
            ("graze", 箱(1.3, 16.0)),            # край в 0.5 от оси: в запасе — попал
            ("edge", 箱(1.45, 17.0)),            # край в 0.65 от оси: мимо
            ("far", 箱(0.5, 40.0)),              # за пределом
            ("low", 箱(0.5, 8.0, y=60.0)),       # ниже луча (голова на 61.95)
        ]
        r = keisan.当たり(E, d, 29.0, 候補, "自分", 0.6)
        ids = [i for _, i in r]
        self.assertEqual(["z1", "graze", "z2"], ids)
        self.assertNotIn("自分", ids)
        ts = [t for t, _ in r]
        self.assertEqual(sorted(ts), ts)
        # стена на 12 блоках: graze и z2 уже не задеты
        self.assertEqual(["z1"], [i for _, i in keisan.当たり(E, d, 12.0, 候補, "自分", 0.6)])

    def test_trail_points(self):
        pts = keisan.道の点((0, 0, 0), (0, 0, 1), 2.4, 6.0, 1.5)
        self.assertEqual([2.4, 3.9, 5.4], [round(p[2], 6) for p in pts])
        self.assertEqual([], keisan.道の点((0, 0, 0), (0, 0, 1), 3.0, 2.0, 1.5))

    def test_cooldown_and_bar(self):
        self.assertEqual(3.0, keisan.冷却残り(1000, 4000))
        self.assertEqual(0.0, keisan.冷却残り(5000, 4000))
        self.assertEqual("3", keisan.秒の文字(3.0))
        self.assertEqual("2.5", keisan.秒の文字(2.41))
        self.assertEqual("0.1", keisan.秒の文字(0.01))
        self.assertEqual("29", keisan.秒の文字(29.0))
        b = keisan.充填棒(0.5, 20)
        self.assertEqual("<#7fd8ff>" + "|" * 10 + "</#7fd8ff><dark_gray>" + "|" * 10 + "</dark_gray>", b)
        self.assertEqual("<dark_gray>" + "|" * 20 + "</dark_gray>", keisan.充填棒(0.0, 20))
        self.assertEqual("<#7fd8ff>" + "|" * 20 + "</#7fd8ff>", keisan.充填棒(1.0, 20))


if __name__ == "__main__":
    unittest.main(verbosity=2)
