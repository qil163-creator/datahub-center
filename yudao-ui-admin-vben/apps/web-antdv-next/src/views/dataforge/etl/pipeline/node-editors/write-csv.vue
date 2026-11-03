<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import { onMounted } from 'vue';

import { Checkbox, Input, Select } from 'antdv-next';

import OutputStorageField from './output-storage-field.vue';

import './editor-style.css';

defineOptions({ name: 'EtlNodeWriteCsv' });

const props = defineProps<{
  config: Record<string, any>;
}>();

// oxlint-disable-next-line unicorn/text-encoding-identifier-case -- 标准字符集名传给 Java Charset，保持已有发布配置的拼写。
const CHARSET_OPTIONS = ['UTF-8', 'GBK', 'GB2312', 'ISO-8859-1'].map(
  (value) => ({ label: value, value }),
);

const DELIMITER_OPTIONS = [
  { label: '逗号 ,', value: ',' },
  { label: '分号 ;', value: ';' },
  { label: '竖线 |', value: '|' },
  { label: '制表符 Tab', value: 'TAB' },
];

onMounted(() => {
  const defaults: Record<string, any> = {
    // oxlint-disable-next-line unicorn/text-encoding-identifier-case -- 与 Java Charset 及编码选择项保持一致。
    charset: 'UTF-8',
    delimiter: ',',
    includeHeader: true,
  };
  Object.entries(defaults).forEach(([key, value]) => {
    if (props.config[key] === null || props.config[key] === undefined) {
      props.config[key] = value;
    }
  });
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
        placeholder="result.csv（支持 ${sys.today} 占位符）"
      />
    </div>
    <div class="flex gap-4">
      <div class="form-item flex-1">
        <label class="form-item__label">文件编码</label>
        <Select v-model:value="config.charset" :options="CHARSET_OPTIONS" />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label">分隔符</label>
        <Select v-model:value="config.delimiter" :options="DELIMITER_OPTIONS" />
      </div>
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
