<script setup lang="ts">
import type { BusinessConditionApi } from '#/api/modeling/business-condition';
import type { IndicatorModelApi } from '#/api/modeling/indicator-model';
import type { ModelingModelApi } from '#/api/modeling/model';
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { DICT_TYPE } from '@vben/constants';
import { getDictOptions, useAntdDesignTokens } from '@vben/hooks';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Checkbox,
  ConfigProvider,
  Input,
  message,
  Modal,
  Popover,
  Spin,
} from 'antdv-next';

import { getBusinessConditionPage } from '#/api/modeling/business-condition';
import { getIndicatorPage } from '#/api/modeling/indicator-model';
import {
  getTableModel,
  getTableModelDesign,
  modelSaveData,
  saveTableModelDraft,
} from '#/api/modeling/tablemodel';
import ReferencePagination from '#/views/modeling/model-materialized/modules/reference-pagination.vue';

import { assertCurrentDesign } from '../../modelcommon/current-design-state';
import domainIcon from '../assets/data-section-default-icon-small.svg';
import emptyRecords from '../assets/empty-records.png';
import {
  fieldCategory,
  fieldConstraints,
  filteredDetailFields,
  summaryFieldGroups,
  detailText as text,
} from '../detail-state';
import { QdIcon } from '../icons';
import { loadLogicOptionPages } from '../options';
import FieldModal from './field-modal.vue';

import '../detail.css';

defineOptions({ name: 'ModelingLogicTableDetail' });

const props = defineProps<{ modelId: string }>();

const emit = defineEmits<{ back: []; edit: [model: TableModelApi.Model] }>();

const { tokens } = useAntdDesignTokens();

const router = useRouter();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const loading = ref(false);
const failed = ref(false);
const saving = ref(false);
const model = ref<TableModelApi.Model>();
const design = ref<TableModelApi.Design>();
const activeTab = ref('fields');
const keyword = ref('');
const pageNo = ref(1);
const pageSize = ref(10);
const selected = ref(new Set<string>());
const fieldModal = ref<InstanceType<typeof FieldModal>>();
const hiddenColumns = ref(new Set<string>());
const atomicIndicators = ref<IndicatorModelApi.Model[]>([]);
const conditions = ref<BusinessConditionApi.Model[]>([]);
const relatedLoading = ref(false);
const relatedError = ref(false);
const relatedDenied = computed(
  () =>
    !hasAccessByCodes([
      activeTab.value === 'atomic'
        ? 'modeling:indicator-model:query'
        : 'modeling:business-condition:query',
    ]),
);
let sequence = 0;
let relatedSequence = 0;

