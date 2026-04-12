<script setup lang="ts">
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';
</script>

<template>
  <div class="paper-page h-full flex flex-col gap-4">
    <div class="paper-stage min-h-0 flex flex-col flex-1 card-wrapper">
      <ChatList />
    </div>
    <InputBox />
  </div>
</template>

<style scoped></style>
