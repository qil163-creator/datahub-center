<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 仅编辑父设计器的隔离草稿，统一由父级保存。 */
import type { ReferenceField } from './reference-node-schema';
import type {
  RelationInputCode,
  RelationInputColumn,
} from './relation-input-config';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { IntegrationApi } from '#/api/dataforge/integration';

import { computed, ref, watch } from 'vue';

import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  Input,
  Modal,
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

import { validateConnectorConfig } from './connector-schema';
import DatabaseConnectionPicker from './database-connection-picker.vue';
import ReferenceFieldEditModal from './reference-field-edit-modal.vue';
import {
  moveReferenceField,
  validateReferenceFields,
} from './reference-node-schema';
import {
  createRelationReadConfig,
  RELATION_READ_KEYS,
  relationColumnMetadata,
  relationColumnsFromMetadata,
  relationConnectionMatches,
} from './relation-input-config';
import TableInputReadOptions from './table-input-read-options.vue';
import TableInputSqlEditor from './table-input-sql-editor.vue';

const props = defineProps<{
  config: Record<string, any>;
  disabled?: boolean;
  engine?: string;
  executionReason?: string;
  mode?: string;
  resolveFields?: () => Promise<IntegrationApi.SchemaField[]>;
  toolCode: RelationInputCode;
}>();
const access = useAccessStore();
const epoch = ref(0);
const databases = ref<FoundationDatabaseApi.DatabaseSimple[]>([]);
const tables = ref<FoundationDatabaseApi.DatabaseTable[]>([]);
const metadata = ref<FoundationDatabaseApi.DatabaseTableColumn[]>([]);
const connectionsLoading = ref(false);
const tablesLoading = ref(false);
const fieldsLoading = ref(false);
const connectionError = ref('');
const tableError = ref('');
const fieldError = ref('');
const errors = ref<string[]>([]);
const readOptions = ref<{ validate: () => boolean }>();
const realtime = computed(
  () => props.toolCode === 'read-tdengine' && props.mode === 'REALTIME',
);
const isTdengine = computed(() => props.toolCode === 'read-tdengine');
const isHive = computed(() => props.toolCode === 'read-hive');
const isOdps = computed(() => props.toolCode === 'read-odps');
const isMongo = computed(() => props.toolCode === 'read-mongodb');
// ODPS, Hive, MongoDB and TDengine follow the qData reference dialog layout.
const referenceRelation = computed(
  () => isOdps.value || isHive.value || isMongo.value || isTdengine.value,
);
// Hive and TDengine share the SQL toolbar above the editor.
const sqlToolbar = computed(() => isHive.value || isTdengine.value);
const tablePlaceholder = computed(() => {
  if (isOdps.value || isTdengine.value) return '请选择表';
  return isHive.value || isMongo.value ? '请选择' : '请选择表或视图';
});
const sortDirection = (key: 'length' | 'scale') =>
  fieldSort.value?.key === key ? fieldSort.value.direction : 0;
