<script setup lang="ts">
import type {
  MasterDataCondition,
  MasterDataFilterOperator,
  MasterDataFilterRow,
} from './data-filter-model';

import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import { computed, ref } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Button, Checkbox, Input, Select, Tooltip } from 'antdv-next';

import { fieldTypePlaceholder } from '../../masterdata/data';
import {
  collectMasterDataConditions,
  defaultMasterDataFilterOperator,
  masterDataFilterNeedsValue,
  masterDataFilterOperatorOptions,
  normalizeMasterDataConditions,
} from './data-filter-model';

const props = defineProps<{
  allowedOperators?: MasterDataFilterOperator[];
  appliedConditions: MasterDataCondition[];
  columns: GovernanceMasterDataApi.Column[];
  loading?: boolean;
}>();
const emit = defineEmits<{ apply: [conditions: MasterDataCondition[]] }>();
let nextKey = 0;
const rows = ref<MasterDataFilterRow[]>(
  normalizeMasterDataConditions(
    props.appliedConditions,
    props.columns,
    props.allowedOperators,
  ).map((condition) => ({
    ...condition,
    enabled: true,
    key: nextKey++,
    operator: condition.operator,
    value: condition.value ?? '',
  })),
);
const collapsed = ref(false);
const attempted = ref(false);
const columnMap = computed(
  () => new Map(props.columns.map((column) => [column.name, column])),
);
const fieldOptions = computed(() =>
  props.columns
    .filter((column) => column.name)
    .map((column) => ({
      label: column.commentStr
        ? `${column.commentStr} · ${column.name}`
        : column.name!,
      value: column.name!,
    })),
);
const collected = computed(() =>
  collectMasterDataConditions(
    rows.value,
    props.columns,
    props.allowedOperators,
  ),
);
const invalidKeys = computed(() =>
  attempted.value && !collected.value.valid
    ? new Set(collected.value.invalidKeys)
    : new Set<number>(),
);
const pending = computed(
  () =>
    !collected.value.valid ||
    JSON.stringify(collected.value.conditions) !==
      JSON.stringify(
        normalizeMasterDataConditions(
          props.appliedConditions,
          props.columns,
          props.allowedOperators,
        ),
      ),
);

function fieldLabel(name: string) {
  return (
    fieldOptions.value.find((option) => option.value === name)?.label ?? name
  );
}
function addCondition() {
  rows.value.push({
    enabled: true,
    key: nextKey++,
    name: '',
    operator: 'IS_NULL',
    value: '',
  });
  collapsed.value = false;
}
function removeCondition(key: number) {
  rows.value = rows.value.filter((row) => row.key !== key);
}
function selectField(row: MasterDataFilterRow, value: unknown) {
  const hadField = columnMap.value.has(row.name);
  row.name = typeof value === 'string' ? value : '';
  const fieldType = columnMap.value.get(row.name)?.fieldType;
  if (
    !hadField ||
    !masterDataFilterOperatorOptions(fieldType, props.allowedOperators).some(
      (option) => option.value === row.operator,
    )
  ) {
    row.operator = defaultMasterDataFilterOperator(
      fieldType,
      props.allowedOperators,
    );
  }
  if (!masterDataFilterNeedsValue(row.operator)) row.value = '';
}
function selectOperator(row: MasterDataFilterRow, value: unknown) {
  const option = operatorOptions(row).find((item) => item.value === value);
  if (!option) return;
  row.operator = option.value;
  if (!masterDataFilterNeedsValue(row.operator)) row.value = '';
}
function operatorValid(row: MasterDataFilterRow) {
  return operatorOptions(row).some((option) => option.value === row.operator);
}
function operatorOptions(row: MasterDataFilterRow) {
  return masterDataFilterOperatorOptions(
    columnMap.value.get(row.name)?.fieldType,
    props.allowedOperators,
  );
}
function apply() {
  if (props.loading) return;
  attempted.value = true;
  if (!collected.value.valid) {
    collapsed.value = false;
    return;
  }
  emit('apply', collected.value.conditions);
}
function reset() {
  if (props.loading) return;
  rows.value = [];
  attempted.value = false;
  emit('apply', []);
}
</script>

<template>
  <section class="master-data-filter" aria-label="数据筛选">
    <div class="master-data-filter__toolbar">
      <div class="flex min-w-0 flex-wrap items-center gap-2">
        <span class="font-medium">筛选</span>
        <span class="text-xs text-muted-foreground">所有启用条件同时满足</span>
        <span
          v-if="appliedConditions.length"
          class="rounded bg-muted px-2 py-0.5 text-xs text-muted-foreground"
        >
          已应用 {{ appliedConditions.length }} 项
        </span>
        <span v-if="pending" class="text-xs text-primary">条件待应用</span>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <Button
          size="small"
          :disabled="fieldOptions.length === 0"
          @click="addCondition"
        >
          + 添加条件
        </Button>
        <Button
          size="small"
          :disabled="loading || (!rows.length && !appliedConditions.length)"
          @click="reset"
        >
          重置
        </Button>
        <Button size="small" type="primary" :loading="loading" @click="apply">
          应用筛选
        </Button>
        <Button
          v-if="rows.length"
          size="small"
          type="text"
          :aria-expanded="!collapsed"
          @click="collapsed = !collapsed"
        >
          <template #icon>
