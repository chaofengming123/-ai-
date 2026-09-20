import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import { http } from '../src/api/http.js'
import { useKnowledgeBaseStore } from '../src/stores/knowledgeBases.js'

// 使用可控传输层验证等待与失败，避免测试修改正在运行的后端数据。
test('shared store handles pending creation, server errors and recovery', async () => {
  const original = http.defaults.adapter
  try {
    setActivePinia(createPinia())
    const store = useKnowledgeBaseStore()
    const records = [{ id: 1, name: '已有记录', description: '说明', category: '示例', documentCount: 0 }]
    const respond = (config, data) => ({ data, status: 200, statusText: 'OK', headers: {}, config })
    http.defaults.adapter = async config => respond(config, records)
    await store.loadKnowledgeBases()
    assert.equal(store.knowledgeBaseCount, 1)

    let finish
    let calls = 0
    http.defaults.adapter = config => {
      calls++
      return new Promise(resolve => { finish = data => resolve(respond(config, data)) })
    }
    const pending = store.addKnowledgeBase({ name: '  新记录  ', description: '' })
    assert.equal(store.isSaving, true)
    assert.equal(store.knowledgeBaseCount, 1)
    assert.ok((await store.addKnowledgeBase({ name: '重复点击', description: '' })).error)
    await store.loadKnowledgeBases()
    assert.equal(calls, 1)
    finish({ id: 10, name: '新记录', description: '暂无描述', category: '自建知识库', documentCount: 0 })
    await pending
    assert.equal(store.isSaving, false)
    assert.equal(store.knowledgeBases[1].id, 10)

    http.defaults.adapter = async () => { throw { response: { status: 409, data: { message: '后端重名提示' } } } }
    assert.equal((await store.addKnowledgeBase({ name: '新记录', description: '' })).error, '后端重名提示')
    assert.equal(store.knowledgeBaseCount, 2)
    assert.equal(store.isSaving, false)

    http.defaults.adapter = async () => { throw new Error('Network Error') }
    const failedCreate = await store.addKnowledgeBase({ name: '连接失败', description: '' })
    assert.match(failedCreate.error, /未能确认创建结果/)
    assert.equal(store.knowledgeBaseCount, 2)
    await store.loadKnowledgeBases()
    assert.ok(store.loadError)
    assert.equal(store.hasLoaded, true)
    assert.equal(store.isLoading, false)
    assert.equal(store.knowledgeBaseCount, 2)

    http.defaults.adapter = async config => respond(config, records.slice(0, 1))
    await store.loadKnowledgeBases()
    assert.equal(store.loadError, '')
    assert.equal(store.knowledgeBaseCount, 1)

    setActivePinia(createPinia())
    const fresh = useKnowledgeBaseStore()
    http.defaults.adapter = async () => { throw new Error('offline') }
    await fresh.loadKnowledgeBases()
    assert.equal(fresh.hasLoaded, false)
    assert.equal(fresh.isLoading, false)
    assert.ok(fresh.loadError)
  } finally {
    http.defaults.adapter = original
  }
})
