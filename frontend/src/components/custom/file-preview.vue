<script setup lang="ts">
import { ref, watch } from 'vue';
import { NButton, NSpin } from 'naive-ui';
import { request } from '@/service/request';
import { getFileExt } from '@/utils/common';
import SvgIcon from '@/components/custom/svg-icon.vue';

interface Props {
  fileName: string;
  visible: boolean;
}

interface Emits {
  (e: 'close'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const loading = ref(false);
const downloading = ref(false);
const content = ref('');
const error = ref('');

function getFileIcon(fileName: string) {
  const ext = getFileExt(fileName);

  if (ext) {
    const supportedIcons = ['pdf', 'doc', 'docx', 'txt', 'md', 'jpg', 'jpeg', 'png', 'gif'];
    return supportedIcons.includes(ext.toLowerCase()) ? ext : 'dflt';
  }

  return 'dflt';
}

watch(
  () => props.fileName,
  async newFileName => {
    if (newFileName && props.visible) {
      await loadPreviewContent();
    }
  },
  { immediate: true }
);

watch(
  () => props.visible,
  async visible => {
    if (visible && props.fileName) {
      await loadPreviewContent();
    }
  }
);

async function loadPreviewContent() {
  if (!props.fileName) return;

  loading.value = true;
  error.value = '';
  content.value = '';

  try {
    const token = localStorage.getItem('token');
    const { error: requestError, data } = await request<{
      fileName: string;
      content: string;
      fileSize: number;
    }>({
      url: '/documents/preview',
      params: {
        fileName: props.fileName,
        token: token || undefined
      }
    });

    if (requestError) {
      error.value = `预览失败：${requestError.message || '未知错误'}`;
      return;
    }

    if (data) {
      content.value = data.content;
    }
  } catch (err: any) {
    error.value = `预览失败：${err.message || '网络错误'}`;
  } finally {
    loading.value = false;
  }
}

async function downloadFile() {
  if (!props.fileName) return;

  downloading.value = true;

  try {
    const token = localStorage.getItem('token');
    const { error: requestError, data } = await request<{
      fileName: string;
      downloadUrl: string;
      fileSize: number;
    }>({
      url: '/documents/download',
      params: {
        fileName: props.fileName,
        token: token || undefined
      }
    });

    if (requestError) {
      window.$message?.error(`下载失败：${requestError.message || '未知错误'}`);
      return;
    }

    if (data) {
      const link = document.createElement('a');
      link.href = data.downloadUrl;
      link.download = data.fileName;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.$message?.success('开始下载文件');
    }
  } catch (err: any) {
    window.$message?.error(`下载失败：${err.message || '网络错误'}`);
  } finally {
    downloading.value = false;
  }
}

function closePreview() {
  emit('close');
}
</script>

<template>
  <div class="file-preview-container">
    <div class="preview-header">
      <div class="flex items-center gap-2">
        <SvgIcon :local-icon="getFileIcon(fileName)" class="text-16" />
        <span class="font-medium">{{ fileName }}</span>
      </div>
      <div class="flex items-center gap-2">
        <NButton size="small" :loading="downloading" @click="downloadFile">
          <template #icon>
            <icon-mdi-download />
          </template>
          下载
        </NButton>
        <NButton size="small" @click="closePreview">
          <template #icon>
            <icon-mdi-close />
          </template>
        </NButton>
      </div>
    </div>

    <div class="preview-content">
      <template v-if="loading">
        <div class="flex h-full items-center justify-center">
          <NSpin size="large" />
        </div>
      </template>
      <template v-else-if="error">
        <div class="preview-empty flex h-full flex-col items-center justify-center">
          <icon-mdi-alert-circle class="mb-4 text-48" />
          <p>{{ error }}</p>
        </div>
      </template>
      <template v-else>
        <div class="content-wrapper">
          <pre class="preview-text">{{ content }}</pre>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped lang="scss">
.file-preview-container {
  @apply flex h-full flex-col;
  background: rgb(var(--container-bg-color));
  border-left: 1px solid rgb(var(--primary-color) / 0.08);
  color: rgb(var(--base-text-color));

  .preview-header {
    @apply flex items-center justify-between p-4;
    border-bottom: 1px solid rgb(var(--primary-color) / 0.08);
    background: linear-gradient(180deg, rgb(var(--container-bg-color)), rgb(var(--layout-bg-color)));
  }

  .preview-content {
    @apply flex-1 overflow-hidden;

    .content-wrapper {
      @apply h-full overflow-auto p-4;
      background: rgb(var(--container-bg-color));
    }

    .preview-text {
      @apply break-words whitespace-pre-wrap text-sm;
      font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
      line-height: 1.7;
      margin: 0;
    }
  }
}

.preview-empty {
  color: rgb(var(--base-text-color) / 0.6);
}

html.dark .file-preview-container {
  border-left-color: rgb(var(--warning-color) / 0.1);
}

html.dark .preview-header {
  border-bottom-color: rgb(var(--warning-color) / 0.1);
}
</style>