const sqlMode = computed(() => props.config.ifSql === 1);
const sqlAllowed = computed(
  () => !realtime.value && props.toolCode !== 'read-mongodb',
);
const readConfig = computed(() => createRelationReadConfig(props.config));
// Reference layouts have no manual field or resource editing; keep those
// sections reachable only for saved configs that already use them.
const legacyAdvanced = computed(
  () =>
    (isMongo.value || isTdengine.value) &&
    ((props.config.resourceBindingId !== undefined &&
      props.config.resourceBindingId !== '') ||
      (Array.isArray(props.config.columns) &&
        props.config.columns.some(
          (column: RelationInputColumn) =>
            typeof column.key === 'string' && column.key.trim() !== '',
        ))),
);
const wherePlaceholder = computed(() => {
  if (props.toolCode === 'read-odps') return '例如 id > 10，请不要以分号;结尾';
  if (isHive.value || isMongo.value || isTdengine.value)
    return '例如 id > 10 and id < 1000，请不要以分号;结尾';
  return undefined;
});
const columns = computed<RelationInputColumn[]>(() =>
  Array.isArray(props.config.columns) ? props.config.columns : [],
);
const database = computed(() =>
  databases.value.find(
    (item) => String(item.id) === String(props.config.databaseId),
  ),
);
const compatibleDatabases = computed(() =>
  databases.value.filter((item) =>
    relationConnectionMatches(props.toolCode, item),
  ),
);
const tableOptions = computed(() => {
  const options = tables.value.map((table) => {
    let suffix = '';
    if (table.viewTable) suffix = '（视图）';
    else if (table.remarks) suffix = `（${table.remarks}）`;
    return { value: table.tableName, label: `${table.tableName}${suffix}` };
  });
  if (
    props.config.tableName &&
    !options.some((item) => item.value === props.config.tableName)
  )
    options.unshift({
      value: props.config.tableName,
      label: `${props.config.tableName}（当前保存值）`,
    });
  return options;
});
const readFields = computed(() =>
  (isHive.value && sqlMode.value
    ? [...columns.value, ...(Array.isArray(tableDraft.columns) ? tableDraft.columns as RelationInputColumn[] : [])]
        .filter((column, index, list) => list.findIndex((item) => item.name === column.name) === index)
    : columns.value).map((column) => {
    const actual = metadata.value.find(
      (field) => field.fieldName === column.name,
    );
    return actual ?? relationColumnMetadata(column);
  }),
);
const fieldSort = ref<{ direction: -1 | 1; key: 'length' | 'scale'; }>();
const displayedFields = computed(() =>
  columns.value
    .map((column, index) => ({
      ...column,
      ordinal: index + 1,
      metadata: metadata.value.find((field) => field.fieldName === column.name),
    }))
    .toSorted((a, b) => {
      const sort = fieldSort.value;
      if (!sort) return 0;
      const left = a.metadata?.[sort.key];
      const right = b.metadata?.[sort.key];
      if (typeof left !== 'number') return typeof right === 'number' ? 1 : 0;
      if (typeof right !== 'number') return -1;
      if (left === right) return 0;
      return (left < right ? -1 : 1) * sort.direction;
    }),
);
let tableDraft: Record<string, unknown> = {};
let sqlDraft: Record<string, unknown> = {};
const connectionRequest = useObjectRequest(
  () => `${epoch.value}/${props.toolCode}`,
  () => {
    databases.value = [];
    connectionsLoading.value = false;
    connectionError.value = '';
  },
);
const tableRequest = useObjectRequest(
  () =>
    `${epoch.value}/${props.toolCode}/${props.config.databaseId ?? ''}/${realtime.value}`,
  () => {
    tables.value = [];
    tablesLoading.value = false;
    tableError.value = '';
  },
);
const fieldRequest = useObjectRequest(
  () =>
    `${epoch.value}/${props.toolCode}/${props.config.databaseId ?? ''}/${props.config.tableName ?? ''}/${props.config.ifSql ?? 0}/${props.config.sql ?? ''}/${props.config.topic ?? ''}`,
  () => {
    metadata.value = [];
    fieldsLoading.value = false;
    fieldError.value = '';
  },
);

