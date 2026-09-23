import test from 'node:test'
import assert from 'node:assert/strict'
import { evaluateRetrieval } from '../src/utils/retrievalEvaluation.js'
const docs = [{ id: 1, fileName: 'A' }, { id: 2, fileName: 'B' }]
test('未标注不等于零分或无答案', () => {
  assert.equal(evaluateRetrieval([], []), null)
})
test('重复片段和重复标注不能增加文档覆盖率', () => {
  const result = evaluateRetrieval([{ documentId: 1 }, { documentId: 1 }], [...docs, docs[0]])
  assert.equal(result.recall, 0.5)
  assert.deepEqual(result.missing, [docs[1]])
  assert.equal(result.reciprocalRank, 1)
})
test('排名按最终片段列表计算，不按去重后的文档列表计算', () => {
  const result = evaluateRetrieval([{ documentId: 9 }, { documentId: 9 }, { documentId: 2 }], docs)
  assert.equal(result.firstRank, 3)
  assert.equal(result.reciprocalRank, 1 / 3)
})
test('零命中保留全部缺失文档且 RR 为零', () => {
  const result = evaluateRetrieval([], docs)
  assert.equal(result.recall, 0)
  assert.equal(result.firstRank, null)
  assert.equal(result.reciprocalRank, 0)
  assert.deepEqual(result.missing, docs)
})
test('同名文件按 ID 区分，完整覆盖为一', () => {
  const expected = [{ id: 1, fileName: 'same' }, { id: 2, fileName: 'same' }]
  assert.equal(evaluateRetrieval([{ documentId: 1 }], expected).recall, 0.5)
  assert.equal(evaluateRetrieval([{ documentId: 2 }, { documentId: 1 }], expected).recall, 1)
})
