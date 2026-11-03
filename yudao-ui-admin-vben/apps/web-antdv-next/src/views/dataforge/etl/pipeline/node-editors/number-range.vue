<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, onMounted } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { AutoComplete, Button, Input } from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeNumberRange' });

const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();

const fieldOptions = computed(() =>
  (props.inputFields ?? []).map((field) => ({
    value: field.name!,
    label: `${field.name}（${field.dataType ?? '-'}）`,
  })),
);

onMounted(() => {
  if (!Array.isArray(props.config.ranges)) {
    props.config.ranges = [];
  }
  if (props.config.ranges.length === 0) {
    props.config.ranges.push({ min: '', max: '', value: '' });
  }
});
</script>

<template>
  <div class="etl-editor">
    <div class="flex gap-4">
      <div class="form-item flex-1">
        <label class="form-item__label form-item__label--required">
          来源字段（数值）
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
        范围规则（下限 ≤ 值 &lt; 上限，按顺序首个命中生效）
      </label>
      <div
        v-for="(range, index) in config.ranges ?? []"
        :key="index"
        class="editor-row"
      >
        <Input
          v-model:value="range.min"
          class="flex-1"
          placeholder="下限（含，空为不限）"
        />
        <span>≤ 值 &lt;</span>
        <Input
          v-model:value="range.max"
          class="flex-1"
          placeholder="上限（不含，空为不限）"
        />
        <IconifyIcon class="size-4" icon="lucide:arrow-right" />
        <Input
          v-model:value="range.value"
          class="flex-1"
          placeholder="目标值"
        />
        <IconifyIcon
          class="editor-row__remove size-4"
          icon="lucide:trash-2"
          @click="config.ranges.splice(Number(index), 1)"
        />
      </div>
      <Button
        class="self-start"
        size="small"
        type="dashed"
        @click="config.ranges.push({ min: '', max: '', value: '' })"
      >
        <template #icon><IconifyIcon icon="lucide:plus" /></template>
        添加范围
      </Button>
    </div>
    <div class="form-item">
      <label class="form-item__label">未命中/非数值时的默认值</label>
      <Input
        v-model:value="config.defaultValue"
        placeholder="可为空（置 null）"
      />
    </div>
  </div>
</template>
