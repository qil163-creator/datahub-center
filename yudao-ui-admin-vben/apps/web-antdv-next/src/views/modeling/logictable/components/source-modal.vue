<script setup lang="ts">
import type { SourceTableSelection } from '../source-state';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';
import type { StandardDocApi } from '#/api/governance/doc';
import type { FoundationDwLayerApi } from '#/api/modeling/dwlayer';
import type { ModelingLogicTableApi } from '#/api/modeling/logictable';
import type { ModelingModelApi } from '#/api/modeling/model';
import type { TableModelApi } from '#/api/modeling/tablemodel';
import type { SystemUserApi } from '#/api/system/user';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Checkbox,
  Input,
  message,
  Modal,
  Radio,
  RadioGroup,
  Select,
  Spin,
  TextArea,
} from 'antdv-next';

import { getDatabaseTableList } from '#/api/dataforge/database';
import { getDatabaseTypeSimpleList } from '#/api/dataforge/databasetype';
import { getDocSimpleList } from '#/api/governance/doc';
import { getDwLayerList } from '#/api/modeling/dwlayer';
import { getLogicTableSourceSystems } from '#/api/modeling/logictable';
import {
  getModelDatabaseList,
  getModelImportFields,
  getModelTableList,
} from '#/api/modeling/model';
import {
  createTableModel,
  getTableModel,
  getTableModelDesign,
  getTableModelPage,
  modelSaveData,
  saveTableModelDraft,
} from '#/api/modeling/tablemodel';
import { getSimpleUserList } from '#/api/system/user';
import DatabasePicker from '#/views/modeling/model-materialized/modules/database-picker.vue';
import ReferencePagination from '#/views/modeling/model-materialized/modules/reference-pagination.vue';

import { assertCurrentDesign } from '../../modelcommon/current-design-state';
import emptyRecords from '../assets/empty-records.png';
import { QdIcon } from '../icons';
import { loadLogicOptionPages } from '../options';
import {
  persistSourceSelections,
  sourceTableKey,
  sourceTargetName,
  validateSourceSelections,
} from '../source-state';

import '../detail.css';
import '../source-modal.css';

defineOptions({ name: 'ModelingSourceLogicTableModal' });
const emit = defineEmits<{ saved: [id?: TableModelApi.Id] }>();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const visible = ref(false);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const modelError = ref(false);
const form = reactive<TableModelApi.Save>({ enabled: 0 });
const original = ref<TableModelApi.Model>();
const currentDesign = ref<TableModelApi.Design>();
const rows = ref<SourceTableSelection[]>([]);
const sourceSystems = ref<ModelingLogicTableApi.SourceSystem[]>([]);
const databases = ref<
  Array<ModelingModelApi.DatabaseSimple & { id: TableModelApi.Id }>
>([]);
const catalog = ref<FoundationDatabaseTypeApi.DatabaseType[]>([]);
const documents = ref<StandardDocApi.SimpleDoc[]>([]);
const users = ref<SystemUserApi.User[]>([]);
const layers = ref<FoundationDwLayerApi.DwLayer[]>([]);
let sequence = 0;
let pickerSequence = 0;
let previewSequence = 0;
const isEdit = computed(() => form.id !== undefined);
const readonly = computed(() => original.value?.status === 'DISCARDED');
const disabled = computed(
  () => loading.value || saving.value || readonly.value || modelError.value,
);
const sourceLayer = computed(() =>
  layers.value.find(
    (layer) =>
      layer.nodeType === 'LAYER' &&
      (layer.defaultTableType === 'SOURCE' || layer.layerCode === 'ODS') &&
      layer.status !== 1,
  ),
);
const currentDatabase = computed(() =>
  databases.value.find(
    (database) => String(database.id) === String(form.datasourceId),
  ),
);
const sourceOptions = computed(() =>
  sourceSystems.value.map((item) => ({
    label: item.name,
    value: String(item.id),
  })),
);
const userOptions = computed(() =>
  users.value.map((item) => ({
    label: item.nickname || item.username,
    value: String(item.id),
  })),
);
const documentOptions = computed(() =>
  documents.value
    .filter(
      (item) =>
        !form.documentType ||
        String(item.stdType) === String(form.documentType),
    )
    .map((item) => ({ label: item.nameCn, value: String(item.id) })),
);
const standardTypes = computed(() =>
  getDictOptions(DICT_TYPE.STANDARD_STD_TYPE, 'string'),
);
const sourceSystemName = computed(
  () =>
    sourceSystems.value.find(
      (item) => String(item.id) === String(form.sourceSystemId),
    )?.name ||
    original.value?.sourceSystemName ||
    '',
);
const savedCount = computed(
  () => rows.value.filter((row) => row.savedId !== undefined).length,
);
const identityLocked = computed(
  () => savedCount.value > 0 || rows.value.some((row) => row.uncertain),
);

