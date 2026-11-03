<script lang="ts" setup>
import type { StandardDocApi } from '#/api/governance/doc';
import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';
import type { ModelingModelApi } from '#/api/modeling/model';
import type { TableModelApi } from '#/api/modeling/tablemodel';
import type { SystemUserApi } from '#/api/system/user';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { handleTree } from '@vben/utils';

import {
  Button,
  Input,
  InputNumber,
  message,
  Modal,
  Radio,
  RadioGroup,
  Select,
  Switch,
  Table,
  TextArea,
  TreeSelect,
} from 'antdv-next';

import { getDocSimpleList } from '#/api/governance/doc';
import { getStandardElementPage } from '#/api/governance/standardelement';
import { getBizCategoryList } from '#/api/modeling/bizcategory';
import {
  getModelDatabaseList,
  getModelImportFields,
  getModelTableList,
} from '#/api/modeling/model';
import {
  createTableModel,
  getTableModel,
  getTableModelDesign,
  modelSaveData,
  saveTableModelDraft,
} from '#/api/modeling/tablemodel';
import { getSimpleUserList } from '#/api/system/user';

import { assertCurrentDesign } from '../../modelcommon/current-design-state';
import {
  getLayerTreeOptions,
  getSubjectDomainTreeOptions,
  TABLE_TYPE_OPTIONS,
} from '../../modelcommon/data';

defineOptions({ name: 'ModelingTableModelForm' });

const emit = defineEmits<{ saved: [id: number | string] }>();

const COLUMN_TYPE_OPTIONS = [
  'TINYINT',
  'INTEGER',
  'BIGINT',
  'DECIMAL',
  'NUMERIC',
  'FLOAT',
  'DOUBLE',
  'NUMBER',
  'CHAR',
  'VARCHAR',
  'VARCHAR2',
  'TEXT',
  'DATE',
  'TIMESTAMP',
  'DATETIME',
].map((value) => ({ label: value, value }));

const DECIMAL_TYPES = new Set([
  'DECIMAL',
  'DOUBLE',
  'FLOAT',
  'NUMBER',
  'NUMERIC',
]);

const visible = ref(false);
const saving = ref(false);
const optionsLoading = ref(false);
const fieldsLoading = ref(false);
let loadSequence = 0;
let tableSequence = 0;
let importSequence = 0;
const accessStore = useAccessStore();
watch(
  () => `${accessStore.accessToken}/${accessStore.refreshToken}`,
  () => {
    loadSequence++;
    tableSequence++;
    importSequence++;
    visible.value = false;
    saving.value = false;
  },
);
watch(visible, (opened) => {
  if (!opened) {
    loadSequence++;
    tableSequence++;
    importSequence++;
  }
});
onBeforeUnmount(() => {
  loadSequence++;
  tableSequence++;
  importSequence++;
});

type FieldRow = ModelingModelApi.ModelField & { _key: string };
type DomainOption = { code?: string; label: string; value: number | string };

const form = reactive<
  Partial<TableModelApi.Model> & {
    databaseAddress?: string;
    databaseType?: string;
    fields?: FieldRow[];
    typeConfig?: ModelingModelApi.ModelTypeConfig | null;
  }
>({});
const layerTree = ref<any[]>([]);
const layerById = ref<Map<string, any>>(new Map());
const subjectDomainTree = ref<any[]>([]);
const bizCategoryTree = ref<any[]>([]);
const bizCategoryFlat = ref<any[]>([]);
const databaseList = ref<ModelingModelApi.DatabaseSimple[]>([]);
const tableList = ref<{ label: string; value: string }[]>([]);
const users = ref<SystemUserApi.User[]>([]);
const documents = ref<StandardDocApi.SimpleDoc[]>([]);
const standards = ref<GovernanceStandardElementApi.StandardElement[]>([]);

const isReverse = computed(() => form.createMode === 'REVERSE');
const isApplication = computed(() => form.tableType === 'APPLICATION');
const isEdit = computed(() => form.id !== null && form.id !== undefined);

const stdTypeOptions = computed(() =>
  getDictOptions(DICT_TYPE.STANDARD_STD_TYPE, 'string'),
);

const dataDomainOptions = computed<DomainOption[]>(() => {
  const catId = form.bizCategoryId;
  if (catId === null || catId === undefined) return [];
  const cat = bizCategoryFlat.value.find(
    (item) => String(item.id) === String(catId),
  );
  return (cat?.dataDomains || [])
    .filter(
      (item: any) => item.status === undefined || Number(item.status) === 0,
    )
    .map((item: any) => ({
      code: item.code,
      label: item.code ? `${item.name} (${item.code})` : item.name,
      value: item.id,
    }));
});

const databaseOptions = computed(() =>
  databaseList.value.map((item) => ({
    label: item.name || String(item.id ?? ''),
    value: item.id,
  })),
);

