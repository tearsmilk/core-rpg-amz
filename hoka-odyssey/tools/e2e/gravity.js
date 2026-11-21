// Живая проверка модуля gravity ботом mineflayer. Нужен запущенный тестовый сервер с офлайн-входом (как в run.sh):
// gravity.jar в module/, карта galaxy1 (в консоли: homap create galaxy1 void), бот gbot в ops.json, консоль — fifo.
// Запуск: SRV=/путь/к/серверу PORT=25610 node gravity.js
// prismarine-physics не знает атрибутов gravity/jump_strength — эмулируем ванильный клиент: гравитацию бота берём
// из атрибута, а пробел шлём только флагом ввода (player_input), без физического прыжка (jump_strength = 0).
const mineflayer = require('mineflayer')
const fs = require('fs')

const SRV = process.env.SRV
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log('[test]', ...a)
const results = []
const check = (name, ok, extra = '') => { results.push([name, ok]); log(ok ? 'OK  ' : 'FAIL', name, extra) }
const consoleCmd = c => fs.appendFileSync(SRV + '/console.fifo', c + '\n')

const bot = mineflayer.createBot({ host: '127.0.0.1', port: Number(process.env.PORT || 25610), username: 'gbot', version: '1.21.11', auth: 'offline' })
bot.msgs = []
bot.on('messagestr', m => { bot.msgs.push(m); log('чат:', m) })
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
bot._client.on('explosion', p => { explosions++ })
bot._client.on('entity_update_attributes', p => {
  if (!bot.entity || p.entityId !== bot.entity.id) return
  for (const prop of p.properties) {
    const k = String(prop.key).replace('minecraft:', '').replace('generic.', '').replace('player.', '')
    attrs[k] = prop
  }
  const g = attrValue(attrs.gravity)
  if (g != null) { bot.physics.gravity = g; log('гравитация бота ->', g) }
})
const gravity = () => { const g = attrValue(attrs.gravity); return g == null ? 0.08 : g }
const input = (flags) => bot._client.write('player_input', { inputs: flags })
const y = () => bot.entity.position.y
const waitMsg = async (re, ms = 4000) => {
  const t = Date.now()
  while (Date.now() - t < ms) { if (bot.msgs.some(m => re.test(m))) return true; await sleep(100) }
  return false
}
async function rate (ms) { const e0 = explosions; await sleep(ms); return (explosions - e0) / (ms / 1000) }