const source = computed(() => model.value?.tableType === 'SOURCE');
const summary = computed(() => model.value?.tableType === 'SUMMARY');
const activity = computed(() => model.value?.tableType === 'DETAIL');
const dimension = computed(() => model.value?.tableType === 'DIMENSION');
const fields = computed(() => design.value?.fields || []);
const editable = computed(
  () =>
    hasAccessByCodes(['modeling:model:save']) &&
    !!design.value &&
    design.value.status !== 'DISCARDED',
);
const canEditModel = computed(
  () =>
    hasAccessByCodes(['modeling:model:save']) ||
    hasAccessByCodes(['modeling:logic-table:update']),
);
const filtered = computed(() =>
  filteredDetailFields(fields.value, keyword.value),
);
const sorted = computed(() => {
  const result = [...filtered.value];
  if (sortField.value)
    result.sort(
      (first, second) =>
        String(first[sortField.value] ?? '').localeCompare(
          String(second[sortField.value] ?? ''),
          'zh-CN',
          { numeric: true },
        ) * (sortAsc.value ? 1 : -1),
    );
  return result;
});
const paged = computed(() =>
  sorted.value.slice(
    (pageNo.value - 1) * pageSize.value,
    pageNo.value * pageSize.value,
  ),
);
const allSelected = computed(
  () =>
    paged.value.length > 0 &&
    paged.value.every((field) => selected.value.has(key(field))),
);
const sortField = ref<keyof ModelingModelApi.ModelField>('sort');
const sortAsc = ref(true);
const tableNameLabel = computed(() => {
  const labels: Record<string, string> = {
    SOURCE: '英文名称',
    DIMENSION: '维度逻辑表英文名',
    DETAIL: '事实逻辑表英文名',
    SUMMARY: '汇总表英文名',
    APPLICATION: 'ADS应用表英文名',
  };
  return labels[model.value?.tableType || 'APPLICATION'];
});
const tabs = computed(() => [
  { key: 'fields', label: '属性字段' },
  ...(dimension.value || activity.value
    ? [
        { key: 'atomic', label: '原子指标' },
        { key: 'conditions', label: '业务限定' },
      ]
    : []),
  ...(summary.value ? [{ key: 'groups', label: '字段分组' }] : []),
  { key: 'versions', label: source.value ? '历史版本' : '版本与变更' },
]);
const columns = computed(() => {
  if (source.value)
    return [
      { key: 'sort', label: '编号', width: 70, sortable: true },
      { key: 'cnName', label: '中文名称', width: 180 },
      { key: 'fieldName', label: '英文名称', width: 200 },
      { key: 'dataType', label: '字段类型', width: 160 },
      { key: 'dataLength', label: '字段长度', width: 140, sortable: true },
      { key: 'dataScale', label: '小数位数', width: 140, sortable: true },
      { key: 'nullableFlag', label: '是否可空', width: 130 },
      { key: 'pkFlag', label: '是否主键', width: 130 },
      { key: 'defaultValue', label: '默认值', width: 140 },
    ];
  if (summary.value)
    return [
      { key: 'sort', label: '编号', width: 90, sortable: true },
      { key: 'fieldName', label: '字段英文名', width: 220 },
      { key: 'cnName', label: '字段中文名', width: 220 },
      { key: 'comment', label: '描述', width: 350 },
      { key: 'fieldCategory', label: '字段类型', width: 180 },
      { key: 'dataType', label: '数据类型', width: 180 },
      { key: 'dimensionName', label: '对应对象/指标', width: 230 },
      { key: 'pkFlag', label: '是否主键', width: 130 },
    ];
  return [
    { key: 'sort', label: '编号', width: 70, sortable: true },
    { key: 'fieldName', label: '字段英文名称', width: 160 },
    { key: 'cnName', label: '字段中文名称', width: 160 },
    { key: 'dataType', label: '数据类型', width: 135 },
    { key: 'fieldCategory', label: '字段类别', width: 130 },
    { key: 'dimensionName', label: '关联维度', width: 130 },
    { key: 'dataLength', label: '属性长度', width: 130 },
    { key: 'dataScale', label: '小数位数', width: 130 },
    { key: 'defaultValue', label: '默认值', width: 120 },
    { key: 'dataClassificationName', label: '数据分类', width: 150 },
    { key: 'dataGrade', label: '数据分级', width: 140 },
    { key: 'constraints', label: '字段约束', width: 180 },
    { key: 'standardName', label: '标准数据元', width: 160 },
    { key: 'codeTableName', label: '标准代码表', width: 160 },
  ];
});
const shownColumns = computed(() =>
  columns.value.filter((column) => !hiddenColumns.value.has(column.key)),
);
const timeLabel = computed(() => {
  const value = model.value?.dataTimeliness;
  return (
    getDictOptions(DICT_TYPE.MODELING_LOGIC_DATA_TIMELINESS, 'string').find(
      (option) => option.value === value,
    )?.label ||
    value ||
    '-'
  );
});
const entityType = computed(() => {
  const value = activity.value
    ? model.value?.businessType
    : model.value?.objType;
  return (
    (
      {
        EVENT: '业务事件',
        PROCESS: '业务流程',
        NORMAL: '普通对象',
        HIERARCHICAL: '层级对象',
      } as Record<string, string>
    )[value || ''] ||
    value ||
    '-'
  );
});
const groupedFields = computed(() => summaryFieldGroups(fields.value));
const owner = computed(
  () =>
    [
      model.value?.ownerName || model.value?.contactUserId,
      model.value?.ownerPhone || model.value?.contactNumber,
    ]
      .filter(Boolean)
      .join('/') || '-',
);
const belonging = computed(
  () =>
    [model.value?.dataDomainName, model.value?.subjectDomainName]
      .filter(Boolean)
      .join(' / ') || '-',
);
const sourceLocation = computed(
  () =>
    [model.value?.dbName, model.value?.schemaName]
      .filter(Boolean)
      .join(' / ') || '-',
);
function key(field: ModelingModelApi.ModelField) {
  return field.fieldUid || field.fieldName || '';
}
function cell(field: ModelingModelApi.ModelField, name: string, index: number) {
  if (name === 'sort')
    return field.sort ?? (pageNo.value - 1) * pageSize.value + index + 1;
  if (name === 'fieldCategory') return fieldCategory(field);
  if (name === 'dataLength')
    return text(field.dataLength ?? field.dataPrecision);
  if (name === 'constraints')
    return fieldConstraints(field).join(' / ') || '无';
  return text(field[name as keyof ModelingModelApi.ModelField]);
}
function sort(column: string) {
  if (sortField.value === column) sortAsc.value = !sortAsc.value;
  else {
    sortField.value = column as keyof ModelingModelApi.ModelField;
    sortAsc.value = true;
  }
  pageNo.value = 1;
}
function select(field: ModelingModelApi.ModelField, checked: boolean) {
  const next = new Set(selected.value);
  if (checked) next.add(key(field));
  else next.delete(key(field));
  selected.value = next;
}
function selectAll(checked: boolean) {
  paged.value.forEach((field) => select(field, checked));
}
function toggleColumn(column: string, checked: boolean) {
  const next = new Set(hiddenColumns.value);
  if (checked) next.delete(column);
  else next.add(column);
  hiddenColumns.value = next;
}
function changePage(page: number, size: number) {
  pageNo.value = page;
  pageSize.value = size;
}
async function load() {
  const request = ++sequence;
  loading.value = true;
  failed.value = false;
  try {
    const [detail, currentDesign] = await Promise.all([
      getTableModel(props.modelId),
      getTableModelDesign(props.modelId),
    ]);
    if (request !== sequence) return;
    assertCurrentDesign(detail, currentDesign);
    model.value = detail;
    design.value = currentDesign;
    selected.value = new Set();
    pageNo.value = Math.min(
      pageNo.value,
      Math.max(1, Math.ceil(fields.value.length / pageSize.value)),
    );
  } catch {
    if (request === sequence) failed.value = true;
  } finally {
    if (request === sequence) loading.value = false;
  }
}
async function loadRelated() {
  const request = ++relatedSequence;
  atomicIndicators.value = [];
  conditions.value = [];
  if (relatedDenied.value) return;
  relatedLoading.value = true;
  relatedError.value = false;
  try {
    if (activeTab.value === 'atomic') {
      const result = await loadLogicOptionPages((page) =>
        getIndicatorPage({
          ...page,
          indicatorType: 'ATOMIC',
          sourceModelId: props.modelId,
        }),
      );
      if (request === relatedSequence) atomicIndicators.value = result;
    } else if (activeTab.value === 'conditions') {
      const result = await loadLogicOptionPages((page) =>
        getBusinessConditionPage({
          ...page,
          sourceModelId: props.modelId,
        }),
      );
      if (request === relatedSequence) conditions.value = result;
    }
  } catch {
    if (request === relatedSequence) relatedError.value = true;
  } finally {
    if (request === relatedSequence) relatedLoading.value = false;
  }
}
async function persist(next: ModelingModelApi.ModelField[]) {
  const current = model.value;
  const currentDesign = design.value;
  if (!current || !currentDesign || !editable.value || saving.value)
    return false;
  const request = sequence;
  saving.value = true;
  try {
    const saved = await saveTableModelDraft({
      ...modelSaveData(current),
      modelId: current.id,
      expectedRevision: current.revision,
      typeConfig: currentDesign.typeConfig || null,
      fields: next.map((field, index) => ({ ...field, sort: index + 1 })),
    });
    if (request !== sequence || saved === false) return false;
    message.success('保存成功');
    await load();
    return true;
  } finally {
    if (request === sequence || request + 1 === sequence) saving.value = false;
  }
}
async function saveField(field: ModelingModelApi.ModelField) {
  if (!editable.value || loading.value || saving.value) return;
  const next = [...fields.value];
  const index = next.findIndex((item) => key(item) === key(field));
  if (index === -1) next.push(field);
  else next[index] = field;
  if (await persist(next)) fieldModal.value?.close();
}
function removeFields(targets: ModelingModelApi.ModelField[]) {
  if (!editable.value || saving.value || loading.value || targets.length === 0)
    return;
  const request = sequence;
  const ids = new Set(targets.map((field) => key(field)));
  Modal.confirm({
    title:
      targets.length === 1
        ? `确认删除字段「${targets[0]?.cnName || targets[0]?.fieldName}」？`
        : `确认删除选中的 ${targets.length} 个字段？`,
    okText: '删除',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: async () => {
      if (request === sequence)
        await persist(fields.value.filter((field) => !ids.has(key(field))));
    },
  });
}
watch(keyword, () => {
  pageNo.value = 1;
  selected.value = new Set();
});
watch(activeTab, () => {
  relatedSequence++;
  if (activeTab.value === 'atomic' || activeTab.value === 'conditions')
    void loadRelated();
});
watch(
  () => [
    props.modelId,
    access.accessToken,
    access.refreshToken,
    JSON.stringify(access.accessCodes),
  ],
  () => {
    sequence++;
    relatedSequence++;
    model.value = undefined;
    design.value = undefined;
    saving.value = false;
    activeTab.value = 'fields';
    keyword.value = '';
    selected.value = new Set();
    void load();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  sequence++;
  relatedSequence++;
});
</script>

