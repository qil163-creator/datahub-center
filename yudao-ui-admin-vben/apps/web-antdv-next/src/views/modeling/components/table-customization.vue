<script setup lang="ts">
import type { CustomizableColumn } from './use-table-customization';

import { computed, ref, watch } from 'vue';

import { confirm } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import { Button, Checkbox, InputNumber, Modal, Tooltip } from 'antdv-next';

const props = defineProps<{ columns: CustomizableColumn[]; open: boolean }>();
const emit = defineEmits<{
  apply: [columns: CustomizableColumn[]];
  reset: [];
  'update:open': [value: boolean];
}>();
const draft = ref<CustomizableColumn[]>([]);
const dragging = ref<number>();
const frozenCount = computed(() => draft.value.filter((column) => column.visible !== false && column.fixed).length);
function orderGroups(columns: CustomizableColumn[]) {
  return [
    ...columns.filter((column) => column.fixed === 'left'),
    ...columns.filter((column) => !column.fixed),
    ...columns.filter((column) => column.fixed === 'right'),
  ];
}
watch(() => props.open, (value) => {
  if (value) draft.value = orderGroups(props.columns.map((column) => ({ ...column })));
});
function move(index: number, delta: number) {
  const target = index + delta;
  if (target < 0 || target >= draft.value.length) return;
  if (draft.value[index]?.fixed !== draft.value[target]?.fixed) return;
  const [column] = draft.value.splice(index, 1);
  if (column) draft.value.splice(target, 0, column);
}
function drop(target: number) {
  if (dragging.value !== undefined) move(dragging.value, target - dragging.value);
  dragging.value = undefined;
}
function setVisible(column: CustomizableColumn, value: boolean) {
  if (!value && draft.value.filter((item) => item.visible !== false).length <= 1) return;
  if (value && column.fixed && frozenCount.value >= 4) column.fixed = undefined;
  column.visible = value;
  draft.value = orderGroups(draft.value);
}
function setFixed(column: CustomizableColumn, side: 'left' | 'right') {
  if (!column.fixed && frozenCount.value >= 4) return;
  column.fixed = column.fixed === side ? undefined : side;
  draft.value = orderGroups(draft.value);
}
async function reset() {
  try {
    await confirm('请确认是否恢复成默认列配置？');
    emit('reset');
  } catch {
    // 取消恢复时保留当前设置。
  }
}
</script>

<template>
  <Modal
    :open="open"
    title="个性化设置"
    :width="1000"
    @update:open="emit('update:open', $event)"
    @cancel="emit('update:open', false)"
    @ok="emit('apply', draft)"
  >
    <div class="customization-scroll">
      <table class="customization-table">
        <thead><tr><th>显示</th><th>列标题</th><th>列宽（像素）</th><th>冻结列（4）</th><th>对齐方式</th></tr></thead>
        <tbody>
          <tr v-for="(column, index) in draft" :key="column.key" draggable="true" @dragstart="dragging = index" @dragover.prevent @drop.prevent="drop(index)" @dragend="dragging = undefined">
            <td><Checkbox :checked="column.visible !== false" :disabled="column.locked" :aria-label="`显示${column.label || '选择列'}`" @update:checked="setVisible(column, $event)" /></td>
            <td class="customization-label">
              <Tooltip title="上移"><Button type="text" size="small" :disabled="index === 0 || draft[index - 1]?.fixed !== column.fixed" :aria-label="`上移${column.label || '选择列'}`" @click="move(index, -1)"><IconifyIcon icon="lucide:chevron-up" /></Button></Tooltip>
              <Tooltip title="下移"><Button type="text" size="small" :disabled="index === draft.length - 1 || draft[index + 1]?.fixed !== column.fixed" :aria-label="`下移${column.label || '选择列'}`" @click="move(index, 1)"><IconifyIcon icon="lucide:chevron-down" /></Button></Tooltip>
              {{ column.label || '选择列' }}
            </td>
            <td><InputNumber v-model:value="column.width" :min="40" :max="2000" :disabled="column.visible === false" :placeholder="String(column.minWidth || 80)" /></td>
            <td class="customization-buttons">
              <Tooltip title="冻结在左侧"><Button :type="column.fixed === 'left' ? 'primary' : 'default'" :disabled="column.visible === false || column.locked || (!column.fixed && frozenCount >= 4)" :aria-label="`左侧冻结${column.label || '选择列'}`" @click="setFixed(column, 'left')"><IconifyIcon icon="lucide:pin" /></Button></Tooltip>
              <Tooltip title="冻结在右侧"><Button :type="column.fixed === 'right' ? 'primary' : 'default'" :disabled="column.visible === false || column.locked || (!column.fixed && frozenCount >= 4)" :aria-label="`右侧冻结${column.label || '选择列'}`" @click="setFixed(column, 'right')"><IconifyIcon icon="lucide:pin" /></Button></Tooltip>
            </td>
            <td class="customization-buttons">
              <Tooltip v-for="side in (['left', 'center', 'right'] as const)" :key="side" :title="side === 'left' ? '左对齐' : side === 'center' ? '居中' : '右对齐'"><Button :type="column.align === side ? 'primary' : 'default'" :disabled="column.visible === false" :aria-label="`${column.label || '选择列'}${side === 'left' ? '左对齐' : side === 'center' ? '居中' : '右对齐'}`" @click="column.align = side"><IconifyIcon :icon="`lucide:align-${side}`" /></Button></Tooltip>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <template #footer>
      <Button @click="reset">恢复默认</Button>
      <Button @click="emit('update:open', false)">取消</Button>
      <Button type="primary" @click="emit('apply', draft)">确定</Button>
    </template>
  </Modal>
</template>

<style scoped>
.customization-scroll {
  max-height: 60vh;
  overflow: auto;
}

.customization-table {
  width: 100%;
  border-collapse: collapse;
}

.customization-table th,
.customization-table td {
  padding: 8px;
  text-align: center;
  border: 1px solid hsl(var(--border));
}

.customization-table th {
  background: hsl(var(--accent));
}

.customization-table .customization-label {
  min-width: 180px;
  text-align: left;
  white-space: nowrap;
}

.customization-buttons {
  white-space: nowrap;
}

.customization-buttons .ant-btn + .ant-btn {
  margin-left: 4px;
}
</style>
