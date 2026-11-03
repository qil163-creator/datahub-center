<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 此配置对象为父设计器隔离草稿，确认保存后才写回画布。 */
import type { ReferenceField } from './reference-node-schema';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, ref, shallowRef, watch } from 'vue';

import { useAccessStore } from '@vben/stores';
import {
  downloadFileFromBlobPart,
  downloadFileFromUrl,
  formatDate,
} from '@vben/utils';

import {
  Alert,
  AutoComplete,
  Button,
  Dropdown,
  Input,
  InputNumber,
  Menu,
  MenuItem,
  message,
  Modal,
  Select,
  TextArea,
  Upload,
} from 'antdv-next';

import { getDatabaseSimpleList } from '#/api/dataforge/database';
import { getIntegrationFileSchema } from '#/api/dataforge/integration';
import { uploadFileWithInfo } from '#/api/infra/file';
import { useObjectRequest } from '#/hooks/use-object-request';

import FileInputFieldModal from './file-input-field-modal.vue';
import {
  safeFileDownloadUrl,
  validateFileInputDates,
} from './file-input-fields';
import KafkaInputFieldModal from './kafka-input-field-modal.vue';
import { validateKafkaInputPaths } from './kafka-input-fields';
import ReferenceFieldEditModal from './reference-field-edit-modal.vue';
import ReferenceFieldMergeDialog from './reference-field-merge-dialog.vue';
import ReferenceIcon from './reference-icon.vue';
import {
  moveReferenceField,
  parseReferenceParameters,
  referenceFieldTypes,
  resolveReferenceFields,
  validateReferenceFields,
} from './reference-node-schema';
import ReferenceOutputSelection from './reference-output-selection.vue';

import './reference-node-editor.css';

const props = defineProps<{
  config: Record<string, any>;
  disabled?: boolean;
  engine?: string;
  inputFields?: DataforgeEtlApi.FieldMeta[];
  mode?: string;
  toolCode:
    | 'column-map'
    | 'read-csv'
    | 'read-excel'
    | 'read-json'
    | 'read-kafka'
    | 'write-kafka';
}>();
const access = useAccessStore();
const epoch = ref(0);
const attachmentEpoch = ref(0);
const uploading = ref(false);
const parsing = ref(false);
const connectionsLoading = ref(false);
const failed = ref('');
const issues = ref<string[]>([]);
const warnings = ref<string[]>([]);
const partialSchema = ref(false);
const referenceCount = ({
  count,
  maxLength,
}: {
  count: number;
  maxLength?: number;
}) => `${count} / ${maxLength ?? 256}个字符`;
const fileMode = ref<'managed' | 'upload'>('upload');
const uploadedFile = shallowRef<File>();
const uploadedAt = ref<number>();
let uploadDraft: Record<string, unknown> = {};
let managedDraft: Record<string, unknown> = {};
const uploadKeys = [
  'fileId',
  'fileName',
  'fileUrl',
  'schemaFileKey',
  'startRow',
  'startColumn',
  'sheetName',
];
const managedKeys = [
  'resourceBindingId',
  'relativePath',
  'path',
  'encoding',
  'charset',
  'hasHeader',
  'fieldDelimiter',
  'delimiter',
  'quote',
  'escape',
  'multiLine',
  'nullValue',
  'startRow',
  'startColumn',
  'sheetName',
  'headerRow',
  'endRow',
  'formulaMode',
  'skipEmptyRows',
];
const defaultCsvNullValue = String.raw`\N`;
const connections = ref<FoundationDatabaseApi.DatabaseSimple[]>([]);
const fieldEditorOpen = ref(false);
const fieldDraft = ref<ReferenceField>({ name: '' });
const fieldErrors = ref<string[]>([]);
let editedField: ReferenceField | undefined;
let editedConfig: Record<string, any> | undefined;
let addingField = false;
let dragged = -1;
const isFile = computed(() =>
  ['read-csv', 'read-excel', 'read-json'].includes(props.toolCode),
);
const supportsManagedFile = computed(() =>
  ['read-csv', 'read-excel', 'read-json'].includes(props.toolCode),
);
const managedFile = computed(
  () => supportsManagedFile.value && fileMode.value === 'managed',
);
const tabularUpload = computed(
  () =>
    !managedFile.value && ['read-csv', 'read-excel'].includes(props.toolCode),
);
const attachmentUrl = computed(() =>
  safeFileDownloadUrl(props.config.fileUrl, window.location.href),
);
const canDownloadAttachment = computed(
  () => !!uploadedFile.value || !!attachmentUrl.value,
);
const attachmentDate = computed(() =>
  uploadedAt.value === undefined ? '-' : formatDate(uploadedAt.value),
);
const fileFormat = computed(() => {
  if (props.toolCode === 'read-excel') return 'EXCEL';
  if (props.toolCode === 'read-csv') return 'CSV';
  return 'JSONL';
});
const displayFileFormat = computed(() =>
  props.toolCode === 'read-excel' ? 'Excel' : fileFormat.value,
);
const parseDescription = computed(() => {
  if (props.toolCode === 'read-json')
    return '解析上传的JSONL文件，并生成属性字段';
  if (props.toolCode === 'read-csv')
    return '自动解析上传的CSV文件并生成属性字段，可修改字段类型、日期格式；默认分隔符为逗号，其他分隔符请提前确认文件格式。';
  return '自动解析上传的Excel文件并生成属性字段，解析完成后请确认字段类型、日期格式。';
});
const mergeOpen = ref(false);
function requestReferenceFields() {
  if (props.disabled) return;
  if (rows.value.length === 0) resolveFields('addAll');
  else mergeOpen.value = true;
}
watch(
  () => [
    props.config,
    props.disabled,
    props.toolCode,
    access.accessToken,
    access.refreshToken,
  ],
  () => {
    mergeOpen.value = false;
  },
  { flush: 'sync' },
);
const fileExtensions = computed(() => {
  if (props.toolCode === 'read-excel') return ['xlsx', 'xls'];
  if (props.toolCode === 'read-csv') return ['csv'];
  return ['jsonl'];
});
const isKafka = computed(() => props.toolCode.endsWith('kafka'));
const isOutput = computed(() => props.toolCode === 'write-kafka');
const isMapping = computed(() => props.toolCode === 'column-map');
const legacyFile = computed(
  () => isFile.value && !!props.config.fileUrl && !props.config.fileFormat,
);
const legacyKafka = computed(
  () =>
    isKafka.value &&
    ['bootstrapServers', 'saslUsername', 'saslPassword'].some(
      (key) => !!props.config[key],
    ),
);
const legacyMapping = computed(
  () =>
    isMapping.value &&
    props.config.columnMappingMode !== 'FIELDS' &&
    !!props.config.regexColumnMapper?.length,
);
const rows = computed<ReferenceField[]>(() => {
  const value = isMapping.value
    ? props.config.fieldSelections
    : props.config.columns;
  return Array.isArray(value) ? value : [];
});
const metadataSort = ref<{ direction: -1 | 1; key: 'length' | 'precision' }>();
const jsonlRows = computed(() => {
  const sort = metadataSort.value;
  if (!sort) return rows.value;
  return rows.value.toSorted((left, right) => {
    const first = left[sort.key];
    const second = right[sort.key];
    const a =
      first === null || first === undefined || !/^\d+$/u.test(String(first))
        ? undefined
        : BigInt(first);
    const b =
      second === null || second === undefined || !/^\d+$/u.test(String(second))
        ? undefined
        : BigInt(second);
    if (a === undefined) return b === undefined ? 0 : 1;
    if (b === undefined) return -1;
    return a === b ? 0 : (a < b ? -1 : 1) * sort.direction;
  });
});
function sortMetadata(key: 'length' | 'precision') {
  if (metadataSort.value?.key !== key) {
    metadataSort.value = { key, direction: 1 };
    return;
  }
  metadataSort.value =
    metadataSort.value.direction === 1 ? { key, direction: -1 } : undefined;
}
function metadataSortState(key: 'length' | 'precision') {
  if (metadataSort.value?.key !== key) return 'none';
  return metadataSort.value.direction === 1 ? 'ascending' : 'descending';
}
const removed = computed<string[]>(() =>
  Array.isArray(props.config.removedFields) ? props.config.removedFields : [],
);
const fieldOptions = computed(() =>
  (props.inputFields ?? [])
    .filter((field) => field.name)
    .map((field) => ({ label: field.name!, value: field.name! })),
);
const connectionProduct = (item: FoundationDatabaseApi.DatabaseSimple) =>
  `${item.productId ?? ''} ${item.databaseTypeName ?? ''}`.trim();