<template>
  <ConfigProvider :theme="{ token: { borderRadius: tokens.borderRadius } }">
    <div class="qd-logic-detail" :class="{ 'is-source': source }">
      <div v-if="!model" class="qd-logic-detail-state">
        <Spin v-if="loading" /><Alert
          v-else
          type="error"
          show-icon
          message="模型详情加载失败"
        >
          <template #action>
            <button class="qd-logic-detail-btn" @click="load">重试</button><button class="qd-logic-detail-btn" @click="emit('back')">
              返回
            </button>
          </template>
        </Alert>
      </div>
      <template v-else>
        <section class="qd-logic-detail-summary">
          <div class="qd-logic-detail-heading">
            <span class="qd-logic-detail-id">{{ model.id }}</span>
            <h1>{{ model.name }}</h1>
            <span
              class="qd-logic-detail-tag"
              :class="model.enabled === 1 ? 'green' : 'red'"
              >{{ model.enabled === 1 ? '启用' : '禁用' }}</span>
            <div class="qd-logic-detail-heading-actions">
              <button
                v-if="!source && !summary && canEditModel"
                class="qd-logic-detail-btn"
                @click="emit('edit', model)"
              >
                <QdIcon name="edit" />修改
</button><button class="qd-logic-detail-btn" @click="emit('back')">
                <QdIcon name="arrowLeft" />返回
              </button>
            </div>
          </div>
          <dl class="qd-logic-detail-info">
            <dt>{{ tableNameLabel }}</dt>
            <dd>{{ text(model.tableNameNotation || model.code) }}</dd>
            <dt>{{ source ? '来源系统' : '归属信息' }}</dt>
            <dd>
              <img v-if="!source" :src="domainIcon" alt="" />{{
                source ? text(model.sourceSystemName) : belonging
              }}
            </dd>
            <dt>
              {{
                source
                  ? '来源位置'
                  : summary
                    ? '统计时效'
                    : dimension || activity
                      ? activity
                        ? '业务活动'
                        : '业务对象'
                      : '字段数'
              }}
            </dt>
            <dd>
              <span v-if="source" class="qd-logic-detail-tag">{{
                sourceLocation
              }}</span><span v-else-if="summary" class="qd-logic-detail-tag green">{{
                timeLabel
              }}</span><template v-else-if="dimension || activity">
                <span class="qd-logic-detail-tag">{{ entityType }}</span>{{ text(model.entityName) }}
