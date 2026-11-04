<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 节点编辑器共享设计器草稿，统一由设计器校验与保存。 */
import type {
  FieldMapping,
  MappingColumns,
  MappingSide,
} from './field-mapping';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { DataforgeEtlApi } from '#/api/dataforge/etl';
import type { IntegrationApi } from '#/api/dataforge/integration';

import { computed, ref, watch } from 'vue';

import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  Checkbox,
  Empty,
  Input,
  InputNumber,
  RadioGroup,
  Select,
  Spin,
  Switch,
  TextArea,
} from 'antdv-next';

import {
  getDatabaseSimpleList,
  getDatabaseTableList,
  getDatabaseTableMeta,
} from '#/api/dataforge/database';
import { useObjectRequest } from '#/hooks/use-object-request';

import DatabaseConnectionPicker from './database-connection-picker.vue';
import {
  createMappingColumns,
  moveMappingColumn,
  refreshMappingColumns,
  serializeMappingColumns,
  serializeMappingLayout,
  validateFieldMappings,
} from './field-mapping';
import ReferenceIcon from './reference-icon.vue';
import TableInputReadOptions from './table-input-read-options.vue';
import TableInputSqlEditor from './table-input-sql-editor.vue';

const props = defineProps<{
  config: Record<string, any>;
  disabled?: boolean;
  engine?: string;
  inputFields?: DataforgeEtlApi.FieldMeta[];
  mode?: string;
  resolveFields?: () => Promise<IntegrationApi.SchemaField[]>;
  toolCode: 'insert-db' | 'read-db';
}>();
const access = useAccessStore();
const isInput = computed(() => props.toolCode === 'read-db');
const databases = ref<FoundationDatabaseApi.DatabaseSimple[]>([]);
const tables = ref<FoundationDatabaseApi.DatabaseTable[]>([]);
const fields = ref<FoundationDatabaseApi.DatabaseTableColumn[]>([]);
const databaseLoading = ref(false);
const tableLoading = ref(false);
const fieldLoading = ref(false);
const loadError = ref('');
const validationErrors = ref<string[]>([]);
const readOptions = ref<{ validate: () => boolean }>();
const fieldSort = ref<{ direction: -1 | 1; key: 'length' | 'scale' }>();
const displayedFields = computed(() => fields.value.map((field, index) => ({ ...field, ordinal: index + 1 })).toSorted((left, right) => {
  const sort = props.config.ifSql === 1 ? undefined : fieldSort.value;
  if (!sort) return 0;
  const a = left[sort.key];
  const b = right[sort.key];
  if (typeof a !== 'number') return typeof b === 'number' ? 1 : 0;
  if (typeof b !== 'number') return -1;
  if (a === b) return 0;
  return (a < b ? -1 : 1) * sort.direction;
}));
function sortFields(key: 'length' | 'scale') {
  if (fieldSort.value?.key === key) {
    fieldSort.value = fieldSort.value.direction === 1 ? { key, direction: -1 } : undefined;
  } else {
    fieldSort.value = { key, direction: 1 };
  }
}
function sortState(key: 'length' | 'scale') {
  if (fieldSort.value?.key === key)
    return fieldSort.value.direction === 1 ? 'ascending' : 'descending';
  return 'none';
}
const configEpoch = ref(0);
watch(
  () => props.config,
  () => {
    configEpoch.value++;
  },
  { flush: 'sync', immediate: true },
);
const optionRequest = useObjectRequest(
  () => props.toolCode,
  () => {
    databases.value = [];
  },
);
const tableRequest = useObjectRequest(
  () =>
    `${configEpoch.value}/${props.toolCode}/${props.config.databaseId ?? ''}`,
);
const fieldRequest = useObjectRequest(
  () =>
    `${configEpoch.value}/${props.toolCode}/${props.config.databaseId ?? ''}/${props.config.tableName ?? ''}/${props.config.ifSql ?? 0}/${props.config.sql ?? ''}`,
);
const database = computed(() =>
  databases.value.find(
    (item) => String(item.id) === String(props.config.databaseId),
  ),
);
const sqlHooksSupported = computed(
  () =>
    !isInput.value &&
    props.engine === 'DATAX' &&
    props.config.dataSyncType === 2 &&
    database.value?.productId?.toUpperCase() === 'POSTGRESQL',
);
const hasSqlHooks = computed(() =>
  [props.config.preSql, props.config.postSql].some((value) =>
    String(value ?? '').trim(),
  ),
);
const databaseOptions = computed(() =>
  databases.value.map((item) => ({
    value: String(item.id),
    label: item.name || String(item.id),
  })),
);
const tableOptions = computed(() =>
  tables.value.map((item) => ({
    value: item.tableName,
    label: item.remarks
      ? `${item.tableName}（${item.remarks}）`
      : item.tableName,
  })),
);
const mappings = computed<FieldMapping[]>(() =>
  Array.isArray(props.config.fieldMappings) ? props.config.fieldMappings : [],
);
const sourceFields = computed(() => {
  const declared =
    props.inputFields?.flatMap((field) =>
      field.name ? [{ name: field.name, dataType: field.dataType }] : [],
    ) ?? [];
  return declared.length > 0
    ? declared
    : mappings.value.map((item) => ({ name: item.source }));
});
const targetFields = computed(() =>
  fields.value.map((field) => ({
    name: field.fieldName,
    dataType: field.fieldTypeName,
  })),
);
const mappingSides: MappingSide[] = ['source', 'target'];
const mappingColumns = ref<MappingColumns>({ source: [], target: [] });
const mappingRows = computed(() =>
  Math.max(
    mappingColumns.value.source.length,
    mappingColumns.value.target.length,
  ),
);
const drag = ref<{
  config: Record<string, any>;
  key: string;
  side: MappingSide;
}>();
let mappingConfig: Record<string, any> | undefined;
let committedMapping = '';
const mappingSignature = () =>
  JSON.stringify([props.config.fieldMappings, props.config.fieldMappingLayout]);