const userOptions = computed(() =>
  users.value.map((item) => ({
    label: item.nickname || item.username,
    value: item.id,
  })),
);

const documentOptions = computed(() =>
  documents.value
    .filter((item) => !form.documentType || item.stdType === form.documentType)
    .map((item) => ({
      label: item.stdCode ? `${item.nameCn} (${item.stdCode})` : item.nameCn,
      value: item.id,
    })),
);

const standardOptions = computed(() =>
  standards.value.map((item) => ({
    label: item.code ? `${item.name} (${item.code})` : item.name,
    value: item.id,
  })),
);

const fullNameOptions = computed(() => [
  { label: composeTableName(1), value: 1 },
  { label: composeTableName(2), value: 2 },
]);

const getTitle = computed(() => {
  if (form.id) return '编辑逻辑模型';
  return '新增逻辑模型';
});

const fieldColumns = [
  { title: '编号', key: 'index', width: 70 },
  {
    title: '关联标准',
    dataIndex: 'standardName',
    key: 'standardName',
    ellipsis: true,
    width: 180,
  },
  {
    title: '表中文名称',
    dataIndex: 'cnName',
    key: 'cnName',
    ellipsis: true,
    width: 180,
  },
  {
    title: '表英文名称',
    dataIndex: 'fieldName',
    key: 'fieldName',
    ellipsis: true,
    width: 180,
  },
  {
    title: '描述',
    dataIndex: 'comment',
    key: 'comment',
    ellipsis: true,
    width: 200,
  },
  { title: '数据类型', dataIndex: 'dataType', key: 'dataType', width: 120 },
  { title: '属性长度', dataIndex: 'dataLength', key: 'dataLength', width: 100 },
  { title: '是否主键', key: 'pkFlag', width: 100 },
  { title: '操作', key: 'action', width: 180, fixed: 'right' as const },
];

const fieldModalOpen = ref(false);
const fieldEditingIndex = ref<null | number>(null);
const fieldDraft = reactive<FieldRow>({ _key: '' });
const scaleDisabled = computed(
  () => !DECIMAL_TYPES.has(String(fieldDraft.dataType || '').toUpperCase()),
);
const lengthRequired = computed(
  () => String(fieldDraft.dataType || '').toUpperCase() !== 'DATE',
);
const fieldModalTitle = computed(() =>
  fieldEditingIndex.value === null || fieldEditingIndex.value === undefined
    ? '新增表字段'
    : '编辑表字段',
);

function newKey() {
  const cryptoKey = globalThis.crypto?.randomUUID?.();
  return cryptoKey || `f-${Date.now()}-${Math.random()}`;
}

function findNode(nodes: any[] | undefined, id: unknown): any | undefined {
  if (id === null || id === undefined || id === '') return undefined;
  for (const node of nodes || []) {
    if (node?.children?.length) {
      const found = findNode(node.children, id);
      if (found) return found;
    }
    if (String(node?.id) === String(id)) return node;
  }
  return undefined;
}

function composeTableName(tableCase: number) {
  const parts: string[] = [];
  const layer =
    form.layerId === null || form.layerId === undefined
      ? undefined
      : layerById.value.get(String(form.layerId));
  const layerToken = String(layer?.layerCode || layer?.tablePrefix || '')
    .replace(/_+$/u, '')
    .trim();
  if (layerToken) parts.push(layerToken);
  if (isApplication.value) {
    const theme = findNode(subjectDomainTree.value, form.subjectDomainId);
    const token = String(theme?.code || '').trim();
    if (token) parts.push(token);
  } else {
    const biz = findNode(bizCategoryTree.value, form.bizCategoryId);
    const bizToken = String(biz?.code || '').trim();
    if (bizToken) parts.push(bizToken);
    const domain = dataDomainOptions.value.find(
      (item) => String(item.value) === String(form.dataDomainId),
    );
    const domainToken = String(domain?.code || '').trim();
    if (domainToken) parts.push(domainToken);
  }
  const suffix = (form.code || '').trim();
  if (suffix) parts.push(suffix);
  const name = parts.join('_');
  if (!name) return '-';
  return tableCase === 2 ? name.toLowerCase() : name.toUpperCase();
}

function toFieldRow(field: ModelingModelApi.ModelField): FieldRow {
  return {
    ...field,
    _key: field.fieldUid || newKey(),
    pkFlag: !!field.pkFlag,
    nullableFlag: field.nullableFlag !== false,
  };
}

function resetFieldDraft() {
  Object.keys(fieldDraft).forEach((key) =>
    Reflect.deleteProperty(fieldDraft, key),
  );
  fieldDraft._key = newKey();
  fieldDraft.dataLength = 1;
  fieldDraft.dataScale = 0;
  fieldDraft.pkFlag = false;
  fieldDraft.nullableFlag = true;
  fieldDraft.standardId = undefined;
}