async function loadConnections() {
  const current = connectionRequest.begin();
  connectionsLoading.value = true;
  connectionError.value = '';
  try {
    const result = await getDatabaseSimpleList();
    if (current()) databases.value = result;
  } catch {
    if (current()) connectionError.value = '数据连接加载失败，请重试';
  } finally {
    if (current()) connectionsLoading.value = false;
  }
}
async function loadTables() {
  const current = tableRequest.begin();
  tablesLoading.value = false;
  tableError.value = '';
  tables.value = [];
  if (!props.config.databaseId || realtime.value) return;
  tablesLoading.value = true;
  try {
    const result = await getDatabaseTableList(props.config.databaseId, {
      silentError: true,
    });
    if (current()) tables.value = result;
  } catch {
    if (current())
      tableError.value = '表与视图目录加载失败，当前保存值仍保留。';
  } finally {
    if (current()) tablesLoading.value = false;
  }
}
async function loadMetadata(replace = false) {
  const current = fieldRequest.begin();
  metadata.value = [];
  fieldsLoading.value = false;
  fieldError.value = '';
  if (
    !props.config.databaseId ||
    !props.config.tableName ||
    sqlMode.value ||
    realtime.value
  )
    return;
  fieldsLoading.value = true;
  try {
    const result = await getDatabaseTableMeta(
      props.config.databaseId,
      props.config.tableName,
      { silentError: true },
    );
    if (!current()) return;
    metadata.value = result.columns ?? [];
    if (replace && !props.disabled)
      props.config.columns = relationColumnsFromMetadata(
        props.toolCode,
        metadata.value,
        columns.value,
      );
    if (metadata.value.length === 0)
      fieldError.value = '当前表没有返回可确认的属性字段。';
  } catch {
    if (current())
      fieldError.value = '属性字段加载失败，已有字段仍保留，请重试。';
  } finally {
    if (current()) fieldsLoading.value = false;
  }
}
function clearReadSelection() {
  for (const key of [
    'incrementColumn',
    'dateColumn',
    'timeConditions',
    'cursorTime',
  ])
    Reflect.deleteProperty(props.config, key);
}
function changeDatabase(value: string | undefined) {
  if (
    props.disabled ||
    String(value ?? '') === String(props.config.databaseId ?? '')
  )
    return;
  props.config.databaseId = value;
  Reflect.deleteProperty(props.config, 'tableName');
  Reflect.deleteProperty(props.config, 'topic');
  props.config.columns = [];
  clearReadSelection();
  tableDraft = {};
  sqlDraft = {};
}
function selectTable(value: unknown) {
  if (value !== undefined && value !== null && typeof value !== 'string') return;
  if (props.disabled || value === props.config.tableName) return;
  props.config.tableName = value;
  props.config.columns = [];
  clearReadSelection();
  void loadMetadata(true);
}
function changeSqlMode(value: number) {
  if (
    props.disabled ||
    (value === 1 && !sqlAllowed.value) ||
    (value === 1) === sqlMode.value
  )
    return;
  // Hive 的增量配置在参考 SQL 分支仍可编辑；它属于两种连接方式共用的读取草稿。
  const tableKeys = ['tableName', 'columns', ...(isHive.value ? ['whereCondition'] : RELATION_READ_KEYS)];
  const sqlKeys = ['sql', 'columns'];
  const outgoing = sqlMode.value ? sqlKeys : tableKeys;
  const draft = Object.fromEntries(
    outgoing
      .filter((key) => props.config[key] !== undefined)
      .map((key) => [key, JSON.parse(JSON.stringify(props.config[key]))]),
  );
  if (sqlMode.value) sqlDraft = draft;
  else tableDraft = draft;
  for (const key of outgoing) Reflect.deleteProperty(props.config, key);
  Object.assign(props.config, value === 1 ? sqlDraft : tableDraft);
  if (isTdengine.value && value === 0)
    Reflect.deleteProperty(props.config, 'ifSql');
  else props.config.ifSql = value;
  metadata.value = [];
  if (value === 0) void loadMetadata();
}
function setSql(value: string) {
  if (props.disabled) return;
  props.config.sql = value;
  props.config.columns = [];
}
async function parseSql() {
  if (props.disabled || fieldsLoading.value) return;
  if (!props.config.databaseId || !props.config.sql?.trim()) {
    fieldError.value = '请选择源数据库连接并填写查询SQL';
    return;
  }
  if (!props.resolveFields) {
    fieldError.value = '当前组件尚未提供受管SQL字段解析，已有SQL草稿仍保留。';
    return;
  }
  const current = fieldRequest.begin();
  fieldsLoading.value = true;
  fieldError.value = '';
  try {
    const result = await props.resolveFields();
    if (!current() || props.disabled) return;
    if (
      result.length === 0 ||
      result.some((field) => !field.name || !field.dataType)
    ) {
      fieldError.value =
        '未返回可确认的SQL字段，请检查当前组件与数据源是否支持该查询。';
      return;
    }
    props.config.columns = result.map((field) => ({
      name: field.name,
      type: field.dataType,
    }));
  } catch (error) {
    if (current())
      fieldError.value =
        error instanceof Error ? error.message : 'SQL解析失败，请检查查询语句';
  } finally {
    if (current()) fieldsLoading.value = false;
  }
}
function sortFields(key: 'length' | 'scale') {
  if (fieldSort.value?.key !== key) fieldSort.value = { key, direction: 1 };
  else if (fieldSort.value.direction === 1)
    fieldSort.value = { key, direction: -1 };
  else fieldSort.value = undefined;
}
function sortState(key: 'length' | 'scale') {
  if (fieldSort.value?.key !== key) return 'none';
  return fieldSort.value.direction === 1 ? 'ascending' : 'descending';
}
function set(key: string, value: unknown) {
  if (props.disabled) return;
  if (
    (key === 'resourceBindingId' && !value) ||
    (isTdengine.value && key === 'whereCondition' && !value)
  )
    Reflect.deleteProperty(props.config, key);
  else props.config[key] = value;
}

