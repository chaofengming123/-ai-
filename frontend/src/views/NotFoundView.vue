<script setup>
import { RouterLink } from 'vue-router'
</script>

<template>
  <section class="page" aria-labelledby="not-found-title">
    <p class="eyebrow">404</p>
    <h1 id="not-found-title">页面不存在</h1>
    <p class="page-description">请检查地址，或返回知识库继续浏览。</p>
    <RouterLink to="/knowledge-bases" class="primary-button page-link">返回知识库</RouterLink>
  </section>
</template>
