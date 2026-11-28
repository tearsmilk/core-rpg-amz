# Звуки плазменной винтовки ПВ-7 — синтез (numpy), без чужих записей. Пишет .ogg (Vorbis, через ffmpeg) в
# guns/assets-hoka/sounds/:
#   plasma_rifle_shoot.ogg     — выстрел у стрелка (стерео, 44,1 кГц): «зип» с падающим тоном, щелчок разряда, гул;
#   plasma_rifle_shoot_3p.ogg  — выстрел для остальных (моно, из точки), с хвостом;
#   plasma_rifle_draw.ogg      — доставание: нарастающий гул питания и щелчок;
#   plasma_rifle_put_away.ogg  — убирание: гул вниз;
#   plasma_rifle_cell_out.ogg  — ячейка вынута: защёлка, шипение сброса, падающий писк;
#   plasma_rifle_cell_in.ogg   — ячейка вставлена: удар, защёлка, короткий подъём тона;
#   plasma_rifle_charge.ogg    — рукоять заряжания: скольжение, щелчок и нарастающий заряд.
# Случайность — с постоянным зерном: перегенерация даёт те же звуки. Запуск: python3 tools/plasma/gen_plasma_sounds.py
import os, subprocess, sys, tempfile, wave

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, '..', '..', 'guns', 'assets-hoka', 'sounds'))
SR = 44100
rng = np.random.default_rng(1977)


def t_(sec, sr=SR):
    return np.arange(int(sec * sr)) / sr


def env(t, attack, decay):
    a = np.clip(t / max(attack, 1e-4), 0, 1)
    return a * np.exp(-np.maximum(t - attack, 0) / decay)


def chirp(t, f0, f1, k=4.0):
    """Тон, частота экспоненциально от f0 к f1 (скорость k): фаза — интеграл частоты."""
    f = f1 + (f0 - f1) * np.exp(-k * t)
    return np.sin(2 * np.pi * np.cumsum(f) / SR), f


def lowpass(x, cutoff, sr=SR):
    a = np.exp(-2 * np.pi * cutoff / sr)
    y = np.empty_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc = (1 - a) * v + a * acc
        y[i] = acc
    return y


def highpass(x, cutoff, sr=SR):
    return x - lowpass(x, cutoff, sr)


def noise(n):
    return rng.standard_normal(n)


def click(t, at, freq=3200, decay=0.004):
    tt = t - at
    m = tt >= 0
    out = np.zeros_like(t)
    out[m] = np.sin(2 * np.pi * freq * tt[m]) * np.exp(-tt[m] / decay)
    out[m] += 0.6 * noise(m.sum()) * np.exp(-tt[m] / (decay * 0.7))
    return out


def reverb(x, sr=SR, mix=0.25, times=(0.023, 0.037, 0.051, 0.067, 0.083), fb=0.45):
    y = x.copy()
    for k, d in enumerate(times):
        n = int(d * sr)
        tail = np.zeros(len(x) + n * 4)
        tail[:len(x)] += x
        for r in range(1, 5):
            tail[n * r:n * r + len(x)] += x * (fb ** r) * (0.8 if k % 2 else 1.0)
        y = np.pad(y, (0, max(0, len(tail) - len(y))))
        y[:len(tail)] += mix / len(times) * tail
    return y


def norm(x, peak=0.89):
    m = np.max(np.abs(x))
    return x * (peak / m) if m > 0 else x


def fade(x, ms=8):
    n = int(SR * ms / 1000)
    x = x.copy()
    x[-n:] *= np.linspace(1, 0, n)
    return x


# ------------------------------------------------------------------ звуки
def shot(sec=0.5, var=0.0):
    t = t_(sec)
    zap, f = chirp(t, 2600 + var, 210, k=22)
    fm = np.sin(2 * np.pi * np.cumsum(f * 0.5 + 40 * np.sin(2 * np.pi * 55 * t)) / SR)
    body = (0.55 * zap + 0.35 * fm) * env(t, 0.002, 0.09)
    crack = highpass(noise(len(t)), 2500) * env(t, 0.0005, 0.012) * 0.9
    hiss = lowpass(noise(len(t)), 5000) * env(t, 0.004, 0.06) * 0.35
    thump = np.sin(2 * np.pi * (95 * t - 60 * t * t)) * env(t, 0.003, 0.07) * 0.8
    sub = np.sin(2 * np.pi * 52 * t) * env(t, 0.01, 0.12) * 0.35
    ring = np.sin(2 * np.pi * 1320 * t) * env(t, 0.01, 0.18) * 0.06     # «звон» катушки после выстрела
    return body + crack + hiss + thump + sub + ring