watch(
  () =>
    [
      props.config,
      mappingSignature(),
      sourceFields.value,
      targetFields.value,
    ] as const,
  () => {
    const signature = mappingSignature();
    if (
      mappingConfig !== props.config ||
      signature !== committedMapping ||
      props.config.fieldMappings === undefined
    ) {
      mappingColumns.value = createMappingColumns(
        sourceFields.value,
        targetFields.value,
        Array.isArray(props.config.fieldMappings)
          ? props.config.fieldMappings
          : undefined,
        props.config.fieldMappingLayout,
      );
      drag.value = undefined;
    } else
      mappingColumns.value = refreshMappingColumns(
        mappingColumns.value,
        sourceFields.value,
        targetFields.value,
      );
    mappingConfig = props.config;
    committedMapping = signature;
  },
  { immediate: true },
);
watch(
  () => props.disabled,
  () => {
    drag.value = undefined;
  },
);
function commitMappings() {
  props.config.fieldMappings = serializeMappingColumns(mappingColumns.value);
  props.config.fieldMappingLayout = serializeMappingLayout(
    mappingColumns.value,
  );
  committedMapping = mappingSignature();
}
function selectMapping(side: MappingSide, index: number, checked: boolean) {
  if (props.disabled || fieldLoading.value) return;
  const column = mappingColumns.value[side][index];
  if (!column?.exists || !column.name) return;
  column.checked = checked;
  commitMappings();
}
function selectAllMappings(side: MappingSide, checked: boolean) {
  if (props.disabled || fieldLoading.value) return;
  for (const column of mappingColumns.value[side])
    if (column.exists && column.name) column.checked = checked;
  commitMappings();
}
function mappingSelection(side: MappingSide) {
  const columns = mappingColumns.value[side].filter(
    (column) => column.exists && column.name,
  );
  const checked = columns.filter((column) => column.checked).length;
  return {
    all: columns.length > 0 && checked === columns.length,
    partial: checked > 0 && checked < columns.length,
  };
}
function moveMapping(side: MappingSide, from: number, to: number) {
  if (props.disabled || fieldLoading.value) return;
  const next = moveMappingColumn(mappingColumns.value, side, from, to);
  if (next !== mappingColumns.value) {
    mappingColumns.value = next;
    commitMappings();
  }
}
function startMappingDrag(event: DragEvent, side: MappingSide, index: number) {
  if (props.disabled || fieldLoading.value) {
    event.preventDefault();
    return;
  }
  drag.value = {
    side,
    key: mappingColumns.value[side][index]!.key,
    config: props.config,
  };
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', drag.value.key);
  }
}
function dropMapping(side: MappingSide, index: number) {
  const current = drag.value;
  drag.value = undefined;
  if (!current || current.config !== props.config || current.side !== side)
    return;
  moveMapping(
    side,
    mappingColumns.value[side].findIndex(
      (column) => column.key === current.key,
    ),
    index,
  );
}
function mappingKeydown(
  event: KeyboardEvent,
  side: MappingSide,
  index: number,
) {
  if (!event.altKey || !['ArrowDown', 'ArrowUp'].includes(event.key)) return;
  event.preventDefault();
  moveMapping(side, index, index + (event.key === 'ArrowUp' ? -1 : 1));
}
function mappingActive(index: number) {
  const source = mappingColumns.value.source[index];
  const target = mappingColumns.value.target[index];
  return !!(
    source?.checked &&
    source.exists &&
    target?.checked &&
    target.exists &&
    source.name &&
    target.name
  );
}
const sqlMode = computed({
  get: () => (props.config.ifSql === 1 ? 1 : 0),
  set: (value: number) => {
    if (props.disabled) return;
    if (value === sqlMode.value) return;
    if (value === 1) {
      props.config.tableReadDraft = {
        whereCondition: props.config.whereCondition,
        selectedColumns: props.config.selectedColumns,
        fieldMappings: props.config.fieldMappings,
      };
      delete props.config.whereCondition;
      delete props.config.selectedColumns;
      delete props.config.fieldMappings;
    } else if (props.config.tableReadDraft) {
      for (const key of ['whereCondition', 'selectedColumns', 'fieldMappings'])
        props.config[key] = props.config.tableReadDraft[key];
      delete props.config.tableReadDraft;
    }
    props.config.ifSql = value;
  },
});
const realtimeOutput = computed(() => !isInput.value && props.mode === 'REALTIME');
const flinkOutput = computed(() => !isInput.value && props.engine === 'FLINK');
const writeModes = computed(() => realtimeOutput.value ? [{ label: '增量更新', value: 2 }] : [
  { label: '追加', value: 1 },
  ...(flinkOutput.value ? [] : [{ label: '全量', value: 0, disabled: true }]),
  { label: '增量更新', value: 2, disabled: flinkOutput.value },
  ...(props.config.dataSyncType === 3 ? [{ label: '仅更新（历史配置）', value: 3, disabled: true }] : []),
]);
async function loadDatabases() {
  const current = optionRequest.begin();
  databaseLoading.value = true;
  loadError.value = '';
  try {
    const result = await getDatabaseSimpleList();
    if (current()) databases.value = result;
  } catch {
    if (current()) loadError.value = '数据连接加载失败，请重试';
  } finally {
    if (current()) databaseLoading.value = false;
  }
}
async function loadTables() {
  const current = tableRequest.begin();
  tables.value = [];
  tableLoading.value = false;
  if (!props.config.databaseId) return;
  tableLoading.value = true;
  loadError.value = '';
  try {
    const result = await getDatabaseTableList(props.config.databaseId);
    if (current()) tables.value = result;
  } catch {
    if (current()) loadError.value = '表与视图加载失败，请重试';
  } finally {
    if (current()) tableLoading.value = false;
  }
}
async function loadFields() {
  const current = fieldRequest.begin();
  fields.value = [];
  fieldLoading.value = false;
  if (
    !props.config.databaseId ||
    !props.config.tableName ||
    (isInput.value && props.config.ifSql === 1)
  )
    return;
  fieldLoading.value = true;
  loadError.value = '';
  try {
    const result = await getDatabaseTableMeta(
      props.config.databaseId,
      props.config.tableName,
    );
    if (current()) {
      fields.value = result.columns ?? [];
      if (isInput.value && props.config.ifSql !== 1)
        props.config.fieldHints = {
          databaseId: String(props.config.databaseId),
          tableName: props.config.tableName,
          fields: fields.value.map((item) => ({
            name: item.fieldName,
            dataType: item.fieldTypeName,
            nullable: item.nullable,
            precision: item.precision,
            scale: item.scale,
            length: item.length,
            comment: item.remarks,
            primaryKey: item.pk,
          })),
        };
    }
  } catch {
    if (current()) loadError.value = '表字段加载失败，请重试';
  } finally {
    if (current()) fieldLoading.value = false;
  }
}
async function parseSql() {
  if (
    !props.resolveFields ||
    !props.config.databaseId ||
    !props.config.sql?.trim()
  ) {
    loadError.value = '请选择数据连接并填写查询SQL';
    return;
  }
  const current = fieldRequest.begin();
  fieldLoading.value = true;
  loadError.value = '';
  try {
    const result = await props.resolveFields();
    if (!current()) return;
    fields.value = result.map((item) => ({
      fieldName: item.name,
      fieldTypeName: item.dataType ?? undefined,
      nullable: item.nullable ?? undefined,
      precision: item.precision ?? undefined,
      scale: item.scale ?? undefined,
    }));
  } catch (error) {
    if (current())
      loadError.value =
        error instanceof Error ? error.message : 'SQL解析失败，请检查查询语句';
  } finally {
    if (current()) fieldLoading.value = false;
  }
}
function changeDatabase() {
  if (props.disabled) return;
  props.config.tableName = undefined;
  props.config.selectedColumns = undefined;
  if (isInput.value && props.config.tableReadDraft)
    props.config.tableReadDraft.selectedColumns = undefined;
  if (!isInput.value) {
    props.config.fieldMappings = [];
    props.config.fieldMappingLayout = undefined;
    props.config.upsertKeys = realtimeOutput.value ? undefined : [];
  }
}
function isSelected(name: string) {
  return (
    !Array.isArray(props.config.selectedColumns) ||
    props.config.selectedColumns.includes(name)
  );
}
function selectField(name: string, checked: boolean) {
  if (props.disabled || fieldLoading.value) return;
  const current = Array.isArray(props.config.selectedColumns)
    ? ([...props.config.selectedColumns] as string[])
    : fields.value.map((field) => field.fieldName);
  props.config.selectedColumns = checked
    ? [...new Set([...current, name])]
    : current.filter((item) => item !== name);
}
function autoMap() {
  if (props.disabled || fieldLoading.value) return;
  mappingColumns.value = createMappingColumns(
    sourceFields.value,
    targetFields.value,
  );
  commitMappings();
}
function validate() {
  const messages: string[] = [];
  const readValid = !isInput.value || sqlMode.value === 1 || (readOptions.value?.validate() ?? true);
  if (!isInput.value && hasSqlHooks.value && !sqlHooksSupported.value)
    messages.push(
      '前后置 SQL 仅支持 PostgreSQL DataX 幂等写入，请调整执行引擎、目标连接和写入模式',
    );
  if (!props.config.databaseId) messages.push('请选择数据连接');
  if (isInput.value && sqlMode.value === 1) {
    if (!props.config.sql?.trim()) messages.push('请输入查询SQL');
  } else if (!props.config.tableName?.trim()) messages.push('请选择数据表');
  if (
    isInput.value && sqlMode.value === 0 &&
    Array.isArray(props.config.selectedColumns) &&
    props.config.selectedColumns.length === 0
  )
    messages.push('请至少选择一个属性字段');
  if (!isInput.value) {
    if (fieldLoading.value) messages.push('目标字段仍在加载，请稍候');
    else
      messages.push(
        ...validateFieldMappings(
          Array.isArray(props.config.fieldMappings)
            ? props.config.fieldMappings
            : sourceFields.value.map((source) => ({
                source: source.name,
                target: source.name,
                enabled: true,
              })),
          sourceFields.value,
          targetFields.value,
        ),
      );
  }
  if (realtimeOutput.value && props.config.dataSyncType !== 2) messages.push('实时表输出请明确选择增量更新；历史追加配置仍保留');
  if (!isInput.value && props.config.dataSyncType === 2) {
    const keys = props.config.upsertKeys;
    if (!Array.isArray(keys) || keys.length === 0)
      messages.push('请选择目标表非空唯一键');
    else if (
      keys.some((key) => !fields.value.some((field) => field.fieldName === key))
    )
      messages.push('唯一键包含当前目标表不存在的字段');
    if (realtimeOutput.value) {
      const actual = fields.value.filter((field) => field.pk === true && field.nullable === false).map((field) => field.fieldName);
      if (Array.isArray(props.config.fieldMappings) && !actual.every((name) => props.config.fieldMappings.some((mapping: FieldMapping) => mapping.enabled !== false && mapping.target === name))) messages.push('实时增量输出映射必须覆盖全部目标主键');
      if (actual.length === 0 || !Array.isArray(keys) || keys.length !== actual.length || !actual.every((name) => keys.includes(name))) messages.push('实时增量更新必须使用目标元数据确认的全部非空主键');
    }
  }
  validationErrors.value = messages;
  return messages.length === 0 && readValid;
}
defineExpose({ validate });
function refresh() {
  void loadDatabases();
  void loadTables();
  if (isInput.value && props.config.ifSql === 1) void parseSql();
  else void loadFields();
}
watch(
  () => [props.config.ifSql, props.config.sql],
  () => {
    if (isInput.value && props.config.ifSql === 1) {
      fields.value = [];
      fieldLoading.value = false;
    }
  },
);
watch(
  () => [access.accessToken, access.refreshToken],
  () => {
    void loadDatabases();
  },
  { immediate: true },
);
watch(
  () => [props.toolCode, props.config.databaseId, props.config],
  () => {
    void loadTables();
  },
  { immediate: true },
);
watch(
  () => [
    props.toolCode,
    props.config.databaseId,
    props.config.tableName,
    props.config.ifSql,
    props.config,
  ],
  () => {
    void loadFields();
  },
  { immediate: true },
);
watch(
  () => [props.toolCode, props.mode, props.config],
  () => {
    if (isInput.value) {
      props.config.ifSql ??= 0;
      props.config.readMode ??= 'FULL';
    } else {
      props.config.dataSyncType ??= realtimeOutput.value ? 2 : 1;
      props.config.batchSize ??= 1024;
      props.config.targetTruncate ??= false;
    }
  },
  { immediate: true },
);
watch(
  () => [props.config.dataSyncType, fields.value, realtimeOutput.value],
  () => {
    if (props.disabled || !realtimeOutput.value || props.config.dataSyncType !== 2 || (props.config.upsertKeys !== undefined && props.config.upsertKeys !== null)) return;
    const actual = fields.value.filter((field) => field.pk === true && field.nullable === false).map((field) => field.fieldName);
    if (actual.length > 0) props.config.upsertKeys = actual;
  },
  { immediate: true },
);
</script>

