<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import { onMounted } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Button, Checkbox, Input, Select } from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeAddConstant' });

const props = defineProps<{
  config: Record<string, any>;
}>();

const FIELD_TYPE_OPTIONS = [
  'String',
  'Integer',
  'Long',
  'Double',
  'Boolean',
  'Date',
].map((value) => ({ label: value, value }));

onMounted(() => {
  if (!Array.isArray(props.config.fields)) {
    props.config.fields = [];
  }
  if (props.config.fields.length === 0) {
    addField();
  }
});

function addField() {
  props.config.fields.push({
    name: '',
    fieldType: 'String',
    value: '',
    setEmpty: false,
  });
}
</script>

<template>
  <div class="etl-editor">
    <div class="form-item">
      <label class="form-item__label form-item__label--required">
        常量字段（每行数据都会追加，同名覆盖）
      </label>
      <div
        v-for="(field, index) in config.fields ?? []"
        :key="index"
        class="editor-row"
      >
        <Input v-model:value="field.name" class="w-36" placeholder="字段名称" />
        <Select
          v-model:value="field.fieldType"
          :options="FIELD_TYPE_OPTIONS"
          class="w-28"
        />
        <Input
          v-model:value="field.value"
          :disabled="field.setEmpty === true"
          class="flex-1"
          placeholder="默认值（Date 如 2026-01-01 00:00:00）"
        />
        <Checkbox
          :checked="field.setEmpty === true"
          @update:checked="(v: boolean) => (field.setEmpty = v)"
        >
          空串
        </Checkbox>
        <IconifyIcon
          class="editor-row__remove size-4"
          icon="lucide:trash-2"
          @click="config.fields.splice(Number(index), 1)"
        />
      </div>
      <Button class="self-start" size="small" type="dashed" @click="addField">
        <template #icon><IconifyIcon icon="lucide:plus" /></template>
        新增字段
      </Button>
      <div class="form-item__tip">
        勾选「空串」后忽略默认值，字段固定赋空字符串
      </div>
    </div>
  </div>
</template>
