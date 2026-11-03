<script lang="ts" setup>
import type { SafetyRuleApi } from '#/api/governance/rule';

import { IconifyIcon } from '@vben/icons';

import { Button, Input } from 'antdv-next';

/** 内容替换的匹配-替换码表：多行匹配值与替换值，命中即停 */
defineOptions({ name: 'SafetyRuleReplaceItemTable' });

const props = withDefaults(
  defineProps<{
    disabled?: boolean;
    modelValue?: SafetyRuleApi.ReplaceItem[];
  }>(),
  {
    disabled: false,
    modelValue: () => [],
  },
);

const emit = defineEmits<{
  'update:modelValue': [value: SafetyRuleApi.ReplaceItem[]];
}>();

function updateRows(rows: SafetyRuleApi.ReplaceItem[]) {
  emit('update:modelValue', rows);
}

function handleAdd() {
  updateRows([
    ...(props.modelValue ?? []),
    { matchValue: '', replacementValue: '' },
  ]);
}

function handleRemove(index: number) {
  const rows = [...(props.modelValue ?? [])];
  rows.splice(index, 1);
  updateRows(rows);
}

function handleCellChange(
  index: number,
  key: 'matchValue' | 'replacementValue',
  value: string,
) {
  const rows = [...(props.modelValue ?? [])];
  rows[index] = { ...rows[index], [key]: value } as SafetyRuleApi.ReplaceItem;
  updateRows(rows);
}
</script>

<template>
  <div class="w-full overflow-x-auto">
    <div class="mb-2 flex justify-end">
      <Button
        :disabled="disabled"
        size="small"
        type="primary"
        @click="handleAdd"
      >
        <template #icon><IconifyIcon icon="lucide:plus" /></template>
        添加
      </Button>
    </div>
    <table class="replace-item-table">
      <thead>
        <tr>
          <th>匹配值</th>
          <th>替换值</th>
          <th class="replace-item-table__op">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="!modelValue || modelValue.length === 0">
          <td class="replace-item-table__empty" colspan="3">
            暂无数据，请点击右上角"添加"
          </td>
        </tr>
        <tr v-for="(row, index) in modelValue" :key="index">
          <td>
            <Input
              :disabled="disabled"
              placeholder="请输入匹配值"
              :value="row.matchValue"
              @update:value="
                (val: string) => handleCellChange(index, 'matchValue', val)
              "
            />
          </td>
          <td>
            <Input
              :disabled="disabled"
              placeholder="请输入替换值"
              :value="row.replacementValue"
              @update:value="
                (val: string) =>
                  handleCellChange(index, 'replacementValue', val)
              "
            />
          </td>
          <td class="replace-item-table__op">
            <Button
              danger
              :disabled="disabled"
              size="small"
              type="primary"
              @click="handleRemove(index)"
            >
              <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
              删除
            </Button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.replace-item-table {
  inline-size: 100%;
  min-inline-size: 460px;
  border-collapse: collapse;
}

.replace-item-table th,
.replace-item-table td {
  padding: 6px 8px;
  font-size: 12px;
  text-align: start;
  border: 1px solid var(--ant-color-border-secondary, #f0f0f0);
}

.replace-item-table th {
  font-weight: 500;
  background-color: var(--ant-color-fill-quaternary, #fafafa);
}

.replace-item-table__op {
  position: sticky;
  right: 0;
  z-index: 1;
  inline-size: 88px;
  min-inline-size: 88px;
  text-align: center;
  white-space: nowrap;
  background: var(--ant-color-bg-container, #fff);
}

.replace-item-table__empty {
  color: var(--ant-color-text-quaternary, #bfbfbf);
  text-align: center;
}

th.replace-item-table__op {
  background: var(--ant-color-fill-quaternary, #fafafa);
}
</style>
