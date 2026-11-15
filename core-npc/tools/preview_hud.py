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
    glyphs = Image.open(os.path.join(A, 'textures', 'font', 'dialog', 'glyphs.png')).convert('RGBA')
    box = Image.open(os.path.join(A, 'textures', 'font', 'dialog', 'box.png')).convert('RGBA')
    hl = Image.open(os.path.join(A, 'textures', 'font', 'dialog', 'highlight.png')).convert('RGBA')
    tall_img = Image.open(os.path.join(A, 'textures', 'font', 'dialog', 'glyphs_tall.png')).convert('RGBA')
    cells = {}
    for prov, src, h, up in ((font('row0')['providers'][2], tall_img, 12, 3), (font('row0')['providers'][1], glyphs, 8, 0)):
        for r, line in enumerate(prov['chars']):
            for c, ch in enumerate(line):
                cells[ch] = (src, c * 8, r * 64, h, up)
    widths = {}
    for line in open(os.path.join(HERE, '..', 'src', 'main', 'resources', 'font-widths.txt'), encoding='utf-8'):
        if not line.startswith('#'):
            a, b = line.split()
            widths[chr(int(a, 16))] = int(b)
    asc = [font('row%d' % i)['providers'][1]['ascent'] for i in range(7)]
    box_asc = font('dialog')['providers'][1]['ascent']
    img = Image.new('RGBA', (W, H), (110, 150, 200, 255))
    d = ImageDraw.Draw(img)
    d.rectangle((W // 2 - 91, H - 22, W // 2 + 91, H - 1), fill=(40, 40, 40, 255))          # хотбар
    d.rectangle((W // 2 - 91, H - 39, W // 2 - 10, H - 31), fill=(200, 30, 30, 255))      # сердца
    d.rectangle((W // 2 - 91, H - 49, W // 2 - 10, H - 41), fill=(160, 160, 170, 255))    # броня
    d.rectangle((W // 2 + 10, H - 39, W // 2 + 91, H - 31), fill=(150, 90, 40, 255))      # еда
    x0 = W // 2 - (box.width + 1) // 2
    img.alpha_composite(box, (x0, H - 65 - box_asc))

    def text(s, row, color, x=8):
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

    text('Староста Ведан', 0, (255, 211, 122))
    text('Здравствуй, Steve. Давно у нас путников не было. Ёлки!', 1, (236, 230, 214))
    text('Ты по делу или так, поглазеть?', 2, (236, 230, 214))
    img.alpha_composite(hl.crop((0, 0, hl.width, 12)), (x0 + 6, H - 65 - asc[5] - 1))
    text('1. Кто ты такой?', 4, (189, 182, 166))
    text('» 2. Есть работа?', 5, (255, 211, 122))
    text('3. Ничего, я пойду.', 6, (189, 182, 166))
    d.line((W // 2 - 4, H // 2, W // 2 + 4, H // 2), fill=(255, 255, 255, 255))
    d.line((W // 2, H // 2 - 4, W // 2, H // 2 + 4), fill=(255, 255, 255, 255))
    os.makedirs(os.path.join(HERE, 'preview'), exist_ok=True)
    img.resize((W * 3, H * 3), Image.NEAREST).save(os.path.join(HERE, 'preview', 'hud.png'))


if __name__ == '__main__':
    main()
