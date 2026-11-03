<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { onMounted } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { AutoComplete, Button, Input, RadioGroup, Select } from 'antdv-next';

import { FILTER_OPERATOR_OPTIONS } from '../../data';

defineOptions({ name: 'EtlNodeDataFilter' });

const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();

const LOGICAL_OPTIONS = [
  { label: '全部满足（AND）', value: 'AND' },
  { label: '任一满足（OR）', value: 'OR' },
];

function fieldOptions() {
  return (props.inputFields ?? []).map((field) => ({
    value: field.name!,
    label: `${field.name}（${field.dataType ?? '-'}）`,
  }));
}

function addCondition() {
  props.config.filterConditions.push({
    fieldName: '',
    operator: 'EQUALS',
    value: '',
  });
}

function removeCondition(index: number) {
  props.config.filterConditions.splice(index, 1);
}

function needValue(operator?: string) {
  return (
    operator !== 'IS_NULL' &&
    operator !== 'IS_NOT_NULL' &&
    operator !== 'BETWEEN'
  );
}

onMounted(() => {
  if (!props.config.logicalOperator) {
    props.config.logicalOperator = 'AND';
  }
  if (!Array.isArray(props.config.filterConditions)) {
    props.config.filterConditions = [
      { fieldName: '', operator: 'EQUALS', value: '' },
    ];
  }
});
</script>

<template>
  <div class="flex flex-col gap-3">
    <div class="form-item">
      <label class="form-item__label">条件组合</label>
      <RadioGroup
        v-model:value="config.logicalOperator"
        :options="LOGICAL_OPTIONS"
        option-type="button"
      />
    </div>
    <div
      v-for="(condition, index) in config.filterConditions ?? []"
      :key="index"
      class="flex items-center gap-2"
    >
      <AutoComplete
        v-model:value="condition.fieldName"
        :options="fieldOptions()"
        class="w-40"
        placeholder="字段名"
      />
      <Select
        v-model:value="condition.operator"
        :options="FILTER_OPERATOR_OPTIONS"
        class="w-32"
      />
      <template v-if="condition.operator === 'BETWEEN'">
        <Input
          v-model:value="condition.minValue"
          class="flex-1"
          placeholder="最小值"
        />
        <Input
          v-model:value="condition.maxValue"
          class="flex-1"
          placeholder="最大值"
        />
      </template>
      <Input
        v-else-if="needValue(condition.operator)"
        v-model:value="condition.value"
        class="flex-1"
        placeholder="比较值（IN 用逗号分隔）"
      />
      <IconifyIcon
        class="size-4 cursor-pointer text-red-500"
        icon="lucide:trash-2"
        @click="removeCondition(Number(index))"
      />
    </div>
    <Button class="self-start" size="small" type="dashed" @click="addCondition">
      <template #icon><IconifyIcon icon="lucide:plus" /></template>
      添加条件
    </Button>
  </div>
</template>

<style scoped>
.form-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-item__label {
  font-size: 14px;
  font-weight: 500;
}
</style>
