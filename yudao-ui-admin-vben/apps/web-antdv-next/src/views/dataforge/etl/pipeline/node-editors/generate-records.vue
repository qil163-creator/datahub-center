<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props */
import { onMounted } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Button, Input, InputNumber, Select } from 'antdv-next';

import './editor-style.css';
defineOptions({ name: 'EtlNodeGenerateRecords' });
const props = defineProps<{ config: Record<string, any> }>();
const TYPE_OPTIONS = [
  'String',
  'Integer',
  'Long',
  'Double',
  'Boolean',
  'Date',
].map((value) => ({ label: value, value }));
onMounted(() => {
  if (props.config.count === null || props.config.count === undefined)
    props.config.count = 1;
  if (!Array.isArray(props.config.fields) || props.config.fields.length === 0)
    props.config.fields = [{ name: '', type: 'String', value: '' }];
});
function addField() {
  props.config.fields.push({ name: '', type: 'String', value: '' });
}
</script>
<template><div class="etl-editor"><div class="form-item"><label class="form-item__label form-item__label--required">生成条数</label><InputNumber v-model:value="config.count" :min="0" :max="1000000" class="w-full" /></div><div class="form-item"><label class="form-item__label form-item__label--required">字段定义</label><div v-for="(field, index) in config.fields ?? []" :key="index" class="editor-row"><Input v-model:value="field.name" class="w-32" placeholder="字段名" /><Select v-model:value="field.type" :options="TYPE_OPTIONS" class="w-28" /><Input v-model:value="field.value" class="flex-1" placeholder="固定值" /><IconifyIcon class="editor-row__remove size-4" icon="lucide:trash-2" @click="config.fields.splice(Number(index), 1)" /></div><Button class="self-start" size="small" type="dashed" @click="addField"><template #icon><IconifyIcon icon="lucide:plus" /></template>添加字段</Button></div></div></template>
