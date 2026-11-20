#!/usr/bin/env python3
"""shaderhooks - shared core shader hooks of textshader, tabshader, nameshader and chatshader.

    python3 -I tools/shaderhooks/shaderhooks.py                 # check: generated files up to date in every project
    python3 -I tools/shaderhooks/shaderhooks.py --write         # regenerate in every project, copy this tool dir
    python3 -I tools/shaderhooks/shaderhooks.py install --dest DIR --plugins textshader,tabshader [--present ...]
    python3 -I tools/shaderhooks/shaderhooks.py combos [client-1.21.11.jar]

Several plugins need hooks in the same core shaders (gui, position_tex_color, rendertype_text). Every project
ships the same canonical core files; each calls a fixed list of hook functions, one include per plugin and stage:
<plugin>:<prefix>_hook_<program>_<stage>.glsl (prefixes ts, th, ns, cs; programs gui, sprite, text; stages vertex,
fragment). A plugin ships its own real hook files in pack/assets and no-op stubs of every plugin in pack/stubs.
Real files and stubs have the same path, so they are never copied by hand: the plugin (ShaderHooks.java, generated
from ShaderHooks.java.in) writes them into plugins/Nexo/pack/assets at startup:

- canonical core files of the programs where the plugin has a real hook (never over a foreign file, never over a
  newer SHADERHOOKS_VERSION; a program is written as a vsh+fsh pair or not at all);
- all other files of its pack/assets (own includes, own real hooks, textures, exclusive core files that are not
  foreign);
- if any canonical core file is in the pack: stubs of every other plugin that is not installed, or whose hook
  files are incomplete.

The result does not depend on the order in which plugins start. "install" emulates the same rules (used by the
real client harness and for manual deployment), "combos" proves it: every combination of the existing projects,
every install order, the same files and every program compiles (glslangValidator 330 and 410); stale real hooks of
a removed plugin are replaced by stubs. The tool dir is identical in every project; --write copies the dir of the
project it runs from to the others.
"""
import difflib
import itertools
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(os.path.dirname(HERE))
REPO = os.path.dirname(PROJECT)
VERSION = 1
MARK = "#define SHADERHOOKS_VERSION "
PLUGINS = [("textshader", "ts"), ("tabshader", "th"), ("nameshader", "ns"), ("chatshader", "cs")]
NAMES = [n for n, _ in PLUGINS]
PREFIX = dict(PLUGINS)
PROGRAMS = [("gui", "gui"), ("sprite", "position_tex_color"), ("text", "rendertype_text")]
STAGES = ("vertex", "fragment")
OVERLAY_PROJECTS = ("textshader", "tabshader")
OVERLAY_DIR = "mythichud-1_21_6"
JAR = os.path.join(REPO, "core-npc", "tools", ".cache", "client-1.21.11.jar")
CORE_DIR = "minecraft/shaders/core/"

