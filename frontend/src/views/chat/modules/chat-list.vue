<script setup lang="ts">
import { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from 'vue-markdown-shiki';
import ChatMessage from './chat-message.vue';

defineOptions({
  name: 'ChatList'
});

const chatStore = useChatStore();
const { list } = storeToRefs(chatStore);

const suggestedQuestions = [
  '查询最新报销制度中的审批流程',
  '总结项目交付文档中的风险项',
  '检索某个客户方案的关键结论'
];

const loading = ref(false);
const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

watch(() => [...list.value], scrollToBottom);

function scrollToBottom() {
  setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999999,
      behavior: 'auto'
    });
  }, 100);
}

const range = ref<[number, number]>([dayjs().subtract(7, 'day').valueOf(), dayjs().add(1, 'day').valueOf()]);

const params = computed(() => {
  return {
    start_date: dayjs(range.value[0]).format('YYYY-MM-DD'),
    end_date: dayjs(range.value[1]).format('YYYY-MM-DD')
  };
});

watchEffect(() => {
  getList();
});

async function getList() {
  loading.value = true;
  const { error, data } = await request<Api.Chat.Message[]>({
    url: 'users/conversation',
    params: params.value
  });
  if (!error) {
    list.value = data;
  }
  loading.value = false;
}

onMounted(() => {
  chatStore.scrollToBottom = scrollToBottom;
});

function fillQuestion(question: string) {
  chatStore.input.message = question;
}
</script>

<template>
  <Suspense>
    <NScrollbar ref="scrollbarRef" class="h-0 flex-auto">
      <Teleport defer to="#header-extra">
        <div class="h-full flex items-center px-6">
          <NForm :model="params" label-placement="left" :show-feedback="false" inline>
            <NFormItem label="时间">
              <NDatePicker v-model:value="range" type="daterange" />
            </NFormItem>
          </NForm>
        </div>
      </Teleport>
      <NSpin :show="loading">
        <VueMarkdownItProvider>
          <ChatMessage v-for="(item, index) in list" :key="index" :msg="item" />
        </VueMarkdownItProvider>
        <div v-if="!loading && !list.length" class="empty-workbench flex-col items-center justify-center py-22">
          <div class="empty-mark flex-center">
            <icon-solar:chat-square-like-bold-duotone class="text-30px text-primary" />
          </div>
          <h2 class="mb-2 mt-5 text-20px font-600">开始一次知识库问答</h2>
          <p class="m-0 max-w-560px text-center text-14px color-[rgb(var(--base-text-color)/0.58)]">
            系统会在授权范围内检索企业私有文档，并在回答中保留可追溯来源。
          </p>
          <div class="mt-6 flex flex-wrap justify-center gap-3">
            <NButton v-for="question in suggestedQuestions" :key="question" secondary @click="fillQuestion(question)">
              {{ question }}
            </NButton>
          </div>
        </div>
      </NSpin>
    </NScrollbar>
  </Suspense>
</template>

<style scoped lang="scss">
.empty-workbench {
  min-height: 420px;
}

.empty-mark {
  width: 64px;
  height: 64px;
  border: 1px solid rgb(var(--primary-color) / 0.12);
  border-radius: 8px;
  background: rgb(var(--primary-color) / 0.08);
}

:deep(.n-button) {
  border-radius: 6px;
}
</style>
