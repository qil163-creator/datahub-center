<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import { onMounted } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Button, Input, TextArea } from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeShellScript' });

const props = defineProps<{
  config: Record<string, any>;
}>();

function envEntries() {
  return Object.entries(props.config.environmentVariables ?? {});
}

function addEnv() {
  if (!props.config.environmentVariables) {
    props.config.environmentVariables = {};
  }
  props.config.environmentVariables[''] = '';
}

function updateEnvKey(oldKey: string, newKey: string) {
  const value = props.config.environmentVariables[oldKey];
  Reflect.deleteProperty(props.config.environmentVariables, oldKey);
  props.config.environmentVariables[newKey] = value;
}

function removeEnv(key: string) {
  Reflect.deleteProperty(props.config.environmentVariables, key);
}

onMounted(() => {
  if (props.config.script === undefined) {
    props.config.script = '';
  }
});
</script>

<template>
  <div class="etl-editor">
    <div class="form-item">
      <label class="form-item__label form-item__label--required">脚本内容</label>
      <TextArea
        v-model:value="config.script"
        :rows="10"
        class="font-mono"
        placeholder="Windows 用 cmd /c 执行，Linux 用 /bin/sh -c 执行；支持 ${params.xxx} 占位符"
      />
      <div class="form-item__tip">
        脚本以服务进程用户身份执行，退出码非 0 视为失败
      </div>
    </div>
    <div class="form-item">
      <label class="form-item__label">工作目录</label>
      <Input v-model:value="config.workingDirectory" allow-clear />
    </div>
    <div class="form-item">
      <label class="form-item__label">环境变量</label>
      <div v-for="[key] in envEntries()" :key="key" class="editor-row">
        <Input
          :value="key"
          class="w-44"
          placeholder="变量名"
          @change="(e: any) => updateEnvKey(key, e.target.value)"
        />
        <Input
          v-model:value="config.environmentVariables[key]"
          class="flex-1"
          placeholder="变量值"
        />
        <IconifyIcon
          class="editor-row__remove size-4"
          icon="lucide:trash-2"
          @click="removeEnv(key)"
        />
      </div>
      <Button class="self-start" size="small" type="dashed" @click="addEnv">
        <template #icon><IconifyIcon icon="lucide:plus" /></template>
        添加环境变量
      </Button>
    </div>
  </div>
</template>