const selectedConnection = computed(() =>
  connections.value.find(
    (item) => String(item.id) === String(props.config.databaseId),
  ),
);
const kafkaConnectionMismatch = computed(
  () =>
    isKafka.value &&
    selectedConnection.value &&
    connectionProduct(selectedConnection.value) &&
    !/kafka/iu.test(connectionProduct(selectedConnection.value)),
);
const connectionOptions = computed(() => {
  const options = connections.value
    .filter(
      (item) =>
        isOutput.value ||
        !connectionProduct(item) ||
        /kafka/iu.test(connectionProduct(item)) ||
        String(item.id) === String(props.config.databaseId),
    )
    .map((item) => ({
      value: String(item.id),
      label: `${item.name || item.id}${!isOutput.value && connectionProduct(item) && !/kafka/iu.test(connectionProduct(item)) ? '（非Kafka连接）' : ''}`,
    }));
  if (
    props.config.databaseId !== null &&
    props.config.databaseId !== undefined &&
    !options.some((item) => item.value === String(props.config.databaseId))
  )
    options.unshift({
      value: String(props.config.databaseId),
      label: `未知连接（${props.config.databaseId}）`,
    });
  return options;
});
const parameters = computed({
  get: () =>
    typeof props.config.configParameters === 'string'
      ? props.config.configParameters
      : JSON.stringify(props.config.configParameters ?? {}, null, 2),
  set: (value: string) => {
    if (props.disabled) return;
    props.config.configParameters = value;
  },
});
const parametersError = computed(() => {
  if (!isKafka.value) return '';
  try {
    parseReferenceParameters(props.config.configParameters);
    return '';
  } catch (error) {
    return error instanceof Error ? error.message : '配置参数格式无效';
  }
});
const uploadRequest = useObjectRequest(
  () =>
    `${epoch.value}/${attachmentEpoch.value}/${props.toolCode}/${fileMode.value}`,
  () => {
    uploading.value = false;
  },
);
const parseRequest = useObjectRequest(
  () =>
    JSON.stringify([
      epoch.value,
      attachmentEpoch.value,
      props.toolCode,
      fileMode.value,
      props.config.fileId,
      props.config.startRow,
      props.config.startColumn,
      sheetName(),
    ]),
  () => {
    parsing.value = false;
  },
);
const connectionRequest = useObjectRequest(
  () => `${epoch.value}/${props.toolCode}`,
  () => {
    connections.value = [];
    connectionsLoading.value = false;
  },
);

watch(
  () => [props.config, props.disabled, props.toolCode],
  () => {
    epoch.value++;
    fileMode.value =
      supportsManagedFile.value &&
      (props.config.resourceBindingId ||
        props.config.path ||
        props.config.relativePath)
        ? 'managed'
        : 'upload';
    uploadDraft = {};
    managedDraft = {};
    issues.value = [];
    failed.value = '';
    warnings.value = [];
    partialSchema.value = false;
    metadataSort.value = undefined;
    uploadedFile.value = undefined;
    uploadedAt.value = undefined;
    closeFieldEditor();
    if (props.disabled) return;
    if (isOutput.value) props.config.batchSize ??= 10_000;
    if (isFile.value && !props.config.fileUrl)
      props.config.fileFormat ??= fileFormat.value;
    if (props.toolCode === 'read-excel') {
      if (managedFile.value) props.config.startRow ??= 2;
      props.config.startColumn ??= 1;
    }
  },
  { immediate: true, flush: 'sync' },
);
watch(
  () => [props.toolCode, epoch.value, access.accessToken, access.refreshToken],
  () => {
    if (isKafka.value) void loadConnections();
  },
  { immediate: true },
);
watch(() => [access.accessToken, access.refreshToken], closeFieldEditor);

