// Живая проверка respack + coreshaders на ядре HokaOdyssey (офлайн-вход только для теста).
const mineflayer = require('mineflayer')
const fs = require('fs')
const { Vec3 } = require('vec3')

const SRV = process.env.SRV
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log('[test]', ...a)
const results = []
const check = (name, ok, extra = '') => { results.push([name, ok]); log(ok ? 'OK  ' : 'FAIL', name, extra) }
const consoleCmd = c => fs.appendFileSync(SRV + '/console.fifo', c + '\n')

function connect (name, onPack) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25599, username: name, version: '1.21.11', auth: 'offline' })
  bot.packs = []
  bot.msgs = []
  bot._client.on('add_resource_pack', p => {
    bot.packs.push(p)
    log(name, 'пак:', p.url, p.hash, 'forced', p.forced)
    onPack(bot, p)
  })
  bot.on('messagestr', m => { bot.msgs.push(m); log(name, 'чат:', m) })
  const plain = c => { try { return require('prismarine-chat')('1.21.11').fromNotch(c).toString() } catch (e) { return JSON.stringify(c) } }
  bot._client.on('action_bar', p => { const s = plain(p.text); bot.msgs.push(s); log(name, 'actionbar:', s) })
  bot._client.on('set_action_bar_text', p => { const s = plain(p.text); bot.msgs.push(s); log(name, 'actionbar:', s) })
  bot.on('kicked', r => { bot.kickReason = typeof r === 'string' ? r : JSON.stringify(r); log(name, 'кик:', bot.kickReason) })
  bot.on('error', e => log(name, 'ошибка', e.message))
  return bot
}

function respond (bot, p, statuses) {
  const send = r => bot._client.write('resource_pack_receive', { uuid: p.uuid, result: r })
  statuses.forEach((r, i) => setTimeout(() => send(r), 50 * i))
}

const displays = bot => Object.values(bot.entities).filter(e => e.name === 'item_display')
const interactions = bot => Object.values(bot.entities).filter(e => e.name === 'interaction')
const waitMsg = async (bot, re, ms = 4000) => {
  const t = Date.now()
  while (Date.now() - t < ms) { if (bot.msgs.some(m => re.test(m))) return true; await sleep(100) }
  return false
}
const lastMsg = (bot, re) => [...bot.msgs].reverse().find(m => re.test(m))

