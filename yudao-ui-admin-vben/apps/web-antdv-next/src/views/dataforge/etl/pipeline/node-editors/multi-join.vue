<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, onMounted } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { AutoComplete, Button, InputNumber, Select } from 'antdv-next';

import './editor-style.css';
defineOptions({ name: 'EtlNodeMultiJoin' });
const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();
const JOIN_OPTIONS = [
  { label: '内连接', value: 'INNER' },
  { label: '左连接（保留首个上游）', value: 'LEFT' },
];
const fieldOptions = computed(() =>
  (props.inputFields ?? []).map((field) => ({
    value: field.name!,
    label: field.name,
  })),
);
onMounted(() => {
  if (!props.config.joinType) props.config.joinType = 'INNER';
  if (
    props.config.maxOutputRows === null ||
    props.config.maxOutputRows === undefined
  )
    props.config.maxOutputRows = 1_000_000;
  if (!Array.isArray(props.config.joinConditions))
    props.config.joinConditions = [{ leftField: '', rightField: '' }];
});
</script>
<template><div class="etl-editor"><div class="form-item"><label class="form-item__label">连接方式</label><Select v-model:value="config.joinType" :options="JOIN_OPTIONS" /><div class="form-item__tip">按上游接入顺序依次连接，至少需要两个上游</div></div><div class="form-item"><label class="form-item__label form-item__label--required">关联条件</label><div v-for="(condition, index) in config.joinConditions ?? []" :key="index" class="editor-row"><AutoComplete v-model:value="condition.leftField" :options="fieldOptions" class="flex-1" placeholder="左侧字段" /><span>=</span><AutoComplete v-model:value="condition.rightField" :options="fieldOptions" class="flex-1" placeholder="右侧字段" /><IconifyIcon class="editor-row__remove size-4" icon="lucide:trash-2" @click="config.joinConditions.splice(Number(index), 1)" /></div><Button class="self-start" size="small" type="dashed" @click="config.joinConditions.push({ leftField: '', rightField: '' })"><template #icon><IconifyIcon icon="lucide:plus" /></template>添加条件</Button></div><div class="form-item"><label class="form-item__label">最大输出行数</label><InputNumber v-model:value="config.maxOutputRows" :min="1" :max="10000000" class="w-full" /></div></div></template>
