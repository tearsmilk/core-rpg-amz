// Живая проверка модуля items двумя ботами mineflayer. Нужен тестовый сервер с офлайн-входом (как в run.sh):
// items.jar в module/ (в module/items/config.yml despawn-seconds: 12), боты ibot1 и ibot2 в ops.json, консоль — fifo,
// и вспомогательный модуль с командой /hotgm <survival|creative|spectator> (только для теста наблюдателя; без него
// эта проверка пропускается). Запуск: SRV=/путь/к/серверу PORT=25611 node items.js
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')
const fs = require('fs')

const SRV = process.env.SRV
const PORT = Number(process.env.PORT || 25611)
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log('[test]', ...a)
const results = []
const check = (name, ok, extra = '') => { results.push([name, ok]); log(ok ? 'OK  ' : 'FAIL', name, extra) }
const consoleCmd = c => fs.appendFileSync(SRV + '/console.fifo', c + '\n')

function mk (name) {
  const b = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username: name, version: '1.21.11', auth: 'offline' })
  b.msgs = []
  b.on('messagestr', m => { b.msgs.push(m); log(name, 'чат:', m) })
  b.on('kicked', r => log(name, 'кик:', JSON.stringify(r)))
  b.on('error', e => log(name, 'ошибка', e.message))
  b.collects = []
  b._client.on('collect', p => { b.collects.push(p); b.seq.push(['collect', p.collectedEntityId, p.pickupItemCount]) })
  // порядок пакетов по предметам: метаданные (количество в стопке) и подбор — чтобы повторить счёт ванильного клиента
  b.seq = []
  b._client.on('entity_metadata', p => {
    const e = (p.metadata || []).find(m => m.value && typeof m.value === 'object' && 'itemCount' in m.value)
    if (e) b.seq.push(['meta', p.entityId, e.value.itemCount])
  })
  return b
}

const items = b => Object.values(b.entities).filter(e => e.name === 'item')
const stackOf = e => { try { return e.getDroppedItem() } catch (_) { return null } }
// Ванильный клиент (handleTakeItemEntity): на collect вычитает количество из того, что видит, и при ≤ 0 удаляет
// сущность у себя. Возвращает, что увидит такой клиент после всех пакетов (0 — сущность у него исчезла).
function vanillaCount (b, id) {
  let n = null
  for (const [t, eid, c] of b.seq) {
    if (eid !== id) continue
    if (t === 'meta') { if (n !== 0) n = c } else if (n !== null) { n -= c; if (n <= 0) n = 0 }
  }
  return n
}
const count = (b, name) => b.inventory.items().filter(i => i.name === name).reduce((s, i) => s + i.count, 0)
const mcItem = (b, name, n) => new (require('prismarine-item')(b.registry))(b.registry.itemsByName[name].id, n)
const dropKey = (b, all) => b._client.write('block_dig', { status: all ? 3 : 4, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 })
async function waitFor (fn, ms = 4000, step = 50) {
  const t = Date.now()
  while (Date.now() - t < ms) { const v = fn(); if (v) return v; await sleep(step) }
  return null
}
// новая сущность-предмет (после вызова act)
async function spawned (b, act, ms = 2000) {
  const before = new Set(items(b).map(e => e.id))
  await act()
  await waitFor(() => items(b).some(e => !before.has(e.id)), ms)
  return items(b).filter(e => !before.has(e.id))
}
async function walkTo (b, pos, within = 0.4, ms = 6000) {
  const t = Date.now()
  while (Date.now() - t < ms) {
    const d = b.entity.position.offset(0, 0, 0).distanceTo(new Vec3(pos.x, b.entity.position.y, pos.z))
    if (d < within) break
    await b.lookAt(new Vec3(pos.x, b.entity.position.y + 1.62, pos.z), true)
    b.setControlState('forward', true)
    await sleep(50)
  }
  b.setControlState('forward', false)
  await sleep(300)
}
async function setSlots (b, list) {          // list: [слот, имя, количество] (слоты окна: 36..44 — хотбар)
  for (const [slot, name, n] of list) {
    const item = name ? mcItem(b, name, n) : null
    b._client.write('set_creative_slot', { slot, item: require('prismarine-item')(b.registry).toNotch(item) })
    b._setSlot(slot, item)                                  // сервер на креатив-слот не отвечает — правим у себя
  }
  await sleep(500)
}

