<script setup lang="ts">
import { computed } from 'vue';
import type { Component } from 'vue';
import { loginModuleRecord } from '@/constants/app';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { $t } from '@/locales';
import PwdLogin from './modules/pwd-login.vue';
import CodeLogin from './modules/code-login.vue';
import Register from './modules/register.vue';
import ResetPwd from './modules/reset-pwd.vue';
import BindWechat from './modules/bind-wechat.vue';

interface Props {
  /** The login module */
  module?: UnionKey.LoginModule;
}

const props = defineProps<Props>();

const appStore = useAppStore();
const themeStore = useThemeStore();

interface LoginModule {
  label: string;
  component: Component;
}

const moduleMap: Record<UnionKey.LoginModule, LoginModule> = {
  'pwd-login': { label: loginModuleRecord['pwd-login'], component: PwdLogin },
  'code-login': { label: loginModuleRecord['code-login'], component: CodeLogin },
  register: { label: loginModuleRecord.register, component: Register },
  'reset-pwd': { label: loginModuleRecord['reset-pwd'], component: ResetPwd },
  'bind-wechat': { label: loginModuleRecord['bind-wechat'], component: BindWechat }
};

const activeModule = computed(() => moduleMap[props.module || 'pwd-login']);
</script>

<template>
  <div class="login-page size-full flex items-stretch">
    <section class="login-brand-panel flex-1 flex-col justify-between p-10 lt-md:hidden">
      <div>
        <div class="flex items-center gap-3">
          <span class="brand-mark flex-center">
            <SystemLogo class="text-28px" />
          </span>
          <span class="text-20px font-700">{{ $t('system.title') }}</span>
        </div>
        <h1 class="mb-0 mt-14 max-w-620px text-38px font-700 leading-tight">
          企业级私有知识库 RAG 问答系统
        </h1>
        <p class="mt-5 max-w-560px text-16px leading-7 color-white/72">
          汇聚企业制度、流程和项目资料，在权限边界内完成检索、问答与来源追溯。
        </p>
      </div>
      <div class="brand-capabilities grid grid-cols-3 gap-4">
        <div>
          <strong>Private</strong>
          <span>私有化知识资产</span>
        </div>
        <div>
          <strong>RAG</strong>
          <span>检索增强生成</span>
        </div>
        <div>
          <strong>Trace</strong>
          <span>回答来源可追溯</span>
        </div>
      </div>
    </section>

    <section class="login-form-panel flex-center p-6">
      <NCard :bordered="false" class="login-card relative z-4 w-auto card-wrapper">
        <div class="w-400px lt-sm:w-300px">
          <header class="flex-y-center justify-between">
            <div class="flex-y-center gap-3">
              <span class="login-card-logo flex-center">
                <SystemLogo class="text-24px text-primary" />
              </span>
              <div>
                <h3 class="m-0 text-22px font-700 lt-sm:text-20px">{{ $t('system.title') }}</h3>
                <p class="m-0 mt-1 text-13px color-[rgb(var(--base-text-color)/0.56)]">安全登录工作台</p>
              </div>
            </div>
          <div class="i-flex-col">
            <ThemeSchemaSwitch
              :theme-schema="themeStore.themeScheme"
              :show-tooltip="false"
              class="text-20px lt-sm:text-18px"
              @switch="themeStore.toggleThemeScheme"
            />
            <LangSwitch
              v-if="themeStore.header.multilingual.visible"
              :lang="appStore.locale"
              :lang-options="appStore.localeOptions"
              :show-tooltip="false"
              @change-lang="appStore.changeLocale"
            />
          </div>
          </header>
          <main class="pt-28px">
            <h3 class="text-18px text-primary font-medium">{{ $t(activeModule.label) }}</h3>
            <div class="pt-24px">
              <Transition :name="themeStore.page.animateMode" mode="out-in" appear>
                <component :is="activeModule.component" />
              </Transition>
            </div>
          </main>
        </div>
      </NCard>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  background:
    linear-gradient(90deg, rgb(12 31 54) 0%, rgb(15 45 78) 46%, rgb(var(--layout-bg-color)) 46%),
    rgb(var(--layout-bg-color));
}

.login-brand-panel {
  color: white;
  background:
    linear-gradient(135deg, rgb(12 31 54), rgb(18 67 113)),
    rgb(12 31 54);
}

.brand-mark,
.login-card-logo {
  width: 40px;
  height: 40px;
  border-radius: 8px;
}

.brand-mark {
  color: white;
  border: 1px solid rgb(255 255 255 / 0.16);
  background: rgb(255 255 255 / 0.1);
}

.brand-capabilities > div {
  min-height: 92px;
  border: 1px solid rgb(255 255 255 / 0.14);
  border-radius: 8px;
  background: rgb(255 255 255 / 0.08);
  padding: 16px;
}

.brand-capabilities strong {
  display: block;
  font-size: 18px;
  line-height: 1.2;
}

.brand-capabilities span {
  display: block;
  margin-top: 10px;
  color: rgb(255 255 255 / 0.72);
}

.login-form-panel {
  width: min(560px, 100%);
  background: rgb(var(--layout-bg-color));
}

.login-card {
  padding: 6px;
}

.login-card-logo {
  background: rgb(var(--primary-color) / 0.08);
  border: 1px solid rgb(var(--primary-color) / 0.14);
}

@media (width < 960px) {
  .login-page {
    background: rgb(var(--layout-bg-color));
  }

  .login-form-panel {
    width: 100%;
  }
}
</style>