function setRows(value: ReferenceField[]) {
  if (props.disabled) return;
  if (isMapping.value) {
    props.config.fieldSelections = value;
    props.config.columnMappingMode = 'FIELDS';
  } else props.config.columns = value;
}
function addField() {
  if (props.disabled) return;
  const existingIssues =
    rows.value.length > 0
      ? validateReferenceFields(rows.value, !isMapping.value)
      : [];
  if (existingIssues.length > 0) {
    issues.value = existingIssues;
    return;
  }
  if (isMapping.value) setRows([...rows.value, { name: '', type: undefined }]);
  else openFieldEditor();
}
function closeFieldEditor() {
  fieldEditorOpen.value = false;
  fieldErrors.value = [];
  fieldDraft.value = { name: '' };
  editedField = undefined;
  editedConfig = undefined;
  addingField = false;
}
function openFieldEditor(index?: number) {
  if (props.disabled) return;
  editedField = index === undefined ? undefined : rows.value[index];
  if (index !== undefined && !editedField) return;
  editedConfig = props.config;
  addingField = index === undefined;
  fieldDraft.value = editedField
    ? {
        ...editedField,
        ...(isKafka.value && !isOutput.value
          ? { key: editedField.key ?? editedField.jsonPath ?? editedField.name }
          : {}),
      }
    : {
        name: '',
        ...(isFile.value
          ? { type: tabularUpload.value ? 'string' : 'String' }
          : {}),
      };
  fieldErrors.value = [];
  fieldEditorOpen.value = true;
}
function updateFieldDraft(value: ReferenceField) {
  if (fieldEditorOpen.value && !props.disabled) fieldDraft.value = value;
}
function saveFieldEditor() {
  if (props.disabled || props.config !== editedConfig || !fieldEditorOpen.value)
    return;
  if (!addingField && !rows.value.includes(editedField!)) {
    fieldErrors.value = ['该字段已变更，请关闭后重新修改'];
    return;
  }
  if (
    isKafka.value &&
    !isOutput.value &&
    fieldDraft.value.jsonPath !== undefined
  )
    fieldDraft.value = { ...fieldDraft.value, jsonPath: fieldDraft.value.key };
  const updated = addingField
    ? [...rows.value, fieldDraft.value]
    : rows.value.map((field) =>
        field === editedField ? fieldDraft.value : field,
      );
  // 逐个确认未知类型；其他行尚未确认时，不阻止保存当前合法草稿。
  fieldErrors.value = validateReferenceFields([fieldDraft.value]);
  if (tabularUpload.value)
    fieldErrors.value.push(...validateFileInputDates([fieldDraft.value]));
  if (isKafka.value && !isOutput.value)
    fieldErrors.value.push(
      ...validateKafkaInputPaths([fieldDraft.value], true),
    );
  const outputs = new Set<string>();
  for (const field of updated) {
    const output = field.alias?.trim() || field.name?.trim();
    if (output && outputs.has(output))
      fieldErrors.value.push(`输出字段名称重复：${output}`);
    if (output) outputs.add(output);
  }
  if (fieldErrors.value.length > 0) return;
  setRows(updated.map((field) => ({ ...field })));
  closeFieldEditor();
}
function removeField(index: number) {
  if (props.disabled) return;
  if (rows.value.length === 1) {
    issues.value = ['请至少保留一个属性字段'];
    return;
  }
  const context = props.config;
  const field = rows.value[index];
  Modal.confirm({
    title: '确定要删除这条数据吗？',
    okText: '确定',
    cancelText: '取消',
    onOk: () => {
      if (props.config === context)
        setRows(rows.value.filter((row) => row !== field));
    },
  });
}
function move(from: number, to: number) {
  setRows(moveReferenceField(rows.value, from, to));
}
function resolveFields(mode: 'addAll' | 'addNewOnly' | 'clearAndAddAll') {
  if (props.disabled) return;
  const upstream = isMapping.value
    ? (props.inputFields ?? []).map((field) => ({ name: field.name }))
    : (props.inputFields ?? []);
  setRows(resolveReferenceFields(rows.value, removed.value, upstream, mode));
  if (mode !== 'addNewOnly') props.config.removedFields = [];
}
function adoptFile() {
  if (props.disabled) return;
  props.config.fileFormat = fileFormat.value;
  props.config.fileId = undefined;
  props.config.fileName = undefined;
  props.config.schemaFileKey = undefined;
}
function setFileMode(mode: 'managed' | 'upload') {
  if (props.disabled || !supportsManagedFile.value || mode === fileMode.value)
    return;
  const outgoing = fileMode.value === 'upload' ? uploadKeys : managedKeys;
  const draft = Object.fromEntries(
    outgoing
      .filter((key) => props.config[key] !== undefined)
      .map((key) => [key, props.config[key]]),
  );
  if (fileMode.value === 'upload') uploadDraft = draft;
  else managedDraft = draft;
  for (const key of outgoing) Reflect.deleteProperty(props.config, key);
  Object.assign(props.config, mode === 'managed' ? managedDraft : uploadDraft);
  fileMode.value = mode;
  issues.value = [];
  failed.value = '';
  warnings.value = [];
  if (mode === 'managed') {
    props.config.fileFormat = fileFormat.value;
    if (props.toolCode === 'read-excel') {
      props.config.startRow ??= 2;
      props.config.startColumn ??= 1;
    }
    if (props.toolCode === 'read-json')
      setRows(
        rows.value.map((field) => {
          const match = field.jsonPath?.match(
            /^\$\["([\p{L}_][\p{L}\p{N}_]*)"\]$/u,
          );
          return match ? { ...field, jsonPath: `$.${match[1]}` } : field;
        }),
      );
  }
}
function clearLegacyKafka() {
  if (props.disabled) return;
  delete props.config.bootstrapServers;
  delete props.config.saslUsername;
  delete props.config.saslPassword;
  delete props.config.saslMechanism;
  delete props.config.securityProtocol;
}
async function loadConnections() {
  const ticket = connectionRequest.begin();
  connectionsLoading.value = true;
  failed.value = '';
  try {
    const result = await getDatabaseSimpleList();
    if (ticket()) connections.value = result;
  } catch {
    if (ticket()) failed.value = '数据连接加载失败，请重试';
  } finally {
    if (ticket()) connectionsLoading.value = false;
  }
}
async function upload(file: File) {
  if (uploading.value || parsing.value || props.disabled || managedFile.value)
    return false;
  const extension = file.name.split('.').at(-1)?.toLowerCase();
  const allowed = fileExtensions.value;
  if (!extension || !allowed.includes(extension)) {
    failed.value = `请选择 ${allowed.join('/')} 文件`;
    return false;
  }
  if (file.size > 50 * 1024 * 1024) {
    failed.value = '附件不能超过50MB';
    return false;
  }
  if (file.size === 0) {
    failed.value = '附件不能为空';
    return false;
  }
  const ticket = uploadRequest.begin();
  uploading.value = true;
  failed.value = '';
  try {
    const result = await uploadFileWithInfo({
      file,
      directory: 'dataforge/integration',
    });
    if (!ticket() || props.disabled) return false;
    if (result.id === undefined || result.id === null)
      throw new Error('上传未返回已登记的文件编号');
    props.config.fileId = String(result.id);
    props.config.fileName = result.name || file.name;
    props.config.fileUrl = result.url;
    props.config.fileFormat = fileFormat.value;
    props.config.schemaFileKey = undefined;
    setRows([]);
    warnings.value = [];
    partialSchema.value = false;
    uploadedFile.value = file;
    uploadedAt.value = Date.now();
  } catch (error) {
    if (ticket())
      failed.value = error instanceof Error ? error.message : '附件上传失败';
  } finally {
    if (ticket()) uploading.value = false;
  }
  return false;
}
function sheetName() {
  return props.toolCode === 'read-excel' &&
    typeof props.config.sheetName === 'string'
    ? props.config.sheetName
    : '';
}
const fileKey = () => {
  const base = `${props.config.fileId}/${props.config.fileFormat}/${props.config.startRow ?? 1}/${props.config.startColumn ?? 1}`;
  return sheetName() ? `${base}/sheet:${JSON.stringify(sheetName())}` : base;
};
const fileNeedsParsing = computed(
  () =>
    !managedFile.value &&
    props.config.fileId &&
    props.config.schemaFileKey !== fileKey(),
);
const invalidExcelStart = computed(
  () =>
    props.toolCode === 'read-excel' &&
    ![props.config.startRow, props.config.startColumn].every(
      (value) => Number.isSafeInteger(value) && value > 0,
    ),
);
async function parseFile() {
  if (
    parsing.value ||
    uploading.value ||
    !props.config.fileId ||
    managedFile.value ||
    legacyFile.value ||
    props.disabled
  )
    return;
  if (
    props.toolCode === 'read-excel' &&
    ![props.config.startRow, props.config.startColumn].every(
      (value) => Number.isSafeInteger(value) && value > 0,
    )
  ) {
    failed.value = '起始行、起始列必须为大于零的整数';
    return;
  }
  const ticket = parseRequest.begin();
  const key = fileKey();
  parsing.value = true;
  failed.value = '';
  try {
    const result = await getIntegrationFileSchema({
      fileId: props.config.fileId,
      format: fileFormat.value,
      startRow: props.config.startRow ?? 1,
      startColumn: props.config.startColumn ?? 1,
      ...(sheetName() ? { sheetName: sheetName() } : {}),
    });
    if (!ticket() || props.disabled) return;
    if (result.fields.length === 0) {
      failed.value = '未获取到有效字段，请检查文件内容和读取行列';
      return;
    }
    setRows(
      result.fields.map((field) => ({
        name: field.name,
        type: field.dataType ?? undefined,
        length: field.length,
        precision: field.scale,
        ...(props.toolCode === 'read-json'
          ? {
              jsonPath:
                'path' in field && typeof field.path === 'string'
                  ? field.path
                  : `$[${JSON.stringify(field.name)}]`,
            }
          : {}),
      })),
    );
    props.config.schemaFileKey = key;
    partialSchema.value = 'partial' in result && result.partial === true;
    warnings.value = result.warnings ?? [];
    message.success(`${displayFileFormat.value}解析成功，请确认属性字段类型！`);
  } catch (error) {
    if (ticket())
      failed.value = error instanceof Error ? error.message : '附件解析失败';
  } finally {
    if (ticket()) parsing.value = false;
  }
}
function detachFile() {
  if (props.disabled) return;
  attachmentEpoch.value++;
  props.config.fileId = undefined;
  props.config.fileName = undefined;
  props.config.fileUrl = undefined;
  props.config.schemaFileKey = undefined;
  setRows([]);
  warnings.value = [];
  failed.value = '';
  partialSchema.value = false;
  uploadedFile.value = undefined;
  uploadedAt.value = undefined;
}
async function downloadAttachment() {
  if (!canDownloadAttachment.value) return;
  const fileName = props.config.fileName || '附件';
  if (uploadedFile.value) {
    downloadFileFromBlobPart({ fileName, source: uploadedFile.value });
    return;
  }
  const url = attachmentUrl.value;
  if (!url) return;
  try {
    await downloadFileFromUrl({ fileName, source: url });
  } catch {
    failed.value = '附件下载未能启动，请重试';
  }
}
function setConfig(key: string, value: unknown) {
  if (!props.disabled) props.config[key] = value;
}
function updateField(
  field: ReferenceField,
  key: keyof ReferenceField,
  value: unknown,
) {
  if (!props.disabled) Object.assign(field, { [key]: value });
}
function addRow() {
  if (!props.disabled) addField();
}
function addRemovedField() {
  if (!props.disabled) props.config.removedFields = [...removed.value, ''];
}
function setRemoved(index: number, value: string) {
  if (!props.disabled) props.config.removedFields[index] = value;
}
function removeRemoved(index: number) {
  if (!props.disabled) props.config.removedFields.splice(index, 1);
}
function validCsvNullValue(value: unknown) {
  return (
    typeof value === 'string' &&
    value.length > 0 &&
    value.length <= 64 &&
    ![...value].some((character) => {
      const point = character.codePointAt(0) ?? 0;
      return point < 32 || (point >= 127 && point <= 159);
    })
  );
}
function validate() {
  const result: string[] = [];
  if (uploading.value || parsing.value || connectionsLoading.value)
    result.push('请等待当前操作完成');
  if (isFile.value) {
    if (managedFile.value) {
      if (
        !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$/u.test(
          props.config.resourceBindingId ?? '',
        )
      )
        result.push('请填写管理员授权的执行资源编号');
      const path = props.config.relativePath || props.config.path || '';
      if (
        !/^[\w./-]+$/u.test(path) ||
        path.startsWith('/') ||
        path.includes('//') ||
        path.split('/').some((part: string) => ['.', '..'].includes(part))
      )
        result.push('请填写执行资源内的相对文件路径');
      if (props.config.fileId || props.config.fileUrl)
        result.push('请明确移除上传附件或历史地址后选择执行资源');
      const nullValue: unknown = props.config.nullValue ?? defaultCsvNullValue;
      if (props.toolCode === 'read-csv' && !validCsvNullValue(nullValue))
        result.push('空值标记必须为1至64个非控制字符，不能使用空字符串');
    } else {
      if (legacyFile.value)
        result.push('请明确切换到文件读取配置，历史HTTP数组配置不会自动转换');
      if (!props.config.fileId) result.push('请上传附件');
      if (props.config.schemaFileKey !== fileKey())
        result.push('文件或起始行列已变化，请重新解析并确认属性字段');
    }
    if (
      props.toolCode === 'read-excel' &&
      ![props.config.startRow, props.config.startColumn].every(
        (value) => Number.isSafeInteger(value) && value > 0,
      )
    )
      result.push('起始行、起始列必须为大于零的整数');
  }
  if (isKafka.value) {
    if (legacyKafka.value)
      result.push('请明确清除旧连接参数后选择平台数据连接');
    if (!props.config.databaseId) result.push('请选择平台数据连接');
    if (!isOutput.value && kafkaConnectionMismatch.value)
      result.push('请选择Kafka类型的数据源连接');
    if (!props.config.topic?.trim()) result.push('请填写主题名称');
    if (!isOutput.value) result.push(...validateKafkaInputPaths(rows.value));
    try {
      parseReferenceParameters(props.config.configParameters);
    } catch (error) {
      result.push(error instanceof Error ? error.message : '配置参数格式无效');
    }
    if (
      isOutput.value &&
      props.config.batchSize !== undefined &&
      (!Number.isSafeInteger(props.config.batchSize) ||
        props.config.batchSize < 1)
    )
      result.push('单次写入字节必须为正整数');
  }
  if (legacyMapping.value)
    result.push('历史正则配置尚未转换，请获取字段或新增字段后确认');
  result.push(...validateReferenceFields(rows.value, !isMapping.value));
  if (tabularUpload.value) result.push(...validateFileInputDates(rows.value));
  if (
    removed.value.some((name) => !name.trim()) ||
    new Set(removed.value).size !== removed.value.length
  )
    result.push('删除字段名称不能为空或重复');
  if (
    removed.value.some((name) =>
      rows.value.some((field) => field.name === name),
    )
  )
    result.push('同一字段不能同时修改与删除');
  issues.value = result;
  return result.length === 0;
}
defineExpose({ validate });
</script>

