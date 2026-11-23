// Модуль starmap (сервер): данные для карты-радара клиентского модуля starmap — корабли и игроки рядом.
// Классов clientmods и ships при сборке нет — только рефлексия (softdepends), поэтому зависимостей кроме API нет.
tasks.jar { archiveFileName.set("starmap.jar") }
