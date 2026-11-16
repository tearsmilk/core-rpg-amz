#!/usr/bin/env python3
"""Маленький программный растеризатор (numpy) для armor3d: ортографическая камера, z-буфер, треугольники с текстурой,
логика фрагментного шейдера (обрезка по альфе, свет граней как minecraft_mix_light, отбрасывание задних граней)."""
import math

import numpy as np

LIGHT0 = np.array([0.2, 1.0, -0.7]) / np.linalg.norm([0.2, 1.0, -0.7])   # Lighting.DIFFUSE_LIGHT_0 (мир)
LIGHT1 = np.array([-0.2, 1.0, 0.7]) / np.linalg.norm([-0.2, 1.0, 0.7])


def mix_light(n):
    """minecraft_mix_light_separate для нормали n (мир, y вверх) → множитель (лицо, изнанка)."""
    a = max(0.0, float(np.dot(LIGHT0, n))) + max(0.0, float(np.dot(LIGHT1, n)))
    b = max(0.0, float(np.dot(LIGHT0, -n))) + max(0.0, float(np.dot(LIGHT1, -n)))
    return min(1.0, a * 0.6 + 0.4), min(1.0, b * 0.6 + 0.4)


class Camera:
    """Мир (пиксели, y вверх) → вид: поворот вокруг Y (yaw), затем наклон (pitch); орто-проекция."""

    def __init__(self, yaw, pitch, scale, cx, cy, center=(0, 0, 0)):
        a, b = math.radians(yaw), math.radians(pitch)
        Ry = np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])
        Rx = np.array([[1, 0, 0], [0, math.cos(b), -math.sin(b)], [0, math.sin(b), math.cos(b)]])
        self.R = Rx @ Ry
        self.scale, self.cx, self.cy = scale, cx, cy
        self.center = np.asarray(center, float)

    def view(self, p):
        return self.R @ (np.asarray(p, float) - self.center)

    def screen(self, p):
        v = self.view(p)
        return np.array([self.cx + v[0] * self.scale, self.cy - v[1] * self.scale, v[2]])


class Raster:
    def __init__(self, w, h, bg=(0, 0, 0, 0)):
        self.w, self.h = w, h
        self.color = np.zeros((h, w, 4), np.float32)
        self.color[:] = bg
        self.depth = np.full((h, w), -1e9, np.float32)

    def tri(self, s, uv, tex, mode, light, emissive=False, double=True, tint=(1, 1, 1)):
        """s: 3 экранные точки (x, y, depth; depth больше — ближе); uv: нормированные; tex: (H,W,4) uint8.
        mode '3d' — альфа 0 дырка, иначе непрозрачно (путь armor3d); 'cutout' — альфа < 25.5 отбросить
        (ALPHA_CUTOUT 0.1); light: (лицо, изнанка)."""
        s = np.asarray(s, float)
        # лицо: против часовой стрелки при y вверх (экранный y вниз → знак обратный)
        area = (s[1, 0] - s[0, 0]) * (s[2, 1] - s[0, 1]) - (s[2, 0] - s[0, 0]) * (s[1, 1] - s[0, 1])
        if abs(area) < 1e-9:
            return
        front = area < 0
        if mode == "3d" and not front and not double:
            return
        x0, x1 = max(int(math.floor(s[:, 0].min())), 0), min(int(math.ceil(s[:, 0].max())), self.w - 1)
        y0, y1 = max(int(math.floor(s[:, 1].min())), 0), min(int(math.ceil(s[:, 1].max())), self.h - 1)
        if x0 > x1 or y0 > y1:
            return
        xs, ys = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        w0 = ((s[1, 0] - xs) * (s[2, 1] - ys) - (s[2, 0] - xs) * (s[1, 1] - ys)) / area
        w1 = ((s[2, 0] - xs) * (s[0, 1] - ys) - (s[0, 0] - xs) * (s[2, 1] - ys)) / area
        w2 = 1.0 - w0 - w1
        m = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
        if not m.any():
            return
        z = w0 * s[0, 2] + w1 * s[1, 2] + w2 * s[2, 2]
        sub = self.depth[y0:y1 + 1, x0:x1 + 1]
        m &= z > sub
        if not m.any():
            return
        uv = np.asarray(uv, float)
        u = w0 * uv[0, 0] + w1 * uv[1, 0] + w2 * uv[2, 0]
        v = w0 * uv[0, 1] + w1 * uv[1, 1] + w2 * uv[2, 1]
        th, tw = tex.shape[:2]
        tx = np.clip(np.floor(u * tw).astype(int), 0, tw - 1)
        ty = np.clip(np.floor(v * th).astype(int), 0, th - 1)
        c = tex[ty, tx].astype(np.float32)
        a = c[..., 3]
        if mode == "3d":
            m &= a > 0
        else:
            m &= a >= 25.5
        if not m.any():
            return
        k = 1.0 if emissive else (light[0] if front else light[1])
        rgb = c[..., :3] / 255.0 * k * np.asarray(tint, np.float32)
        col = self.color[y0:y1 + 1, x0:x1 + 1]
        col[m, :3] = rgb[m]
        col[m, 3] = 1.0
        sub[m] = z[m]

    def quad(self, s4, uv4, tex, mode, light, **kw):
        self.tri([s4[0], s4[1], s4[2]], [uv4[0], uv4[1], uv4[2]], tex, mode, light, **kw)
        self.tri([s4[2], s4[3], s4[0]], [uv4[2], uv4[3], uv4[0]], tex, mode, light, **kw)

    def image(self):
        from PIL import Image
        return Image.fromarray((np.clip(self.color, 0, 1) * 255).astype(np.uint8), "RGBA")
