"""
amazake_ja — японский режим (日本語モード) Python-систем core-python.

Система с ключом `言語: 日本語` (или `language: ja`) в manifest.yml: каждый её .py перед компиляцией проходит через
translate() — токены NAME из таблиц ниже заменяются английскими словами Python. Строки (и литеральные части
f-строк), комментарии и числа не трогаются. Замена — на месте: английское слово дополняется пробелами до той же
длины в байтах UTF-8, поэтому номера строк и столбцы (в байтах, как в code object) совпадают с исходным файлом,
а code object получает имя исходного файла — трассы указывают на исходную японскую строку.

Словарь и правила — docs/japanese-mode-vocabulary.md. Модуль не зависит от amazake/Java: проверяется и обычным
CPython 3.12+ (tokenize).
"""

import builtins as _builtins
import importlib.machinery as _machinery
import importlib.util as _util
import _imp
import io as _io
import os as _os
import sys as _sys
import tokenize as _tokenize
import unicodedata as _unicodedata

# ---------------------------------------------------------------------------------------------
# Словарь (docs/japanese-mode-vocabulary.md §2, §2.3, §3.3)
# ---------------------------------------------------------------------------------------------

#: §2 Ключевые слова (переводятся всегда и везде; зарезервированы).
KEYWORDS = {
    "関数": "def",
    "返す": "return",
    "もし": "if",
    "でなければもし": "elif",
    "でなければ": "else",
    "各": "for",
    "於": "in",
    "繰り返し": "while",
    "抜ける": "break",
    "飛ばす": "continue",
    "何もしない": "pass",
    "取り込む": "import",
    "から": "from",
    "として": "as",
    "型定義": "class",
    "試す": "try",
    "捕まえる": "except",
    "最後に": "finally",
    "投げる": "raise",
    "使って": "with",
    "ラムダ": "lambda",
    "生み出す": "yield",
    "大域": "global",
    "非局所": "nonlocal",
    "かつ": "and",
    "または": "or",
    "非": "not",
    "である": "is",
    "抹消": "del",
    "表明": "assert",
    "非同期": "async",
    "完了待ち": "await",
    "はい": "True",
    "いいえ": "False",
    "無し": "None",
    "場合分け": "match",
    "場合": "case",
}

#: §2.3 Dunder-имена (переводятся всюду, включая после точки).
DUNDERS = {
    "__初期化__": "__init__",
    "__文字列__": "__str__",
    "__表現__": "__repr__",
    "__等しい__": "__eq__",
    "__ハッシュ__": "__hash__",
    "__長さ__": "__len__",
    "__反復__": "__iter__",
    "__呼出__": "__call__",
    "__入る__": "__enter__",
    "__出る__": "__exit__",
    "__名前__": "__name__",
}

#: §3.3 Методы встроенных типов. Переводятся ВЕЗДЕ, как dunder-имена (после «.», в def/class, в присваиваниях, в
#: импорте, в именах аргументов): одно и то же слово во всех файлах системы — одно и то же английское имя, поэтому
#: `型定義 甲: 取得 = 5` и `甲.取得`, `関数 更新()` в модуле и `模.更新()` согласованы. Эти слова — английские имена
#: атрибутов; в строках (属性取得(x, "get"), ключи словарей) нужно английское слово.
METHODS = {
    "取得": "get",
    "キー一覧": "keys",
    "値一覧": "values",
    "項目一覧": "items",
    "既定設定": "setdefault",
    "更新": "update",
    "取り出す": "pop",
    "全消去": "clear",
    "複製": "copy",
    "追加": "append",
    "拡張": "extend",
    "挿入": "insert",
    "除去": "remove",
    "整列": "sort",
    "反転する": "reverse",
    "大文字": "upper",
    "小文字": "lower",
    "空白除去": "strip",
    "分割": "split",
    "連結": "join",
    "で始まる": "startswith",
    "で終わる": "endswith",
    "置き換え": "replace",
    "検索": "find",
    "書式化": "format",
    "数字か": "isdigit",
    "加える": "add",
    "数える": "count",
    "何番目": "index",
}