function openFieldModal(row?: FieldRow, index?: number) {
  resetFieldDraft();
  if (row) {
    Object.assign(fieldDraft, { ...row });
    fieldEditingIndex.value = index ?? null;
  } else {
    fieldEditingIndex.value = null;
  }
  fieldModalOpen.value = true;
}

function onStandardChange(value: unknown) {
  const picked = standards.value.find(
    (item) => String(item.id) === String(value),
  );
  if (!picked) {
    fieldDraft.standardId = undefined;
    fieldDraft.standardName = undefined;
    fieldDraft.standardCode = undefined;
    fieldDraft.standardColumnType = undefined;
    return;
  }
  fieldDraft.standardId = picked.id;
  fieldDraft.standardName = picked.name;
  fieldDraft.standardCode = picked.code;
  fieldDraft.standardColumnType = picked.columnType;
  fieldDraft.cnName = picked.name;
  fieldDraft.fieldName = (picked.engName || '').toUpperCase();
  fieldDraft.dataType = picked.columnType;
  if (picked.dataLength !== null && picked.dataLength !== undefined)
    fieldDraft.dataLength = picked.dataLength;
  if (picked.dataScale !== null && picked.dataScale !== undefined)
    fieldDraft.dataScale = picked.dataScale;
  fieldDraft.defaultValue = picked.defaultValue;
  fieldDraft.comment = picked.description;
  fieldDraft.nullableFlag = picked.nullableFlag !== 0;
}

function onCodeInput(value: string) {
  form.code = value.replaceAll(/[^A-Za-z0-9_]/g, '');
}

function onFieldNameInput(value: string) {
  fieldDraft.fieldName = value.toUpperCase().replaceAll(/[^A-Z0-9_]/g, '');
}

function validateDefaultValue() {
  const value = (fieldDraft.defaultValue || '').trim();
  if (!value) return '';
  const type = String(fieldDraft.dataType || '').toUpperCase();
  const length = Number(fieldDraft.dataLength || 0);
  const scale = Number(fieldDraft.dataScale || 0);
  if (['BIGINT', 'INTEGER', 'TINYINT'].includes(type)) {
    if (!/^-?\d+$/.test(value)) return '整数类型的默认值不能包含小数点';
    if (length > 0 && value.replace('-', '').length > length) {
      return `默认值长度不能超过属性长度${length}`;
    }
  } else if (DECIMAL_TYPES.has(type)) {
    if (!/^-?\d+(\.\d+)?$/.test(value)) return '数值类型的默认值只能为数字';
    const [intPart = '', decPart = ''] = value.replace('-', '').split('.');
    const intLimit = Math.max(length - scale, 0);
    if (length > 0 && intPart.length > intLimit) {
      return `整数部分长度不能超过${intLimit}位`;
    }
    if (decPart.length > scale) return `小数部分长度不能超过${scale}位`;
  } else if (['CHAR', 'TEXT', 'VARCHAR', 'VARCHAR2'].includes(type)) {
    if (length > 0 && value.length > length) {
      return `字符串长度不能超过${length}个字符`;
    }
  } else if (type === 'DATE' && !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return '日期格式不正确，请使用YYYY-MM-DD格式';
  } else if (
    ['DATETIME', 'TIMESTAMP'].includes(type) &&
    !/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(value)
  ) {
    return '日期时间格式不正确，请使用YYYY-MM-DD HH:mm:ss格式';
  }
  return '';
}

function saveFieldDraft() {
  if (!fieldDraft.cnName?.trim()) {
    message.warning('请输入中文名称');
    return;
  }
  if (!fieldDraft.fieldName?.trim()) {
    message.warning('请输入英文名称');
    return;
  }
  if (!/^[A-Z][A-Z0-9_]*$/.test(fieldDraft.fieldName)) {
    message.warning('英文名称只能包含字母、数字和下划线，且必须以字母开头');
    return;
  }
  if (!fieldDraft.dataType?.trim()) {
    message.warning('请选择数据类型');
    return;
  }
  if (
    lengthRequired.value &&
    (fieldDraft.dataLength === null ||
      fieldDraft.dataLength === undefined ||
      fieldDraft.dataLength < 1)
  ) {
    message.warning('请输入属性长度');
    return;
  }
  const defaultError = validateDefaultValue();
  if (defaultError) {
    message.warning(defaultError);
    return;
  }
  if (!form.fields) form.fields = [];
  const duplicated = form.fields.some(
    (item, index) =>
      index !== fieldEditingIndex.value &&
      item.fieldName?.toLowerCase() === fieldDraft.fieldName?.toLowerCase(),
  );
  if (duplicated) {
    message.warning('英文名称不能重复');
    return;
  }
  const row: FieldRow = {
    ...fieldDraft,
    cnName: fieldDraft.cnName.trim(),
    _key: fieldDraft._key || newKey(),
  };
  if (
    fieldEditingIndex.value === null ||
    fieldEditingIndex.value === undefined
  ) {
    form.fields.push(row);
  } else {
    form.fields[fieldEditingIndex.value] = row;
  }
  fieldModalOpen.value = false;
}

