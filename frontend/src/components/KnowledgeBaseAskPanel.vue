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
const retrievalMode = ref('vector')
const keywordText = ref('')
const rerankEnabled = ref(false)
const bypassCache = ref(false)
const cacheLabels = { HIT: '命中：复用问题向量', MISS: '未命中：已生成并缓存', BYPASS: '跳过缓存：本次重新生成且不写缓存', NOT_USED: '未使用：没有参与检索的文档' }
const stageLabels = { EMBEDDING: '问题向量生成', VECTOR_SEARCH: '文档向量检索与校验', KEYWORD_SCAN: '关键词原文扫描与校验', RERANK: 'BGE 重排', GENERATION: 'GLM 生成与格式校验' }
const allowed = computed(() => ['knowledge-base:read', 'document:read', 'chat:send'].every(p => auth.user?.permissions?.includes(p)))
const { result, busy, error, run, reset } = createVectorStorage({
  search: async ({ mode, text, expectedDocuments, searchMode, keywords, rerank, bypassCache }, signal) => ({
    ...(await http.post(`/knowledge-bases/${props.baseId}/${mode}`, { query: text, mode: searchMode, keywords, rerank, bypassCache }, { signal, timeout: 300000 })).data,
    kind: mode,
    expectedDocuments,
  }),
}, () => auth.sessionVersion)
const retrieval = computed(() => result.value?.retrieval ?? result.value)
const evaluation = computed(() => retrieval.value ? evaluateRetrieval(retrieval.value.matches, result.value.expectedDocuments) : null)
const baselineEvaluation = computed(() => retrieval.value?.rerank?.enabled
  ? evaluateRetrieval(retrieval.value.rerank.before, result.value.expectedDocuments) : null)