async function reconcile(row: SourceTableSelection) {
  const models = await loadLogicOptionPages((page) =>
    getTableModelPage({ ...page, tableType: 'SOURCE', code: row.targetName }),
  );
  return models.find(
    (model) =>
      String(model.datasourceId) === String(form.datasourceId) &&
      model.tableName === row.tableName &&
      (model.schemaName || '') === (row.schemaName || '') &&
      (model.tableNameNotation || model.code) === row.targetName,
  )?.id;
}

async function loadOptions() {
  const request = sequence;
  const results = await Promise.allSettled([
    getLogicTableSourceSystems(),
    getModelDatabaseList(),
    getSimpleUserList(),
    hasAccessByCodes(['governance:doc:query'])
      ? getDocSimpleList()
      : Promise.resolve([]),
    getDwLayerList({}),
    hasAccessByCodes(['dataforge:database-type:query'])
      ? getDatabaseTypeSimpleList()
      : Promise.resolve([]),
  ]);
  if (request !== sequence) return;
  const [systems, dbs, people, docs, dwLayers, dbCatalog] = results;
  sourceSystems.value = systems.status === 'fulfilled' ? systems.value : [];
  databases.value =
    dbs.status === 'fulfilled'
      ? dbs.value.filter(
          (
            item,
          ): item is ModelingModelApi.DatabaseSimple & {
            id: TableModelApi.Id;
          } => item.id !== undefined,
        )
      : [];
  users.value = people.status === 'fulfilled' ? people.value : [];
  documents.value = docs.status === 'fulfilled' ? docs.value : [];
  layers.value = dwLayers.status === 'fulfilled' ? dwLayers.value : [];
  catalog.value = dbCatalog.status === 'fulfilled' ? dbCatalog.value : [];
  const names = [
    '来源系统',
    '数据连接',
    '负责人',
    '标准登记',
    '目标分层',
    '连接类型',
  ];
  error.value = results
    .flatMap((result, index) =>
      result.status === 'rejected' ? [names[index]] : [],
    )
    .join('、');
  if (error.value) error.value = `${error.value}加载失败，请重试`;
}

async function show(id?: TableModelApi.Id) {
  const request = ++sequence;
  pickerSequence++;
  previewSequence++;
  visible.value = true;
  loading.value = true;
  saving.value = false;
  pickerOpen.value = false;
  previewOpen.value = false;
  error.value = '';
  modelError.value = false;
  rows.value = [];
  original.value = undefined;
  currentDesign.value = undefined;
  Object.keys(form).forEach((key) => Reflect.deleteProperty(form, key));
  Object.assign(form, { id, enabled: 0, tableCase: 1, tableType: 'SOURCE' });
  try {
    await loadOptions();
    if (request !== sequence) return;
    if (id !== undefined) {
      const [model, design] = await Promise.all([
        getTableModel(id),
        getTableModelDesign(id),
      ]);
      if (request !== sequence) return;
      assertCurrentDesign(model, design);
      original.value = model;
      currentDesign.value = design;
      Object.assign(form, modelSaveData(model));
      if (form.documentId !== undefined)
        form.documentId = String(form.documentId);
      const sourceName =
        model.tableName ||
        currentDesign.value.typeConfig?.source?.sourceTable ||
        '';
      rows.value = [
        {
          tableName: sourceName,
          comment: model.name || '',
          targetName: model.tableNameNotation || model.code || '',
          targetComment: model.name || '',
          tableCase: model.tableCase || 1,
          schemaName: model.schemaName,
        },
      ];
    }
  } catch {
    if (request === sequence) {
      modelError.value = true;
      error.value = '贴源逻辑表加载失败，请重试';
    }
  } finally {
    if (request === sequence) loading.value = false;
  }
}
function retry() {
  if (modelError.value) void show(form.id);
  else void loadOptions();
}
function selectDatabase(id: TableModelApi.Id) {
  form.datasourceId = id;
  form.databaseId = id;
  form.dbType = currentDatabase.value?.productType;
  form.schemaName = currentDatabase.value?.schema;
  rows.value = [];
}
function selectOwner(value: unknown) {
  const owner = users.value.find((user) => String(user.id) === String(value));
  form.contactUserId = owner?.id;
  form.contactNumber = owner?.mobile;
}
function applyCase(row: SourceTableSelection) {
  row.targetName =
    row.tableCase === 2
      ? row.targetName.toLowerCase()
      : row.targetName.toUpperCase();
}

