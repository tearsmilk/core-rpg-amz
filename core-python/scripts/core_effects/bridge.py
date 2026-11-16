# 橋 — байты класса-моста PlaceholderAPI (чистый Python, без amazake: проверяется в tests_core_effects.py).
#
# Почему так. Класс Python, унаследованный от PlaceholderExpansion, GraalPy создать не может: адаптер нужен в
# загрузчике, который видит и PlaceholderAPI, и классы GraalPy, а такого нет («Could not determine a class loader that
# can see all types»). Java-система core-effects решала это так же: мост CoreEffectsExpansion определялся в
# загрузчике-потомке загрузчика PlaceholderAPI. Здесь байты того же по смыслу класса собираются этим модулем (версия
# class-файла 52, со StackMapTable), а main.py определяет его в URLClassLoader(родитель — загрузчик PlaceholderAPI).
#
# Отличие от моста Java-системы ради GIL: onRequest не зовёт Python. Ответы лежат в java.util.Map
# (ConcurrentHashMap: «UUID игрока» → Map<параметр, ответ>, «» → ответы по умолчанию и <эффект>_name), их готовит
# поток игрока. Запрос MythicHUD (асинхронно, часто) — два Map.get в Java, без входа в Python.
#
#   public final class CoreEffectsPyExpansion extends PlaceholderExpansion {
#       private final String identifier, author, version;
#       private final Map tables;
#       public CoreEffectsPyExpansion(String identifier, String author, String version, Map tables) {...}
#       public String getIdentifier() / getAuthor() / getVersion();
#       public boolean persist() { return true; }       // не выгружать при /papi reload
#       public boolean canRegister() { return true; }
#       public String onRequest(OfflinePlayer p, String params) {
#           if (p == null) return null;
#           String k = params.toLowerCase(Locale.ROOT);
#           Map t = (Map) tables.get(p.getUniqueId().toString());
#           if (t != null) { String v = (String) t.get(k); if (v != null) return v; }
#           Map d = (Map) tables.get("");
#           if (d == null) return null;
#           return (String) d.get(k);
#       }
#   }

import struct

名前 = "core.amazake.systems.coreeffects.pybridge.CoreEffectsPyExpansion"
_内名 = 名前.replace(".", "/")
_親 = "me/clip/placeholderapi/expansion/PlaceholderExpansion"


class _定数:
    """Пул констант class-файла."""

    def __init__(self):
        self.項 = []
        self.索 = {}

    def _足す(self, 鍵, 生):
        if 鍵 in self.索:
            return self.索[鍵]
        self.項.append(生)
        i = len(self.項)
        self.索[鍵] = i
        return i

    def utf8(self, s):
        b = s.encode("utf-8")
        return self._足す(("u", s), struct.pack(">BH", 1, len(b)) + b)

    def cls(self, n):
        return self._足す(("c", n), struct.pack(">BH", 7, self.utf8(n)))

    def string(self, s):
        return self._足す(("s", s), struct.pack(">BH", 8, self.utf8(s)))

    def nat(self, n, d):
        return self._足す(("n", n, d), struct.pack(">BHH", 12, self.utf8(n), self.utf8(d)))

    def _ref(self, tag, c, n, d):
        return self._足す((tag, c, n, d), struct.pack(">BHH", tag, self.cls(c), self.nat(n, d)))

    def field(self, c, n, d):
        return self._ref(9, c, n, d)

    def method(self, c, n, d):
        return self._ref(10, c, n, d)

    def imethod(self, c, n, d):
        return self._ref(11, c, n, d)

    def 書く(self):
        return struct.pack(">H", len(self.項) + 1) + b"".join(self.項)


def _コード(cp, 最大スタック, 最大局所, 命令, 枠=b""):
    """Атрибут Code (без таблицы исключений); 枠 — тело StackMapTable или b""."""
    属 = b""
    属数 = 0
    if 枠:
        属 = struct.pack(">HI", cp.utf8("StackMapTable"), len(枠)) + 枠
        属数 = 1
    本体 = struct.pack(">HHI", 最大スタック, 最大局所, len(命令)) + 命令 + struct.pack(">H", 0) + struct.pack(">H", 属数) + 属
    return struct.pack(">HI", cp.utf8("Code"), len(本体)) + 本体


def _メソッド(cp, 修飾, 名, 記述, code):
    return struct.pack(">HHHH", 修飾, cp.utf8(名), cp.utf8(記述), 1) + code