POSITION = {
    "gui": "void {p}_hook_gui_position(inout vec3 pos)",
    "sprite": "void {p}_hook_sprite_position(inout vec3 pos)",
    "text": "void {p}_hook_text_position(inout vec3 pos)",
}
VERTEX = {
    "gui": "void {p}_hook_gui_vertex(vec3 pos, vec4 color, int vertexId, inout vec4 outColor)",
    "sprite": "void {p}_hook_sprite_vertex(vec3 pos, vec2 uv, vec4 color, int vertexId, inout vec4 outColor)",
    "text": "void {p}_hook_text_vertex(vec3 pos, vec4 color, vec2 uv, int vertexId, vec4 light, inout vec4 outColor)",
}
FRAGMENT = {
    "gui": "vec4 {p}_hook_gui_fragment(vec4 color, vec4 vertexColor)",
    "sprite": "vec4 {p}_hook_sprite_fragment(vec4 color, vec4 tex, vec2 uv, vec4 vertexColor)",
    "text": "vec4 {p}_hook_text_fragment(vec4 color, vec4 vertexColor, vec2 uv)",
}
CALL_POSITION = "    {p}_hook_{prog}_position(pos);"
CALL_VERTEX = {
    "gui": "    {p}_hook_gui_vertex(Position, Color, gl_VertexID, vertexColor);",
    "sprite": "    {p}_hook_sprite_vertex(Position, UV0, Color, gl_VertexID, vertexColor);",
    "text": "    {p}_hook_text_vertex(Position, Color, UV0, gl_VertexID, light, vertexColor);",
}
CALL_FRAGMENT = {
    "gui": "    color = {p}_hook_gui_fragment(color, vertexColor);",
    "sprite": "    color = {p}_hook_sprite_fragment(color, tex, texCoord0, vertexColor);",
    "text": "    color = {p}_hook_text_fragment(color, vertexColor, texCoord0);",
}
MH_HEAD = ["#define MH_VERSION 5", "#moj_import <minecraft:mythichud_utils.glsl>", ""]
MH_TAIL = [
    "    vec4 thPosition = gl_Position;",
    "    float thSpherical = sphericalVertexDistance;",
    "    float thCylindrical = cylindricalVertexDistance;",
    "    applyCustomHud();",
    "    if (distance(gl_Position, ProjMat * ModelViewMat * vec4(Position, 1.0)) < 0.001 * max(1.0, abs(thPosition.w))) {",
    "        gl_Position = thPosition;",
    "        sphericalVertexDistance = thSpherical;",
    "        cylindricalVertexDistance = thCylindrical;",
    "    }",
]


def hook_path(plugin, prog, stage):
    return f"{plugin}/shaders/include/{PREFIX[plugin]}_hook_{prog}_{stage}.glsl"


def hooks(plugin):
    return [hook_path(plugin, prog, stage) for prog, _ in PROGRAMS for stage in STAGES]


def core_path(prog, ext):
    return CORE_DIR + dict(PROGRAMS)[prog] + "." + ext


def canonical_paths():
    return [core_path(prog, ext) for prog, _ in PROGRAMS for ext in ("vsh", "fsh")]


def stub(plugin, prog, stage):
    p = PREFIX[plugin]
    if stage == "vertex":
        body = [POSITION[prog].format(p=p) + " {", "}", "", VERTEX[prog].format(p=p) + " {", "}"]
    else:
        body = [FRAGMENT[prog].format(p=p) + " {", "    return color;", "}"]
    return "\n".join(["#version 330", ""] + body) + "\n"


def imports(prog, stage):
    return [f"#moj_import <{n}:{PREFIX[n]}_hook_{prog}_{stage}.glsl>" for n in NAMES]


def calls(template, prog):
    return [template.format(p=PREFIX[n], prog=prog) for n in NAMES]


def position_block(prog):
    return ["    vec3 pos = Position;"] + calls(CALL_POSITION, prog) + ["    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);"]


