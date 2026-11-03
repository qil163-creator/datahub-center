<script lang="ts" setup>
import type { TableColumnsType } from 'antdv-next';

import type { SafetyLevelApi } from '#/api/governance/level';
import type { SafetyPermissionApi } from '#/api/governance/permission';

import { computed, onBeforeUnmount, ref } from 'vue';

import { CommonStatusEnum } from '@vben/constants';
import { IconifyIcon } from '@vben/icons';

import { Button, Empty, Select, Spin, Table, Tag } from 'antdv-next';

import { getPermissionColumnList } from '#/api/governance/permission';

import {
  FIELD_PERMISSION_OPTIONS,
  fieldPermissionColor,
  fieldPermissionLabel,
} from '../data';

defineOptions({ name: 'SafetyPermissionFieldTable' });

const props = defineProps<{
  configReady?: boolean;
  currentRoleId?: number | string;
  currentTable?: SafetyPermissionApi.TableNode & {
    databaseId: number | string;
    databaseName?: string;
  };
  maskingRules: { id: number; methodCode?: string; ruleName?: string }[];
  securityLevels: SafetyLevelApi.Level[];
}>();

const emit = defineEmits<{
  (e: 'batchConfig' | 'change'): void;
  (e: 'fieldEdit', field: SafetyPermissionApi.FieldPermission): void;
  (e: 'fieldSelect', fields: SafetyPermissionApi.FieldPermission[]): void;
}>();

export interface FieldRow extends SafetyPermissionApi.FieldPermission {
  pkFlag?: boolean;
  metaSafetyLevelId?: number;
}

const editable = computed(() => !!props.currentRoleId && !!props.configReady);

const loading = ref(false);
let requestSeq = 0;
onBeforeUnmount(() => {
  ++requestSeq;
});
const fieldRows = ref<FieldRow[]>([]);
const selectedRowKeys = ref<string[]>([]);
const selectedFields = ref<FieldRow[]>([]);

const enabledLevels = computed(() =>
  props.securityLevels.filter(
    (item) => item.status === CommonStatusEnum.ENABLE,
  ),
);

const columns: TableColumnsType<FieldRow> = [
  { title: '字段名', dataIndex: 'fieldName', key: 'fieldName', width: 160 },
  { title: '字段类型', dataIndex: 'fieldType', key: 'fieldType', width: 120 },
  {
    title: '字段说明',
    dataIndex: 'fieldComment',
    key: 'fieldComment',
    width: 140,
    ellipsis: true,
  },
  { title: '安全等级', key: 'securityLevelId', width: 150 },
  { title: '权限配置', key: 'permissionType', width: 150 },
  { title: '脱敏规则', key: 'maskingRuleId', width: 180 },
  { title: '状态', key: 'status', width: 100 },
  { title: '操作', key: 'action', width: 180, fixed: 'right' },
];

async function reloadColumns(
  table?: SafetyPermissionApi.TableNode & {
    databaseId: number | string;
    databaseName?: string;
  },
) {
  const seq = ++requestSeq;
  const target = table ?? props.currentTable;
  selectedRowKeys.value = [];
  selectedFields.value = [];
  fieldRows.value = [];
  if (
    !target?.databaseId ||
    !target.schemaName ||
    !target.tableName ||
    !target.tableReference
  ) {
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const columnsResp = await getPermissionColumnList(
      target.databaseId,
      target,
    );
    if (seq !== requestSeq) return;
    fieldRows.value = (columnsResp ?? []).map((item) => ({
      fieldName: item.fieldName,
      fieldType: item.fieldType,
      fieldComment: item.fieldComment,
      pkFlag: item.pkFlag,
      permissionType: 1,
      maskingRuleId: undefined,
      securityLevelId: undefined,
      metaSafetyLevelId: item.safetyLevelId,
    }));
  } finally {
    if (seq === requestSeq) loading.value = false;
  }
}

function handleSelectionChange(keys: (number | string)[], rows: FieldRow[]) {
  selectedRowKeys.value = keys.map(String);
  selectedFields.value = rows;
  emit('fieldSelect', rows);
}

function handlePermissionTypeChange(row: FieldRow) {
  if (row.permissionType !== 2) {
    row.maskingRuleId = undefined;
  }
  emit('change');
}

function resetField(row: FieldRow) {
  row.permissionType = 1;
  row.maskingRuleId = undefined;
  row.securityLevelId = undefined;
  emit('change');
}

function levelName(id?: number) {
  if (!id) {
    return '';
  }
  return props.securityLevels.find((item) => item.id === id)?.safetyName ?? '';
}

function updateFieldConfig(
  field: SafetyPermissionApi.FieldPermission,
  config: Partial<FieldRow>,
) {
  const target = fieldRows.value.find(
    (item) => item.fieldName === field.fieldName,
  );
  if (!target) {
    return;
  }
  Object.assign(target, config);
  if (target.permissionType !== 2) {
    target.maskingRuleId = undefined;
  }
}

function batchUpdateConfig(
  fields: SafetyPermissionApi.FieldPermission[],
  config: Partial<FieldRow>,
) {
  const names = new Set(fields.map((item) => item.fieldName));
  fieldRows.value.forEach((row) => {
    if (!names.has(row.fieldName)) {
      return;
    }
    Object.assign(row, config);
    if (row.permissionType !== 2) {
      row.maskingRuleId = undefined;
    }
  });
}

