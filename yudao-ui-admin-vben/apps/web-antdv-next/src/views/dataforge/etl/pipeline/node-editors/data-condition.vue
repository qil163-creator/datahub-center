<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { onMounted } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { AutoComplete, Button, Input, RadioGroup, Select } from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeDataCondition' });

const props = defineProps<{
  config: Record<string, any>;
  /** 当前节点的数据下游（设计器传入，用于分支绑定目标节点） */
  downstreamNodes?: { targetId: string; targetName: string }[];
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();

const OPERATOR_OPTIONS = [
  { label: '等于', value: 'EQ' },
  { label: '不等于', value: 'NE' },
  { label: '大于', value: 'GT' },
  { label: '大于等于', value: 'GTE' },
  { label: '小于', value: 'LT' },
  { label: '小于等于', value: 'LTE' },
  { label: '包含', value: 'CONTAINS' },
  { label: '不包含', value: 'NOT_CONTAINS' },
  { label: '为空', value: 'IS_NULL' },
  { label: '不为空', value: 'NOT_NULL' },
];

const TYPE_OPTIONS = [
  { label: '条件分支', value: 'IF' },
  { label: '其余情况', value: 'ELSE' },
];

const UNMATCHED_OPTIONS = [
  { label: '丢弃', value: 'DROP' },
  { label: '报错', value: 'ERROR' },
];

function fieldOptions() {
  return (props.inputFields ?? []).map((field) => ({
    value: field.name!,
    label: `${field.name}（${field.dataType ?? '-'}）`,
  }));
}

function targetOptions() {
  return (props.downstreamNodes ?? []).map((item) => ({
    label: item.targetName,
    value: item.targetId,
  }));
}

function needValue(operator?: string) {
  return operator !== 'IS_NULL' && operator !== 'NOT_NULL';
}

function addBranch(elseBranch = false) {
  props.config.branches.push({
    id: `branch_${Date.now()}_${props.config.branches.length + 1}`,
    name: elseBranch ? '其余情况' : `分支${props.config.branches.length + 1}`,
    type: elseBranch ? 'ELSE' : 'IF',
    fieldName: '',
    operator: 'EQ',
    value: '',
    targetNodeNo: '',
  });
}

onMounted(() => {
  if (!props.config.unmatchedPolicy) {
    props.config.unmatchedPolicy = 'DROP';
  }
  if (!Array.isArray(props.config.branches)) {
    props.config.branches = [];
    addBranch();
  }
});
</script>

<template>
  <div class="etl-editor">
    <div class="form-item__tip mb-2">
      数据按分支顺序判断，命中后仅流向该分支绑定的下游节点；请先连好下游连线再绑定
    </div>
    <div
      v-for="(branch, index) in config.branches ?? []"
      :key="branch.id"
      class="condition-branch"
    >
      <div class="editor-row">
        <Input
          v-model:value="branch.name"
          class="w-32"
          placeholder="分支名称"
        />
        <RadioGroup
          v-model:value="branch.type"
          :options="TYPE_OPTIONS"
          option-type="button"
          size="small"
        />
        <IconifyIcon
          class="editor-row__remove size-4"
          icon="lucide:trash-2"
          @click="config.branches.splice(Number(index), 1)"
        />
      </div>
      <div v-if="branch.type !== 'ELSE'" class="editor-row">
        <AutoComplete
          v-model:value="branch.fieldName"
          :options="fieldOptions()"
          class="w-36"
          placeholder="字段名"
        />
        <Select
          v-model:value="branch.operator"
          :options="OPERATOR_OPTIONS"
          class="w-28"
        />
        <Input
          v-if="needValue(branch.operator)"
          v-model:value="branch.value"
          class="flex-1"
          placeholder="比较值（支持 ${params.xxx}）"
        />
      </div>
      <div class="editor-row">
        <span class="text-xs">流向下游</span>
        <Select
          v-model:value="branch.targetNodeNo"
          :options="targetOptions()"
          allow-clear
          class="flex-1"
          placeholder="选择该分支的数据去向节点"
        />
      </div>
    </div>
    <div class="mb-3 flex gap-2">
      <Button size="small" type="dashed" @click="addBranch(false)">
        <template #icon><IconifyIcon icon="lucide:git-branch" /></template>
        添加分支
      </Button>
      <Button size="small" type="dashed" @click="addBranch(true)">
        <template #icon><IconifyIcon icon="lucide:plus" /></template>
        添加"其余情况"
      </Button>
    </div>
    <div class="form-item">
      <label class="form-item__label">未命中任何分支时</label>
      <RadioGroup
        v-model:value="config.unmatchedPolicy"
        :options="UNMATCHED_OPTIONS"
        option-type="button"
      />
    </div>
  </div>
</template>

<style scoped>
.condition-branch {
  padding: 8px;
  margin-bottom: 8px;
  background: hsl(var(--muted) / 30%);
  border-radius: 6px;
}
</style>
