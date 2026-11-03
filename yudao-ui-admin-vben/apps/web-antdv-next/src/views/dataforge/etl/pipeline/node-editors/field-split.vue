<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, onMounted } from 'vue';

import { IconifyIcon } from '@vben/icons';

import {
  AutoComplete,
  Button,
  Checkbox,
  Input,
  RadioGroup,
  Select,
} from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeFieldSplit' });

const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();

const SPLIT_TYPE_OPTIONS = [
  { label: '分隔符', value: 'DELIMITER' },
  { label: '正则表达式', value: 'REGEX' },
];

const TRIM_TYPE_OPTIONS = [
  { label: '不去除', value: 'NONE' },
  { label: '两端', value: 'BOTH' },
  { label: '左侧', value: 'LEFT' },
  { label: '右侧', value: 'RIGHT' },
];

const fieldOptions = computed(() =>
  (props.inputFields ?? []).map((field) => ({
    value: field.name!,
    label: `${field.name}（${field.dataType ?? '-'}）`,
  })),
);

onMounted(() => {
  if (props.config.splitType === null || props.config.splitType === undefined) {
    props.config.splitType = 'DELIMITER';
  }
  if (!Array.isArray(props.config.outputs)) {
    props.config.outputs = [];
  }
  if (props.config.outputs.length === 0) {
    props.config.outputs.push({ name: '', trimType: 'NONE' });
  }
});
</script>

<template>
  <div class="etl-editor">
    <div class="flex gap-4">
      <div class="form-item flex-1">
        <label class="form-item__label form-item__label--required">
          需拆分的字段
        </label>
        <AutoComplete
          v-model:value="config.sourceField"
          :options="fieldOptions"
          placeholder="选择或输入字段名"
        />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label form-item__label--required">
          拆分类型
        </label>
        <RadioGroup
          v-model:value="config.splitType"
          :options="SPLIT_TYPE_OPTIONS"
          option-type="button"
        />
      </div>
    </div>
    <div class="flex gap-4">
      <div v-if="config.splitType === 'REGEX'" class="form-item flex-1">
        <label class="form-item__label form-item__label--required">
          正则表达式
        </label>
        <Input v-model:value="config.pattern" placeholder="如 [,;]" />
      </div>
      <div v-else class="form-item flex-1">
        <label class="form-item__label form-item__label--required">
          分隔符
        </label>
        <Input v-model:value="config.delimiter" placeholder="如 ," />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label">包裹字符（去除每段两端）</label>
        <Input v-model:value="config.wrapper" placeholder="如 &quot;" />
      </div>
    </div>
    <div class="form-item">
      <Checkbox
        :checked="config.keepSource === true"
        @update:checked="(v: boolean) => (config.keepSource = v)"
      >
        保留原字段
      </Checkbox>
    </div>
    <div class="form-item">
      <label class="form-item__label form-item__label--required">
        拆分字段（按顺序对应拆分结果）
      </label>
      <div
        v-for="(output, index) in config.outputs ?? []"
        :key="index"
        class="editor-row"
      >
        <Input
          v-model:value="output.name"
          class="flex-1"
          placeholder="字段名称"
        />
        <Select
          v-model:value="output.trimType"
          :options="TRIM_TYPE_OPTIONS"
          class="w-28"
          placeholder="去除空格"
        />
        <IconifyIcon
          class="editor-row__remove size-4"
          icon="lucide:trash-2"
          @click="config.outputs.splice(Number(index), 1)"
        />
      </div>
      <Button
        class="self-start"
        size="small"
        type="dashed"
        @click="config.outputs.push({ name: '', trimType: 'NONE' })"
      >
        <template #icon><IconifyIcon icon="lucide:plus" /></template>
        新增拆分字段
      </Button>
    </div>
  </div>
</template>