def 作る():
    """Байты class-файла моста."""
    cp = _定数()
    this = cp.cls(_内名)
    sup = cp.cls(_親)
    S = "Ljava/lang/String;"
    M = "Ljava/util/Map;"
    f_id = cp.field(_内名, "identifier", S)
    f_au = cp.field(_内名, "author", S)
    f_ve = cp.field(_内名, "version", S)
    f_ta = cp.field(_内名, "tables", M)
    super_init = cp.method(_親, "<init>", "()V")
    root = cp.field("java/util/Locale", "ROOT", "Ljava/util/Locale;")
    lower = cp.method("java/lang/String", "toLowerCase", "(Ljava/util/Locale;)Ljava/lang/String;")
    uuid = cp.imethod("org/bukkit/OfflinePlayer", "getUniqueId", "()Ljava/util/UUID;")
    uuid_s = cp.method("java/util/UUID", "toString", "()Ljava/lang/String;")
    get = cp.imethod("java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;")
    c_map = cp.cls("java/util/Map")
    c_str = cp.cls("java/lang/String")
    empty = cp.string("")

    H = lambda op, i: struct.pack(">BH", op, i)
    INV = lambda i, n: struct.pack(">BHBB", 0xB9, i, n, 0)
    ALOAD0, ALOAD1, ALOAD2, ALOAD3 = b"\x2a", b"\x2b", b"\x2c", b"\x2d"
    ARETURN, ACONST_NULL, RETURN = b"\xb0", b"\x01", b"\xb1"
    ALOAD = lambda n: bytes([0x19, n])
    ASTORE = lambda n: bytes([0x3A, n])

    メソッド = []

    # <init>(String, String, String, Map)
    命 = (ALOAD0 + H(0xB7, super_init)
         + ALOAD0 + ALOAD1 + H(0xB5, f_id)
         + ALOAD0 + ALOAD2 + H(0xB5, f_au)
         + ALOAD0 + ALOAD3 + H(0xB5, f_ve)
         + ALOAD0 + ALOAD(4) + H(0xB5, f_ta)
         + RETURN)
    メソッド.append(_メソッド(cp, 0x0001, "<init>", "(" + S + S + S + M + ")V", _コード(cp, 2, 5, 命)))

    for 名, f in (("getIdentifier", f_id), ("getAuthor", f_au), ("getVersion", f_ve)):
        メソッド.append(_メソッド(cp, 0x0001, 名, "()" + S, _コード(cp, 1, 1, ALOAD0 + H(0xB4, f) + ARETURN)))
    for 名 in ("persist", "canRegister"):
        メソッド.append(_メソッド(cp, 0x0001, 名, "()Z", _コード(cp, 1, 1, b"\x04\xac")))   # iconst_1; ireturn

    # onRequest(OfflinePlayer, String): метки считаются по ходу сборки.
    部 = []          # (байты | ("jump", op, метка))
    印 = {}

    def 置(b):
        部.append(b)

    def 跳(op, ラベル):
        部.append(("j", op, ラベル))

    def 札(ラベル):
        部.append(("l", ラベル))

    置(ALOAD1)
    跳(0xC7, "A")                    # ifnonnull A
    置(ACONST_NULL + ARETURN)
    札("A")
    置(ALOAD2 + H(0xB2, root) + H(0xB6, lower) + ASTORE(3))
    置(ALOAD0 + H(0xB4, f_ta) + ALOAD1 + INV(uuid, 1) + H(0xB6, uuid_s) + INV(get, 2) + H(0xC0, c_map) + ASTORE(4))
    置(ALOAD(4))
    跳(0xC6, "B")                    # ifnull B
    置(ALOAD(4) + ALOAD3 + INV(get, 2) + H(0xC0, c_str) + ASTORE(5))
    置(ALOAD(5))
    跳(0xC6, "B")                    # ifnull B
    置(ALOAD(5) + ARETURN)
    札("B")
    置(ALOAD0 + H(0xB4, f_ta) + H(0x13, empty) + INV(get, 2) + H(0xC0, c_map) + ASTORE(4))
    置(ALOAD(4))
    跳(0xC7, "C")                    # ifnonnull C
    置(ACONST_NULL + ARETURN)
    札("C")
    置(ALOAD(4) + ALOAD3 + INV(get, 2) + H(0xC0, c_str) + ARETURN)

    位 = 0
    for p in 部:
        if isinstance(p, tuple):
            if p[0] == "l":
                印[p[1]] = 位
            else:
                位 += 3
        else:
            位 += len(p)
    命 = b""
    for p in 部:
        if isinstance(p, tuple):
            if p[0] == "j":
                命 += struct.pack(">Bh", p[1], 印[p[2]] - len(命))
        else:
            命 += p
    # StackMapTable: A — same_frame; B — append_frame(String k); C — append_frame(Map t).
    a, b, c = 印["A"], 印["B"], 印["C"]
    枠 = struct.pack(">H", 3)
    枠 += bytes([a])                                              # same_frame (delta = a < 64)
    枠 += struct.pack(">BHBH", 252, b - a - 1, 7, c_str)          # append_frame, 1 local: String
    枠 += struct.pack(">BHBH", 252, c - b - 1, 7, c_map)          # append_frame, 1 local: Map
    assert a < 64
    メソッド.append(_メソッド(cp, 0x0001, "onRequest", "(Lorg/bukkit/OfflinePlayer;" + S + ")" + S,
                          _コード(cp, 3, 6, 命, 枠)))

    フィールド = b""
    for 名, d in (("identifier", S), ("author", S), ("version", S), ("tables", M)):
        フィールド += struct.pack(">HHHH", 0x0012, cp.utf8(名), cp.utf8(d), 0)   # private final

    体 = (struct.pack(">HHHH", 0x0031, this, sup, 0)                     # public final super; no interfaces
          + struct.pack(">H", 4) + フィールド
          + struct.pack(">H", len(メソッド)) + b"".join(メソッド)
          + struct.pack(">H", 0))
    return struct.pack(">IHH", 0xCAFEBABE, 0, 52) + cp.書く() + 体