def canonical(prog, ext):
    head = ["#version 330", ""]
    mark = [MARK + str(VERSION), ""]
    if prog == "gui" and ext == "vsh":
        lines = head + ["#moj_import <minecraft:dynamictransforms.glsl>", "#moj_import <minecraft:projection.glsl>", ""] + mark
        lines += imports("gui", "vertex") + ["", "in vec3 Position;", "in vec4 Color;", "", "out vec4 vertexColor;", "",
                                             "void main() {"]
        lines += position_block("gui") + ["", "    vertexColor = Color;"] + calls(CALL_VERTEX["gui"], "gui") + ["}"]
    elif prog == "gui":
        lines = head + ["#moj_import <minecraft:dynamictransforms.glsl>", ""] + mark
        lines += imports("gui", "fragment") + ["", "in vec4 vertexColor;", "", "out vec4 fragColor;", "", "void main() {",
                                               "    vec4 color = vertexColor;"]
        lines += calls(CALL_FRAGMENT["gui"], "gui")
        lines += ["    if (color.a == 0.0) {", "        discard;", "    }", "    fragColor = color * ColorModulator;", "}"]
    elif prog == "sprite" and ext == "vsh":
        lines = head + ["#moj_import <minecraft:dynamictransforms.glsl>", "#moj_import <minecraft:projection.glsl>", ""] + mark
        lines += imports("sprite", "vertex") + ["", "in vec3 Position;", "in vec2 UV0;", "in vec4 Color;", "",
                                                "out vec2 texCoord0;", "out vec4 vertexColor;", "", "void main() {"]
        lines += position_block("sprite") + ["", "    texCoord0 = UV0;", "    vertexColor = Color;"]
        lines += calls(CALL_VERTEX["sprite"], "sprite") + ["}"]
    elif prog == "sprite":
        lines = head + ["#moj_import <minecraft:dynamictransforms.glsl>", ""] + mark
        lines += ["uniform sampler2D Sampler0;", ""] + imports("sprite", "fragment")
        lines += ["", "in vec2 texCoord0;", "in vec4 vertexColor;", "", "out vec4 fragColor;", "", "void main() {",
                  "    vec4 tex = texture(Sampler0, texCoord0);", "    vec4 color = tex * vertexColor;"]
        lines += calls(CALL_FRAGMENT["sprite"], "sprite")
        lines += ["    if (color.a == 0.0) {", "        discard;", "    }", "    fragColor = color * ColorModulator;", "}"]
    elif ext == "vsh":
        lines = head + ["#moj_import <minecraft:fog.glsl>", "#moj_import <minecraft:dynamictransforms.glsl>",
                        "#moj_import <minecraft:projection.glsl>", ""] + mark
        lines += ["uniform sampler2D Sampler0;", "uniform sampler2D Sampler2;", ""] + imports("text", "vertex")
        lines += ["", "in vec3 Position;", "in vec4 Color;", "in vec2 UV0;", "in ivec2 UV2;", "",
                  "out float sphericalVertexDistance;", "out float cylindricalVertexDistance;", "out vec4 vertexColor;",
                  "out vec2 texCoord0;", "", "void main() {"]
        lines += position_block("text")
        lines += ["", "    sphericalVertexDistance = fog_spherical_distance(Position);",
                  "    cylindricalVertexDistance = fog_cylindrical_distance(Position);",
                  "    vec4 light = texelFetch(Sampler2, UV2 / 16, 0);", "    vertexColor = Color * light;",
                  "    texCoord0 = UV0;"]
        lines += calls(CALL_VERTEX["text"], "text") + ["}"]
    else:
        lines = head + ["#moj_import <minecraft:fog.glsl>", "#moj_import <minecraft:dynamictransforms.glsl>", ""] + mark
        lines += ["uniform sampler2D Sampler0;", ""] + imports("text", "fragment")
        lines += ["", "in float sphericalVertexDistance;", "in float cylindricalVertexDistance;", "in vec4 vertexColor;",
                  "in vec2 texCoord0;", "", "out vec4 fragColor;", "", "void main() {",
                  "    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;"]
        lines += calls(CALL_FRAGMENT["text"], "text")
        lines += ["    if (color.a < 0.1) {", "        discard;", "    }",
                  "    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, "
                  "FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);", "}"]
    return "\n".join(lines) + "\n"


def overlay(ext):
    text = canonical("text", ext)
    if ext == "fsh":
        return text
    lines = text.split("\n")
    main = lines.index("void main() {")
    lines[main:main] = MH_HEAD
    last = max(i for i, l in enumerate(lines) if "_hook_text_vertex(" in l)
    lines[last + 1:last + 1] = MH_TAIL
    return "\n".join(lines)


def projects():
    out = []
    for n in NAMES:
        d = os.path.join(REPO, n)
        if os.path.isdir(os.path.join(d, "pack", "assets")):
            out.append(n)
    return out


def java_package(project):
    return "dev.tearsmilk." + project


def java_source(name, project):
    text = open(os.path.join(HERE, name + ".java.in"), encoding="utf-8").read()
    return text.replace("@PACKAGE@", java_package(project)).replace("@NAME@", project)


