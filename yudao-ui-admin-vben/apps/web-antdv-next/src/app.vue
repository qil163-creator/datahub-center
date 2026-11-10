<script lang="ts" setup>
import { computed } from 'vue';

import { useAntdDesignTokens } from '@vben/hooks';
import { preferences, usePreferences } from '@vben/preferences';

import { App, ConfigProvider, StyleProvider, theme } from 'antdv-next';

import { antdLocale } from '#/locales';

defineOptions({ name: 'App' });

const { isDark } = usePreferences();
const { tokens } = useAntdDesignTokens();

const tokenTheme = computed(() => {
  const algorithm = isDark.value
    ? [theme.darkAlgorithm]
    : [theme.defaultAlgorithm];

  // antd 紧凑模式算法
  if (preferences.app.compact) {
    algorithm.push(theme.compactAlgorithm);
  }

  return {
    algorithm,
    token: tokens,
  };
});
</script>

<template>
  <!-- layer: antd 组件样式注入 @layer antd，让 Tailwind 工具类可以覆盖组件样式 -->
  <StyleProvider layer>
    <ConfigProvider :locale="antdLocale" :theme="tokenTheme">
      <App>
        <RouterView />
      </App>
    </ConfigProvider>
  </StyleProvider>
</template>

<style>
.subtle-tree-scrollbar {
  scrollbar-color: transparent transparent;
  scrollbar-width: thin;
}

.subtle-tree-scrollbar:hover,
.subtle-tree-scrollbar:focus-within {
  scrollbar-color: rgb(128 128 128 / 28%) transparent;
}

.subtle-grid-scrollbar .vxe-table--scroll-x-handle::-webkit-scrollbar {
  height: 6px;
}

.subtle-grid-scrollbar .vxe-table--scroll-x-handle::-webkit-scrollbar-track {
  background: transparent;
}

.subtle-grid-scrollbar .vxe-table--scroll-x-handle::-webkit-scrollbar-thumb {
  background: hsl(var(--foreground) / 20%);
  border-radius: 999px;
}

.subtle-grid-scrollbar .vxe-table--scroll-x-handle::-webkit-scrollbar-thumb:hover {
  background: hsl(var(--foreground) / 30%);
}

@supports not selector(::-webkit-scrollbar) {
  .subtle-grid-scrollbar .vxe-table--scroll-x-handle {
    scrollbar-color: hsl(var(--foreground) / 20%) transparent;
    scrollbar-width: thin;
  }
}

/* 让加载图标的旋转中心始终与开关滑块中心重合。 */
.ant-switch .ant-switch-handle > .ant-switch-loading-icon.anticon {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  transform-origin: center;
}
</style>