<template>
  <div
    class="qi-reference-editor"
    :class="{
      'qi-io-reference-editor': !isMapping,
      'qi-file-reference-editor': isFile,
      'qi-tabular-file-reference': tabularUpload,
    }"
    :data-tool-code="toolCode"
  >
    <Alert
      v-if="legacyFile"
      type="warning"
      message="此历史节点按HTTP文件地址读取，JSON数组与JSONL语义不同，原配置仍保留。"
    >
      <template #action>
        <Button size="small" @click="adoptFile"> 改用上传文件读取 </Button>
      </template>
    </Alert>
    <Alert
      v-if="legacyKafka"
      type="warning"
      message="此历史节点有直连参数。请将认证信息维护到平台数据连接，节点不再接收密码。"
    >
      <template #action>
        <Button size="small" @click="clearLegacyKafka"> 清除旧连接参数 </Button>
      </template>
    </Alert>
    <Alert
      v-if="legacyMapping"
      type="warning"
      message="此历史节点使用正则映射。获取字段后将采用新的显式字段配置，原正则配置仍保留。"
    />
    <Alert v-if="failed" type="error" :message="failed">
      <template v-if="isKafka" #action>
        <Button size="small" @click="loadConnections">重试</Button>
      </template>
    </Alert>
    <Alert v-if="issues.length" type="error" :message="issues.join('；')" />
    <p v-for="warning in warnings" :key="warning" class="qi-reference-notice">
      {{ warning }}
    </p>
    <template v-if="isFile && !managedFile">
      <h3 v-if="toolCode === 'read-json'" class="qi-reference-io-heading">
        上传文件
      </h3>
      <p v-if="fileNeedsParsing" class="qi-reference-notice" role="status">
        文件或起始行列已变化，请重新解析并确认属性字段。
      </p>
      <p
        v-if="partialSchema && warnings.length === 0"
        class="qi-reference-notice"
      >
        请确认解析后的属性字段类型。
      </p>
      <div
        class="qi-reference-file-grid"
        :class="{ 'qi-reference-jsonl-upload': toolCode === 'read-json' }"
      >
        <div class="qi-reference-form-row qi-reference-file-upload">
          <label><em>*</em>{{ toolCode === 'read-json' ? '上传文件' : '上传附件' }}</label>
          <div>
            <div class="qi-reference-file-actions">
              <Upload
                :before-upload="upload"
                :show-upload-list="false"
                :accept="
                  fileExtensions.map((extension) => `.${extension}`).join(',')
                "
                :disabled="disabled || uploading || parsing || legacyFile"
              >
                <Button
                  class="qi-reference-button"
                  :loading="uploading"
                  :disabled="disabled || parsing || legacyFile"
                >
                  <ReferenceIcon name="plus" />选择文件
                </Button>
              </Upload>
            </div>
            <template v-if="toolCode === 'read-json'">
              <p class="qi-reference-file-tip">
                仅支持上传单个 <strong>.jsonl</strong> 文件
              </p>
              <p class="qi-reference-file-tip">
                要求文件中每行均为一条 JSON 记录，且每条 JSON 的字段结构保持一致
              </p>
              <p class="qi-reference-file-tip">
                文件大小不超过 <strong>50MB</strong>
              </p>
            </template>
            <p v-else class="qi-reference-file-tip">
              仅支持上传
              <strong>{{
                fileExtensions.map((extension) => `.${extension}`).join(' / ')
              }}</strong>
              格式的单个文件，大小不超过<strong>50MB</strong>
            </p>
            <div v-if="config.fileId" class="qi-reference-attachment">
              <ReferenceIcon name="file" />
              <Button
                class="qi-reference-attachment-name"
                type="link"
                :disabled="!canDownloadAttachment"
                @click="downloadAttachment"
              >
                {{ config.fileName || '已上传' }}
              </Button>
              <time title="本次上传日期">{{ attachmentDate }}</time>
              <Button
                type="link"
                aria-label="下载附件"
                :title="
                  canDownloadAttachment
                    ? '下载附件'
                    : '此历史附件没有可用下载地址'
                "
                :disabled="!canDownloadAttachment"
                @click="downloadAttachment"
              >
                ↓
              </Button>
              <Button
                type="link"
                aria-label="移除"
                title="删除附件"
                :disabled="disabled"
                @click="detachFile"
              >
                <ReferenceIcon name="delete" />
              </Button>
            </div>
          </div>
        </div>
        <template v-if="toolCode === 'read-excel'">
          <div class="qi-reference-form-row">
            <label title="从1开始计数，输入2表示跳过第一行标题"><em>*</em>起始行</label>
            <InputNumber
              :value="config.startRow"
              :disabled="disabled || parsing"
              :min="1"
              :precision="0"
              aria-label="Excel起始行"
              placeholder="请输入起始行"
              @update:value="(value) => setConfig('startRow', value)"
            />
          </div>
          <div class="qi-reference-form-row">
            <label title="从1开始计数，输入2表示跳过第一列"><em>*</em>起始列</label>
            <InputNumber
              :value="config.startColumn"
              :disabled="disabled || parsing"
              :min="1"
              :precision="0"
              aria-label="Excel起始列"
              @update:value="(value) => setConfig('startColumn', value)"
            />
          </div>
        </template>
        <div class="qi-reference-file-parse">
          <Button
            :class="
              toolCode === 'read-json'
                ? 'qi-jsonl-parse-button'
                : 'qi-reference-button'
            "
            :type="toolCode === 'read-json' ? 'primary' : 'default'"
            :loading="parsing"
            :disabled="
              disabled ||
              !config.fileId ||
              uploading ||
              legacyFile ||
              invalidExcelStart
            "
            :title="parseDescription"
            @click="parseFile"
          >
            {{
              toolCode === 'read-csv'
                ? '解析csv'
                : toolCode === 'read-excel'
                  ? '解析Excel'
                  : '解析JSONL'
            }}
          </Button>
        </div>
        <div
          v-if="toolCode === 'read-excel' && config.sheetName !== undefined"
          class="qi-reference-form-row"
        >
          <label>工作表</label>
          <Input
            :value="config.sheetName"
            :disabled="disabled"
            aria-label="读取工作表"
            placeholder="留空读取第一个工作表"
            @update:value="(value) => setConfig('sheetName', value)"
          />
        </div>
      </div>
    </template>
    <section v-if="toolCode === 'read-json'" class="qi-reference-json-format">
      <h3 class="qi-reference-io-heading">格式说明</h3>
      <div class="qi-reference-json-guide">
        <div class="qi-reference-json-rules">
          <p><span aria-hidden="true">✓</span>每行一条 JSON 记录</p>
          <p>
            <span aria-hidden="true">✓</span>文件内各行 JSON 字段结构需保持一致
          </p>
          <p>
            <span aria-hidden="true">✓</span>示例字段：station_code、obs_time、water_level、quality_flag
          </p>
        </div>
        <div class="qi-reference-json-samples">
          <p>
            {"station_code":"ST001","obs_time":"2025-05-20
            08:00:00","water_level":12.345,"quality_flag":"1"}
          </p>
          <p>
            {"station_code":"ST002","obs_time":"2025-05-20
            08:00:00","water_level":10.872,"quality_flag":"0"}
          </p>
          <span>（示例仅供参考）</span>
        </div>
      </div>
    </section>
    <div v-if="isKafka" class="qi-reference-kafka-grid">
      <div class="qi-reference-form-row">
        <label><em>*</em>{{ isOutput ? '目标数据连接' : '数据源连接' }}</label><Select
          :value="
            config.databaseId == null ? undefined : String(config.databaseId)
          "
          :options="connectionOptions"
          :disabled="disabled"
          :loading="connectionsLoading"
          show-search
          option-filter-prop="label"
          allow-clear
          placeholder="请选择平台数据连接"
          @update:value="(value) => setConfig('databaseId', value)"
        />
      </div>
      <div class="qi-reference-form-row">
        <label><em>*</em>主题名称</label><Input
          :value="config.topic"
          :disabled="disabled"
          @update:value="(value) => setConfig('topic', value)"
          placeholder="请输入主题名称"
        />
      </div>
      <div class="qi-reference-form-row">
        <label>配置参数</label><TextArea
          v-model:value="parameters"
          :disabled="disabled"
          :rows="4"
          placeholder="例如 {&quot;group.id&quot;:&quot;demo_test&quot;}"
          :maxlength="256"
          :show-count="isOutput ? true : { formatter: referenceCount }"
        />
      </div>
      <Alert
        v-if="!isOutput && kafkaConnectionMismatch"
        type="warning"
        message="当前数据源并非Kafka类型，请重新选择Kafka连接。"
      />
      <Alert v-if="parametersError" type="error" :message="parametersError" />
    </div>
    <template v-if="isOutput">
      <h3 class="qi-reference-io-heading">属性字段</h3>
      <ReferenceOutputSelection
        :config="config"
        :input-fields="inputFields"
        :disabled="disabled"
      />
    </template>
    <section v-if="toolCode === 'read-json'" class="qi-jsonl-reference-fields">
      <h3 class="qi-reference-io-heading">属性字段</h3>
      <table aria-label="JSONL属性字段">
        <thead>
          <tr>
            <th>序号</th>
            <th>字段名称</th>
            <th>中文名称</th>
            <th>JSON路径</th>
            <th>字段类型</th>
            <th :aria-sort="metadataSortState('length')">
              <button
                type="button"
                aria-label="按字段长度排序"
                @click="sortMetadata('length')"
              >
                字段长度<span aria-hidden="true">{{
                  metadataSort?.key === 'length'
                    ? metadataSort.direction === 1
                      ? ' ▴'
                      : ' ▾'
                    : ' ↕'
                }}</span>
              </button>
            </th>
            <th :aria-sort="metadataSortState('precision')">
              <button
                type="button"
                aria-label="按小数精度排序"
                @click="sortMetadata('precision')"
              >
                小数精度<span aria-hidden="true">{{
                  metadataSort?.key === 'precision'
                    ? metadataSort.direction === 1
                      ? ' ▴'
                      : ' ▾'
                    : ' ↕'
                }}</span>
              </button>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(field, index) in jsonlRows" :key="rows.indexOf(field)">
            <td>{{ index + 1 }}</td>
            <td>{{ field.name || '-' }}</td>
            <td>{{ field.comment || '-' }}</td>
            <td>{{ field.jsonPath || '-' }}</td>
            <td>{{ field.type || '-' }}</td>
            <td>{{ field.length ?? '-' }}</td>
            <td>{{ field.precision ?? '-' }}</td>
          </tr>
          <tr v-if="!rows.length">
            <td colspan="7" class="qi-reference-empty">暂无记录</td>
          </tr>
        </tbody>
      </table>
    </section>
    <component
      :is="isOutput || toolCode === 'read-json' ? 'details' : 'div'"
      :class="{
        'qi-reference-execution-options': isOutput || toolCode === 'read-json',
      }"
    >
      <summary v-if="isOutput || toolCode === 'read-json'">
        高级字段配置
      </summary>
      <div class="qi-reference-section-header">
        <h3 v-if="isMapping">需要选择与修改的字段</h3>
        <h3 v-else>{{ isOutput ? '字段映射' : '属性字段' }}</h3>
        <div>
          <Button
            v-if="isMapping"
            class="qi-reference-button"
            :disabled="disabled || !inputFields?.length"
            @click="requestReferenceFields"
          >
            获取字段
          </Button>
          <Dropdown v-if="isOutput" :trigger="['click']">
            <Button
              class="qi-reference-button"
              :disabled="disabled || !inputFields?.length"
            >
              同名自动映射
            </Button>
            <template #popupRender>
              <Menu>
                <MenuItem @click="resolveFields('addNewOnly')">
                  只增加新字段
                </MenuItem>
                <MenuItem @click="resolveFields('addAll')">
                  增加所有字段
                </MenuItem>
                <MenuItem @click="resolveFields('clearAndAddAll')">
                  清空并增加所有字段
                </MenuItem>
              </Menu>
            </template>
          </Dropdown>
          <Button
            class="qi-reference-button"
            :disabled="disabled"
            @click="addRow"
          >
            <ReferenceIcon name="plus" />新增
          </Button>
        </div>
      </div>
      <div class="qi-reference-fields-scroll">
        <table
          class="qi-reference-fields-table"
          :class="{
            'qi-reference-field-details':
              isMapping || toolCode === 'read-json' || managedFile,
          }"
        >
          <thead>
            <tr>
              <th>序号</th>
              <th>字段名称</th>
              <th v-if="isMapping">字段别名</th>
              <th v-if="toolCode === 'read-json'">中文名称</th>
              <th v-if="toolCode === 'read-json'">路径</th>
              <th>字段类型</th>
              <th v-if="isKafka && !isOutput">JSON解析取值</th>
              <template
                v-if="isMapping || toolCode === 'read-json' || managedFile"
              >
                <th>字段长度</th>
                <th>{{ isMapping ? '字段精度' : '小数精度' }}</th>
              </template>
              <th v-if="toolCode === 'read-excel' || toolCode === 'read-csv'">
                日期格式
              </th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(field, index) in rows"
              :key="index"
              :draggable="isMapping && !disabled"
              :tabindex="isMapping && !disabled ? 0 : undefined"
              :aria-label="
                isMapping ? `第${index + 1}行，按Alt与上下键排序` : undefined
              "
              @dragstart="dragged = index"
              @dragover.prevent
              @drop.prevent="move(dragged, index)"
              @keydown.alt.up.prevent="move(index, index - 1)"
              @keydown.alt.down.prevent="move(index, index + 1)"
            >
              <td>{{ index + 1 }}</td>
              <td>
                <Input
                  v-if="isMapping"
                  :value="field.name"
                  :disabled="disabled"
                  @update:value="(value) => updateField(field, 'name', value)"
                  placeholder="字段名称"
                /><span v-else>{{ field.name || '-' }}</span>
              </td>
              <td v-if="isMapping">
                <Input
                  :value="field.alias"
                  :disabled="disabled"
                  @update:value="(value) => updateField(field, 'alias', value)"
                  placeholder="请输入新的字段名称"
                />
              </td>
              <td v-if="toolCode === 'read-json'">
                {{ field.comment || '-' }}
              </td>
              <td v-if="toolCode === 'read-json'">
                {{ field.jsonPath || '-' }}
              </td>
              <td>
                <Select
                  v-if="isMapping"
                  :value="field.type"
                  :disabled="disabled"
                  @update:value="(value) => updateField(field, 'type', value)"
                  :options="referenceFieldTypes"
                  show-search
                  allow-clear
                  placeholder="请选择字段类型"
                />
                <span v-else>{{ field.type || '-' }}</span>
              </td>
              <td v-if="isKafka && !isOutput">
                {{ field.key ?? field.jsonPath ?? '-' }}
              </td>
              <template
                v-if="isMapping || toolCode === 'read-json' || managedFile"
              >
                <td>
                  <Input
                    v-if="isMapping"
                    :value="field.length"
                    :disabled="disabled"
                    @update:value="
                      (value) => updateField(field, 'length', value)
                    "
                    inputmode="numeric"
                  /><span v-else>{{ field.length ?? '-' }}</span>
                </td>
                <td>
                  <Input
                    v-if="isMapping"
                    :value="field.precision"
                    :disabled="disabled"
                    @update:value="
                      (value) => updateField(field, 'precision', value)
                    "
                    inputmode="numeric"
                  />
                  <span v-else>{{ field.precision ?? '-' }}</span>
                </td>
              </template>
              <td v-if="toolCode === 'read-excel' || toolCode === 'read-csv'">
                {{ field.format || '-' }}
              </td>
              <td>
                <Button
                  v-if="!isMapping"
                  type="link"
                  :disabled="disabled"
                  @click="openFieldEditor(index)"
                >
                  <ReferenceIcon name="edit" />修改
                </Button>
                <Button
                  v-if="!tabularUpload"
                  type="link"
                  danger
                  :disabled="disabled"
                  @click="removeField(index)"
                >
                  <ReferenceIcon name="delete" />删除
                </Button>
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td :colspan="10" class="qi-reference-empty">
                <template v-if="tabularUpload || (isKafka && !isOutput)">
                  <img
                    src="/assets/qdata-integration/node-empty-reference.png"
                    alt=""
                    aria-hidden="true"
                  />
                  <p>暂无记录</p>
                </template>
                <template v-else>
                  暂无字段，请解析文件、新增字段或获取上游字段
                </template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </component>
    <details
      v-if="tabularUpload && rows.length"
      class="qi-reference-execution-options"
    >
      <summary>高级字段维护</summary>
      <div
        v-for="(field, index) in rows"
        :key="index"
        class="qi-reference-file-actions"
      >
        <span>{{ field.name || `第${index + 1}行` }}</span>
        <Button
          type="link"
          danger
          :disabled="disabled"
          @click="removeField(index)"
        >
          删除
        </Button>
      </div>
    </details>
    <section v-if="isMapping" class="qi-reference-removed-section">
      <div class="qi-reference-section-header">
        <h3>需要移除的字段</h3>
        <Button
          :disabled="disabled"
          class="qi-reference-button"
          @click="addRemovedField"
        >
          <ReferenceIcon name="plus" />新增移除字段
        </Button>
      </div>
      <div class="qi-reference-fields-scroll">
        <table class="qi-reference-fields-table">
          <thead>
            <tr>
              <th>序号</th>
              <th>字段名称</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(name, index) in removed" :key="index">
              <td>{{ index + 1 }}</td>
              <td>
                <AutoComplete
                  :value="name"
                  :disabled="disabled"
                  :options="fieldOptions"
                  @update:value="(value) => setRemoved(index, value)"
                />
              </td>
              <td>
                <Button
                  type="link"
                  danger
                  :disabled="disabled"
                  @click="removeRemoved(index)"
                >
                  <ReferenceIcon name="delete" />删除
                </Button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <h3 v-if="isOutput" class="qi-reference-io-heading">输出配置</h3>
    <div v-if="isOutput" class="qi-reference-form-row">
      <label>单次写入字节</label><InputNumber
        :value="config.batchSize"
        :disabled="disabled"
        @update:value="(value) => setConfig('batchSize', value)"
        :min="1"
        :precision="0"
      />
    </div>
    <details
      v-if="supportsManagedFile"
      class="qi-reference-execution-options"
      :open="managedFile"
    >
      <summary>执行配置</summary>
      <div class="qi-reference-form-row">
        <label>文件用途</label>
        <div class="qi-reference-file-actions">
          <Button
            :disabled="disabled"
            :type="fileMode === 'upload' ? 'primary' : 'default'"
            :aria-pressed="fileMode === 'upload'"
            @click="setFileMode('upload')"
          >
            上传解析