#: §3.1–3.2 Встроенные (кладутся в builtins контекста японской системы; не зарезервированы).
BUILTIN_NAMES = {
    "表示": "print", "長さ": "len", "範囲": "range", "文字列": "str", "整数": "int", "小数": "float", "真偽": "bool",
    "リスト": "list", "辞書": "dict", "集合": "set", "タプル": "tuple", "型か": "isinstance", "型": "type",
    "最小値": "min", "最大値": "max", "絶対値": "abs", "丸める": "round", "合計": "sum", "親型": "super",
    "基本型": "object", "静的メソッド": "staticmethod", "プロパティ": "property", "並べ替え": "sorted",
    "逆順": "reversed", "番号付け": "enumerate", "束ねる": "zip", "いずれか": "any", "すべて": "all",
    "属性取得": "getattr", "属性設定": "setattr", "属性あり": "hasattr", "写像": "map", "絞り込み": "filter",
    "反復子": "iter", "次の要素": "next", "文字": "chr", "文字コード": "ord", "表現": "repr", "累乗": "pow",
    "書式": "format", "呼べるか": "callable", "識別番号": "id", "型メソッド": "classmethod", "開く": "open",
    "例外": "Exception", "基底例外": "BaseException", "値エラー": "ValueError", "型エラー": "TypeError",
    "キーエラー": "KeyError", "添字エラー": "IndexError", "属性エラー": "AttributeError", "名前エラー": "NameError",
    "実行時エラー": "RuntimeError", "ゼロ除算エラー": "ZeroDivisionError", "未実装エラー": "NotImplementedError",
    "反復終了": "StopIteration", "表明エラー": "AssertionError",
}


def _check_tables():
    """Инвариант §1.1: японское слово не короче английского в байтах UTF-8, знаки — из BMP; таблицы не пересекаются."""
    seen = {}
    for table in (KEYWORDS, DUNDERS, METHODS):
        for ja, en in table.items():
            if len(ja.encode("utf-8")) < len(en):
                raise AssertionError("vocabulary: %r (%d bytes) is shorter than %r" % (ja, len(ja.encode("utf-8")), en))
            if any(ord(c) > 0xFFFF for c in ja) or not ja.isidentifier():
                raise AssertionError("vocabulary: %r is not a BMP identifier" % ja)
            if ja in seen:
                raise AssertionError("vocabulary: %r is in two tables" % ja)
            seen[ja] = en
    for ja in BUILTIN_NAMES:
        if ja in seen:
            raise AssertionError("vocabulary: builtin %r is reserved" % ja)


_check_tables()

#: Слова, которые зарезервированы всегда (подсказка «нужен пробел после ключевого слова»).
_RESERVED_BY_LENGTH = sorted(KEYWORDS, key=len, reverse=True)

_FULLWIDTH_EXTRA = {"\u3000": " ", "、": ",", "。": ".", "「": '"', "」": '"', "『": '"', "』": '"'}


# ---------------------------------------------------------------------------------------------
# Перевод
# ---------------------------------------------------------------------------------------------

def _lines(source):
    """Строки так, как их режет tokenize/compile — только по \\n (str.splitlines режет ещё по \\x0c, \\x85, U+2028…)."""
    return _io.StringIO(source).readlines()


def _lookup(s):
    """Английское слово для токена NAME (или None). Python нормализует имена по NFKC: ﾑｼ — то же, что 無し."""
    en = KEYWORDS.get(s) or DUNDERS.get(s) or METHODS.get(s)
    if en is None and not s.isascii():
        n = _unicodedata.normalize("NFKC", s)
        if n != s:
            en = KEYWORDS.get(n) or DUNDERS.get(n) or METHODS.get(n)
    return en


def _pad(ja, en):
    return en + " " * (len(ja.encode("utf-8")) - len(en))


def _bad_char(tok_string):
    """Первый знак токена NAME, которого не может быть в имени Python, и совет (или None)."""
    for i, ch in enumerate(tok_string):
        if (ch if i == 0 else "a" + ch).isidentifier():
            continue
        cp = ord(ch)
        if 0xFF01 <= cp <= 0xFF5E:
            return i, "full-width %r (U+%04X); use ASCII %r" % (ch, cp, chr(cp - 0xFEE0))
        if ch in _FULLWIDTH_EXTRA:
            return i, "full-width %r (U+%04X); use ASCII %r" % (ch, cp, _FULLWIDTH_EXTRA[ch])
        return i, "invalid character %r (U+%04X)" % (ch, cp)
    return None


def _syntax_error(msg, filename, lineno, col, line):
    return SyntaxError(msg, (filename, lineno, col + 1, line.rstrip("\r\n") + "\n" if line else None))


def _apply(lines, edits):
    out = list(lines)
    for row, items in edits.items():
        line = out[row - 1]
        for start, end, text in sorted(items, reverse=True):
            line = line[:start] + text + line[end:]
        out[row - 1] = line
    return "".join(out)


