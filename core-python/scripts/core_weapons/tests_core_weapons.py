# 試験 — тесты чистой логики core_weapons (перенос Java SettingsTest, FramesTest, CmdStringsTest, GeometryTest).
# Запуск обычным python3 из любой папки:  python3 tests_core_weapons.py   (amazake не нужен; для разбора config.yml
# нужен PyYAML — без него этот тест пропускается).

import json
import math
import os
import sys
import unittest

ここ = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ここ)

import ronri    # noqa: E402
import settei   # noqa: E402


def _設定(yaml文字):
    import yaml
    # amazake.config: YAML (SnakeYAML) → JSON (Gson) → dict — повторяем путь через json
    return json.loads(json.dumps(yaml.safe_load(yaml文字)))


class 設定の試験(unittest.TestCase):

    def test_bundled_config_parses_without_warnings(self):
        try:
            import yaml  # noqa: F401
        except ImportError:
            self.skipTest("нет PyYAML")
        with open(os.path.join(ここ, "config.yml"), encoding="utf-8") as f:
            c = _設定(f.read())
        warns = []
        s = settei.解析(c, warns.append)
        self.assertEqual([], warns)
        w = s.武器("azerus_crimson_scythe")
        self.assertIsNotNone(w)
        self.assertEqual("azerus:crimson_scythe", w.試験品.モデル)
        self.assertIsNone(w.軌道)   # у косы кольцо — в модели, частицы выключены
        self.assertEqual(("cw_swing_1", "cw_swing_2", "cw_swing_3"), w.振り.コマ.名前)
        self.assertEqual(6.0, w.地震.半径)
        self.assertEqual(0.0, w.地震.傷)
        self.assertEqual(3, len(w.地震.音))
        self.assertEqual("entity.warden.step", w.地震.音[0].鍵)
        # остальное — как в config.yml
        self.assertEqual(6, w.振り.最小間隔)
        self.assertEqual((1, 2, 3), w.振り.コマ.時間)
        self.assertEqual(0xFF2A1F, w.振り.色1)
        self.assertEqual(settei.音("entity.player.attack.sweep", settei.f32(0.7), settei.f32(0.6)), w.振り.音)
        self.assertEqual(12, w.地震.長さ)
        self.assertEqual(30, w.地震.待ち)
        self.assertEqual(90, w.地震.最大表示)
        self.assertEqual(0.12, w.地震.跳ね)
        self.assertEqual("NETHERITE_SWORD", w.試験品.素材)
        self.assertIn("<player>", s.文言["no-player"])
        self.assertTrue(s.接頭.startswith("<dark_gray>"))

    def test_disabled_behaviour_is_none(self):
        c = {"weapons": {"x": {"orbit": {"enabled": False}, "crit-tremor": {"radius": 99}}}}
        w = settei.解析(c).武器("x")
        self.assertIsNone(w.軌道)
        self.assertIsNone(w.振り)
        self.assertEqual(16.0, w.地震.半径)

    def test_sound_spec(self):
        self.assertEqual(settei.音("minecraft:block.stone.break", 0.5, 2.0),
                         settei.音.解析("minecraft:block.stone.break:0.5:2"))
        self.assertEqual(settei.音("entity.x", 1.0, 1.0), settei.音.解析("entity.x"))
        self.assertIsNone(settei.音.解析(" "))
        # только громкость; ключ в нижний регистр
        self.assertEqual(settei.音("entity.y", 0.25, 1.0), settei.音.解析("Entity.Y:0.25"))

    def test_bad_colors_and_types_warn_and_default(self):
        warns = []
        c = {"weapons": {"x": {"orbit": {"colors": ["#zz", "00ff00"], "points": 99, "period": "3"},
                               "swing": {"frames": ["cw_swing_1:x"]}}, "y": 5}}
        s = settei.解析(c, warns.append)
        o = s.武器("x").軌道
        self.assertEqual(0xFF3B2F, o.色1)
        self.assertEqual(0x00FF00, o.色2)
        self.assertEqual(12, o.点数)   # clamp 1..12
        self.assertEqual(2, o.周期)    # строка — не число (getInt → по умолчанию)
        self.assertEqual({"weapons.y: не секция",
                          "weapons.x.цвет «#zz» не понят, взят ff3b2f",
                          "weapons.x.swing.frames: «cw_swing_1:x»: тики не число, взято 2"}, set(warns))
        self.assertEqual(3, len(warns))


