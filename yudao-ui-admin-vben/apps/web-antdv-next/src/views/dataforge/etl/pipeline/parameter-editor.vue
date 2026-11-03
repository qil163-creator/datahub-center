<script lang="ts" setup>
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed } from 'vue';

import { IconifyIcon } from '@vben/icons';

import {
  Alert,
  Button,
  Checkbox,
  Input,
  InputNumber,
  Select,
} from 'antdv-next';

import { PARAMETER_TYPES, parameterDefinitionIssues } from './parameters';

const props = defineProps<{ modelValue?: DataforgeEtlApi.ParamItem[] }>();
const emit = defineEmits<{
  'update:modelValue': [DataforgeEtlApi.ParamItem[]];
}>();
const items = computed(() => props.modelValue ?? []);
const issues = computed(() => parameterDefinitionIssues(items.value));
function update(index: number, changes: Partial<DataforgeEtlApi.ParamItem>) {
  emit(
    'update:modelValue',
    items.value.map((item, i) =>
      i === index ? { ...item, ...changes } : item,
    ),
  );
}
function changeType(index: number, type: unknown) {
  if (
    type !== 'STRING' &&
    type !== 'INTEGER' &&
    type !== 'DECIMAL' &&
    type !== 'BOOLEAN'
  )
    return;
  update(index, {
    type,
    min: undefined,
    max: undefined,
    minLength: undefined,
    maxLength: undefined,
  });
}
function changeBooleanDefault(index: number, value: unknown) {
  if (value === 'true' || value === 'false')
    update(index, { defaultValue: value });
}
</script>
<template>
  <div class="space-y-3">
    <p class="text-sm text-muted-foreground">
      参数值与数值上下限始终按字符串保存；整数和精确小数不支持指数写法。节点配置中用
      ${params.名称} 引用。
    </p>
    <div
      v-for="(item, index) in items"
      :key="index"
      class="rounded border p-3 space-y-2"
    >
      <div class="flex gap-2">
        <Input
          :value="item.name"
          placeholder="参数名"
          :maxlength="64"
          @update:value="(value) => update(index, { name: value })"
        />
        <Select
          :value="item.type ?? 'STRING'"
          :options="PARAMETER_TYPES"
          class="min-w-28"
          @update:value="(value) => changeType(index, value)"
        />
        <Button
          danger
          @click="
            emit(
              'update:modelValue',
              items.filter((_, i) => i !== index),
            )
          "
        >
          <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
          删除参数
        </Button>
      </div>
      <div class="flex gap-3">
        <Checkbox
          :checked="item.required === true"
          @update:checked="(value) => update(index, { required: value })"
        >
          必填
        </Checkbox>
        <Checkbox
          :checked="
            item.defaultValue !== undefined && item.defaultValue !== null
          "
          @update:checked="
            (value) => update(index, { defaultValue: value ? '' : undefined })
          "
        >
          设置默认值
        </Checkbox>
      </div>
      <Select
        v-if="
          item.defaultValue !== undefined &&
          item.defaultValue !== null &&
          item.type === 'BOOLEAN'
        "
        :value="item.defaultValue"
        :options="[
          { label: 'true', value: 'true' },
          { label: 'false', value: 'false' },
        ]"
        class="w-full"
        placeholder="默认值"
        @update:value="(value) => changeBooleanDefault(index, value)"
      />
      <Input
        v-else-if="
          item.defaultValue !== undefined && item.defaultValue !== null
        "
        :value="item.defaultValue"
        placeholder="默认值（原样字符串）"
        :maxlength="4096"
        @update:value="(value) => update(index, { defaultValue: value })"
      />
      <div
        v-if="item.type === 'INTEGER' || item.type === 'DECIMAL'"
        class="flex gap-2"
      >
        <Input
          :value="item.min"
          placeholder="最小值（可选）"
          :maxlength="128"
          @update:value="
            (value) => update(index, { min: value === '' ? undefined : value })
          "
        />
        <Input
          :value="item.max"
          placeholder="最大值（可选）"
          :maxlength="128"
          @update:value="
            (value) => update(index, { max: value === '' ? undefined : value })
          "
        />
      </div>
      <div v-if="!item.type || item.type === 'STRING'" class="flex gap-2">
        <InputNumber
          :value="item.minLength"
          :min="0"
          :max="4096"
          :precision="0"
          placeholder="最小长度"
          @update:value="
            (value) => update(index, { minLength: value ?? undefined })
          "
        />
        <InputNumber
          :value="item.maxLength"
          :min="0"
          :max="4096"
          :precision="0"
          placeholder="最大长度"
          @update:value="
            (value) => update(index, { maxLength: value ?? undefined })
          "
        />
      </div>
      <Input
        :value="item.description"
        placeholder="参数说明"
        @update:value="(value) => update(index, { description: value })"
      />
    </div>
    <Button
      :disabled="items.length >= 100"
      @click="
        emit('update:modelValue', [
          ...items,
          { name: '', type: 'STRING', required: false },
        ])
      "
    >
      <template #icon><IconifyIcon icon="lucide:plus" /></template>
      添加参数
    </Button>
    <Alert
      v-if="issues.length"
      type="warning"
      message="参数定义需要修正"
      :description="issues.join('；')"
    />
  </div>
</template>