def _scan(source, lines, edits):
    """Проход по токенам: заполняет edits. Возвращает None или (строка, столбец, совет) — недопустимый знак."""
    NAME = _tokenize.NAME
    for tok in _tokenize.generate_tokens(_io.StringIO(source).readline):
        tt = tok.type
        if tt == NAME:
            s = tok.string
            en = _lookup(s)
            if en is not None:
                if len(s.encode("utf-8")) < len(en):
                    # Совместимая форма короче английского слова (например, ㍿-подобные знаки): не уложить на место.
                    return tok.start[0], tok.start[1], "write the keyword %r in its standard form" % s
                row, col = tok.start
                edits.setdefault(row, []).append((col, tok.end[1], _pad(s, en)))
            elif not s.isidentifier():
                bad = _bad_char(s)
                if bad is not None:
                    return tok.start[0], tok.start[1] + bad[0], bad[1]
            continue
        if tt == _tokenize.ERRORTOKEN and tok.string.strip():
            bad = _bad_char(tok.string) or (0, "invalid character %r" % tok.string[0])
            return tok.start[0], tok.start[1] + bad[0], bad[1]
    return None


def translate(source, filename="<string>"):
    """Японский исходник → английский исходник той же длины в байтах в каждой строке (§1.1).

    SyntaxError (по-английски, с исходной строкой): полноширинная пунктуация и прочие знаки, которых не бывает в
    коде Python; ошибки токенизации (незакрытая строка, отступы) — из компилятора по уже переведённой части.
    """
    if not source:
        return source
    if source[0] == "\ufeff":
        source = source[1:]     # BOM (decode_source убирает его сам; здесь — для прямых вызовов translate(str))
    lines = _lines(source)
    edits = {}
    try:
        bad = _scan(source, lines, edits)
    except (_tokenize.TokenError, SyntaxError) as err:
        partial = _apply(lines, edits)
        try:
            compile(partial, filename, "exec", dont_inherit=True)
        except SyntaxError as e:
            raise _fix_syntax_error(e, source, partial, filename) from None
        lineno, col = (err.args[1] if isinstance(err, _tokenize.TokenError) and len(err.args) > 1
                       else (getattr(err, "lineno", 1) or 1, (getattr(err, "offset", 1) or 1) - 1))
        line = lines[lineno - 1] if 1 <= lineno <= len(lines) else ""
        raise _syntax_error(str(err.args[0] if err.args else err), filename, lineno, col, line) from None
    if bad is not None:
        row, col, advice = bad
        raise _syntax_error("%s at line %d" % (advice, row), filename, row, col, lines[row - 1])
    return _apply(lines, edits) if edits else source


def _glued(name):
    """Ключевое слово, к которому без пробела приклеено продолжение (`もしx` → 'もし'), или None."""
    if name in KEYWORDS or name in DUNDERS:
        return None
    for kw in _RESERVED_BY_LENGTH:
        if len(name) > len(kw) and name.startswith(kw):
            return kw
    return None


def name_hint(name):
    """Подсказка к NameError японской системы: имя начинается с ключевого слова (`返す値`) — возможно, забыт пробел."""
    kw = _glued(str(name)) if name else None
    if kw is None:
        return None
    return "hint: if %r was meant as the keyword %r (%s), put a space after it" % (name, kw, KEYWORDS[kw])


def _hint(line, offset=None):
    """Подсказка §6.2 для SyntaxError: токен NAME начинается с ключевого слова (`もしx`) — забыт пробел.

    Только если такой токен стоит в начале оператора (первое имя строки) или рядом с местом ошибки (offset, с 1):
    обычные имена вроде 非表示 / 各プレイヤー в другой части строки подсказку не дают."""
    if not line:
        return None
    try:
        toks = list(_tokenize.generate_tokens(_io.StringIO(line if line.endswith("\n") else line + "\n").readline))
    except (_tokenize.TokenError, SyntaxError):
        return None
    toks = [t for t in toks
            if t.type not in (_tokenize.INDENT, _tokenize.DEDENT, _tokenize.NL, _tokenize.NEWLINE, _tokenize.COMMENT)]
    for i, tok in enumerate(toks):
        if tok.type != _tokenize.NAME:
            continue
        kw = _glued(tok.string)
        if kw is None:
            continue
        near = offset is not None and tok.start[1] - 1 <= offset - 1 <= tok.end[1] + 1
        # Первое имя оператора — но не цель присваивания / не объект атрибута (`各プレイヤー = …`, `非表示.x`).
        nxt = toks[i + 1].string if i + 1 < len(toks) and toks[i + 1].type == _tokenize.OP else ""
        target = nxt == "." or (nxt.endswith("=") and nxt not in ("==", "!=", "<=", ">="))
        if (i == 0 and not target) or near:
            return "hint: %r is a single name; put a space after the keyword %r (%s)" % (tok.string, kw, KEYWORDS[kw])
    return None