</Button><Button
            :disabled="disabled"
            :type="fileMode === 'managed' ? 'primary' : 'default'"
            :aria-pressed="fileMode === 'managed'"
            @click="setFileMode('managed')"
          >
            执行资源
          </Button>
        </div>
      </div>
      <template v-if="managedFile">
        <div class="qi-reference-form-row">
          <label>执行资源编号</label><Input
            :value="config.resourceBindingId"
            :disabled="disabled"
            aria-label="执行资源编号"
            placeholder="填写管理员为当前任务授权的资源编号"
            @update:value="(value) => setConfig('resourceBindingId', value)"
          />
        </div>
        <div class="qi-reference-form-row">
          <label>相对文件路径</label><Input
            :value="config.relativePath || config.path"
            :disabled="disabled"
            aria-label="相对文件路径"
            placeholder="例如 input/data.csv"
            @update:value="
              (value) => {
                setConfig('relativePath', value);
                if (!disabled && config.path !== undefined)
                  setConfig('path', value);
              }
            "
          />
        </div>
        <div v-if="toolCode !== 'read-excel'" class="qi-reference-form-row">
          <label>文件编码</label><Select
            :value="config.encoding || config.charset || 'UTF-8'"
            :disabled="disabled"
            aria-label="文件编码"
            :options="
              ['UTF-8', 'GBK', 'GB2312', 'ISO-8859-1'].map((value) => ({
                label: value,
                value,
              }))
            "
            @update:value="
              (value) => {
                setConfig('encoding', value);
                if (!disabled && config.charset !== undefined)
                  setConfig('charset', value);
              }
            "
          />
        </div>
        <div v-if="toolCode === 'read-csv'" class="qi-reference-form-grid">
          <label>首行为表头<Select
              :value="
                config.hasHeader === true || config.hasHeader === 'true'
                  ? 'true'
                  : 'false'
              "
              :options="[
                { label: '是', value: 'true' },
                { label: '否', value: 'false' },
              ]"
              :disabled="disabled"
              @update:value="
                (value) => {
                  setConfig('hasHeader', value === 'true');
                  if (config.startRow !== undefined)
                    setConfig('startRow', value === 'true' ? 2 : 1);
                }
              "
          /></label>
          <label>分隔符<Input
              :value="config.fieldDelimiter || config.delimiter || ','"
              :disabled="disabled"
              aria-label="文件分隔符"
              @update:value="
                (value) => {
                  setConfig('fieldDelimiter', value);
                  if (!disabled && config.delimiter !== undefined)
                    setConfig('delimiter', value);
                }
              "
          /></label>
          <label>空值标记<Input
              :value="config.nullValue ?? defaultCsvNullValue"
              :disabled="disabled"
              aria-label="文件空值标记"
              placeholder="与空文本区分，例如 \N"
              @update:value="(value) => setConfig('nullValue', value)"
          /></label>
          <label>起始列<InputNumber
              :value="config.startColumn ?? 1"
              :min="1"
              :precision="0"
              :disabled="disabled"
              @update:value="(value) => setConfig('startColumn', value)"
          /></label>
        </div>
        <div v-if="toolCode === 'read-excel'" class="qi-reference-form-grid">
          <label>工作表<Input
              :value="config.sheetName"
              :disabled="disabled"
              aria-label="执行工作表"
              placeholder="留空读取第一个工作表"
              @update:value="(value) => setConfig('sheetName', value)"
          /></label>
          <label>标题行<InputNumber
              :value="config.headerRow ?? 1"
              :min="0"
              :precision="0"
              :disabled="disabled"
              @update:value="(value) => setConfig('headerRow', value)"
          /></label>
          <label>起始行<InputNumber
              :value="config.startRow ?? 1"
              :min="1"
              :precision="0"
              :disabled="disabled"
              @update:value="(value) => setConfig('startRow', value)"
          /></label>
          <label>结束行<InputNumber
              :value="config.endRow ?? 0"
              :min="0"
              :precision="0"
              :disabled="disabled"
              @update:value="(value) => setConfig('endRow', value)"
          /></label>
          <label>起始列<InputNumber
              :value="config.startColumn ?? 1"
              :min="1"
              :precision="0"
              :disabled="disabled"
              @update:value="(value) => setConfig('startColumn', value)"
          /></label>
          <label>公式读取<Select
              :value="config.formulaMode ?? 'LITERAL'"
              :options="[
                { label: '公式文本', value: 'LITERAL' },
                { label: '已保存的缓存值', value: 'CACHED' },
              ]"
              :disabled="disabled"
              aria-label="公式读取方式"
              @update:value="(value) => setConfig('formulaMode', value)"
          /></label>
          <label>跳过空行<Select
              :value="config.skipEmptyRows === false ? 'false' : 'true'"
              :options="[
                { label: '是', value: 'true' },
                { label: '否', value: 'false' },
              ]"
              :disabled="disabled"
              @update:value="
                (value) => setConfig('skipEmptyRows', value === 'true')
              "
          /></label>
        </div>
        <p class="qi-reference-notice">
          {{
            toolCode === 'read-excel'
              ? '执行资源须由管理员授权。行列从1开始，结束行0表示读到末尾，标题行0表示无标题；公式使用文本或已保存的缓存值，运行时不重新计算。'
              : '执行资源须由管理员授权给当前任务。请声明完整源字段；CSV 从第一条数据开始读取，起始列从1开始，JSONL 使用顶层字段映射。'
          }}
        </p>
      </template>
      <p class="qi-reference-notice">
        用于字段解析，运行请选择执行资源；执行时仍受该资源的行数和字节预算约束。
      </p>
    </details>
    <ReferenceFieldMergeDialog
      v-model:open="mergeOpen"
      :existing-count="rows.length"
      :incoming-count="inputFields?.filter((field) => field.name).length ?? 0"
      :disabled="disabled"
      @resolve="resolveFields"
    />
    <FileInputFieldModal
      v-if="tabularUpload"
      :open="fieldEditorOpen"
      :field="fieldDraft"
      :disabled="disabled"
      :errors="fieldErrors"
      @update:field="updateFieldDraft"
      @save="saveFieldEditor"
      @cancel="closeFieldEditor"
    />
    <KafkaInputFieldModal
      v-else-if="isKafka && !isOutput"
      :open="fieldEditorOpen"
      :field="fieldDraft"
      :disabled="disabled"
      :errors="fieldErrors"
      @update:field="updateFieldDraft"
      @save="saveFieldEditor"
      @cancel="closeFieldEditor"
    />
    <ReferenceFieldEditModal
      v-else
      :open="fieldEditorOpen"
      :field="fieldDraft"
      :disabled="disabled"
      :errors="fieldErrors"
      :show-key="isKafka && !isOutput"
      :show-json-path="toolCode === 'read-json'"
      :show-comment="toolCode === 'read-json'"
      :show-metadata="
        toolCode === 'read-excel' || toolCode === 'read-json' || managedFile
      "
      :show-format="isFile"
      @update:field="updateFieldDraft"
      @save="saveFieldEditor"
      @cancel="closeFieldEditor"
    />
  </div>
