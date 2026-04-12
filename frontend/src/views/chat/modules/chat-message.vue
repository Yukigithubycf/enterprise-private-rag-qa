<script setup lang="ts">
// eslint-disable-next-line @typescript-eslint/no-unused-vars
import { nextTick } from 'vue';
import { VueMarkdownIt } from 'vue-markdown-shiki';
import { formatDate } from '@/utils/common';
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{ msg: Api.Chat.Message }>();

const authStore = useAuthStore();

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('已复制');
}

const chatStore = useChatStore();

// 存储文件名和对应的事件处理
const sourceFiles = ref<Array<{fileName: string, id: string}>>([]);

// 处理来源文件链接的函数
function processSourceLinks(text: string): string {
  // 匹配 (来源#数字: 文件名) 的正则表达式
  const sourcePattern = /\(来源#(\d+):\s*([^)]+)\)/g;

  return text.replace(sourcePattern, (_match, sourceNum, fileName) => {
    // 为文件名创建可点击的链接
    const linkClass = 'source-file-link';
    const encodedFileName = encodeURIComponent(fileName.trim());
    const fileId = `source-file-${sourceFiles.value.length}`;

    // 存储文件信息
    sourceFiles.value.push({
      fileName: encodedFileName,
      id: fileId
    });

    return `(来源#${sourceNum}: <span class="${linkClass}" data-file-id="${fileId}">${fileName}</span>)`;
  });
}

const content = computed(() => {
  chatStore.scrollToBottom?.();
  const rawContent = props.msg.content ?? '';

  // 只对助手消息处理来源链接
  if (props.msg.role === 'assistant') {
    return processSourceLinks(rawContent);
  }

  return rawContent;
});

// 处理内容点击事件（事件委托）
function handleContentClick(event: MouseEvent) {
  const target = event.target as HTMLElement;

  // 检查点击的是否是文件链接
  if (target.classList.contains('source-file-link')) {
    const fileId = target.getAttribute('data-file-id');
    if (fileId) {
      const file = sourceFiles.value.find(f => f.id === fileId);
      if (file) {
        handleSourceFileClick(file.fileName);
      }
    }
  }
}

// 处理来源文件点击事件
async function handleSourceFileClick(fileName: string) {
  const decodedFileName = decodeURIComponent(fileName);
  console.log('点击了来源文件:', decodedFileName);

  try {
    window.$message?.loading(`正在获取文件下载链接: ${decodedFileName}`, {
      duration: 0,
      closable: false
    });

    // 调用文件下载接口
    const { error, data } = await request<Api.Document.DownloadResponse>({
      url: 'documents/download',
      params: {
        fileName: decodedFileName,
        token: authStore.token
      },
      baseURL: '/proxy-api'
    });

    window.$message?.destroyAll();

    if (error) {
      window.$message?.error(`文件下载失败: ${error.response?.data?.message || '未知错误'}`);
      return;
    }

    if (data?.downloadUrl) {
      // 在新窗口打开下载链接
      window.open(data.downloadUrl, '_blank');
      window.$message?.success(`文件下载链接已打开: ${decodedFileName}`);
    } else {
      window.$message?.error('未能获取到下载链接');
    }
  } catch (err) {
    window.$message?.destroyAll();
    console.error('文件下载失败:', err);
    window.$message?.error(`文件下载失败: ${decodedFileName}`);
  }
}
</script>

<template>
  <div class="mb-8 flex-col gap-2">
    <div v-if="msg.role === 'user'" class="flex items-center gap-4">
      <NAvatar class="bg-success">
        <SvgIcon icon="ph:user-circle" class="text-icon-large color-white" />
      </NAvatar>
      <div class="flex-col gap-1">
        <NText class="text-4 font-bold">{{ authStore.userInfo.username }}</NText>
        <NText class="text-3 color-gray-500">{{ formatDate(msg.timestamp) }}</NText>
      </div>
    </div>
    <div v-else class="flex items-center gap-4">
      <NAvatar class="assistant-avatar">
        <SystemLogo class="text-6" />
      </NAvatar>
      <div class="flex-col gap-1">
        <NText class="text-4 font-bold">文渊助手</NText>
        <NText class="text-3 color-gray-500">{{ formatDate(msg.timestamp) }}</NText>
      </div>
    </div>
    <NText v-if="msg.status === 'pending'">
      <icon-eos-icons:three-dots-loading class="ml-12 mt-2 text-8" />
    </NText>
    <NText v-else-if="msg.status === 'error'" class="ml-12 mt-2 italic">服务器繁忙，请稍后再试</NText>
    <div v-else-if="msg.role === 'assistant'" class="assistant-message-shell mt-2 pl-12" @click="handleContentClick">
      <div class="assistant-message-card">
        <VueMarkdownIt :content="content" />
      </div>
    </div>
    <NText v-else-if="msg.role === 'user'" class="ml-12 mt-2 text-4">{{ content }}</NText>
    <NDivider class="ml-12 w-[calc(100%-3rem)] mb-0! mt-2!" />
    <div class="ml-12 flex gap-4">
      <NButton quaternary @click="handleCopy(msg.content)">
        <template #icon>
          <icon-mynaui:copy />
        </template>
      </NButton>
    </div>
  </div>
</template>

<style scoped lang="scss">
:deep(.assistant-avatar) {
  background: rgb(var(--container-bg-color));
  border: 1px solid rgb(var(--primary-color) / 0.12);
  box-shadow: 0 8px 20px -16px rgb(var(--primary-color) / 0.28);
}

.assistant-message-shell {
  padding-right: 12px;
}

:deep(.assistant-message-card) {
  max-width: min(100%, 60rem);
  border: 1px solid rgb(var(--primary-color) / 0.08);
  border-radius: 14px;
  background:
    linear-gradient(180deg, rgb(var(--container-bg-color)), rgb(var(--layout-bg-color) / 0.72));
  box-shadow: 0 16px 36px -30px rgb(var(--primary-color) / 0.24);
  padding: 16px 18px;
}

:deep(.assistant-message-card > :first-child) {
  margin-top: 0;
}

:deep(.assistant-message-card > :last-child) {
  margin-bottom: 0;
}

:deep(.assistant-message-card p) {
  margin: 0 0 1em;
  line-height: 1.9;
}

:deep(.assistant-message-card ul),
:deep(.assistant-message-card ol) {
  padding-left: 1.25rem;
  line-height: 1.85;
}

:deep(.assistant-message-card li + li) {
  margin-top: 0.35rem;
}

:deep(.assistant-message-card a) {
  color: rgb(var(--primary-color));
}

:deep(.assistant-message-card code) {
  border-radius: 6px;
  background: rgb(var(--primary-color) / 0.06);
  padding: 0.1em 0.38em;
}

:deep(.assistant-message-card pre) {
  overflow-x: auto;
  border: 1px solid rgb(var(--primary-color) / 0.08);
  border-radius: 12px;
  background: rgb(var(--layout-bg-color)) !important;
  margin: 1em 0;
}

:deep(.assistant-message-card pre code) {
  background: transparent;
  padding: 0;
}

:deep(.assistant-message-card blockquote) {
  border-left: 3px solid rgb(var(--warning-color) / 0.58);
  border-radius: 0 10px 10px 0;
  background: rgb(var(--warning-color) / 0.06);
  margin: 1em 0;
  padding: 10px 14px;
}

:deep(.assistant-message-card hr) {
  border: 0;
  border-top: 1px solid rgb(var(--primary-color) / 0.08);
  margin: 1.2em 0;
}

:deep(.source-file-link) {
  color: rgb(var(--warning-color));
  cursor: pointer;
  text-decoration: underline;
  transition: color 0.2s;

  &:hover {
    color: rgb(var(--primary-color));
    text-decoration: none;
  }

  &:active {
    color: rgb(var(--warning-700-color));
  }
}

html.dark :deep(.assistant-avatar) {
  border-color: rgb(var(--warning-color) / 0.14);
  box-shadow: 0 10px 22px -18px rgb(0 0 0 / 0.42);
}

html.dark :deep(.assistant-message-card) {
  border-color: rgb(var(--warning-color) / 0.1);
  background:
    linear-gradient(180deg, rgb(var(--container-bg-color)), rgb(var(--layout-bg-color) / 0.8));
  box-shadow: 0 18px 40px -32px rgb(0 0 0 / 0.42);
}

html.dark :deep(.assistant-message-card code) {
  background: rgb(var(--warning-color) / 0.08);
}

html.dark :deep(.assistant-message-card pre) {
  border-color: rgb(var(--warning-color) / 0.1);
}

html.dark :deep(.assistant-message-card blockquote) {
  border-left-color: rgb(var(--warning-color) / 0.5);
  background: rgb(var(--warning-color) / 0.08);
}

html.dark :deep(.assistant-message-card hr) {
  border-top-color: rgb(var(--warning-color) / 0.1);
}
</style>