const fieldEditorOpen = ref(false);
const fieldDraft = ref<ReferenceField>({ name: '' });
const fieldErrors = ref<string[]>([]);
let editingColumn: RelationInputColumn | undefined;
let editingConfig: Record<string, any> | undefined;
function openField(index?: number) {
  if (props.disabled) return;
  editingConfig = props.config;
  editingColumn = index === undefined ? undefined : columns.value[index];
  fieldDraft.value = editingColumn ? { ...editingColumn } : { name: '' };
  fieldErrors.value = [];
  fieldEditorOpen.value = true;
}
function saveField() {
  if (
    props.disabled ||
    !fieldEditorOpen.value ||
    props.config !== editingConfig
  )
    return;
  if (editingColumn && !columns.value.includes(editingColumn)) {
    fieldErrors.value = ['该字段已变更，请关闭后重新编辑'];
    return;
  }
  const next = editingColumn
    ? columns.value.map((column) =>
        column === editingColumn ? { ...fieldDraft.value } : column,
      )
    : [...columns.value, { ...fieldDraft.value }];
  fieldErrors.value = validateReferenceFields(next);
  if (fieldErrors.value.length > 0) return;
  props.config.columns = next;
  fieldEditorOpen.value = false;
}
function removeField(index: number) {
  if (props.disabled) return;
  if (columns.value.length === 1) {
    errors.value = ['请至少保留一个属性字段'];
    return;
  }
  const context = props.config;
  const field = columns.value[index];
  Modal.confirm({
    title: '确定要删除这条数据吗？',
    okText: '确定',
    cancelText: '取消',
    onOk: () => {
      if (!props.disabled && props.config === context)
        props.config.columns = columns.value.filter(
          (column) => column !== field,
        );
    },
  });
}
function moveField(index: number, offset: number) {
  if (!props.disabled)
    props.config.columns = moveReferenceField(
      columns.value,
      index,
      index + offset,
    );
}
function validate() {
  const messages: string[] = [];
  if (!/^[1-9]\d*$/u.test(String(props.config.databaseId ?? '')))
    messages.push('请选择源数据库连接');
  messages.push(
    ...validateConnectorConfig(props.toolCode, props.config, props.mode)
      .filter((issue) => issue.field === 'credentials')
      .map((issue) => issue.message),
  );
  if (
    database.value &&
    !relationConnectionMatches(props.toolCode, database.value)
  )
    messages.push('当前数据连接类型与输入组件不匹配');
  if (connectionsLoading.value || fieldsLoading.value)
    messages.push('请等待当前加载或解析完成');
  if (realtime.value) {
    if (!props.config.topic?.trim()) messages.push('请输入订阅Topic');
    if (!/^[A-Za-z0-9._-]{1,128}$/u.test(props.config.groupId ?? ''))
      messages.push('消费组ID仅支持1至128位字母、数字、点、下划线和短横线');
    if (
      !['earliest', 'latest'].includes(props.config.offsetReset ?? 'earliest')
    )
      messages.push('请选择消费起始位置');
  } else if (sqlMode.value) {
    if (!props.config.sql?.trim()) messages.push('请输入SQL语句');
  } else {
    if (!props.config.tableName?.trim()) messages.push('请选择表或视图');
    if (!isTdengine.value && !(readOptions.value?.validate() ?? true))
      messages.push('请完善读取模式配置');
    if (
      !isTdengine.value &&
      props.config.readMode === 'ID_INCREMENT' &&
      !/^[+-]?\d+$/u.test(String(readConfig.value.startValue ?? ''))
    )
      messages.push('开始值必须为整数');
  }
  if (
    !sqlMode.value &&
    !realtime.value &&
    props.toolCode !== 'read-mongodb' &&
    typeof props.config.whereCondition === 'string' &&
    /;|--|\/\*|\b(?:select|where|insert|update|delete|drop|alter|truncate|create|grant|revoke|merge|call)\b/iu.test(
      props.config.whereCondition,
    )
  )
    messages.push('where条件仅支持单条只读过滤表达式');
  if (
    props.toolCode === 'read-mongodb' &&
    !sqlMode.value &&
    props.config.whereCondition?.trim()
  ) {
    try {
      const query: unknown = JSON.parse(props.config.whereCondition);
      if (!query || typeof query !== 'object' || Array.isArray(query))
        throw new Error('查询条件');
    } catch {
      messages.push('MongoDB查询条件必须为有效的JSON对象');
    }
  }
  messages.push(...validateReferenceFields(columns.value));
  errors.value = messages;
  return messages.length === 0;
}
watch(
  () => props.config,
  () => {
    epoch.value++;
    tableDraft = {};
    sqlDraft = {};
    fieldEditorOpen.value = false;
    errors.value = [];
    fieldSort.value = undefined;
  },
  { immediate: true, flush: 'sync' },
);
watch(
  () => [props.disabled, access.accessToken, access.refreshToken],
  () => {
    fieldEditorOpen.value = false;
  },
);
watch(() => [access.accessToken, access.refreshToken], () => {
  tableDraft = {};
  sqlDraft = {};
});
watch(
  () => [props.toolCode, epoch.value, access.accessToken, access.refreshToken],
  () => {
    void loadConnections();
  },
  { immediate: true },
);
watch(
  () => [
    props.toolCode,
    epoch.value,
    props.config.databaseId,
    realtime.value,
    access.accessToken,
    access.refreshToken,
  ],
  () => {
    void loadTables();
    void loadMetadata();
  },
  { immediate: true },
);
defineExpose({ validate });
</script>

<template>
  <div class="qi-relation-input" :data-tool-code="toolCode">
    <Alert
      v-if="connectionError"
      :message="connectionError"
      type="error"
      show-icon
    >
      <template #action>