<template>
  <div class="qi-database-editor" :class="{ 'qi-db-sql-mode': isInput && sqlMode === 1 }" :data-tool-code="toolCode">
    <Alert
      v-if="validationErrors.length > 0"
      type="error"
      :message="validationErrors.join('；')"
      show-icon
    />
    <Alert v-if="loadError" type="error" :message="loadError" show-icon>
      <template #action>
        <Button
          size="small"
          :disabled="databaseLoading || tableLoading || fieldLoading"
          @click="refresh"
          >
重试
</Button>
      </template>
    </Alert>
    <div v-if="isInput" class="qi-db-field qi-db-full">
      <label>连接方式</label>
      <RadioGroup
        v-model:value="sqlMode"
        :disabled="disabled"
        :options="[
          { label: '数据连接', value: 0 },
          { label: 'SQL', value: 1 },
        ]"
      />
    </div>
    <div class="qi-db-grid">
      <div
        class="qi-db-field qi-db-source-connection"
      >
        <label><em>*</em>{{ isInput ? '源数据库连接' : '目标数据连接' }}</label>
        <DatabaseConnectionPicker
          v-if="isInput"
          :value="config.databaseId"
          :databases="databases"
          :loading="databaseLoading"
          :disabled="disabled"
          placeholder="请选择源数据库连接"
          @update:value="(value) => { if (!disabled) config.databaseId = value; }"
          @change="changeDatabase"
        />
        <Select
          v-else
          :value="
            config.databaseId == null ? undefined : String(config.databaseId)
          "
          :options="databaseOptions"
          :loading="databaseLoading"
          show-search
          option-filter-prop="label"
          allow-clear
          :placeholder="isInput ? '请选择源数据库连接' : '请选择目标数据连接'"
          aria-label="节点数据连接"
          @update:value="
            (value) => {
              config.databaseId = value;
              changeDatabase();
            }
          "
        />
      </div>
      <div class="qi-db-field">
        <label>数据连接类型</label><Input :value="database?.databaseTypeName || database?.productId || undefined" placeholder="请输入数据连接类型" disabled />
      </div>
      <div class="qi-db-field">
        <label>数据连接实例</label><Input :value="database?.databaseSchema || undefined" placeholder="自动回显实例" disabled />
      </div>
      <div
        v-if="!isInput || sqlMode === 0"
        class="qi-db-field qi-db-selected-table"
      >
        <label><em>*</em>{{ isInput ? '选择表/视图' : '选择表' }}</label>
        <Select
          v-model:value="config.tableName"
          :options="tableOptions"
          :loading="tableLoading"
          :disabled="disabled || !config.databaseId"
          show-search
          option-filter-prop="label"
          allow-clear
          placeholder="请选择数据表"
          aria-label="节点数据表"
          @change="config.selectedColumns = undefined"
        />
      </div>
      <div v-if="isInput && sqlMode === 1" class="qi-db-field qi-db-full">
        <label><em>*</em>SQL语句</label>
        <div class="qi-db-sql-input">
          <TableInputSqlEditor
            class="qi-db-query-editor"
            :model-value="config.sql ?? ''"
            :readonly="disabled"
            :folding="false"
            render-validation-decorations="off"
            height="140px"
            aria-label="节点查询SQL"
            @update:model-value="(value) => { if (!disabled) config.sql = value; }"
          />
          <Button
            class="qi-reference-button"
            :disabled="disabled || !config.databaseId || !config.sql?.trim()"
            :loading="fieldLoading"
            @click="parseSql"
            >
