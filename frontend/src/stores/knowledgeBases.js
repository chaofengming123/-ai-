import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchKnowledgeBases, createKnowledgeBase } from '../api/knowledgeBases.js'
import { apiErrorMessage } from '../api/http.js'

export const useKnowledgeBaseStore = defineStore('knowledge-bases', () => {
  const knowledgeBases = ref([])
  const isLoading = ref(false)
  const isSaving = ref(false)
  const loadError = ref('')
  const hasLoaded = ref(false)

  async function loadKnowledgeBases() {
    if (isLoading.value || isSaving.value) return
    isLoading.value = true
    loadError.value = ''
    try {
      const records = await fetchKnowledgeBases()
      knowledgeBases.value = records
      hasLoaded.value = true
    } catch (error) {
      loadError.value = apiErrorMessage(error)
    } finally {
      isLoading.value = false
    }
  }
  const knowledgeBaseCount = computed(() => knowledgeBases.value.length)

  async function addKnowledgeBase({ name, description }) {
    if (isSaving.value) return { error: '正在提交，请勿重复创建。' }
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
    isSaving.value = true
    try {
      const knowledgeBase = await createKnowledgeBase({ name: trimmedName, description: trimmedDescription })
      knowledgeBases.value.push(knowledgeBase)
      return { knowledgeBase }
    } catch (error) {
      return { error: apiErrorMessage(error, { creating: true }) }
    } finally {
      isSaving.value = false
    }
  }

  return { knowledgeBases, knowledgeBaseCount, addKnowledgeBase, isLoading, isSaving, loadError, hasLoaded, loadKnowledgeBases }
})