</template><template v-else>{{ fields.length }}个</template>
            </dd>
            <dt>描述</dt>
            <dd class="wide">{{ text(model.description) }}</dd>
            <dt>
              {{
                summary
                  ? '统计粒度'
                  : source || (!dimension && !activity)
                    ? '负责人'
                    : '数据时效'
              }}
            </dt>
            <dd>
              <span
                v-if="dimension || activity"
                class="qd-logic-detail-tag green"
                >{{ timeLabel }}</span><span v-else-if="summary" class="qd-logic-detail-tag">{{
                text(model.statisticsStr || model.grain)
              }}</span><template v-else>{{ owner }}</template>
            </dd>
            <dt>
              {{
                source || (!summary && !dimension && !activity)
                  ? '创建人'
                  : '字段数'
              }}
            </dt>
            <dd>
              {{
                source || (!summary && !dimension && !activity)
                  ? text(model.creatorName || model.creator)
                  : `${fields.length}个`
              }}
            </dd>
            <dt>
              {{
                summary
                  ? '指标数'
                  : dimension || activity
                    ? '负责人'
                    : '创建时间'
              }}
            </dt>
            <dd>
              {{
                summary
                  ? groupedFields.find((group) => group.key === 'INDICATOR')
                      ?.fields.length || 0
                  : dimension || activity
                    ? owner
                    : formatDateTime(model.createTime) || '-'
              }}
            </dd>
          </dl>
        </section>
        <section class="qd-logic-detail-panel">
          <nav class="qd-logic-detail-tabs" aria-label="逻辑表详情">
            <button
              v-for="tab in tabs"
              :key="tab.key"
              :class="{ active: activeTab === tab.key }"
              :aria-current="activeTab === tab.key ? 'page' : undefined"
              @click="activeTab = tab.key"
            >
              {{ tab.label }}
            </button>
          </nav>
          <Alert
            v-if="failed"
            message="刷新失败，请重试"
            type="error"
            show-icon
          />
          <template v-if="activeTab === 'fields'">
            <div v-if="!source" class="qd-logic-detail-toolbar">
              <template v-if="!summary">
                <button
                  v-if="hasAccessByCodes(['modeling:model:save'])"
                  class="qd-logic-detail-btn"
                  :disabled="!editable || loading || saving"
                  @click="fieldModal?.open()"
                >
                  <QdIcon name="plus" />新增字段
