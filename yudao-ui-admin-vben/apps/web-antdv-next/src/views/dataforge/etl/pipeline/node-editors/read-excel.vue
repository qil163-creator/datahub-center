<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import { onMounted } from 'vue';

import { Checkbox, Input, InputNumber } from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeReadExcel' });

const props = defineProps<{
  config: Record<string, any>;
}>();

onMounted(() => {
  const defaults: Record<string, any> = {
    headerRow: 1,
    startRow: 2,
    endRow: 0,
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
        placeholder="http(s):// 开头的 Excel 文件地址"
      />
      <div class="form-item__tip">
        可先在「数据研发 / 文件管理」上传文件后复制访问地址
      </div>
    </div>
    <div class="form-item">
      <label class="form-item__label">工作表名（空取第一个）</label>
      <Input
        v-model:value="config.sheetName"
        allow-clear
        placeholder="Sheet1"
      />
    </div>
    <div class="flex gap-4">
      <div class="form-item flex-1">
        <label class="form-item__label">标题行号（0 表示无标题）</label>
        <InputNumber v-model:value="config.headerRow" :min="0" class="w-full" />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label">数据起始行</label>
        <InputNumber v-model:value="config.startRow" :min="1" class="w-full" />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label">结束行（0 到末尾）</label>
        <InputNumber v-model:value="config.endRow" :min="0" class="w-full" />
      </div>
    </div>
    <div class="form-item">
      <Checkbox
        :checked="config.skipEmptyRows !== false"
        @update:checked="(v: boolean) => (config.skipEmptyRows = v)"
      >
        跳过空行
      </Checkbox>
    </div>
  </div>
</template>
