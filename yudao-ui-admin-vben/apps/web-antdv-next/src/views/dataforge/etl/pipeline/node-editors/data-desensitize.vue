<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { onMounted } from 'vue';

import { IconifyIcon } from '@vben/icons';

import {
  AutoComplete,
  Button,
  Checkbox,
  Input,
  InputNumber,
  Select,
} from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeDataDesensitize' });

const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();

const STRATEGY_OPTIONS = [
  { label: '身份证号', value: 'ID_CARD' },
  { label: '手机号', value: 'PHONE' },
  { label: '邮箱', value: 'EMAIL' },
  { label: '中文姓名', value: 'NAME' },
  { label: '银行卡号', value: 'BANK_CARD' },
  { label: '地址', value: 'ADDRESS' },
  { label: 'IP 地址', value: 'IP_ADDRESS' },
  { label: '通用掩码', value: 'MASK' },
  { label: '固定替换', value: 'REPLACE' },
  { label: '截断', value: 'TRUNCATE' },
  { label: '置空', value: 'NULL_REPLACE' },
];

function fieldOptions() {
  return (props.inputFields ?? []).map((field) => ({
    value: field.name!,
    label: `${field.name}（${field.dataType ?? '-'}）`,
  }));
}

function addField() {
  props.config.desensitizeFields.push({
    fieldName: '',
    strategy: 'MASK',
    maskChar: '*',
    keepStart: 1,
    keepEnd: 1,
    keepOriginal: false,
  });
}

onMounted(() => {
  if (!Array.isArray(props.config.desensitizeFields)) {
    props.config.desensitizeFields = [];
    addField();
  }
});
</script>

<template>
  <div class="etl-editor">
    <div
      v-for="(field, index) in config.desensitizeFields ?? []"
      :key="index"
      class="desensitize-field"
    >
      <div class="editor-row">
        <AutoComplete
          v-model:value="field.fieldName"
          :options="fieldOptions()"
          class="w-44"
          placeholder="字段名"
        />
        <Select
          v-model:value="field.strategy"
          :options="STRATEGY_OPTIONS"
          class="w-32"
        />
        <IconifyIcon
          class="editor-row__remove size-4"
          icon="lucide:trash-2"
          @click="config.desensitizeFields.splice(Number(index), 1)"
        />
      </div>
      <div v-if="field.strategy === 'MASK'" class="editor-row">
        <Input
          v-model:value="field.maskChar"
          :maxlength="1"
          class="w-24"
          placeholder="掩码符"
        />
        <span class="text-xs">保留头</span>
        <InputNumber v-model:value="field.keepStart" :min="0" class="w-20" />
        <span class="text-xs">保留尾</span>
        <InputNumber v-model:value="field.keepEnd" :min="0" class="w-20" />
      </div>
      <div v-else-if="field.strategy === 'REPLACE'" class="editor-row">
        <Input
          v-model:value="field.replaceValue"
          class="flex-1"
          placeholder="替换为固定值，如 ***"
        />
      </div>
      <div v-else-if="field.strategy === 'TRUNCATE'" class="editor-row">
        <span class="text-xs">保留前</span>
        <InputNumber
          v-model:value="field.truncateLength"
          :min="0"
          class="w-24"
        />
        <span class="text-xs">个字符</span>
      </div>
      <div class="editor-row">
        <Input
          v-model:value="field.outputFieldName"
          class="flex-1"
          placeholder="输出字段名（空=覆盖原字段）"
        />
        <Checkbox
          :checked="field.keepOriginal === true"
          @update:checked="(v: boolean) => (field.keepOriginal = v)"
        >
          保留原字段
        </Checkbox>
      </div>
    </div>
    <Button class="self-start" size="small" type="dashed" @click="addField">
      <template #icon><IconifyIcon icon="lucide:plus" /></template>
      添加字段
    </Button>
  </div>
</template>

<style scoped>
.desensitize-field {
  padding: 8px;
  margin-bottom: 8px;
  background: hsl(var(--muted) / 30%);
  border-radius: 6px;
}
</style>