def _fix_syntax_error(e, source, translated, filename):
    """SyntaxError из переведённого текста → исходная строка в text, столбец по байтам, подсказка §6.2."""
    lineno = e.lineno
    if not lineno:
        return e
    src_lines = [ln.rstrip("\r\n") for ln in _lines(source)]
    tr_lines = [ln.rstrip("\r\n") for ln in _lines(translated)]
    if not (1 <= lineno <= len(src_lines)):
        return e
    original = src_lines[lineno - 1]
    offset = e.offset
    if offset and lineno <= len(tr_lines):
        # Столбец в знаках переведённой строки → байты → знаки исходной (байтовые смещения совпадают, §1.1).
        nbytes = len(tr_lines[lineno - 1][:max(0, offset - 1)].encode("utf-8"))
        acc = 0
        idx = 0
        for idx, ch in enumerate(original):
            if acc >= nbytes:
                break
            acc += len(ch.encode("utf-8"))
        else:
            idx = len(original)
        offset = idx + 1
    msg = e.msg or "invalid syntax"
    hint = _hint(original, offset)
    if hint and hint not in msg:
        msg = msg + " (" + hint + ")"
    return type(e)(msg, (filename, lineno, offset, original + "\n"))


def compile_ja(source, filename, mode="exec"):
    """compile() японского исходника: перевод + compile(..., filename) с исходной строкой в SyntaxError."""
    if isinstance(source, bytes):
        source = _util.decode_source(source)
    translated = translate(source, filename)
    try:
        return compile(translated, filename, mode, dont_inherit=True)
    except SyntaxError as e:
        raise _fix_syntax_error(e, source, translated, filename) from None


# ---------------------------------------------------------------------------------------------
# Импорт модулей японской системы и запуск главного файла
# ---------------------------------------------------------------------------------------------

class _JaLoader(_machinery.SourceFileLoader):
    """Загрузчик .py из папки японской системы: всегда из исходника, через перевод (без кэша байткода)."""

    def get_code(self, fullname):
        path = self.get_filename(fullname)
        return compile_ja(_util.decode_source(self.get_data(path)), path)

    def source_to_code(self, data, path, *, _optimize=-1):
        return compile_ja(_util.decode_source(data) if isinstance(data, bytes) else data, path)


def _is_frozen(name):
    try:
        return bool(_imp.is_frozen(name))
    except Exception:
        return False


class _JaFinder:
    """Модули, чей файл лежит в папке системы, — через _JaLoader; остальные (stdlib, amazake) — как обычно."""

    def __init__(self, folder):
        self.folder = _os.path.normcase(_os.path.abspath(folder))

    def _inside(self, path):
        p = _os.path.normcase(_os.path.abspath(path))
        return p == self.folder or p.startswith(self.folder + _os.sep)

    def find_spec(self, fullname, path=None, target=None):
        if fullname in _sys.builtin_module_names or _is_frozen(fullname):
            return None
        spec = _machinery.PathFinder.find_spec(fullname, path, target)
        if spec is None or not spec.origin or not spec.origin.endswith(".py") or not self._inside(spec.origin):
            return None
        loader = _JaLoader(fullname, spec.origin)
        new = _util.spec_from_file_location(fullname, spec.origin, loader=loader,
                                             submodule_search_locations=spec.submodule_search_locations)
        return new

    def invalidate_caches(self):
        pass


def install_builtins():
    """Японские имена встроенных (§3) — в builtins этого контекста (ссылки на те же объекты)."""
    for ja, en in BUILTIN_NAMES.items():
        if hasattr(_builtins, en):
            setattr(_builtins, ja, getattr(_builtins, en))
    # ジャバ例外: исключение Java (polyglot.ForeignException) и amazake.JavaError — `捕まえる ジャバ例外` ловит оба.
    amazake = _sys.modules.get("amazake")
    if amazake is not None and hasattr(amazake, "ジャバ例外"):
        _builtins.ジャバ例外 = amazake.ジャバ例外
    else:
        try:
            import polyglot
            fe = getattr(polyglot, "ForeignException", None)
            if fe is not None:
                _builtins.ジャバ例外 = fe
        except ImportError:
            pass


def install(folder):
    """Включить японский режим для модулей из папки folder (импорт-хук) и японские встроенные имена."""
    for f in _sys.meta_path:
        if isinstance(f, _JaFinder) and f.folder == _os.path.normcase(_os.path.abspath(folder)):
            break
    else:
        # После встроенных и замороженных импортёров: обычные правила затенения (.py системы не перекрывает sys и т.п.).
        i = 0
        for j, f in enumerate(_sys.meta_path):
            if f in (_machinery.BuiltinImporter, _machinery.FrozenImporter):
                i = j + 1
        _sys.meta_path.insert(i, _JaFinder(folder))
    install_builtins()


def run_main(folder, path):
    """Выполнить главный файл японской системы как __main__ (как обычный главный файл)."""
    install(folder)
    with open(path, "rb") as f:
        data = f.read()
    code = compile_ja(data, path)
    main = _sys.modules["__main__"]
    g = main.__dict__
    g.setdefault("__file__", path)
    exec(code, g)