<Button size="small" @click="loadConnections">重试</Button>
</template>
    </Alert>
    <Alert
      v-if="errors.length"
      :message="errors.join('；')"
      type="error"
      show-icon
    />
    <div class="qi-relation-grid">
      <div class="qi-relation-field qi-relation-full qi-relation-mode">
        <label>连接方式</label>
        <RadioGroup
          :value="sqlMode ? 1 : 0"
          :disabled="disabled"
          :options="[
            { label: '数据连接', value: 0 },
            ...(sqlAllowed || sqlMode
              ? [{ label: referenceRelation ? 'sql' : 'SQL', value: 1 }]
              : []),
          ]"
          aria-label="连接方式"
          @update:value="changeSqlMode"
        />
      </div>
      <div class="qi-relation-field">
        <label><em>*</em>源数据库连接</label>
        <DatabaseConnectionPicker
          :value="config.databaseId"
          :databases="compatibleDatabases"
          :loading="connectionsLoading"
          :disabled="disabled"
          :placeholder="toolCode === 'read-odps' ? '请选择ODPS数据源' : '请选择源数据库连接'"
          :type-codes="
            isMongo ? ['MONGODB'] : isTdengine ? ['TDENGINE'] : undefined
          "
          @change="changeDatabase"
        />
      </div>
      <div class="qi-relation-field">
        <label>数据连接类型</label><Input
          :value="database?.databaseTypeName || database?.productId || (referenceRelation ? undefined : '-')"
          :placeholder="referenceRelation ? '请输入数据连接类型' : undefined"
          disabled
        />
      </div>
      <div class="qi-relation-field">
        <label>数据连接实例</label><Input :value="database?.databaseSchema || undefined" :placeholder="toolCode === 'read-odps' ? '自动回显项目名' : isHive || isMongo || isTdengine ? '请输入数据连接实例' : '自动回显实例'" disabled />
      </div>
      <div v-if="sqlMode && sqlToolbar" class="qi-relation-sql-toolbar">
        <Button
          type="primary"
          class="qi-relation-parse-button"
          :auto-insert-space="false"
          :disabled="disabled || !config.databaseId || !config.sql?.trim()"
          :loading="fieldsLoading"
          @click="parseSql"
        >
SQL解析
</Button>
      </div>
      <div v-if="!sqlMode && !realtime" class="qi-relation-field">
        <label><em>*</em>{{ referenceRelation ? '选择表' : '选择表/视图' }}</label>
        <Select
          :value="config.tableName"
          :options="tableOptions"
          :loading="tablesLoading"
          :disabled="disabled || (!config.databaseId && !referenceRelation)"
          :suffix-icon="referenceRelation ? null : undefined"
          allow-clear
          show-search
          option-filter-prop="label"
          :placeholder="tablePlaceholder"
          aria-label="选择表/视图"
          @update:value="selectTable"
        />
      </div>
      <div v-if="realtime" class="qi-relation-field">
        <label><em>*</em>订阅Topic</label><Input
          :value="config.topic"
          :disabled="disabled || !config.databaseId"
          placeholder="请输入订阅Topic"
          aria-label="订阅Topic"
          @update:value="(value) => set('topic', value)"
        />
      </div>
      <div v-if="sqlMode" class="qi-relation-field qi-relation-full">
        <label><em>*</em>SQL语句</label>
        <div class="qi-relation-sql">
          <TableInputSqlEditor
            :model-value="config.sql"
            :readonly="disabled"
            :height="toolCode === 'read-odps' || sqlToolbar ? '140px' : '190px'"
            :folding="!referenceRelation"
            :placeholder="isTdengine ? 'select * FROM log_table where id>${id}' : undefined"
            :render-validation-decorations="referenceRelation ? 'off' : undefined"
            :class="{ 'qi-relation-reference-sql': referenceRelation }"
            aria-label="SQL语句"
            @update:model-value="setSql"
          />
          <div v-if="!sqlToolbar" class="qi-relation-sql-actions">
            <Button
              :disabled="disabled || !config.databaseId || !config.sql?.trim()"
              :loading="fieldsLoading"
              @click="parseSql"
              >
SQL解析
</Button>
          </div>
        </div>
      </div>
    </div>
    <div v-if="tableError" class="qi-relation-status" role="alert">
      <span>{{ tableError }}</span><Button size="small" :loading="tablesLoading" @click="loadTables">
重试表目录
</Button>
    </div>
    <div v-if="fieldError" class="qi-relation-status" role="alert">
      <span>{{ fieldError }}</span><Button
        v-if="!sqlMode && !realtime"
        size="small"
        :loading="fieldsLoading"
        @click="loadMetadata(true)"
        >