class コマの試験(unittest.TestCase):

    def test_parses_and_steps(self):
        warns = []
        f = ronri.コマ.解析(["cw_swing_1:1", "cw_swing_2:2", "cw_swing_3", "bad:3"], warns.append)
        self.assertEqual(("cw_swing_1", "cw_swing_2", "cw_swing_3"), f.名前)
        self.assertEqual(1, len(warns))
        self.assertEqual(5, f.合計())
        self.assertEqual("cw_swing_1", f.時点(0))
        self.assertEqual("cw_swing_2", f.時点(1))
        self.assertEqual("cw_swing_2", f.時点(2))
        self.assertEqual("cw_swing_3", f.時点(3))
        self.assertEqual("cw_swing_3", f.時点(4))
        self.assertIsNone(f.時点(5))
        self.assertIsNone(f.時点(-1))

    def test_bad_ticks_fall_back_and_clamp(self):
        f = ronri.コマ.解析(["cw_swing_a:x", "cw_swing_b:99", "cw_swing_c:0"], lambda m: None)
        self.assertEqual((2, 20, 1), f.時間)


class CMD文字列の試験(unittest.TestCase):

    def test_frame_goes_first_and_old_frames_leave(self):
        self.assertEqual(["cw_swing_2", "nexo"], ronri.枠を付ける(["cw_swing_1", "nexo"], "cw_swing_2"))
        self.assertEqual(["cw_swing_1"], ronri.枠を付ける(None, "cw_swing_1"))

    def test_strip_keeps_foreign_strings(self):
        self.assertEqual(["a", "b"], ronri.枠を外す(["cw_swing_3", "a", "cw_swing_1", "b"]))
        self.assertTrue(ronri.枠あり(["x", "cw_swing_9"]))
        self.assertFalse(ronri.枠あり(["x"]))
        self.assertFalse(ronri.枠あり(None))


class 幾何の試験(unittest.TestCase):

    def test_basis_follows_minecraft_yaw(self):
        f = ronri.前(0)   # юг
        self.assertAlmostEqual(0, f[0], delta=1e-9)
        self.assertAlmostEqual(1, f[2], delta=1e-9)
        r = ronri.右(0)   # смотрит на юг — справа запад (−x)
        self.assertAlmostEqual(-1, r[0], delta=1e-9)
        w = ronri.前(90)  # запад
        self.assertAlmostEqual(-1, w[0], delta=1e-9)

    def test_orbit_points_keep_radius_in_plane(self):
        pts = ronri.輪(5, 3, 0.75, 14, 0, 0, 37)
        self.assertEqual(3, len(pts))
        for p in pts:
            self.assertAlmostEqual(0.75, math.hypot(p[0], p[2]), delta=1e-9)
            self.assertAlmostEqual(0, p[1], delta=1e-9)

    def test_arc_goes_from_upper_right_to_lower_left(self):
        look = (0, 0, 1)
        a = ronri.弧(0, 1.3, 1.2, look, 0)
        b = ronri.弧(1, 1.3, 1.2, look, 0)
        # справа при yaw 0 — это −x
        self.assertTrue(a[0] < 0 and a[1] > 0, "начало справа сверху")
        self.assertTrue(b[0] > 0 and b[1] < 0, "конец слева снизу")
        self.assertAlmostEqual(1.2, a[2], delta=1e-9)

    def test_bands_cover_disc_once(self):
        total = 0
        prev = 0
        for s in range(12):
            r = ronri.波半径(s, 12, 6)
            self.assertTrue(r >= prev)
            total += len(ronri.帯(0 if s == 0 else prev, r))
            prev = r
        self.assertAlmostEqual(6.0, prev, delta=1e-9)
        self.assertEqual(len(ronri.帯(0, 6)), total)

    def test_precomputed_wave_matches_steps(self):
        表 = ronri.波の表(12, 6.0)
        self.assertEqual(12, len(表))
        self.assertEqual(len(ronri.帯(0, 6)), sum(len(c) for _, _, c in 表))
        self.assertEqual(ronri.帯(表[3][0], 表[3][1]), list(表[3][2]))
        self.assertIs(表, ronri.波の表(12, 6.0))   # кэш

    def test_shake_up_and_down(self):
        self.assertAlmostEqual(0, ronri.揺れ高さ(0, 0.2), delta=1e-9)
        self.assertAlmostEqual(0.2, ronri.揺れ高さ(2, 0.2), delta=1e-9)
        self.assertTrue(ronri.揺れ高さ(3, 0.2) < 0.2)
        self.assertAlmostEqual(0, ronri.揺れ高さ(5, 0.2), delta=1e-9)