</template>

<style scoped>
.qi-io-reference-editor {
  --qi-io-label-width: 110px;
}

.qi-io-reference-editor[data-tool-code='read-json'] {
  --qi-io-label-width: 110px;
}

.qi-io-reference-editor[data-tool-code='read-excel'] {
  --qi-io-label-width: 110px;
}

.qi-io-reference-editor :deep(.qi-reference-form-row) {
  grid-template-columns: var(--qi-io-label-width) minmax(0, 1fr);
  gap: 12px;
}

.qi-io-reference-editor .qi-reference-form-grid label {
  grid-template-columns: var(--qi-io-label-width) minmax(0, 1fr);
  gap: 12px;
}

.qi-io-reference-editor .qi-reference-fields-table th:first-child {
  width: 80px;
}

.qi-io-reference-editor .qi-reference-fields-table th:last-child {
  width: 240px;
}

.qi-io-reference-editor[data-tool-code='read-json']
  .qi-reference-fields-table
  th:last-child {
  width: 150px;
}

.qi-reference-execution-options {
  padding: 10px 12px;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}

.qi-reference-execution-options summary {
  margin-bottom: 10px;
  color: #606266;
  cursor: pointer;
}

.qi-reference-execution-options[open] {
  display: grid;
  gap: 14px;
}