;(async () => {
  const a = mk('ibot1')
  await new Promise(r => a.once('spawn', r))
  const b = mk('ibot2')
  await new Promise(r => b.once('spawn', r))
  await sleep(1500)
  log('режимы', a.game.gameMode, b.game.gameMode, 'позиции', a.entity.position, b.entity.position)

  // ---- 1. Q: 1 предмет вперёд по взгляду, Ctrl+Q — стак
  await setSlots(a, [[36, 'diamond', 10]])
  a.setQuickBarSlot(0)
  await sleep(200)
  const start = a.entity.position.clone()
  await a.lookAt(start.offset(10, 1.62, 0), true)          // смотрим на +X по горизонтали
  await sleep(300)
  let n = await spawned(a, () => dropKey(a, false))
  check('Q: появилась одна сущность-предмет', n.length === 1, n.length)
  const it = n[0]
  if (it) {
    const s0 = stackOf(it)
    check('Q: в ней 1 алмаз', s0 && s0.name === 'diamond' && s0.count === 1, s0 && `${s0.name}x${s0.count}`)
    const p0 = it.position.clone()
    check('Q: появилась у глаз игрока (на 0,3 ниже)', Math.abs(p0.y - (start.y + 1.62 - 0.3)) < 0.15 && Math.abs(p0.x - start.x) < 0.3, p0)
    await sleep(2500)
    const p1 = it.position.clone()
    log('предмет: было', p0, 'стало', p1, 'скорость', it.velocity)
    check('Q: предмет улетел вперёд по взгляду (+X на 1–5 блоков)', p1.x - p0.x > 1 && p1.x - p0.x < 5 && Math.abs(p1.z - p0.z) < 0.5, (p1.x - p0.x).toFixed(2))
  }
  await waitFor(() => count(a, 'diamond') === 9, 1500)
  check('Q: в руке осталось 9', count(a, 'diamond') === 9, count(a, 'diamond'))
  // Ctrl+Q — весь стак
  await a.look(a.entity.yaw + Math.PI, 0, true)             // в другую сторону (−X), чтобы не мешать
  await sleep(200)
  n = await spawned(a, () => dropKey(a, true))
  const st = n[0] && stackOf(n[0])
  check('Ctrl+Q: выброшен весь стак (9)', n.length === 1 && st && st.count === 9, st && st.count)
  await waitFor(() => count(a, 'diamond') === 0, 1500)
  check('Ctrl+Q: рука пуста', count(a, 'diamond') === 0, count(a, 'diamond'))

  // ---- 2. подбор вторым ботом: подходит к первому предмету (+X)
  const target = it
  await walkTo(b, target.position, 0.3)
  const got = await waitFor(() => count(b, 'diamond') >= 1, 3000)
  check('подбор: ibot2 подобрал алмаз', !!got, count(b, 'diamond'))
  check('подбор: сущность исчезла', !b.entities[target.id], '')
  check('подбор: пришёл пакет подбора (анимация/звук)', b.collects.some(p => p.collectedEntityId === target.id), JSON.stringify(b.collects.slice(-1)))

  // ---- 3. задержка подбора: бросок под ноги, 1,2 с — не подобран, после 2 с — подобран
  await setSlots(a, [[36, 'gold_ingot', 5]])
  a.setQuickBarSlot(0)
  await a.look(0, -Math.PI / 2, true)                       // взгляд вниз
  await sleep(300)
  n = await spawned(a, () => dropKey(a, false))
  const t0 = Date.now()
  await sleep(1200)
  check('задержка: через 1,2 с выкинувший ещё не подобрал', count(a, 'gold_ingot') === 4 && n[0] && a.entities[n[0].id], count(a, 'gold_ingot'))
  await waitFor(() => count(a, 'gold_ingot') === 5, 3000)
  check('задержка: после ~2 с подобрал обратно', count(a, 'gold_ingot') === 5, `${count(a, 'gold_ingot')} через ${Date.now() - t0} мс`)

  // ---- 4. выброс курсором за окно инвентаря (bot.toss: клик по слоту → клик за окном, slot -999)
  await setSlots(a, [[36, 'emerald', 6]])
  await a.look(Math.PI / 2, 0, true)
  await sleep(200)
  n = await spawned(a, () => a.toss(a.registry.itemsByName.emerald.id, null, 2))
  const em = n.map(stackOf).filter(Boolean)
  check('курсор за окно: выброшены изумруды', em.length >= 1 && em.reduce((s, i) => s + i.count, 0) === 2, em.map(i => i.count).join('+'))
  await waitFor(() => count(a, 'emerald') === 4, 1500)
  check('курсор за окно: осталось 4', count(a, 'emerald') === 4, count(a, 'emerald'))

  // ---- 5. выброс из креативного меню (set_creative_slot со слотом -1)
  n = await spawned(a, () => a._client.write('set_creative_slot', { slot: -1, item: require('prismarine-item')(a.registry).toNotch(mcItem(a, 'apple', 3)) }))
  const ap = n[0] && stackOf(n[0])
  check('креатив: выброс из меню создал предмет (3 яблока)', n.length === 1 && ap && ap.name === 'apple' && ap.count === 3, ap && `${ap.name}x${ap.count}`)

  // ---- 6. полный инвентарь — не подбирает; частично — берёт сколько влезает
  const full = []
  for (let s = 9; s <= 44; s++) full.push([s, 'stone', 64])
  full[full.length - 1] = [44, 'dirt', 60]                    // последний слот хотбара: 60 земли (влезет ещё 4)
  await setSlots(b, full)
  await setSlots(a, [[36, 'iron_ingot', 3], [37, 'dirt', 6]])   // 6: взято 4 ≥ остатка 2 — случай, где важен порядок пакетов
  a.setQuickBarSlot(0)
  await a.lookAt(b.entity.position.offset(0, 0.5, 0), true)
  await sleep(300)
  const dist = a.entity.position.distanceTo(b.entity.position)
  log('расстояние между ботами', dist.toFixed(2))
  // ставим ibot2 туда, куда упадёт предмет: бросаем, ждём приземления, подводим ibot2
  n = await spawned(a, () => dropKey(a, true))
  const iron = n[0]
  await sleep(1500)
  if (iron) await walkTo(b, iron.position, 0.3)
  await sleep(2500)
  check('полный инвентарь: железо не подобрано', iron && b.entities[iron.id] && count(b, 'iron_ingot') === 0, count(b, 'iron_ingot'))
  a.setQuickBarSlot(1)
  await sleep(200)
  await a.lookAt(b.entity.position.offset(0, 0.5, 0), true)
  await sleep(200)
  n = await spawned(a, () => dropKey(a, true))
  const dirt = n[0]
  await sleep(1500)
  if (dirt) await walkTo(b, dirt.position, 0.3)
  await waitFor(() => count(b, 'dirt') === 64, 3000)
  await sleep(700)
  const rest = dirt && b.entities[dirt.id] && stackOf(b.entities[dirt.id])
  check('частично: ibot2 взял 4 земли (60 → 64)', count(b, 'dirt') === 64, count(b, 'dirt'))
  check('частично: на земле осталось 2', rest && rest.count === 2, rest && rest.count)
  check('частично: пакет подбора на 4', b.collects.some(p => dirt && p.collectedEntityId === dirt.id && p.pickupItemCount === 4), '')
  for (const [who, bot] of [['подобравший', b], ['другой игрок', a]]) {
    const vc = dirt && vanillaCount(bot, dirt.id)
    check(`частично: ${who} (счёт ванильного клиента) видит остаток 2, а не пропавший предмет`, vc === 2,
      `${vc}; пакеты: ${JSON.stringify(bot.seq.filter(x => dirt && x[1] === dirt.id))}`)
  }

  // ---- 7. наблюдатель не подбирает (если есть /hotgm). Слот освобождаем ещё в креативе, отойдя от лежащих предметов.
  await walkTo(b, a.entity.position.offset(0, 0, 4), 0.4)
  await setSlots(b, [[44, null]])
  b.chat('/hotgm spectator')
  await sleep(1000)
  if (b.game.gameMode === 'spectator') {
    await setSlots(a, [[36, 'stick', 1]])
    a.setQuickBarSlot(0)
    await a.lookAt(b.entity.position.offset(0, 0.5, 0), true)
    await sleep(300)
    n = await spawned(a, () => dropKey(a, false))
    const stick = n[0]
    await sleep(1500)
    if (stick) await walkTo(b, stick.position, 0.3)
    await sleep(2500)
    check('наблюдатель: не подбирает палку под ногами', stick && b.entities[stick.id] && count(b, 'stick') === 0,
      `до палки ${stick && b.entities[stick.id] ? b.entities[stick.id].position.distanceTo(b.entity.position).toFixed(2) : '-'}`)
    b.chat('/hotgm creative')
    await waitFor(() => stick && !b.entities[stick.id], 3000)
    await waitFor(() => count(b, 'stick') === 1, 1500)
    check('после выхода из наблюдателя — подобрал', stick && !b.entities[stick.id] && count(b, 'stick') === 1, count(b, 'stick'))
  } else log('нет /hotgm — проверка наблюдателя пропущена')

  // ---- 8. слияние: два одиночных броска под ноги, ibot1 сразу отходит (до конца задержки подбора) → одна стопка из 2
  await walkTo(b, b.entity.position.offset(0, 0, -6), 0.5)  // ibot2 отходит
  await setSlots(a, [[36, 'redstone', 2]])
  a.setQuickBarSlot(0)
  await a.look(0, -Math.PI / 2, true)
  await sleep(300)
  const here = a.entity.position.clone()
  const r1 = await spawned(a, () => dropKey(a, false))
  await sleep(100)
  const r2 = await spawned(a, () => dropKey(a, false))
  await walkTo(a, here.offset(3, 0, 0), 0.4, 1500)
  await sleep(3000)
  const reds = items(a).filter(e => { const s = stackOf(e); return s && s.name === 'redstone' })
  const rs = reds.map(stackOf)
  log('красная пыль:', reds.map(e => e.position.toString()).join(' '))
  check('слияние: осталась одна стопка из 2', reds.length === 1 && rs[0].count === 2, rs.map(i => i.count).join('+') + ` (брошено ${r1.length}+${r2.length})`)

  // ---- 9. исчезновение (despawn-seconds: 12): бросок в сторону от обоих ботов
  await setSlots(a, [[36, 'lapis_lazuli', 1]])
  a.setQuickBarSlot(0)
  await a.look(Math.PI / 2, 0, true)
  await sleep(300)
  n = await spawned(a, () => dropKey(a, true))
  const lap = n[0]
  const tl = Date.now()
  await sleep(9000)
  check('исчезновение: через 9 с ещё лежит', lap && a.entities[lap.id] && count(a, 'lapis_lazuli') === 0, '')
  const gone = lap && await waitFor(() => !a.entities[lap.id], 6000, 100)
  check('исчезновение: пропало через ~12 с (despawn-seconds)', !!gone && count(a, 'lapis_lazuli') === 0 && count(b, 'lapis_lazuli') === 0, `${Date.now() - tl} мс`)

  // ---- 10. homodule reload items: предметы модуля убраны, новый бросок — ровно одна сущность
  await setSlots(a, [[36, 'quartz', 3]])
  a.setQuickBarSlot(0)
  await a.look(Math.PI / 2, 0, true)
  await sleep(300)
  await spawned(a, () => dropKey(a, false))
  await spawned(a, () => dropKey(a, false))
  await sleep(500)
  const before = items(a).length
  log('предметов перед reload:', before)
  consoleCmd('homodule reload items')
  await sleep(2500)
  check('reload: выброшенные модулем предметы убраны', before >= 2 && items(a).length === 0, `${before} → ${items(a).length}`)
  await setSlots(a, [[36, 'coal', 4]])
  a.setQuickBarSlot(0)
  await a.look(Math.PI, 0, true)
  await sleep(300)
  n = await spawned(a, () => dropKey(a, false), 1500)
  await sleep(800)
  const coal = items(a).filter(e => { const s = stackOf(e); return s && s.name === 'coal' })
  check('reload: бросок после перезагрузки — одна сущность, без дублей', n.length === 1 && coal.length === 1 && stackOf(coal[0]).count === 1, `${n.length}/${coal.length}`)
  await waitFor(() => count(a, 'coal') === 3, 1500)
  check('reload: из руки ушёл ровно 1', count(a, 'coal') === 3, count(a, 'coal'))

  // ---- 11. no-despawn-maps: добавить карту world — предметы на ней не исчезают по времени
  if (SRV) {
    const cfg = SRV + '/module/items/config.yml'
    fs.writeFileSync(cfg, fs.readFileSync(cfg, 'utf8').replace(/no-despawn-maps:\n/, 'no-despawn-maps:\n  - world\n'))
    consoleCmd('homodule reload items')
    await sleep(2500)
    await setSlots(a, [[36, 'prismarine_shard', 1]])
    a.setQuickBarSlot(0)
    await a.look(Math.PI / 2, 0, true)
    await sleep(300)
    n = await spawned(a, () => dropKey(a, true))
    const ps = n[0]
    await sleep(15000)
    check('no-despawn-maps: на карте world предмет лежит дольше despawn-seconds (15 с > 12 с)', ps && a.entities[ps.id], '')
  }

  a.quit(); b.quit()
  await sleep(500)
  const failed = results.filter(r => !r[1])
  log(`итог: ${results.length - failed.length}/${results.length} OK`)
  process.exit(failed.length ? 1 : 0)
})().catch(e => { console.error(e); process.exit(2) })
