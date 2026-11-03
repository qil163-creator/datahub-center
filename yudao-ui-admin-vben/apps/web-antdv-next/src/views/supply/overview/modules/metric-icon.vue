<script lang="ts" setup>
import { IconifyIcon } from '@vben/icons';

defineOptions({ name: 'SupplyMetricIcon' });

withDefaults(
  defineProps<{
    color?: string;
    icon: string;
  }>(),
  {
    color: '#2f7df6',
  },
);
</script>

<template>
  <span
    class="inline-flex items-center justify-center rounded-xl"
    :style="{ backgroundColor: `${color}1a`, color }"
  >
    <IconifyIcon :icon="icon" class="size-[1.375rem]" />
  </span>
</template>