SQL解析
</Button>
        </div>
      </div>
      <div v-if="!isInput" class="qi-db-field qi-db-full">
        <label>where条件</label>
        <TextArea v-model:value="config.whereCondition" :rows="3" :maxlength="256" show-count aria-label="where条件" placeholder="请输入where条件" />
      </div>
    </div>
    <TableInputReadOptions
      v-if="isInput && sqlMode === 0"
      ref="readOptions"
      :config="config"
      :fields="fields"
      :disabled="disabled"
    />
    <section class="qi-db-section">
      <h3>{{ isInput ? '属性字段' : '字段映射' }}</h3>
      <div v-if="!isInput" class="qi-db-mapping-toolbar">
        <span>源字段 <span aria-hidden="true">→</span> 目标字段</span>
        <Button
          class="qi-reference-button"
          :disabled="
            disabled || fieldLoading || !sourceFields.length || !fields.length
          "
          @click="autoMap"
        >
          <ReferenceIcon name="refresh" />同名自动映射
        </Button>
      </div>
      <Spin :spinning="fieldLoading">
        <div v-if="!isInput" class="qi-db-mapping-scroll">
          <div class="qi-db-mapping-columns">
            <template v-for="side in mappingSides" :key="side">
              <div
                v-if="side === 'target'"
                class="qi-db-mapping-arrows"
                aria-hidden="true"
              >
                <div
                  v-for="index in mappingRows"
                  :key="index"
                  class="qi-db-mapping-arrow-row"
                >
                  <template
                    v-if="
                      mappingColumns.source[index - 1] &&
                      mappingColumns.target[index - 1]
                    "
                  >
                    <i class="qi-db-mapping-dot"></i>
                    <span
                      class="qi-db-mapping-arrow"
                      :class="{ 'is-active': mappingActive(index - 1) }"
                    ></span>
                    <i class="qi-db-mapping-dot"></i>
                  </template>
                </div>
              </div>
              <div class="qi-db-mapping-side" :data-mapping-side="side">
                <div class="qi-db-mapping-heading">
                  <p>{{ side === 'source' ? '来源表字段：' : '目标字段：' }}</p>
                  <Checkbox
                    :checked="mappingSelection(side).all"
                    :indeterminate="mappingSelection(side).partial"
                    :disabled="
                      disabled || fieldLoading || !mappingColumns[side].length
                    "
                    :aria-label="
                      side === 'source' ? '全选来源字段' : '全选目标字段'
                    "
                    @update:checked="
                      (checked) => selectAllMappings(side, checked)
                    "
                  >
                    全选
                  </Checkbox>
                </div>
                <div
                  class="qi-db-mapping-list"
                  :aria-label="
                    side === 'source' ? '来源字段排序' : '目标字段排序'
                  "
                >
                  <div
                    v-for="(column, index) in mappingColumns[side]"
                    :key="column.key"
                    class="qi-db-mapping-row"
                    :class="{
                      'is-missing':
                        !column.exists && !fieldLoading && !loadError,
                      'is-dragging':
                        drag?.side === side && drag.key === column.key,
                    }"
                    :draggable="!disabled && !fieldLoading"
                    :data-field-name="column.name"
                    :tabindex="disabled ? -1 : 0"
                    :title="`${column.name || '未选择字段'}${column.dataType ? `（${column.dataType}）` : ''}${column.exists ? '' : fieldLoading || loadError ? '：字段元数据尚未验证' : '：字段不存在'}；Alt + 方向键调整顺序`"
                    @dragstart="(event) => startMappingDrag(event, side, index)"
                    @dragend="drag = undefined"
                    @dragover.prevent
                    @drop.prevent="dropMapping(side, index)"
                    @keydown="(event) => mappingKeydown(event, side, index)"
                  >
                    <Checkbox
                      :checked="column.checked"
                      :disabled="
                        disabled ||
                        fieldLoading ||
                        !column.exists ||
                        !column.name
                      "
                      :aria-label="`${side === 'source' ? '来源' : '目标'}字段${column.name || '未选择'}`"
                      @update:checked="
                        (checked) => selectMapping(side, index, checked)
                      "
                    >
                      <span>{{ column.name || '未选择字段' }}</span>
                    </Checkbox>
                    <ReferenceIcon
                      name="field-drag-ref"
                      class="qi-db-field-drag"
                    />
                  </div>
                </div>
              </div>
            </template>
          </div>
          <div v-if="!fieldLoading && !sourceFields.length" class="qi-reference-empty">暂无记录</div>
        </div>
        <div v-else class="qi-db-field-scroll">
          <table class="qi-db-field-table" aria-label="表输入属性字段">
            <thead>
              <tr>
                <th>序号</th>
                <th>英文名称</th>
                <th>中文名称</th>
                <th>字段类型</th>
                <th v-if="sqlMode === 0">是否主键</th>
                <th v-if="sqlMode === 0" :aria-sort="sortState('length')"><button type="button" class="qi-db-sort" @click="sortFields('length')">字段长度<span aria-hidden="true">{{ fieldSort?.key === 'length' ? fieldSort.direction === 1 ? ' ▴' : ' ▾' : ' ↕' }}</span></button></th>
                <th v-if="sqlMode === 0" :aria-sort="sortState('scale')"><button type="button" class="qi-db-sort" @click="sortFields('scale')">小数精度<span aria-hidden="true">{{ fieldSort?.key === 'scale' ? fieldSort.direction === 1 ? ' ▴' : ' ▾' : ' ↕' }}</span></button></th>
                <th v-if="sqlMode === 0">读取</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="field in displayedFields" :key="field.fieldName">
                <td>{{ field.ordinal }}</td>
                <td>{{ field.fieldName }}</td>
                <td>{{ field.remarks || '-' }}</td>
                <td>{{ field.fieldTypeName || '-' }}</td>
                <td v-if="sqlMode === 0">
                  <span v-if="typeof field.pk !== 'boolean'">-</span>
                  <Switch v-else :checked="field.pk" disabled :aria-label="`${field.fieldName}是否主键`" />
                </td>
                <td v-if="sqlMode === 0">{{ field.length ?? '-' }}</td>
                <td v-if="sqlMode === 0">{{ field.scale ?? '-' }}</td>
                <td v-if="sqlMode === 0"><Checkbox :checked="isSelected(field.fieldName)" :disabled="disabled || fieldLoading" :aria-label="`读取字段${field.fieldName}`" @update:checked="(value) => selectField(field.fieldName, value)" /></td>
              </tr>
            </tbody>
          </table>
          <div v-if="!isInput && !fieldLoading && !fields.length" class="qi-reference-empty">暂无记录</div>
          <div v-if="sqlMode === 1 && !fieldLoading && !fields.length" class="qi-db-sql-empty">
            <img src="/assets/qdata-integration/node-empty-reference.png" alt="" aria-hidden="true" />
            <span>暂无记录</span>
          </div>
          <Empty
            v-if="isInput && sqlMode === 0 && !fieldLoading && !fields.length"
            description="选择数据表后展示字段"
          />
        </div>
      </Spin>
    </section>
    <section v-if="!isInput" class="qi-db-section">
      <h3>输出配置</h3>
      <div v-if="!flinkOutput" class="qi-db-field qi-db-full">
        <label>前置SQL</label>
        <TextArea
          v-model:value="config.preSql"
          :rows="4"
          :maxlength="4000"
          :disabled="disabled || !sqlHooksSupported"
          show-count
          aria-label="前置SQL"
          placeholder="写入前执行的 INSERT、UPDATE 或 DELETE"
        />
      </div>

      <div class="qi-db-grid">
        <div class="qi-db-field">
          <label>写入模式</label><RadioGroup
            v-model:value="config.dataSyncType"
            :options="writeModes"
            aria-label="写入模式"
          />
        </div>
        <div class="qi-db-field">
          <label>单次写入数据</label><div class="qi-db-batch-value">
