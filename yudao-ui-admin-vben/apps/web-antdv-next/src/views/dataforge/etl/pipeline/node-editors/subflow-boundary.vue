<script setup lang="ts">
import type { SubflowField } from '#/api/dataforge/etl/subflow';

/* eslint-disable vue/no-mutating-props -- 编辑器只操作设计器提供的配置副本。 */
import { onMounted } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Alert, Button, Input, InputNumber, Select } from 'antdv-next';

import FieldContractSummary from '../field-contract-summary.vue';

import './editor-style.css';
const props = defineProps<{ config: Record<string, any> }>();
const types = [
  'String',
  'Integer',
  'Long',
  'Double',
  'Decimal',
  'Boolean',
  'Date',
  'Object',
].map((value) => ({ value, label: value }));
const nullableOptions = [
  { value: 'UNKNOWN', label: '未知 / 未声明' },
  { value: 'NULLABLE', label: '允许空值' },
  { value: 'NOT_NULL', label: '禁止空值' },
];
function nullableValue(field: SubflowField) {
  if (field.nullable === true) return 'NULLABLE';
  if (field.nullable === false) return 'NOT_NULL';
  return 'UNKNOWN';
}
function changeNullable(field: SubflowField, value: unknown) {
  if (value === 'UNKNOWN') field.nullable = null;
  else if (value === 'NULLABLE') field.nullable = true;
  else if (value === 'NOT_NULL') field.nullable = false;
}
function clearNumericConstraints(field: SubflowField) {
  field.precision = null;
  field.scale = null;
}
function hasNumericConstraints(field: SubflowField) {
  const value = field.precision ?? field.scale;
  return value !== undefined && value !== null;
}
function changeType(field: SubflowField, value: unknown) {
  if (typeof value !== 'string' || !types.some((item) => item.value === value))
    return;
  field.type = value;
  if (value !== 'Decimal' && hasNumericConstraints(field))
    clearNumericConstraints(field);
}
function changeNumeric(
  field: SubflowField,
  key: 'precision' | 'scale',
  value: unknown,
) {
  if (value === undefined || value === null || value === '') {
    field[key] = null;
    return;
  }
  const minimum = key === 'precision' ? 1 : -1000;
  if (
    typeof value === 'number' &&
    Number.isInteger(value) &&
    value >= minimum &&
    value <= 1000
  )
    field[key] = value;
}
onMounted(() => {
  props.config.fields ??= [{ name: '', type: 'Object' }];
});
</script>
<template>
  <div class="etl-editor">
    <Alert
      type="info"
      show-icon
      message="含边界节点的流程是可复用子流程定义，只能发布供父流程调用，不能直接运行或调度。定义至少包含一个输入和一个输出，内部仅允许纯转换。"
    />
    <div class="form-item">
      <label class="form-item__label form-item__label--required">端口名称</label><Input
        v-model:value="config.portName"
        :maxlength="32"
        placeholder="字母开头，可用字母、数字、_ 和 -"
      />
    </div>
    <div class="form-item">
      <label class="form-item__label">字段契约</label>
      <div
        v-for="(field, index) in config.fields ?? []"
        :key="index"
        class="space-y-2 rounded border p-3"
      >
        <div class="editor-row">
          <Input
            v-model:value="field.name"
            placeholder="字段名称"
            :maxlength="128"
          />
          <Select
            :value="field.type"
            :options="types"
            class="w-28"
            aria-label="字段类型"
            @update:value="(value) => changeType(field, value)"
          />
          <Select
            :value="nullableValue(field)"
            :options="nullableOptions"
            class="w-36"
            aria-label="可空性"
            @update:value="(value) => changeNullable(field, value)"
          />
          <Button danger size="small" @click="config.fields.splice(index, 1)">
            <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
            删除
          </Button>
        </div>
        <div v-if="field.type === 'Decimal'" class="flex gap-3">
          <label class="flex-1 text-sm">
            精度（1–1000，可选）
            <InputNumber
              :value="field.precision ?? undefined"
              :min="1"
              :max="1000"
              :precision="0"
              placeholder="未知 / 未声明"
              class="w-full"
              aria-label="Decimal精度"
              @update:value="
                (value) => changeNumeric(field, 'precision', value)
              "
            />
          </label>
          <label class="flex-1 text-sm">
            scale（-1000–1000，可选）
            <InputNumber
              :value="field.scale ?? undefined"
              :min="-1000"
              :max="1000"
              :precision="0"
              placeholder="未知 / 未声明"
              class="w-full"
              aria-label="Decimalscale"
              @update:value="(value) => changeNumeric(field, 'scale', value)"
            />
          </label>
        </div>
        <template v-else-if="hasNumericConstraints(field)">
          <Alert
            type="error"
            message="精度和 scale 仅支持 Decimal；当前字段含不兼容声明，请清除后重新校验。"
          />
          <Button size="small" @click="clearNumericConstraints(field)">
            <template #icon><IconifyIcon icon="lucide:eraser" /></template>
            清除数值约束
          </Button>
        </template>
        <FieldContractSummary :field="field" class="block" />
        <template
          v-if="field.timezone !== undefined && field.timezone !== null"
        >
          <Alert
            type="error"
            message="当前边界含时区声明，服务端暂不支持；请明确清除后重新校验。"
          />
          <Button size="small" @click="field.timezone = null">
            <template #icon><IconifyIcon icon="lucide:eraser" /></template>
            清除不支持的时区声明
          </Button>
        </template>
      </div>
      <Button
        :disabled="(config.fields?.length ?? 0) >= 256"
        @click="config.fields.push({ name: '', type: 'Object' })"
      >
        <template #icon><IconifyIcon icon="lucide:plus" /></template>
        添加字段
      </Button>
      <p class="form-item__tip">
        缺省属性显示为未知，不代表已经验证。运行时字段必须存在；可空性未声明时沿用历史兼容行为，允许空值，只有显式“禁止空值”才拒绝空值。其他字段原样传递。
      </p>
      <p class="form-item__tip">
        精度与 scale 可单独声明，仅适用于 Decimal；允许负 scale 和 scale
        大于精度。运行时要求精确数值并验证无损表示，不舍入、不改写原值。时区语义尚未支持，暂不能声明。
      </p>
    </div>
  </div>
</template>