def shoot_1p():
    l = shot(0.55, 0)
    r = shot(0.55, 140)
    l = l + 0.15 * np.roll(r, int(0.004 * SR))
    r = r + 0.15 * np.roll(l, int(0.006 * SR))
    st = np.stack([norm(fade(l)), norm(fade(r))], 1)
    return st


def shoot_3p():
    x = shot(0.6, 60)
    x = lowpass(x, 7000)
    x = reverb(x, mix=0.5)
    return norm(fade(x[:int(0.9 * SR)]), 0.85)


def hum(sec, f0, f1, up=True):
    t = t_(sec)
    f = f0 + (f1 - f0) * (t / sec) ** (0.6 if up else 1.6)
    ph = 2 * np.pi * np.cumsum(f) / SR
    tone = np.sin(ph) + 0.35 * np.sin(2 * ph) + 0.15 * np.sign(np.sin(0.5 * ph))
    am = 1 + 0.25 * np.sin(2 * np.pi * 31 * t)
    e = np.clip(t / 0.03, 0, 1) * np.clip((sec - t) / 0.08, 0, 1)
    return lowpass(tone * am * e, 4000) * 0.5


def draw():
    t = t_(0.6)
    x = hum(0.6, 120, 820, True) + 0.6 * click(t, 0.48, 2600, 0.006) + 0.4 * click(t, 0.02, 1500, 0.01)
    return norm(fade(x), 0.75)


def put_away():
    t = t_(0.45)
    x = hum(0.45, 700, 90, False) + 0.4 * click(t, 0.01, 1800, 0.008)
    return norm(fade(x), 0.65)


def cell_out():
    t = t_(0.55)
    latch = click(t, 0.0, 2100, 0.006) + 0.7 * click(t, 0.035, 1200, 0.01)
    vent = highpass(noise(len(t)), 1800) * env(t - 0.03, 0.01, 0.12) * (t > 0.03) * 0.5
    whine, _ = chirp(t, 1700, 260, k=6)
    whine *= env(t, 0.02, 0.18) * 0.3
    return norm(fade(latch + vent + whine), 0.8)


def cell_in():
    t = t_(0.5)
    hit = lowpass(noise(len(t)), 900) * env(t, 0.001, 0.025) * 1.2
    hit += np.sin(2 * np.pi * 140 * t) * env(t, 0.001, 0.04) * 0.8
    latch = 0.8 * click(t, 0.05, 2800, 0.005)
    rise, _ = chirp(t, 300, 1500, k=7)
    rise *= env(t - 0.08, 0.04, 0.15) * (t > 0.08) * 0.35
    return norm(fade(hit + latch + rise), 0.85)


def charge():
    t = t_(0.8)
    slide = lowpass(highpass(noise(len(t)), 900), 4000) * np.clip(1 - np.abs(t - 0.06) / 0.06, 0, 1) * 0.6
    snap = click(t, 0.12, 2400, 0.006) + 0.8 * click(t, 0.16, 1300, 0.012)
    tt = np.maximum(t - 0.17, 0)
    f = 180 + 1700 * (1 - np.exp(-tt * 5))
    up = np.sin(2 * np.pi * np.cumsum(f) / SR) * (t > 0.17) * np.clip(tt / 0.05, 0, 1) * np.exp(-tt / 0.35) * 0.4
    up += 0.12 * np.sin(4 * np.pi * np.cumsum(f) / SR) * (t > 0.17) * np.exp(-tt / 0.3)
    return norm(fade(slide + snap + up), 0.8)


def write_ogg(name, data, sr=SR, q=5):
    os.makedirs(OUT, exist_ok=True)
    data = np.asarray(data, np.float64)
    ch = 1 if data.ndim == 1 else data.shape[1]
    pcm = (np.clip(data, -1, 1) * 32767).astype('<i2')
    with tempfile.TemporaryDirectory() as td:
        wav = os.path.join(td, name + '.wav')
        with wave.open(wav, 'wb') as w:
            w.setnchannels(ch)
            w.setsampwidth(2)
            w.setframerate(sr)
            w.writeframes(pcm.tobytes())
        out = os.path.join(OUT, name + '.ogg')
        subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', wav, '-c:a', 'libvorbis',
                        '-q:a', str(q), '-map_metadata', '-1', '-fflags', '+bitexact', '-flags:a', '+bitexact',
                        out], check=True)
    print(name, f'{len(data) / sr:.2f} с', 'стерео' if ch == 2 else 'моно')


def main():
    write_ogg('plasma_rifle_shoot', shoot_1p())
    write_ogg('plasma_rifle_shoot_3p', shoot_3p())
    write_ogg('plasma_rifle_draw', draw())
    write_ogg('plasma_rifle_put_away', put_away())
    write_ogg('plasma_rifle_cell_out', cell_out())
    write_ogg('plasma_rifle_cell_in', cell_in())
    write_ogg('plasma_rifle_charge', charge())


if __name__ == '__main__':
    main()