<InputNumber
            v-model:value="config.batchSize"
            :min="1"
            :max="10000"
            :precision="0"
            aria-label="单次写入数据"
            placeholder="不输入默认1000条"
          /><span>条</span>
</div>
        </div>
      </div>
      <template v-if="config.dataSyncType === 2 && !realtimeOutput">
        <div class="qi-db-field qi-db-full">
          <label>更新主键字段</label>
          <Select
            v-model:value="config.upsertKeys"
            mode="multiple"
            :options="
              fields.map((field) => ({
                label: field.fieldName,
                value: field.fieldName,
              }))
            "
            aria-label="目标唯一键"
            placeholder="选择目标表非空主键或唯一键的全部列"
          />
        </div>
        <Alert
          type="info"
          show-icon
          message="同一唯一键重放时更新已有行。需要受管幂等插件、非空唯一约束及 INSERT/UPDATE 权限；不支持清空、用户触发器或行级策略。"
        />
      </template>
      <div v-if="!flinkOutput" class="qi-db-field qi-db-full">
        <label>后置SQL</label>
        <TextArea
          v-model:value="config.postSql"
          :rows="4"
          :maxlength="4000"
          :disabled="disabled || !sqlHooksSupported"
          show-count
          aria-label="后置SQL"
          placeholder="写入后执行的 INSERT、UPDATE 或 DELETE"
        />
      </div>
    </section>
  </div>
