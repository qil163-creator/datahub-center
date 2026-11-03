<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props */
import { onMounted } from 'vue';

import { Input, RadioGroup, Select } from 'antdv-next';

import OutputStorageField from './output-storage-field.vue';

import './editor-style.css';
defineOptions({ name: 'EtlNodeWriteJson' });
const props = defineProps<{ config: Record<string, any> }>();
const CHARSET_OPTIONS = ['utf8', 'GBK', 'GB2312'].map((value) => ({
  label: value,
  value,
}));
const FORMAT_OPTIONS = [
  { label: 'JSON 数组', value: 'ARRAY' },
  { label: 'NDJSON（逐行）', value: 'NDJSON' },
];
onMounted(() => {
  Object.entries({ charset: 'utf8', format: 'ARRAY' }).forEach(
    ([key, value]) => {
      if (props.config[key] === null || props.config[key] === undefined)
        props.config[key] = value;
    },
  );
});
</script>
<template>
  <div class="etl-editor">
    <OutputStorageField v-model="config.filePath" />
    <div class="form-item">
      <label class="form-item__label form-item__label--required">文件名</label><Input v-model:value="config.fileName" placeholder="result.json" />
    </div>
    <div class="flex gap-4">
      <div class="form-item flex-1">
        <label class="form-item__label">编码</label><Select v-model:value="config.charset" :options="CHARSET_OPTIONS" />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label">格式</label><RadioGroup
          v-model:value="config.format"
          :options="FORMAT_OPTIONS"
          option-type="button"
        />
      </div>
    </div>
  </div>
</template>
