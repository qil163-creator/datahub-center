<script lang="ts" setup>
import type { DataAssetApi } from '#/api/asset/dataasset';
import type { AssetRegistrationApi } from '#/api/asset/dataasset/registration';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';
import { handleTree } from '@vben/utils';

import {
  Alert,
  Button,
  Input,
  message,
  Modal,
  RadioGroup,
  Select,
  Table,
  Tag,
  TreeSelect,
} from 'antdv-next';

import {
  ASSET_TYPE_OPTIONS,
  batchRegisterDataAsset,
} from '#/api/asset/dataasset';
import {
  getAssetRegistrationContext,
  getAssetRegistrationOptions,
  previewAssetRegistrationName,
  registerGenericDataAssets,
} from '#/api/asset/dataasset/registration';
import {
  createLatestWins,
  parseResourceId,
  resourceIdKey,
} from '#/utils/resource-id';

import MetadataPicker from './modules/metadata-picker.vue';

defineOptions({ name: 'AssetMap' });
function registrationImage(name: string) {
  return `/assets/qdata-map/${name}.png`;
}
const route = useRoute();
const router = useRouter();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canRegister = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes(['asset:data-asset:create']),
);
const identity = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
interface DraftRow {
  meta: AssetRegistrationApi.Candidate;
  assetName: string;
  categoryId?: DataAssetApi.Id;
  dataDomainId?: DataAssetApi.Id;
  subjectDomainId?: DataAssetApi.Id;
  entityId?: DataAssetApi.Id;
  granularityEntityIds?: DataAssetApi.Id[];
  description?: string;
  englishName?: string;
  tableType?: string;
  layer?: string;
  updateCycle?: string;
  nameCase?: string;
  customNames?: boolean;
  context?: AssetRegistrationApi.Context;
  contextLoading?: boolean;
  contextError?: string;
}
type ConfigField =
  | 'dataDomainId'
  | 'entityId'
  | 'granularityEntityIds'
  | 'layer'
  | 'nameCase'
  | 'subjectDomainId'
  | 'tableType'
  | 'updateCycle';
