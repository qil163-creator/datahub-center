<script setup lang="ts">
import type { EditableLogicKind } from '../editor-state';

import type { GovernanceStandardCodeSetApi } from '#/api/governance/standardcodeset';
import type { IndicatorModelApi } from '#/api/modeling/indicator-model';
import type { ModelingModelApi } from '#/api/modeling/model';
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { computed, ref } from 'vue';

import { useAntdDesignTokens } from '@vben/hooks';

import {
  Alert,
  Button,
  Checkbox,
  ConfigProvider,
  Input,
  Modal,
  Select,
  Spin,
} from 'antdv-next';

import { getActiveIndicatorOptions } from '#/api/modeling/indicator-model';
import { getTableModelDesign, getTableModelPage } from '#/api/modeling/tablemodel';
import { useObjectRequest } from '#/hooks/use-object-request';

import emptyRecords from '../assets/empty-records.png';
import {
  codeTableField,
  indicatorField,
  logicTableField,
} from '../field-import-state';
import { QdIcon } from '../icons';

import '../modal.css';
import '../version.css';

export type FieldImportMode = 'codeTable' | 'indicator' | 'logic';

const props = defineProps<{
  codeTables: GovernanceStandardCodeSetApi.StandardCodeSet[];
  currentModelId?: number | string;
  dataDomainId?: number | string;
  kind: EditableLogicKind;
}>();

const emit = defineEmits<{ import: [fields: ModelingModelApi.ModelField[]] }>();

const { tokens } = useAntdDesignTokens();

const TITLES: Record<FieldImportMode, string> = {
  logic: '从逻辑表引入',
  indicator: '从派生指标引用',
  codeTable: '从代码表引入',
};

interface Row {
  key: string;
  name: string;
  code: string;
  extra: string;
  field: ModelingModelApi.ModelField;
}

const visible = ref(false);
const mode = ref<FieldImportMode>('logic');
const loading = ref(false);
const failed = ref(false);
const keyword = ref('');
const tables = ref<TableModelApi.Model[]>([]);
const tableId = ref<string>();
const rows = ref<Row[]>([]);
const checked = ref(new Set<string>());
const guard = useObjectRequest(() => `${mode.value}/${String(props.dataDomainId ?? '')}`);

const title = computed(() => TITLES[mode.value]);
const visibleRows = computed(() => {
  const text = keyword.value.trim().toLowerCase();
  return rows.value.filter((row) =>
    `${row.name} ${row.code}`.toLowerCase().includes(text),
  );
});
const allChecked = computed(
  () =>
    visibleRows.value.length > 0 &&
    visibleRows.value.every((row) => checked.value.has(row.key)),
);
const someChecked = computed(
  () =>
    !allChecked.value &&
    visibleRows.value.some((row) => checked.value.has(row.key)),
);
const tableOptions = computed(() =>
  tables.value
    .filter((table) => String(table.id) !== String(props.currentModelId ?? ''))
    .map((table) => ({
      label: `${table.name || ''}（${table.tableNameNotation || table.code || ''}）`,
      value: String(table.id),
    })),
);
const columnLabels = computed(() => {
  if (mode.value === 'indicator') return ['指标名称', '指标编码', '统计周期 / 单位'];
  if (mode.value === 'codeTable') return ['代码表名称', '字段类型', '代码项数'];
  return ['字段中文名称', '字段英文名称', '数据类型'];
});

function reset() {
  loading.value = false;
  failed.value = false;
  keyword.value = '';
  tables.value = [];
  tableId.value = undefined;
  rows.value = [];
  checked.value = new Set();
}

