<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 配置编辑在设计器草稿中完成，父级负责统一保存。 */
import type { ConnectorColumn } from './connector-schema';
import type { ReferenceField } from './reference-node-schema';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, ref, watch } from 'vue';

import { useAccessStore } from '@vben/stores';

import {
  Alert,
  AutoComplete,
  Button,
  Dropdown,
  Empty,
  Input,
  InputNumber,
  Menu,
  MenuItem,
  message,
  Modal,
  RadioGroup,
  Select,
} from 'antdv-next';

import {
  getDatabaseSimpleList,
  getDatabaseTableList,
  getDatabaseTableMeta,
} from '#/api/dataforge/database';
import { useObjectRequest } from '#/hooks/use-object-request';

import ConnectorReferenceFields from './connector-reference-fields.vue';
import {
  connectorDefaults,
  connectorFieldVisible,
  getConnectorSchema,
  tdengineLegacyConfigKeys,
  validateConnectorColumn,
  validateConnectorConfig,
} from './connector-schema';
import ReferenceFieldEditModal from './reference-field-edit-modal.vue';
import ReferenceIcon from './reference-icon.vue';
import {
  moveReferenceField,
  resolveReferenceFields,
} from './reference-node-schema';
import ReferenceOutputMapping from './reference-output-mapping.vue';
import ReferenceOutputSelection from './reference-output-selection.vue';

