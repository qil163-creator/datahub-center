<script setup lang="ts">
import { IconifyIcon } from '@vben/icons';

import { Tooltip } from 'antdv-next';

defineProps<{
  label: string;
  statusHint?: string;
}>();
</script>

<template>
  <span>{{ label }}</span>
  <Tooltip>
    <template #title>
      <template v-if="statusHint">{{ statusHint }}</template>
      <template v-else>
        <div>数据元：表示一个具体的字段，如“水位”、“年龄”</div>
        <div>
          代码表：表示一组枚举值，如“性别”（男/女）、“预警等级”（初级/中级/高级）
        </div>
      </template>
    </template>
    <button type="button" class="standard-header-help" :aria-label="`${label}说明`">
      <IconifyIcon icon="ant-design:info-circle-filled" />
    </button>
  </Tooltip>
</template>

<style scoped>
.standard-header-help {
  display: inline-flex;
  align-items: center;
  padding: 0;
  margin-left: 4px;
  color: hsl(var(--muted-foreground));
  cursor: help;
  background: none;
  border: 0;
}
</style>