function removeField(index: number) {
  form.fields?.splice(index, 1);
}

watch(
  () => fieldDraft.pkFlag,
  (pk) => {
    if (pk) fieldDraft.nullableFlag = false;
  },
);

watch(
  () => form.bizCategoryId,
  () => {
    if (form.dataDomainId === null || form.dataDomainId === undefined) return;
    const ok = dataDomainOptions.value.some(
      (item) => String(item.value) === String(form.dataDomainId),
    );
    if (!ok) form.dataDomainId = undefined;
  },
);

watch(
  () => form.documentType,
  () => {
    if (form.documentId === null || form.documentId === undefined) return;
    const ok = documentOptions.value.some(
      (item) => String(item.value) === String(form.documentId),
    );
    if (!ok) form.documentId = undefined;
  },
);

watch(
  () => form.layerId,
  (id) => {
    if (id === null || id === undefined || form.tableType) return;
    const layer = layerById.value.get(String(id));
    if (layer?.defaultTableType) form.tableType = layer.defaultTableType;
  },
);

function flattenLayers(nodes: any[], map: Map<string, any>) {
  for (const node of nodes || []) {
    if (node.id !== null && node.id !== undefined)
      map.set(String(node.id), node);
    if (node.children?.length) flattenLayers(node.children, map);
  }
}

function onContactChange(userId: unknown) {
  const user = users.value.find((item) => String(item.id) === String(userId));
  form.contactNumber = user?.mobile || '';
}

async function loadTables() {
  const request = ++tableSequence;
  tableList.value = [];
  if (!form.databaseId) return;
  const tables = await getModelTableList(form.databaseId);
  if (request !== tableSequence) return;
  tableList.value = (tables || []).map((name: string) => ({
    label: name,
    value: name,
  }));
}

async function onDatabaseChange(databaseId: unknown) {
  const database = databaseList.value.find(
    (item) => String(item.id) === String(databaseId),
  );
  form.databaseType = database?.productType || '';
  form.databaseAddress = database?.host || '';
  form.tableName = undefined;
  form.fields = [];
  await loadTables();
}

async function onTableChange(tableName: unknown) {
  const request = ++importSequence;
  form.fields = [];
  if (!form.databaseId || !tableName) return;
  fieldsLoading.value = true;
  try {
    const fields = await getModelImportFields(
      form.databaseId,
      String(tableName),
    );
    if (request !== importSequence) return;
    form.fields = (fields || []).map((item) => toFieldRow(item));
  } finally {
    if (request === importSequence) fieldsLoading.value = false;
  }
}

async function open(
  mode: 'FORWARD' | 'REVERSE',
  model?: Partial<TableModelApi.Model>,
  defaults?: {
    bizCategoryId?: number | string;
    dataDomainId?: number | string;
    layerId?: number | string;
    subjectDomainId?: number | string;
    tableType?: string;
  },
) {
  const request = ++loadSequence;
  tableSequence++;
  importSequence++;
  optionsLoading.value = true;
  try {
    Object.keys(form).forEach((key) => Reflect.deleteProperty(form, key));
    if (model?.id !== null && model?.id !== undefined) {
      const latest = await getTableModel(model.id);
      if (request !== loadSequence) return;
      Object.assign(form, latest);
      form.tableCase = latest.tableCase === 2 ? 2 : 1;
      form.enabled = latest.enabled === 1 ? 1 : 0;
      const design = await getTableModelDesign(model.id);
      if (request !== loadSequence) return;
      assertCurrentDesign(latest, design);
      form.typeConfig = design.typeConfig ?? null;
      form.fields = design.fields.map((item) => toFieldRow(item));
    } else {
      form.createMode = mode;
      form.fields = [];
      form.tableCase = 1;
      form.enabled = 0;
      form.dataDomainId = defaults?.dataDomainId;
      form.bizCategoryId = defaults?.bizCategoryId;
      form.subjectDomainId = defaults?.subjectDomainId;
      form.tableType = defaults?.tableType;
    }

    const [
      layers,
      subjectDomains,
      bizCategories,
      databases,
      userList,
      docs,
      elementPage,
    ] = await Promise.all([
      getLayerTreeOptions(),
      getSubjectDomainTreeOptions(),
      getBizCategoryList({}),
      getModelDatabaseList().catch(() => []),
      getSimpleUserList().catch(() => []),
      getDocSimpleList().catch(() => []),
      getStandardElementPage({ pageNo: 1, pageSize: 200 }).catch(() => ({
        list: [],
        total: 0,
      })),
    ]);
    if (request !== loadSequence) return;

    layerTree.value = layers;
    const map = new Map<string, any>();
    flattenLayers(layers || [], map);
    layerById.value = map;
    subjectDomainTree.value = subjectDomains;
    bizCategoryFlat.value = (bizCategories || []).filter(
      (item: any) => item.status === undefined || Number(item.status) === 0,
    );
    bizCategoryTree.value = handleTree(bizCategoryFlat.value);
    databaseList.value = databases || [];
    users.value = userList || [];
    documents.value = docs || [];
    standards.value = elementPage?.list || [];
    if (form.databaseId !== null && form.databaseId !== undefined) {
      const database = databaseList.value.find(
        (item) => String(item.id) === String(form.databaseId),
      );
      form.databaseType = database?.productType || '';
      form.databaseAddress = database?.host || '';
      if (isReverse.value && !form.id) await loadTables();
    }
    visible.value = true;
  } finally {
    if (request === loadSequence) optionsLoading.value = false;
  }
}