interface BatchConfig {
  categoryId?: DataAssetApi.Id;
  dataDomainId?: DataAssetApi.Id;
  subjectDomainId?: DataAssetApi.Id;
  entityId?: DataAssetApi.Id;
  granularityEntityIds?: DataAssetApi.Id[];
  open: boolean;
  tableType?: string;
  layer?: string;
  updateCycle?: string;
  nameCase?: string;
  tableNameUpdateMode: number;
}
function parseType(value: unknown) {
  if (String(value) === '2') return 2;
  if (String(value) === '3') return 3;
  return 1;
}
function emptyContext(): AssetRegistrationApi.Context {
  return {
    dataDomains: [],
    subjectDomains: [],
    layers: [],
    entities: [],
    granularities: [],
    namingRules: [],
  };
}
const assetType = ref(parseType(route.query.assetType));
const saving = ref(false);
const optionError = ref('');
const drafts = ref<DraftRow[]>([]);
const selectedRowKeys = ref<string[]>([]);
const options = ref<DataAssetApi.Facets>({});
const baseContext = ref<AssetRegistrationApi.Context>(emptyContext());
const batchContext = ref<AssetRegistrationApi.Context>(emptyContext());
const batchLoading = ref(false);
const batchApplying = ref(false);
const batchError = ref('');
const guard = createLatestWins();
const submitGuard = createLatestWins();
const batchGuard = createLatestWins();
const batchContextGuard = createLatestWins();
const rowGuards = new Map<string, ReturnType<typeof createLatestWins>>();
let alive = true;
let active = true;
const categoryTree = computed(
  () =>
    handleTree(
      (options.value.categories ?? []).map((item) => ({
        id: item.key,
        parentId: item.parentKey || '0',
        name: item.label,
      })),
    ) as { children?: unknown[]; id: string; name: string }[],
);
const batch = reactive<BatchConfig>({ open: false, tableNameUpdateMode: 0 });
const domainOptions = computed(() => idOptions(baseContext.value.dataDomains));
function idOptions(items: AssetRegistrationApi.ContextOption[]) {
  return items.map((item) => ({
    label: item.name,
    value: resourceIdKey(item.id),
  }));
}
function subjectOptions(
  context?: AssetRegistrationApi.Context,
  domainId?: DataAssetApi.Id,
) {
  return domainId
    ? idOptions(context?.subjectDomains ?? [])
    : (options.value.subjectDomains ?? []).map((item) => ({
        label: item.label,
        value: item.key,
      }));
}
function layerOptions(context?: AssetRegistrationApi.Context) {
  return (context?.layers ?? baseContext.value.layers)
    .filter((item) => item.code)
    .map((item) => ({ label: item.name, value: item.code }));
}
const [PickerModal, pickerApi] = useVbenModal({
  connectedComponent: MetadataPicker,
  destroyOnClose: true,
});
function openPicker() {
  if (!active || !canRegister.value || saving.value || batchApplying.value)
    return;
  pickerApi
    .setData({
      assetType: assetType.value,
      excludedIds: drafts.value.map((draft) => draft.meta.id!),
    })
    .open();
}
function rowGuard(draft: DraftRow) {
  const key = resourceIdKey(draft.meta.id);
  let result = rowGuards.get(key);
  if (!result) {
    result = createLatestWins();
    rowGuards.set(key, result);
  }
  return result;
}
function queryFor(
  draft: Pick<DraftRow, 'dataDomainId' | 'subjectDomainId' | 'tableType'>,
) {
  return {
    dataDomainId: draft.dataDomainId,
    tableType: draft.tableType,
    subjectDomainId: draft.dataDomainId ? draft.subjectDomainId : undefined,
  };
}
function previewReady(draft: DraftRow) {
  if (!draft.tableType) return false;
  if (['ADS', 'ODS'].includes(draft.tableType)) return true;
  if (!draft.dataDomainId) return false;
  if (draft.tableType === 'DWS')
    return !!draft.subjectDomainId && !!draft.granularityEntityIds?.length;
  if (!draft.entityId) return false;
  const rule = draft.context?.namingRules.find(
    (item) => item.tableType === draft.tableType,
  );
  return !rule?.includeSubjectAbbreviation || !!draft.subjectDomainId;
}
async function generateName(draft: DraftRow, force: boolean) {
  if (
    assetType.value !== 1 ||
    (!force && draft.customNames) ||
    !previewReady(draft)
  )
    return undefined;
  return previewAssetRegistrationName({
    ...queryFor(draft),
    candidateId: draft.meta.id!,
    tableType: draft.tableType!,
    namingCase: draft.nameCase || 'LOWER',
    entityId: draft.entityId,
    granularityEntityIds: draft.granularityEntityIds,
    timeEffect: draft.updateCycle,
  });
}
function acceptName(
  draft: DraftRow,
  result?: AssetRegistrationApi.NamePreview,
) {
  if (!result) return;
  if (!result.assetName?.trim() || !result.businessEnglishName?.trim())
    throw new Error('EMPTY_NAME');
  draft.assetName = result.assetName;
  draft.englishName = result.businessEnglishName;
  draft.customNames = false;
}
async function refreshDraftContext(draft: DraftRow) {
  const ticket = rowGuard(draft).next();
  draft.contextLoading = true;
  draft.contextError = '';
  try {
    const context = await getAssetRegistrationContext(queryFor(draft));
    if (!ticket.isCurrent() || !alive || !active || !canRegister.value) return;
    draft.context = context;
    const generated = await generateName(draft, false);
    if (ticket.isCurrent() && !draft.customNames) acceptName(draft, generated);
  } catch {
    if (ticket.isCurrent())
      draft.contextError = '配置或名称规则读取失败，请重试。';
  } finally {
    if (ticket.isCurrent()) draft.contextLoading = false;
  }
}
function changeRow(draft: DraftRow, field: ConfigField, value: unknown) {
  if (saving.value || batchApplying.value || !canRegister.value) return;
  const parsed = ['dataDomainId', 'entityId', 'subjectDomainId'].includes(field)
    ? parseResourceId(value)
    : value;
  Object.assign(draft, { [field]: parsed });
  if (['dataDomainId', 'tableType'].includes(field)) {
    draft.entityId = undefined;
    draft.granularityEntityIds = undefined;
    if (field === 'dataDomainId') draft.subjectDomainId = undefined;
  }
  if (field === 'subjectDomainId') {
    draft.entityId = undefined;
    draft.granularityEntityIds = undefined;
  }
  void refreshDraftContext(draft);
}
function manualName(draft: DraftRow) {
  draft.customNames = true;
}
function onPicked(rows: AssetRegistrationApi.Candidate[]) {
  if (!alive || !active || !canRegister.value) return;
  const existing = new Set(
    drafts.value.map((draft) => resourceIdKey(draft.meta.id)),
  );
  for (const row of rows) {
    const key = parseResourceId(row.id);
    if (
      !key ||
      existing.has(key) ||
      row.registered ||
      (row.assetType && row.assetType !== assetType.value)
    )
      continue;
    existing.add(key);
    const draft: DraftRow = reactive({
      meta: row,
      assetName:
        row.businessName ||
        row.customComment ||
        row.sourceComment ||
        row.name ||
        row.fileName?.replace(/\.[^.]+$/, '') ||
        row.tableName ||
        '',
      englishName: row.tableName || '',
      tableType: ['ADS', 'DIM', 'DWD', 'DWS', 'ODS'].includes(
        row.tableType || '',
      )
        ? row.tableType
        : undefined,
      layer: row.layer,
      subjectDomainId:
        row.subjectDomainId === undefined
          ? undefined
          : String(row.subjectDomainId),
      description: row.businessDefinition || row.description || undefined,
      nameCase: 'LOWER',
      context: baseContext.value,
    });
    drafts.value.push(draft);
    void refreshDraftContext(draft);
  }
}
function removeDraft(id: DataAssetApi.Id | undefined) {
  const key = resourceIdKey(id);
  rowGuards.get(key)?.invalidate();
  rowGuards.delete(key);
  batchGuard.invalidate();
  batchApplying.value = false;
  drafts.value = drafts.value.filter(
    (draft) => resourceIdKey(draft.meta.id) !== key,
  );
  selectedRowKeys.value = selectedRowKeys.value.filter(
    (value) => value !== key,
  );
}
function openBatch() {
  if (!active || !canRegister.value || saving.value) return;
  if (selectedRowKeys.value.length === 0) {
    message.warning('请先勾选需要批量设置的资产');
    return;
  }
  Object.assign(batch, {
    categoryId: undefined,
    dataDomainId: undefined,
    subjectDomainId: undefined,
    entityId: undefined,
    granularityEntityIds: undefined,
    tableType: undefined,
    layer: undefined,
    updateCycle: undefined,
    nameCase: undefined,
    tableNameUpdateMode: 0,
    open: true,
  });
  batchError.value = '';
  batchContext.value = baseContext.value;
}
async function changeBatch(field: ConfigField, value: unknown) {
  if (batchApplying.value) return;
  Object.assign(batch, { [field]: value });
  if (['dataDomainId', 'tableType'].includes(field)) {
    batch.subjectDomainId = undefined;
    batch.entityId = undefined;
    batch.granularityEntityIds = undefined;
  }
  if (field === 'subjectDomainId') {
    batch.entityId = undefined;
    batch.granularityEntityIds = undefined;
  }
  const ticket = batchContextGuard.next();
  batchLoading.value = true;
  batchError.value = '';
  try {
    const result = await getAssetRegistrationContext(queryFor(batch));
    if (ticket.isCurrent()) batchContext.value = result;
  } catch {
    if (ticket.isCurrent()) batchError.value = '配置选项读取失败，请重试。';
  } finally {
    if (ticket.isCurrent()) batchLoading.value = false;
  }
}
async function applyBatch() {
  if (
    !batch.open ||
    batchApplying.value ||
    batchLoading.value ||
    batchError.value ||
    !active ||
    !canRegister.value ||
    saving.value
  )
    return;
  const ticket = batchGuard.next();
  batchApplying.value = true;
  const selected = drafts.value.filter((draft) =>
    selectedRowKeys.value.includes(resourceIdKey(draft.meta.id)),
  );
  for (const draft of selected) {
    rowGuard(draft).invalidate();
    draft.contextLoading = false;
  }
  try {
    const prepared: { draft: DraftRow; next: DraftRow }[] = [];
    for (const draft of selected) {
      const next: DraftRow = { ...draft };
      if (
        batch.dataDomainId &&
        resourceIdKey(batch.dataDomainId) !== resourceIdKey(next.dataDomainId)
      ) {
        next.subjectDomainId = undefined;
        next.entityId = undefined;
        next.granularityEntityIds = undefined;
      }
      if (batch.tableType && batch.tableType !== next.tableType) {
        next.entityId = undefined;
        next.granularityEntityIds = undefined;
      }
      for (const field of [
        'categoryId',
        'dataDomainId',
        'subjectDomainId',
        'entityId',
        'tableType',
        'layer',
        'updateCycle',
        'nameCase',
      ] as const)
        if (batch[field]) Object.assign(next, { [field]: batch[field] });
      if (batch.granularityEntityIds?.length)
        next.granularityEntityIds = [...batch.granularityEntityIds];
      next.context = await getAssetRegistrationContext(queryFor(next));
      if (!ticket.isCurrent()) return;
      if (
        assetType.value === 1 &&
        batch.tableNameUpdateMode === 1 &&
        !previewReady(next)
      )
        throw new Error('INCOMPLETE_NAMING_CONFIG');
      acceptName(
        next,
        await generateName(next, batch.tableNameUpdateMode === 1),
      );
      if (!ticket.isCurrent()) return;
      prepared.push({ draft, next });
    }
    if (!ticket.isCurrent()) return;
    for (const { draft, next } of prepared) {
      rowGuard(draft).invalidate();
      Object.assign(draft, next, { contextLoading: false, contextError: '' });
    }
    batch.open = false;
  } catch {
    if (ticket.isCurrent())
      message.error('批量配置失败，草稿未修改。请检查配置后重试。');
  } finally {
    if (ticket.isCurrent()) batchApplying.value = false;
  }
}
watch(
  () => batch.open,
  (open) => {
    if (!open) {
      batchGuard.invalidate();
      batchContextGuard.invalidate();
      batchLoading.value = false;
      batchApplying.value = false;
    }
  },
  { flush: 'sync' },
);
function backToList() {
  void router.push(String(route.meta.activePath || '/asset/market/map'));
}
function registrationConfig(draft: DraftRow): DataAssetApi.RegistrationConfig {
  return {
    dataDomainId: draft.dataDomainId,
    entityId: draft.entityId,
    granularityEntityIds: draft.granularityEntityIds,
    businessEnglishName:
      assetType.value === 1
        ? draft.englishName?.trim() || undefined
        : undefined,
    tableType: draft.tableType,
    layer: draft.layer,
    timeEffect: draft.updateCycle,
    namingCase: assetType.value === 1 ? draft.nameCase : undefined,
  };
}
async function registerAndExit() {
  if (
    !alive ||
    !active ||
    !canRegister.value ||
    saving.value ||
    batchApplying.value
  )
    return;
  if (drafts.value.length === 0) {
    message.warning('请先选择元数据');
    return;
  }
  if (
    drafts.value.some((draft) => draft.contextLoading || draft.contextError)
  ) {
    message.warning('请等待配置读取完成，或重试失败的配置。');
    return;
  }
  if (drafts.value.some((draft) => !draft.assetName.trim())) {
    message.warning('请输入资产中文名称');
    return;
  }
  const ticket = submitGuard.next();
  saving.value = true;
  try {
    const items = drafts.value.map((draft) => ({
      candidateId: draft.meta.id!,
      assetName: draft.assetName.trim(),
      categoryId: draft.categoryId,
      subjectDomainId: draft.subjectDomainId,
      description: draft.description?.trim() || undefined,
      registrationConfig: registrationConfig(draft),
    }));
    const ids =
      assetType.value === 1
        ? await batchRegisterDataAsset(
            items.map((item) => ({ ...item, metaTableId: item.candidateId })),
          )
        : await registerGenericDataAssets(assetType.value, items);
    if (!ticket.isCurrent()) return;
    if (!Array.isArray(ids) || ids.length !== items.length)
      throw new Error('REGISTRATION_RESULT');
    message.success(`成功注册 ${ids.length} 项数据资产`);
    drafts.value = [];
    selectedRowKeys.value = [];
    backToList();
  } catch {
    if (ticket.isCurrent())
      message.error('注册失败，请检查候选元数据状态后重试。');
  } finally {
    if (ticket.isCurrent()) saving.value = false;
  }
}
async function loadOptions() {
  if (!alive || !active || !canRegister.value) return;
  const ticket = guard.next();
  optionError.value = '';
  try {
    const [facets, context] = await Promise.all([
      getAssetRegistrationOptions(),
      getAssetRegistrationContext(),
    ]);
    if (ticket.isCurrent()) {
      options.value = facets;
      baseContext.value = context;
    }
  } catch {
    if (ticket.isCurrent()) {
      options.value = {};
      baseContext.value = emptyContext();
      optionError.value = '归属选项读取失败，请重试。';
    }
  }
}
function invalidate() {
  guard.invalidate();
  submitGuard.invalidate();
  batchGuard.invalidate();
  batchContextGuard.invalidate();
  for (const item of rowGuards.values()) item.invalidate();
  rowGuards.clear();
  drafts.value = [];
  selectedRowKeys.value = [];
  options.value = {};
  baseContext.value = emptyContext();
  saving.value = false;
  batch.open = false;
  pickerApi.setState({ isOpen: false });
}
watch(
  identity,
  () => {
    invalidate();
    void loadOptions();
  },
  { flush: 'sync' },
);
watch(
  assetType,
  () => {
    invalidate();
    void loadOptions();
  },
  { flush: 'sync' },
);
onMounted(() => {
  void loadOptions();
});
onActivated(() => {
  if (!active) {
    active = true;
    void loadOptions();
  }
});
onDeactivated(() => {
  active = false;
  invalidate();
});
onBeforeUnmount(() => {
  alive = false;
  active = false;
  invalidate();
});
const columns = computed(() => {
  const result = [
    { title: '编号', key: 'index', width: 50 },
    {
      title: '来源信息',
      key: 'source',
      width: 250,
      className: 'asset-register__source',
    },
    { title: '资产中文名称', key: 'assetName', width: 166 },
  ];
  if (assetType.value === 1)
    result.push(
      { title: '资产英文名称', key: 'englishName', width: 260 },
      { title: '表类型', key: 'tableType', width: 150 },
      { title: '数仓分层', key: 'layer', width: 150 },
    );
  result.push(
    { title: '业务板块', key: 'category', width: 150 },
    { title: '主题域', key: 'subject', width: 150 },
  );
  if (assetType.value === 1)
    result.push(
      { title: '业务实体', key: 'entity', width: 140 },
      { title: '数据时效', key: 'updateCycle', width: 160 },
      { title: '统计粒度', key: 'granularity', width: 140 },
      { title: '表命名规范', key: 'case', width: 140 },
    );
  return [
    ...result,
    { title: '描述', key: 'description', width: 166 },
    { title: '操作', key: 'actions', width: 83, fixed: 'right' as const },
  ];
});
const rowSelection = computed(() => ({
  selectedRowKeys: selectedRowKeys.value,
  columnWidth: 50,
  onChange: (keys: (number | string)[]) => {
    if (!batchApplying.value) selectedRowKeys.value = keys.map(String);
  },
}));
const timeEffectOptions = computed(() =>
  baseContext.value.timeEffects?.length
    ? baseContext.value.timeEffects
    : [
        { value: '1', label: '天（T+1）' },
        { value: '2', label: '小时（T+h）' },
        { value: '3', label: '分钟（T+h）' },
      ],
);
const namingCaseOptions = [
  { value: 'LOWER', label: '小写' },
  { value: 'UPPER', label: '大写' },
];
const tableTypeOptions = [
  { label: '贴源逻辑表', value: 'ODS' },
  { label: '维度逻辑表', value: 'DIM' },
  { label: '事实逻辑表', value: 'DWD' },
  { label: '汇总逻辑表', value: 'DWS' },
  { label: 'ADS应用表', value: 'ADS' },
];
</script>

