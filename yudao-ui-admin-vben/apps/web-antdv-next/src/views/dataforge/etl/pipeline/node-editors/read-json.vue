<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props */
import { onMounted } from 'vue';

import { Input, InputNumber } from 'antdv-next';

import './editor-style.css';
defineOptions({ name: 'EtlNodeReadJson' });
const props = defineProps<{ config: Record<string, any> }>();
onMounted(() => {
  if (props.config.maxRows === null || props.config.maxRows === undefined)
    props.config.maxRows = 100_000;
});
</script>
<template><div class="etl-editor"><div class="form-item"><label class="form-item__label form-item__label--required">JSON 文件地址</label><Input v-model:value="config.fileUrl" allow-clear placeholder="http(s):// 开头的 JSON 文件地址" /></div><div class="form-item"><label class="form-item__label">最大行数</label><InputNumber v-model:value="config.maxRows" :min="1" :max="1000000" class="w-full" /><div class="form-item__tip">根数组或 data/rows/items/results 数组会被转换为数据行</div></div></div></template>