</button><button
                  v-if="hasAccessByCodes(['modeling:model:save'])"
                  class="qd-logic-detail-btn danger"
                  :disabled="!editable || !selected.size || saving"
                  @click="
                    removeFields(
                      fields.filter((field) => selected.has(key(field))),
                    )
                  "
                >
                  <QdIcon name="delete" />批量删除
                </button>
</template><span class="qd-logic-detail-toolbar-spacer"></span><Input
                v-model:value="keyword"
                class="qd-logic-detail-search"
                placeholder="请输入字段中文/英文名称"
                allow-clear
              /><button
                class="qd-logic-detail-btn square"
                aria-label="刷新字段"
                :disabled="loading || saving"
                @click="load"
              >
                <QdIcon name="toolbarRefresh" />
</button><Popover trigger="click" placement="bottomRight">
                <template #content>
                  <div class="qd-logic-detail-columns">
                    <Checkbox
                      v-for="column in columns"
                      :key="column.key"
                      :checked="!hiddenColumns.has(column.key)"
                      @change="toggleColumn(column.key, $event.target.checked)"
                    >
                      {{ column.label }}
                    </Checkbox>
                  </div>
</template><button
                  class="qd-logic-detail-btn square"
                  aria-label="设置表格列"
                >
                  <QdIcon name="menu" />
                </button>
              </Popover>
            </div>
            <Spin :spinning="loading || saving">
              <div class="qd-logic-detail-table-scroll">
                <table
                  class="qd-logic-detail-table"
                  :style="{ minWidth: source || summary ? '100%' : '2360px' }"
                >
                  <colgroup>
                    <col v-if="!source && !summary" style="width: 42px" />
                    <col
                      v-for="column in shownColumns"
                      :key="column.key"
                      :style="{ width: `${column.width}px` }"
                    />
                    <col v-if="!source && !summary" style="width: 220px" />
                  </colgroup>
                  <thead>
                    <tr>
                      <th v-if="!source && !summary">
                        <Checkbox
                          :checked="allSelected"
                          :indeterminate="selected.size > 0 && !allSelected"
                          @change="selectAll($event.target.checked)"
                        />
                      </th>
                      <th v-for="column in shownColumns" :key="column.key">
                        <button
                          v-if="column.sortable"
                          class="qd-logic-detail-sort"
                          @click="sort(column.key)"
                        >
                          {{ column.label
                          }}<span
                            :class="{ active: sortField === column.key }"
                            >{{
                              sortField === column.key && !sortAsc ? '▾' : '▴'
                            }}</span>
