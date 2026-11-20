const fs = require('fs')
const lib = process.env.RC_BOTLIB || '/home/user/mc-bot/node_modules/mineflayer'
const mineflayer = require(lib)
const ctl = process.argv[2]
const name = process.argv[3] || 'NameBot'
const port = parseInt(process.env.RC_PORT || '25601', 10)
const sleep = ms => new Promise(r => setTimeout(r, ms))
const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: name, version: '1.21.11', auth: 'offline' })
bot.on('kicked', r => { console.log('KICKED', JSON.stringify(r)); process.exit(2) })
bot.on('error', e => console.log('ERROR', e.message))
bot.once('spawn', async () => {
  console.log('SPAWNED')
  let last = ''
  for (;;) {
    await sleep(200)
    let cmd = ''
    try { cmd = fs.readFileSync(ctl, 'utf8').trim() } catch (e) { cmd = '' }
    if (cmd === last) continue
    last = cmd
    console.log('CMD', cmd)
    if (cmd === 'sneak') bot.setControlState('sneak', true)
    if (cmd === 'stand') bot.setControlState('sneak', false)
    if (cmd === 'quit') { bot.quit(); await sleep(500); process.exit(0) }
  }
})
