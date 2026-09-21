import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchKnowledgeBases, createKnowledgeBase, updateKnowledgeBase, deleteKnowledgeBase } from '../api/knowledgeBases.js'
import { apiErrorMessage } from '../api/http.js'

export const useKnowledgeBaseStore = defineStore('knowledge-bases', () => {
  const knowledgeBases = ref([])
  const isLoading = ref(false)
  const isSaving = ref(false)
  const loadError = ref('')
  const hasLoaded = ref(false)
  let generation = 0

  function reset() {
    generation++
    knowledgeBases.value = []
    hasLoaded.value = false
    isLoading.value = false
    isSaving.value = false
    loadError.value = ''
  }

  async function loadKnowledgeBases() {
    if (isLoading.value || isSaving.value) return
    const requestGeneration = generation
    isLoading.value = true
    loadError.value = ''
    try {
      const records = await fetchKnowledgeBases()
      if (requestGeneration !== generation) return { error: '登录状态已变化，请重新操作。' }
      knowledgeBases.value = records
      hasLoaded.value = true
    } catch (error) {
      if (requestGeneration !== generation) return { error: '登录状态已变化，请重新操作。' }
      loadError.value = apiErrorMessage(error)
    } finally {
      if (requestGeneration === generation) isLoading.value = false
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
    const requestGeneration = generation
    isSaving.value = true
    try {
      const knowledgeBase = await createKnowledgeBase({ name: trimmedName, description: trimmedDescription })
      if (requestGeneration !== generation) return { error: '登录状态已变化，请重新操作。' }
      knowledgeBases.value.push(knowledgeBase)
      return { knowledgeBase }
    } catch (error) {
      if (requestGeneration !== generation) return { error: '登录状态已变化，请重新操作。' }
      return { error: apiErrorMessage(error, { creating: true }) }
    } finally {
      if (requestGeneration === generation) isSaving.value = false
    }
  }

  async function editKnowledgeBase(id, data) {
    if (isLoading.value || isSaving.value) return { error: '请等待当前操作完成。' }
    const requestGeneration = generation
    isSaving.value = true
    try {
      const record = await updateKnowledgeBase(id, data)
      if (requestGeneration !== generation) return { error: '登录状态已变化，请重新操作。' }
      knowledgeBases.value = knowledgeBases.value.map(item => item.id === id ? record : item)
      return { knowledgeBase: record }
    } catch (error) {
      if (requestGeneration !== generation) return { error: '登录状态已变化，请重新操作。' }
      return { error: apiErrorMessage(error, { mutating: true }) }
    } finally {
      if (requestGeneration === generation) isSaving.value = false
    }
  }

  async function removeKnowledgeBase(id) {
    if (isLoading.value || isSaving.value) return { error: '请等待当前操作完成。' }
    const requestGeneration = generation
    isSaving.value = true
    try {
      await deleteKnowledgeBase(id)
      if (requestGeneration !== generation) return { error: '登录状态已变化，请重新操作。' }
      knowledgeBases.value = knowledgeBases.value.filter(item => item.id !== id)
      return { success: true }
    } catch (error) {
      if (requestGeneration !== generation) return { error: '登录状态已变化，请重新操作。' }
      return { error: apiErrorMessage(error, { mutating: true }) }
    } finally {
      if (requestGeneration === generation) isSaving.value = false
    }
  }

  return { reset, knowledgeBases, knowledgeBaseCount, addKnowledgeBase, editKnowledgeBase, removeKnowledgeBase, isLoading, isSaving, loadError, hasLoaded, loadKnowledgeBases }
})