function submit(mode) {
  if (!allowed.value || busy.value || !query.value.trim()) return
  // 请求开始时固定标注，之后修改选择不改变已经完成的评估。
  const expectedDocuments = props.documents.filter(doc => expectedIds.value.includes(doc.id))
    .map(doc => ({ id: doc.id, fileName: doc.fileName }))
  const keywords = retrievalMode.value === 'hybrid' ? keywordText.value.split(/[,，]/).map(word => word.trim()).filter(Boolean) : []
  return run('search', { mode, text: query.value, expectedDocuments, searchMode: retrievalMode.value, keywords, rerank: rerankEnabled.value, bypassCache: bypassCache.value })
}
const sources = computed(() => result.value?.kind === 'answer' ? result.value.sources : retrieval.value?.matches ?? [])
onBeforeUnmount(reset)
</script>
<template>
  <section class="chunk-panel" aria-label="知识库问答">
    <h2>知识库检索与问答 · 第 30 课</h2>
    <p>搜索当前知识库中最多 5 份模型兼容的成功索引。未建立索引或模型不兼容的文件会列出；不会搜索其他知识库。</p>
    <p>检索把问题发送到向量模型；启用重排还会把最多六段原文发送到硅基流动重排服务，生成回答再发送最多三段。可以先查找片段，再决定是否生成回答。</p>
    <p v-if="!allowed">需要知识库查看、文档查看和 AI 对话权限。</p>
    <form class="chat-form" @submit.prevent="submit('search')">
      <label for="base-rag-query">向当前知识库提问</label>
      <textarea id="base-rag-query" v-model="query" rows="2" maxlength="1000" required :disabled="busy"></textarea>
      <label for="retrieval-mode">检索方式 · 第 32 课</label>
      <select id="retrieval-mode" v-model="retrievalMode" :disabled="busy">
        <option value="vector">向量检索</option>
        <option value="hybrid">混合检索：向量 + 关键词</option>
      </select>
      <template v-if="retrievalMode === 'hybrid'">
        <label for="retrieval-keywords">关键词（逗号分隔，1–5 个，每个最多 40 字符）</label>
        <input id="retrieval-keywords" v-model="keywordText" maxlength="204" :disabled="busy" placeholder="例如：1 MB, UTF-8">
        <p>关键词按原文字面匹配，忽略大小写。它们用于查找资料，不是预期文档标注；混合检索仍会调用向量模型。</p>
      </template>
      <label><input v-model="bypassCache" type="checkbox" :disabled="busy || !allowed"> 本次跳过问题向量缓存 · 第 35 课</label>
      <p>默认复用同一用户、同一模型配置下的问题向量，最多保存 5 分钟；文档检索和答案仍每次重新处理。后端重启会清空缓存。</p>
      <label><input v-model="rerankEnabled" type="checkbox" :disabled="busy || !allowed"> 启用 BGE 重排 · 第 33 课</label>
      <p>默认关闭。开启后，候选至少两段时额外调用一次 BAAI/bge-reranker-v2-m3，从最多六段中选出并排序最多三段；可能更慢，效果需对照原文核实。</p>
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
      <p v-if="retrieval.embeddingCache">问题向量缓存：{{ cacheLabels[retrieval.embeddingCache] ?? retrieval.embeddingCache }}</p>
      <details v-if="retrieval.timings">
        <summary>本次耗时 · 第 34 课：后端 {{ retrieval.timings.totalMillis }} ms</summary>
        <table>
          <thead><tr><th scope="col">阶段</th><th scope="col">执行次数</th><th scope="col">累计耗时</th></tr></thead>
          <tbody><tr v-for="step in retrieval.timings.steps" :key="step.stage">
            <td>{{ stageLabels[step.stage] ?? step.stage }}</td><td>{{ step.calls }}</td><td>{{ step.calls ? step.millis + ' ms' : '未执行' }}</td>
          </tr></tbody>
        </table>
        <p>其他处理：{{ retrieval.timings.otherMillis }} ms。统计后端业务处理，不含浏览器网络传输或页面渲染。阶段次数不是 HTTP 请求数；少于 1 ms 的执行显示 0 ms。</p>
      </details>
      <p>本次方式：{{ retrieval.mode === 'hybrid' ? '混合检索' : '向量检索' }}<span v-if="retrieval.mode === 'hybrid'">；关键词：{{ retrieval.keywords.join('、') }}。融合分数用于排序，不是相似度或正确概率。</span></p>
      <p>本次检索 {{ retrieval.searchedDocuments }} / {{ retrieval.totalDocuments }} 份文档，合并去除完全相同的原文后最多选择三个片段。</p>
      <section v-if="retrieval.rerank?.enabled" aria-label="重排对照">
        <p v-if="retrieval.rerank.applied">本次使用 {{ retrieval.rerank.model }} 重排 {{ retrieval.rerank.candidateCount }} 段候选，显示顺序为重排后的顺序。原始分数不随重排改变。</p>
        <p v-else>候选不足两段，跳过模型重排。</p>
        <details>
          <summary>查看重排前的前三段</summary>
          <article v-for="hit in retrieval.rerank.before" :key="`${hit.documentId}-${hit.chunkIndex}`" class="chunk-item">
            <p>原排名 {{ hit.sourceId }} · {{ hit.fileName }} · 文档 {{ hit.documentId }} · 块 {{ hit.chunkIndex + 1 }}</p>
            <pre class="document-text-preview">{{ hit.text }}</pre>
          </article>
        </details>
      </section>
      <ul v-if="retrieval.skipped.length"><li v-for="item in retrieval.skipped" :key="item.documentId">未参与：{{ item.fileName }}（{{ item.reason }}）</li></ul>
      <section v-if="evaluation" aria-label="检索评估结果">
        <h3>本次人工标注文档的检索表现</h3>
        <p v-if="baselineEvaluation">重排前：覆盖率 {{ (baselineEvaluation.recall * 100).toFixed(1) }}%，RR {{ baselineEvaluation.reciprocalRank.toFixed(3) }}。下方为最终结果指标，使用同一次检索与同一份标注。</p>
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
        <p>文档 {{ source.documentId }} · {{ retrieval.rerank?.applied ? '重排前' : '' }}{{ retrieval.mode === 'hybrid' ? '融合分数' : '相似度' }} {{ source.score.toFixed(4) }} · 索引时间 {{ source.indexedAt?.replace('T', ' ') }}</p>
        <p v-if="source.usingPreviousVersion">使用最近一次重建之前的成功版本。</p>
        <p>{{ source.note }}</p>
        <p>正文字符范围 [{{ source.startOffset }}, {{ source.endOffset }})，不是页码。</p>
        <pre class="document-text-preview">{{ source.text }}</pre>
      </article>
    </section>
  </section>
</template>