def generated(project):
    out = {}
    for prog, _ in PROGRAMS:
        for ext in ("vsh", "fsh"):
            out["pack/assets/" + core_path(prog, ext)] = canonical(prog, ext)
        for n in NAMES:
            for stage in STAGES:
                out["pack/stubs/" + hook_path(n, prog, stage)] = stub(n, prog, stage)
    if project in OVERLAY_PROJECTS:
        for ext in ("vsh", "fsh"):
            out[f"pack/overlays/{OVERLAY_DIR}/assets/{CORE_DIR}rendertype_text.{ext}"] = overlay(ext)
    pkg = "src/main/java/dev/tearsmilk/" + project + "/"
    out[pkg + "ShaderHooks.java"] = java_source("ShaderHooks", project)
    out["src/test/java/dev/tearsmilk/" + project + "/ShaderHooksTest.java"] = java_source("ShaderHooksTest", project)
    return out


def tool_files(base):
    return sorted(f for f in os.listdir(base) if not f.startswith(".") and f != "__pycache__")


def read_tree(base):
    out = {}
    if not os.path.isdir(base):
        return out
    for d, _, files in os.walk(base):
        for f in files:
            p = os.path.join(d, f)
            with open(p, "rb") as fh:
                out[os.path.relpath(p, base).replace(os.sep, "/")] = fh.read()
    return out


def bundle(project):
    root = os.path.join(REPO, project, "pack")
    return read_tree(os.path.join(root, "assets")), read_tree(os.path.join(root, "stubs"))


def own_hooks_problems(project, own):
    problems = []
    p = PREFIX[project]
    for prog, _ in PROGRAMS:
        for stage in STAGES:
            path = hook_path(project, prog, stage)
            if path not in own:
                problems.append(f"{project}: missing own hook pack/assets/{path}")
                continue
            text = own[path].decode("utf-8")
            sigs = [POSITION[prog], VERTEX[prog]] if stage == "vertex" else [FRAGMENT[prog]]
            for s in sigs:
                if s.format(p=p) + " {" not in text:
                    problems.append(f"{project}: {path} does not define {s.format(p=p)}")
            if re.search(r"^\s*(flat\s+)?(in|out)\s", text, re.M):
                problems.append(f"{project}: {path} declares varyings, keep them in the included files")
    for path in own:
        m = re.match(r"^([a-z]+)/shaders/include/([a-z]+)_hook_", path)
        if m and m.group(1) != project:
            problems.append(f"{project}: ships a hook of another plugin in pack/assets: {path}")
    return problems


def check(write):
    ok = True
    here = os.path.basename(PROJECT)
    for project in projects():
        base = os.path.join(REPO, project)
        tool = os.path.join(base, "tools", "shaderhooks")
        if project != here:
            for f in tool_files(HERE):
                src = os.path.join(HERE, f)
                dst = os.path.join(tool, f)
                same = os.path.exists(dst) and open(src, "rb").read() == open(dst, "rb").read()
                if not same:
                    if write:
                        os.makedirs(tool, exist_ok=True)
                        shutil.copyfile(src, dst)
                        print("COPY tools/shaderhooks/" + f + " -> " + project)
                    else:
                        print("DIFF " + project + "/tools/shaderhooks/" + f)
                        ok = False
            if os.path.isdir(tool):
                for f in tool_files(tool):
                    if f not in tool_files(HERE):
                        print("EXTRA " + project + "/tools/shaderhooks/" + f)
                        ok = False
        for rel, text in generated(project).items():
            path = os.path.join(base, rel)
            data = text.encode("utf-8")
            same = os.path.exists(path) and open(path, "rb").read() == data
            if same:
                continue
            if write:
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "wb") as fh:
                    fh.write(data)
                print("WRITE " + project + "/" + rel)
            else:
                print("STALE " + project + "/" + rel)
                ok = False
        own, stubs = bundle(project)
        for path in own:
            if path.startswith(CORE_DIR) and path not in canonical_paths():
                text = own[path].decode("utf-8")
                if MARK in text:
                    print(f"ERR {project}: exclusive core file {path} must not carry {MARK.strip()}")
                    ok = False
        for p in own_hooks_problems(project, own):
            print("ERR " + p)
            ok = False
        extra = sorted(set(stubs) - {hook_path(n, prog, st) for n in NAMES for prog, _ in PROGRAMS for st in STAGES})
        for e in extra:
            print(f"ERR {project}: unexpected file pack/stubs/{e}")
            ok = False
    union = {}
    for project in projects():
        own, _ = bundle(project)
        for path, data in own.items():
            if path in union and union[path][1] != data:
                print(f"ERR copy order: {path} differs between {union[path][0]} and {project}")
                ok = False
            union.setdefault(path, (project, data))
    print("RESULT:", "up to date" if ok else ("written" if write else "STALE (run with --write)"))
    return ok or write