const pickerOpen = ref(false);
const pickerLoading = ref(false);
const pickerError = ref(false);
const tableKeyword = ref('');
const scopeKeyword = ref('');
const tableList = ref<
  Array<FoundationDatabaseApi.DatabaseTable & { modeled?: boolean }>
>([]);
const pickerSelected = ref(new Set<string>());
const pickerPage = ref(1);
const pickerSize = ref(10);
const filteredTables = computed(() =>
  tableList.value.filter((table) =>
    table.tableName
      .toLowerCase()
      .includes(tableKeyword.value.toLowerCase().trim()),
  ),
);
const pickerRows = computed(() =>
  filteredTables.value.slice(
    (pickerPage.value - 1) * pickerSize.value,
    pickerPage.value * pickerSize.value,
  ),
);
const allPickerChecked = computed(
  () =>
    pickerRows.value.some((row) => !row.modeled) &&
    pickerRows.value
      .filter((row) => !row.modeled)
      .every((row) => pickerSelected.value.has(row.tableName)),
);

async function openPicker() {
  if (form.datasourceId === undefined) {
    message.warning('请先选择数据连接');
    return;
  }
  pickerOpen.value = true;
  pickerLoading.value = true;
  pickerError.value = false;
  tableKeyword.value = '';
  scopeKeyword.value = '';
  pickerPage.value = 1;
  const request = ++pickerSequence;
  const datasourceId = form.datasourceId;
  pickerSelected.value = new Set(rows.value.map((row) => row.tableName));
  try {
    const [tables, models] = await Promise.all([
      hasAccessByCodes(['dataforge:database:query'])
        ? getDatabaseTableList(datasourceId)
        : getModelTableList(datasourceId).then((names) =>
            names.map((tableName) => ({ tableName })),
          ),
      loadLogicOptionPages((page) =>
        getTableModelPage({ ...page, tableType: 'SOURCE' }),
      ),
    ]);
    if (request !== pickerSequence || !pickerOpen.value) return;
    tableList.value = tables.map((table) => ({
      ...table,
      modeled: models.some(
        (model) =>
          String(model.datasourceId) === String(datasourceId) &&
          model.tableName === table.tableName &&
          (model.schemaName || '') === (currentDatabase.value?.schema || ''),
      ),
    }));
  } catch {
    if (request === pickerSequence) pickerError.value = true;
  } finally {
    if (request === pickerSequence) pickerLoading.value = false;
  }
}
function pickTable(tableName: string, checked: boolean) {
  const next = new Set(pickerSelected.value);
  if (checked) next.add(tableName);
  else next.delete(tableName);
  pickerSelected.value = next;
}
function confirmTables() {
  const keys = new Set(rows.value.map(sourceTableKey));
  for (const table of tableList.value) {
    if (!pickerSelected.value.has(table.tableName) || table.modeled) continue;
    const row: SourceTableSelection = {
      tableName: table.tableName,
      comment: table.remarks || '',
      targetName: sourceTargetName(
        table.tableName,
        1,
        sourceLayer.value?.tablePrefix || 'ODS_',
      ),
      targetComment: table.remarks || '',
      tableCase: 1,
      schemaName: currentDatabase.value?.schema,
    };
    if (!keys.has(sourceTableKey(row))) {
      rows.value.push(row);
      keys.add(sourceTableKey(row));
    }
  }
  pickerOpen.value = false;
}

