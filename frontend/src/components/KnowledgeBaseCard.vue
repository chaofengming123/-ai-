<script setup>
// Props 是父页面传入的数据；卡片只负责展示，不修改它。
defineProps({
  busy: Boolean,
  canManage: Boolean,
  knowledgeBase: {
    type: Object,
    required: true,
  },
})

// 声明卡片会向父页面发出的事件。
const emit = defineEmits(['view-detail', 'edit', 'delete'])
</script>

<template>
      <article class="knowledge-card">
        <div class="card-topline">
          <span class="folder-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M3 7a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v10H3V7Z" />
              <path d="M3 10h18" />
            </svg>
          </span>
          <span class="category">{{ knowledgeBase.category }}</span>
        </div>
        <h3>{{ knowledgeBase.name }}</h3>
        <p>{{ knowledgeBase.description }}</p>
        <footer><span class="document-dot" aria-hidden="true"></span>{{ knowledgeBase.documentCount }} 份文档<button type="button" class="card-detail-button" :aria-label="`查看${knowledgeBase.name}的详情`" @click="emit('view-detail', knowledgeBase.id)">查看详情 →</button></footer>
        <div v-if="canManage" class="card-actions">
          <button type="button" class="secondary-button" :disabled="busy" :aria-label="`编辑${knowledgeBase.name}`" @click="emit('edit', knowledgeBase.id)">编辑</button>
          <button type="button" class="danger-button" :disabled="busy" :aria-label="`删除${knowledgeBase.name}`" @click="emit('delete', knowledgeBase.id)">删除</button>
        </div>
      </article>
</template>
