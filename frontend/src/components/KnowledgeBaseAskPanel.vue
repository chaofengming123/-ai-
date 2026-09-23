<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { useAuthStore } from '../stores/auth.js'
import { http } from '../api/http.js'
import { createVectorStorage } from '../utils/vectorStorage.js'
import { evaluateRetrieval } from '../utils/retrievalEvaluation.js'
const props = defineProps({ baseId: { type: Number, required: true }, documents: { type: Array, default: () => [] } })
const auth = useAuthStore()
const query = ref('这个知识库有哪些操作要求？')
const expectedIds = ref([])
const allowed = computed(() => ['knowledge-base:read', 'document:read', 'chat:send'].every(p => auth.user?.permissions?.includes(p)))
const { result, busy, error, run, reset } = createVectorStorage({
  search: async ({ mode, text, expectedDocuments }, signal) => ({
    ...(await http.post(`/knowledge-bases/${props.baseId}/${mode}`, { query: text }, { signal, timeout: 300000 })).data,
    kind: mode,
    expectedDocuments,
  }),
}, () => auth.sessionVersion)
const retrieval = computed(() => result.value?.retrieval ?? result.value)
const evaluation = computed(() => retrieval.value ? evaluateRetrieval(retrieval.value.matches, result.value.expectedDocuments) : null)
function submit(mode) {
  if (!allowed.value || busy.value || !query.value.trim()) return
  // 请求开始时固定标注，之后修改选择不改变已经完成的评估。
  const expectedDocuments = props.documents.filter(doc => expectedIds.value.includes(doc.id))
    .map(doc => ({ id: doc.id, fileName: doc.fileName }))
  return run('search', { mode, text: query.value, expectedDocuments })
}
const sources = computed(() => result.value?.kind === 'answer' ? result.value.sources : retrieval.value?.matches ?? [])
onBeforeUnmount(reset)
</script>
<template>
  <section class="chunk-panel" aria-label="知识库问答">
    <h2>知识库检索与问答 · 第 30 课</h2>
    <p>搜索当前知识库中最多 5 份模型兼容的成功索引。未建立索引或模型不兼容的文件会列出；不会搜索其他知识库。</p>
    <p>检索把问题发送到向量模型；回答还会把最多三段原文发送到 GLM。可以先查找片段，再决定是否生成回答。</p>
    <p v-if="!allowed">需要知识库查看、文档查看和 AI 对话权限。</p>
    <form class="chat-form" @submit.prevent="submit('search')">
      <label for="base-rag-query">向当前知识库提问</label>
      <textarea id="base-rag-query" v-model="query" rows="2" maxlength="1000" required :disabled="busy"></textarea>
      <fieldset :disabled="busy || !allowed">
        <legend>检索评估 · 第 31 课（可选）</legend>
        <p>先阅读资料，勾选应包含答案的文档，再发起请求。标注只用于本次结果对照，不影响检索。</p>
        <label v-for="doc in documents" :key="doc.id" style="display: block">
          <input v-model="expectedIds" type="checkbox" :value="doc.id"> {{ doc.fileName }}（文档 {{ doc.id }}）
        </label>
        <p v-if="!documents.length">文档列表尚无可标注项目。</p>
      </fieldset>
      <button class="primary-button" :disabled="busy || !allowed || !query.trim()">{{ busy ? '正在处理……' : '查找相关片段' }}</button>
      <button type="button" class="primary-button" :disabled="busy || !allowed || !query.trim()" @click="submit('answer')">生成知识库回答</button>
    </form>
    <p v-if="error" class="load-error" role="alert">{{ error }}</p>
    <section v-if="retrieval" aria-label="知识库结果">
      <p>知识库：{{ retrieval.knowledgeBaseName }} · 问题：{{ retrieval.query }}</p>
      <p>本次检索 {{ retrieval.searchedDocuments }} / {{ retrieval.totalDocuments }} 份文档，合并去除完全相同的原文后最多选择三个片段。</p>
      <ul v-if="retrieval.skipped.length"><li v-for="item in retrieval.skipped" :key="item.documentId">未参与：{{ item.fileName }}（{{ item.reason }}）</li></ul>
      <section v-if="evaluation" aria-label="检索评估结果">
        <h3>本次人工标注文档的检索表现</h3>
        <p>预期文档覆盖率：{{ (evaluation.recall * 100).toFixed(1) }}%（{{ evaluation.found.length }} / {{ result.expectedDocuments.length }}）。</p>
        <p>首个预期来源的片段排名：{{ evaluation.firstRank ?? '未命中' }}；倒数排名 RR：{{ evaluation.reciprocalRank.toFixed(3) }}。</p>
        <p>请求时标注：{{ result.expectedDocuments.map(doc => `${doc.fileName}（${doc.id}）`).join('、') }}</p>
        <p v-if="evaluation.missing.length">未找回：{{ evaluation.missing.map(doc => `${doc.fileName}（${doc.id}）`).join('、') }}</p>
        <p>依据最终最多三个检索片段计算，重复文档只计一次覆盖。命中文档不保证片段包含答案，也不等于回答正确；修改勾选后需重新提交。</p>
      </section>
      <p v-else>本次未标注预期文档，不计算检索指标；这不代表问题无答案。</p>
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
