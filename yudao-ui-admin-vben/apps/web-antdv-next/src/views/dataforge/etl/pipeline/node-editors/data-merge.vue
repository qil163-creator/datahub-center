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
  RadioGroup,
  Select,
} from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeDataMerge' });

const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();

const STRATEGY_OPTIONS = [
  { label: '纵向联合（UNION）', value: 'UNION' },
  { label: '两源关联（JOIN）', value: 'JOIN' },
];

const JOIN_TYPE_OPTIONS = [
  { label: '内关联（INNER）', value: 'INNER' },
  { label: '左关联（LEFT）', value: 'LEFT' },
  { label: '右关联（RIGHT）', value: 'RIGHT' },
];

function fieldOptions() {
  return (props.inputFields ?? []).map((field) => ({
    value: field.name!,
    label: `${field.name}（${field.dataType ?? '-'}）`,
  }));
}

onMounted(() => {
  if (!props.config.mergeStrategy) {
    props.config.mergeStrategy = 'UNION';
  }
  if (!props.config.joinType) {
    props.config.joinType = 'INNER';
  }
  if (!Array.isArray(props.config.joinConditions)) {
    props.config.joinConditions = [{ leftField: '', rightField: '' }];
  }
});
</script>

<template>
  <div class="etl-editor">
    <div class="form-item">
      <label class="form-item__label">合并策略</label>
      <RadioGroup
        v-model:value="config.mergeStrategy"
        :options="STRATEGY_OPTIONS"
        option-type="button"
      />
      <div class="form-item__tip">
        UNION：多上游按字段并集纵向合并；JOIN：仅支持两个上游按条件关联
      </div>
    </div>

    <template v-if="config.mergeStrategy === 'JOIN'">
      <div class="form-item">
        <label class="form-item__label">关联方式</label>
        <Select v-model:value="config.joinType" :options="JOIN_TYPE_OPTIONS" />
        <div class="form-item__tip">左=先连入的上游，右=后连入的上游</div>
      </div>
      <div class="form-item">
        <label class="form-item__label form-item__label--required">
          关联条件
        </label>
        <div
          v-for="(condition, index) in config.joinConditions ?? []"
          :key="index"
          class="editor-row"
        >
          <AutoComplete
            v-model:value="condition.leftField"
            :options="fieldOptions()"
            class="flex-1"
            placeholder="左表字段"
          />
          <span>=</span>
          <AutoComplete
            v-model:value="condition.rightField"
            :options="fieldOptions()"
            class="flex-1"
            placeholder="右表字段"
          />
          <IconifyIcon
            class="editor-row__remove size-4"
            icon="lucide:trash-2"
            @click="config.joinConditions.splice(Number(index), 1)"
          />
        </div>
        <Button
          class="self-start"
          size="small"
          type="dashed"
          @click="config.joinConditions.push({ leftField: '', rightField: '' })"
        >
          <template #icon><IconifyIcon icon="lucide:plus" /></template>
          添加条件
        </Button>
      </div>
    </template>

    <template v-else>
      <div class="form-item">
        <Checkbox
          :checked="config.addSourceTag === true"
          @update:checked="(v: boolean) => (config.addSourceTag = v)"
        >
          追加来源标记字段
        </Checkbox>
        <Input
          v-if="config.addSourceTag"
          v-model:value="config.sourceTagField"
          class="w-52"
          placeholder="标记字段名，默认 _source"
        />
      </div>
    </template>

    <div class="form-item">
      <Checkbox
        :checked="config.removeDuplicates === true"
        @update:checked="(v: boolean) => (config.removeDuplicates = v)"
      >
        合并后全行去重
      </Checkbox>
    </div>
  </div>
</template>
