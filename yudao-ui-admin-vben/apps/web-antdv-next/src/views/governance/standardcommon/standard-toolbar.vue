<script setup lang="ts">
import { Grid, IconifyIcon, RefreshCw, Search, Settings } from '@vben/icons';

import { Button, Checkbox, Popover, Tooltip } from 'antdv-next';

defineProps<{
  columns: { key: string; title: string }[];
  hideSearch?: boolean;
  settingsIcon?: string;
  title?: string;
}>();
const emit = defineEmits<{ refresh: []; search: [] }>();
const visibleColumns = defineModel<string[]>('visibleColumns', {
  required: true,
});
function toggle(key: string, checked: boolean) {
  visibleColumns.value = checked
    ? [...visibleColumns.value, key]
    : visibleColumns.value.filter((value) => value !== key);
}
</script>

<template>
  <div class="standard-toolbar">
    <slot name="title"><strong class="standard-toolbar-title">{{ title || '关联信息' }}</strong></slot>
    <div class="standard-toolbar-actions"><slot></slot></div>
    <div class="standard-toolbar-tools">
      <Tooltip v-if="!hideSearch" title="显示/隐藏查询">
        <Button aria-label="显示/隐藏查询" @click="emit('search')">
          <template #icon><Search /></template>
        </Button>
      </Tooltip>
      <Tooltip title="刷新">
        <Button aria-label="刷新" @click="emit('refresh')">
          <template #icon><RefreshCw /></template>
        </Button>
      </Tooltip>
      <Popover placement="bottomRight" trigger="click">
        <template #content>
          <div class="standard-column-options">
            <Checkbox
              v-for="column in columns"
              :key="column.key"
              :checked="visibleColumns.includes(column.key)"
              @change="toggle(column.key, $event.target.checked)"
            >
              {{ column.title }}
            </Checkbox>
          </div>
        </template>
        <Button aria-label="列设置">
          <template #icon>
            <Settings v-if="settingsIcon === 'lucide:settings'" />
            <Grid v-else-if="!settingsIcon || settingsIcon === 'lucide:grid-2x2'" />
            <IconifyIcon v-else :icon="settingsIcon" />
          </template>
        </Button>
      </Popover>
    </div>
  </div>
</template>
