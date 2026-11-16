#!/usr/bin/env python3
"""Хук «Посоха Лунного Странника» в общем шейдере rendertype_item_entity_translucent_cull (.vsh/.fsh).

    python3 -I tools/moonstaff_hook.py apply     вставить блоки во все копии (повторный запуск ничего не меняет)
    python3 -I tools/moonstaff_hook.py strip     убрать блоки (файлы снова байт-в-байт как до хука)
    python3 -I tools/moonstaff_hook.py status

Копии общего шейдера (все должны совпадать байт-в-байт): rezusmenu/pack (.vsh), core-portals/pack (.vsh+.fsh),
core-skyrift/pack (.vsh+.fsh). В nexo-items/pack копии быть НЕ должно (armor3d_check.py). Блоки вставляются после
блоков coreskyrift (импорт) и последней строкой main() (.vsh) / сразу после блока coreportals (.fsh).
"""
import hashlib
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
REL = os.path.join('pack', 'assets', 'minecraft', 'shaders', 'core', 'rendertype_item_entity_translucent_cull')
VSH = [os.path.join(REPO, d, REL + '.vsh') for d in ('rezusmenu', 'core-portals', 'core-skyrift')]
FSH = [os.path.join(REPO, d, REL + '.fsh') for d in ('core-portals', 'core-skyrift')]
MD5_VSH = 'e9e2d03b716df7e4cf9cefcdf1728ffb'   # без хука (= снимок прода)
MD5_FSH = '052fb2fb14fbfddbeb1533d8646c7a70'

SKY_IMPORT_END = '#moj_import <coreskyrift:skyrift_vertex.glsl>\n// <<< coreskyrift hook <<<\n'
SKY_IMPORT_END_F = '#moj_import <coreskyrift:skyrift_fragment.glsl>\n// <<< coreskyrift hook <<<\n'
SKY_CALL_END = ('    if (sr_vertex(Color, gl_VertexID, Position, Normal, UV2)) vertexColor = vec4(1.0);\n'
                '    // <<< coreskyrift hook <<<\n')
CP_FRAG_END = ('        return;\n    }\n    // <<< coreportals hook <<<\n')

V_IMPORT = ('\n// >>> azerus moonstaff hook (1/2): import >>>\n'
            '#moj_import <azerus:moonstaff_vertex.glsl>\n'
            '// <<< azerus moonstaff hook <<<\n')
V_CALL = ('\n    // >>> azerus moonstaff hook (2/2): magic circle / beam markers (outputs only, the vertex itself is'
          ' unchanged) >>>\n'
          '    ms_vertex(Color, gl_VertexID, UV2);\n'
          '    // <<< azerus moonstaff hook <<<\n')
F_IMPORT = ('\n// >>> azerus moonstaff hook (1/2): import >>>\n'
            '#moj_import <azerus:moonstaff_fragment.glsl>\n'
            '// <<< azerus moonstaff hook <<<\n')
F_MAIN = ('    // >>> azerus moonstaff hook (2/2): magic circle / beam from its own sprite (vanilla unless both'
          ' sentinels match) >>>\n'
          '    if (msOn > 0) {\n'
          '        vec4 msColor;\n'
          '        if (ms_fragment(Sampler0, texCoord0, msColor)) {\n'
          '            if (msColor.a < 0.004) discard;\n'
          '            fragColor = apply_fog(msColor, sphericalVertexDistance, cylindricalVertexDistance,'
          ' FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);\n'
          '            return;\n'
          '        }\n'
          '    }\n'
          '    // <<< azerus moonstaff hook <<<\n')

# (якорь, блок): блок вставляется сразу после якоря; якорь должен встречаться ровно один раз
V_EDITS = [(SKY_IMPORT_END, V_IMPORT), (SKY_CALL_END, V_CALL)]
F_EDITS = [(SKY_IMPORT_END_F, F_IMPORT), (CP_FRAG_END, F_MAIN)]


def md5(text):
    return hashlib.md5(text.encode('utf-8')).hexdigest()


def strip_text(text, edits):
    for anchor, block in edits:
        text = text.replace(anchor + block, anchor)
    return text


def apply_text(text, edits):
    text = strip_text(text, edits)
    for anchor, block in edits:
        n = text.count(anchor)
        if n != 1:
            raise SystemExit(f'якорь встречается {n} раз(а), ожидался 1: {anchor!r}')
        text = text.replace(anchor, anchor + block)
    return text


def has_hook(text):
    return 'azerus moonstaff hook' in text


def files():
    return [(p, V_EDITS, MD5_VSH) for p in VSH] + [(p, F_EDITS, MD5_FSH) for p in FSH]


def main(argv):
    cmd = argv[0] if argv else 'status'
    for p, edits, base in files():
        text = open(p, encoding='utf-8', newline='').read()
        rel = os.path.relpath(p, REPO)
        if cmd == 'status':
            print(f'{rel}: хук {"есть" if has_hook(text) else "нет"}, без хука md5 '
                  f'{"OK" if md5(strip_text(text, edits)) == base else "НЕ СОВПАДАЕТ"}')
            continue
        if md5(strip_text(text, edits)) != base:
            raise SystemExit(f'{rel}: файл без хука отличается от известного (md5 {base}) — сначала разберитесь')
        new = apply_text(text, edits) if cmd == 'apply' else strip_text(text, edits)
        with open(p, 'w', encoding='utf-8', newline='') as fh:
            fh.write(new)
        print(f'{rel}: {cmd} → md5 {md5(new)}')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
