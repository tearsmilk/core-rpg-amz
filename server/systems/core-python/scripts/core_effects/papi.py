# プレースホルダー — расширение PlaceholderAPI %coreeffects_…% (порт papi/PapiHook.java + мост CoreEffectsExpansion).
#
# Класс Python, унаследованный от PlaceholderExpansion, GraalPy не создаёт (адаптеру нужен загрузчик, видящий и
# PlaceholderAPI, и GraalPy). Как у Java-системы: класс-мост определяется в загрузчике-потомке загрузчика
# PlaceholderAPI (URLClassLoader над папкой данных системы; байты собирает bridge.py). Как и у Java-системы:
#   * не регистрируемся поверх чужого расширения с тем же идентификатором (LocalExpansionManager.getExpansion);
#   * снимая своё, не трогаем чужое, если наше уже вытеснили;
#   * persist() = true (не выгружается при /papi reload).
# onRequest — чистая Java: ответы берёт из 表 (ConcurrentHashMap), Python и его GIL не трогает.

import amazake

PAPI = "PlaceholderAPI"

_状態 = {"拡張": None, "識別子": None, "papi": None}


def _持ち主(papi, 識別子):
    """(известно ли, кто держит идентификатор, расширение или None)."""
    try:
        mgr = papi.getLocalExpansionManager()
        if mgr is None:
            return False, None
        return True, mgr.getExpansion(識別子)
    except Exception as e:
        amazake.log.debug("PlaceholderAPI: getExpansion недоступен: " + str(e))
        return False, None


_橋 = {"型": None, "papi": None, "根": None}


def 準備():
    """Записать class-файл моста в папку данных (вне тиков: вызывается при загрузке модуля, асинхронный поток)."""
    import os
    import bridge
    根 = os.path.join(str(amazake.data_folder()), "papi-bridge")
    路 = os.path.join(根, *bridge.名前.split(".")) + ".class"
    生 = bridge.作る()
    try:
        with open(路, "rb") as f:
            同じ = f.read() == 生
    except OSError:
        同じ = False
    if not 同じ:
        os.makedirs(os.path.dirname(路), exist_ok=True)
        with open(路, "wb") as f:
            f.write(生)
    _橋["根"] = 根


def _橋の型(papi):
    """Класс моста в загрузчике-потомке загрузчика PlaceholderAPI (новый загрузчик — на каждый PlaceholderAPI)."""
    if _橋["型"] is not None and _橋["papi"] is papi:
        return _橋["型"]
    import os
    import java
    import bridge
    if _橋["根"] is None:
        raise RuntimeError("нет class-файла моста")
    urls = java.type("java.net.URL[]")(1)
    urls[0] = java.type("java.io.File")(_橋["根"] + os.sep).toURI().toURL()
    載 = java.type("java.net.URLClassLoader")(urls, papi.getClass().getClassLoader())
    型 = java.type("java.lang.Class").forName(bridge.名前, True, 載)
    _橋.update(型=型, papi=papi)
    return 型


def 登録(識別子, 版, 表):
    """Зарегистрировать %<識別子>_…%; прежнее снимается. 表 — java.util.Map ответов (см. bridge.py). True — принято."""
    解除()
    papi = amazake.Bukkit.getPluginManager().getPlugin(PAPI)
    if papi is None or not papi.isEnabled():
        return False
    既知, 持 = _持ち主(papi, 識別子)
    if 既知 and 持 is not None:
        amazake.log.warn("Идентификатор PlaceholderAPI «" + 識別子 + "» уже занят другим расширением — "
                         "плейсхолдеры %" + 識別子 + "_…% core_effects не зарегистрированы. "
                         "Смените hud.placeholderapi-identifier в config.yml.")
        return False
    try:
        import java
        型 = _橋の型(papi)
        S = java.type("java.lang.String")
        M = java.type("java.util.Map")
        型s = java.type("java.lang.Class[]")(4)
        for i, t in enumerate((S, S, S, M)):
            型s[i] = getattr(t, "class")
        値 = java.type("java.lang.Object[]")(4)
        値[0] = 識別子
        値[1] = "tearsmilk"
        値[2] = 版
        値[3] = 表
        拡張 = 型.getConstructor(型s).newInstance(値)
        if not 拡張.register():
            amazake.log.warn("PlaceholderAPI не принял расширение «" + 識別子 + "». "
                             "Смените hud.placeholderapi-identifier в config.yml.")
            return False
    except Exception as e:
        amazake.log.warn("Не удалось зарегистрировать плейсхолдеры в PlaceholderAPI: " + str(e))
        return False
    _状態.update(拡張=拡張, 識別子=識別子, papi=papi)
    amazake.log.info("Плейсхолдеры PlaceholderAPI зарегистрированы: %" + 識別子 + "_…%.")
    return True


def 解除():
    """Снять своё расширение (если есть). Чужое под нашим идентификатором не трогаем — только забываем своё."""
    拡張 = _状態["拡張"]
    papi = _状態["papi"]
    識別子 = _状態["識別子"]
    _状態.update(拡張=None, 識別子=None, papi=None)
    if 拡張 is None:
        return
    既知, 持 = (False, None) if papi is None or 識別子 is None else _持ち主(papi, 識別子)
    if not 既知 or 持 is 拡張 or (持 is not None and 持.equals(拡張)):
        # unregister() шлёт ExpansionUnregisterEvent — только в глобальном потоке. Из другого (выгрузка через
        # /python, асинхронно) — чистой Java-задачей там: Python к тому времени может быть уже закрыт.
        try:
            import common
            if not common.ジャバで全体へ(common.ジャバ処理(_橋["型"] or 拡張.getClass(), "unregister", [], 拡張)):
                amazake.log.warn("PlaceholderAPI: не удалось снять расширение «" + 識別子 + "»")
        except Exception as e:
            amazake.log.warn("PlaceholderAPI: unregister: " + str(e))
