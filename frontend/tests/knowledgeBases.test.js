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

test('edits and deletes update state only after success and block concurrent mutations', async () => {
  const original = http.defaults.adapter
  try {
    setActivePinia(createPinia())
    const store = useKnowledgeBaseStore()
    const record = { id: 42, name: '原名', description: '说明', category: '自建知识库', documentCount: 0 }
    const respond = (config, data) => ({ data, status: 200, headers: {}, config })
    http.defaults.adapter = async config => respond(config, [record])
    await store.loadKnowledgeBases()
    let finish
    http.defaults.adapter = config => {
      assert.equal(config.method, 'put')
      assert.equal(config.url, '/knowledge-bases/42')
      return new Promise(resolve => { finish = () => resolve(respond(config, { ...record, name: '新名' })) })
    }
    const editing = store.editKnowledgeBase(42, { name: '新名' })
    assert.equal(store.knowledgeBases[0].name, '原名')
    assert.ok((await store.removeKnowledgeBase(42)).error)
    finish()
    await editing
    assert.equal(store.knowledgeBases[0].name, '新名')
    http.defaults.adapter = async () => { throw { response: { status: 409, data: { message: '重名' } } } }
    assert.equal((await store.editKnowledgeBase(42, { name: '冲突' })).error, '重名')
    assert.equal(store.knowledgeBases[0].name, '新名')
    http.defaults.adapter = async () => { throw new Error('offline') }
    assert.match((await store.removeKnowledgeBase(42)).error, /未能确认操作结果/)
    assert.equal(store.knowledgeBaseCount, 1)
    assert.equal(store.isSaving, false)
    http.defaults.adapter = config => {
      assert.equal(config.method, 'delete')
      return new Promise(resolve => { finish = () => resolve({ ...respond(config, ''), status: 204 }) })
    }
    const deleting = store.removeKnowledgeBase(42)
    assert.equal(store.knowledgeBaseCount, 1)
    assert.ok((await store.editKnowledgeBase(42, { name: '并发修改' })).error)
    finish()
    assert.equal((await deleting).success, true)
    assert.equal(store.knowledgeBaseCount, 0)
    assert.equal(store.isSaving, false)
  } finally {
    http.defaults.adapter = original
  }
})
