<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props */
import { onMounted } from 'vue';

import { Input, Select, TextArea } from 'antdv-next';

import OutputStorageField from './output-storage-field.vue';

import './editor-style.css';
defineOptions({ name: 'EtlNodeWriteText' });
const props = defineProps<{ config: Record<string, any> }>();
const CHARSET_OPTIONS = ['utf8', 'GBK', 'GB2312'].map((value) => ({
  label: value,
  value,
}));
const SEPARATOR_OPTIONS = [
  { label: 'LF（\\n）', value: '\n' },
  { label: 'CRLF（\\r\\n）', value: '\r\n' },
];
onMounted(() => {
  Object.entries({
    charset: 'utf8',
    lineSeparator: '\n',
    template: ['$', '{field}'].join(''),
  }).forEach(([key, value]) => {
    if (props.config[key] === null || props.config[key] === undefined)
      props.config[key] = value;
  });
});
</script>
<template>
  <div class="etl-editor">
    <OutputStorageField v-model="config.filePath" />
    <div class="form-item">
      <label class="form-item__label form-item__label--required">文件名</label><Input v-model:value="config.fileName" placeholder="result.txt" />
    </div>
    <div class="flex gap-4">
      <div class="form-item flex-1">
        <label class="form-item__label">编码</label><Select v-model:value="config.charset" :options="CHARSET_OPTIONS" />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label">换行符</label><Select
          v-model:value="config.lineSeparator"
          :options="SEPARATOR_OPTIONS"
        />
      </div>
    </div>
    <div class="form-item">
      <label class="form-item__label form-item__label--required">行模板</label><TextArea
        v-model:value="config.template"
        :rows="4"
        placeholder="例如：${id},${name}"
      />
      <div class="form-item__tip">使用 ${fieldName} 引用当前行字段</div>
    </div>
  </div>
</template>
