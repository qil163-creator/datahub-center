<script lang="ts" setup>
import type { DataforgeFillTableApi } from '#/api/dataforge/filltable';

import { computed, reactive, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';

import {
  Button,
  Input,
  InputNumber,
  message,
  Modal,
  Popconfirm,
  RadioGroup,
  Select,
  Switch,
  Table,
  Tag,
  TextArea,
} from 'antdv-next';

import {
  createFillTableRule,
  deleteFillTableRule,
  getFillTableRuleList,
  updateFillTableRule,
} from '#/api/dataforge/filltable';
import { validateExpression } from '#/views/dataforge/fill/workbench/fill-expression';

import { RULE_SCOPE_OPTIONS, RULE_SEVERITY_OPTIONS, ruleExamples } from '../data';

defineOptions({ name: 'DataforgeFillTableRulesPanel' });

const props = defineProps<{
  columns: DataforgeFillTableApi.FillTableColumn[];
  editable: boolean;
  tableId?: number;
}>();

const rules = ref<DataforgeFillTableApi.FillTableRule[]>([]);
const loading = ref(false);
let loadRequest = 0;

const fieldCodes = computed(() =>
  props.columns.map((column) => column.fieldCode ?? column.name ?? ''),
);
const fieldOptions = computed(() =>
  props.columns.map((column) => ({
    label: `${column.cnName ?? column.name}（${column.fieldCode ?? column.name}）`,
    value: column.fieldCode ?? column.name ?? '',
  })),
);

const tableColumns = [
  { title: '规则名称', dataIndex: 'name', key: 'name', width: 140 },
  { title: '范围', key: 'scope', width: 80 },
  { title: '表达式', dataIndex: 'expression', key: 'expression', ellipsis: true },
  { title: '提示语', dataIndex: 'message', key: 'message', width: 180, ellipsis: true },
  { title: '级别', key: 'severity', width: 70 },
  { title: '启用', key: 'enabled', width: 60 },
  { title: '操作', key: 'actions', width: 140 },
];

async function loadRules() {
  const request = ++loadRequest;
  if (!props.tableId) {
    rules.value = [];
    return;
  }
  loading.value = true;
  try {
    const list = await getFillTableRuleList(props.tableId);
    if (request === loadRequest) rules.value = list;
  } finally {
    if (request === loadRequest) loading.value = false;
  }
}

watch(() => props.tableId, loadRules, { immediate: true });

// ---------------------------------------------------------------- 编辑
const editOpen = ref(false);
const saving = ref(false);
const model = reactive<DataforgeFillTableApi.FillTableRule>({});

function resetModel(rule?: DataforgeFillTableApi.FillTableRule) {
  Object.assign(model, {
    enabled: 1,
    expression: '',
    fieldCode: '',
    id: undefined,
    message: '',
    name: '',
    scope: 'ROW',
    severity: 1,
    sort: 0,
    ...rule,
  });
}

const expressionError = computed(() =>
  model.expression?.trim()
    ? validateExpression(model.expression, model.scope ?? 'ROW', fieldCodes.value)
    : undefined,
);

function openEdit(rule?: DataforgeFillTableApi.FillTableRule) {
  resetModel(rule);
  editOpen.value = true;
}

async function handleSave() {
  if (saving.value) return;
  if (!model.name?.trim() || !model.expression?.trim() || !model.message?.trim()) {
    message.warning('请填写规则名称、表达式和提示语');
    return;
  }
  if (expressionError.value) {
    message.warning(`表达式无效：${expressionError.value}`);
    return;
  }
  saving.value = true;
  try {
    const data = { ...model, tableId: props.tableId };
    await (model.id ? updateFillTableRule(data) : createFillTableRule(data));
    message.success(model.id ? '规则已更新' : '规则已添加');
    editOpen.value = false;
    await loadRules();
  } finally {
    saving.value = false;
  }
}

async function handleDelete(rule: DataforgeFillTableApi.FillTableRule) {
  await deleteFillTableRule(rule.id!);
  message.success('规则已删除');
  await loadRules();
}

defineExpose({ reload: loadRules });
</script>

<template>
  <div class="mt-4">
    <div class="mb-2 flex items-center justify-between">
      <div>
        <span class="text-sm font-medium">校验规则</span>
        <span class="text-muted-foreground ml-2 text-xs">
          行级规则逐行检查，表级规则检查汇总值，跨期规则与上期已审批数据比较；结果为假即提示
        </span>
      </div>
      <Button v-if="editable" size="small" @click="openEdit()">
        <template #icon><IconifyIcon icon="lucide:plus" /></template>
        添加规则
      </Button>
    </div>
    <Table
      :columns="tableColumns"
      :data-source="rules"
      :loading="loading"
      :pagination="false"
      row-key="id"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'scope'">
          {{ RULE_SCOPE_OPTIONS.find((item) => item.value === record.scope)?.label ?? record.scope }}
        </template>
        <template v-else-if="column.key === 'severity'">
          <Tag :color="record.severity === 2 ? 'warning' : 'error'">
            {{ record.severity === 2 ? '警告' : '错误' }}
          </Tag>
        </template>
        <template v-else-if="column.key === 'enabled'">
          {{ record.enabled === 0 ? '否' : '是' }}
        </template>
        <template v-else-if="column.key === 'actions'">
          <div v-if="editable" class="flex gap-1">
            <Button size="small" type="link" @click="openEdit(record as any)">
              编辑
            </Button>
            <Popconfirm title="确认删除该规则吗？" @confirm="handleDelete(record as any)">
              <Button danger size="small" type="link">删除</Button>
            </Popconfirm>
          </div>
          <span v-else>-</span>
        </template>
      </template>
    </Table>

    <Modal
      v-model:open="editOpen"
      :confirm-loading="saving"
      :title="model.id ? '编辑校验规则' : '添加校验规则'"
      width="720px"
      @ok="handleSave"
    >
      <div class="flex flex-col gap-3 py-2">
        <div class="rule-row">
          <label class="rule-label">规则名称</label>
          <Input v-model:value="model.name" :maxlength="100" placeholder="如：分项之和等于合计" />
        </div>
        <div class="rule-row">
          <label class="rule-label">范围</label>
          <RadioGroup v-model:value="model.scope" :options="RULE_SCOPE_OPTIONS" />
        </div>
        <div class="rule-row">
          <label class="rule-label">表达式</label>
          <div class="min-w-0 flex-1">
            <TextArea
              v-model:value="model.expression"
              :maxlength="1000"
              :rows="3"
              placeholder="结果为假即违反"
            />
            <p v-if="expressionError" class="rule-error">{{ expressionError }}</p>
            <p class="rule-hint">
              示例：{{ ruleExamples(model.scope ?? 'ROW') }}。可用字段：{{ fieldCodes.join('、') || '请先添加字段' }}
            </p>
          </div>
        </div>
        <div class="rule-row">
          <label class="rule-label">提示语</label>
          <Input v-model:value="model.message" :maxlength="200" placeholder="违反时显示给填报人" />
        </div>
        <div class="rule-row">
          <label class="rule-label">级别</label>
          <RadioGroup v-model:value="model.severity" :options="RULE_SEVERITY_OPTIONS" />
        </div>
        <div v-if="model.scope === 'ROW'" class="rule-row">
          <label class="rule-label">定位字段</label>
          <Select
            v-model:value="model.fieldCode"
            :options="fieldOptions"
            allow-clear
            class="w-full"
            placeholder="违反时高亮的单元格（可选）"
          />
        </div>
        <div class="rule-row">
          <label class="rule-label">排序 / 启用</label>
          <InputNumber v-model:value="model.sort" :min="0" class="w-32" />
          <Switch
            :checked="model.enabled !== 0"
            class="ml-4"
            @change="(value: any) => (model.enabled = value ? 1 : 0)"
          />
        </div>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
.rule-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.rule-label {
  flex-shrink: 0;
  width: 90px;
  padding-top: 5px;
  font-size: 14px;
  font-weight: 500;
  text-align: right;
}

.rule-hint {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 18px;
  color: hsl(var(--muted-foreground));
}

.rule-error {
  margin: 4px 0 0;
  font-size: 12px;
  color: hsl(var(--destructive));
}
</style>
