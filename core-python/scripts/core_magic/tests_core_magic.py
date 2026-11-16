# 試験 — тесты чистой логики core_magic (settei, keisan; барьер — 結界の試験). Запуск обычным python3 из любой папки:
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


# ------------------------------------------------------------------------------------------------- барьер

_表 = [(-1.082532, -1.5), (-0.216506, -1.5), (0.649519, -1.5),
      (-0.649519, -0.75), (0.216506, -0.75), (1.082532, -0.75),
      (-1.082532, 0.0), (-0.216506, 0.0), (0.649519, 0.0),
      (-0.649519, 0.75), (0.216506, 0.75), (1.082532, 0.75),
      (-1.082532, 1.5), (-0.216506, 1.5), (0.649519, 1.5)]
_W = math.sqrt(3.0) * 0.5


def _記録(yaw=0.0, eye=(0.5, 65.62, 0.5), 世界="world", id=1, 今=0, 主="a", 設定=None):
    return keisan.結界を作る(id, 世界, eye, yaw, 設定 or settei.既定.結界, 今, 主)


class 結界の試験(unittest.TestCase):

    def test_layout_matches_contract(self):
        c = keisan.蜂巣(3, 5, 0.5)
        self.assertEqual(15, len(c))
        for (u, v), (eu, ev) in zip(c, _表):
            self.assertAlmostEqual(eu, u, delta=1e-6)
            self.assertAlmostEqual(ev, v, delta=1e-6)
        rec = _記録()
        self.assertAlmostEqual(1.5155445, rec.半幅, delta=1e-6)
        self.assertAlmostEqual(2.0, rec.半高, delta=1e-9)
        for i in range(15):
            n = 0
            for j in range(15):
                if i == j:
                    continue
                d = math.hypot(c[i][0] - c[j][0], c[i][1] - c[j][1])
                self.assertGreaterEqual(d, _W - 1e-9)
                if abs(d - _W) < 1e-9:
                    n += 1
            self.assertTrue(2 <= n <= 6, (i, n))

    def test_rows_cols_cap(self):
        warns = []
        s = settei.解析({"barrier": {"columns": 7, "rows": 9}}, warns.append)
        self.assertEqual(7, s.結界.列)
        self.assertEqual(3, s.結界.行)
        self.assertLessEqual(s.結界.列 * s.結界.行, 25)
        self.assertTrue(any(w.startswith("barrier.rows") for w in warns), warns)
        self.assertEqual(21, len(keisan.蜂巣(s.結界.列, s.結界.行, 0.5)))

    def test_build_order_and_spawn_ticks(self):
        c = keisan.蜂巣(3, 5, 0.5)
        順 = keisan.組順(c)
        self.assertEqual([7, 8, 10, 4, 3, 9, 6, 11, 5, 1, 13, 14, 2, 0, 12], 順)
        ts = [keisan.出現tick(m, 15, 20, 6) for m in range(15)]
        self.assertEqual(list(range(15)), ts)
        self.assertEqual(sorted(ts), ts)
        self.assertEqual(20 - 6, ts[-1])
        self.assertEqual(0, keisan.出現tick(0, 1, 20, 6))
        ts = [keisan.出現tick(m, 7, 40, 10) for m in range(7)]
        self.assertEqual(sorted(ts), ts)
        self.assertEqual(30, ts[-1])

    def test_shimmer_and_param_encoding(self):
        c = keisan.蜂巣(3, 5, 0.5)
        self.assertEqual([11, 17, 24, 11, 18, 24, 5, 12, 18, 6, 12, 19, 0, 6, 13], keisan.輝き番号(c))
        self.assertEqual([0], keisan.輝き番号([(0.0, 0.0)]))
        self.assertEqual(249, keisan.結界R(24, 9))
        self.assertLessEqual(keisan.結界R(24, 9), keisan.R上限)
        self.assertEqual(0, keisan.結界R(-3, -1))
        self.assertEqual(249, keisan.結界R(99, 99))
        for g in range(25):
            for L in range(10):
                R = keisan.結界R(g, L)
                self.assertTrue(0 <= R <= 249)
                # как шейдер: code = floor(msParam*250 + 0.5), msParam = R/250
                code = int(math.floor(R / 250.0 * 250.0 + 0.5))
                self.assertEqual((L, g), (code // 25, code - 25 * (code // 25)))
                self.assertEqual((L, g), keisan.結界R解く(R))
        self.assertAlmostEqual(1.024, keisan.表示倍率(0.5, 0.04), places=9)

    def test_hex_inside(self):
        r = 0.5
        self.assertTrue(keisan.六角内(0.0, 0.0, r))
        self.assertTrue(keisan.六角内(0.0, r, r))
        self.assertFalse(keisan.六角内(0.0, r + 0.01, r))
        self.assertTrue(keisan.六角内(math.sqrt(3.0) / 2.0 * r, 0.0, r))
        self.assertFalse(keisan.六角内(math.sqrt(3.0) / 2.0 * r + 0.01, 0.0, r))
        self.assertFalse(keisan.六角内(0.0, -r - 0.01, r))

    def test_honeycomb_no_overlap_no_holes(self):
        import random
        c = keisan.蜂巣(3, 5, 0.5)
        rnd = random.Random(7)
        内 = 0.5 * (1.0 - 1e-6)
        for _ in range(10000):
            u = rnd.uniform(-1.08, 1.08)
            v = rnd.uniform(-1.5, 1.5)
            n = sum(1 for cu, cv in c if keisan.六角内(u - cu, v - cv, 内))
            self.assertLessEqual(n, 1, (u, v))
            self.assertTrue(any(keisan.六角内(u - cu, v - cv, 0.5) for cu, cv in c), (u, v))
        # точно между рядами (линии стыка) — тоже покрыто
        for u in (-1.0, -0.433013, 0.0, 0.2165, 0.9):
            for v in (-1.125, -0.375, 0.375, 1.125):
                self.assertTrue(any(keisan.六角内(u - cu, v - cv, 0.5) for cu, cv in c), (u, v))

    def test_axes_and_anchor(self):
        右, n = keisan.結界の軸(0.0)
        self.assertTrue(近い((-1.0, 0.0, 0.0), 右))
        self.assertTrue(近い((0.0, 0.0, 1.0), n))
        右, n = keisan.結界の軸(90.0)
        self.assertTrue(近い((0.0, 0.0, -1.0), 右))
        self.assertTrue(近い((-1.0, 0.0, 0.0), n))
        # 右 — правая рука: взгляд × вверх
        for yaw in (0.0, 37.0, 90.0, 200.0, -45.0):
            右, n = keisan.結界の軸(yaw)
            up = (0.0, 1.0, 0.0)
            cross = (n[1] * up[2] - n[2] * up[1], n[2] * up[0] - n[0] * up[2], n[0] * up[1] - n[1] * up[0])
            self.assertTrue(近い(cross, 右), yaw)
            self.assertTrue(近い(keisan.視線(yaw, 0.0), n), yaw)
        self.assertTrue(近い((0.5, 65.87, 2.5), keisan.結界の中心((0.5, 65.62, 0.5), 0.0, 2.0, 0.25)))
        self.assertTrue(近い((-1.5, 65.87, 0.5), keisan.結界の中心((0.5, 65.62, 0.5), 90.0, 2.0, 0.25)))

    def test_segment_vs_barrier(self):
        rec = _記録()                                    # центр (0.5, 65.87, 2.5), плоскость z = 2.5
        c = rec.中心
        # перпендикулярно через центр, с 10 блоков спереди (стрелок смотрит на заклинателя)
        E = (c[0], c[1], c[2] + 10.0)
        h = keisan.線分と結界(E, (0.0, 0.0, -1.0), 29.0, rec)
        self.assertIsNotNone(h)
        self.assertAlmostEqual(10.0, h[0], places=9)
        self.assertEqual(7, h[1])
        # сзади тоже
        h = keisan.線分と結界((c[0], c[1], c[2] - 10.0), (0.0, 0.0, 1.0), 29.0, rec)
        self.assertAlmostEqual(10.0, h[0], places=9)
        self.assertEqual(7, h[1])
        # параллельно плоскости
        self.assertIsNone(keisan.線分と結界((c[0] - 10.0, c[1], c[2]), (1.0, 0.0, 0.0), 29.0, rec))
        # барьер дальше D
        self.assertIsNone(keisan.線分と結界(E, (0.0, 0.0, -1.0), 9.0, rec))
        # мимо прямоугольника
        self.assertIsNone(keisan.線分と結界((c[0], c[1] + 2.1, c[2] + 5.0), (0.0, 0.0, -1.0), 29.0, rec))
        # зазубрина края: u = −1.45, v = 1.9 (локально) — внутри прямоугольника, но ни в одной ячейке
        右 = rec.右
        P = (c[0] + 右[0] * -1.45, c[1] + 1.9, c[2] + 右[2] * -1.45)
        self.assertLessEqual(1.45, rec.半幅)
        self.assertIsNone(keisan.線分と結界((P[0], P[1], P[2] + 5.0), (0.0, 0.0, -1.0), 29.0, rec))
        # та же точка по u, но v = 1.5 — ячейка 12 (левая верхняя)
        P = (c[0] + 右[0] * -1.0825, c[1] + 1.5, c[2])
        self.assertEqual(12, keisan.線分と結界((P[0], P[1], P[2] + 5.0), (0.0, 0.0, -1.0), 29.0, rec)[1])
        # наклонно, 45° в горизонтали: попадаем в центр
        s = math.sqrt(0.5)
        E2 = (c[0] - 6.0, c[1], c[2] + 6.0)
        h = keisan.線分と結界(E2, (s, 0.0, -s), 29.0, rec)
        self.assertAlmostEqual(6.0 * math.sqrt(2.0), h[0], places=9)
        self.assertEqual(7, h[1])
        # и сверху вниз под углом (pitch)
        d = keisan.視線(180.0, 30.0)                      # на −Z и вниз
        E3 = (c[0], c[1] + 5.0 * math.tan(math.radians(30.0)), c[2] + 5.0)
        h = keisan.線分と結界(E3, d, 29.0, rec)
        self.assertAlmostEqual(5.0 / math.cos(math.radians(30.0)), h[0], places=9)
        self.assertEqual(7, h[1])

    def test_block_world_expiry_nearest(self):
        rec = _記録(今=1000)                              # живёт до 11000
        c = rec.中心
        E = (c[0], c[1], c[2] + 10.0)
        d = (0.0, 0.0, -1.0)
        self.assertEqual((10.0, 1, 7), tuple(round(x, 9) for x in keisan.遮断(E, d, 29.0, [rec], "world", 5000)))
        self.assertIsNone(keisan.遮断(E, d, 29.0, [rec], "world_nether", 5000))
        self.assertIsNone(keisan.遮断(E, d, 29.0, [rec], "world", 11000))
        self.assertIsNone(keisan.遮断(E, d, 29.0, [], "world", 5000))
        # два барьера: ближний побеждает (порядок в реестре не важен)
        近 = _記録(id=2, eye=(0.5, 65.62, 4.5), 今=1000)    # плоскость z = 6.5
        for recs in ([rec, 近], [近, rec]):
            h = keisan.遮断(E, d, 29.0, recs, "world", 5000)
            self.assertEqual(2, h[1])
            self.assertAlmostEqual(6.0, h[0], places=9)

    def test_truncation_and_side(self):
        rec = _記録()                                    # плоскость z = 2.5, заклинатель на z = 0.5
        c = rec.中心
        E = (c[0], 65.62, 12.5)                           # стрелок в 10 блоках перед барьером, бьёт на −Z
        d = (0.0, 0.0, -1.0)
        def 箱(x, z, w=0.6, h=1.8, y=64.0):
            return (x - w / 2, y, z - w / 2, x + w / 2, y + h, z + w / 2)
        候補 = [("перед", 箱(c[0], 6.0)), ("заклинатель", 箱(c[0], 0.5)), ("стрелок", 箱(c[0], 12.5)),
              ("вплотную", 箱(c[0], 2.3))]
        h = keisan.遮断(E, d, 29.0, [rec], "world", 0)
        D = h[0]
        self.assertAlmostEqual(10.0, D, places=9)
        r = keisan.当たり(E, d, D, 候補, "стрелок", 0.6)
        ids = [i for _, i in r]
        self.assertIn("перед", ids)
        self.assertNotIn("заклинатель", ids)              # за барьером — без урона
        # центр 0.2 за плоскостью: расширенный хитбокс дотягивается до D, но 手前か его отсекает
        self.assertIn("вплотную", ids)
        中 = lambda b: ((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2)
        表 = dict(候補)
        ids2 = [i for _, i in r if keisan.手前か(rec, 中(表[i]), E)]
        self.assertEqual(["перед"], ids2)
        # свой барьер гасит и свой луч: заклинатель стреляет вперёд
        E0 = (0.5, 65.62, 0.5)
        h = keisan.遮断(E0, keisan.視線(0.0, 0.0), 29.0, [rec], "world", 0)
        self.assertIsNotNone(h)
        self.assertAlmostEqual(2.0, h[0], places=9)
        self.assertTrue(keisan.手前か(rec, E0, E0))
        self.assertFalse(keisan.手前か(rec, (0.5, 65.0, 3.0), E0))

    def test_settings_barrier_and_mode(self):
        s = settei.既定
        b = s.結界
        self.assertEqual("azerus:moon_staff_barrier", b.モデル)
        self.assertEqual((20, 10.0, 15.0, 0.5, 2.0, 0.25, 0.5, 3, 5, 0.04, 6, 0.15, 0.15, 3, 12, 6, 4, 1.5),
                         tuple(b[1:]))
        self.assertEqual(settei.様式("beam", 300, True, True), s.様式)
        for k in ("barrier-start", "barrier-cell", "barrier-formed", "barrier-hit", "barrier-end", "barrier-fizzle",
                  "mode-beam", "mode-barrier"):
            self.assertTrue(s.音表[k], k)
        self.assertEqual(settei.音("block.beacon.power_select", settei.f32(0.8), settei.f32(1.6)),
                         s.音表["barrier-formed"][0])
        self.assertEqual(settei.粒("ELECTRIC_SPARK", 3, 0.15, 0.02, 0.0), s.形成粒)
        self.assertEqual(settei.粒("END_ROD", 16, 0.12, 0.07, 0.0), s.打粒)
        self.assertEqual(settei.粒("ELECTRIC_SPARK", 2, 0.2, 0.03, 0.0), s.終結粒)
        for k in ("mode-beam", "mode-barrier", "barrier-charge", "barrier-ready", "barrier-cooldown",
                  "barrier-too-early", "barrier-no-room"):
            self.assertTrue(s.文(k), k)
        self.assertIn("<bar>", s.文("barrier-charge"))
        self.assertIn("<s>", s.文("barrier-ready"))
        self.assertIn("<need>", s.文("barrier-too-early"))
        self.assertIn("<barriers>", s.文("info"))

    def test_settings_barrier_clamps(self):
        warns = []
        s = settei.解析({"barrier": {"charge-ticks": 10, "fly-ticks": 30, "cell-radius": 9, "item-model": "x y"},
                        "mode": {"default": "x", "toggle-debounce-ms": 5}}, warns.append)
        self.assertEqual(10, s.結界.充填)
        self.assertEqual(9, s.結界.飛)
        self.assertEqual(1.5, s.結界.半径)
        self.assertEqual("azerus:moon_staff_barrier", s.結界.モデル)
        self.assertEqual("beam", s.様式.既定)
        self.assertEqual(50, s.様式.間隔)
        for 道 in ("barrier.fly-ticks", "barrier.cell-radius", "barrier.item-model", "mode.default",
                  "mode.toggle-debounce-ms"):
            self.assertTrue(any(w.startswith(道) for w in warns), 道 + " не в " + repr(warns))
        self.assertEqual("barrier", settei.解析({"mode": {"default": "Barrier"}}).様式.既定)


if __name__ == "__main__":
    unittest.main(verbosity=2)