<template>
  <Page
    auto-content-height
    content-class="flex min-h-0 flex-col overflow-hidden"
  >
    <PickerModal @confirm="onPicked" />
    <div class="asset-register">
      <div class="asset-register__type">
        <label>资产类型</label
        ><Select
          v-model:value="assetType"
          :options="ASSET_TYPE_OPTIONS"
          :disabled="saving || batchApplying"
        />
      </div>
      <section class="asset-register__panel">
        <Alert
          v-if="!canRegister"
          message="当前登录身份没有资产注册权限。"
          type="warning"
          show-icon
        />
        <Alert v-if="optionError" :message="optionError" type="error" show-icon>
          <template #action>
            <Button size="small" @click="loadOptions">重试</Button>
          </template>
        </Alert>
        <header class="asset-register__heading">
          <h1>资产信息完善</h1>
          <p>
            请完善资产的基本信息，明确来源表、所属业务域和命名规范等内容，便于资产管理、检索和后续使用。
          </p>
          <Button
            type="primary"
            :disabled="!canRegister || saving || batchApplying"
            @click="openPicker"
          >
            选择元数据 </Button
          ><Button
            :disabled="!selectedRowKeys.length || saving || batchApplying"
            @click="openBatch"
          >
            批量设置
          </Button>
        </header>
        <div class="asset-register__table">
          <Table
            :columns="columns"
            :data-source="drafts"
            :row-key="(row: DraftRow) => resourceIdKey(row.meta.id)"
            :row-selection="rowSelection"
            :pagination="false"
            :scroll="{
              x: assetType === 1 ? 2700 : 1500,
              y: 'calc(100vh - 330px)',
            }"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <span
                v-if="column.key === 'index'"
                :title="String(record.meta.id)"
                >{{ record.meta.id }}</span
              >
              <div v-else-if="column.key === 'source'">
                {{
                  record.meta.fileName ||
                  record.meta.name ||
                  record.meta.tableName
                }}<Tag v-if="assetType !== 1" class="asset-register__kind">
                  {{
                    record.meta.fileType || (assetType === 2 ? 'API' : '')
                  }} </Tag
                ><small v-if="assetType === 1">{{
                  record.meta.customComment || record.meta.sourceComment || '-'
                }}</small>
              </div>
              <Input
                v-else-if="column.key === 'assetName'"
                v-model:value="record.assetName"
                :disabled="saving || batchApplying"
                @change="manualName(record)"
                :maxlength="256"
                placeholder="请输入资产中文名称"
              />
              <Input
                v-else-if="column.key === 'englishName'"
                v-model:value="record.englishName"
                :disabled="saving || batchApplying"
                @change="manualName(record)"
                placeholder="请输入资产英文名称"
              />
              <Select
                v-else-if="column.key === 'tableType'"
                :value="record.tableType"
                :options="tableTypeOptions"
                :disabled="saving || batchApplying"
                placeholder="表类型"
                @change="(value) => changeRow(record, 'tableType', value)"
              />
              <Select
                v-else-if="column.key === 'layer'"
                :value="record.layer"
                :options="layerOptions(record.context)"
                :disabled="saving || batchApplying || record.contextLoading"
                placeholder="数仓分层"
                @change="(value) => changeRow(record, 'layer', value)"
              />
              <Select
                v-else-if="column.key === 'category'"
                :value="record.dataDomainId"
                :options="domainOptions"
                :disabled="saving || batchApplying"
                allow-clear
                placeholder="请选择数据板块"
                @change="(value) => changeRow(record, 'dataDomainId', value)"
              />
              <Select
                v-else-if="column.key === 'subject'"
                :value="record.subjectDomainId"
                :options="subjectOptions(record.context, record.dataDomainId)"
                :disabled="saving || batchApplying || record.contextLoading"
                allow-clear
                placeholder="主题域"
                @change="(value) => changeRow(record, 'subjectDomainId', value)"
              />
              <Select
                v-else-if="
                  column.key === 'entity' &&
                  ['DIM', 'DWD'].includes(record.tableType || '')
                "
                :value="record.entityId"
                :options="idOptions(record.context?.entities || [])"
                :disabled="
                  saving ||
                  batchApplying ||
                  record.contextLoading ||
                  !record.dataDomainId
                "
                allow-clear
                placeholder="业务实体"
                @change="(value) => changeRow(record, 'entityId', value)"
              />
              <Select
                v-else-if="
                  column.key === 'granularity' && record.tableType === 'DWS'
                "
                :value="record.granularityEntityIds"
                :options="idOptions(record.context?.granularities || [])"
                mode="multiple"
                :max-tag-count="1"
                :disabled="
                  saving ||
                  batchApplying ||
                  record.contextLoading ||
                  !record.subjectDomainId
                "
                allow-clear
                placeholder="统计粒度"
                @change="
                  (value) => changeRow(record, 'granularityEntityIds', value)
                "
              />
              <Select
                v-else-if="column.key === 'updateCycle'"
                :value="record.updateCycle"
                :options="timeEffectOptions"
                :disabled="saving || batchApplying"
                allow-clear
                placeholder="数据时效"
                @change="(value) => changeRow(record, 'updateCycle', value)"
              />
              <Select
                v-else-if="column.key === 'case'"
                :value="record.nameCase"
                :options="namingCaseOptions"
                :disabled="saving || batchApplying"
                @change="(value) => changeRow(record, 'nameCase', value)"
              />
              <Input
                v-else-if="column.key === 'description'"
                v-model:value="record.description"
                :disabled="saving || batchApplying"
                :maxlength="2000"
                placeholder="请输入描述"
              />
              <Button
                v-else-if="column.key === 'actions'"
                type="link"
                danger
                :disabled="saving || batchApplying"
                @click="removeDraft(record.meta.id)"
              >
                删除 </Button
              ><span v-else>-</span>
              <small v-if="column.key === 'assetName' && record.contextLoading"
                >正在读取配置…</small
              >
              <small
                v-if="column.key === 'assetName' && record.contextError"
                class="asset-register__error"
                >{{ record.contextError
                }}<Button
                  type="link"
                  size="small"
                  @click="refreshDraftContext(record)"
                  >重试</Button
                ></small
              >
            </template>
            <template #emptyText>
              <div class="asset-register__empty">
                <img
                  :src="registrationImage('empty-search')"
                  alt=""
                  aria-hidden="true"
                />
                <p>暂无记录</p>
              </div>
            </template>
          </Table>
        </div>
        <footer class="asset-register__footer">
          <Button :disabled="saving || batchApplying" @click="backToList">
            返回列表 </Button
          ><Button
            type="primary"
            :disabled="!drafts.length || !canRegister"
            :loading="saving"
            @click="registerAndExit"
          >
            注册并退出
          </Button>
        </footer>
      </section>
    </div>
    <Modal
      v-model:open="batch.open"
      title="批量设置"
      width="600px"
      :style="{ top: '60px' }"
      :confirm-loading="batchApplying"
      :ok-button-props="{ disabled: batchLoading || !!batchError }"
      :cancel-button-props="{ disabled: batchApplying }"
      :closable="!batchApplying"
      :mask-closable="!batchApplying"
      @ok="applyBatch"
    >
      <div class="asset-register__batch">
        <p>仅应用您在此处选择的属性，留空的项将保持原资产的配置不变。</p>
        <Alert v-if="batchError" :message="batchError" type="error" />
        <label v-if="assetType === 1"
          >表类型<Select
            :value="batch.tableType"
            :options="tableTypeOptions"
            :disabled="batchApplying"
            allow-clear
            placeholder="-- 不修改 --"
            @change="(value) => changeBatch('tableType', value)"
        /></label>
        <label v-if="assetType === 1"
          >数仓分层<Select
            v-model:value="batch.layer"
            :options="layerOptions(batchContext)"
            :disabled="batchApplying || batchLoading"
            allow-clear
            placeholder="-- 不修改 --"
        /></label>
        <label
          >业务板块<Select
            :value="batch.dataDomainId"
            :options="domainOptions"
            :disabled="batchApplying"
            allow-clear
            placeholder="-- 不修改 --"
            @change="(value) => changeBatch('dataDomainId', value)"
        /></label>
        <label
          >主题域<Select
            :value="batch.subjectDomainId"
            :options="subjectOptions(batchContext, batch.dataDomainId)"
            :disabled="batchApplying || batchLoading"
            allow-clear
            placeholder="-- 不修改 --"
            @change="(value) => changeBatch('subjectDomainId', value)"
        /></label>
        <label
          v-if="
            assetType === 1 && ['DIM', 'DWD'].includes(batch.tableType || '')
          "
          >业务实体<Select
            v-model:value="batch.entityId"
            :options="idOptions(batchContext.entities)"
            :disabled="batchApplying || batchLoading || !batch.dataDomainId"
            allow-clear
            placeholder="-- 不修改 --"
        /></label>
        <label v-if="assetType === 1 && batch.tableType === 'DWS'"
          >统计粒度<Select
            v-model:value="batch.granularityEntityIds"
            :options="idOptions(batchContext.granularities)"
            mode="multiple"
            :disabled="batchApplying || batchLoading || !batch.subjectDomainId"
            allow-clear
            placeholder="-- 不修改 --"
        /></label>
        <label v-if="assetType === 1"
          >数据时效<Select
            v-model:value="batch.updateCycle"
            :options="timeEffectOptions"
            :disabled="batchApplying"
            allow-clear
            placeholder="-- 不修改 --"
        /></label>
        <label v-if="assetType === 1"
          >表命名规范<Select
            v-model:value="batch.nameCase"
            :options="namingCaseOptions"
            :disabled="batchApplying"
            allow-clear
            placeholder="-- 不修改 --"
        /></label>
        <label
          >资产分类<TreeSelect
            v-model:value="batch.categoryId"
            :tree-data="categoryTree"
            :field-names="{
              value: 'id',
              label: 'name',
              children: 'children',
            }"
            :disabled="batchApplying"
            tree-default-expand-all
            allow-clear
            placeholder="-- 不修改 --"
        /></label>
        <label v-if="assetType === 1"
          >表名更新方式<RadioGroup
            v-model:value="batch.tableNameUpdateMode"
            :disabled="batchApplying"
            :options="[
              { value: 0, label: '保留自定义表名' },
              { value: 1, label: '按规则重新生成' },
            ]"
        /></label>
        <small v-if="assetType === 1">{{
          batch.tableNameUpdateMode === 0
            ? '已自定义名称保持不变，仅更新未自定义的表名称。'
            : '将根据规则重新生成所有表的表名称，并覆盖已手动修改的名称。'
        }}</small>
      </div>
    </Modal>
  </Page>
