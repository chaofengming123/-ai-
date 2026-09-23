import test from 'node:test'
import assert from 'node:assert/strict'
import { createIndexMonitor } from '../src/utils/indexMonitor.js'

test('polls only status after acceptance and stops at completion', async () => {
  let scheduled, calls = 0
  const timers = { set: fn => { scheduled = fn; return 1 }, clear: () => { scheduled = null }, now: () => 0 }
  const monitor = createIndexMonitor({ build: async () => ({ state: 'PROCESSING' }), status: async () => { calls++; return { state: 'READY' } } }, () => 1, timers)
  await monitor.refresh('build'); assert.equal(monitor.status.value.state, 'PROCESSING')
  await scheduled(); assert.equal(calls, 1); assert.equal(scheduled, null)
  assert.equal(monitor.status.value.state, 'READY')
})
test('unmount ignores late acceptance and cannot start polling', async () => {
  let resolve, scheduled = false
  const monitor = createIndexMonitor({ build: () => new Promise(r => { resolve = r }) }, () => 1,
    { set: () => { scheduled = true }, clear: () => {}, now: () => 0 })
  const pending = monitor.refresh('build'); monitor.stop(); resolve({ state: 'PROCESSING' }); await pending
  assert.equal(scheduled, false); assert.equal(monitor.status.value, null)
})
test('polling pauses on elapsed window and does not resubmit build', async () => {
  let tick, time = 0, builds = 0
  const monitor = createIndexMonitor({ build: async () => { builds++; return { state: 'PROCESSING' } }, status: async () => ({ state: 'PROCESSING' }) }, () => 1,
    { set: fn => { tick = fn }, clear: () => { tick = null }, now: () => time })
  await monitor.refresh('build'); time = 300000; await tick()
  assert.equal(tick, null); assert.equal(builds, 1); assert.match(monitor.notice.value, /五分钟/)
})

test('query failure pauses polling without submitting another task', async () => {
  let tick, builds = 0
  const monitor = createIndexMonitor({ build: async () => { builds++; return { state: 'PROCESSING' } }, status: async () => { throw new Error('offline') } }, () => 1,
    { set: fn => { tick = fn }, clear: () => { tick = null }, now: () => 0 })
  await monitor.refresh('build'); await tick()
  assert.equal(tick, null); assert.equal(builds, 1); assert.ok(monitor.error.value)
  assert.match(monitor.notice.value, /暂停/)
})