.qi-reference-io-heading {
  margin: 0;
  font-size: 14px;
  font-weight: 400;
  color: #303133;
}

.qi-reference-json-format {
  padding: 10px 14px;
  font-size: 12px;
  color: #606266;
  background: #f5f7fa;
}

.qi-reference-json-format p {
  margin: 4px 0;
}
</style>

<style scoped>
[data-tool-code='column-map'] .qi-reference-fields-table th:first-child {
  width: 80px;
}

[data-tool-code='column-map'] .qi-reference-fields-table th:last-child {
  width: 120px;
}

.qi-reference-removed-section {
  display: grid;
  gap: 16px;
}
</style>

<style scoped>
.qi-reference-kafka-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px 20px;
}

.qi-reference-kafka-grid > .qi-reference-form-row:nth-child(3) {
  grid-column: 1 / -1;
}

.qi-reference-file-parse {
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 768px) {
  .qi-reference-kafka-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>

<style scoped>
.qi-reference-json-samples {
  padding: 14px 18px;
  margin-top: 18px;
  overflow: auto;
  white-space: nowrap;
  background: #f1f1f5;
}

.qi-reference-json-samples span {
  display: block;
  margin-top: 20px;
}

.qi-jsonl-reference-fields {
  overflow: auto;
}

.qi-jsonl-reference-fields h3 {
  margin: 0 0 16px;
  font-size: 14px;
  font-weight: 400;
}

.qi-jsonl-reference-fields table {
  width: 100%;
  min-width: 780px;
  border-collapse: collapse;
}

.qi-jsonl-reference-fields th {
  padding: 10px;
  font-weight: 400;
  text-align: left;
  background: #f1f1f5;
}

.qi-jsonl-reference-fields td {
  height: 42px;
  padding: 8px 10px;
  border-bottom: 1px solid #ebeef5;
}

.qi-jsonl-reference-fields th:first-child {
  width: 80px;
}
</style>

<style scoped>
.qi-reference-file-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
}

