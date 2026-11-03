<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import { onMounted } from 'vue';

import { InputNumber, TextArea } from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeJsScript' });

const props = defineProps<{
  config: Record<string, any>;
}>();

const DEFAULT_CODE = `/**
 * 按批处理数据行（rows 为对象数组，可增删改行与字段），返回值作为节点输出
 * 引擎为 Nashorn（ES6 语法子集）
 */
function execute(rows) {
    for (var i = 0; i < rows.length; i++) {
        var row = rows[i];
        // TODO 在此处理数据，例如：row.new_field = 'value';
    }
    return rows;
}
`;

onMounted(() => {
  if (!props.config.code) {
    props.config.code = DEFAULT_CODE;
  }
  if (props.config.batchSize == null) {
    props.config.batchSize = 1000;
  }
});
</script>

<template>
  <div class="etl-editor">
    <div class="form-item">
      <label class="form-item__label form-item__label--required">
        脚本代码（需定义 execute(rows) 函数）
      </label>
      <TextArea
        v-model:value="config.code"
        :rows="16"
        class="js-script__code"
        spellcheck="false"
      />
      <div class="form-item__tip">
        按批调用 execute(rows)，返回值作为节点输出；数据经 JSON 转换传入脚本，
        数值/日期以 JSON 语义为准
      </div>
    </div>
    <div class="form-item w-52">
      <label class="form-item__label">批次行数</label>
      <InputNumber v-model:value="config.batchSize" :min="1" class="w-full" />
    </div>
  </div>
</template>

<style scoped>
.js-script__code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
}
</style>
