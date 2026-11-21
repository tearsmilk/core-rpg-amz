// Живая проверка gravzones ботом mineflayer. Нужен тестовый сервер с офлайн-входом (как в run.sh): gravity.jar и
// gravzones.jar в module/, карта galaxy1 (homap create galaxy1 void), бот zbot в ops.json, консоль — fifo.
// Запуск: SRV=/путь/к/серверу PORT=25613 node gravzones.js
// prismarine-physics не знает атрибутов gravity/jump_strength — эмулируем ванильный клиент: гравитацию бота берём из
// атрибута, прыжок — скорость vy = jump_strength из атрибута.
const mineflayer = require('mineflayer')
const fs = require('fs')
const { Vec3 } = require('vec3')

const SRV = process.env.SRV
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log('[test]', ...a)
const results = []
const check = (name, ok, extra = '') => { results.push([name, ok]); log(ok ? 'OK  ' : 'FAIL', name, extra) }
const consoleCmd = c => fs.appendFileSync(SRV + '/console.fifo', c + '\n')

const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.PORT || 25613), username: 'zbot', version: '1.21.11', auth: 'offline' })
bot.msgs = []
bot.bars = []
bot.on('messagestr', (m, pos) => { if (pos === 'game_info') { bot.bars.push(m); log('бар:', m) } else { bot.msgs.push(m); log('чат:', m) } })
// Minestom шлёт action bar пакетом set_action_bar_text (NBT-текст) — mineflayer его не разбирает: достаём строки сами
const nbtText = t => { const out = []; const walk = v => { if (typeof v === 'string') out.push(v); else if (v && typeof v === 'object') Object.values(v).forEach(walk) }; walk(t); return out.filter(x => !['string', 'compound', 'byte', 'list', 'text', 'gold', 'yellow', 'red', 'aqua', 'green'].includes(x)).join('') }
bot._client.on('action_bar', p => { const s = nbtText(p.text); bot.bars.push(s); log('бар:', s, '| бот y =', bot.entity && bot.entity.position.y.toFixed(2)) })
bot.on('kicked', r => log('кик:', JSON.stringify(r)))
bot.on('error', e => log('ошибка', e.message))

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
let explosions = 0
let moving = false
bot._client.on('explosion', () => { explosions++ })
bot._client.on('entity_update_attributes', p => {
  if (!bot.entity || p.entityId !== bot.entity.id) return
  for (const prop of p.properties) {
    const k = String(prop.key).replace('minecraft:', '').replace('generic.', '').replace('player.', '')
    attrs[k] = prop
  }
  if (!moving) bot.physics.gravity = gravity()
  log('атрибуты: g =', +gravity().toFixed(5), 'jump =', +jumpStrength().toFixed(4), 'safe_fall =', +(attrValue(attrs.safe_fall_distance) ?? 3).toFixed(2))
})
const gravity = () => { const g = attrValue(attrs.gravity); return g == null ? 0.08 : g }
const jumpStrength = () => { const j = attrValue(attrs.jump_strength); return j == null ? 0.42 : j }
const near = (a, b, eps = 1e-6) => Math.abs(a - b) < eps
const y = () => bot.entity.position.y
const pos = () => bot.entity.position
const waitMsg = async (re, ms = 4000, list = bot.msgs) => {
  const t = Date.now()
  while (Date.now() - t < ms) { const m = list.find(x => re.test(x)); if (m) return m; await sleep(100) }
  return null
}
const clearMsgs = () => { bot.msgs.length = 0; bot.bars.length = 0 }
async function cmd (c, re, ms) { clearMsgs(); bot.chat(c); return re ? waitMsg(re, ms) : null }

