import { ref } from 'vue'
import { createVectorStorage } from './vectorStorage.js'

// Browser timers require their global receiver; do not store the native methods directly.
const defaultTimers = {
  set: (callback, delay) => globalThis.setTimeout(callback, delay),
  clear: handle => globalThis.clearTimeout(handle),
  now: () => Date.now(),
}

export function createIndexMonitor(api, sessionVersion, timers = defaultTimers) {
  const store = createVectorStorage(api, sessionVersion)
  const notice = ref('')
  let timer, stopped = false, deadline = 0
  const version = sessionVersion()
  async function refresh(action = 'status', automatic = false) {
    if (stopped || store.busy.value || version !== sessionVersion()) return
    timers.clear(timer)
    if (!automatic) { deadline = timers.now() + 300000; notice.value = '' }
    await store.run(action)
    if (stopped || version !== sessionVersion()) return
    if (store.error.value) { notice.value = '自动刷新已暂停，可手动刷新确认状态。'; return }
    if (store.status.value?.state === 'PROCESSING') {
      if (timers.now() >= deadline) notice.value = '已自动查询五分钟，可稍后手动刷新；任务可能仍在执行。'
      else timer = timers.set(() => refresh('status', true), 2000)
    }
  }
  function stop() { stopped = true; timers.clear(timer); store.reset() }
  return { ...store, notice, refresh, stop }
}
