<script lang="ts" setup>
import type { MaskedColumnInfo } from './masked-column';

import { IconifyIcon } from '@vben/icons';

import { Tooltip } from 'antdv-next';

import { maskedColumnHint } from './masked-column';

/** 表头标题：被脱敏的列加锁形标记与原因提示；未脱敏时只显示标题。 */
defineOptions({ name: 'MaskedColumnTitle' });

defineProps<{
  info?: MaskedColumnInfo;
  title: string;
}>();
</script>

<template>
  <Tooltip v-if="info" :title="maskedColumnHint(info)">
    <span
      class="inline-flex items-center gap-1"
      :data-masked-column="info.name"
    >
      <IconifyIcon
        aria-label="已脱敏"
        class="text-warning shrink-0"
        icon="carbon:locked"
      />
      <span>{{ title }}</span>
    </span>
  </Tooltip>
  <span v-else>{{ title }}</span>
</template>