/** Перелёт по шагам 0.5 бл. без гравитации (как flyTo), потом гравитация из атрибута. */
async function moveTo (x, yy, z) {
  moving = true
  const dest = new Vec3(x, yy, z)
  for (;;) {
    bot.physics.gravity = 0
    bot.entity.velocity = new Vec3(0, 0, 0)
    const v = dest.minus(pos()); const d = v.norm()
    if (d <= 0.5) break
    bot.entity.position = pos().plus(v.scaled(0.5 / d))
    await sleep(50)
  }
  bot.entity.position = dest
  await sleep(150)
  moving = false
  bot.physics.gravity = gravity()
}
async function land (ms = 6000) {
  const t = Date.now()
  while (!bot.entity.onGround && Date.now() - t < ms) await sleep(50)
  await sleep(300)
  return bot.entity.onGround
}
/** Прыжок как у ванильного клиента: vy = jump_strength. {h — высота, s — секунд в воздухе}. */
async function jump () {
  await land()
  const y0 = y(); let top = y0; let ticks = 0
  bot.entity.velocity.y = jumpStrength()
  bot.entity.onGround = false
  await new Promise(resolve => {
    const f = () => {
      ticks++; top = Math.max(top, y())
      if ((ticks > 2 && bot.entity.onGround) || ticks > 400) { bot.removeListener('physicsTick', f); resolve() }
    }
    bot.on('physicsTick', f)
  })
  return { h: top - y0, s: ticks / 20 }
}
async function rate (ms) { const e0 = explosions; await sleep(ms); return (explosions - e0) / (ms / 1000) }