重试属性字段
</Button>
    </div>
    <TableInputReadOptions
      v-if="!isTdengine && (!sqlMode || (isHive && (config.readMode ?? 'FULL') !== 'FULL'))"
      ref="readOptions"
      :config="readConfig"
      :fields="readFields"
      :disabled="disabled"
      :batch-enabled="false"
      :reference-layout="referenceRelation"
      :condition-modal-width="referenceRelation ? 912 : 600"
      :condition-table-height="referenceRelation ? 310 : undefined"
      :fixed-baseline-width="referenceRelation ? 220 : undefined"
      :show-read-mode="!sqlMode"
      :show-where="!sqlMode"
      :show-start-required="!isHive && !isMongo"
      :time-format-width="isHive || isMongo ? 240 : undefined"
      time-increment-label="时间范围增"
      :where-placeholder="wherePlaceholder"
    />
    <div
      v-if="!sqlMode && isTdengine && !realtime"
      class="qi-relation-field qi-relation-where"
    >
      <label>where条件</label><TextArea
        :value="config.whereCondition"
        :disabled="disabled"
        :rows="4"
        :maxlength="256"
        :show-count="{
          formatter: ({ count, maxLength }) =>
            `${count} / ${maxLength ?? 256}个字符`,
        }"
        aria-label="where条件"
        :placeholder="wherePlaceholder"
        @update:value="(value) => set('whereCondition', value)"
      />
    </div>
    <div v-if="realtime" class="qi-relation-grid qi-relation-streaming">
      <div class="qi-relation-field">
        <label><em>*</em>消费组ID</label><Input
          :value="config.groupId"
          :disabled="disabled"
          aria-label="消费组ID"
          @update:value="(value) => set('groupId', value)"
        />
      </div>
      <div class="qi-relation-field">
        <label>消费起始位置</label><Select
          :value="config.offsetReset ?? 'earliest'"
          :disabled="disabled"
          :options="[
            { label: '从最早可消费位置开始', value: 'earliest' },
            { label: '从任务启动后的新数据开始', value: 'latest' },
          ]"
          aria-label="消费起始位置"
          @update:value="(value) => set('offsetReset', value)"
        />
      </div>
    </div>
    <section v-if="!referenceRelation || (isOdps && sqlMode) || (config.readMode ?? 'FULL') === 'FULL'" class="qi-relation-properties">
      <h3>属性字段</h3>
      <Spin :spinning="fieldsLoading">
        <div class="qi-relation-table-scroll">
          <table class="qi-relation-table" aria-label="关系输入属性字段">
            <thead>
              <tr>
                <th>序号</th>
                <th>英文名称</th>
                <th>中文名称</th>
                <th>字段类型</th>
                <template v-if="toolCode !== 'read-odps'">
<th>是否主键</th>
                  <th :aria-sort="sortState('length')">
                    <button type="button" @click="sortFields('length')">
                      {{ isMongo ? '长度' : '字段长度' }}
                      <span
                        v-if="referenceRelation"
                        class="qi-sort-caret"
                        :data-sort="sortDirection('length')"
                        aria-hidden="true"
                      ></span>
                      <template v-else>↕</template>
                    </button>
                  </th>
                  <th :aria-sort="sortState('scale')">
                    <button type="button" @click="sortFields('scale')">
                      小数精度
                      <span
                        v-if="referenceRelation"
                        class="qi-sort-caret"
                        :data-sort="sortDirection('scale')"
                        aria-hidden="true"
                      ></span>
                      <template v-else>↕</template>
                    </button>
                  </th>
</template>
              </tr>
            </thead>
            <tbody>
              <tr v-for="field in displayedFields" :key="field.name">
                <td>{{ field.ordinal }}</td>
                <td>{{ field.name }}</td>
                <td>{{ field.metadata?.remarks || field.comment || '-' }}</td>
                <td>{{ field.type || '-' }}</td>
                <template v-if="toolCode !== 'read-odps'">
<td>
                    <Switch
                      v-if="field.metadata?.pk !== undefined"
                      :checked="field.metadata.pk"
                      disabled
                    /><span v-else>-</span>
                  </td>
                  <td>{{ field.metadata?.length ?? field.length ?? '-' }}</td>
                  <td>
                    {{ field.metadata?.scale ?? field.precision ?? '-' }}
                  </td>
</template>
              </tr>
            </tbody>
          </table>
          <div v-if="!columns.length" class="qi-relation-empty">
            <img
              src="/assets/qdata-integration/node-empty-reference.png"
              alt=""
              aria-hidden="true"
            /><span>暂无记录</span>
          </div>
        </div>
      </Spin>
    </section>
    <details v-if="!referenceRelation || legacyAdvanced" class="qi-relation-advanced">
      <summary>高级字段配置</summary>
      <Button v-if="!sqlMode && !realtime" :disabled="disabled || !config.tableName" :loading="fieldsLoading" @click="loadMetadata(true)">解析属性字段</Button>
      <Button :disabled="disabled" @click="openField()">+ 新增字段</Button>
      <div class="qi-relation-table-scroll">
        <table class="qi-relation-table" aria-label="高级属性字段">
          <thead>
            <tr>
              <th>序号</th>
              <th>字段名称</th>
              <th v-if="toolCode === 'read-hive'">输出字段名</th>
              <th>类型</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(column, index) in columns" :key="index">
              <td>{{ index + 1 }}</td>
              <td>{{ column.name }}</td>
              <td v-if="toolCode === 'read-hive'">
                <Input
                  :value="column.alias || column.name"
                  :disabled="disabled"
                  :aria-label="`第${index + 1}行输出字段名`"
                  @update:value="
                    (value) => {
                      if (!disabled) column.alias = value;
                    }
                  "
                />
              </td>
              <td>{{ column.type || '-' }}</td>
              <td class="qi-relation-actions">
                <Button
                  type="link"
                  :disabled="disabled"
                  @click="openField(index)"
                  >
