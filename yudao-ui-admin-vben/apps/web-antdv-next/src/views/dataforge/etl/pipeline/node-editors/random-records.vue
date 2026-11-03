<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props */
import { onMounted } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Button, Input, InputNumber, Select } from 'antdv-next';

import './editor-style.css';
defineOptions({ name: 'EtlNodeRandomRecords' });
const props = defineProps<{ config: Record<string, any> }>();
const TYPE_OPTIONS = [
  'INTEGER',
  'LONG',
  'DOUBLE',
  'BOOLEAN',
  'STRING',
  'UUID',
].map((value) => ({ label: value, value }));
onMounted(() => {
  if (props.config.count === null || props.config.count === undefined)
    props.config.count = 10;
  if (!Array.isArray(props.config.fields) || props.config.fields.length === 0)
    props.config.fields = [{ name: '', type: 'INTEGER', min: 0, max: 100 }];
});
function addField() {
  props.config.fields.push({ name: '', type: 'INTEGER', min: 0, max: 100 });
}
</script>
<template><div class="etl-editor"><div class="flex gap-4"><div class="form-item flex-1"><label class="form-item__label form-item__label--required">生成条数</label><InputNumber v-model:value="config.count" :min="0" :max="1000000" class="w-full" /></div><div class="form-item flex-1"><label class="form-item__label">随机种子（可选）</label><InputNumber v-model:value="config.seed" class="w-full" /></div></div><div class="form-item"><label class="form-item__label form-item__label--required">随机字段</label><div v-for="(field, index) in config.fields ?? []" :key="index" class="editor-row flex-wrap"><Input v-model:value="field.name" class="w-28" placeholder="字段名" /><Select v-model:value="field.type" :options="TYPE_OPTIONS" class="w-28" /><template v-if="['INTEGER', 'LONG', 'DOUBLE'].includes(field.type)"><InputNumber v-model:value="field.min" class="w-24" placeholder="最小" /><InputNumber v-model:value="field.max" class="w-24" placeholder="最大" /></template><InputNumber v-else-if="field.type === 'STRING'" v-model:value="field.length" :min="0" class="w-24" placeholder="长度" /><IconifyIcon class="editor-row__remove size-4" icon="lucide:trash-2" @click="config.fields.splice(Number(index), 1)" /></div><Button class="self-start" size="small" type="dashed" @click="addField"><template #icon><IconifyIcon icon="lucide:plus" /></template>添加字段</Button></div></div></template>