function close() {
  if (saving.value) return;
  visible.value = false;
}

async function handleOk() {
  if (saving.value || optionsLoading.value) return;
  if (!form.createMode) {
    message.warning('创建方式不能为空');
    return;
  }
  if (form.layerId === null || form.layerId === undefined) {
    message.warning('数仓分层不能为空');
    return;
  }
  if (isApplication.value) {
    if (form.subjectDomainId === null || form.subjectDomainId === undefined) {
      message.warning('主题域不能为空');
      return;
    }
  } else if (form.bizCategoryId === null || form.bizCategoryId === undefined) {
    message.warning('业务分类不能为空');
    return;
  } else if (form.dataDomainId === null || form.dataDomainId === undefined) {
    message.warning('数据分域不能为空');
    return;
  }
  const code = (form.code || '').trim();
  if (!code) {
    message.warning('表英文名称不能为空');
    return;
  }
  if (!/^[A-Za-z0-9_]+$/.test(code)) {
    message.warning('表名只能包含字母、数字和下划线');
    return;
  }
  if (!(form.name || '').trim()) {
    message.warning('表中文名称不能为空');
    return;
  }
  if (form.tableCase !== 1 && form.tableCase !== 2) {
    message.warning('完整表名不能为空');
    return;
  }
  if (isReverse.value && !form.id && (!form.databaseId || !form.tableName)) {
    message.warning(form.databaseId ? '表不能为空' : '请选择数据连接名称');
    return;
  }
  const rows: FieldRow[] = form.fields ?? [];
  if (rows.length === 0) {
    message.warning('操作失败，请添加属性字段');
    return;
  }
  if (form.id && form.status === 'DISCARDED') {
    message.warning('已废弃的逻辑模型不能修改');
    return;
  }
  const names = new Set<string>();
  for (const row of rows) {
    const key = (row.fieldName || '').toLowerCase();
    if (!key || names.has(key)) {
      message.warning('属性字段英文名称不能为空且不能重复');
      return;
    }
    names.add(key);
  }
  const request = loadSequence;
  saving.value = true;
  try {
    const fields = rows.map((row, index) => {
      const { _key, ...rest } = row;
      return { ...rest, sort: index + 1 };
    });
    const payload = modelSaveData({
      ...form,
      code,
      name: form.name?.trim(),
      enabled: form.enabled === 1 ? 1 : 0,
      tableCase: form.tableCase === 2 ? 2 : 1,
      fields,
    } as TableModelApi.Model);
    let id = form.id;
    if (form.id !== null && form.id !== undefined) {
      const saved = await saveTableModelDraft({
        ...payload,
        modelId: form.id,
        expectedRevision: form.revision!,
        fields,
        typeConfig: form.typeConfig ?? null,
      });
      if (request !== loadSequence || saved === false) return;
      message.success('模型已更新');
    } else {
      id = await createTableModel(payload);
      if (request !== loadSequence) return;
      message.success('模型已创建');
    }
    visible.value = false;
    if (id !== null && id !== undefined) emit('saved', id);
  } finally {
    if (request === loadSequence) saving.value = false;
  }
}

defineExpose({ open });
</script>