function getCurrentRoleFieldPermissionConfig() {
  return fieldRows.value
    .filter(
      (item) =>
        item.permissionType !== 1 || item.maskingRuleId || item.securityLevelId,
    )
    .map((item) => ({
      fieldName: item.fieldName,
      fieldType: item.fieldType,
      fieldComment: item.fieldComment,
      permissionType: item.permissionType,
      maskingRuleId: item.maskingRuleId ?? null,
      securityLevelId: item.securityLevelId,
    }));
}

function applyFieldPermissions(
  config?: SafetyPermissionApi.RolePermissionConfig,
) {
  const map = new Map(
    (config?.fieldPermissions ?? []).map((item) => [item.fieldName, item]),
  );
  fieldRows.value.forEach((row) => {
    const saved = map.get(row.fieldName);
    if (saved) {
      row.permissionType = saved.permissionType;
      row.maskingRuleId = saved.maskingRuleId ?? undefined;
      row.securityLevelId = saved.securityLevelId;
    } else {
      row.permissionType = 1;
      row.maskingRuleId = undefined;
      row.securityLevelId = undefined;
    }
  });
}

function resetToDefault() {
  fieldRows.value.forEach((row) => {
    row.permissionType = 1;
    row.maskingRuleId = undefined;
    row.securityLevelId = undefined;
  });
}

defineExpose({
  reloadColumns,
  updateFieldConfig,
  batchUpdateConfig,
  getCurrentRoleFieldPermissionConfig,
  loadRoleFieldPermissionConfig: applyFieldPermissions,
  resetToDefault,
});
</script>

<template>
  <div class="flex h-full min-h-0 flex-col">
    <div class="mb-3 flex shrink-0 items-center justify-between">
      <div class="text-sm font-medium">
        {{
          currentTable
            ? `${currentTable.tableName} - 字段权限配置`
            : '请选择数据表'
        }}
        <span
          v-if="currentTable && !editable"
          class="ml-2 text-muted-foreground"
        >
          （请先选择角色）
        </span>
      </div>
      <div class="flex gap-2">
        <Button
          :disabled="!editable || selectedFields.length === 0"
          type="primary"
          @click="emit('batchConfig')"
        >
          <template #icon><IconifyIcon icon="lucide:settings" /></template>
          批量配置 ({{ selectedFields.length }})
        </Button>
      </div>
    </div>
    <div v-if="!currentTable" class="flex flex-1 items-center justify-center">
      <Empty description="请从左侧选择数据表" />
    </div>
    <div v-else class="min-h-0 flex-1">
      <Spin :spinning="loading" class="h-full">
        <Table
          :columns="columns"
          :data-source="fieldRows"
          :pagination="false"
          :row-key="(row: FieldRow) => row.fieldName"
          :row-selection="{
            selectedRowKeys,
            onChange: handleSelectionChange,
          }"
          :scroll="{ x: 1100, y: 'calc(100vh - 320px)' }"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'fieldName'">
              <div class="flex items-center gap-1.5">
                <IconifyIcon
                  class="size-4 text-slate-400"
                  icon="lucide:file-text"
                />
                <span>{{ record.fieldName }}</span>
                <Tag v-if="record.pkFlag" color="warning">主键</Tag>
                <Tag v-if="levelName(record.metaSafetyLevelId)" size="small">
                  {{ levelName(record.metaSafetyLevelId) }}
                </Tag>
              </div>
            </template>
            <template v-else-if="column.key === 'fieldType'">
              <Tag>{{ record.fieldType || '-' }}</Tag>
            </template>
            <template v-else-if="column.key === 'securityLevelId'">
              <Select
                v-model:value="record.securityLevelId"
                allow-clear
                class="w-full"
                :disabled="!editable"
                placeholder="覆盖安全等级"
                size="small"
                :options="
                  enabledLevels.map((item) => ({
                    label: item.safetyName,
                    value: item.id,
                  }))
                "
                @change="emit('change')"
              />
            </template>
            <template v-else-if="column.key === 'permissionType'">
              <Select
                v-model:value="record.permissionType"
                class="w-full"
                size="small"
                :disabled="!editable"
                :options="FIELD_PERMISSION_OPTIONS"
                @change="handlePermissionTypeChange(record)"
              />
            </template>
            <template v-else-if="column.key === 'maskingRuleId'">
              <Select
                v-model:value="record.maskingRuleId"
                allow-clear
                class="w-full"
                :disabled="!editable || record.permissionType !== 2"
                placeholder="选择脱敏规则"
                size="small"
                :options="
                  maskingRules.map((item) => ({
                    label: item.ruleName,
                    value: item.id,
                  }))
                "
                @change="emit('change')"
              />
            </template>
            <template v-else-if="column.key === 'status'">
              <Tag :color="fieldPermissionColor(record.permissionType)">
                {{ fieldPermissionLabel(record.permissionType) }}
              </Tag>
            </template>
            <template v-else-if="column.key === 'action'">
              <Button
                :disabled="!editable"
                size="small"
                type="link"
                @click="emit('fieldEdit', record)"
              >
                <template #icon><IconifyIcon icon="lucide:edit" /></template>
                编辑
              </Button>
              <Button
                :disabled="!editable"
                size="small"
                type="link"
                @click="resetField(record)"
              >
                <template #icon>
                  <IconifyIcon icon="lucide:rotate-ccw" />
                </template>
                重置
              </Button>
            </template>
          </template>
        </Table>
      </Spin>
    </div>
  </div>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