def classify(data):
    if data is None:
        return "absent", 0
    text = data.decode("utf-8", "replace")
    m = re.search(re.escape(MARK) + r"(\d+)", text)
    if m:
        return "ours", int(m.group(1))
    if any(f"<{n}:" in text for n in NAMES):
        return "ours", 0
    return "foreign", 0


def fs_read(dest, path):
    p = os.path.join(dest, path)
    if not os.path.isfile(p):
        return None
    with open(p, "rb") as fh:
        return fh.read()


def fs_put(dest, path, data, written):
    if fs_read(dest, path) == data:
        return
    p = os.path.join(dest, path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "wb") as fh:
        fh.write(data)
    written.append(path)


def real(plugin, prog, own, stubs):
    for stage in STAGES:
        h = hook_path(plugin, prog, stage)
        if own.get(h) is not None and own.get(h) != stubs.get(h):
            return True
    return False


def canonical_present(dest):
    for path in canonical_paths():
        data = fs_read(dest, path)
        if data is not None and MARK.encode() in data:
            return True
    return False


def install(dest, plugin, own, stubs, present):
    written, foreign, kept = [], [], []
    handled = set()
    for prog, _ in PROGRAMS:
        files = [core_path(prog, "vsh"), core_path(prog, "fsh")]
        if not all(f in own for f in files):
            continue
        handled.update(files)
        if not real(plugin, prog, own, stubs):
            continue
        states = [classify(fs_read(dest, f)) for f in files]
        if any(s[0] == "foreign" for s in states):
            foreign += [f for f, s in zip(files, states) if s[0] == "foreign"]
            continue
        if any(s[0] == "ours" and s[1] > VERSION for s in states):
            kept += files
            continue
        for f in files:
            fs_put(dest, f, own[f], written)
    for path in sorted(own):
        if path in handled:
            continue
        if path.startswith(CORE_DIR) and classify(fs_read(dest, path))[0] == "foreign":
            foreign.append(path)
            continue
        fs_put(dest, path, own[path], written)
    if canonical_present(dest):
        for other in NAMES:
            if other == plugin:
                continue
            hs = hooks(other)
            if not present(other) or any(fs_read(dest, h) is None for h in hs):
                for h in hs:
                    fs_put(dest, h, stubs[h], written)
    return written, foreign, kept


def install_set(dest, plugins, present=None):
    alive = set(present if present is not None else plugins)
    report = []
    for p in plugins:
        own, stubs = bundle(p)
        report.append((p,) + install(dest, p, own, stubs, lambda n: n in alive))
    return report


def load_vanilla(jar):
    with zipfile.ZipFile(jar) as z:
        return {n: z.read(n).decode() for n in z.namelist() if n.startswith("assets/minecraft/shaders/")}


def expand(src, vanilla, seen, roots, missing):
    out = []
    for line in src.split("\n"):
        m = re.match(r"^#moj_import <([a-z_]+):([^>]+)>", line)
        if not m:
            out.append(line)
            continue
        ns, p = m.groups()
        if (ns, p) in seen:
            continue
        seen.add((ns, p))
        text = None
        for base in roots:
            own = os.path.join(base, ns, "shaders", "include", p)
            if os.path.exists(own):
                text = open(own, encoding="utf-8").read()
                break
        if text is None:
            key = f"assets/{ns}/shaders/include/{p}"
            if key not in vanilla:
                missing.append(f"{ns}:{p}")
                continue
            text = vanilla[key]
        text = "\n".join(l for l in text.split("\n") if not l.startswith("#version"))
        out.append(expand(text, vanilla, seen, roots, missing))
    return "\n".join(out)


