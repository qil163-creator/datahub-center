<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, onMounted } from 'vue';

import {
  AutoComplete,
  Checkbox,
  Input,
  InputNumber,
  Select,
  TextArea,
} from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeFieldDerive' });

const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();

const OPERATE_TYPE_OPTIONS = [
  { label: '拼接', value: 'CONCAT' },
  { label: '截取', value: 'SUBSTRING' },
  { label: '替换', value: 'REPLACE' },
  { label: '表达式', value: 'EXPRESSION' },
  { label: '哈希', value: 'HASH' },
  { label: '正则提取', value: 'REGEX_EXTRACT' },
  { label: '常量赋值', value: 'CONSTANT' },
];

const HASH_OPTIONS = ['MD5', 'SHA1', 'SHA256'].map((value) => ({
  label: value,
  value,
}));

/** 单来源字段的操作类型 */
const SINGLE_SOURCE_TYPES = new Set(['REGEX_EXTRACT', 'REPLACE', 'SUBSTRING']);
/** 多来源字段的操作类型 */
const MULTI_SOURCE_TYPES = new Set(['CONCAT', 'HASH']);

const fieldSelectOptions = computed(() =>
  (props.inputFields ?? []).map((field) => ({
    label: `${field.name}（${field.dataType ?? '-'}）`,
    value: field.name!,
  })),
);

/** 单来源类型时绑定 sourceFields[0] */
const singleSource = computed<string>({
  get: () => props.config.sourceFields?.[0] ?? '',
  set: (value) => {
    props.config.sourceFields = value ? [value] : [];
  },
});

onMounted(() => {
  if (props.config.operateType == null) {
    props.config.operateType = 'CONCAT';
  }
  if (!Array.isArray(props.config.sourceFields)) {
    props.config.sourceFields = [];
  }
  const defaults: Record<string, any> = {
    beginIndex: 1,
    length: 0,
    algorithm: 'MD5',
    groupIndex: 1,
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
    <div class="flex gap-4">
      <div class="form-item flex-1">
        <label class="form-item__label form-item__label--required">
          操作类型
        </label>
        <Select
          v-model:value="config.operateType"
          :options="OPERATE_TYPE_OPTIONS"
        />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label form-item__label--required">
          新增字段名称
        </label>
        <Input
          v-model:value="config.targetField"
          allow-clear
          placeholder="派生后的字段名（同名则覆盖）"
        />
      </div>
    </div>

    <div v-if="MULTI_SOURCE_TYPES.has(config.operateType)" class="form-item">
      <label class="form-item__label form-item__label--required">
        来源字段（按顺序参与）
      </label>
      <Select
        v-model:value="config.sourceFields"
        :options="fieldSelectOptions"
        mode="tags"
        placeholder="选择或输入上游字段名"
      />
    </div>
    <div v-if="SINGLE_SOURCE_TYPES.has(config.operateType)" class="form-item">
      <label class="form-item__label form-item__label--required">
        来源字段
      </label>
      <AutoComplete
        v-model:value="singleSource"
        :options="fieldSelectOptions"
        placeholder="选择或输入上游字段名"
      />
    </div>

    <!-- 拼接 -->
    <div v-if="config.operateType === 'CONCAT'" class="flex gap-4">
      <div class="form-item flex-1">
        <label class="form-item__label">前缀</label>
        <Input v-model:value="config.prefix" placeholder="可为空" />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label">连接符</label>
        <Input v-model:value="config.separator" placeholder="如 - 或 _" />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label">后缀</label>
        <Input v-model:value="config.suffix" placeholder="可为空" />
      </div>
    </div>

    <!-- 截取 -->
    <div v-if="config.operateType === 'SUBSTRING'" class="flex gap-4">
      <div class="form-item flex-1">
        <label class="form-item__label">起始位置（从 1 开始）</label>
        <InputNumber
          v-model:value="config.beginIndex"
          :min="1"
          class="w-full"
        />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label">截取长度（0 到末尾）</label>
        <InputNumber v-model:value="config.length" :min="0" class="w-full" />
      </div>
    </div>

    <!-- 替换 -->
    <template v-if="config.operateType === 'REPLACE'">
      <div class="flex gap-4">
        <div class="form-item flex-1">
          <label class="form-item__label form-item__label--required">
            查找内容
          </label>
          <Input v-model:value="config.search" placeholder="待替换的内容" />
        </div>
        <div class="form-item flex-1">
          <label class="form-item__label">替换为</label>
          <Input v-model:value="config.replacement" placeholder="可为空" />
        </div>
      </div>
      <div class="form-item">
        <Checkbox
          :checked="config.useRegex === true"
          @update:checked="(v: boolean) => (config.useRegex = v)"
        >
          按正则表达式匹配
        </Checkbox>
      </div>
    </template>

    <!-- 表达式 -->
    <div v-if="config.operateType === 'EXPRESSION'" class="form-item">
      <label class="form-item__label form-item__label--required">表达式</label>
      <TextArea
        v-model:value="config.expression"
        :rows="3"
        placeholder="如 ${price} * ${quantity} 或 ${province}-${city}"
      />
      <div class="form-item__tip">
        用 ${字段名} 引用上游字段；数值表达式自动计算，否则输出替换后的文本
      </div>
    </div>

    <!-- 哈希 -->
    <div v-if="config.operateType === 'HASH'" class="form-item">
      <label class="form-item__label">哈希算法</label>
      <Select v-model:value="config.algorithm" :options="HASH_OPTIONS" />
    </div>

    <!-- 正则提取 -->
    <div v-if="config.operateType === 'REGEX_EXTRACT'" class="flex gap-4">
      <div class="form-item flex-1">
        <label class="form-item__label form-item__label--required">
          正则表达式
        </label>
        <Input v-model:value="config.pattern" placeholder="如 (\d{6})" />
      </div>
      <div class="form-item w-44">
        <label class="form-item__label">分组序号（0 整体匹配）</label>
        <InputNumber
          v-model:value="config.groupIndex"
          :min="0"
          class="w-full"
        />
      </div>
    </div>

    <!-- 常量赋值 -->
    <div v-if="config.operateType === 'CONSTANT'" class="form-item">
      <label class="form-item__label">常量值</label>
      <Input
        v-model:value="config.constantValue"
        placeholder="固定值，可为空"
      />
    </div>
  </div>
</template>
