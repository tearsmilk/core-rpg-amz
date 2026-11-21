// Живая проверка чёрных дыр, дрейфа и предметов модуля gravity ботом mineflayer. Нужен тестовый сервер с офлайн-входом
// (как в run.sh): gravity.jar и тестовый itemtest.jar (tools/e2e/itemspawner, на сервер не выкладывать) в module/,
// карта galaxy1 (homap create galaxy1 void), бот hbot в ops.json, консоль — fifo, а в module/gravity/config.yml дыра
//   black-holes: { galaxy1: { abyss: {x: 0.5, y: 70, z: -40, horizon: 6, reach: 100, pull: 4, max-speed: 12} } }
// Сборка itemtest.jar: javac -d cls -cp run/server.jar tools/e2e/itemspawner/src/ink/hoka/odyssey/e2e/*.java &&
//   cp tools/e2e/itemspawner/module.yml cls/ && jar cf itemtest.jar -C cls .
// Запуск: SRV=/путь/к/серверу PORT=25612 node blackholes.js
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')
const fs = require('fs')

const SRV = process.env.SRV
const HOLE = new Vec3(0.5, 70, -40)
const HORIZON = 6
const MAX_DRIFT = 1.5
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log('[test]', ...a)
const results = []
const check = (name, ok, extra = '') => { results.push([name, ok]); log(ok ? 'OK  ' : 'FAIL', name, extra) }
const consoleCmd = c => fs.appendFileSync(SRV + '/console.fifo', c + '\n')

const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.PORT || 25612), username: 'hbot', version: '1.21.11', auth: 'offline' })
bot.msgs = []
bot.on('messagestr', m => { bot.msgs.push(m); if (!m.startsWith('itemtest info')) log('чат:', m) })
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
bot._client.on('explosion', () => { explosions++ })
bot._client.on('entity_update_attributes', p => {
  if (!bot.entity || p.entityId !== bot.entity.id) return
  for (const prop of p.properties) {
    const k = String(prop.key).replace('minecraft:', '').replace('generic.', '').replace('player.', '')
    attrs[k] = prop
  }
  const g = attrValue(attrs.gravity)
  if (g != null) bot.physics.gravity = g
})
const gravity = () => { const g = attrValue(attrs.gravity); return g == null ? 0.08 : g }
const input = (flags) => bot._client.write('player_input', { inputs: flags })
const pos = () => bot.entity.position.clone()
const center = () => pos().offset(0, 0.9, 0)
const dist = () => center().distanceTo(HOLE)
const waitMsg = async (re, ms = 4000) => {
  const t = Date.now()
  while (Date.now() - t < ms) { const m = bot.msgs.find(x => re.test(x)); if (m) return m; await sleep(100) }
  return null
}
// предметы карты от тестового модуля: [{id, x, y, z, vx, vy, vz, ng}]
async function items () {
  bot.msgs = bot.msgs.filter(m => !m.startsWith('itemtest info'))
  bot.chat('/hoitemtest info')
  const m = await waitMsg(/^itemtest info/)
  if (!m) return null
  return m.split(' | ').slice(1).map(s => {
    const p = s.trim().split(/\s+/)
    return { id: +p[0], x: +p[1], y: +p[2], z: +p[3], vx: +p[5], vy: +p[6], vz: +p[7], ng: p[8] === 'ng=true' }
  })
}
async function spawnItem (dx, dy, dz, vx = 0, vy = 0, vz = 0) {
  bot.msgs = bot.msgs.filter(m => !m.startsWith('itemtest spawned'))
  bot.chat(`/hoitemtest spawn ${dx} ${dy} ${dz} ${vx} ${vy} ${vz}`)
  const m = await waitMsg(/^itemtest spawned/)
  return m ? +m.split(' ')[2] : null
}
const item = async id => ((await items()) || []).find(i => i.id === id)

