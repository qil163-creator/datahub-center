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

defineOptions({ name: 'EtlNodeRowColumn' });

const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();

const MODE_OPTIONS = [
  { label: '列转行（宽转长）', value: 'COL2ROW' },
  { label: '行转列（长转宽）', value: 'ROW2COL' },
];

const fieldSelectOptions = computed(() =>
  (props.inputFields ?? []).map((field) => ({
    label: `${field.name}（${field.dataType ?? '-'}）`,
    value: field.name!,
  })),
);

onMounted(() => {
  const defaults: Record<string, any> = {
    mode: 'COL2ROW',
    keyField: 'key',
    valueField: 'value',
  };
  Object.entries(defaults).forEach(([key, value]) => {
    if (props.config[key] === null || props.config[key] === undefined) {
      props.config[key] = value;
    }
  });
  if (!Array.isArray(props.config.unpivotFields)) {
    props.config.unpivotFields = [];
  }
  if (!Array.isArray(props.config.groupFields)) {
    props.config.groupFields = [];
  }
  if (!Array.isArray(props.config.mappings)) {
    props.config.mappings = [];
  }
});
</script>

<template>
  <div class="etl-editor">
    <div class="form-item">
      <label class="form-item__label form-item__label--required">
        转换类型
      </label>
      <RadioGroup
        v-model:value="config.mode"
        :options="MODE_OPTIONS"
        option-type="button"
      />
    </div>

    <!-- 列转行 -->
    <template v-if="config.mode === 'COL2ROW'">
      <div class="form-item">
        <label class="form-item__label form-item__label--required">
          需转换的列（每列展开为一行）
        </label>
        <Select
          v-model:value="config.unpivotFields"
          :options="fieldSelectOptions"
          mode="tags"
          placeholder="选择或输入上游字段名"
        />
      </div>
      <div class="flex gap-4">
        <div class="form-item flex-1">
          <label class="form-item__label">键字段名（存放原列名）</label>
          <Input v-model:value="config.keyField" placeholder="key" />
        </div>
        <div class="form-item flex-1">
          <label class="form-item__label">值字段名（存放原列值）</label>
          <Input v-model:value="config.valueField" placeholder="value" />
        </div>
      </div>
      <div class="form-item">
        <Checkbox
          :checked="config.skipNull === true"
          @update:checked="(v: boolean) => (config.skipNull = v)"
        >
          跳过空值（原列值为空时不生成行）
        </Checkbox>
      </div>
      <div class="form-item__tip">
        示例：[id, 一月, 二月] 选择「一月、二月」转换后输出 [id, key, value]
        两行
      </div>
    </template>

    <!-- 行转列 -->
    <template v-else>
      <div class="form-item">
        <label class="form-item__label">分组字段（同组多行合并为一行）</label>
        <Select
          v-model:value="config.groupFields"
          :options="fieldSelectOptions"
          mode="tags"
          placeholder="选择或输入上游字段名，可为空"
        />
      </div>
      <div class="flex gap-4">
        <div class="form-item flex-1">
          <label class="form-item__label form-item__label--required">
            键字段（其值映射为新列）
          </label>
          <AutoComplete
            v-model:value="config.keyField"
            :options="fieldSelectOptions"
            placeholder="选择或输入字段名"
          />
        </div>
        <div class="form-item flex-1">
          <label class="form-item__label form-item__label--required">
            值字段（填充到新列）
          </label>
          <AutoComplete
            v-model:value="config.valueField"
            :options="fieldSelectOptions"
            placeholder="选择或输入字段名"
          />
        </div>
      </div>
      <div class="form-item">
        <label class="form-item__label form-item__label--required">
          键值映射（键值 → 生成的列名）
        </label>
        <div
          v-for="(mapping, index) in config.mappings ?? []"
          :key="index"
          class="editor-row"
        >
          <Input
            v-model:value="mapping.keyValue"
            class="flex-1"
            placeholder="键字段的值，如 一月"
          />
          <IconifyIcon class="size-4" icon="lucide:arrow-right" />
          <Input
            v-model:value="mapping.fieldName"
            class="flex-1"
            placeholder="生成的列名，如 jan"
          />
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
          @click="config.mappings.push({ keyValue: '', fieldName: '' })"
        >
          <template #icon><IconifyIcon icon="lucide:plus" /></template>
          添加映射
        </Button>
      </div>
      <div class="form-item__tip">
        示例：[id, key, value] 按 id 分组、键值「一月/二月」映射后输出 [id,
        一月, 二月] 一行
      </div>
    </template>
  </div>
</template>
