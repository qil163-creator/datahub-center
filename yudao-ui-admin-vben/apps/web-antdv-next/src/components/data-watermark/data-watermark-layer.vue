<script lang="ts" setup>
import { computed, onBeforeUnmount, ref } from 'vue';

import { useUserStore } from '@vben/stores';

import { watermarkBackground, watermarkText } from './watermark';

/**
 * 水印层：铺满最近的定位父元素（父元素需 position: relative）。不拦截点击、不参与读屏，每分钟刷新时间。
 */
defineOptions({ name: 'DataWatermarkLayer' });

const userStore = useUserStore();
const now = ref(new Date());
const timer = setInterval(() => {
  now.value = new Date();
}, 60_000);
onBeforeUnmount(() => clearInterval(timer));

const background = computed(() =>
  watermarkBackground(watermarkText(userStore.userInfo, now.value)),
);
</script>

<template>
  <div
    aria-hidden="true"
    class="pointer-events-none absolute inset-0 z-[5] select-none"
    data-testid="data-watermark"
    :style="{ backgroundImage: background, backgroundRepeat: 'repeat' }"
  ></div>
</template>
