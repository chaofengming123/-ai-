<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useAuthStore } from '../stores/auth.js'
import { storedVectorStatus, saveVectors, searchVectors } from '../api/embeddings.js'
import { createVectorStorage } from '../utils/vectorStorage.js'
defineProps({ texts: { type: Array, required: true }, configured: Boolean })
const auth = useAuthStore()
const query = ref('如何上传文件？')
const { status, result, busy, error, message, run, reset } = createVectorStorage({
  status: (_, signal) => storedVectorStatus(signal), save: saveVectors, search: searchVectors,
}, () => auth.sessionVersion)
onMounted(() => run('status'))
onBeforeUnmount(reset)
</script>
<template>
  <section class="chunk-panel" aria-labelledby="stored-title">
    <h2 id="stored-title">保存后检索 · 第 26 课</h2>
    <p>将上面的候选文字保存到你自己的练习库。刷新或重新登录后仍可检索；保存会发送候选文字到硅基流动，检索只发送新问题。</p>
    <p v-if="status">当前模型 {{ status.model }} · 已保存 {{ status.count }} 段<span v-if="status.exists"> · {{ status.dimensions }} 维</span></p>
    <div class="load-controls">
      <button class="primary-button" :disabled="busy || !configured" @click="run('save', [...texts])">保存上面的候选文字</button>
      <button class="secondary-button" :disabled="busy" @click="run('status')">刷新存储状态</button>
    </div>
    <p>保存会追加新文字，相同文字再次保存会更新同一条记录。修改输入框不会删除已保存的旧文字。</p>
    <form class="chat-form" @submit.prevent="run('search', query)">
      <label for="stored-query">检索已保存文字</label>
      <textarea id="stored-query" v-model="query" required maxlength="1000" rows="2" :disabled="busy"></textarea>
      <button class="primary-button" :disabled="busy || !configured">{{ busy ? '正在处理……' : '检索最相近的 3 段' }}</button>
    </form>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="error" class="load-error" role="alert">{{ error }}</p>
    <section v-if="result" aria-label="持久化检索结果">
      <h3>问题：{{ result.query }}</h3>
      <p>结果按余弦相似度排序，分数不是正确率。当前仅检索文字，尚未生成问答。</p>
      <p v-if="!result.matches.length">没有找到已保存的文字。</p>
      <article v-for="(hit, index) in result.matches" :key="index" class="chunk-item">
        <h4>第 {{ index + 1 }} 名 · {{ hit.score.toFixed(4) }}</h4><p>{{ hit.text }}</p>
      </article>
    </section>
  </section>
</template>