;(async () => {
  await new Promise(r => bot.once('spawn', r))
  bot.game.gameMode = 'survival'   // mineflayer в креативе игнорирует отдачу взрыва, ванильный клиент — нет
  bot.on('game', () => { bot.game.gameMode = 'survival' })
  await sleep(1500)

  // ── обычная карта world: предмет падает
  const w = await spawnItem(0, 30, 2)
  const w0 = await item(w)
  await sleep(1000)
  const w1 = await item(w)
  log('world: предмет', JSON.stringify(w0), '->', JSON.stringify(w1))
  check('world: предмет с гравитацией и падает', w0 && w1 && !w1.ng && w1.y < w0.y - 1, w1 && `${w0.y} -> ${w1.y}`)
  bot.chat('/hoitemtest clear')

  // ── команды дыр
  bot.chat('/hograv hole list')
  check('/hograv hole list — дыра abyss', !!(await waitMsg(/abyss: 0\.5 70 -40, горизонт 6, радиус 100/)))

  bot.chat('/homap tp galaxy1')
  await sleep(2500)
  check('galaxy1: гравитация 0', gravity() === 0, gravity())

  // подняться над площадкой
  input({ jump: true })
  await sleep(1000)
  input({})
  await sleep(2500)
  log('над площадкой: pos', pos().toString(), 'onGround', bot.entity.onGround, 'до дыры', dist().toFixed(2))

  // ── дрейф без клавиш: к дыре, не быстрее предела
  const d0 = dist()
  const e0 = explosions
  let maxV = 0
  let prev = center()
  const samples = []
  for (let i = 0; i < 20; i++) {
    await sleep(500)
    const c = center()
    const v = c.distanceTo(prev) / 0.5
    maxV = Math.max(maxV, v)
    samples.push(v.toFixed(2))
    prev = c
  }
  const d1 = dist()
  const rate = (explosions - e0) / 10
  log('дрейф 10 с: до дыры', d0.toFixed(2), '->', d1.toFixed(2), 'скорости', samples.join(' '), 'толчков/с', rate)
  check('без клавиш сносит к дыре', d1 < d0 - 4, `${d0.toFixed(2)} -> ${d1.toFixed(2)}`)
  check('скорость дрейфа не выше предела 1,5 бл/с (с запасом)', maxV <= MAX_DRIFT * 1.15, maxV.toFixed(2))
  const jumps = samples.slice(1).map((v, i) => Math.abs(v - samples[i]))
  check('дрейф плавный: за 0,5 с скорость меняется не больше чем на 0,5 бл/с', Math.max(...jumps) <= 0.5,
    Math.max(...jumps).toFixed(2))
  check('толчков не больше 20/с', rate <= 21, rate)

  // ── клавиши: управление как раньше (W от дыры)
  await bot.lookAt(center().offset(0, 0, 20), true)
  const a0 = dist()
  bot.setControlState('forward', true)
  await sleep(1500)
  bot.setControlState('forward', false)
  const a1 = dist()
  log('W от дыры 1,5 с: до дыры', a0.toFixed(2), '->', a1.toFixed(2))
  check('W — летит от дыры (управляет игрок, дрейф не мешает)', a1 > a0 + 1, `${a0.toFixed(2)} -> ${a1.toFixed(2)}`)
  await sleep(6000)
  const a2 = dist()
  check('отпустил W — снова сносит к дыре', a2 < a1, `${a1.toFixed(2)} -> ${a2.toFixed(2)}`)

  // ── долететь до горизонта: держит у горизонта, внутрь не тянет
  let t = 0
  while (dist() > HORIZON + 1 && t < 60000) { await sleep(500); t += 500 }
  await sleep(4000)
  const h1 = dist()
  await sleep(3000)
  const h2 = dist()
  log('у горизонта: до дыры', h1.toFixed(2), '->', h2.toFixed(2), 'за', (t / 1000).toFixed(1), 'с')
  check('у горизонта держит (none): не внутри и не уплывает', h2 >= HORIZON - 0.3 && h2 <= HORIZON + 1.5 && Math.abs(h2 - h1) < 0.5,
    `${h1.toFixed(2)} -> ${h2.toFixed(2)}`)

  // ── предметы в вакууме: без гравитации, к дыре, поглощение
  const it1 = await spawnItem(0, 1, 4)            // в ~10 блоках от центра дыры
  await sleep(300)
  const i0 = await item(it1)
  await sleep(500)
  const i1 = await item(it1)
  log('предмет у дыры:', JSON.stringify(i0), '->', JSON.stringify(i1))
  check('galaxy1: предмет без гравитации', i0 && i0.ng === true)
  const hd = i => Math.hypot(i.x - HOLE.x, i.y + 0.125 - HOLE.y, i.z - HOLE.z)
  check('предмет летит к дыре', i0 && i1 && hd(i1) < hd(i0), i0 && i1 && `${hd(i0).toFixed(2)} -> ${hd(i1).toFixed(2)}`)
  let gone = false
  for (let k = 0; k < 16 && !gone; k++) { await sleep(500); gone = !(await item(it1)) }
  check('предмет поглощён за горизонтом (исчез)', gone)

  // ── без дыры: висит и плывёт по инерции; hole remove/add
  bot.chat('/hograv hole remove abyss')
  check('/hograv hole remove', !!(await waitMsg(/abyss» на карте galaxy1 убрана/)))
  await sleep(500)
  const it2 = await spawnItem(0, 2, 0, 1, 0, 0)  // 1 бл/с вдоль x
  await sleep(400)
  const j0 = await item(it2)
  await sleep(2000)
  const j1 = await item(it2)
  log('без дыры:', JSON.stringify(j0), '->', JSON.stringify(j1))
  check('предмет висит (не падает)', j0 && j1 && Math.abs(j1.y - j0.y) < 0.05, j1 && (j1.y - j0.y).toFixed(3))
  check('плывёт по инерции почти без затухания', j0 && j1 && (j1.x - j0.x) > 1.6 && j1.vx > 0.9, j1 && `dx=${(j1.x - j0.x).toFixed(2)} vx=${j1.vx}`)

  bot.chat('/hograv hole add near 4 50')
  check('/hograv hole add', !!(await waitMsg(/«near» создана на карте galaxy1/)))
  bot.msgs = []
  bot.chat('/hograv hole list galaxy1')
  check('hole list — near есть, abyss нет', !!(await waitMsg(/^ ?near: .*горизонт 4, радиус 50/)) &&
    !bot.msgs.some(m => /abyss:/.test(m)))
  const cfg = fs.readFileSync(SRV + '/module/gravity/config.yml', 'utf8')
  check('config.yml: near записана, abyss нет, комментарии целы', /^ {4}near: \{x: /m.test(cfg) && !/abyss/.test(cfg) &&
    cfg.includes('# Значения для /hograv hole add'))

  // ── выключили вакуум: предметы падают
  bot.chat('/hoitemtest clear')
  await sleep(300)
  const it3 = await spawnItem(20, 2, 0)        // вне горизонта дыры near
  await sleep(400)
  bot.chat('/hograv off galaxy1')
  check('/hograv off', !!(await waitMsg(/galaxy1 выключен/)))
  await sleep(1500)
  const k1 = await item(it3)
  log('после off:', JSON.stringify(k1))
  check('вакуум выключен — предмету вернули гравитацию', k1 && !k1.ng, JSON.stringify(k1))
  bot.chat('/hograv on galaxy1')
  check('/hograv on', !!(await waitMsg(/galaxy1 включён/)))
  await sleep(3000)

  // ── reload: без дублей
  bot.chat('/hoitemtest clear')
  consoleCmd('homodule reload gravity')
  await sleep(2500)
  input({ jump: true })
  await sleep(600)
  input({})
  await sleep(1500)
  const r0 = explosions
  await sleep(3000)
  const r = (explosions - r0) / 3
  check('после reload гравитация 0, толчков ≤ 20/с', gravity() === 0 && r <= 21, `g=${gravity()} rate=${r}`)
  const it4 = await spawnItem(0, 0, 20)
  await sleep(500)
  const m4 = await item(it4)
  check('после reload предметы снова без гравитации', m4 && m4.ng, JSON.stringify(m4))
  consoleCmd('homodule unload gravity')
  await sleep(1500)
  const m5 = await item(it4)
  check('unload — предмету вернули гравитацию', m5 ? !m5.ng : true, JSON.stringify(m5))
  check('unload — игроку вернули гравитацию', Math.abs(gravity() - 0.08) < 1e-9, gravity())
  consoleCmd('homodule load gravity.jar')
  await sleep(2000)
  check('load — снова невесомость', gravity() === 0, gravity())
  bot.chat('/hoitemtest clear')

  bot.quit()
  const failed = results.filter(x => !x[1])
  log(`итог: ${results.length - failed.length}/${results.length}`)
  process.exit(failed.length ? 1 : 0)
})().catch(e => { console.error(e); process.exit(2) })
