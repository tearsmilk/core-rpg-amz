# Проверки переводчика японского режима (amazake_ja) — без Java: запускаются и CPython 3.12+, и GraalPy (тест хоста).
#   python3 src/test/resources/ja/translator_checks.py src/host/resources/python
import ast
import re
import os
import sys
import tempfile
import traceback

if len(sys.argv) > 1:
    sys.path.insert(0, sys.argv[1])
import amazake_ja as ja

results = []


def check(name):
    def deco(fn):
        try:
            fn()
            results.append((name, None))
        except BaseException as e:  # noqa
            results.append((name, "".join(traceback.format_exception(type(e), e, e.__traceback__))))
        return fn
    return deco


def same_shape(src, out):
    a, b = src.splitlines(keepends=True), out.splitlines(keepends=True)
    assert len(a) == len(b), (len(a), len(b))
    for i, (x, y) in enumerate(zip(a, b), 1):
        assert len(x.encode()) == len(y.encode()), (i, x, y)


@check("keywords: every word round-trips with the byte invariant")
def _():
    for jw, en in list(ja.KEYWORDS.items()) + list(ja.DUNDERS.items()):
        out = ja.translate(jw + "\n")
        assert out.rstrip() == en, (jw, out)
        assert len(out.encode()) == len((jw + "\n").encode())


@check("basic program translates, parses and runs")
def _():
    src = (
        "関数 足す(甲, 乙=無し):\n"
        "    もし 乙 である 無し:\n"
        "        返す 甲\n"
        "    でなければもし 甲 > 乙 かつ 非 (乙 < 0):\n"
        "        返す 甲 - 乙\n"
        "    でなければ:\n"
        "        返す 甲 + 乙\n"
        "結果 = [足す(値, 1) 各 値 於 範囲(5) もし 値 % 2 == 0]\n"
        "表 = {\"ア\": 1}\n"
        "表.更新({\"イ\": 2})\n"
        "鍵一覧 = 並べ替え(表.キー一覧())\n"
        "試す:\n"
        "    投げる 値エラー(\"bad\")\n"
        "捕まえる 値エラー として 誤り:\n"
        "    文 = 文字列(誤り)\n"
        "最後に:\n"
        "    何もしない\n"
        "回数 = 0\n"
        "繰り返し 回数 < 10:\n"
        "    回数 += 1\n"
        "    もし 回数 == 3:\n"
        "        飛ばす\n"
        "    もし 回数 > 5:\n"
        "        抜ける\n"
        "平方 = ラムダ 数: 数 * 数\n"
        "真 = はい または いいえ\n"
    )
    out = ja.translate(src)
    same_shape(src, out)
    ast.parse(out)
    ja.install_builtins()
    g = {}
    exec(ja.compile_ja(src, "<t>"), g)
    assert g["結果"] == [1, 1, 3], g["結果"]
    assert g["鍵一覧"] == ["ア", "イ"]
    assert g["文"] == "bad"
    assert g["回数"] == 6
    assert g["平方"](4) == 16
    assert g["真"] is True


@check("strings, comments, numbers untouched")
def _():
    src = '文 = "もし 無し である はい"  # もし 返す 無し\n数 = 0x1F + 1e3\n長文 = """\nもし\n返す\n"""\n'
    out = ja.translate(src)
    same_shape(src, out)
    assert '"もし 無し である はい"' in out
    assert "# もし 返す 無し" in out
    assert '"""\nもし\n返す\n"""' in out
    g = {}
    exec(ja.compile_ja(src, "<t>"), g)
    assert g["文"] == "もし 無し である はい"
    assert g["長文"] == "\nもし\n返す\n"


@check("f-strings: literal parts untouched, names in {} translated, nested")
def _():
    src = ('幅 = 6\n'
           '値 = {"鍵": はい}\n'
           '文 = f"もし {値["鍵"]!r:>{幅}} {{無し}} {はい もし 幅 > 1 でなければ いいえ} {[甲 各 甲 於 範囲(2)]}"\n'
           '二 = f\'\'\'{無し}\n返す {f"{はい}"}\'\'\'\n')
    out = ja.translate(src)
    same_shape(src, out)
    ja.install_builtins()
    g = {}
    exec(ja.compile_ja(src, "<t>"), g)
    assert g["文"] == "もし   True {無し} True [0, 1]", repr(g["文"])
    assert g["二"] == "None\n返す True", repr(g["二"])