const props = defineProps<{
  config: Record<string, any>;
  disabled?: boolean;
  engine?: string;
  executionReason?: string;
  inputFields?: DataforgeEtlApi.FieldMeta[];
  mode?: string;
  toolCode: string;
}>();
const access = useAccessStore();
const descriptor = computed(() => getConnectorSchema(props.toolCode, props.mode));
const executionHint = computed(
  () =>
    props.executionReason?.trim() ||
    '组件可用性尚未确认，请先检查当前任务配置。',
);
const legacyTdengineKeys = computed(() =>
  props.toolCode === 'read-tdengine'
    ? tdengineLegacyConfigKeys(props.config, props.mode)
    : [],
);
function clearLegacyTdengineConfig() {
  if (props.disabled) return;
  for (const key of legacyTdengineKeys.value)
    Reflect.deleteProperty(props.config, key);
  if (props.mode === 'OFFLINE') props.config.readMode = 'TABLE';
}
const fields = computed(
  () =>
    descriptor.value?.fields.filter((field) =>
      connectorFieldVisible(field, props.config) && !(
        ['read-hive', 'read-mongodb', 'read-odps'].includes(props.toolCode) && props.config.ifSql === 1 &&
        ['cursorTime', 'dateColumn', 'dateFormat', 'incrementColumn', 'incrementStart', 'readMode', 'whereCondition'].includes(field.key)
      ),
    ) ?? [],
);
const relationInput = computed(() =>
  ['read-hive', 'read-mongodb', 'read-odps'].includes(props.toolCode) ||
  (props.toolCode === 'read-tdengine' && props.mode === 'OFFLINE'),
);
const tdengineRealtime = computed(() => props.toolCode === 'read-tdengine' && props.mode !== 'OFFLINE');
const relationFieldsView = computed(() => ['read-hive', 'read-mongodb', 'read-odps', 'read-tdengine'].includes(props.toolCode));
const extendedRelationFields = computed(() => relationFieldsView.value && props.toolCode !== 'read-odps');
const mainProperties = computed(() => descriptor.value?.direction === 'input' || selectionOutput.value || ['write-ftp', 'write-hdfs'].includes(props.toolCode));
const showPlatformConnection = computed(
  () => descriptor.value?.connection || props.toolCode === 'read-ftp',
);
const tableConnector = computed(
  () =>
    relationInput.value ||
    ['write-hive', 'write-mongodb', 'write-odps'].includes(props.toolCode),
);
const selectionOutput = computed(() =>
  ['write-rabbitmq', 'write-redis', 'write-rocketmq'].includes(props.toolCode),
);
const mappingOutput = computed(
  () => descriptor.value?.direction === 'output' && !selectionOutput.value,
);
const advancedKeys = new Set([
  'credentialRef',
  'fileName',
  'groupId',
  'hasHeader',
  'maxBytes',
  'maxResponseBytes',
  'maxRows',
  'nullFormat',
  'offsetReset',
  'primaryKeys',
  'resourceBindingId',
]);
const outputKeys = new Set([
  'batchSize',
  'postSql',
  'preSql',
  'primaryKeys',
  'writeModeType',
]);
const isAdvancedField = (key: string) => advancedKeys.has(key) && !(tdengineRealtime.value && ['groupId', 'offsetReset'].includes(key));
const advancedFields = computed(() =>
  fields.value.filter((field) => isAdvancedField(field.key)),
);
const basicFields = computed(() =>
  fields.value.filter(
    (field) =>
      !isAdvancedField(field.key) &&
      !(descriptor.value?.direction === 'output' && outputKeys.has(field.key)),
  ),
);
const modeFields = computed(() =>
  relationInput.value
    ? basicFields.value.filter((field) => field.key === 'ifSql')
    : [],
);
const leadingFields = computed(() => {
  const firstInputFields: Record<string, string> = { 'read-ftp': 'encoding', 'read-hdfs': 'encoding', 'read-redis': 'dataType', 'read-rabbitmq': 'topic', 'read-rocketmq': 'topic' };
  const inputKey = firstInputFields[props.toolCode];
  return basicFields.value.filter((field) => descriptor.value?.direction === 'output'
    ? ['dataType', 'encoding', 'tableName', 'topic'].includes(field.key)
    : field.key === inputKey).slice(0, 1);
});
const remainingFields = computed(() =>
  basicFields.value.filter(
    (field) =>
      !modeFields.value.includes(field) && !leadingFields.value.includes(field) && !(tdengineRealtime.value && field.key === 'topic'),
  ),
);
const showConnectionDetails = computed(
  () =>
    relationInput.value ||
    descriptor.value?.direction === 'output' ||
    props.toolCode === 'read-tdengine',
);
const outputFields = computed(() => {
  const active = fields.value.filter((field) => outputKeys.has(field.key) && !isAdvancedField(field.key))
    .map((field) => ({ ...field, label: field.key === 'batchSize' ? '单次写入数据' : field.label.replace(/（暂不支持，可清空）$/u, ''), options: props.toolCode === 'write-mongodb' && field.key === 'writeModeType' ? field.options?.filter((option) => option.value !== 2 || props.config.writeModeType === 2) : field.options }));
  if (props.toolCode === 'write-hive') {
    const order = ['preSql', 'writeModeType', 'batchSize', 'postSql'];
    return active.toSorted((left, right) => order.indexOf(left.key) - order.indexOf(right.key));
  }
  return active;
});
const metadataEpoch = ref(0);
const tables = ref<FoundationDatabaseApi.DatabaseTable[]>([]);
const tablesLoading = ref(false);
const fieldsLoading = ref(false);
const metadataIssue = ref('');
const targetFields = ref<{ name: string; type?: string }[]>();
const tableOptions = computed(() => {
  const values = tables.value.map((table) => ({
    label: table.remarks
      ? `${table.tableName}（${table.remarks}）`
      : table.tableName,
    value: table.tableName,
  }));
  if (
    props.config.tableName &&
    !values.some((item) => item.value === props.config.tableName)
  )
    values.unshift({
      label: props.config.tableName,
      value: props.config.tableName,
    });
  return values;
});
const metadataRequest = useObjectRequest(
  () =>
    `${metadataEpoch.value}/${props.toolCode}/${props.config.databaseId ?? ''}`,
  () => {
    tables.value = [];
    targetFields.value = undefined;
    tablesLoading.value = false;
    fieldsLoading.value = false;
  },
);
const fieldMetadataRequest = useObjectRequest(
  () =>
    `${metadataEpoch.value}/${props.toolCode}/${props.config.databaseId ?? ''}/${props.config.tableName ?? ''}`,
  () => {
    targetFields.value = undefined;
    fieldsLoading.value = false;
  },
);
async function loadTables() {
  if (!tableConnector.value || !props.config.databaseId) return;
  const current = metadataRequest.begin();
  tablesLoading.value = true;
  metadataIssue.value = '';
  try {
    const result = await getDatabaseTableList(props.config.databaseId);
    if (current()) tables.value = result;
  } catch {
    if (current())
      metadataIssue.value = '表目录加载失败，可重试；已有表名与字段仍保留。';
  } finally {
    if (current()) tablesLoading.value = false;
  }
}
async function resolveTableFields() {
  if (props.disabled || !props.config.databaseId || !props.config.tableName)
    return;
  const current = fieldMetadataRequest.begin();
  const originalTable = props.config.tableName;
  fieldsLoading.value = true;
  metadataIssue.value = '';
  try {
    const result = await getDatabaseTableMeta(
      props.config.databaseId,
      originalTable,
    );
    if (!current() || props.config.tableName !== originalTable) return;
    const discovered = (result.columns ?? []).map((field) => ({
      name: field.fieldName,
      type: field.fieldTypeName || '',
      comment: field.remarks || '',
      primaryKey: field.pk,
      ...((field.length === undefined || field.length === null) ? {} : { length: field.length }),
      ...((field.scale === undefined || field.scale === null) ? {} : { precision: field.scale }),
    }));
    if (descriptor.value?.direction === 'output')
      targetFields.value = discovered;
    else
      setColumns(
        discovered.map((field) => ({
          ...columns.value.find((old) => old.name === field.name),
          ...field,
        })),
      );
  } catch {
    if (current())
      metadataIssue.value = '字段解析失败，已有属性字段仍保留，请重试。';
  } finally {
    if (current()) fieldsLoading.value = false;
  }
}
function changeReferenceField(key: string, value: unknown) {
  changeField(key, value);
  if (key === 'tableName') {
    targetFields.value = undefined;
    if (value) void resolveTableFields();
  }
}
function selectDatabase(value: unknown) {
  if (props.disabled) return;
  if (String(props.config.databaseId ?? '') !== String(value ?? '')) {
    props.config.databaseId = value;
    props.config.tableName = undefined;
    targetFields.value = undefined;
  }
}
function addReferenceColumn() {
  if (props.disabled) return;
  if (columns.value.length > 0 && issues.value.some((issue) => issue.field === 'columns')) {
    showErrors.value = true;
    message.warning('请先完善已有属性字段，再新增字段');
    return;
  }
  editedColumn = undefined;
  editedConfig = props.config;
  addingColumn.value = true;
  columnDraft.value = { name: '', type: 'STRING', ...(descriptor.value?.columnMode === 'index' ? { index: columns.value.length } : {}), ...(showKey.value ? { key: '' } : {}) };
  columnEditorErrors.value = [];
  columnEditorOpen.value = true;
}
const databases = ref<FoundationDatabaseApi.DatabaseSimple[]>([]);
const loading = ref(false);
const failed = ref(false);
const showErrors = ref(false);
const columnEditorOpen = ref(false);
const addingColumn = ref(false);
const columnEditorErrors = ref<string[]>([]);
const columnDraft = ref<ConnectorColumn>({ name: '', type: '' });
let editedColumn: ConnectorColumn | undefined;
let editedConfig: Record<string, any> | undefined;
let draggedColumn = -1;
const request = useObjectRequest(
  () => [props.toolCode, props.mode],
  () => {
    databases.value = [];
    loading.value = false;
  },
);
const database = computed(() =>
  databases.value.find(
    (item) => String(item.id) === String(props.config.databaseId),
  ),
);
const options = computed(() => {
  const product = props.toolCode.replace(/^(?:read|write)-/u, '').toLowerCase();
  const typedInputs = new Set(['ftp', 'hdfs', 'rabbitmq', 'redis', 'rocketmq']);
  const values = databases.value
    .filter((item) => {
      if (!props.toolCode.startsWith('read-') || !typedInputs.has(product)) return true;
      const type = `${item.productId ?? ''} ${item.databaseTypeName ?? ''}`.toLowerCase().replaceAll(/[^a-z0-9]/gu, '');
      return !type || type.includes(product) || String(item.id) === String(props.config.databaseId);
    })
    .map((item) => ({ label: item.name || String(item.id), value: String(item.id) }));
  const selected = props.config.databaseId;
  if (selected !== undefined && selected !== null && !values.some((item) => item.value === String(selected)))
    values.unshift({ label: `已选连接（${String(selected)}）`, value: String(selected) });
  return values;
});
const columns = computed<ConnectorColumn[]>(() =>
  Array.isArray(props.config.columns) ? props.config.columns : [],
);
const issues = computed(() =>
  validateConnectorConfig(props.toolCode, props.config, props.mode),
);
const dataTypes = [
  'STRING',
  'INTEGER',
  'LONG',
  'DOUBLE',
  'DECIMAL',
  'BOOLEAN',
  'DATE',
  'TIMESTAMP',
  'BINARY',
].map((value) => ({ label: value, value }));
const showKey = computed(() =>
  ['json', 'redis'].includes(descriptor.value?.columnMode ?? ''),
);
function error(key: string) {
  return showErrors.value
    ? issues.value.find((issue) => issue.field === key)?.message
    : undefined;
}
function validate() {
  showErrors.value = true;
  return issues.value.length === 0;
}
function changeField(key: string, value: unknown) {
  if (props.disabled) return;
  props.config[key] = value;
}
function setColumns(value: ConnectorColumn[]) {
  if (props.disabled) return;
  const columns =
    descriptor.value?.columnMode === 'index'
      ? value
      : value.map((column, index) =>
          column.index === undefined ? column : { ...column, index },
        );
  props.config.columns = columns;
  if (descriptor.value?.direction === 'output') {
    props.config.fieldMappingColumns = columns.map((column) => ({ ...column }));
    Reflect.deleteProperty(props.config, 'fieldMappingDraft');
  }
  if (descriptor.value?.direction === 'output')
    props.config.fieldMappings = columns.map((column) => ({
      source: column.key || column.name,
      target: column.name,
      enabled: true,
    }));
}
function columnKey(column: ConnectorColumn) {
  return descriptor.value?.columnMode === 'redis' && typeof column.redisField === 'string' ? column.redisField : column.key;
}
function updateColumn(
  index: number,
  key: keyof ConnectorColumn,
  value: unknown,
) {
  setColumns(
    columns.value.map((column, row) =>
      row === index ? { ...column, [key]: value, ...(key === 'key' && descriptor.value?.columnMode === 'redis' && 'redisField' in column ? { redisField: value } : {}) } : { ...column },
    ),
  );
}
function closeColumnEditor() {
  columnEditorOpen.value = false;
  addingColumn.value = false;
  columnEditorErrors.value = [];
  editedColumn = undefined;
  editedConfig = undefined;
  columnDraft.value = { name: '', type: '' };
}
function editColumn(index: number) {
  if (props.disabled || !columns.value[index]) return;
  editedColumn = columns.value[index];
  editedConfig = props.config;
  addingColumn.value = false;
  columnDraft.value = { ...editedColumn, ...(showKey.value ? { key: columnKey(editedColumn) } : {}) };
  columnEditorErrors.value = [];
  columnEditorOpen.value = true;
}
function saveColumnEditor() {
  if (props.disabled || editedConfig !== props.config || (!addingColumn.value && !editedColumn))
    return closeColumnEditor();
  const index = addingColumn.value ? columns.value.length : columns.value.indexOf(editedColumn!);
  if (index === -1) {
    columnEditorErrors.value = ['该字段已变更，请关闭后重新修改'];
    return;
  }
  const errors = validateConnectorColumn(
    columnDraft.value,
    descriptor.value!.columnMode,
    index,
    props.config.dataType,
  );
  if (
    columns.value.some(
      (column) =>
        column !== editedColumn && column.name === columnDraft.value.name,
    )
  )
    errors.push(`字段${columnDraft.value.name}重复`);
  columnEditorErrors.value = errors;
  if (errors.length > 0) return;
  const saved = { ...columnDraft.value, ...(descriptor.value?.columnMode === 'redis' && 'redisField' in columnDraft.value ? { redisField: columnDraft.value.key } : {}) };
  setColumns(addingColumn.value
    ? [...columns.value, saved]
    : columns.value.map((column) => column === editedColumn ? saved : { ...column }));
  closeColumnEditor();
}
function updateColumnDraft(field: ReferenceField) {
  columnDraft.value = {
    ...field,
    type: field.type ?? '',
    length: field.length ?? undefined,
    precision: field.precision ?? undefined,
    ...(descriptor.value?.columnMode === 'redis' && 'redisField' in field ? { redisField: field.key } : {}),
  };
}
function addColumn() {
  if (props.disabled) return;
  if (
    issues.value.some((issue) => issue.field === 'columns') &&
    columns.value.length > 0
  ) {
    showErrors.value = true;
    message.warning('请先完善已有属性字段，再新增字段');
    return;
  }
  setColumns([
    ...columns.value,
    { name: '', type: 'STRING', index: columns.value.length, key: '' },
  ]);
}
function removeColumn(index: number) {
  if (props.disabled) return;
  if (descriptor.value?.requiresColumns && columns.value.length === 1) {
    message.warning('请至少保留一个属性字段');
    return;
  }
  const draft = props.config;
  const row = columns.value[index];
  Modal.confirm({
    title: '确定要删除这条数据吗？',
    okText: '确定',
    cancelText: '取消',
    onOk: () => {
      if (
        props.disabled ||
        props.config !== draft ||
        !row ||
        !columns.value.includes(row)
      )
        return;
      setColumns(columns.value.filter((column) => column !== row));
    },
  });
}
function fromUpstream(
  mode: 'addAll' | 'addNewOnly' | 'clearAndAddAll' = 'addAll',
) {
  if (props.disabled) return;
  const merged = resolveReferenceFields(
    columns.value,
    [],
    props.inputFields ?? [],
    mode,
  );
  const facts = new Map(
    (props.inputFields ?? []).map((field) => [field.name, field]),
  );
  setColumns(
    merged.map((field, index) => ({
      ...field,
      name: field.name,
      type: field.type ?? '',
      key: field.name,
      index: typeof field.index === 'number' ? field.index : index,
      length: field.length ?? facts.get(field.name)?.length ?? undefined,
      precision: field.precision ?? facts.get(field.name)?.scale ?? undefined,
    })),
  );
}
function moveColumn(from: number, to: number) {
  if (props.disabled) return;
  setColumns(moveReferenceField(columns.value, from, to));
}
async function loadDatabases() {
  const current = request.begin();
  if (!showPlatformConnection.value) {
    databases.value = [];
    return;
  }
  loading.value = true;
  failed.value = false;
  try {
    const result = await getDatabaseSimpleList();
    if (current()) databases.value = result;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
watch(
  () => [
    props.toolCode,
    props.config,
    props.disabled,
    access.accessToken,
    access.refreshToken,
  ],
  closeColumnEditor,
);
watch(
  () => [props.toolCode, props.mode, props.config, props.disabled],
  () => {
    showErrors.value = false;
    if (props.disabled) return;
    for (const [key, value] of Object.entries(
      connectorDefaults(props.toolCode, props.mode),
    ))
      if (props.config[key] === undefined || props.config[key] === null)
        props.config[key] = value;
  },
  { immediate: true },
);
watch(
  () => [props.toolCode, access.accessToken, access.refreshToken],
  () => {
    void loadDatabases();
  },
  { immediate: true },
);
watch(
  () => [props.config, props.toolCode],
  () => {
    metadataEpoch.value++;
  },
  { flush: 'sync' },
);
watch(
  () => [
    props.toolCode,
    props.config.databaseId,
    metadataEpoch.value,
    access.accessToken,
    access.refreshToken,
  ],
  () => {
    void loadTables();
  },
  { immediate: true },
);
defineExpose({ validate });
</script>

<template>
  <div
    v-if="descriptor"
    class="qi-connector-editor qi-connector-reference"
    :data-tool-code="toolCode"
  >
<Alert
      v-if="toolCode === 'read-csv' && config.fileUrl"
      type="warning"
      message="该历史节点仍保存了文件地址。运行需选择已授权的执行资源，请移除旧地址并填写资源编号与相对路径。"
      show-icon
    >
      <template #action>
        <Button size="small" @click="changeField('fileUrl', undefined)">
移除旧地址
</Button>
      </template>
    </Alert>
    <Alert
      v-if="legacyTdengineKeys.length"
      type="warning"
      :message="mode === 'OFFLINE' ? '当前为表快照，持续订阅或SQL参数仍保留。请明确清理后选择快照表。' : '历史表读取参数不适用于持续订阅。原配置仍保留，请明确清理后配置订阅主题和消费组。'"
      show-icon
    >
      <template #action>
<Button
          size="small"
          :disabled="disabled"
          @click="clearLegacyTdengineConfig"
          >
{{ mode === 'OFFLINE' ? '转换为表快照配置' : '清除历史表读取参数' }}
</Button>
</template>
    </Alert>
    <Alert
      v-if="
        ['read-ftp', 'write-ftp'].includes(toolCode) &&
        config.databaseId !== undefined &&
        config.databaseId !== null
      "
      type="info"
      :message="toolCode === 'read-ftp' ? '已关联平台数据连接。执行仍使用当前任务授权的文件资源与凭据引用。' : '历史数据连接仍保留。执行资源可独立授权；若资源未关联该连接，请明确移除旧关联。'"
      show-icon
    >
      <template #action>
<Button
          size="small"
          :disabled="disabled"
          @click="changeField('databaseId', undefined)"
          >
移除历史数据连接
</Button>
</template>
    </Alert>
    <Alert v-if="failed" type="error" message="数据连接加载失败" show-icon>
      <template #action>
        <Button size="small" @click="loadDatabases">重试</Button>
      </template>
    </Alert>
    <div class="qi-connector-reference-main">
      <div v-if="tdengineRealtime" class="qi-connector-field"><label>连接方式</label><RadioGroup :value="0" :options="[{ label: '数据连接', value: 0 }]" :disabled="disabled" /></div>
      <ConnectorReferenceFields
        :config="config"
        :fields="modeFields"
        :disabled="disabled"
        :issues="showErrors ? issues : []"
        @change="changeReferenceField"
      />
      <div class="qi-connector-grid">
        <div v-if="showPlatformConnection" class="qi-connector-field">
          <label><em v-if="descriptor.connection">*</em>{{
              tdengineRealtime ? '源数据库连接' : descriptor.direction === 'input' ? '数据源连接' : '目标数据连接'
            }}</label>
          <div>
            <Select
              :value="
                config.databaseId === undefined || config.databaseId === null
                  ? undefined
                  : String(config.databaseId)
              "
              :options="options"
              :disabled="disabled"
              :loading="loading"
              :status="error('databaseId') ? 'error' : undefined"
              show-search
              option-filter-prop="label"
              allow-clear
              placeholder="请选择平台数据连接"
              aria-label="连接器数据连接"
              @update:value="selectDatabase"
            /><small v-if="error('databaseId')">{{
              error('databaseId')
            }}</small>
          </div>
        </div>
        <ConnectorReferenceFields
          :config="config"
          :fields="leadingFields"
          :disabled="disabled"
          :issues="showErrors ? issues : []"
          :table-options="tableConnector ? tableOptions : undefined"
          :tables-loading="tablesLoading"
          @change="changeReferenceField"
        />
        <template v-if="descriptor.connection && showConnectionDetails">
          <div class="qi-connector-field">
            <label>数据连接类型</label><Input :value="database?.databaseTypeName || '-'" disabled />
          </div>
          <div v-if="toolCode !== 'write-rocketmq'" class="qi-connector-field">
            <label>数据连接实例</label><Input :value="database?.databaseSchema || '-'" disabled />
          </div>
        </template>
        <div v-if="tdengineRealtime" class="qi-connector-field"><label><em>*</em>订阅Topic</label><AutoComplete :value="config.topic" :options="config.topic ? [{ value: config.topic }] : []" :disabled="disabled" aria-label="订阅 Topic" placeholder="请选择或输入订阅Topic" @update:value="(value) => changeField('topic', value)" /></div>
      </div>
      <ConnectorReferenceFields
        :config="config"
        :fields="remainingFields"
        :disabled="disabled"
        :issues="showErrors ? issues : []"
        :table-options="tableConnector ? tableOptions : undefined"
        :tables-loading="tablesLoading"
        @change="changeReferenceField"
      />
    </div>
    <div v-if="tableConnector" class="qi-connector-reference-parse">
      <Button
        :disabled="disabled || !config.tableName || fieldsLoading"
        :loading="fieldsLoading"
        @click="resolveTableFields"
        >
解析
</Button><Button
        v-if="metadataIssue"
        :disabled="tablesLoading"
        @click="loadTables"
        >
重试表目录
</Button><small v-if="metadataIssue">{{ metadataIssue }}</small>
    </div>
    <section v-if="mainProperties" class="qi-connector-reference-properties">
      <div class="qi-connector-section-header">
        <h3>属性字段</h3>
        <Button
          v-if="!selectionOutput && !relationFieldsView"
          class="qi-reference-button"
          :disabled="disabled"
          @click="addReferenceColumn"
          >
<ReferenceIcon name="plus" />新增
</Button>
      </div>
      <ReferenceOutputSelection
        v-if="selectionOutput"
        :config="config"
        :input-fields="inputFields"
        :disabled="disabled"
      />
      <div v-else class="qi-connector-reference-scroll">
        <table class="qi-connector-reference-table" aria-label="属性字段">
          <colgroup>
            <col style="width: 80px" />
            <col />
            <col v-if="relationFieldsView" />
            <col />
            <col v-if="showKey && !relationFieldsView" />
            <col v-if="descriptor.columnMode === 'index'" />
            <template v-if="extendedRelationFields">
<col style="width: 90px" />
              <col style="width: 100px" />
              <col style="width: 100px" />
</template>
            <col v-if="!relationFieldsView" style="width: 240px" />
          </colgroup>
          <thead>
            <tr>
              <th>序号</th>
              <th>{{ relationFieldsView ? '英文名称' : '字段名称' }}</th>
              <th v-if="relationFieldsView">中文名称</th>
              <th>字段类型</th>
              <th v-if="showKey && !relationFieldsView">
                {{ descriptor.columnMode === 'redis' ? '解析值' : 'JSON解析取值' }}
              </th>
              <th v-if="descriptor.columnMode === 'index'">索引</th>
              <template v-if="extendedRelationFields">
<th>是否主键</th>
                <th>字段长度</th>
                <th>小数精度</th>
</template>
              <th v-if="!relationFieldsView">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(column, index) in columns" :key="index">
              <td>{{ index + 1 }}</td>
              <td>{{ column.name || '-' }}</td>
              <td v-if="relationFieldsView">{{ column.comment || '-' }}</td>
              <td>{{ column.type || '-' }}</td>
              <td v-if="showKey && !relationFieldsView">{{ columnKey(column) || '-' }}</td>
              <td v-if="descriptor.columnMode === 'index'">
                {{ column.index ?? '-' }}
              </td>
              <template v-if="extendedRelationFields">
<td>
                  {{
                    column.primaryKey == null
                      ? '-'
                      : column.primaryKey
                        ? '是'
                        : '否'
                  }}
                </td>
                <td>{{ column.length ?? '-' }}</td>
                <td>{{ column.precision ?? '-' }}</td>
</template>
              <td v-if="!relationFieldsView">
                <Button
                  type="link"
                  :disabled="disabled"
                  :aria-label="`修改第${index + 1}行属性字段`"
                  @click="editColumn(index)"
                  >
<ReferenceIcon name="edit" />修改
</Button><Button
                  type="link"
                  danger
                  :disabled="disabled"
                  :aria-label="`删除第${index + 1}行字段`"
                  @click="removeColumn(index)"
                  >
<ReferenceIcon name="delete" />删除
</Button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="!columns.length" class="qi-reference-empty">暂无记录</div>
      </div>
    </section>
    <section v-if="mappingOutput" class="qi-connector-reference-properties">
      <ReferenceOutputMapping
        connector-mode
        :config="config"
        :input-fields="inputFields"
        :target-fields="targetFields"
        :disabled="disabled"
      />
    </section>
    <section
      v-if="descriptor.direction === 'output'"
      class="qi-connector-reference-properties"
    >
      <h3 class="qi-connector-reference-title">输出配置</h3>
      <ConnectorReferenceFields
        :config="config"
        :fields="outputFields"
        :disabled="disabled"
        :issues="showErrors ? issues : []"
        @change="changeField"
      />
    </section>
    <details class="qi-connector-advanced">
      <summary>高级字段配置</summary>
      <section class="qi-connector-columns">
        <div class="qi-connector-section-header">
          <h3>
            {{ descriptor.direction === 'input' ? '属性字段' : '字段映射' }}
          </h3>
          <div>
            <Dropdown
              v-if="descriptor.direction === 'output'"
              :trigger="['click']"
            >
              <Button
                class="qi-reference-button"
                :disabled="disabled || !inputFields?.length"
                >
<ReferenceIcon name="refresh" />获取字段
</Button>
              <template #popupRender>
<Menu>
<MenuItem @click="fromUpstream('clearAndAddAll')">
清空并添加全部字段
</MenuItem><MenuItem @click="fromUpstream('addNewOnly')">
仅添加新增字段
</MenuItem><MenuItem @click="fromUpstream('addAll')">
添加全部字段
</MenuItem>
</Menu>
</template>
</Dropdown><Button class="qi-reference-button" :disabled="disabled" @click="addColumn">
              <ReferenceIcon name="plus" />新增字段
            </Button>
          </div>
        </div>
        <small v-if="error('columns')" class="qi-connector-error">{{
          error('columns')
        }}</small>
        <div class="qi-connector-column-scroll">
          <table>
            <thead>
              <tr>
                <th>序号</th>
                <th>字段名称</th>
                <th>字段类型</th>
                <th v-if="toolCode === 'read-hive'">输出字段名</th>
                <th v-if="descriptor.direction === 'output'">源字段</th>
                <th v-else-if="showKey">
                  {{
                    descriptor.columnMode === 'redis'
                      ? '解析值'
                      : 'JSON解析取值'
                  }}
                </th>
                <th v-if="descriptor.columnMode === 'index'">索引</th>
                <th>字段描述</th>
                <th>字段长度</th>
                <th>小数精度</th>
                <th>格式</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(column, index) in columns"
                :key="index"
                :draggable="!disabled"
                :tabindex="disabled ? -1 : 0"
                :aria-label="`第${index + 1}行属性字段，按Alt加上下方向键调整顺序`"
                @dragstart="draggedColumn = index"
                @dragover.prevent
                @drop.prevent="moveColumn(draggedColumn, index)"
                @dragend="draggedColumn = -1"
                @keydown.alt.up.prevent="moveColumn(index, index - 1)"
                @keydown.alt.down.prevent="moveColumn(index, index + 1)"
              >
                <td>{{ index + 1 }}</td>
                <td>
                  <Input
                    :disabled="disabled"
                    :value="column.name"
                    :aria-label="`第${index + 1}行字段名称`"
                    @update:value="
                      (value) => updateColumn(index, 'name', value)
                    "
                  />
                </td>
                <td>
                  <Select
                    :disabled="disabled"
                    :value="column.type"
                    :options="dataTypes"
                    :aria-label="`第${index + 1}行字段类型`"
                    @update:value="
                      (value) => updateColumn(index, 'type', value)
                    "
                  />
                </td>
                <td v-if="descriptor.direction === 'output' || showKey">
                  <Input
                    :disabled="disabled"
                    :value="columnKey(column)"
                    :aria-label="`第${index + 1}行字段取值`"
                    @update:value="(value) => updateColumn(index, 'key', value)"
                  />
                </td>
                <td v-if="toolCode === 'read-hive'">
                  <Input
                    :disabled="disabled"
                    :value="column.alias"
                    :aria-label="`第${index + 1}行输出字段名`"
                    placeholder="默认保留物理字段名"
                    @update:value="
                      (value) => updateColumn(index, 'alias', value)
                    "
                  />
                </td>
                <td v-if="descriptor.columnMode === 'index'">
                  <InputNumber
                    :disabled="disabled"
                    :value="column.index"
                    :min="0"
                    :precision="0"
                    :aria-label="`第${index + 1}行索引`"
                    @update:value="
                      (value) => updateColumn(index, 'index', value)
                    "
                  />
                </td>
                <td>
                  <Input
                    :disabled="disabled"
                    :value="column.comment"
                    :aria-label="`第${index + 1}行字段描述`"
                    @update:value="
                      (value) => updateColumn(index, 'comment', value)
                    "
                  />
                </td>
                <td>
                  <Input
                    :disabled="disabled"
                    :value="column.length"
                    :aria-label="`第${index + 1}行字段长度`"
                    @update:value="
                      (value) => updateColumn(index, 'length', value)
                    "
                  />
                </td>
                <td>
                  <Input
                    :disabled="disabled"
                    :value="column.precision"
                    :aria-label="`第${index + 1}行小数精度`"
                    @update:value="
                      (value) => updateColumn(index, 'precision', value)
                    "
                  />
                </td>
                <td>
                  <Input
                    :disabled="disabled"
                    :value="column.format"
                    :aria-label="`第${index + 1}行字段格式`"
                    @update:value="
                      (value) => updateColumn(index, 'format', value)
                    "
                  />
                </td>
                <td class="qi-connector-column-actions">
                  <Button
                    type="link"
                    :disabled="disabled"
                    :aria-label="`修改第${index + 1}行属性字段`"
                    @click="editColumn(index)"
                    >
<ReferenceIcon name="edit" />修改
</Button>
                  <Button
                    type="link"
                    danger
                    :aria-label="`删除第${index + 1}行字段`"
                    @click="removeColumn(index)"
                  >
                    <ReferenceIcon name="delete" />删除
                  </Button>
                </td>
              </tr>
            </tbody>
          </table>
          <Empty
            v-if="!columns.length"
            description="暂无字段，请新增字段或从上游节点映射"
          />
        </div>
      </section>
    </details>
    <details class="qi-connector-advanced">
      <summary>执行配置与高级参数</summary>
      <Alert
        type="info"
        :message="executionHint"
        show-icon
      /><ConnectorReferenceFields
        :config="config"
        :fields="advancedFields"
        :disabled="disabled"
        :issues="showErrors ? issues : []"
        @change="changeField"
      />
    </details>
    <ReferenceFieldEditModal
      :open="columnEditorOpen"
      :field="columnDraft"
      :disabled="disabled"
      :errors="columnEditorErrors"
      :type-options="dataTypes"
      :show-key="descriptor.direction === 'output' || showKey"
      :key-label="
        descriptor.direction === 'output'
          ? '源字段'
          : descriptor.columnMode === 'redis'
            ? '解析值'
            : 'JSON解析取值'
      "
      :show-index="descriptor.columnMode === 'index'"
      :show-comment="relationInput"
      :show-metadata="relationInput"
      :show-format="relationInput"
      @update:field="updateColumnDraft"
      @save="saveColumnEditor"
      @cancel="closeColumnEditor"
    />
  </div>
</template>

<style scoped>
.qi-connector-editor {
  display: flex;
  flex-direction: column;
  gap: 20px;
  font-size: 14px;
  color: #333;
}

.qi-connector-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px 24px;
}

.qi-connector-full {
  grid-column: 1 / -1;
}

.qi-connector-field {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr);
  align-items: start;
}

