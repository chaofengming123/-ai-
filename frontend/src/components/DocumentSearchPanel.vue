<script setup>
import { ref, computed, onBeforeUnmount } from 'vue'
import { useAuthStore } from '../stores/auth.js'
import { searchDocument } from '../api/documents.js'
import { createVectorStorage } from '../utils/vectorStorage.js'
const props = defineProps({ documentId: { type: Number, required: true }, available: Boolean })
const auth = useAuthStore()
const query = ref('这份文档说明了什么？')
const canSearch = computed(() => ['document:read', 'chat:send'].every(p => auth.user?.permissions?.includes(p)))
const { result, busy, error, run, reset } = createVectorStorage({
  search: (text, signal) => searchDocument(props.documentId, text, signal),
}, () => auth.sessionVersion)
onBeforeUnmount(reset)
</script>
<template>
  <section class="chunk-panel" aria-label="文档检索">
    <h3>检索这份文档 · 第 28 课</h3>
    <p>提交时仅将问题发送到向量模型，再从这份文档的成功索引中查找片段。这里展示原文，不生成聊天回答。</p>
    <p v-if="!available">需要先有与当前模型匹配的成功索引；建立或重建后刷新索引状态。</p>
    <p v-if="!canSearch">检索需要同时拥有文档查看和 AI 对话权限。</p>
    <form class="chat-form" @submit.prevent="canSearch && available && run('search', query)">
      <label for="document-search-query">你的问题</label>
      <textarea id="document-search-query" v-model="query" rows="2" maxlength="1000" required :disabled="busy"></textarea>
      <button class="primary-button" :disabled="busy || !available || !canSearch || !query.trim()">{{ busy ? '正在检索……' : '查找最相近的 3 个片段' }}</button>
    </form>
    <p v-if="error" class="load-error" role="alert">{{ error }}</p>
    <section v-if="result" aria-label="文档检索结果">
      <p>问题：{{ result.query }}</p>
      <p>来源：{{ result.fileName }} · 文档 {{ result.documentId }} · 索引时间 {{ result.indexedAt?.replace('T', ' ') }}</p>
      <p v-if="result.usingPreviousVersion">最近一次重建正在处理或失败，本次使用之前成功发布的版本。</p>
      <p>{{ result.note }}</p>
      <p>相似度只表示向量接近程度，不是正确率；最相近的片段也可能无法回答问题。</p>
      <p v-if="!result.matches.length">当前索引没有返回片段，请刷新状态或联系编辑者检查索引。</p>
      <article v-for="(hit, rank) in result.matches" :key="hit.chunkIndex" class="chunk-item">
        <h4>第 {{ rank + 1 }} 名 · 块 {{ hit.chunkIndex + 1 }} · 相似度 {{ hit.score.toFixed(4) }}</h4>
        <p>正文字符范围 [{{ hit.startOffset }}, {{ hit.endOffset }})，不是原文件页码。</p>
        <pre class="document-text-preview">{{ hit.text }}</pre>
      </article>
    </section>
  </section>
</template>
