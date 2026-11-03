<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, onMounted } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { AutoComplete, Button, Input, Select } from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeCalculator' });

const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();

/** 计算类型（arity：参与字段数量） */
const CALC_TYPES: { arity: number; label: string; value: string }[] = [
  { label: 'A + B', value: 'ADD', arity: 2 },
  { label: 'A - B', value: 'SUB', arity: 2 },
  { label: 'A * B', value: 'MUL', arity: 2 },
  { label: 'A / B', value: 'DIV', arity: 2 },
  { label: 'A + B + C', value: 'ADD3', arity: 3 },
  { label: 'Min(A, B)', value: 'MIN', arity: 2 },
  { label: 'Max(A, B)', value: 'MAX', arity: 2 },
  { label: 'Abs(A)', value: 'ABS', arity: 1 },
  { label: 'Round(A)', value: 'ROUND', arity: 1 },
  { label: 'Ceil(A)', value: 'CEIL', arity: 1 },
  { label: 'Floor(A)', value: 'FLOOR', arity: 1 },
  { label: 'A MOD B', value: 'MOD', arity: 2 },
  { label: 'A ^ B', value: 'POWER', arity: 2 },
  { label: 'SQRT(A)', value: 'SQRT', arity: 1 },
  { label: 'A + B Days', value: 'ADD_DAYS', arity: 2 },
  { label: 'A - B (in days)', value: 'DIFF_DAYS', arity: 2 },
  { label: 'Year of date A', value: 'YEAR', arity: 1 },
  { label: 'Month of date A', value: 'MONTH', arity: 1 },
  { label: 'Day of date A', value: 'DAY', arity: 1 },
  { label: 'String length(A)', value: 'STR_LEN', arity: 1 },
  { label: 'Upper case(A)', value: 'UPPER', arity: 1 },
  { label: 'Lower case(A)', value: 'LOWER', arity: 1 },
  { label: 'Init cap(A)', value: 'INIT_CAP', arity: 1 },
  { label: 'Trim(A)', value: 'TRIM', arity: 1 },
];

const VALUE_TYPE_OPTIONS = [
  { label: '字符串', value: 'STRING' },
  { label: '整数', value: 'INTEGER' },
  { label: '小数', value: 'DOUBLE' },
];

const fieldOptions = computed(() =>
  (props.inputFields ?? []).map((field) => ({
    value: field.name!,
    label: `${field.name}（${field.dataType ?? '-'}）`,
  })),
);

function arityOf(calcType?: string) {
  return CALC_TYPES.find((item) => item.value === calcType)?.arity ?? 0;
}

onMounted(() => {
  if (!Array.isArray(props.config.rules)) {
    props.config.rules = [];
  }
  if (props.config.rules.length === 0) {
    addRule();
  }
});

function addRule() {
  props.config.rules.push({
    targetField: '',
    calcType: undefined,
    fieldA: '',
    fieldB: '',
    fieldC: '',
    valueType: 'STRING',
  });
}
</script>

<template>
  <div class="etl-editor">
    <div class="form-item">
      <label class="form-item__label form-item__label--required">
        计算规则（按顺序执行，可引用前序结果字段）
      </label>
      <div
        v-for="(rule, index) in config.rules ?? []"
        :key="index"
        class="editor-row"
      >
        <Input
          v-model:value="rule.targetField"
          class="w-28"
          placeholder="结果字段"
        />
        <Select
          v-model:value="rule.calcType"
          :options="CALC_TYPES"
          class="w-44"
          placeholder="计算类型"
          show-search
        />
        <AutoComplete
          v-if="arityOf(rule.calcType) >= 1"
          v-model:value="rule.fieldA"
          :options="fieldOptions"
          class="w-28"
          placeholder="字段A"
        />
        <AutoComplete
          v-if="arityOf(rule.calcType) >= 2"
          v-model:value="rule.fieldB"
          :options="fieldOptions"
          class="w-28"
          placeholder="字段B"
        />
        <AutoComplete
          v-if="arityOf(rule.calcType) >= 3"
          v-model:value="rule.fieldC"
          :options="fieldOptions"
          class="w-28"
          placeholder="字段C"
        />
        <Select
          v-model:value="rule.valueType"
          :options="VALUE_TYPE_OPTIONS"
          class="w-24"
        />
        <IconifyIcon
          class="editor-row__remove size-4"
          icon="lucide:trash-2"
          @click="config.rules.splice(Number(index), 1)"
        />
      </div>
      <Button class="self-start" size="small" type="dashed" @click="addRule">
        <template #icon><IconifyIcon icon="lucide:plus" /></template>
        新增规则
      </Button>
      <div class="form-item__tip">
        日期计算（A + B Days
        等）要求字段值可解析为日期；数值计算非数值时结果为空
      </div>
    </div>
  </div>
</template>