;(async () => {
  await new Promise(r => bot.once('spawn', r))
  bot.game.gameMode = 'survival'   // mineflayer в креативе игнорирует отдачу взрыва, ванильный клиент — нет
  bot.on('game', () => { bot.game.gameMode = 'survival' })
  await sleep(1500)
  check('на world гравитация обычная', Math.abs(gravity() - 0.08) < 1e-9, gravity())

  bot.chat('/hograv')
  check('/hograv — список карт', await waitMsg(/Вакуум .*на картах: galaxy1/))

  bot.chat('/homap tp galaxy1')
  await sleep(2000)
  check('на galaxy1 гравитация 0', gravity() === 0, gravity())
  const y0 = y()
  log('galaxy1, y =', y0, 'onGround', bot.entity.onGround)

  // пробел 2 с
  input({ jump: true })
  const r = await rate(2000)
  const y1 = y()
  log('после 2 с пробела y =', y1, 'толчков/с', r)
  check('пробел — поднимается', y1 > y0 + 3, `${y0.toFixed(2)} -> ${y1.toFixed(2)}`)
  check('скорость подъёма не выше 4 бл/с (с запасом)', (y1 - y0) / 2 <= 4.3, ((y1 - y0) / 2).toFixed(2))
  check('толчков не больше 20 в секунду (нет дублей)', r <= 21, r)
  input({})
  await sleep(1500)                                        // тормозит плавно
  const y2 = y()
  await sleep(3000)
  const y3 = y()
  log('отпустил: y', y1.toFixed(2), '->', y2.toFixed(2), '-> через 3 с', y3.toFixed(2))
  check('отпустил — проплыл по инерции, а не встал мгновенно', y2 > y1 + 0.3, (y2 - y1).toFixed(2))
  check('висит, не падает 3 с', Math.abs(y3 - y2) < 0.3, (y3 - y2).toFixed(3))

  // Shift 1 с
  bot.setControlState('sneak', true)
  await sleep(1000)
  bot.setControlState('sneak', false)
  await sleep(1000)
  const y4 = y()
  check('Shift — опускается', y4 < y3 - 1, `${y3.toFixed(2)} -> ${y4.toFixed(2)}`)
  await sleep(2000)
  check('после Shift снова висит', Math.abs(y() - y4) < 0.3, (y() - y4).toFixed(3))

  // WASD с инерцией
  const x0 = bot.entity.position.clone()
  bot.setControlState('forward', true)
  await sleep(1500)
  bot.setControlState('forward', false)
  const x1 = bot.entity.position.clone()
  await sleep(1500)
  const x2 = bot.entity.position.clone()
  const d1 = x1.distanceTo(x0); const d2 = x2.distanceTo(x1)
  log('W 1,5 с:', d1.toFixed(2), 'бл, после отпускания ещё', d2.toFixed(2), 'бл, y', y().toFixed(2))
  check('W — летит вперёд', d1 > 3, d1.toFixed(2))
  check('отпустил W — скользит по инерции (> 1 бл)', d2 > 1, d2.toFixed(2))
  check('при полёте вбок не падает', Math.abs(y() - y4) < 0.5, (y() - y4).toFixed(3))

  // выключили вакуум — падает
  bot.chat('/hograv off galaxy1')
  check('/hograv off', await waitMsg(/выключен/))
  await sleep(1500)
  const y5 = y()
  check('без вакуума — гравитация вернулась и падает', Math.abs(gravity() - 0.08) < 1e-9 && y5 < y4 - 3, `g=${gravity()} y ${y4.toFixed(1)} -> ${y5.toFixed(1)}`)
  const cfgOff = fs.readFileSync(SRV + '/module/gravity/config.yml', 'utf8')
  check('config.yml: galaxy1 убрана, комментарии целы', /^maps: \[\]$/m.test(cfgOff) && cfgOff.includes('# Вверх/вниз'))

  bot.chat('/hograv on galaxy1')
  check('/hograv on', await waitMsg(/galaxy1 включён/))
  await sleep(6000)
  const y6 = y(); await sleep(2000); const y7 = y()
  log('включили снова в падении: y', y5.toFixed(1), '->', y6.toFixed(1), '->', y7.toFixed(1), 'g', gravity())
  check('включили в падении — падение погашено', gravity() === 0 && Math.abs(y7 - y6) < 0.5, (y7 - y6).toFixed(3))
  const cfgOn = fs.readFileSync(SRV + '/module/gravity/config.yml', 'utf8')
  check('config.yml: galaxy1 снова в списке', /^maps:\n {2}- galaxy1$/m.test(cfgOn))

  // перезагрузка модуля — без дублей
  consoleCmd('homodule reload gravity')
  await sleep(2000)
  check('после reload гравитация 0', gravity() === 0, gravity())
  const y8 = y()
  input({ jump: true })
  const r2 = await rate(2000)
  input({})
  const y9 = y()
  check('после reload пробел работает, толчков ≤ 20/с', y9 > y8 + 3 && r2 <= 21, `dy=${(y9 - y8).toFixed(2)} rate=${r2}`)
  check('после reload скорость прежняя', (y9 - y8) / 2 <= 4.3, ((y9 - y8) / 2).toFixed(2))

  // выгрузка — гравитация возвращается
  consoleCmd('homodule unload gravity')
  await sleep(1500)
  check('unload — гравитация вернулась', Math.abs(gravity() - 0.08) < 1e-9, gravity())
  consoleCmd('homodule load gravity.jar')
  await sleep(2000)
  check('load — снова 0', gravity() === 0, gravity())

  // обратно на world
  bot.chat('/homap tp world')
  await sleep(2000)
  check('на world снова обычная гравитация', Math.abs(gravity() - 0.08) < 1e-9, gravity())

  bot.quit()
  const failed = results.filter(r => !r[1])
  log(`итог: ${results.length - failed.length}/${results.length}`)
  process.exit(failed.length ? 1 : 0)
})().catch(e => { console.error(e); process.exit(2) })