修改
</Button><Button
                  type="link"
                  :disabled="disabled"
                  @click="removeField(index)"
                  >
删除
</Button><Button
                  type="link"
                  :disabled="disabled || index === 0"
                  :aria-label="`上移第${index + 1}行字段`"
                  @click="moveField(index, -1)"
                  >
上移
</Button><Button
                  type="link"
                  :disabled="disabled || index === columns.length - 1"
                  :aria-label="`下移第${index + 1}行字段`"
                  @click="moveField(index, 1)"
                  >
下移
</Button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>
    <details v-if="!referenceRelation || legacyAdvanced" class="qi-relation-advanced">
      <summary>执行配置</summary>
      <div
        v-if="
          isTdengine ||
          toolCode === 'read-mongodb' ||
          config.resourceBindingId !== undefined
        "
        class="qi-relation-field"
      >
        <label>执行资源编号</label><Input
          :value="config.resourceBindingId"
          :disabled="disabled"
          aria-label="执行资源编号"
          @update:value="(value) => set('resourceBindingId', value)"
        />
      </div>
      <p>
        {{
          executionReason ||
          '执行前由任务检查确认当前引擎、数据源授权及读取配置。'
        }}
      </p>
    </details>
    <ReferenceFieldEditModal
      :open="fieldEditorOpen"
      :field="fieldDraft"
      :errors="fieldErrors"
      :disabled="disabled"
      :show-key="toolCode === 'read-mongodb'"
      :show-metadata="!isTdengine"
      :show-format="!isTdengine"
      @update:field="
        (value) => {
          if (!disabled) fieldDraft = value;
        }
      "
      @save="saveField"
      @cancel="fieldEditorOpen = false"
    />
  </div>
</template>

<style scoped>
.qi-relation-input {
  display: grid;
  gap: 16px;
  min-width: 0;
}

.qi-relation-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 28px;
}

.qi-relation-field {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  align-items: start;
  min-width: 0;
}

.qi-relation-field > label {
  padding: 6px 12px 0 0;
  text-align: right;
}

.qi-relation-full {
  grid-column: 1 / -1;
}

.qi-relation-field :deep(.ant-select) {
  width: 100%;
}

.qi-relation-field em {
  margin-right: 4px;
  font-style: normal;
  color: #f56c6c;
}

.qi-relation-sql {
  min-width: 0;
}

.qi-relation-sql-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

.qi-relation-sql-toolbar {
  display: flex;
  justify-content: flex-end;
}

.qi-relation-status {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  color: #f56c6c;
}

.qi-relation-properties {
  min-width: 0;
}

.qi-relation-properties h3 {
  display: flex;
  gap: 9px;
  align-items: center;
  margin: 4px 0 18px;
  font-size: 16px;
  font-weight: 400;
}

.qi-relation-properties h3::before {
  width: 6px;
  height: 16px;
  content: '';
  background: #2d63ff;
  border-radius: 3px;
}

.qi-relation-table-scroll {
  min-width: 0;
  overflow-x: auto;
  border-bottom: 1px solid #e3e5ed;
}

.qi-relation-properties .qi-relation-table-scroll {
  height: 310px;
  overflow: auto;
}

.qi-relation-properties .qi-relation-table th {
  position: sticky;
  top: 0;
  z-index: 1;
}

.qi-relation-table {
  width: 100%;
  text-align: left;
  border-collapse: collapse;
}

.qi-relation-table th {
  padding: 10px;
  font-weight: 600;
  color: #666;
  white-space: nowrap;
  background: #f1f1f5;
}

.qi-relation-table td {
  padding: 16px 10px;
  color: #606266;
  border-bottom: 1px solid #e3e5ed;
}

.qi-relation-table tbody tr:nth-child(even) {
  background: #fafafa;
}

.qi-relation-table th:first-child {
  width: 80px;
}