<template>
  <Modal
    v-model:open="visible"
    :closable="!saving"
    :footer="null"
    :mask-closable="!saving"
    :title="getTitle"
    width="1180px"
    wrap-class-name="logic-model-modal"
  >
    <div class="logic-form">
      <div class="logic-form__section">基础信息</div>
      <div class="logic-form__grid">
        <div v-if="isEdit" class="logic-form__item">
          <label class="logic-form__label" for="logic-table-type">表类型</label>
          <Select
            id="logic-table-type"
            v-model:value="form.tableType"
            class="logic-form__control"
            disabled
            :options="TABLE_TYPE_OPTIONS"
            placeholder="请选择表类型"
          />
        </div>
        <div v-else class="logic-form__item">
          <label
            class="logic-form__label logic-form__label--required"
            for="logic-create-mode"
          >
            创建方式
          </label>
          <RadioGroup
            id="logic-create-mode"
            v-model:value="form.createMode"
            class="logic-form__control"
          >
            <Radio value="FORWARD">手工录入</Radio>
            <Radio value="REVERSE">物化表生成</Radio>
          </RadioGroup>
        </div>
        <div class="logic-form__item">
          <label
            class="logic-form__label logic-form__label--required"
            for="logic-layer"
          >
            数仓分层
          </label>
          <TreeSelect
            id="logic-layer"
            v-model:value="form.layerId"
            allow-clear
            class="logic-form__control"
            :field-names="{ label: 'name', value: 'id', children: 'children' }"
            placeholder="请选择数仓分层"
            show-search
            tree-default-expand-all
            tree-node-filter-prop="name"
            :tree-data="layerTree"
          />
        </div>

        <template v-if="isApplication">
          <div class="logic-form__item">
            <label
              class="logic-form__label logic-form__label--required"
              for="logic-theme"
            >
              所属主题
            </label>
            <TreeSelect
              id="logic-theme"
              v-model:value="form.subjectDomainId"
              allow-clear
              class="logic-form__control"
              :field-names="{
                label: 'name',
                value: 'id',
                children: 'children',
              }"
              placeholder="请选择所属主题"
              show-search
              tree-default-expand-all
              tree-node-filter-prop="name"
              :tree-data="subjectDomainTree"
            />
          </div>
        </template>
        <template v-else>
          <div class="logic-form__item">
            <label
              class="logic-form__label logic-form__label--required"
              for="logic-biz"
            >
              业务分类
            </label>
            <TreeSelect
              id="logic-biz"
              v-model:value="form.bizCategoryId"
              allow-clear
              class="logic-form__control"
              :field-names="{
                label: 'name',
                value: 'id',
                children: 'children',
              }"
              placeholder="请选择业务分类"
              show-search
              tree-default-expand-all
              tree-node-filter-prop="name"
              :tree-data="bizCategoryTree"
            />
          </div>
          <div class="logic-form__item">
            <label
              class="logic-form__label logic-form__label--required"
              for="logic-domain"
            >
              数据分域
            </label>
            <Select
              id="logic-domain"
              v-model:value="form.dataDomainId"
              allow-clear
              class="logic-form__control"
              :disabled="
                form.bizCategoryId === null || form.bizCategoryId === undefined
              "
              option-filter-prop="label"
              :options="dataDomainOptions"
              placeholder="请选择数据分域"
              show-search
            />
          </div>
        </template>

        <div class="logic-form__item">
          <label
            class="logic-form__label logic-form__label--required"
            for="logic-code"
          >
            表英文名称
          </label>
          <Input
            id="logic-code"
            :value="form.code"
            class="logic-form__control"
            placeholder="请输入表英文名称"
            @update:value="onCodeInput"
          />
        </div>
        <div class="logic-form__item">
          <label
            class="logic-form__label logic-form__label--required"
            for="logic-name"
          >
            表中文名称
          </label>
          <Input
            id="logic-name"
            v-model:value="form.name"
            class="logic-form__control"
            placeholder="请输入表中文名称"
          />
        </div>
        <div class="logic-form__item">
          <label
            class="logic-form__label logic-form__label--required"
            for="logic-full-name"
          >
            完整表名
          </label>
          <Select
            id="logic-full-name"
            v-model:value="form.tableCase"
            class="logic-form__control"
            :options="fullNameOptions"
            placeholder="请选择完整表名"
          />
        </div>
        <div class="logic-form__item">
          <label class="logic-form__label" for="logic-std-type">标准类型</label>
          <Select
            id="logic-std-type"
            v-model:value="form.documentType"
            allow-clear
            class="logic-form__control"
            option-filter-prop="label"
            :options="stdTypeOptions"
            placeholder="请选择标准类型"
            show-search
          />
        </div>
        <div class="logic-form__item">
          <label class="logic-form__label" for="logic-doc">标准登记</label>
          <Select
            id="logic-doc"
            v-model:value="form.documentId"
            allow-clear
            class="logic-form__control"
            :disabled="!form.documentType"
            option-filter-prop="label"
            :options="documentOptions"
            placeholder="请选择标准登记"
            show-search
          />
        </div>
        <div class="logic-form__item">
          <label class="logic-form__label" for="logic-owner">责任人</label>
          <Select
            id="logic-owner"
            v-model:value="form.contactUserId"
            allow-clear
            class="logic-form__control"
            option-filter-prop="label"
            :options="userOptions"
            placeholder="请选择责任人"
            show-search
            @change="onContactChange"
          />
        </div>
        <div class="logic-form__item">
          <label class="logic-form__label" for="logic-phone">联系电话</label>
          <Input
            id="logic-phone"
            v-model:value="form.contactNumber"
            class="logic-form__control"
            disabled
            placeholder="请输入联系电话"
          />
        </div>
        <div class="logic-form__item">
          <label class="logic-form__label" for="logic-enabled">状态</label>
          <RadioGroup
            id="logic-enabled"
            v-model:value="form.enabled"
            class="logic-form__control"
          >
            <Radio :value="0">禁用</Radio>
            <Radio :value="1">启用</Radio>
          </RadioGroup>
        </div>
        <div
          class="logic-form__item logic-form__item--top logic-form__item--full"
        >
          <label class="logic-form__label" for="logic-description">描述</label>
          <TextArea
            id="logic-description"
            v-model:value="form.description"
            class="logic-form__control"
            :maxlength="500"
            placeholder="请输入描述"
            :rows="4"
            show-count
          />
        </div>
        <div
          class="logic-form__item logic-form__item--top logic-form__item--full"
        >
          <label class="logic-form__label" for="logic-remark">备注</label>
          <TextArea
            id="logic-remark"
            v-model:value="form.remark"
            class="logic-form__control"
            :maxlength="500"
            placeholder="请输入备注"
            :rows="4"
            show-count
          />
        </div>
      </div>

      <template v-if="isReverse && !isEdit">
        <div class="logic-form__section">数据源</div>
        <div class="logic-form__grid">
          <div class="logic-form__item">
            <label
              class="logic-form__label logic-form__label--required"
              for="logic-database"
            >
              数据连接名称
            </label>
            <Select
              id="logic-database"
              v-model:value="form.databaseId"
              class="logic-form__control"
              option-filter-prop="label"
              :options="databaseOptions"
              placeholder="请选择数据连接名称"
              show-search
              @change="onDatabaseChange"
            />
          </div>
          <div class="logic-form__item">
            <label class="logic-form__label" for="logic-db-type">数据库类型</label>
            <Input
              id="logic-db-type"
              :value="form.databaseType"
              class="logic-form__control"
              disabled
              placeholder="请选择数据库类型"
            />
          </div>
          <div class="logic-form__item">
            <label class="logic-form__label" for="logic-db-address">数据库地址</label>
            <Input
              id="logic-db-address"
              :value="form.databaseAddress"
              class="logic-form__control"
              disabled
              placeholder="请选择数据库类型"
            />
          </div>
          <div class="logic-form__item">
            <label
              class="logic-form__label logic-form__label--required"
              for="logic-table"
            >
              选择表
            </label>
            <Select
              id="logic-table"
              v-model:value="form.tableName"
              class="logic-form__control"
              option-filter-prop="label"
              :options="tableList"
              placeholder="请选择表"
              show-search
              @change="onTableChange"
            />
          </div>
        </div>
      </template>

      <div class="logic-form__section">属性字段</div>
      <Button
        class="logic-form__add"
        size="small"
        type="primary"
        ghost
        @click="openFieldModal()"
      >
        + 新增
      </Button>
      <Table
        :columns="fieldColumns"
        :data-source="form.fields || []"
        :loading="fieldsLoading"
        :locale="{ emptyText: '暂无数据' }"
        :pagination="false"
        :row-key="(row: FieldRow) => row._key"
        :scroll="{ x: 1280 }"
      >
        <template #bodyCell="{ column, record, index }">
          <template v-if="column.key === 'index'">{{ index + 1 }}</template>
          <template v-else-if="column.key === 'standardName'">
            {{ record.standardName || '-' }}
          </template>
          <template v-else-if="column.key === 'pkFlag'">
            <Switch :checked="!!record.pkFlag" disabled size="small" />
          </template>
          <template v-else-if="column.key === 'action'">
            <Button
              size="small"
              type="link"
              @click="openFieldModal(record, index)"
            >
              <template #icon><IconifyIcon icon="lucide:edit" /></template>
              编辑
            </Button>
            <Button danger size="small" type="link" @click="removeField(index)">
              <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
              删除
            </Button>
          </template>
        </template>
      </Table>
    </div>
    <div class="logic-form__footer">
      <Button :disabled="saving" @click="close">取消</Button>
      <Button
        :disabled="optionsLoading"
        :loading="saving"
        type="primary"
        @click="handleOk"
      >
        确定
      </Button>
    </div>
  </Modal>

  <Modal
    v-model:open="fieldModalOpen"
    :footer="null"
    :title="fieldModalTitle"
    width="680px"
  >
    <div class="logic-form logic-form--field">
      <div class="logic-form__item">
        <label class="logic-form__label" for="field-standard">关联标准</label>
        <Select
          id="field-standard"
          v-model:value="fieldDraft.standardId"
          allow-clear
          class="logic-form__control"
          option-filter-prop="label"
          :options="standardOptions"
          placeholder="请选择关联标准"
          show-search
          @change="onStandardChange"
        />
      </div>
      <div class="logic-form__item">
        <label
          class="logic-form__label logic-form__label--required"
          for="field-cn"
        >
          中文名称
        </label>
        <Input
          id="field-cn"
          v-model:value="fieldDraft.cnName"
          class="logic-form__control"
          placeholder="请输入中文名称"
        />
      </div>
      <div class="logic-form__item">
        <label
          class="logic-form__label logic-form__label--required"
          for="field-en"
        >
          英文名称
        </label>
        <Input
          id="field-en"
          :value="fieldDraft.fieldName"
          class="logic-form__control"
          placeholder="请输入英文名称"
          @update:value="onFieldNameInput"
        />
      </div>
      <div class="logic-form__item">
        <label
          class="logic-form__label logic-form__label--required"
          for="field-type"
        >
          数据类型
        </label>
        <Select
          id="field-type"
          v-model:value="fieldDraft.dataType"
          class="logic-form__control"
          option-filter-prop="label"
          :options="COLUMN_TYPE_OPTIONS"
          placeholder="请选择数据类型"
          show-search
        />
      </div>
      <div class="logic-form__item">
        <label
          class="logic-form__label"
          :class="{ 'logic-form__label--required': lengthRequired }"
          for="field-length"
        >
          属性长度
        </label>
        <InputNumber
          id="field-length"
          v-model:value="fieldDraft.dataLength"
          class="logic-form__control"
          :min="1"
          :step="1"
        />
      </div>
      <div class="logic-form__item">
        <label class="logic-form__label" for="field-scale">小数位数</label>
        <InputNumber
          id="field-scale"
          v-model:value="fieldDraft.dataScale"
          class="logic-form__control"
          :disabled="scaleDisabled"
          :min="0"
          :step="1"
        />
      </div>
      <div class="logic-form__item">
        <label class="logic-form__label" for="field-default">默认值</label>
        <Input
          id="field-default"
          v-model:value="fieldDraft.defaultValue"
          class="logic-form__control"
          placeholder="请输入默认值"
        />
      </div>
      <div class="logic-form__item logic-form__item--top">
        <label class="logic-form__label" for="field-comment">描述</label>
        <TextArea
          id="field-comment"
          v-model:value="fieldDraft.comment"
          class="logic-form__control"
          :maxlength="500"
          placeholder="请输入描述"
          :rows="4"
          show-count
        />
      </div>
      <div class="logic-form__item">
        <label class="logic-form__label" for="field-pk">是否主键</label>
        <RadioGroup
          id="field-pk"
          v-model:value="fieldDraft.pkFlag"
          class="logic-form__control"
        >
          <Radio :value="true">是</Radio>
          <Radio :value="false">否</Radio>
        </RadioGroup>
      </div>
      <div class="logic-form__item">
        <label class="logic-form__label" for="field-required">是否必填</label>
        <RadioGroup
          id="field-required"
          class="logic-form__control"
          :disabled="!!fieldDraft.pkFlag"
          :value="fieldDraft.nullableFlag === false ? 'Y' : 'N'"
          @update:value="
            (value: string) => {
              fieldDraft.nullableFlag = value !== 'Y';
            }
          "
        >
          <Radio value="N">否</Radio>
          <Radio value="Y">是</Radio>
        </RadioGroup>
      </div>
    </div>
    <div class="logic-form__footer">
      <Button @click="fieldModalOpen = false">取消</Button>
      <Button type="primary" @click="saveFieldDraft">确定</Button>
    </div>
  </Modal>
