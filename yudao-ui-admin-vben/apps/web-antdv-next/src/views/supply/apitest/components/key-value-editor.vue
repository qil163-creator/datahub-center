<script setup lang="ts">
import type { KeyValueItem } from '../request';

import { computed } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Button, Checkbox, Input } from 'antdv-next';

import { createRow } from '../request';

withDefaults(
  defineProps<{
    namePlaceholder?: string;
    showType?: boolean;
    valuePlaceholder?: string;
  }>(),
  { namePlaceholder: '请输入参数名', valuePlaceholder: '请输入参数值' },
);
const items = defineModel<KeyValueItem[]>('items', { required: true });
const allChecked = computed(
  () => items.value.length > 0 && items.value.every((item) => item.enabled),
);
function setAll(checked: boolean) {
  items.value = items.value.map((item) => ({ ...item, enabled: checked }));
}
function remove(id: string) {
  items.value = items.value.filter((item) => item.id !== id);
}
</script>

<template>
  <div class="kv-editor">
    <Button type="link" class="kv-editor__add" @click="items.push(createRow())">
      <template #icon><IconifyIcon icon="lucide:plus" /></template>
      新增参数
    </Button>
    <div
      class="kv-editor__table"
      :class="{ 'kv-editor__table--typed': showType }"
    >
      <div class="kv-editor__row kv-editor__head">
        <Checkbox
          aria-label="全选参数"
          :checked="allChecked"
          :indeterminate="!allChecked && items.some((item) => item.enabled)"
          @change="setAll($event.target.checked)"
        />
        <span>参数名</span><span>参数值</span><span v-if="showType">类型</span><span>操作</span>
      </div>
      <div v-for="item in items" :key="item.id" class="kv-editor__row">
        <Checkbox
          v-model:checked="item.enabled"
          :aria-label="`启用 ${item.name || '参数'}`"
        />
        <Input
          v-model:value="item.name"
          :placeholder="namePlaceholder"
          aria-label="参数名"
        />
        <Input
          v-model:value="item.value"
          :placeholder="valuePlaceholder"
          aria-label="参数值"
        />
        <span v-if="showType" class="kv-editor__type">{{
          item.paramType || 'String'
        }}</span>
        <Button danger type="link" size="small" @click="remove(item.id)">
          <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
          删除
        </Button>
      </div>
      <div v-if="!items.length" class="kv-editor__empty">
        暂无参数，点击“新增参数”添加
      </div>
    </div>
  </div>
</template>

<style scoped>
.kv-editor__add {
  padding-left: 0;
  margin-bottom: 10px;
}

.kv-editor__table {
  overflow-x: auto;
  border: 1px solid hsl(var(--border));
  border-radius: calc(var(--radius) - 2px);
}

.kv-editor__row {
  display: grid;
  grid-template-columns: 36px minmax(140px, 2fr) minmax(180px, 3fr) 88px;
  gap: 12px;
  align-items: center;
  min-width: 554px;
  padding: 8px 12px;
  border-bottom: 1px solid hsl(var(--border));
}

.kv-editor__table--typed .kv-editor__row {
  grid-template-columns: 36px minmax(140px, 2fr) minmax(180px, 3fr) 80px 88px;
  min-width: 634px;
}

.kv-editor__head {
  font-weight: 500;
  background: hsl(var(--muted) / 65%);
}

.kv-editor__row:last-child {
  border-bottom: 0;
}

.kv-editor__type {
  color: hsl(var(--primary));
}

.kv-editor__empty {
  padding: 20px;
  color: hsl(var(--muted-foreground));
  text-align: center;
}
</style>