</template>

<style scoped>
.qi-database-editor {
  display: flex;
  flex-direction: column;
  gap: 20px;
  color: #333;
}

.qi-db-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px 26px;
}

.qi-db-full {
  grid-column: 1 / -1;
}

.qi-db-field {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr);
  align-items: start;
}

.qi-db-field > label {
  padding: 5px 12px 0 0;
  text-align: right;
}

.qi-db-field em {
  margin-right: 4px;
  font-style: normal;
  color: #ff6262;
}

.qi-db-field :deep(.ant-select),
.qi-db-field :deep(.ant-input-number) {
  width: 100%;
}

.qi-db-section h3 {
  padding-left: 10px;
  margin: 0 0 16px;
  font-size: 14px;
  font-weight: 400;
  border-left: 4px solid #2f62ff;
}

.qi-db-sql-input {
  display: grid;
  gap: 8px;
  justify-items: end;
  min-width: 0;
}

.qi-db-query-editor {
  width: 100%;
  min-width: 0;
}

.qi-db-query-editor :deep(.qi-sql-editor-surface) {
  border: 0;
}

.qi-db-query-editor :deep(.monaco-editor .margin) {
  background-color: #f5f5f7;
}

.qi-db-query-editor :deep(.monaco-editor .view-overlays .current-line) {
  background: #e8f1ff;
  border: 0;
}