const previewOpen = ref(false);
const previewLoading = ref(false);
const previewError = ref(false);
const previewFields = ref<ModelingModelApi.ModelField[]>([]);
const previewPage = ref(1);
const previewSize = ref(10);
const previewRows = computed(() =>
  previewFields.value.slice(
    (previewPage.value - 1) * previewSize.value,
    previewPage.value * previewSize.value,
  ),
);
const previewSource = ref<SourceTableSelection>();
async function preview(row: SourceTableSelection) {
  if (form.datasourceId === undefined || !row.tableName) return;
  previewSource.value = row;
  previewOpen.value = true;
  previewLoading.value = true;
  previewError.value = false;
  previewFields.value = [];
  previewPage.value = 1;
  const request = ++previewSequence;
  try {
    const fields = await getModelImportFields(form.datasourceId, row.tableName);
    if (request === previewSequence && previewOpen.value)
      previewFields.value = fields;
  } catch {
    if (request === previewSequence) previewError.value = true;
  } finally {
    if (request === previewSequence) previewLoading.value = false;
  }
}
async function save() {
  if (disabled.value) return;
  if (!hasAccessByCodes(['modeling:model:save'])) return;
  if (!form.sourceSystemId || !form.datasourceId) {
    message.warning('请选择来源系统和数据连接');
    return;
  }
  if (!sourceLayer.value && !form.layerId) {
    message.warning('请先配置启用的 ODS 目标分层');
    return;
  }
  const validation = validateSourceSelections(rows.value);
  if (validation) {
    message.warning(validation);
    return;
  }
  const request = sequence;
  saving.value = true;
  error.value = '';
  try {
    if (original.value) {
      const row = rows.value[0]!;
      const design = currentDesign.value;
      if (!design) return;
      const saved = await saveTableModelDraft({
        ...modelSaveData(original.value),
        ...form,
        expectedRevision: original.value.revision,
        code: row.targetName,
        tableNameNotation: row.targetName,
        name: row.targetComment,
        tableCase: row.tableCase,
        tableName: row.tableName,
        modelId: original.value.id,
        fields: design.fields,
        typeConfig: {
          ...design.typeConfig,
          source: { ...design.typeConfig?.source, sourceTable: row.tableName },
        },
      });
      if (request !== sequence || saved === false) return;
      emit('saved', original.value.id);
      visible.value = false;
      message.success('修改成功');
    } else {
      await persistSourceSelections(
        rows.value,
        (row) =>
          createTableModel({
            ...form,
            tableType: 'SOURCE',
            createMode: 'REVERSE',
            layerId: sourceLayer.value?.id,
            databaseId: form.datasourceId,
            datasourceId: form.datasourceId,
            dbType: currentDatabase.value?.productType || form.dbType,
            schemaName: row.schemaName,
            tableName: row.tableName,
            code: row.targetName,
            tableNameNotation: row.targetName,
            name: row.targetComment,
            tableCase: row.tableCase,
            typeConfig: {
              source: {
                sourceTable: row.tableName,
                sourceSystem: sourceSystemName.value,
              },
            },
          }),
        () => request === sequence,
        reconcile,
      );
      if (request !== sequence) return;
      if (rows.value.every((row) => row.savedId !== undefined)) {
        emit('saved', rows.value[0]?.savedId);
        visible.value = false;
        message.success(`已创建 ${savedCount.value} 张贴源逻辑表`);
      } else {
        error.value = `已创建 ${savedCount.value} 张，${rows.value.length - savedCount.value} 张未成功。再次确认时只会重试未成功的条目。`;
        emit('saved');
      }
    }
  } finally {
    if (request === sequence) saving.value = false;
  }
}
watch(tableKeyword, () => {
  pickerPage.value = 1;
});
watch(pickerOpen, (value) => {
  if (!value) {
    pickerSequence++;
    pickerLoading.value = false;
  }
});
watch(previewOpen, (value) => {
  if (!value) {
    previewSequence++;
    previewLoading.value = false;
  }
});
watch(visible, (value) => {
  if (!value) {
    sequence++;
    pickerSequence++;
    previewSequence++;
    pickerOpen.value = false;
    previewOpen.value = false;
  }
});
watch(
  () => [
    access.accessToken,
    access.refreshToken,
    JSON.stringify(access.accessCodes),
  ],
  () => {
    sequence++;
    visible.value = false;
  },
);
onBeforeUnmount(() => {
  sequence++;
  pickerSequence++;
  previewSequence++;
});
defineExpose({ open: show });
</script>

