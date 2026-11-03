<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import { onMounted } from 'vue';

import { Checkbox, Input, Select } from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeReadCsv' });

const props = defineProps<{
  config: Record<string, any>;
}>();

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
    charset: 'UTF-8',
    delimiter: ',',
    hasHeader: true,
    skipEmptyRows: true,
  };
  Object.entries(defaults).forEach(([key, value]) => {
    if (props.config[key] == null) {
      props.config[key] = value;
    }
  });
});
</script>

<template>
  <div class="etl-editor">
    <div class="form-item">
      <label class="form-item__label form-item__label--required">
        文件地址
      </label>
      <Input
        v-model:value="config.fileUrl"
        allow-clear
        placeholder="http(s):// 开头的 CSV 文件地址"
      />
      <div class="form-item__tip">
        可先在「数据研发 / 文件管理」上传文件后复制访问地址
      </div>
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
        :checked="config.hasHeader !== false"
        @update:checked="(v: boolean) => (config.hasHeader = v)"
      >
        首行为表头（否则按 A/B/C... 生成列名）
      </Checkbox>
      <Checkbox
        :checked="config.skipEmptyRows !== false"
        @update:checked="(v: boolean) => (config.skipEmptyRows = v)"
      >
        跳过空行
      </Checkbox>
    </div>
  </div>
</template>