</template>

<style scoped>
.asset-register {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
  font-size: 14px;
  color: hsl(var(--foreground));
}

.asset-register__type {
  display: flex;
  flex: none;
  gap: 16px;
  align-items: center;
  min-height: 64px;
  padding: 14px 22px;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.asset-register__type :deep(.ant-select) {
  width: 300px;
}

.asset-register__panel {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  padding: 16px;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.asset-register__heading {
  display: flex;
  flex: none;
  gap: 12px;
  align-items: center;
  margin: 2px 0 14px;
}

.asset-register__heading h1 {
  flex: none;
  padding-left: 8px;
  margin: 0;
  font-size: 16px;
  font-weight: 500;
  border-left: 5px solid hsl(var(--primary));
  border-radius: 2px;
}

.asset-register__heading p {
  flex: 1;
  margin: 0;
  font-size: 14px;
  color: hsl(var(--muted-foreground));
}

.asset-register__table {
  flex: 1;
  min-height: 0;
}

.asset-register__table :deep(.ant-select),
.asset-register__table :deep(.ant-tree-select) {
  width: 100%;
}

.asset-register__table small {
  display: block;
  margin-top: 4px;
  color: hsl(var(--muted-foreground));
}

.asset-register__kind {
  padding: 0 6px;
  margin-left: 8px;
  line-height: 20px;
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 10%);
  border: 0;
}

.asset-register__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-height: 450px;
  padding-top: 85px;
}