def program_source(vanilla, roots, core, ext, missing):
    text = None
    for base in roots:
        p = os.path.join(base, "minecraft", "shaders", "core", f"{core}.{ext}")
        if os.path.exists(p):
            text = open(p, encoding="utf-8").read()
            break
    if text is None:
        text = vanilla[f"assets/minecraft/shaders/core/{core}.{ext}"]
    return expand(text, vanilla, set(), roots, missing)


def compile_pair(name, vsrc, fsrc, tmp):
    v = os.path.join(tmp, name + ".vert")
    f = os.path.join(tmp, name + ".frag")
    with open(v, "w") as fh:
        fh.write(vsrc)
    with open(f, "w") as fh:
        fh.write(fsrc)
    r = subprocess.run(["glslangValidator", "-l", v, f], capture_output=True, text=True)
    text = (r.stdout + r.stderr).strip()
    if r.returncode != 0 or "WARNING" in text:
        print("ERR " + name + "\n    " + text.replace("\n", "\n    ")[:4000])
    return r.returncode == 0


def compile_dir(label, assets, vanilla, tmp):
    ok = True
    core_dir = os.path.join(assets, "minecraft", "shaders", "core")
    cores = sorted({f.rsplit(".", 1)[0] for f in os.listdir(core_dir)}) if os.path.isdir(core_dir) else []
    count = 0
    for core in cores:
        if f"assets/minecraft/shaders/core/{core}.vsh" not in vanilla:
            continue
        missing = []
        vs = program_source(vanilla, [assets], core, "vsh", missing)
        fs = program_source(vanilla, [assets], core, "fsh", missing)
        if missing:
            print(f"ERR {label} {core}: missing includes {', '.join(sorted(set(missing)))}")
            ok = False
            continue
        for ver in ("330", "410"):
            ok &= compile_pair(f"{label}_{core}_{ver}".replace("+", "_"), vs.replace("#version 330", f"#version {ver}", 1),
                               fs.replace("#version 330", f"#version {ver}", 1), tmp)
            count += 1
    return ok, count


def shader_tree(dest):
    return {k: v for k, v in read_tree(dest).items() if "/shaders/" in k}


def tree_diff(a, b):
    keys = sorted(set(a) | set(b))
    return [k for k in keys if a.get(k) != b.get(k)]


