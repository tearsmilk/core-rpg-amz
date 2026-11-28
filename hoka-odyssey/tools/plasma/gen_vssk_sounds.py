# Звуки плазменной ВССК «Выхлоп» — синтез (numpy), без чужих записей; общие приёмы — из gen_plasma_sounds.py. Пишет
# .ogg (Vorbis, через ffmpeg) в guns/assets-hoka/sounds/:
#   vssk_vykhlop_shoot.ogg     — выстрел у стрелка (стерео): тяжёлый плазменный разряд через встроенный глушитель —
#                                глухой «тумп», низкий падающий гул, лязг затворной группы, без треска;
#   vssk_vykhlop_shoot_3p.ogg  — то же для остальных (моно, тише, с коротким хвостом);
#   vssk_vykhlop_draw.ogg      — доставание: низкий нарастающий гул и щелчок;
#   vssk_vykhlop_put_away.ogg  — убирание: гул вниз;
#   vssk_vykhlop_cell_out.ogg  — тяжёлая ячейка вынута: защёлка, сброс газа, падающий писк;
#   vssk_vykhlop_cell_in.ogg   — вставлена: глухой удар, защёлка, подъём тона;
#   vssk_vykhlop_charge.ogg    — затворная рукоятка: скольжение, лязг и нарастающий заряд.
# Глушитель — часть винтовки, поэтому «обычного» громкого выстрела нет (silence в config.yml — те же файлы).
# Запуск: python3 tools/plasma/gen_vssk_sounds.py
import os, sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gen_plasma_sounds as ps         # noqa: E402

ps.rng = np.random.default_rng(2077)    # своё зерно: перегенерация даёт те же звуки
SR = ps.SR
t_, env, chirp, lowpass, highpass, noise, click = ps.t_, ps.env, ps.chirp, ps.lowpass, ps.highpass, ps.noise, ps.click
norm, fade, reverb = ps.norm, ps.fade, ps.reverb
N = 'vssk_vykhlop_'


def heavy(sec=0.6, var=0.0):
    t = t_(sec)
    zap, f = chirp(t, 900 + var, 70, k=14)                               # низкий падающий разряд
    body = 0.5 * zap * env(t, 0.004, 0.08)
    thump = np.sin(2 * np.pi * (62 * t - 30 * t * t)) * env(t, 0.003, 0.09) * 1.0
    puff = lowpass(noise(len(t)), 900) * env(t, 0.002, 0.045) * 0.9      # газ в глушителе
    sub = np.sin(2 * np.pi * 38 * t) * env(t, 0.01, 0.16) * 0.45
    clack = 0.35 * click(t, 0.035, 1500, 0.006) + 0.25 * click(t, 0.11, 1100, 0.008)  # затворная группа
    hum = np.sin(2 * np.pi * 220 * t) * env(t - 0.12, 0.05, 0.15) * (t > 0.12) * 0.08  # дозарядка
    return lowpass(body + thump + puff + sub + hum, 2600) + clack


def shoot_1p():
    l, r = heavy(0.6, 0), heavy(0.6, 60)
    return np.stack([norm(fade(l), 0.8), norm(fade(r), 0.8)], 1)


def shoot_3p():
    x = reverb(heavy(0.6, 30), mix=0.3)
    return norm(fade(x[:int(0.75 * SR)]), 0.6)


def hum(sec, f0, f1, up=True):
    return ps.hum(sec, f0, f1, up)


def draw():
    t = t_(0.6)
    x = hum(0.6, 70, 420, True) + 0.7 * click(t, 0.46, 1900, 0.008) + 0.4 * click(t, 0.02, 1200, 0.01)
    return norm(fade(x), 0.75)


def put_away():
    t = t_(0.45)
    x = hum(0.45, 380, 60, False) + 0.4 * click(t, 0.01, 1400, 0.01)
    return norm(fade(x), 0.65)


def cell_out():
    t = t_(0.6)
    latch = click(t, 0.0, 1600, 0.008) + 0.8 * click(t, 0.04, 900, 0.012)
    vent = highpass(noise(len(t)), 1200) * env(t - 0.03, 0.01, 0.14) * (t > 0.03) * 0.45
    whine, _ = chirp(t, 1100, 180, k=5)
    whine *= env(t, 0.02, 0.2) * 0.3
    return norm(fade(latch + vent + whine), 0.8)


def cell_in():
    t = t_(0.55)
    hit = lowpass(noise(len(t)), 700) * env(t, 0.001, 0.03) * 1.3
    hit += np.sin(2 * np.pi * 105 * t) * env(t, 0.001, 0.05) * 0.9
    latch = 0.8 * click(t, 0.06, 2200, 0.006)
    rise, _ = chirp(t, 200, 1000, k=6)
    rise *= env(t - 0.09, 0.04, 0.17) * (t > 0.09) * 0.35
    return norm(fade(hit + latch + rise), 0.85)


def charge():
    t = t_(0.85)
    slide = lowpass(highpass(noise(len(t)), 700), 3200) * np.clip(1 - np.abs(t - 0.07) / 0.07, 0, 1) * 0.6
    snap = click(t, 0.13, 1800, 0.008) + 0.9 * click(t, 0.17, 950, 0.016)
    tt = np.maximum(t - 0.18, 0)
    f = 120 + 1100 * (1 - np.exp(-tt * 5))
    up = np.sin(2 * np.pi * np.cumsum(f) / SR) * (t > 0.18) * np.clip(tt / 0.05, 0, 1) * np.exp(-tt / 0.38) * 0.4
    return norm(fade(slide + snap + up), 0.8)


def main():
    ps.write_ogg(N + 'shoot', shoot_1p())
    ps.write_ogg(N + 'shoot_3p', shoot_3p())
    ps.write_ogg(N + 'draw', draw())
    ps.write_ogg(N + 'put_away', put_away())
    ps.write_ogg(N + 'cell_out', cell_out())
    ps.write_ogg(N + 'cell_in', cell_in())
    ps.write_ogg(N + 'charge', charge())


if __name__ == '__main__':
    main()