@check("nested brackets and line continuations keep line structure")
def _():
    src = ("値 = [\n"
           "    (甲, 乙)\n"
           "    各 甲 於 範囲(3)\n"
           "    各 乙 於 (\n"
           "        はい, いいえ,\n"
           "    )\n"
           "    もし 甲 である 非 無し\n"
           "]\n"
           "合 = 1 + \\\n"
           "    2\n")
    out = ja.translate(src)
    same_shape(src, out)
    ja.install_builtins()
    g = {}
    exec(ja.compile_ja(src, "<t>"), g)
    assert len(g["値"]) == 6 and g["合"] == 3


@check("decorators, classes, dunders, method words in class body")
def _():
    src = ("関数 印(処理):\n"
           "    処理.印付き = はい\n"
           "    返す 処理\n"
           "\n"
           "型定義 袋:\n"
           "    # 注釈\n"
           "    関数 __初期化__(自分):\n"
           "        自分.中 = []\n"
           "\n"
           "    @印\n"
           "    関数 追加(自分, 物):\n"
           "        自分.中.追加(物)\n"
           "\n"
           "    関数 __長さ__(自分):\n"
           "        返す 長さ(自分.中)\n"
           "\n"
           "関数 追加(甲):\n"
           "    返す 甲 + 1\n"
           "袋一 = 袋()\n"
           "袋一.追加(5)\n"
           "名 = 袋.__名前__\n")
    out = ja.translate(src)
    same_shape(src, out)
    assert re.search(r"def +append\(", out) and re.search(r"def +追加\(甲", out), out
    ja.install_builtins()
    g = {}
    exec(ja.compile_ja(src, "<t>"), g)
    assert len(g["袋一"]) == 1 and g["袋一"].中 == [5]
    assert g["袋"].append.印付き is True
    assert g["追加"](1) == 2
    assert g["名"] == "袋"


@check("method words are not translated as plain names")
def _():
    src = "取得 = 1\n更新 = 取得 + 1\n"
    out = ja.translate(src)
    assert out == src


@check("one-line class does not leak class-body state")
def _():
    src = "型定義 甲: 何もしない\n関数 追加(乙):\n    返す 乙\n"
    out = ja.translate(src)
    assert re.search(r"def +追加\(", out), out


@check("match / case soft keywords")
def _():
    src = ("関数 判定(値):\n"
           "    場合分け 値:\n"
           "        場合 [\"reload\"]:\n"
           "            返す 1\n"
           "        場合 _:\n"
           "            返す 2\n")
    g = {}
    exec(ja.compile_ja(src, "<t>"), g)
    assert g["判定"](["reload"]) == 1 and g["判定"]([]) == 2


@check("traceback points to the original file and line")
def _():
    d = tempfile.mkdtemp()
    p = os.path.join(d, "main.py")
    src = ("# 一行目\n"
           "関数 壊す(甲):\n"
           "    もし 甲 である 無し:\n"
           "        投げる 値エラー(\"empty value\")\n"
           "    返す 甲\n"
           "壊す(無し)\n")
    with open(p, "w", encoding="utf-8") as f:
        f.write(src)
    ja.install_builtins()
    try:
        exec(ja.compile_ja(src, p), {"__name__": "x"})
        raise AssertionError("no error")
    except ValueError as e:
        tb = traceback.extract_tb(e.__traceback__)
        frames = [(os.path.basename(f.filename), f.lineno, f.name) for f in tb if f.filename == p]
        assert frames == [("main.py", 6, "<module>"), ("main.py", 4, "壊す")], frames
        text = "".join(traceback.format_exception(type(e), e, e.__traceback__))
        assert "投げる 値エラー" in text, text      # строка — из исходного файла
        assert "ValueError: empty value" in text