<template>
  <Modal
    v-model:open="visible"
    :title="isEdit ? '编辑贴源逻辑表' : '新增贴源逻辑表'"
    class="qd-source-modal"
    :width="1120"
    :style="{ top: '68px' }"
    :footer="null"
    :mask-closable="!saving"
    :closable="!saving"
    destroy-on-hidden
  >
    <Spin :spinning="loading">
      <div class="qd-source-body">
        <Alert
          v-if="error && (!/(?:加载|读取|获取).*失败/.test(error) || /保存|冲突|未确认/.test(error))"
          type="error"
          :message="error"
          show-icon
          class="qd-source-alert"
        >
          <template #action>
            <button v-if="!saving" class="qd-logic-detail-link" @click="retry">
              重试
            </button>
          </template>
        </Alert>
        <Alert
          v-if="readonly"
          type="info"
          message="当前模型已废弃，不能修改。"
          class="qd-source-alert"
        />
        <h2>基础信息</h2>
        <div class="qd-source-form">
          <label><i>*</i>类型</label><Input value="贴源逻辑表" disabled /><label><i>*</i>目标分层</label><Input
            :value="
              sourceLayer
                ? `${sourceLayer.layerCode} ${sourceLayer.layerName}`
                : 'ODS 操作数据层'
            "
            disabled
          />
          <label><i>*</i>来源系统</label><Input v-if="isEdit" :value="sourceSystemName" disabled /><Select
            v-else
            v-model:value="form.sourceSystemId"
            :options="sourceOptions"
            :disabled="disabled || identityLocked"
            placeholder="请选择来源系统"
            allow-clear
            show-search
            option-filter-prop="label"
          />
          <label>标准类型</label><Select
            v-model:value="form.documentType"
            :options="standardTypes"
            :disabled="disabled"
            placeholder="请选择标准类型"
            allow-clear
            @change="form.documentId = undefined"
          />
          <label>标准登记</label><Select
            v-model:value="form.documentId"
            :options="documentOptions"
            :disabled="disabled || !form.documentType"
            placeholder="请选择标准登记"
            allow-clear
            show-search
            option-filter-prop="label"
          />
          <label>负责人</label><Select
            :value="
              form.contactUserId === undefined
                ? undefined
                : String(form.contactUserId)
            "
            :options="userOptions"
            :disabled="disabled"
            placeholder="请选择负责人"
            allow-clear
            show-search
            option-filter-prop="label"
            @change="selectOwner"
          />
          <label>联系电话</label><Input
            :value="form.contactNumber"
            placeholder="负责人未设置联系电话"
            disabled
          /><label>状态</label><RadioGroup v-model:value="form.enabled" :disabled="disabled">
            <Radio :value="1">启用</Radio><Radio :value="0">禁用</Radio>
          </RadioGroup>
          <label>描述</label><TextArea
            v-model:value="form.description"
            :disabled="disabled"
            :maxlength="256"
            show-count
            :rows="3"
            class="qd-source-wide"
            placeholder="请输入贴源逻辑表描述，建议填写表的业务含义、数据范围及主要用途。"
          />
        </div>
        <h2>数据源</h2>
        <div class="qd-source-form">
          <label><i>*</i>数据连接名称</label><DatabasePicker
            :value="form.datasourceId"
            :databases="databases"
            :catalog="catalog"
            :disabled="disabled || isEdit || identityLocked"
            :loading="loading"
            @change="selectDatabase"
          /><label>数据连接类型</label><Input
            :value="
              currentDatabase?.databaseTypeName ||
              currentDatabase?.productType ||
              form.dbType
            "
            placeholder="请输入数据连接类型"
            disabled
          />
        </div>
        <h2>源表及物化名称</h2>
        <div v-if="!isEdit" class="qd-source-table-toolbar">
          <button
            class="qd-logic-detail-btn"
            :disabled="disabled"
            @click="openPicker"
          >
            <QdIcon name="plus" />选择源表
