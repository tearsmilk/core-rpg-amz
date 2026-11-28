// Живая проверка: предметы в зонах gravzones на вакуумной карте (gravity спрашивает режим у gravzones через
// VacuumService.ZoneProvider). Нужен тестовый сервер с офлайн-входом (как в run.sh): gravity.jar, gravzones.jar и
// items.jar в module/, карта galaxy1 (homap create galaxy1 void — площадка 5×5 на y=64), бот zfbot в ops.json,
// консоль — fifo. Заранее:
//   module/gravity/config.yml — black-holes: galaxy1: abyss: {x: 0.5, y: 66, z: -30, horizon: 5, reach: 100, pull: 4, max-speed: 6}
//   module/gravzones/zones.json — {"version": 1, "zones": [
//     {"name": "home", "map": "galaxy1", "min": [-6, 40, -6], "max": [6, 90, 6], "g": 1, "created": 1},
//     {"name": "vac", "map": "galaxy1", "min": [6, 55, -20], "max": [16, 80, -12], "g": 12, "created": 2}]}
// Запуск: SRV=/путь/к/серверу PORT=25618 node zonefix.js
const mineflayer = require('mineflayer')
const fs = require('fs')
const { Vec3 } = require('vec3')

const SRV = process.env.SRV
const PORT = Number(process.env.PORT || 25618)
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log('[test]', ...a)
const results = []
const check = (name, ok, extra = '') => { results.push([name, ok]); log(ok ? 'OK  ' : 'FAIL', name, extra) }
const consoleCmd = c => fs.appendFileSync(SRV + '/console.fifo', c + '\n')
const HOLE = new Vec3(0.5, 66, -30)

const bot = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: 'zfbot', version: '1.21.11', auth: 'offline' })
bot.msgs = []
bot.on('messagestr', m => { bot.msgs.push(m); log('чат:', m) })
bot.on('kicked', r => log('кик:', JSON.stringify(r)))
bot.on('error', e => log('ошибка', e.message))
const collected = new Set()
bot._client.on('collect', p => collected.add(p.collectedEntityId))
const gone = new Set()
bot.on('entityGone', e => { if (e.name === 'item') gone.add(e.id) })

// гравитация бота — из атрибута (prismarine-physics его не знает); пока «держим» бота — без гравитации
const attrs = {}
function attrValue (a) {
  if (!a) return null
  let v = a.value
  const mods = a.modifiers || []
  for (const m of mods) if (m.operation === 0) v += m.amount
  const base = v
  for (const m of mods) if (m.operation === 1) v += base * m.amount
  for (const m of mods) if (m.operation === 2) v *= 1 + m.amount
  return v
}
const gravity = () => { const g = attrValue(attrs.gravity); return g == null ? 0.08 : g }
let hold = null
bot._client.on('entity_update_attributes', p => {
  if (!bot.entity || p.entityId !== bot.entity.id) return
  for (const prop of p.properties) attrs[String(prop.key).replace('minecraft:', '').replace('generic.', '')] = prop
  if (!hold) bot.physics.gravity = gravity()
})
bot.on('physicsTick', () => {
  if (hold) { bot.physics.gravity = 0; bot.entity.velocity = new Vec3(0, 0, 0); bot.entity.position = hold.clone() }
})
const pos = () => bot.entity.position
/** Перелёт шагами 0.5 бл. и «держать» бота в точке (без гравитации), пока не позовут moveTo снова. */
async function moveTo (x, y, z) {
  const dest = new Vec3(x, y, z)
  hold = null
  for (;;) {
    bot.physics.gravity = 0
    bot.entity.velocity = new Vec3(0, 0, 0)
    const v = dest.minus(pos()); const d = v.norm()
    if (d <= 0.5) break
    bot.entity.position = pos().plus(v.scaled(0.5 / d))
    await sleep(50)
  }
  hold = dest
  await sleep(300)
}

