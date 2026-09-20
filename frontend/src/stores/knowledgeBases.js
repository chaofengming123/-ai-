import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchKnowledgeBases, insertMockKnowledgeBase } from '../api/knowledgeBases.js'

export const useKnowledgeBaseStore = defineStore('knowledge-bases', () => {
  const knowledgeBases = ref([])
  const isLoading = ref(false)
  const loadError = ref('')
  const hasLoaded = ref(false)

  async function loadKnowledgeBases(options = {}) {
    if (isLoading.value) return
    isLoading.value = true
    loadError.value = ''
    try {
      const records = await fetchKnowledgeBases(options)
      knowledgeBases.value = records
      hasLoaded.value = true
    } catch (error) {
      loadError.value = error instanceof Error ? error.message : '加载失败，请重试。'
    } finally {
      isLoading.value = false
    }
  }
  const knowledgeBaseCount = computed(() => knowledgeBases.value.length)

  function addKnowledgeBase({ name, description }) {
    if (isLoading.value || !hasLoaded.value) {
      return { error: '请等待知识库加载完成后再创建。' }
    }
    const trimmedName = name.trim()
    const trimmedDescription = description.trim()

    if (!trimmedName) {
      return { error: '请输入知识库名称，不能只填写空格。' }
    }
    if (trimmedName.length > 60 || trimmedDescription.length > 300) {
      return { error: '名称最多 60 个字符，描述最多 300 个字符。' }
    }
    if (knowledgeBases.value.some(item => item.name === trimmedName)) {
      return { error: '这个名称已经存在，请换一个名称。' }
    }

    const knowledgeBase = {
      id: crypto.randomUUID(),
      name: trimmedName,
      description: trimmedDescription || '暂无描述',
      documentCount: 0,
      category: '自建知识库',
    }
    insertMockKnowledgeBase(knowledgeBase)
    knowledgeBases.value.push(knowledgeBase)
    return { knowledgeBase }
  }

  return { knowledgeBases, knowledgeBaseCount, addKnowledgeBase, isLoading, loadError, hasLoaded, loadKnowledgeBases }
})