</button><span>点击选择源表，选中结果将在下方列表中回显。</span>
        </div>
        <div class="qd-source-table-scroll">
          <table class="qd-source-table">
            <thead>
              <tr>
                <th style="width: 55px">编号</th>
                <th>来源物理表名</th>
                <th>来源中文表名</th>
                <th>目标逻辑表名</th>
                <th>目标中文表名</th>
                <th style="width: 110px">表命名规范</th>
                <th style="width: 160px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in rows" :key="sourceTableKey(row)">
                <td>{{ index + 1 }}</td>
                <td>{{ row.tableName }}</td>
                <td>{{ row.comment || '-' }}</td>
                <td>
                  <Input
                    v-model:value="row.targetName"
                    :disabled="
                      disabled || row.savedId !== undefined || row.uncertain
                    "
                    @change="applyCase(row)"
                  />
                </td>
                <td>
                  <Input
                    v-model:value="row.targetComment"
                    :disabled="
                      disabled || row.savedId !== undefined || row.uncertain
                    "
                  />
                </td>
                <td>
                  <Select
                    v-model:value="row.tableCase"
                    :disabled="
                      disabled || row.savedId !== undefined || row.uncertain
                    "
                    :options="[
                      { label: '大写', value: 1 },
                      { label: '小写', value: 2 },
                    ]"
                    @change="applyCase(row)"
                  />
                </td>
                <td>
                  <div class="qd-source-row-actions">
                    <button
                      class="qd-logic-detail-link"
                      :disabled="saving"
                      @click="preview(row)"
                    >
                      <QdIcon name="view" />字段预览
</button><button
                      class="qd-logic-detail-link danger"
                      :disabled="
                        disabled ||
                        isEdit ||
                        row.savedId !== undefined ||
                        row.uncertain
                      "
                      @click="rows.splice(index, 1)"
                    >
                      <QdIcon name="delete" />删除
                    </button>
                  </div>
                  <small
                    v-if="row.savedId !== undefined"
                    class="qd-source-success"
                    >已创建</small><small v-else-if="row.error" class="qd-source-error">{{
                    row.error
                  }}</small>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="!rows.length" class="qd-source-empty">
            <img :src="emptyRecords" alt="" /><span>暂无记录</span>
          </div>
        </div>
      </div>
    </Spin>
    <div class="qd-source-footer">
      <button
        class="qd-logic-detail-btn"
        :disabled="saving"
        @click="visible = false"
      >
        取消