@check("syntax error: original line, English message and hint")
def _():
    src = "甲 = 1\nもし甲 > 0:\n    返す\n"
    try:
        ja.compile_ja(src, "/x/main.py")
        raise AssertionError("no error")
    except SyntaxError as e:
        assert e.lineno == 2, e.lineno
        assert e.text.rstrip("\n") == "もし甲 > 0:", repr(e.text)
        assert "put a space after the keyword 'もし' (if)" in e.msg, e.msg
        assert e.filename == "/x/main.py"


@check("syntax error column maps back to the original line")
def _():
    src = "もし はい:\n    甲 = (1 +)\n"
    try:
        ja.compile_ja(src, "<t>")
        raise AssertionError("no error")
    except SyntaxError as e:
        assert e.lineno == 2 and e.text.startswith("    甲 = (1 +)"), (e.lineno, e.text)
        assert 1 <= e.offset <= len(e.text), e.offset


@check("full-width punctuation is rejected with an English hint")
def _():
    for src, ch in (("甲 = 範囲（3）\n", "（"), ("もし はい：\n    何もしない\n", "："), ("甲 = 1　+ 2\n", "　")):
        try:
            ja.compile_ja(src, "<t>")
            raise AssertionError("no error for %r" % src)
        except SyntaxError as e:
            assert "full-width" in e.msg and "use ASCII" in e.msg, e.msg
            assert e.lineno == 1


@check("unterminated string inside a Japanese file reports the right line")
def _():
    src = "もし はい:\n    甲 = 1\n文 = \"abc\n"
    try:
        ja.compile_ja(src, "<t>")
        raise AssertionError("no error")
    except SyntaxError as e:
        assert e.lineno == 3, (e.lineno, e.msg)


@check("names that merely start with a keyword are ordinary identifiers")
def _():
    src = "もしも = 1\n無しの数 = 2\n返す値 = もしも + 無しの数\n"
    g = {}
    exec(ja.compile_ja(src, "<t>"), g)
    assert g["返す値"] == 3


@check("English keywords still work in Japanese mode")
def _():
    src = "def f(x):\n    return x if x is not None else 0\n値 = f(無し)\n"
    g = {}
    exec(ja.compile_ja(src, "<t>"), g)
    assert g["値"] == 0


@check("module import hook translates every .py of the system")
def _():
    d = tempfile.mkdtemp()
    with open(os.path.join(d, "japlugmod.py"), "w", encoding="utf-8") as f:
        f.write("関数 二倍(甲):\n    返す 甲 * 2\n")
    os.makedirs(os.path.join(d, "japkg"))
    with open(os.path.join(d, "japkg", "__init__.py"), "w", encoding="utf-8") as f:
        f.write("から .内側 取り込む 値\n")
    with open(os.path.join(d, "japkg", "内側.py"), "w", encoding="utf-8") as f:
        f.write("値 = はい\n")
    sys.path.insert(0, d)
    try:
        ja.install(d)
        import japlugmod
        import japkg
        assert japlugmod.二倍(4) == 8
        assert japkg.値 is True
    finally:
        sys.path.remove(d)


@check("vocabulary sample (section 8 of the spec) translates and parses")
def _():
    here = os.path.dirname(os.path.abspath(__file__))
    for cand in (os.path.join(here, "..", "..", "..", "..", "scripts", "core_potions", "main.py"),
                 os.environ.get("CORE_POTIONS_MAIN", "")):
        if cand and os.path.isfile(cand):
            src = open(cand, encoding="utf-8").read()
            out = ja.translate(src, cand)
            same_shape(src, out)
            ast.parse(out)
            return


failed = [r for r in results if r[1]]
for name, err in results:
    print(("FAIL " if err else "ok   ") + name)
    if err:
        print(err)
print("%d checks, %d failed" % (len(results), len(failed)))
RESULT = "%d/%d" % (len(results) - len(failed), len(results))
if __name__ == "__main__" and failed:
    sys.exit(1)
