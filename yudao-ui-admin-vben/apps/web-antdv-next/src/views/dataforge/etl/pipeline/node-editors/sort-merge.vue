<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, onMounted } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { AutoComplete, Button, Select } from 'antdv-next';

import './editor-style.css';
defineOptions({ name: 'EtlNodeSortMerge' });
const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();
const ORDER_OPTIONS = [
  { label: '升序', value: 'ASC' },
  { label: '降序', value: 'DESC' },
];
const fieldOptions = computed(() =>
  (props.inputFields ?? []).map((field) => ({
    value: field.name!,
    label: `${field.name}（${field.dataType ?? '-'}）`,
  })),
);
onMounted(() => {
  if (!Array.isArray(props.config.sorts))
    props.config.sorts = [{ field: '', order: 'ASC' }];
});
</script>
<template><div class="etl-editor"><div class="form-item"><label class="form-item__label form-item__label--required">排序规则</label><div v-for="(sort, index) in config.sorts ?? []" :key="index" class="editor-row"><AutoComplete v-model:value="sort.field" :options="fieldOptions" class="flex-1" placeholder="字段名" /><Select v-model:value="sort.order" :options="ORDER_OPTIONS" class="w-24" /><IconifyIcon class="editor-row__remove size-4" icon="lucide:trash-2" @click="config.sorts.splice(Number(index), 1)" /></div><Button class="self-start" size="small" type="dashed" @click="config.sorts.push({ field: '', order: 'ASC' })"><template #icon><IconifyIcon icon="lucide:plus" /></template>添加排序字段</Button></div></div></template>