.asset-register__empty img {
  width: 290px;
  height: 207px;
  object-fit: contain;
}

.asset-register__empty p {
  margin: 16px 0 0;
  font-size: 14px;
  color: hsl(var(--muted-foreground));
}

.asset-register__footer {
  display: flex;
  flex: none;
  gap: 12px;
  justify-content: flex-end;
  padding: 20px 20px 6px;
  margin-top: auto;
  border-top: 1px solid hsl(var(--border));
}

.asset-register__batch {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 20px;
}

.asset-register__error {
  color: hsl(var(--destructive)) !important;
}

.asset-register__batch > p {
  margin: 0;
  color: hsl(var(--muted-foreground));
}

.asset-register__batch > small {
  padding-left: 110px;
  color: hsl(var(--muted-foreground));
}

.asset-register__table :deep(.asset-register__source) {
  text-align: left;
}

.asset-register__batch label {
  display: flex;
  gap: 16px;
  align-items: center;
  text-align: right;
}

.asset-register__batch :deep(.ant-select),
.asset-register__batch :deep(.ant-tree-select) {
  flex: 1;
  min-width: 0;
}

@media (width <= 900px) {
  .asset-register__heading {
    flex-wrap: wrap;
  }

  .asset-register__heading p {
    flex-basis: 80%;
  }
}

@media (width <= 640px) {
  .asset-register__type :deep(.ant-select) {
    flex: 1;
    width: auto;
  }

  .asset-register__heading p {
    flex-basis: 100%;
  }

  .asset-register__empty img {
    width: 200px;
    height: 143px;
  }
}
</style>

<style scoped src="../table-font.css"></style>
