<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import { useUserStore } from '@vben/stores';

import { Alert, Button } from 'antdv-next';

import { parseResourceId } from '#/utils/resource-id';

defineOptions({ inheritAttrs: false });

const props = defineProps<{ guideId: string }>();
const userStore = useUserStore();
const hidden = ref(false);
const preferenceKey = computed(() => {
  const userId = parseResourceId(userStore.userInfo?.userId);
  const namespace = import.meta.env.VITE_APP_NAMESPACE || 'datahub';
  return userId
    ? `${namespace}:page-guide:${JSON.stringify([userId, props.guideId])}`
    : undefined;
});

watch(
  preferenceKey,
  (key) => {
    hidden.value = false;
    if (!key) return;
    try {
      hidden.value = window.localStorage.getItem(key) === 'hidden';
    } catch {
      // 浏览器禁用本地存储时，提示仍可正常使用。
    }
  },
  { immediate: true },
);

function close() {
  hidden.value = true;
}

function dismiss() {
  const key = preferenceKey.value;
  if (key) {
    try {
      window.localStorage.setItem(key, 'hidden');
    } catch {
      // 偏好无法保存时，退回到仅关闭当前提示。
    }
  }
  close();
}
</script>

<template>
  <Alert
    v-if="!hidden"
    v-bind="$attrs"
    :closable="{ 'aria-label': '关闭提示' }"
    @close="close"
  >
    <template v-if="$slots.title" #title><slot name="title"></slot></template>
    <template v-if="$slots.message" #message>
      <slot name="message"></slot>
    </template>
    <template v-if="$slots.description" #description>
      <slot name="description"></slot>
    </template>
    <template v-if="$slots.icon" #icon><slot name="icon"></slot></template>
    <template v-if="preferenceKey || $slots.action" #action>
      <div class="flex flex-wrap items-center justify-end gap-1">
        <slot name="action"></slot>
        <Button v-if="preferenceKey" size="small" type="link" @click="dismiss">
          不再提示
        </Button>
      </div>
    </template>
  </Alert>
</template>
