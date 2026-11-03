<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props */
import { onMounted } from 'vue';

import { Checkbox, Input, Select } from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeReadGzipCsv' });
const props = defineProps<{ config: Record<string, any> }>();
const CHARSET_OPTIONS = ['utf8', 'GBK', 'GB2312', 'ISO-8859-1'].map(
  (value) => ({ label: value, value }),
);
const DELIMITER_OPTIONS = [
  { label: '逗号 ,', value: ',' },
  { label: '分号 ;', value: ';' },
  { label: '竖线 |', value: '|' },
  { label: '制表符 Tab', value: 'TAB' },
];
onMounted(() => {
  const defaults = {
    charset: 'utf8',
    delimiter: ',',
    hasHeader: true,
    skipEmptyRows: true,
  };
  Object.entries(defaults).forEach(([key, value]) => {
    if (props.config[key] === null || props.config[key] === undefined)
      props.config[key] = value;
  });
});
</script>
<template>
  <div class="etl-editor">
    <div class="form-item"><label class="form-item__label form-item__label--required">GZIP CSV 地址</label><Input v-model:value="config.fileUrl" allow-clear placeholder="http(s):// 开头的 .csv.gz 文件地址" /></div>
    <div class="flex gap-4"><div class="form-item flex-1"><label class="form-item__label">文件编码</label><Select v-model:value="config.charset" :options="CHARSET_OPTIONS" /></div><div class="form-item flex-1"><label class="form-item__label">分隔符</label><Select v-model:value="config.delimiter" :options="DELIMITER_OPTIONS" /></div></div>
    <div class="form-item"><Checkbox :checked="config.hasHeader !== false" @update:checked="(v: boolean) => (config.hasHeader = v)">首行为表头</Checkbox><Checkbox :checked="config.skipEmptyRows !== false" @update:checked="(v: boolean) => (config.skipEmptyRows = v)">跳过空行</Checkbox></div>
  </div>
</template>