;(async () => {
  // 1. вход: пак приходит в фазе конфигурации, ответ — принят/скачан/загружен
  // статусы: 0 загружен, 1 отклонён, 2 ошибка загрузки, 3 принят, 4 скачан, 5 неверный адрес, 6 ошибка применения, 7 сброшен
  const bot = connect('csbot', (b, p) => respond(b, p, [3, 4, 0]))
  await new Promise(r => bot.once('spawn', r))
  check('пак отправлен при входе', bot.packs.length === 1)
  const p0 = bot.packs[0]
  check('адрес пака — адрес входа + порт HTTP', p0 && /^http:\/\/127\.0\.0\.1:25574\/pack\/[0-9a-f]{40}\.zip$/.test(p0.url), p0 && p0.url)
  check('пак обязательный', p0 && p0.forced === true)
  await sleep(1500)

  // 2. жезл
  bot.chat('/hocs give csbot cube space red')
  await waitMsg(bot, /Выдан жезл/)
  await sleep(500)
  const wand = bot.inventory.items().find(i => i.name === 'blaze_rod')
  check('жезл выдан', !!wand, wand && JSON.stringify(wand.customName || wand.nbt || '').slice(0, 120))
  await bot.equip(wand, 'hand')
  await sleep(300)

  // 3. поставить куб: ПКМ по верху травы перед ботом
  const pos = bot.entity.position.floored()
  const ground = bot.blockAt(pos.offset(2, -1, 0))
  log('бот на', bot.entity.position, 'земля', ground && ground.name, ground && ground.position)
  await bot.lookAt(ground.position.offset(0.5, 1, 0.5), true)
  await bot.activateBlock(ground, new Vec3(0, 1, 0))
  check('куб поставлен (сообщение)', await waitMsg(bot, /Поставлено/))
  await sleep(800)
  check('ItemDisplay появился', displays(bot).length === 1, 'дисплеев ' + displays(bot).length)
  check('Interaction-хитбокс появился', interactions(bot).length === 1)
  const d = displays(bot)[0]
  if (d) {
    check('дисплей в центре блока над травой', d.position.distanceTo(ground.position.offset(0.5, 1.5, 0.5)) < 0.01, d.position)
    const meta = JSON.stringify(d.metadata)
    check('модель coreshaders:cube_space', meta.includes('coreshaders:cube_space'), meta.slice(0, 300))
  }

  // 4. /hocs info, глядя на эффект
  await bot.lookAt(ground.position.offset(0.5, 1.5, 0.5), true)
  bot.chat('/hocs info')
  check('/hocs info видит эффект', await waitMsg(bot, /Эффект: форма cube, картинка Космос/))

  // 5. команда на эффект и вход в него
  bot.chat('/hocs cmd add player hocs list')
  check('команда добавлена', await waitMsg(bot, /Команда добавлена/))
  bot.msgs.length = 0
  bot.chat('/hocs cooldown 0')
  await sleep(300)
  bot.setControlState('forward', false)
  await bot.lookAt(ground.position.offset(0.5, 1.5, 0.5), true)
  bot.setControlState('forward', true)
  await sleep(1200)
  bot.setControlState('forward', false)
  check('триггер enter выполнил команду игрока', await waitMsg(bot, /Картинки: sky_sea/, 3000))

  // 6. хранение: файл карты
  await sleep(6000)
  const file = SRV + '/module/coreshaders/effects/world.json'
  const saved = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : null
  check('эффект сохранён в effects/world.json', saved && saved.effects.length === 1 && saved.effects[0].actions.commands[0] === 'player:hocs list',
    saved && JSON.stringify(saved.effects[0]).slice(0, 200))

  // 7. перезагрузка coreshaders: эффект на месте, без дублей
  consoleCmd('homodule reload coreshaders')
  await sleep(3000)
  check('после reload coreshaders — один дисплей', displays(bot).length === 1, 'дисплеев ' + displays(bot).length)
  check('после reload coreshaders — один хитбокс', interactions(bot).length === 1)

  // 8. перезагрузка respack (и зависимых): пак тот же — не пересылается
  const before = bot.packs.length
  consoleCmd('homodule reload respack')
  await sleep(4000)
  check('после reload respack пак не пересылается (тот же sha1)', bot.packs.length === before, 'паков ' + bot.packs.length)
  check('после reload respack эффект на месте', displays(bot).length === 1 && interactions(bot).length === 1)

  // 9. своя картинка: import → пак пересобран и разослан
  bot.msgs.length = 0
  bot.chat('/hocs import testpic')
  check('import: картинка готова', await waitMsg(bot, /Картинка testpic готова/, 8000))
  check('import: пак обновлён', await waitMsg(bot, /Ресурспак обновлён/, 8000))
  await sleep(500)
  check('новый пак пришёл онлайн-игроку', bot.packs.length === before + 1, bot.packs.length && bot.packs[bot.packs.length - 1].hash)
  const np = bot.packs[bot.packs.length - 1]
  if (np) respond(bot, np, [3, 4, 0])
  await sleep(500)

  // 10. сменить картинку эффекта на свою

  await bot.lookAt(ground.position.offset(0.5, 1.5, 0.5), true)
  await sleep(300)
  bot.chat('/hocs image testpic')
  check('картинка эффекта сменена', await waitMsg(bot, /Картинка эффекта: testpic/))
  await sleep(500)
  check('модель cube_testpic', JSON.stringify(displays(bot)[0] && displays(bot)[0].metadata).includes('coreshaders:cube_testpic'))

  // 11. ЛКМ жезлом по хитбоксу — убрать
  const hit = interactions(bot)[0]
  bot.attack(hit)
  check('ЛКМ: эффект убран', await waitMsg(bot, /Эффект убран/))
  await sleep(800)
  check('сущности эффекта исчезли', displays(bot).length === 0 && interactions(bot).length === 0)

  // 12. плоскость на траве (верхняя грань) и Shift+ЛКМ меняет форму жезла
  bot.setControlState('sneak', true)
  await sleep(200)
  bot.swingArm()
  await sleep(400)
  bot.setControlState('sneak', false)
  check('Shift+ЛКМ: форма жезла plane', await waitMsg(bot, /Форма жезла: plane/))
  await sleep(300)
  const g2 = bot.blockAt(bot.entity.position.floored().offset(-2, -1, 1))
  await bot.lookAt(g2.position.offset(0.5, 1, 0.5), true)
  await bot.activateBlock(g2, new Vec3(0, 1, 0))
  check('плоскость поставлена', await waitMsg(bot, /Поставлено: plane/))
  await sleep(600)
  const pd = displays(bot)[0]
  check('плоскость лежит у верхней грани травы', pd && Math.abs(pd.position.y - (g2.position.y + 1 + 0.015)) < 0.001, pd && pd.position)

  // 13. отказ от пака — кик
  const bad = connect('declinebot', (b, p) => respond(b, p, [1]))
  await sleep(4000)
  check('отказ от пака — кик с сообщением', !!bad.kickReason && bad.kickReason.includes('Без ресурспака'), bad.kickReason)

  // 14. не-OP: команда недоступна
  const guest = connect('guestbot', (b, p) => respond(b, p, [3, 4, 0]))
  await new Promise(r => guest.once('spawn', r))
  await sleep(1000)
  guest.chat('/hocs give')
  await sleep(1200)
  check('не-OP: /hocs недоступна', !guest.inventory.items().some(i => i.name === 'blaze_rod') && !guest.msgs.some(m => /Выдан/.test(m)),
    guest.msgs.slice(-2).join(' | '))
  guest.quit()

  bot.quit()
  await sleep(500)
  const failed = results.filter(r => !r[1])
  log(`ИТОГО: ${results.length - failed.length}/${results.length}`, failed.length ? 'провалено: ' + failed.map(f => f[0]).join('; ') : '')
  process.exit(failed.length ? 1 : 0)
})().catch(e => { console.error(e); process.exit(2) })