.qi-connector-field > label {
  padding: 5px 12px 0 0;
  text-align: right;
}

.qi-connector-field em {
  margin-right: 4px;
  font-style: normal;
  color: #ff6262;
}

.qi-connector-field small.qi-json-valid {
  color: #6b7280;
}

.qi-connector-column-actions {
  white-space: nowrap;
}

.qi-connector-field small,
.qi-connector-error {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: #ff6262;
}

.qi-connector-field :deep(.ant-select),
.qi-connector-field :deep(.ant-input-number) {
  width: 100%;
}

.qi-connector-section-header {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.qi-connector-section-header > div {
  display: flex;
  gap: 10px;
}

.qi-connector-section-header h3 {
  padding-left: 10px;
  margin: 0;
  font-size: 14px;
  font-weight: 400;
  border-left: 4px solid #2f62ff;
}

.qi-connector-column-scroll {
  overflow: auto;
  border: 1px solid #e2e5ed;
}

.qi-connector-column-scroll table {
  width: 100%;
  min-width: 750px;
  font-size: 13px;
  border-collapse: collapse;
}

.qi-connector-column-scroll th {
  padding: 9px 12px;
  font-weight: 400;
  color: #666;
  text-align: left;
  background: #f1f1f5;
}

.qi-connector-column-scroll td {
  min-width: 100px;
  padding: 8px;
  border-bottom: 1px solid #e2e5ed;
}

.qi-connector-column-scroll td:first-child {
  min-width: 50px;
  text-align: center;
}

.qi-connector-column-scroll :deep(.ant-select),
.qi-connector-column-scroll :deep(.ant-input-number) {
  width: 100%;
}

@media (max-width: 768px) {
  .qi-connector-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 14px;
  }

  .qi-connector-field {
    grid-template-columns: 106px minmax(0, 1fr);
  }

  .qi-connector-section-header {
    flex-wrap: wrap;
  }
}
</style>

