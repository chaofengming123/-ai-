// 只衡量人工标注的文档是否出现在本次候选中，不判断答案是否正确。
export function evaluateRetrieval(matches, expectedDocuments) {
  const expected = [...new Map(expectedDocuments.map(doc => [doc.id, doc])).values()]
  if (!expected.length) return null
  const foundIds = new Set(matches.map(hit => hit.documentId))
  const found = expected.filter(doc => foundIds.has(doc.id))
  const missing = expected.filter(doc => !foundIds.has(doc.id))
  const expectedIds = new Set(expected.map(doc => doc.id))
  const firstIndex = matches.findIndex(hit => expectedIds.has(hit.documentId))
  return {
    found, missing,
    recall: found.length / expected.length,
    firstRank: firstIndex < 0 ? null : firstIndex + 1,
    reciprocalRank: firstIndex < 0 ? 0 : 1 / (firstIndex + 1),
  }
}