class 棒の試験(unittest.TestCase):
    """Палка: разбор секции stick, «со спины», цель удара, путь луча, коды шейдера."""

    def test_bundled_stick_config(self):
        try:
            import yaml  # noqa: F401
        except ImportError:
            self.skipTest("нет PyYAML")
        with open(os.path.join(ここ, "config.yml"), encoding="utf-8") as f:
            c = _設定(f.read())
        warns = []
        b = settei.解析(c, warns.append).棒
        self.assertEqual([], warns)
        self.assertEqual("STICK", b.素材)
        self.assertEqual(frozenset(), b.nexo_ids)
        self.assertEqual((6.0, 3.5, 6.0, 2.0, 60.0), (b.背刺.冷却, b.背刺.届き, b.背刺.傷, b.背刺.倍率, b.背刺.背角))
        self.assertEqual((20, 10.0, 16.0, 8.0, 3.0, False),
                         (b.余燼.詠唱, b.余燼.冷却, b.余燼.射程, b.余燼.傷, b.余燼.火秒, b.余燼.貫通))
        self.assertTrue(b.背刺.音 and b.余燼.撃音)

    def test_stick_disabled_and_ids(self):
        s = settei.解析({"stick": {"enabled": False}})
        self.assertIsNone(s.棒)
        s = settei.解析({"stick": {"nexo-ids": ["azerus_stick", " "], "backstab": {"enabled": False}}})
        self.assertEqual(frozenset({"azerus_stick"}), s.棒.nexo_ids)
        self.assertIsNone(s.棒.背刺)
        self.assertIsNotNone(s.棒.余燼)
        self.assertIsNone(settei.解析({}).棒)

    def test_behind(self):
        # цель смотрит на юг (+z, yaw 0); атакующий севернее (−z) — за спиной
        self.assertTrue(ronri.背後か(0, (0, 0, -2), (0, 0, 0), 60))
        self.assertFalse(ronri.背後か(0, (0, 0, 2), (0, 0, 0), 60))          # спереди
        self.assertFalse(ronri.背後か(0, (2, 0, 0), (0, 0, 0), 60))          # сбоку (90°)
        self.assertTrue(ronri.背後か(0, (-1, 0, -2), (0, 0, 0), 60))         # 26.6° — сзади-сбоку
        self.assertFalse(ronri.背後か(0, (-2, 0, -1), (0, 0, 0), 60))        # 63.4°
        self.assertTrue(ronri.背後か(90, (2, 0, 0), (0, 0, 0), 60))          # смотрит на запад, мы с востока
        self.assertFalse(ronri.背後か(0, (0, 5, 0), (0, 0, 0), 60))          # ровно сверху — не «сзади»

    def test_melee_target_first_and_cone(self):
        E = (0.0, 1.6, 0.0)
        v = ronri.視線(0, 0)                                                 # на юг
        zb = lambda x, z: (x - 0.3, 0.0, z - 0.3, x + 0.3, 1.95, z + 0.3)
        hit = ronri.近接の的(E, v, [("far", zb(0, 3.0)), ("near", zb(0, 1.5))], 3.5, 20, 0.2)
        self.assertEqual("near", hit[1])
        self.assertAlmostEqual(1.0, hit[0], delta=1e-9)
        self.assertIsNone(ronri.近接の的(E, v, [("behind", zb(0, -2))], 3.5, 20, 0.2))
        self.assertIsNone(ronri.近接の的(E, v, [("far", zb(0, 4.5))], 3.5, 20, 0.2))
        # мимо луча, но в конусе 20°: центр (0.6, 0.975, 2.6) — угол 18.4° → да; (1.6, …) → нет
        self.assertIsNotNone(ronri.近接の的(E, v, [("c", zb(0.6, 2.6))], 3.5, 20, 0.0))
        self.assertIsNone(ronri.近接の的(E, v, [("c", zb(1.6, 2.6))], 3.5, 20, 0.0))

    def test_owned_length_cuts_at_foreign_chunk(self):
        v = (0.0, 0.0, 1.0)
        n = ronri.使える長さ((8.0, 64.0, 8.0), v, 16.0, 4.0, 1.3, lambda cx, cz: cz <= 0)
        self.assertTrue(4.0 <= n < 8.0 + 1e-9)
        self.assertAlmostEqual(16.0, ronri.使える長さ((8.0, 64.0, 8.0), v, 16.0, 4.0, 1.3, lambda cx, cz: True))

    def test_shader_codes(self):
        for tick in (0, 124, 125, 24000 + 7, 123456):
            for crit in (False, True):
                R = ronri.斬撃R(tick, crit)
                self.assertTrue(0 <= R <= 249)
                self.assertEqual((tick % 125, crit), ronri.斬撃R解読(R))
        self.assertEqual(ronri.斬撃R(5, False), ronri.斬撃R(5 + 24000, False))   # шов дня
        self.assertEqual((0, 250, 125), (ronri.充填R(0), ronri.充填R(1.0), ronri.充填R(0.5)))
        self.assertEqual((1, 128, 250), (ronri.光線R(0.01), ronri.光線R(16), ronri.光線R(99)))
        self.assertEqual(ronri.印G * 256 + ronri.印B, (233 << 8) | 253)


if __name__ == "__main__":
    unittest.main(verbosity=2)