</button><template v-else>{{ column.label }}</template>
                      </th>
                      <th v-if="!source && !summary" class="sticky-action">
                        操作
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="(field, index) in paged"
                      :key="key(field)"
                      :class="{ selected: selected.has(key(field)) }"
                    >
                      <td v-if="!source && !summary">
                        <Checkbox
                          :checked="selected.has(key(field))"
                          @change="select(field, $event.target.checked)"
                        />
                      </td>
                      <td v-for="column in shownColumns" :key="column.key">
                        <span
                          v-if="
                            column.key === 'nullableFlag' ||
                            column.key === 'pkFlag'
                          "
                          class="qd-logic-detail-tag"
                          :class="
                            column.key === 'pkFlag' && !field.pkFlag
                              ? 'red'
                              : ''
                          "
                          >{{
                            column.key === 'pkFlag'
                              ? field.pkFlag
                                ? '是'
                                : '否'
                              : field.nullableFlag !== false
                                ? '是'
                                : '否'
                          }}</span><span
                          v-else-if="
                            summary &&
                            ['fieldCategory', 'dataType'].includes(column.key)
                          "
                          class="qd-logic-detail-tag"
                          >{{ cell(field, column.key, index) }}</span><template v-else>
                          {{ cell(field, column.key, index) }}
                        </template>
                      </td>
                      <td v-if="!source && !summary" class="sticky-action">
                        <div class="qd-logic-detail-row-actions">
                          <button
                            class="qd-logic-detail-link"
                            @click="fieldModal?.open(field, 'detail')"
                          >
                            <QdIcon name="view" />详情
</button><button
                            v-if="hasAccessByCodes(['modeling:model:save'])"
                            class="qd-logic-detail-link"
                            :disabled="!editable || saving || loading"
                            @click="fieldModal?.open(field)"
                          >
                            <QdIcon name="edit" />修改