</template>

<style scoped>
.logic-form {
  max-height: 68vh;
  padding: 4px 8px 8px 0;
  overflow: auto;
}

.logic-form--field {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-height: none;
  overflow: visible;
}

.logic-form__section {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 8px 0 16px;
  font-size: 16px;
  font-weight: 600;
  color: rgb(0 0 0 / 88%);
}

.logic-form__section::before {
  width: 3px;
  height: 14px;
  content: '';
  background: #1677ff;
  border-radius: 1px;
}

.logic-form__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px 36px;
}

.logic-form__item {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
}

.logic-form__item--top {
  align-items: flex-start;
}

.logic-form__item--full {
  grid-column: 1 / -1;
}

.logic-form__label {
  flex-shrink: 0;
  width: 110px;
  color: rgb(0 0 0 / 65%);
  text-align: right;
}

.logic-form__item--top .logic-form__label {
  padding-top: 6px;
}

.logic-form__label--required::before {
  margin-right: 2px;
  color: #ff4d4f;
  content: '*';
}

.logic-form__control {
  flex: 1;
  width: 100%;
  min-width: 0;
}

/* Select / TreeSelect 关闭了属性透传，scoped 宽度到不了根节点，这里按组件类名拉齐。 */
.logic-form__item :deep(.ant-select),
.logic-form__item :deep(.ant-tree-select),
.logic-form__item :deep(.ant-input),
.logic-form__item :deep(.ant-input-affix-wrapper),
.logic-form__item :deep(.ant-input-number),
.logic-form__item :deep(textarea.ant-input) {
  flex: 1 1 0;
  width: 100%;
  min-width: 0;
  max-width: 100%;
}

.logic-form__add {
  margin: -4px 0 10px;
}

.logic-form__footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 16px;
}

@media (max-width: 900px) {
  .logic-form__grid {
    grid-template-columns: 1fr;
  }
}
</style>