async function open(next: FieldImportMode) {
  mode.value = next;
  reset();
  visible.value = true;
  if (next === 'codeTable') {
    rows.value = props.codeTables.map((codeSet) => ({
      key: String(codeSet.id),
      name: codeSet.name || String(codeSet.id),
      code: (codeSet.columnType || 'VARCHAR').toUpperCase(),
      extra: codeSet.itemCount === undefined ? '-' : `${codeSet.itemCount} 项`,
      field: codeTableField(codeSet),
    }));
    return;
  }
  const current = guard.begin();
  loading.value = true;
  try {
    if (next === 'indicator') {
      const options = await getActiveIndicatorOptions(props.dataDomainId);
      if (!current()) return;
      rows.value = options
        .filter((option: IndicatorModelApi.Option) => option.indicatorType === 'DERIVED')
        .map((option) => ({
          key: String(option.id),
          name: option.name,
          code: option.code,
          extra: [option.timePeriod, option.unit].filter(Boolean).join(' / ') || '-',
          field: indicatorField(option),
        }));
    } else {
      const page = await getTableModelPage({
        pageNo: 1,
        pageSize: 100,
        dataDomainId: props.dataDomainId,
        status: 'PUBLISHED',
      });
      if (!current()) return;
      tables.value = page.list;
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}

async function chooseTable(value: unknown) {
  tableId.value = value === undefined || value === null ? undefined : String(value);
  rows.value = [];
  checked.value = new Set();
  if (tableId.value === undefined) return;
  const current = guard.begin();
  loading.value = true;
  failed.value = false;
  try {
    const design = await getTableModelDesign(tableId.value);
    if (!current()) return;
    rows.value = design.fields.map((field, index) => ({
      key: field.fieldUid || `${index}`,
      name: field.cnName || field.fieldName || '',
      code: field.fieldName || '',
      extra: field.dataType || '-',
      field: logicTableField(field, props.kind),
    }));
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}

function toggle(key: string, value: boolean) {
  const next = new Set(checked.value);
  if (value) next.add(key);
  else next.delete(key);
  checked.value = next;
}

function toggleAll(value: boolean) {
  const next = new Set(checked.value);
  for (const row of visibleRows.value) {
    if (value) next.add(row.key);
    else next.delete(row.key);
  }
  checked.value = next;
}

function close() {
  guard.begin();
  visible.value = false;
  reset();
}

function confirm() {
  const fields = rows.value
    .filter((row) => checked.value.has(row.key))
    .map((row) => ({ ...row.field }));
  if (fields.length === 0) return;
  emit('import', fields);
  close();
}

defineExpose({ open });
</script>

<template>
  <ConfigProvider :theme="{ token: { borderRadius: tokens.borderRadius } }">
    <Modal
      v-model:open="visible"
      :title="title"
      class="qd-logic-modal"
      :width="900"
      :footer="null"
      :style="{ top: '68px' }"
      destroy-on-hidden
      @cancel="close"
    >
      <Spin :spinning="loading">
        <div class="qd-modal-scroll">
          <div class="qd-import-toolbar">
            <Select
              v-if="mode === 'logic'"
              :value="tableId"
              :options="tableOptions"
              placeholder="请选择同板块已发布的逻辑表"
              show-search
              option-filter-prop="label"
              allow-clear
              aria-label="选择逻辑表"
              @change="chooseTable"
            />
            <Input
              v-model:value="keyword"
              :placeholder="mode === 'logic' ? '搜索字段名称' : '搜索名称或编码'"
              allow-clear
            >
              <template #prefix><QdIcon name="search" /></template>
            </Input>
            <span class="qd-import-toolbar__hint">
              已选 {{ checked.size }} 项，英文名与现有字段重复的将跳过
            </span>
          </div>
          <Alert
            v-if="failed"
            type="error"
            message="加载失败，请关闭后重试。"
            show-icon
          />
          <div class="qd-import-table-wrap">
            <table class="qd-summary-table">
              <thead>
                <tr>
                  <th class="is-check">
                    <Checkbox
                      :checked="allChecked"
                      :indeterminate="someChecked"
                      :disabled="visibleRows.length === 0"
                      aria-label="全选"
                      @change="toggleAll($event.target.checked)"
                    />
                  </th>
                  <th v-for="label in columnLabels" :key="label">{{ label }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in visibleRows" :key="row.key">
                  <td class="is-check">
                    <Checkbox
                      :checked="checked.has(row.key)"
                      :aria-label="`选择${row.name}`"
                      @change="toggle(row.key, $event.target.checked)"
                    />
                  </td>
                  <td>{{ row.name }}</td>
                  <td>{{ row.code }}</td>
                  <td>{{ row.extra }}</td>
                </tr>
              </tbody>
            </table>
            <div v-if="!loading && visibleRows.length === 0" class="qd-summary-empty">
              <img :src="emptyRecords" alt="" />
              <p>
                {{
                  mode === 'logic' && tableId === undefined
                    ? '请先选择逻辑表'
                    : '暂无可引入的记录'
                }}
              </p>
            </div>
          </div>
        </div>
      </Spin>
      <footer class="qd-modal-footer">
        <Button @click="close">取消</Button><Button
          type="primary"
          :disabled="checked.size === 0"
          @click="confirm"
        >
          确定引入
        </Button>
      </footer>
    </Modal>
  </ConfigProvider>
</template>