.qi-relation-table th button {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  padding: 0;
  font: inherit;
  color: inherit;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.qi-relation-empty {
  display: flex;
  flex-direction: column;
  gap: 18px;
  align-items: center;
  justify-content: center;
  min-height: 260px;
  color: #909399;
}

.qi-relation-empty img {
  width: 190px;
  height: auto;
}

.qi-relation-refresh {
  margin-top: 12px;
}

.qi-relation-actions {
  min-width: 220px;
  white-space: nowrap;
}

.qi-relation-advanced {
  min-width: 0;
}

.qi-relation-advanced summary {
  margin: 10px 0;
  cursor: pointer;
}

.qi-relation-advanced .qi-relation-table-scroll {
  margin-top: 10px;
}

.qi-relation-advanced p {
  margin: 8px 0 0;
  color: #909399;
}

/* ODPS reference layout: read options share the connection grid columns. */
.qi-relation-input:is([data-tool-code='read-odps'], [data-tool-code='read-hive'], [data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) :deep(.qi-table-input-grid) {
  gap: 16px 28px;
}

.qi-relation-input:is([data-tool-code='read-odps'], [data-tool-code='read-hive'], [data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) :deep(.qi-table-input-field) {
  grid-template-columns: 110px minmax(0, 1fr);
  gap: 0;
}

.qi-relation-input:is([data-tool-code='read-odps'], [data-tool-code='read-hive'], [data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) :deep(.qi-table-input-field > label) {
  padding-right: 12px;
}

.qi-relation-input:is([data-tool-code='read-odps'], [data-tool-code='read-hive'], [data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) .qi-relation-field > label {
  padding-top: 5px;
}

.qi-relation-input:is([data-tool-code='read-odps'], [data-tool-code='read-hive'], [data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) .qi-relation-mode {
  align-items: center;
}

.qi-relation-input:is([data-tool-code='read-odps'], [data-tool-code='read-hive'], [data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) .qi-relation-mode > label {
  padding-top: 0;
}

.qi-relation-input:is([data-tool-code='read-odps'], [data-tool-code='read-hive'], [data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) .qi-relation-properties .qi-relation-table {
  table-layout: fixed;
}

.qi-relation-input:is([data-tool-code='read-odps'], [data-tool-code='read-hive'], [data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) .qi-relation-properties .qi-relation-table th {
  padding: 7px 10px;
  font-weight: 600;
}

.qi-relation-input:is([data-tool-code='read-odps'], [data-tool-code='read-hive'], [data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) .qi-relation-properties .qi-relation-table td {
  padding: 10px;
}

/* Checked radio labels use the primary colour in the reference. */
.qi-relation-input:is([data-tool-code='read-odps'], [data-tool-code='read-hive'], [data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) :deep(.ant-radio-wrapper-checked) {
  color: #2f62ff;
}

/* MongoDB spacing measured against the reference screenshot. */
.qi-relation-input:is([data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) .qi-relation-mode {
  margin-bottom: 4px;
}

.qi-relation-input:is([data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) :deep(.qi-table-input-grid > .qi-table-input-field:first-child) {
  margin-bottom: 3px;
}

.qi-relation-input:is([data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) .qi-relation-properties h3 {
  margin: -1px 0 14px;
}

.qi-relation-input:is([data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) :deep(.qi-table-condition-table .ant-table-thead > tr > th:last-child),
.qi-relation-input:is([data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) :deep(.qi-table-condition-table .ant-table-tbody > tr > td:last-child) {
  text-align: center;
}

.qi-relation-input:is([data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) :deep(.qi-table-condition-actions) {
  justify-content: center;
}

/* The reference empty state is the illustration only, without a caption. */
.qi-relation-input:is([data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) .qi-relation-empty {
  justify-content: flex-start;
  padding-top: 83px;
}

.qi-relation-input:is([data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) .qi-relation-empty span,
.qi-relation-input:is([data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) :deep(.qi-table-input-empty span) {
  display: none;
}

.qi-relation-input:is([data-tool-code='read-mongodb'], [data-tool-code='read-tdengine']) :deep(.qi-table-input-empty) {
  padding-top: 49px;
}

.qi-relation-parse-button.ant-btn {
  height: 25px;
  padding: 0 12px;
  font-size: 12px;
  border-radius: 2px;
}

/* Reference SQL editor: grey line-number gutter and filled current line. */
.qi-relation-reference-sql :deep(.qi-sql-editor-surface) {
  border: 0;
}

.qi-relation-reference-sql :deep(.monaco-editor .margin) {
  background-color: #f5f5f7;
}

.qi-relation-reference-sql :deep(.monaco-editor .view-overlays .current-line) {
  background: #e8f1ff;
  border: 0;
}

@media (max-width: 768px) {
  .qi-relation-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .qi-relation-field {
    grid-template-columns: 100px minmax(0, 1fr);
  }

  .qi-relation-table {
    min-width: 620px;
  }
}
</style>
