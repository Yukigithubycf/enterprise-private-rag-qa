<script setup lang="ts">
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';
</script>

<template>
  <div class="paper-page h-full flex flex-col gap-4">
    <section class="rag-workbench-header flex items-center justify-between gap-4">
      <div class="min-w-0">
        <div class="flex items-center gap-2">
          <span class="header-mark flex-center">
            <icon-solar:database-bold-duotone class="text-20px text-primary" />
          </span>
          <h1 class="m-0 truncate text-18px font-600">RAG 问答工作台</h1>
        </div>
        <p class="m-0 mt-1 text-13px color-[rgb(var(--base-text-color)/0.58)]">
          面向企业制度、流程、项目资料的私有知识库问答
        </p>
      </div>
      <div class="header-tags flex flex-wrap justify-end gap-2 lt-sm:hidden">
        <NTag :bordered="false" size="small">权限隔离</NTag>
        <NTag :bordered="false" size="small" type="info">混合检索</NTag>
        <NTag :bordered="false" size="small" type="success">来源追溯</NTag>
      </div>
    </section>
    <div class="paper-stage min-h-0 flex flex-col flex-1 card-wrapper">
      <ChatList />
    </div>
    <InputBox />
  </div>
</template>

<style scoped>
.rag-workbench-header {
  min-height: 74px;
  border: 1px solid rgb(15 23 42 / 0.08);
  border-radius: 8px;
  background: rgb(var(--container-bg-color));
  padding: 14px 18px;
  box-shadow: 0 10px 28px -24px rgb(15 23 42 / 0.28);
}

.header-mark {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  background: rgb(var(--primary-color) / 0.08);
}

.header-tags :deep(.n-tag) {
  border-radius: 6px;
}

html.dark .rag-workbench-header {
  border-color: rgb(255 255 255 / 0.08);
  box-shadow: 0 18px 40px -30px rgb(0 0 0 / 0.5);
}
</style>
