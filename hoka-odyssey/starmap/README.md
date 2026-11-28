# starmap (сервер) — данные для карты-радара

Пара клиентскому модулю `starmap` (`client-modules/modules/starmap`, клавиша **M** у игрока с модом). Пока у игрока
открыта карта, модуль раз в `period-ticks` (10 тиков — 2 раза в секунду) шлёт ему:

- **корабли** его карты (мира) из модуля `ships` — id, название (чертёж), владелец, «мой?», «летит?», «я пилот?»,
  x, y, z, курс. У `ships` нет сервиса, поэтому поля читаются рефлексией (как устроены в ships 0.1.0 на живом
  сервере: `ShipsModule.ships`, `Ship.state/map/owner/...`, `Flight.pose()`); не нашлись — одно предупреждение в лог,
  дальше только игроки;
- **игроков** на той же карте ближе `player-radius` (2000 блоков), кроме самого игрока.

Без `clientmods` модуль ничего не делает (карту не открыть), без `ships` — шлёт только игроков.
Протокол (канал `starmap`, big-endian) — в `client-modules/modules/starmap/README.md` и `StarmapProtocol.java`.

`module.yml`: `softdepends: [clientmods, ships]`. Настройки — `module/starmap/config.yml` (комментарии внутри):
`period-ticks`, `player-radius`, `ship-radius` (0 — все), `show-players`, `show-ships`, `show-stowed`, `max-contacts`,
`open-timeout-seconds`. Перечитать — `homodule reload starmap`.

Ввод проверяется: от клиента принимаются только op 1 (`u8 open`) и op 2 (не чаще раза в секунду), всё остальное
отбрасывается; строки в ответе обрезаются до 48 символов, числа — только конечные. В `onDisable` снимаются подписка
clientmods, задача и списки игроков.