<IconifyIcon
              :icon="collapsed ? 'lucide:chevron-down' : 'lucide:chevron-up'"
          />
</template>
          {{ collapsed ? `展开（${rows.length}）` : '收起' }}
        </Button>
      </div>
    </div>
    <p v-if="!rows.length" class="mt-2 text-xs text-muted-foreground">
      暂无筛选条件，点击「添加条件」选择字段。
    </p>
    <div v-else-if="!collapsed" class="master-data-filter__rows">
      <div
        v-for="(row, index) in rows"
        :key="row.key"
        class="master-data-filter__row"
      >
        <Checkbox
          v-model:checked="row.enabled"
          :aria-label="`启用条件 ${index + 1}`"
          class="master-data-filter__check"
        />
        <Select
          :value="row.name || undefined"
          :aria-label="`条件 ${index + 1} 字段`"
          :disabled="!row.enabled"
          :options="fieldOptions"
          :status="invalidKeys.has(row.key) && !row.name ? 'error' : undefined"
          class="master-data-filter__field"
          placeholder="选择字段（名称 / 注释）"
          option-filter-prop="label"
          show-search
          @update:value="(value) => selectField(row, value)"
        >
          <template #labelRender>
            <Tooltip :title="fieldLabel(row.name)">
              <span class="block truncate">{{ fieldLabel(row.name) }}</span>
            </Tooltip>
          </template>
          <template #optionRender="{ option }">
            <Tooltip :title="option.data.label" placement="right">
              <span class="block truncate">{{ option.data.label }}</span>
            </Tooltip>
          </template>
        </Select>
        <div class="master-data-filter__operator">
          <Select
            :value="row.name ? row.operator : undefined"
            :aria-label="`条件 ${index + 1} 操作符`"
            :disabled="
              !row.enabled || !row.name || !operatorOptions(row).length
            "
            :options="operatorOptions(row)"
            :status="
              invalidKeys.has(row.key) && !operatorValid(row)
                ? 'error'
                : undefined
            "
            class="w-full"
            placeholder="操作符"
            @update:value="(value) => selectOperator(row, value)"
          />
        </div>
        <span
          v-if="row.name && !masterDataFilterNeedsValue(row.operator)"
          class="master-data-filter__value text-xs text-muted-foreground"
          :class="{ 'opacity-50': !row.enabled }"
        >
          无需填写值
        </span>
        <Input
          v-else
          v-model:value="row.value"
          :aria-label="`条件 ${index + 1} 值`"
          :disabled="!row.enabled || !row.name"
          :placeholder="
            fieldTypePlaceholder(columnMap.get(row.name)?.fieldType)
          "
          :status="
            invalidKeys.has(row.key) && row.value.trim() === ''
              ? 'error'
              : undefined
          "
          class="master-data-filter__value"
          allow-clear
          @press-enter="apply"
        />
        <Button
          size="small"
          type="text"
          :aria-label="`删除条件 ${index + 1}`"
          class="master-data-filter__remove"
          @click="removeCondition(row.key)"
        >
          <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
          删除
        </Button>
        <span
          v-if="row.name && !operatorOptions(row).length"
          class="master-data-filter__error text-xs text-destructive"
          role="alert"
        >
          当前数据源暂未支持所选字段的筛选方式。
        </span>
        <span
          v-else-if="invalidKeys.has(row.key)"
          class="master-data-filter__error text-xs text-destructive"
          role="alert"
        >
          请选择有效字段和适用操作符，填写所需筛选值，或取消勾选该条件。
        </span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.master-data-filter {
  flex-shrink: 0;
  padding: 12px;
  margin-bottom: 12px;
  container-type: inline-size;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

.master-data-filter__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  align-items: center;
  justify-content: space-between;
}

.master-data-filter__rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 240px;
  padding-top: 12px;
  overflow-y: auto;
}

.master-data-filter__row {
  display: grid;
  grid-template-columns:
    24px minmax(160px, 280px) 140px minmax(160px, 1fr)
    48px;
  gap: 8px;
  align-items: center;
}

.master-data-filter__field,
.master-data-filter__operator,
.master-data-filter__value {
  min-width: 0;
}

.master-data-filter__error {
  grid-column: 2 / -1;
}

@container (max-width: 740px) {
  .master-data-filter__row {
    grid-template-columns: 24px minmax(0, 1fr) 140px 48px;
  }

  .master-data-filter__value {
    grid-row: 2;
    grid-column: 2 / -1;
  }

  .master-data-filter__remove {
    grid-row: 1;
    grid-column: 4;
  }
}

@container (max-width: 420px) {
  .master-data-filter__row {
    grid-template-columns: 24px minmax(0, 1fr) 48px;
  }

  .master-data-filter__operator {
    grid-row: 2;
    grid-column: 2 / -1;
  }

  .master-data-filter__value {
    grid-row: 3;
  }

  .master-data-filter__remove {
    grid-column: 3;
  }
}
</style>
