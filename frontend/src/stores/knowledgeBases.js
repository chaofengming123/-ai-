import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { knowledgeBases as initialKnowledgeBases } from '../data/knowledgeBases.js'

export const useKnowledgeBaseStore = defineStore('knowledge-bases', () => {
  const knowledgeBases = ref(initialKnowledgeBases.map(item => ({ ...item })))
  const knowledgeBaseCount = computed(() => knowledgeBases.value.length)

  function addKnowledgeBase({ name, description }) {
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
    knowledgeBases.value.push(knowledgeBase)
    return { knowledgeBase }
  }

  return { knowledgeBases, knowledgeBaseCount, addKnowledgeBase }
})
