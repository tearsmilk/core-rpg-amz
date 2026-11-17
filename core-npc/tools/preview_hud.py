#!/usr/bin/env python3
"""Превью окна диалога на экране GUI 480×270 (1080p, масштаб 4): та же раскладка, что у клиента —
верх глифа = H − 65 − ascent (actionbar на H − 68), строки центрируются по ширине окна.

    python3 tools/preview_hud.py   → tools/preview/hud.png (×3)
"""
import json
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
A = os.path.join(HERE, '..', 'pack', 'assets', 'corenpc')
W, H = 480, 270


def font(name):
    return json.load(open(os.path.join(A, 'font', name + '.json'), encoding='utf-8'))


def main():
    def tex(file):
        return Image.open(os.path.join(A, 'textures', file.split(':', 1)[1])).convert('RGBA')

    def joined(parts):   # части окна/подсветки (каждая ≤ 256) встык
        out = Image.new('RGBA', (sum(p.width for p in parts), parts[0].height))
        x = 0
        for p in parts:
            out.paste(p, (x, 0))
            x += p.width
        return out

    dlg = {''.join(p['chars']): p for p in font('dialog')['providers'] if p['type'] == 'bitmap'}

    def put(ch, x):
        p = dlg[ch]
        img.alpha_composite(tex(p['file']), (x0 + x, H - 65 - p['ascent']))

    cells = {}
    row0 = font('row0')['providers']
    for prov, h, up in ((row0[2], 12, 3), (row0[1], 8, 0)):
        src = tex(prov['file'])
        for r, line in enumerate(prov['chars']):
            for c, ch in enumerate(line):
                cells[ch] = (src, c * 8, r * prov['height'], h, up)
    widths = {}
    for line in open(os.path.join(HERE, '..', 'src', 'main', 'resources', 'font-widths.txt'), encoding='utf-8'):
        if not line.startswith('#'):
            a_, b_ = line.split()
            widths[chr(int(a_, 16))] = int(b_)
    asc = [font('row%d' % i)['providers'][1]['ascent'] for i in range(7)]
    img = Image.new('RGBA', (W, H), (110, 150, 200, 255))
    d = ImageDraw.Draw(img)
    d.rectangle((W // 2 - 91, H - 22, W // 2 + 91, H - 1), fill=(40, 40, 40, 255))          # хотбар
    d.rectangle((W // 2 - 91, H - 39, W // 2 - 10, H - 31), fill=(200, 30, 30, 255))      # сердца
    d.rectangle((W // 2 - 91, H - 49, W // 2 - 10, H - 41), fill=(160, 160, 170, 255))    # броня
    d.rectangle((W // 2 + 10, H - 39, W // 2 + 91, H - 31), fill=(150, 90, 40, 255))      # еда
    x0 = W // 2 - 330 // 2 + 60   # dialog.hud.offset-x
    put('\ue000', 0)
    put('\ue001', 142)
    put('\ue002', 172)

    def text(s, row, color, x):
        px = x0 + x
        for ch in s:
            if ch == ' ':
                px += 4
                continue
            if ch in cells:
                src, cx, cy, h, up = cells[ch]
                g = src.crop((cx, cy, cx + 8, cy + h))
                tint = Image.new('RGBA', g.size, color + (255,))
                tint.putalpha(g.getchannel('A'))
                img.alpha_composite(tint, (px, H - 65 - asc[row] - up))
            px += widths.get(ch, 6)

    text('Староста Ведан', 0, (243, 230, 207), 48)
    text('Здравствуй, Steve. Давно у нас путников', 1, (74, 44, 28), 38)
    text('Ты по делу или так, поглазеть?', 2, (74, 44, 28), 38)
    put('\ue021', 166)
    text('1. Кто ты такой?', 4, (122, 74, 50), 184)
    text('2. Есть работа?', 5, (58, 29, 16), 184)
    text('3. Ничего, я пойду.', 6, (122, 74, 50), 184)
    d.line((W // 2 - 4, H // 2, W // 2 + 4, H // 2), fill=(255, 255, 255, 255))
    d.line((W // 2, H // 2 - 4, W // 2, H // 2 + 4), fill=(255, 255, 255, 255))
    os.makedirs(os.path.join(HERE, 'preview'), exist_ok=True)
    img.resize((W * 3, H * 3), Image.NEAREST).save(os.path.join(HERE, 'preview', 'hud.png'))


if __name__ == '__main__':
    main()
