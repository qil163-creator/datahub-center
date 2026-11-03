<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import { onMounted } from 'vue';

import { Checkbox, Input } from 'antdv-next';

import OutputStorageField from './output-storage-field.vue';

import './editor-style.css';

defineOptions({ name: 'EtlNodeWriteExcel' });

const props = defineProps<{
  config: Record<string, any>;
}>();

onMounted(() => {
  if (props.config.sheetName === null || props.config.sheetName === undefined) {
    props.config.sheetName = 'Sheet1';
  }
  if (
    props.config.includeHeader === null ||
    props.config.includeHeader === undefined
  ) {
    props.config.includeHeader = true;
  }
});
</script>

<template>
  <div class="etl-editor">
    <OutputStorageField v-model="config.filePath" />
    <div class="form-item">
      <label class="form-item__label form-item__label--required">文件名</label>
      <Input
        v-model:value="config.fileName"
        allow-clear
        placeholder="result.xlsx（支持 ${sys.today} 占位符）"
      />
    </div>
    <div class="form-item">
      <label class="form-item__label">工作表名</label>
      <Input v-model:value="config.sheetName" allow-clear />
    </div>
    <div class="form-item">
      <Checkbox
        :checked="config.includeHeader !== false"
        @update:checked="(v: boolean) => (config.includeHeader = v)"
      >
        首行写入字段名标题
      </Checkbox>
    </div>
  </div>
</template>