def combos(argv):
    vanilla = load_vanilla(argv[0] if argv else JAR)
    names = projects()
    ok = True
    total = 0
    with tempfile.TemporaryDirectory() as tmp:
        work = os.path.join(tmp, "work")
        for r in range(1, len(names) + 1):
            for subset in itertools.combinations(names, r):
                label = "+".join(subset)
                before = ok
                ok = True
                trees = []
                for order in itertools.permutations(subset):
                    d = os.path.join(work, "_".join(order))
                    install_set(d, list(order))
                    trees.append((order, read_tree(d)))
                    again = install_set(d, list(order))
                    if any(w for _, w, _, _ in again):
                        print(f"ERR {label}: second install in order {','.join(order)} still writes files")
                        ok = False
                base = trees[0][1]
                for order, t in trees[1:]:
                    diff = tree_diff(base, t)
                    if diff:
                        print(f"ERR {label}: order {','.join(order)} differs: {', '.join(diff[:5])}")
                        ok = False
                d0 = os.path.join(work, "_".join(trees[0][0]))
                for n in NAMES:
                    for h in hooks(n):
                        data = fs_read(d0, h)
                        if not canonical_present(d0):
                            continue
                        if data is None:
                            print(f"ERR {label}: hook missing {h}")
                            ok = False
                        elif n not in subset and data != stub(n, h.split("_hook_")[1].split("_")[0], h.rsplit("_", 1)[1][:-5]).encode():
                            print(f"ERR {label}: {h} is not a stub")
                            ok = False
                good, count = compile_dir(label, d0, vanilla, tmp)
                ok &= good
                total += count
                stale = os.path.join(work, "stale_" + label)
                install_set(stale, names)
                install_set(stale, list(subset), present=subset)
                diff = tree_diff(shader_tree(d0), {k: v for k, v in shader_tree(stale).items() if k in shader_tree(d0)})
                hook_diff = [k for k in diff if "_hook_" in k or k in canonical_paths()]
                if hook_diff:
                    print(f"ERR {label}: after removing the other plugins hooks differ: {', '.join(hook_diff[:5])}")
                    ok = False
                good, count = compile_dir("stale_" + label, stale, vanilla, tmp)
                ok &= good
                total += count
                print(f"{'OK ' if ok else 'ERR'} {label}: {len(trees)} orders, same files, programs compile")
                ok = ok and before
                shutil.rmtree(work)
        foreign = os.path.join(tmp, "foreign")
        fs_put(foreign, core_path("gui", "vsh"), b"#version 330\nvoid main() {\n}\n", [])
        rep = install_set(foreign, names)
        if fs_read(foreign, core_path("gui", "vsh")) != b"#version 330\nvoid main() {\n}\n" or fs_read(foreign, core_path("gui", "fsh")) is not None:
            print("ERR foreign gui.vsh was replaced or gui.fsh written without it")
            ok = False
        if "tabshader" in names and not any(core_path("gui", "vsh") in f for _, _, f, _ in rep):
            print("ERR foreign gui.vsh not reported")
            ok = False
        newer = os.path.join(tmp, "newer")
        newer_text = canonical("text", "vsh").replace(MARK + str(VERSION), MARK + str(VERSION + 1)).encode()
        fs_put(newer, core_path("text", "vsh"), newer_text, [])
        install_set(newer, names)
        if fs_read(newer, core_path("text", "vsh")) != newer_text:
            print("ERR a newer canonical rendertype_text.vsh was replaced")
            ok = False
    print(f"RESULT: {'all combinations compile, order does not matter' if ok else 'ERRORS'} ({total} program compilations)")
    return ok


def vanilla_diff(argv):
    vanilla = load_vanilla(argv[0] if argv else JAR)
    allowed = ("_hook_", MARK.strip(), "vec3 pos = Position;", "vec4(pos, 1.0)", "uniform sampler2D Sampler",
               "vec4 light = ", "Color * light", "vec4 tex = ", "tex * vertexColor")
    ok = True
    for path in canonical_paths():
        ours = canonical(*{core_path(p, e): (p, e) for p, _ in PROGRAMS for e in ("vsh", "fsh")}[path]).split("\n")
        theirs = vanilla["assets/" + path].split("\n")
        changed = [l for l in difflib.unified_diff(theirs, ours, lineterm="", n=0)
                   if l[:1] in "+-" and not l.startswith(("+++", "---"))]
        bad = [l for l in changed if l.strip("+- ") and not any(a in l for a in allowed)
               and not (l.startswith("-") and (l[1:].strip().startswith(("//", "layout(std140)", "};", "mat4 ", "vec4 ", "vec3 "))
                                               or "texelFetch(Sampler2" in l or "uniform sampler2D" in l
                                               or "gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);" in l
                                               or "texture(Sampler0, texCoord0) * vertexColor;" in l))
               and not l.startswith("+#moj_import <minecraft:")]
        print(f"VANILLA {path}: {len(changed)} changed lines" + (" (only hooks)" if not bad else " UNEXPECTED"))
        for l in bad:
            print("    " + l)
        ok &= not bad
    return ok


def main(argv):
    if argv[:1] == ["install"]:
        args = dict(zip(argv[1::2], argv[2::2]))
        dest = args.get("--dest")
        plugins = [p for p in args.get("--plugins", "").split(",") if p]
        if not dest or not plugins:
            print(__doc__)
            return 2
        present = [p for p in args["--present"].split(",") if p] if "--present" in args else None
        for p, written, foreign, kept in install_set(dest, plugins, present):
            print(f"{p}: written {len(written)}, foreign {foreign}, kept {kept}")
        return 0
    if argv[:1] == ["combos"]:
        ok = vanilla_diff(argv[1:])
        ok &= combos(argv[1:])
        return 0 if ok else 1
    return 0 if check("--write" in argv) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