</button><button
        class="qd-logic-detail-btn primary"
        :disabled="disabled"
        @click="save"
      >
        {{ saving ? '保存中…' : '确认' }}
      </button>
    </div>
  </Modal>
  <Modal
    v-model:open="pickerOpen"
    title="选择源表"
    class="qd-source-picker"
    width="80vw"
    :style="{ top: '6vh' }"
    :footer="null"
    destroy-on-hidden
  >
    <div class="qd-source-picker-search">
      <Input
        v-model:value="tableKeyword"
        placeholder="请输入物理表名"
        allow-clear
      >
        <template #prefix><QdIcon name="search" /></template>
      </Input>
    </div>
    <div class="qd-source-picker-layout">
      <aside>
        <h3>{{ currentDatabase?.name }}</h3>
        <Input
          v-model:value="scopeKeyword"
          placeholder="请输入数据源对象"
          allow-clear
        /><button
          v-if="
            !scopeKeyword ||
            (currentDatabase?.schema || currentDatabase?.name || '').includes(
              scopeKeyword,
            )
          "
          class="qd-source-scope"
        >
          <QdIcon name="menu" />{{
            currentDatabase?.schema || currentDatabase?.name
          }}
        </button>
      </aside>
      <main>
        <h3>表列表</h3>
        <Spin :spinning="pickerLoading">
          <template v-if="pickerError"></template>
          <table v-else class="qd-source-table">
            <thead>
              <tr>
                <th style="width: 45px">
                  <Checkbox
                    :checked="allPickerChecked"
                    @change="
                      pickerRows
                        .filter((row) => !row.modeled)
                        .forEach((row) =>
                          pickTable(row.tableName, $event.target.checked),
                        )
                    "
                  />
                </th>
                <th style="width: 70px">编号</th>
                <th>物理表名</th>
                <th>中文名称</th>
                <th style="width: 100px">状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in pickerRows" :key="row.tableName">
                <td>
                  <Checkbox
                    :checked="pickerSelected.has(row.tableName)"
                    :disabled="row.modeled"
                    @change="pickTable(row.tableName, $event.target.checked)"
                  />
                </td>
                <td>{{ (pickerPage - 1) * pickerSize + index + 1 }}</td>
                <td>{{ row.tableName }}</td>
                <td>{{ row.remarks || '-' }}</td>
                <td>
                  <span
                    :class="
                      row.modeled ? 'qd-source-modeled' : 'qd-source-unmodeled'
                    "
                    >{{ row.modeled ? '已建模' : '未建模' }}</span>
                </td>
              </tr>
            </tbody>
          </table>
          <div
            v-if="!pickerLoading && !pickerError && !pickerRows.length"
            class="qd-source-empty"
          >
            <img :src="emptyRecords" alt="" /><span>暂无记录</span>
          </div>
</Spin><ReferencePagination
          :page-no="pickerPage"
          :page-size="pickerSize"
          :total="filteredTables.length"
          @change="
            (page, size) => {
              pickerPage = page;
              pickerSize = size;
            }
          "
        />
      </main>
    </div>
    <div class="qd-source-footer">
      <button class="qd-logic-detail-btn" @click="pickerOpen = false">
        取消
</button><button
        class="qd-logic-detail-btn primary"
        :disabled="pickerLoading || !pickerSelected.size"
        @click="confirmTables"
      >
        确认
      </button>
    </div>
  </Modal>
  <Modal
    v-model:open="previewOpen"
    title="字段预览"
    class="qd-source-preview"
    :width="820"
    :style="{ top: '68px' }"
    :footer="null"
    destroy-on-hidden
  >
    <div class="qd-source-preview-body">
      <Spin :spinning="previewLoading">
        <div class="qd-source-preview-table">
          <table class="qd-source-table">
            <thead>
              <tr>
                <th style="width: 65px">序号</th>
                <th>英文名称</th>
                <th>中文名称</th>
                <th>字段类型</th>
                <th>是否主键</th>
                <th>字段长度</th>
                <th>小数精度</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(field, index) in previewRows"
                :key="field.fieldUid || field.fieldName"
              >
                <td>{{ (previewPage - 1) * previewSize + index + 1 }}</td>
                <td>{{ field.fieldName }}</td>
                <td>{{ field.cnName || '-' }}</td>
                <td>{{ field.dataType }}</td>
                <td>{{ field.pkFlag ? '是' : '否' }}</td>
                <td>{{ field.dataLength ?? field.dataPrecision ?? '-' }}</td>
                <td>{{ field.dataScale ?? '-' }}</td>
              </tr>
            </tbody>
          </table>
          <div
            v-if="!previewLoading && !previewError && !previewRows.length"
            class="qd-source-empty"
          >
            <img :src="emptyRecords" alt="" /><span>暂无记录</span>
          </div>
        </div>
</Spin><ReferencePagination
        :page-no="previewPage"
        :page-size="previewSize"
        :total="previewFields.length"
        @change="
          (page, size) => {
            previewPage = page;
            previewSize = size;
          }
        "
      />
    </div>
    <div class="qd-source-footer">
      <button class="qd-logic-detail-btn" @click="previewOpen = false">
        关闭
      </button>
    </div>
  </Modal>
</template>