;(async () => {
  await new Promise(r => bot.once('spawn', r))
  await sleep(1500)
  const sp = pos().clone()
  const bx = Math.floor(sp.x); const bz = Math.floor(sp.z); const gy = Math.floor(sp.y)   // gy — блок у ног
  log('спавн', sp, 'земля y =', gy - 1)
  check('на world гравитация обычная', near(gravity(), 0.08), gravity())

  // ── топорик ──
  check('/hozone — подсказки', !!(await cmd('/hozone', /hozone create <имя> <сила>/)))
  await cmd('/hozone axe', /Топорик выдан/)
  await sleep(500)
  const axe = bot.inventory.items().find(i => i.name === 'wooden_axe')
  check('топорик в инвентаре', !!axe, axe && JSON.stringify(axe.customName || axe.components?.map(c => c.type)))
  await bot.equip(axe, 'hand')
  await sleep(300)
  bot.game.gameMode = 'survival'   // mineflayer в креативе игнорирует отдачу взрыва, ванильный клиент — нет
  bot.on('game', () => { bot.game.gameMode = 'survival' })

  const b1 = new Vec3(bx - 3, gy - 1, bz - 3); const b2 = new Vec3(bx + 3, gy - 1, bz + 3)
  clearMsgs()
  bot.dig(bot.blockAt(b1), true).catch(() => {})
  const bar1 = await waitMsg(/Точка 1:/, 3000, bot.bars)
  check('ЛКМ по блоку — точка 1 в action bar', !!bar1 && bar1.includes(`${b1.x} ${b1.y} ${b1.z}`), bar1)
  await sleep(800)
  bot.stopDigging()
  log('у бота локально блок', bot.blockAt(b1).name)
  clearMsgs()
  await bot.activateBlock(bot.blockAt(b2)).catch(e => log('activate', e.message))
  const bar2 = await waitMsg(/Точка 2:/, 3000, bot.bars)
  check('ПКМ по блоку — точка 2 и объём 49 (7×1×7)', !!bar2 && bar2.includes(`${b2.x} ${b2.y} ${b2.z}`) && /объём 49 /.test(bar2), bar2)
  await sleep(500)
  check('ПКМ — блок не поставлен рядом', bot.blockAt(b2.offset(0, 1, 0)).name === 'air', bot.blockAt(b2.offset(0, 1, 0)).name)

  // ── обычный прыжок вне зон ──
  const j1 = await jump()
  log('прыжок вне зон', j1)
  check('вне зон прыжок обычный (1.25 бл.)', Math.abs(j1.h - 1.25) < 0.1, j1.h.toFixed(2))

  // ── зона 0.1 (луна) вокруг спавна ──
  await moveTo(bx + 3.5, gy + 9.2, bz + 3.5)
  check('/hozone pos2 — точка 2 в воздухе, объём 7×11×7', !!(await waitMsg(/объём 539 /, 3000, (await cmd('/hozone pos2'), bot.bars))))
  const created = await cmd('/hozone create moon 0.1', /Зона «moon» создана/)
  check('/hozone create moon 0.1', !!created, created)
  await moveTo(bx + 0.5, gy + 0.2, bz + 0.5)
  await sleep(500)
  check('в зоне 0.1: атрибут гравитации 0.008', near(gravity(), 0.008), gravity())
  check('в зоне 0.1: сила прыжка 0.42×0.1^0.1', near(jumpStrength(), 0.42 * Math.pow(0.1, 0.1), 1e-4), jumpStrength())
  const j2 = await jump()
  log('прыжок в зоне 0.1', j2)
  check('в зоне 0.1 прыгает выше (> 3.5 бл.)', j2.h > 3.5, j2.h.toFixed(2))
  check('в зоне 0.1 дольше висит (> 2.5 с, обычно 0.6)', j2.s > 2.5, j2.s)

  // ── зона 10 ──
  await moveTo(bx + 17.5, gy + 0.2, bz - 2.5); await cmd('/hozone pos1')
  await moveTo(bx + 23.5, gy + 8.2, bz + 3.5); await cmd('/hozone pos2')
  check('/hozone create heavy 10', !!(await cmd('/hozone create heavy 10', /Зона «heavy» создана/)))
  await moveTo(bx + 20.5, gy + 0.2, bz + 0.5)
  await sleep(500)
  check('в зоне 10: гравитация 0.8', near(gravity(), 0.8), gravity())
  const j3 = await jump()
  log('прыжок в зоне 10', j3)
  check('в зоне 10 прыжок ниже (< 1 бл.) и короче', j3.h < 1.0 && j3.h > 0.5 && j3.s < j1.s, `${j3.h.toFixed(2)} бл. ${j3.s} с`)
  await moveTo(bx + 28.5, gy + 0.2, bz + 0.5)
  await sleep(500)
  check('вышел из зоны 10 — гравитация обычная', near(gravity(), 0.08), gravity())

  // ── зона 12: выброс и невесомость ──
  await moveTo(bx + 37.5, gy + 0.2, bz - 2.5); await cmd('/hozone pos1')
  await moveTo(bx + 43.5, gy + 40.2, bz + 3.5); await cmd('/hozone pos2')
  check('/hozone create vac 12', !!(await cmd('/hozone create vac 12', /Зона «vac» создана/)))
  await moveTo(bx + 33.5, gy + 0.2, bz + 0.5)
  await land()
  const e0 = explosions
  const yv0 = y()
  // заходим пешком по земле
  moving = true
  for (let x = bx + 33.5; x <= bx + 38; x += 0.25) { bot.entity.position = new Vec3(x, yv0, bz + 0.5); await sleep(50) }
  moving = false
  bot.physics.gravity = gravity()
  await sleep(2500)
  const yv1 = y()
  log('вакуум: y', yv0.toFixed(2), '->', yv1.toFixed(2), 'g', gravity(), 'толчков', explosions - e0)
  check('в зоне 12 — выброс вверх (> 4 бл. за 2.5 с)', yv1 > yv0 + 4, (yv1 - yv0).toFixed(2))
  check('в зоне 12 — атрибут гравитации 0 (вакуум gravity)', gravity() === 0, gravity())
  await sleep(1500)
  const yv2 = y(); await sleep(3000); const yv3 = y()
  log('вакуум: висит y', yv2.toFixed(2), '->', yv3.toFixed(2))
  check('в зоне 12 после выброса висит (не падает 3 с)', Math.abs(yv3 - yv2) < 0.6 && yv3 > yv0 + 3, (yv3 - yv2).toFixed(3))
  const r = await rate(2000)
  check('толчков не больше 20/с (без дублей)', r <= 21, r)
  check('/hozone list — в зоне vac', !!(await cmd('/hozone list', /Ты сейчас в зоне «vac»/)))
  // выход вбок — невесомость держится linger 3 с, потом падает
  await moveTo(bx + 50.5, yv3, bz + 0.5)
  bot.physics.gravity = gravity()
  await sleep(1000)
  check('вышел из зоны 12 — ещё 3 с невесомость', gravity() === 0, gravity())
  await sleep(3500)
  const yf = y()
  check('через 3 с после выхода — обычная гравитация и падает', near(gravity(), 0.08) && yf < yv3 - 2, `g=${gravity()} y ${yv3.toFixed(1)} -> ${yf.toFixed(1)}`)
  await land()

  // ── list / info ──
  const lst = await cmd('/hozone list', /Ты сейчас вне зон/)
  check('/hozone list — 3 зоны, ты вне зон', !!lst && bot.msgs.some(m => /на карте world: 3/.test(m)), bot.msgs.join(' | '))
  check('/hozone info moon', !!(await cmd('/hozone info moon', /прыжок ≈ 4\.7/)))
  check('/hozone create с той же областью и плохой силой — ошибка', !!(await cmd('/hozone create bad 14', /Сила — число/)))
  const json = JSON.parse(fs.readFileSync(SRV + '/module/gravzones/zones.json', 'utf8'))
  check('zones.json — 3 зоны', json.zones.length === 3 && json.zones.map(z => z.name).join() === 'moon,heavy,vac', json.zones.map(z => z.name + ':' + z.g).join())

  // ── вакуумная карта galaxy1: зона 0.5 главнее карты ──
  await cmd('/homap tp galaxy1')
  await sleep(2500)
  check('на galaxy1 вакуум (g = 0)', gravity() === 0, gravity())
  const gp = pos().clone()
  const gx = Math.floor(gp.x); const gz = Math.floor(gp.z); const gfy = Math.floor(gp.y)
  await moveTo(gx - 2.5, gfy - 2 + 0.2, gz - 2.5); await cmd('/hozone pos1')
  await moveTo(gx + 3.5, gfy + 6.2, gz + 3.5); await cmd('/hozone pos2')
  check('/hozone create lunar 0.5 на galaxy1', !!(await cmd('/hozone create lunar 0.5', /Зона «lunar» создана/)))
  await moveTo(gp.x, gp.y + 1, gp.z)
  await sleep(800)
  check('в зоне 0.5 на вакуумной карте — гравитация 0.04 (зона главнее)', near(gravity(), 0.04), gravity())
  await land()
  const j4 = await jump()
  check('в зоне 0.5 на galaxy1 — обычный прыжок с земли работает (выше 1.25)', j4.h > 1.3, j4.h.toFixed(2))

  // ── перезагрузки ──
  consoleCmd('homodule reload gravzones')
  await sleep(2500)
  check('reload gravzones — в зоне снова 0.04', near(gravity(), 0.04), gravity())
  consoleCmd('homodule reload gravity')
  await sleep(2500)
  check('reload gravity — в зоне 0.04 (gravzones перезагружен вместе с gravity)', near(gravity(), 0.04), gravity())
  check('после reload зон по-прежнему 4', !!(await cmd('/hozone list', /Ты сейчас в зоне «lunar»/)) && bot.msgs.filter(m => /^ lunar:/.test(m)).length === 1)
  await moveTo(gx + 8.5, gp.y + 1, gz + 0.5)
  await sleep(800)
  check('вышел из зоны на galaxy1 — снова вакуум', gravity() === 0, gravity())
  await moveTo(gp.x, gp.y + 1, gp.z)
  await sleep(800)
  check('remove: перед удалением снова в зоне', near(gravity(), 0.04), gravity())
  check('/hozone remove lunar', !!(await cmd('/hozone remove lunar', /Зона «lunar» убрана/)))
  await sleep(800)
  check('после remove — вакуум карты', gravity() === 0, gravity())

  // ── выгрузка возвращает атрибуты ──
  await cmd('/homap tp world')
  await sleep(2500)
  await moveTo(bx + 0.5, gy + 0.2, bz + 0.5)
  await sleep(800)
  check('снова в moon: 0.008', near(gravity(), 0.008), gravity())
  consoleCmd('homodule unload gravzones')
  await sleep(1500)
  check('unload gravzones — гравитация и прыжок обычные', near(gravity(), 0.08) && near(jumpStrength(), 0.42), `${gravity()} ${jumpStrength()}`)
  consoleCmd('homodule load gravzones.jar')
  await sleep(2500)
  check('load gravzones — снова 0.008', near(gravity(), 0.008), gravity())

  bot.quit()
  // блок, по которому били ЛКМ, на сервере цел: смотрим свежим ботом (его мир — только из пакетов сервера)
  const bot2 = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.PORT || 25613), username: 'zbot2', version: '1.21.11', auth: 'offline' })
  await new Promise(r => bot2.once('spawn', r))
  await bot2.waitForChunksToLoad().catch(() => {})
  await sleep(1500)
  const bb = bot2.blockAt(b1); const bb2 = bot2.blockAt(b2.offset(0, 1, 0))
  check('ЛКМ топориком — блок на сервере не сломан', bb && bb.name !== 'air', bb && bb.name)
  check('ПКМ топориком — на сервере ничего не поставлено', bb2 && bb2.name === 'air', bb2 && bb2.name)
  bot2.quit()
  const failed = results.filter(r => !r[1])
  log(`итог: ${results.length - failed.length}/${results.length}`)
  process.exit(failed.length ? 1 : 0)
})().catch(e => { console.error(e); process.exit(2) })
