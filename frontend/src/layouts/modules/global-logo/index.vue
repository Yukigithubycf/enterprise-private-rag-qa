<script setup lang="ts">
import { $t } from '@/locales';

defineOptions({
  name: 'GlobalLogo'
});

interface Props {
  /** Whether to show the title */
  showTitle?: boolean;
}

withDefaults(defineProps<Props>(), {
  showTitle: true
});
</script>

<template>
  <RouterLink to="/" class="logo-link w-full flex-center nowrap-hidden">
    <span class="logo-mark flex-center">
      <SystemLogo class="text-24px text-primary" />
    </span>
    <h2 v-show="showTitle" class="pl-10px text-15px font-600 transition duration-300 ease-in-out">
      {{ $t('system.title') }}
    </h2>
  </RouterLink>
</template>

<style scoped>
.logo-link {
  color: rgb(var(--base-text-color));
}

.logo-mark {
  width: 34px;
  height: 34px;
  border: 1px solid rgb(var(--primary-color) / 0.14);
  border-radius: 8px;
  background: rgb(var(--primary-color) / 0.08);
}
</style>