<style scoped>
.qi-connector-column-scroll thead th,
.qi-connector-column-scroll tbody td {
  font-size: 12px;
}
</style>

<style scoped>
.qi-connector-reference {
  --qi-connector-label-width: 110px;
}

.qi-connector-reference .qi-connector-field {
  grid-template-columns: var(--qi-connector-label-width) minmax(0, 1fr);
}

.qi-connector-reference-main {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px 20px;
}

.qi-connector-reference-main .qi-connector-grid,
.qi-connector-reference-main :deep(.qi-connector-reference-grid) {
  display: contents;
}

.qi-connector-reference-parse {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: flex-end;
}

.qi-connector-reference-parse small {
  color: #f56c6c;
}

.qi-connector-advanced {
  padding: 10px 12px;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}

.qi-connector-advanced summary {
  margin-bottom: 10px;
  color: #606266;
  cursor: pointer;
}

.qi-connector-advanced[open] {
  display: grid;
  gap: 18px;
}

.qi-connector-reference-scroll {
  max-height: 360px;
  overflow: auto;
}

.qi-connector-reference-table {
  width: 100%;
  min-width: 720px;
  font-size: 14px;
  table-layout: fixed;
  border-collapse: collapse;
}

.qi-connector-reference-table th {
  height: 40px;
  padding: 8px 10px;
  font-weight: 400;
  color: #606266;
  text-align: left;
  background: #f1f1f5;
}

.qi-connector-reference-table td {
  height: 42px;
  padding: 8px 10px;
  color: #606266;
  border-bottom: 1px solid #ebeef5;
}

.qi-connector-reference-table th:last-child,
.qi-connector-reference-table td:last-child {
  position: sticky;
  right: 0;
  background: #fff;
}

.qi-connector-reference-table th:last-child {
  background: #f1f1f5;
}

.qi-connector-reference-title {
  margin: 0 0 16px;
  font-size: 14px;
  font-weight: 400;
  color: #303133;
}

.qi-connector-reference-properties > .qi-connector-section-header {
  flex-direction: column;
  gap: 16px;
  align-items: flex-start;
}

.qi-connector-reference .qi-connector-section-header h3 {
  padding-left: 10px;
  color: #303133;
  border-left: 4px solid #2f62ff;
}
</style>