.qi-db-sql-input :deep(textarea) {
  width: 100%;
  font-family: monospace;
}

.qi-db-mapping-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.qi-db-mapping-scroll {
  max-height: 340px;
  padding: 0 24px 12px;
  overflow: auto;
}

.qi-db-mapping-columns {
  display: grid;
  grid-template-columns: minmax(140px, 1fr) 100px minmax(140px, 1fr);
  align-items: start;
}

.qi-db-mapping-heading {
  height: 76px;
  padding: 8px 12px;
}

.qi-db-mapping-heading p {
  margin: 0 0 10px;
}

.qi-db-mapping-row,
.qi-db-mapping-arrow-row {
  display: flex;
  align-items: center;
  height: 40px;
}

.qi-db-mapping-row {
  gap: 12px;
  justify-content: space-between;
  padding: 0 12px;
  border: 1px solid transparent;
}

.qi-db-mapping-row[draggable='true'] {
  cursor: move;
}

.qi-db-mapping-row:focus-visible {
  outline: 2px solid #2f62ff;
  outline-offset: -2px;
}

.qi-db-mapping-row:hover,
.qi-db-mapping-row.is-dragging {
  background: #f5f7fa;
}

.qi-db-mapping-row.is-missing {
  color: #ff6262;
}

.qi-db-mapping-row :deep(.ant-checkbox-wrapper) {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}

.qi-db-mapping-row :deep(.ant-checkbox-wrapper > span:last-child) {
  overflow: hidden;
  text-overflow: ellipsis;
}

.qi-db-field-drag {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
}

.qi-db-mapping-arrows {
  padding-top: 76px;
}

.qi-db-mapping-arrow-row {
  justify-content: center;
  padding: 0 6px;
}

.qi-db-mapping-dot {
  flex-shrink: 0;
  width: 6px;
  height: 6px;
  background: #2f62ff;
  border-radius: 50%;
}

