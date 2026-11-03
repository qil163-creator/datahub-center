<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, onMounted } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { AutoComplete, Button, Input, RadioGroup } from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeValueMap' });

const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();

const UNMATCHED_OPTIONS = [
  { label: '保留原值', value: 'KEEP' },
  { label: '设为默认值', value: 'DEFAULT' },
  { label: '置空', value: 'SET_NULL' },
];

const fieldOptions = computed(() =>
  (props.inputFields ?? []).map((field) => ({
    value: field.name!,
    label: `${field.name}（${field.dataType ?? '-'}）`,
  })),
);

onMounted(() => {
  if (props.config.unmatched === null || props.config.unmatched === undefined) {
    props.config.unmatched = 'KEEP';
  }
  if (!Array.isArray(props.config.mappings)) {
    props.config.mappings = [];
  }
  if (props.config.mappings.length === 0) {
    props.config.mappings.push({ from: '', to: '' });
  }
});
</script>

<template>
  <div class="etl-editor">
    <div class="flex gap-4">
      <div class="form-item flex-1">
        <label class="form-item__label form-item__label--required">
          来源字段
        </label>
        <AutoComplete
          v-model:value="config.sourceField"
          :options="fieldOptions"
          placeholder="选择或输入字段名"
        />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label">目标字段（空则覆盖来源字段）</label>
        <Input v-model:value="config.targetField" placeholder="可为空" />
      </div>
    </div>
    <div class="form-item">
      <label class="form-item__label form-item__label--required">
        值映射（源值 → 目标值）
      </label>
      <div
        v-for="(mapping, index) in config.mappings ?? []"
        :key="index"
        class="editor-row"
      >
        <Input v-model:value="mapping.from" class="flex-1" placeholder="源值" />
        <IconifyIcon class="size-4" icon="lucide:arrow-right" />
        <Input v-model:value="mapping.to" class="flex-1" placeholder="目标值" />
        <IconifyIcon
          class="editor-row__remove size-4"
          icon="lucide:trash-2"
          @click="config.mappings.splice(Number(index), 1)"
        />
      </div>
      <Button
        class="self-start"
        size="small"
        type="dashed"
        @click="config.mappings.push({ from: '', to: '' })"
      >
        <template #icon><IconifyIcon icon="lucide:plus" /></template>
        添加映射
      </Button>
    </div>
    <div class="form-item">
      <label class="form-item__label">未命中处理</label>
      <RadioGroup
        v-model:value="config.unmatched"
        :options="UNMATCHED_OPTIONS"
        option-type="button"
      />
      <Input
        v-if="config.unmatched === 'DEFAULT'"
        v-model:value="config.defaultValue"
        placeholder="默认值"
      />
    </div>
  </div>
</template>
