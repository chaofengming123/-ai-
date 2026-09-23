<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { useAuthStore } from '../stores/auth.js'
import { http } from '../api/http.js'
import { createVectorStorage } from '../utils/vectorStorage.js'
const props = defineProps({ baseId: { type: Number, required: true } })
const auth = useAuthStore()
const query = ref('这个知识库有哪些操作要求？')
const allowed = computed(() => ['knowledge-base:read', 'document:read', 'chat:send'].every(p => auth.user?.permissions?.includes(p)))
const { result, busy, error, run, reset } = createVectorStorage({
  search: async ({ mode, text }, signal) => ({
    ...(await http.post(`/knowledge-bases/${props.baseId}/${mode}`, { query: text }, { signal, timeout: 300000 })).data,
    kind: mode,
  }),
}, () => auth.sessionVersion)
const retrieval = computed(() => result.value?.retrieval ?? result.value)
const sources = computed(() => result.value?.kind === 'answer' ? result.value.sources : retrieval.value?.matches ?? [])
onBeforeUnmount(reset)
</script>
<template>
  <section class="chunk-panel" aria-label="知识库问答">
    <h2>知识库检索与问答 · 第 30 课</h2>
    <p>搜索当前知识库中最多 5 份模型兼容的成功索引。未建立索引或模型不兼容的文件会列出；不会搜索其他知识库。</p>
    <p>检索把问题发送到向量模型；回答还会把最多三段原文发送到 GLM。可以先查找片段，再决定是否生成回答。</p>
    <p v-if="!allowed">需要知识库查看、文档查看和 AI 对话权限。</p>
    <form class="chat-form" @submit.prevent="allowed && run('search', { mode: 'search', text: query })">
      <label for="base-rag-query">向当前知识库提问</label>
      <textarea id="base-rag-query" v-model="query" rows="2" maxlength="1000" required :disabled="busy"></textarea>
      <button class="primary-button" :disabled="busy || !allowed || !query.trim()">{{ busy ? '正在处理……' : '查找相关片段' }}</button>
      <button type="button" class="primary-button" :disabled="busy || !allowed || !query.trim()" @click="run('search', { mode: 'answer', text: query })">生成知识库回答</button>
    </form>
    <p v-if="error" class="load-error" role="alert">{{ error }}</p>
    <section v-if="retrieval" aria-label="知识库结果">
      <p>知识库：{{ retrieval.knowledgeBaseName }} · 问题：{{ retrieval.query }}</p>
      <p>本次检索 {{ retrieval.searchedDocuments }} / {{ retrieval.totalDocuments }} 份文档，合并去除完全相同的原文后最多选择三个片段。</p>
      <ul v-if="retrieval.skipped.length"><li v-for="item in retrieval.skipped" :key="item.documentId">未参与：{{ item.fileName }}（{{ item.reason }}）</li></ul>
      <template v-if="result.kind === 'answer'">
        <h3>{{ result.insufficient ? '当前资料不足' : '基于知识库片段的回答' }}</h3>
        <pre class="document-text-preview">{{ result.answer }}</pre>
        <p>回答模型：{{ result.model }}。编号已校验，请对照引用原文核实。</p>
      </template>
      <p v-else-if="!sources.length">当前没有可用片段。请先建立文档索引，再尝试相关问题。</p>
      <article v-for="source in sources" :key="source.sourceId" class="chunk-item">
        <h3>来源 {{ source.sourceId }} · {{ source.fileName }} · 块 {{ source.chunkIndex + 1 }}</h3>
        <p>文档 {{ source.documentId }} · 相似度 {{ source.score.toFixed(4) }} · 索引时间 {{ source.indexedAt?.replace('T', ' ') }}</p>
        <p v-if="source.usingPreviousVersion">使用最近一次重建之前的成功版本。</p>
        <p>{{ source.note }}</p>
        <p>正文字符范围 [{{ source.startOffset }}, {{ source.endOffset }})，不是页码。</p>
        <pre class="document-text-preview">{{ source.text }}</pre>
      </article>
    </section>
  </section>
</template>