.qi-db-mapping-arrow {
  position: relative;
  flex: 1;
  height: 1px;
  margin: 0 3px;
}

.qi-db-mapping-arrow.is-active {
  background: #2f62ff;
}

.qi-db-mapping-arrow.is-active::after {
  position: absolute;
  top: -3px;
  right: 0;
  width: 7px;
  height: 7px;
  content: '';
  border-top: 1px solid #2f62ff;
  border-right: 1px solid #2f62ff;
  transform: rotate(45deg);
}

.qi-db-field-scroll {
  max-height: 260px;
  overflow: auto;
  border: 1px solid #e2e5ed;
}

.qi-db-field-table {
  width: 100%;
  font-size: 13px;
  border-collapse: collapse;
}

.qi-db-field-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 9px 12px;
  font-weight: 400;
  color: #666;
  text-align: left;
  background: #f1f1f5;
}

.qi-db-field-table td {
  min-width: 80px;
  padding: 8px 12px;
  border-bottom: 1px solid #e2e5ed;
}

.qi-db-field-table td:first-child {
  width: 56px;
  min-width: 56px;
}

.qi-db-field-table :deep(.ant-select) {
  width: 100%;
  min-width: 150px;
}

.qi-db-sort {
  padding: 0;
  font: inherit;
  color: inherit;
  cursor: pointer;
  background: none;
  border: 0;
}

.qi-db-sort span {
  color: #a8abb2;
}

.qi-db-sort:focus-visible {
  outline: 2px solid #2f62ff;
  outline-offset: 3px;
}

.qi-database-editor[data-tool-code='read-db'] {
  gap: 20px;
}

.qi-database-editor[data-tool-code='read-db'] .qi-db-grid {
  gap: 15px 20px;
}

.qi-database-editor[data-tool-code='read-db'] .qi-db-field {
  grid-template-columns: 110px minmax(0, 1fr);
}

.qi-database-editor[data-tool-code='read-db'] .qi-db-field-scroll {
  max-height: 320px;
  border: 0;
}

.qi-database-editor[data-tool-code='read-db'] .qi-db-field-table {
  min-width: 760px;
}

.qi-database-editor[data-tool-code='read-db'] .qi-db-field-table th {
  padding: 10px;
  font-weight: 600;
  white-space: nowrap;
}

.qi-database-editor[data-tool-code='read-db'] .qi-db-field-table td {
  padding: 13px 10px;
  color: #606266;
}

.qi-database-editor[data-tool-code='read-db'] .qi-db-field-table tbody tr:nth-child(even) {
  background: #fafafa;
}

.qi-db-sql-mode .qi-db-section h3 {
  display: flex;
  gap: 9px;
  align-items: center;
  padding-left: 0;
  margin: 4px 0 18px;
  font-size: 16px;
  border: 0;
}

.qi-db-sql-mode .qi-db-section h3::before {
  width: 6px;
  height: 16px;
  content: '';
  background: #2d63ff;
  border-radius: 3px;
}

.qi-database-editor.qi-db-sql-mode .qi-db-field-scroll {
  height: 310px;
  max-height: 310px;
  border-bottom: 1px solid #e3e5ed;
}

.qi-database-editor.qi-db-sql-mode .qi-db-field-table {
  min-width: 0;
  table-layout: fixed;
}

.qi-database-editor.qi-db-sql-mode .qi-db-field-table th {
  padding: 7px 10px;
}

.qi-db-sql-mode .qi-db-field-table th:first-child {
  width: 72px;
}

.qi-db-sql-empty {
  display: grid;
  gap: 14px;
  justify-items: center;
  padding: 55px 0 40px;
  color: #909399;
}

.qi-db-sql-empty img {
  width: 160px;
  height: 140px;
  object-fit: contain;
}

@media (max-width: 768px) {
  .qi-db-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 14px;
  }

  .qi-db-field {
    grid-template-columns: 106px minmax(0, 1fr);
  }
}
</style>

<style scoped>
.qi-db-field-table thead th,
.qi-db-field-table tbody td {
  font-size: 12px;
}
</style>

<style scoped>
.qi-database-editor[data-tool-code='insert-db'] .qi-db-field {
  grid-template-columns: 110px minmax(0, 1fr);
}

.qi-database-editor[data-tool-code='insert-db'] > .qi-db-grid {
  gap: 18px 20px;
}

.qi-database-editor[data-tool-code='insert-db'] > .qi-db-grid > .qi-db-full {
  order: 5;
}

.qi-database-editor[data-tool-code='insert-db'] .qi-db-section h3 {
  padding-left: 0;
  border: 0;
}

.qi-database-editor[data-tool-code='insert-db'] .qi-db-section > .qi-db-field {
  margin-bottom: 18px;
}

.qi-database-editor[data-tool-code='insert-db'] .qi-db-section > .qi-db-grid {
  margin-bottom: 18px;
}

.qi-db-batch-value {
  display: flex;
  gap: 8px;
  align-items: center;
}

</style>
