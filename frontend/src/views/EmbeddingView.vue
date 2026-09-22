<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { useAuthStore } from '../stores/auth.js'
import { fetchEmbeddingConfiguration, compareEmbeddings } from '../api/embeddings.js'
import { createEmbeddingExperiment } from '../utils/embeddingExperiment.js'
import StoredVectorLab from '../components/StoredVectorLab.vue'
const auth = useAuthStore()
const canUse = computed(() => auth.user?.permissions?.includes('chat:send'))
const configuration = ref(null), configError = ref(''), checking = ref(false)
const query = ref('如何把文件上传到知识库？')
const candidates = ref(['在文档管理中选择知识库，再选择文件并点击上传。', '登录时输入用户名与密码，通过验证后获得登录令牌。', '番茄炒蛋需要鸡蛋、番茄和适量盐。'])
const { result, error, busy, reset, run } = createEmbeddingExperiment(compareEmbeddings, () => auth.sessionVersion)
let active = true, controller
async function check() {
  if (!canUse.value || checking.value) return
  const version = auth.sessionVersion
  checking.value = true; configError.value = ''; controller = new AbortController()
  try {
    const data = await fetchEmbeddingConfiguration(controller.signal)
    if (active && version === auth.sessionVersion) configuration.value = data
  } catch (failure) { if (active && version === auth.sessionVersion) configError.value = failure.response?.data?.message || '无法读取向量模型配置。' }
  finally { if (active) checking.value = false }
}
function submit() { if (canUse.value && configuration.value?.configured) void run(query.value, [...candidates.value]) }
onMounted(check)
onBeforeUnmount(() => { active = false; controller?.abort(); reset() })
const numbers = values => values.map(number => number.toFixed(5)).join(', ')
</script>
<template>
  <section class="page" aria-labelledby="embedding-title">
    <div class="page-heading"><div><p class="eyebrow">理解语义检索</p><h1 id="embedding-title">向量实验</h1></div></div>
    <p class="demo-note">先比较一个问题与三段文字的向量相似度。可以粘贴分块内容；需要保存后反复检索时，使用下方的“保存后检索”。本页尚不生成聊天回答。</p>
    <p v-if="!canUse" class="load-error">当前账号没有向量实验权限。</p>
    <template v-else>
      <div class="load-controls"><span v-if="configuration">模型：{{ configuration.model }}</span><button class="secondary-button" :disabled="checking || busy" @click="check">检查配置</button></div>
      <p class="demo-note">点击计算会把问题与候选文字发送到云端模型服务。默认使用硅基流动免费模型 BAAI/bge-m3；配置检查仅检查填写是否完整，不会调用模型。</p>
      <p v-if="configError" class="load-error" role="alert">{{ configError }}</p>
      <p v-if="configuration && !configuration.configured" class="load-error">Embedding 配置不完整，请参考第二十五课配置并重启后端。</p>
      <form class="chat-form" @submit.prevent="submit">
        <label for="embedding-query">问题</label><textarea id="embedding-query" v-model="query" rows="2" maxlength="1000" required :disabled="busy"></textarea>
        <template v-for="(_, index) in candidates" :key="index"><label :for="`candidate-${index}`">候选文字 {{ index + 1 }}</label><textarea :id="`candidate-${index}`" v-model="candidates[index]" rows="3" maxlength="1000" required :disabled="busy"></textarea></template>
        <button type="submit" class="primary-button" :disabled="busy || !configuration?.configured">{{ busy ? '正在生成向量并比较……' : '计算相似度' }}</button>
      </form>
      <p v-if="error" class="load-error" role="alert">{{ error }}</p>
      <section v-if="result" class="chunk-panel" aria-label="向量比较结果">
        <h2>本次结果 · {{ result.dimensions }} 维</h2><p>问题：{{ result.query }}</p>
        <p>问题向量的前 8 个坐标（相似度使用全部维度）：</p><pre class="document-text-preview">[{{ numbers(result.queryVectorPreview) }}, …]</pre>
        <p>余弦相似度越高，表示本模型中的方向越接近；分数不是回答正确率，也不是相关概率。</p>
        <article v-for="(match, rank) in result.matches" :key="match.index" class="chunk-item">
          <h3>第 {{ rank + 1 }} 名 · 候选 {{ match.index + 1 }} · {{ match.similarity.toFixed(4) }}</h3>
          <p>{{ match.text }}</p><details><summary>查看向量前 8 个坐标</summary><pre class="document-text-preview">[{{ numbers(match.vectorPreview) }}, …]</pre></details>
        </article>
      </section>
      <StoredVectorLab :texts="candidates" :configured="configuration?.configured === true" />
    </template>
  </section>
</template>