const items = () => Object.values(bot.entities).filter(e => e.name === 'item')
const mcItem = (name, n) => new (require('prismarine-item')(bot.registry))(bot.registry.itemsByName[name].id, n)
async function giveHand (name, n) {
  const item = mcItem(name, n)
  bot._client.write('set_creative_slot', { slot: 36, item: require('prismarine-item')(bot.registry).toNotch(item) })
  bot._setSlot(36, item)
  bot.setQuickBarSlot(0)
  await sleep(400)
}
async function waitFor (fn, ms = 4000, step = 50) {
  const t = Date.now()
  while (Date.now() - t < ms) { const v = fn(); if (v) return v; await sleep(step) }
  return null
}
/** Q в сторону точки look; возвращает новую сущность-предмет. */
async function toss (look) {
  await bot.lookAt(look, true)
  await sleep(200)
  const before = new Set(items().map(e => e.id))
  bot._client.write('block_dig', { status: 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
  await waitFor(() => items().some(e => !before.has(e.id)), 2000)
  return items().find(e => !before.has(e.id)) || null
}
// по id: mineflayer может пересоздать объект сущности при повторном спавне у клиента
const alive = e => !!e && !!bot.entities[e.id]
const at = e => (bot.entities[e.id] || e).position
const p3 = v => `(${v.x.toFixed(2)} ${v.y.toFixed(2)} ${v.z.toFixed(2)})`

/** Бросок внутри зоны «home» (сила 1): предмет падает на площадку и лежит, дыра его не тянет. */
// разные предметы — иначе соседние стопки сольются (merge модуля items) и сущность пропадёт
async function tossInZone (label, seconds, item) {
  await moveTo(-1.5, 65, 0.5)
  await giveHand(item, 16)
  const it = await toss(new Vec3(3, 64, 0.5))
  check(`${label}: Q в зоне — предмет появился`, !!it)
  if (!it) return null
  const p0 = at(it).clone()
  await sleep(600)
  await moveTo(-1.6, 65, -1.6)                          // отойти, чтобы не подобрать
  await sleep(1500)
  const p1 = at(it).clone()
  log(label, 'в зоне: старт', p3(p0), 'через 2 с', p3(p1))
  check(`${label}: в зоне предмет упал (гравитация зоны)`, p1.y < p0.y - 0.8 && p1.y > 64.5, `${p0.y.toFixed(2)} → ${p1.y.toFixed(2)}`)
  await sleep(seconds * 1000 - 2000)
  const p2 = at(it).clone()
  check(`${label}: в зоне предмет лежит ${seconds} с и не исчез`, alive(it) && !collected.has(it.id), p3(p2))
  check(`${label}: дыра его не тянет (z не уменьшился)`, Math.abs(p2.z - p1.z) < 0.3 && Math.abs(p2.y - p1.y) < 0.3, `${p3(p1)} → ${p3(p2)}`)
  return it
}

/**
 * Три замера через 0,8 с: в вакууме скорость почти постоянна (вниз не разгоняется), с гравитацией за 0,8 с предмет
 * набрал бы ~0,5 блока/тик вниз — второй отрезок был бы на несколько блоков круче первого.
 */
async function fall (it) {
  const a = at(it).clone(); await sleep(800)
  const b = at(it).clone(); await sleep(800)
  const c = at(it).clone()
  const dy1 = b.y - a.y; const dy2 = c.y - b.y
  return { ok: dy2 > dy1 - 0.5, text: `${p3(a)} → ${p3(b)} → ${p3(c)}, dy ${dy1.toFixed(2)}, ${dy2.toFixed(2)}`, start: a }
}

/** Бросок к дыре: висит (не падает), летит к дыре и поглощается за ms. */
async function tossToHole (label, from, ms) {
  await moveTo(from.x, from.y, from.z)
  await giveHand('stick', 16)
  await sleep(500)
  const it = await toss(HOLE.offset(0, -1.5, 0))    // в дыру (бросок добавляет +0,1 блока/тик вверх)
  check(`${label}: предмет появился`, !!it)
  if (!it) return
  const p0 = at(it).clone()
  const f = await fall(it)
  const p1 = at(it).clone()
  log(label, p3(p0), '→', p3(p1))
  check(`${label}: невесомость (вниз не разгоняется)`, f.ok, f.text)
  check(`${label}: летит к дыре`, p1.distanceTo(HOLE) < p0.distanceTo(HOLE) - 0.5, `${p0.distanceTo(HOLE).toFixed(1)} → ${p1.distanceTo(HOLE).toFixed(1)}`)
  const ok = await waitFor(() => !alive(it), ms, 200)
  check(`${label}: поглощён дырой`, !!ok && !collected.has(it.id), alive(it) ? p3(at(it)) : '')
}

;(async () => {
  await new Promise(r => bot.once('spawn', r))
  await sleep(1500)
  await new Promise(r => { bot.chat('/homap tp galaxy1'); setTimeout(r, 2500) })
  check('на galaxy1', Math.abs(pos().y - 65) < 3, p3(pos()))

  // ── 1. бросок в зоне силой 1 ──
  const lying = await tossInZone('зона 1', 15, 'diamond')

  // ── 2. бросок вне зон — вакуум карты, дыра ──
  await tossToHole('вне зон', new Vec3(0.5, 66, -13), 20000)

  // ── 3. бросок в вакуумной зоне 12 ──
  await tossToHole('зона 12', new Vec3(9, 66, -16), 20000)

  // ── 4. из зоны в вакуум: бросок у края зоны наружу ──
  await moveTo(0.5, 66, -5)
  await giveHand('stick', 16)
  await sleep(500)
  let it = await toss(HOLE)
  check('край зоны: предмет появился', !!it)
  if (it) {
    await sleep(300)
    const f = await fall(it)
    log('вылет из зоны', f.text)
    check('вылетел из зоны — вакуум подхватил (вниз не разгоняется)', f.start.z < -6 && f.ok, f.text)
    // поглощение тут не проверяем: в зоне предмет успевает набрать скорость вниз и может пройти мимо горизонта
    check('вылетел из зоны — летит дальше к дыре (зона его не держит)', alive(it) ? at(it).z < f.start.z - 3 : true,
      alive(it) ? p3(at(it)) : 'поглощён')
  }

  // ── 5. из вакуума в зону: бросок снаружи внутрь — в зоне начинает падать ──
  await moveTo(0.5, 67, -9)
  await giveHand('apple', 16)
  await sleep(500)
  it = await toss(new Vec3(0.5, 68.6, 0))
  check('в зону снаружи: предмет появился', !!it)
  if (it) {
    // в вакууме он пролетел бы зону насквозь (z до +6 и дальше, y растёт); в зоне — падает на площадку
    let maxZ = -99
    const t = Date.now()
    while (Date.now() - t < 4000) { maxZ = Math.max(maxZ, at(it).z); await sleep(50) }
    const p1 = at(it).clone()
    log('влёт в зону: через 4 с', p3(p1), 'max z', maxZ.toFixed(2))
    check('влетел в зону — упал на площадку (гравитация зоны), не пролетел насквозь', alive(it) && p1.y < 65.5 &&
      Math.abs(p1.z - 0.5) < 3 && maxZ < 4, p3(p1))
  }

  // ── 6. перезагрузки ──
  const other = { gravzones: 'emerald', gravity: 'gold_ingot' }
  for (const m of ['gravzones', 'gravity']) {
    consoleCmd(`homodule reload ${m}`)
    await sleep(3000)
    check(`reload ${m}: лежавший в зоне предмет на месте`, alive(lying) && Math.abs(at(lying).z - 0.5) < 1.5, lying && p3(at(lying)))
    await tossInZone(`после reload ${m}`, 6, other[m])
  }

  // ── 7. выгрузка gravzones: вакуум карты снова и в бывшей зоне — предмет тянет к дыре ──
  if (lying && alive(lying)) {
    const z0 = at(lying).z
    consoleCmd('homodule unload gravzones')
    await sleep(4000)
    check('unload gravzones — предмет в бывшей зоне подхватил вакуум', !alive(lying) || at(lying).z < z0 - 0.5, alive(lying) ? p3(at(lying)) : 'поглощён')
    consoleCmd('homodule load gravzones.jar')
    await sleep(3000)
    await tossInZone('после load gravzones', 6, 'iron_ingot')
  }

  const log2 = fs.readFileSync(SRV + '/server.log', 'utf8')
  check('в логе нет WARN/ERROR/Exception', !/ WARN | ERROR |Exception/.test(log2))
  bot.quit()
  const failed = results.filter(r => !r[1])
  log(`итог: ${results.length - failed.length}/${results.length}`)
  process.exit(failed.length ? 1 : 0)
})().catch(e => { console.error(e); process.exit(2) })
