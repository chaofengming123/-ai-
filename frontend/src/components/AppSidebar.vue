<template>
  <aside class="sidebar">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <div class="brand">
      <span class="brand-icon" aria-hidden="true">K</span>
      <div><strong>AI Knowledge</strong><small>企业知识管理平台</small></div>
    </div>
    <p class="nav-label">工作空间</p>
    <nav aria-label="主导航">
      <button class="nav-item" disabled title="后续课程开放">
        <span aria-hidden="true">▦</span> 工作台 <small>待开放</small>
      </button>
      <a class="nav-item active" href="#knowledge-bases" aria-current="page">
        <span aria-hidden="true">▤</span> 知识库
      </a>
      <button class="nav-item" disabled title="后续课程开放">
        <span aria-hidden="true">▧</span> 文档管理 <small>待开放</small>
      </button>
      <button class="nav-item" disabled title="后续课程开放">
        <span aria-hidden="true">✧</span> AI 问答 <small>待开放</small>
      </button>
    </nav>
    <div class="sidebar-footer"><span class="status-dot"></span> 本地工作空间 <span>v0.1</span></div>
  </aside>
</template>