.qi-reference-file-grid > .qi-reference-form-row {
  min-width: 0;
}

.qi-reference-file-grid .ant-input-number {
  width: 100%;
}

.qi-reference-file-grid .qi-reference-file-parse {
  align-items: flex-start;
  justify-content: flex-start;
  padding-left: calc(var(--qi-io-label-width) + 12px);
}

.qi-reference-file-tip {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 20px;
  color: #909399;
}

.qi-reference-jsonl-upload {
  grid-template-columns: minmax(0, 1fr) auto;
  padding-bottom: 8px;
}

.qi-reference-jsonl-upload .qi-reference-file-parse {
  padding-left: 0;
}

.qi-file-reference-editor .qi-reference-section-header {
  display: grid;
  gap: 16px;
  justify-content: start;
}

.qi-file-reference-editor .qi-reference-io-heading,
.qi-file-reference-editor .qi-reference-section-header h3 {
  padding-left: 14px;
  border-left: 4px solid #2f62ff;
}

.qi-file-reference-editor .qi-reference-json-format {
  display: grid;
  gap: 10px;
  padding: 0;
  background: transparent;
}

.qi-file-reference-editor .qi-reference-json-samples {
  margin-top: 2px;
  overflow-wrap: anywhere;
  white-space: normal;
}

.qi-jsonl-reference-fields th button {
  padding: 0;
  font: inherit;
  color: inherit;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.qi-jsonl-reference-fields th button:focus-visible {
  outline: 2px solid #2f62ff;
  outline-offset: 3px;
}

.qi-jsonl-reference-fields .qi-reference-empty {
  height: 180px;
  color: #909399;
  text-align: center;
}

.qi-file-reference-editor .qi-reference-fields-table th:last-child,
.qi-file-reference-editor .qi-reference-fields-table td:last-child {
  position: sticky;
  right: 0;
  z-index: 1;
  background: #fff;
}

.qi-file-reference-editor .qi-reference-fields-table th:last-child {
  z-index: 2;
  background: #f1f1f5;
}

.qi-file-reference-editor
  .qi-reference-fields-table
  tr:nth-child(even)
  td:last-child {
  background: #fafafa;
}

.qi-file-reference-editor
  .qi-reference-fields-table
  tbody
  tr:hover
  td:last-child {
  background: #eaf0ff;
}

@media (max-width: 768px) {
  .qi-reference-file-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .qi-reference-file-grid .qi-reference-file-parse {
    padding-left: calc(var(--qi-io-label-width) + 12px);
  }
}
</style>

<style scoped>
.qi-reference-attachment {
  display: grid;
  grid-template-columns: 16px minmax(0, 1fr) auto 24px 24px;
  gap: 8px;
  align-items: center;
  margin-top: 10px;
}

.qi-reference-attachment time {
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
}

.qi-reference-attachment .qi-reference-attachment-name {
  display: block;
  min-width: 0;
  padding: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  text-align: left;
}

.qi-reference-file-tip strong {
  font-weight: 600;
  color: #f56c6c;
}

.qi-tabular-file-reference .qi-reference-fields-table .qi-reference-empty {
  height: 274px;
}

.qi-tabular-file-reference .qi-reference-empty img {
  display: block;
  width: min(190px, 100%);
  height: auto;
  margin: 0 auto 18px;
}

.qi-tabular-file-reference .qi-reference-empty p {
  margin: 0;
}
</style>

<style scoped>
.qi-reference-jsonl-upload {
  position: relative;
  grid-template-columns: minmax(0, 1fr);
}

.qi-reference-jsonl-upload .qi-reference-file-upload > div {
  min-width: 0;
  min-height: 150px;
  padding: 20px 16px;
  border: 1px dashed #cdd7e8;
  border-radius: 4px;
}

.qi-reference-jsonl-upload .qi-reference-file-tip {
  margin-top: 10px;
  font-size: 14px;
  color: #303133;
}

.qi-reference-jsonl-upload .qi-reference-file-parse {
  position: absolute;
  right: 17px;
  bottom: 17px;
  padding: 0;
}

.qi-reference-jsonl-upload .qi-reference-attachment {
  max-width: calc(100% - 130px);
}

.qi-reference-json-guide {
  display: grid;
  grid-template-columns: 40% minmax(0, 1fr);
  gap: 10px;
  padding: 10px 18px;
  color: #303133;
  background: #eef6fd;
  border-radius: 3px;
}

.qi-reference-json-rules p {
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: 14px;
  line-height: 24px;
}

.qi-reference-json-rules span {
  display: inline-grid;
  flex: none;
  place-items: center;
  width: 12px;
  height: 12px;
  font-size: 10px;
  line-height: 1;
  color: white;
  background: #2f62ff;
  border-radius: 50%;
}

.qi-file-reference-editor .qi-reference-json-guide .qi-reference-json-samples {
  min-width: 0;
  padding: 4px 0 0 10px;
  margin: 0;
  overflow: auto;
  font-family: monospace;
  white-space: nowrap;
  background: transparent;
  border-left: 1px solid #d5e4fa;
}

.qi-reference-json-guide .qi-reference-json-samples span {
  text-align: right;
}

[data-tool-code='read-kafka'] .qi-reference-section-header {
  display: grid;
  gap: 16px;
  justify-content: start;
}

[data-tool-code='read-kafka'] .qi-reference-section-header h3 {
  padding-left: 14px;
  border-left: 4px solid #2f62ff;
}

[data-tool-code='read-kafka'] .qi-reference-empty {
  height: 240px;
}

@media (max-width: 768px) {
  .qi-reference-json-guide {
    grid-template-columns: minmax(0, 1fr);
  }

  .qi-reference-jsonl-upload .qi-reference-file-upload > div {
    padding-bottom: 62px;
  }

  .qi-reference-jsonl-upload .qi-reference-attachment {
    max-width: none;
  }
}
</style>

<style scoped>
[data-tool-code='read-kafka'] .qi-reference-empty img {
  display: block;
  width: min(190px, 100%);
  height: auto;
  margin: 0 auto 18px;
}

[data-tool-code='read-kafka'] .qi-reference-empty p {
  margin: 0;
}
</style>
