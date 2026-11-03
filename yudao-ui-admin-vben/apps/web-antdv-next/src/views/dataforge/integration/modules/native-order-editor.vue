<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 仅编辑父设计器隔离草稿。 */
import { computed, ref, watch } from 'vue';

import { Alert, AutoComplete, Button, Select } from 'antdv-next';

import './reference-node-editor.css';
import './reference-transform-layout.css';

const props = defineProps<{
  config: Record<string, any>;
  disabled?: boolean;
  inputFields?: { name?: string }[];
  mode?: string;
}>();
const errors = ref<string[]>([]);
const rows = computed<Record<string, any>[]>(() =>
  Array.isArray(props.config.orderBy) ? props.config.orderBy : [],
);
const fieldOptions = computed(() =>
  (props.inputFields ?? [])
    .filter((field) => field.name)
    .map((field) => ({ label: field.name!, value: field.name! })),
);
const directions = [
  { label: '升序', value: 'ASC' },
  { label: '降序', value: 'DESC' },
];
function add() {
  if (!props.disabled)
    props.config.orderBy = [...rows.value, { field: '', order: 'ASC' }];
}
function update(row: Record<string, any>, key: string, value: unknown) {
  if (!props.disabled) row[key] = value;
}
function uniqueFields(value: unknown) {
  if (!props.disabled && Array.isArray(value))
    props.config.uniqueFields = [...value];
}
function remove(index: number) {
  if (!props.disabled)
    props.config.orderBy = rows.value.filter(
      (_, position) => position !== index,
    );
}
function move(index: number, offset: number) {
  const target = index + offset;
  if (props.disabled || target < 0 || target >= rows.value.length) return;
  const reordered = [...rows.value];
  [reordered[index], reordered[target]] = [
    reordered[target]!,
    reordered[index]!,
  ];
  props.config.orderBy = reordered;
}
watch(
  () => props.config,
  () => {
    errors.value = [];
  },
);
function validate() {
  const issues: string[] = [];
  const fields = rows.value.map((row) => row.field);
  if (
    rows.value.length === 0 ||
    rows.value.some(
      (row) =>
        typeof row.field !== 'string' ||
        !row.field.trim() ||
        !['ASC', 'DESC'].includes(row.order),
    )
  )
    issues.push('请填写排序字段和方向');
  if (new Set(fields).size !== fields.length) issues.push('排序字段不能重复');
  const unique = props.config.uniqueFields;
  if (
    !Array.isArray(unique) ||
    unique.length === 0 ||
    unique.some((field) => typeof field !== 'string' || !field.trim()) ||
    new Set(unique).size !== unique.length
  )
    issues.push('请指定不重复的唯一性字段');
  else if (unique.some((field) => !fields.includes(field)))
    issues.push('排序字段必须包含全部唯一性字段');
  errors.value = issues;
  return issues.length === 0;
}
defineExpose({ validate });
</script>

<template>
  <details
    class="qi-transform-advanced qi-transform-reference"
    :open="errors.length > 0"
  >
    <summary>{{ mode === 'REALTIME' ? '窗内顺序与唯一性配置' : '确定顺序与唯一性配置' }}</summary>
    <div class="qi-reference-editor">
      <p class="qi-reference-notice">
        {{ mode === 'REALTIME' ? '在每个有限窗口组内按此顺序选择首条或末条完整数据；排序字段须包含唯一性字段。同一窗口组中唯一键相同而原始内容不同会明确失败。此配置不执行无界全局排序。' : '请明确数据顺序：生成序列时按此顺序编号，行转列时使用同组最后一条数据，去重时保留同组第一条的原始字段值。排序字段须包含唯一性字段，执行时会检查重复值。' }}
      </p>
      <Alert v-if="errors.length" type="error" :message="errors.join('；')" />
      <div
        v-for="(row, index) in rows"
        :key="index"
        class="qi-reference-file-actions"
      >
        <AutoComplete
          :value="row.field"
          :options="fieldOptions"
          :disabled="disabled"
          :aria-label="`第${index + 1}个排序字段`"
          placeholder="排序字段"
          @update:value="(value) => update(row, 'field', value)"
        />
        <Select
          :value="row.order"
          :options="directions"
          :disabled="disabled"
          :aria-label="`第${index + 1}个排序方向`"
          @update:value="(value) => update(row, 'order', value)"
        />
        <Button :disabled="disabled || index === 0" @click="move(index, -1)">
上移
</Button><Button
          :disabled="disabled || index === rows.length - 1"
          @click="move(index, 1)"
          >
下移
</Button><Button :disabled="disabled" type="link" danger @click="remove(index)">
删除排序
</Button>
      </div>
      <Button class="qi-reference-button" :disabled="disabled" @click="add">
增加排序字段
</Button>
      <div class="qi-reference-form-row">
        <label>唯一性字段</label><Select
          :value="config.uniqueFields ?? []"
          :options="fieldOptions"
          mode="tags"
          :disabled="disabled"
          aria-label="唯一性字段"
          placeholder="选择或输入能共同唯一标识一行的字段"
          @update:value="uniqueFields"
        />
      </div>
    </div>
  </details>
</template>