</button><button
                            v-if="hasAccessByCodes(['modeling:model:save'])"
                            class="qd-logic-detail-link danger"
                            :disabled="!editable || saving || loading"
                            @click="removeFields([field])"
                          >
                            <QdIcon name="delete" />删除
                          </button>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
                <div v-if="!paged.length" class="qd-logic-detail-empty">
                  <img :src="emptyRecords" alt="" /><span>暂无记录</span>
                </div>
              </div>
            </Spin>
            <ReferencePagination
              :page-no="pageNo"
              :page-size="pageSize"
              :total="filtered.length"
              @change="changePage"
            />
          </template>
          <template v-else-if="activeTab === 'groups'">
            <div
              v-for="group in groupedFields"
              :key="group.key"
              class="qd-logic-detail-group"
            >
              <p>
                {{ group.title }} ({{ group.fields.length }})
                {{ group.description }}
              </p>
              <div class="qd-logic-detail-table-scroll">
                <table class="qd-logic-detail-table">
                  <thead>
                    <tr v-if="group.key === 'INDICATOR'">
                      <th>指标英文名</th>
                      <th>指标中文名</th>
                      <th>描述</th>
                      <th>指标口径</th>
                      <th>原子指标</th>
                      <th>业务限定</th>
                      <th>统计周期</th>
                      <th>数据类型</th>
                      <th>度量单位</th>
                      <th>来源逻辑表</th>
                    </tr>
                    <tr v-else>
                      <th>字段英文名</th>
                      <th>字段中文名</th>
                      <th>描述</th>
                      <th>数据类型</th>
                      <th>字段角色</th>
                      <th>
                        {{ group.key === 'TIME' ? '时间粒度' : '对应业务对象' }}
                      </th>
                      <th v-if="group.key !== 'TIME'">是否主键</th>
                    </tr>
                  </thead>
                  <tbody>
                    <template v-for="field in group.fields" :key="key(field)">
                      <tr v-if="group.key === 'INDICATOR'">
                        <td>{{ text(field.fieldName) }}</td>
                        <td>{{ text(field.cnName) }}</td>
                        <td>{{ text(field.remark) }}</td>
                        <td>{{ text(field.comment) }}</td>
                        <td>{{ text(field.atomicIndicatorName) }}</td>
                        <td>{{ text(field.businessCondition) }}</td>
                        <td>{{ text(field.statisticsPeriod) }}</td>
                        <td>{{ text(field.dataType) }}</td>
                        <td>{{ text(field.measureUnit) }}</td>
                        <td>{{ text(field.sourceModelName) }}</td>
                      </tr>
                      <tr v-else>
                        <td>{{ text(field.fieldName) }}</td>
                        <td>{{ text(field.cnName) }}</td>
                        <td>{{ text(field.comment) }}</td>
                        <td>{{ text(field.dataType) }}</td>
                        <td>
                          <span class="qd-logic-detail-tag">{{
                            fieldCategory(field)
                          }}</span>
                        </td>
                        <td>
                          {{
                            group.key === 'TIME'
                              ? timeLabel
                              : text(field.dimensionName)
                          }}
                        </td>
                        <td v-if="group.key !== 'TIME'">
                          <span class="qd-logic-detail-tag">{{
                            field.pkFlag ? '是' : '否'
                          }}</span>
                        </td>
                      </tr>
                    </template>
                  </tbody>
                </table>
                <div v-if="!group.fields.length" class="qd-logic-detail-empty">
                  <img :src="emptyRecords" alt="" /><span>暂无记录</span>
                </div>
              </div>
            </div>
          </template>
          <div
            v-else-if="activeTab === 'versions'"
            class="p-6 text-center text-muted-foreground"
          >
            暂未实现
          </div>
          <template v-else>
            <Spin :spinning="relatedLoading">
              <Alert
                v-if="relatedDenied"
                type="info"
                message="暂无关联信息查询权限"
              />
              <Alert
                v-else-if="relatedError"
                type="error"
                message="关联信息加载失败"
              >
                <template #action>
                  <button class="qd-logic-detail-link" @click="loadRelated">
                    重试
                  </button>
                </template>
              </Alert>
              <div v-else class="qd-logic-detail-table-scroll">
                <table class="qd-logic-detail-table">
                  <thead>
                    <tr>
                      <th>编号</th>
                      <th>名称</th>
                      <th>英文名称</th>
                      <th>
                        {{ activeTab === 'atomic' ? '指标口径' : '业务表达式' }}
                      </th>
                      <th>负责人</th>
                      <th>状态</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="item in activeTab === 'atomic'
                        ? atomicIndicators
                        : conditions"
                      :key="String(item.id)"
                    >
                      <td>{{ item.id }}</td>
                      <td>{{ item.name }}</td>
                      <td>{{ item.code }}</td>
                      <td>
                        {{
                          text(
                            activeTab === 'atomic'
                              ? (item as IndicatorModelApi.Model).definition
                              : (item as BusinessConditionApi.Model).expression,
                          )
                        }}
                      </td>
                      <td>{{ text(item.ownerUserName) }}</td>
                      <td>
                        <span
                          class="qd-logic-detail-tag"
                          :class="item.status === 'ACTIVE' ? 'green' : ''"
                          >{{
                            item.status === 'ACTIVE' ? '启用' : '草稿'
                          }}</span>
                      </td>
                      <td>
                        <button
                          class="qd-logic-detail-link"
                          @click="
                            router.push({
                              name:
                                activeTab === 'atomic'
                                  ? 'ModelingAtomicIndicatorDetail'
                                  : 'ModelingBusinessConditionDetail',
                              params: { id: String(item.id) },
                            })
                          "
                        >
                          <QdIcon name="view" />详情
                        </button>
                      </td>
                    </tr>
                  </tbody>
                </table>
                <div
                  v-if="
                    !(activeTab === 'atomic' ? atomicIndicators : conditions)
                      .length
                  "
                  class="qd-logic-detail-empty"
                >
                  <img :src="emptyRecords" alt="" /><span>暂无记录</span>
                </div>
              </div>
            </Spin>
          </template>
        </section>
      </template>
      <FieldModal ref="fieldModal" :saving="saving" @submit="saveField" />
    </div>
  </ConfigProvider>
</template>
