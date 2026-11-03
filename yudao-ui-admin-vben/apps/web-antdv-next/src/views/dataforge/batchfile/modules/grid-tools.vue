<script setup lang="ts">
import { Button, Checkbox, Popover, Tooltip } from 'antdv-next';

import ReferenceIcon from './reference-icon.vue';

defineProps<{
  columns: { key: string; title: string }[];
  hideSearch?: boolean;
}>();
const emit = defineEmits<{ refresh: []; search: [] }>();
const visible = defineModel<string[]>('visible', { required: true });
function toggle(key: string, checked: boolean) {
  visible.value = checked
    ? [...visible.value, key]
    : visible.value.filter((value) => value !== key);
}
</script>
<template>
  <div class="bf-toolbar">
    <div class="bf-actions"><slot></slot></div>
    <div class="bf-actions">
      <Tooltip v-if="!hideSearch" title="显示/隐藏查询">
        <Button aria-label="显示/隐藏查询" @click="emit('search')">
          <span class="bf-search-icon" aria-hidden="true"></span>
        </Button>
      </Tooltip>
      <Tooltip title="刷新">
        <Button aria-label="刷新" @click="emit('refresh')">
<ReferenceIcon name="refresh" />
</Button>
      </Tooltip>
      <Popover placement="bottomRight" trigger="click">
        <template #content>
          <div class="bf-column-options">
            <Checkbox
              v-for="column in columns"
              :key="column.key"
              :checked="visible.includes(column.key)"
              @change="toggle(column.key, $event.target.checked)"
            >
              {{ column.title }}
            </Checkbox>
          </div>
</template><Button aria-label="列设置"><ReferenceIcon name="grid" /></Button>
      </Popover>
    </div>
  </div>
</template>
