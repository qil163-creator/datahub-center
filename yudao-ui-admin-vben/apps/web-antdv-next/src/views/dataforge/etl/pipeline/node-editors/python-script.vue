<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import { onMounted } from 'vue';

import { Input, TextArea } from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodePythonScript' });

const props = defineProps<{
  config: Record<string, any>;
}>();

onMounted(() => {
  if (!props.config.interpreter) {
    props.config.interpreter = 'python';
  }
  if (props.config.script === undefined) {
    props.config.script = '';
  }
});
</script>

<template>
  <div class="etl-editor">
    <div class="form-item">
      <label class="form-item__label">解释器</label>
      <Input
        v-model:value="config.interpreter"
        allow-clear
        placeholder="python / python3 / 完整路径"
      />
    </div>
    <div class="form-item">
      <label class="form-item__label form-item__label--required">脚本内容</label>
      <TextArea
        v-model:value="config.script"
        :rows="12"
        class="font-mono"
        placeholder="脚本写入临时 .py 文件后执行；支持 ${params.xxx} 占位符"
      />
      <div class="form-item__tip">需在服务器安装对应解释器，退出码非 0 视为失败</div>
    </div>
    <div class="form-item">
      <label class="form-item__label">工作目录</label>
      <Input v-model:value="config.workingDirectory" allow-clear />
    </div>
  </div>
</template>
