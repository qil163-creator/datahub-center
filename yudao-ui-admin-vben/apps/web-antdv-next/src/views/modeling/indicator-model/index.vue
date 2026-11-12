<script setup lang="ts">
import type { MetricFilter } from './state';

import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  ref,
  watch,
} from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { useMediaQuery } from '@vueuse/core';
import { Alert, message, Switch, TabPane, Tabs, Tag } from 'antdv-next';

import { TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  deleteIndicator,
  getIndicatorPage,
  updateIndicatorStatus,
} from '#/api/modeling/indicator-model';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { useObjectRequest } from '#/hooks/use-object-request';
import domainIcon from '#/views/modeling/logictable/assets/data-section-default-icon-small.svg';

import { options, PERIOD_LABELS, STATUS_LABELS, TYPE_LABELS } from './data';
import IndicatorForm from './modules/form.vue';
import IndicatorImpactModal from './modules/impact-modal.vue';
import MetricCategoryTree from './modules/metric-category-tree.vue';
import { METRIC_ACTION_ICON as ACTION_ICON } from './modules/metric-icons';
import MetricLayout from './modules/metric-layout.vue';
import { useMetricOptions } from './modules/use-metric-options';
import { indicatorDetailLocation, indicatorPageParams } from './state';

import './metric-list.css';

defineOptions({ name: 'ModelingIndicatorModel' });
const props = defineProps<{ fixedType?: IndicatorModelApi.Type }>();
const access = useAccessStore();
const user = useUserStore();
const route = useRoute();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const scope = computed(() =>
  JSON.stringify([user.userInfo?.userId, user.userRoles, access.accessCodes]),
);
const legacyType = ref<IndicatorModelApi.Type>('ATOMIC');
const indicatorType = computed(() => props.fixedType ?? legacyType.value);
const kind = computed(() =>
  indicatorType.value === 'ATOMIC' ? 'atomic' : 'derived',
);
const isMobile = useMediaQuery('(max-width: 767px)');
const keyword = ref('');
const freshnessOptions = computed(() =>
  getDictOptions(DICT_TYPE.MODELING_LOGIC_DATA_TIMELINESS, 'string'),
);
function freshnessLabel(value?: string) {
  return (
    freshnessOptions.value.find((item) => String(item.value) === value)
      ?.label ||
    value ||
    '-'
  );
}
const status = ref<IndicatorModelApi.Status>();
const sourceModelId = ref<IndicatorModelApi.Id>();
const navFilter = ref<MetricFilter>({});
const pageNo = ref(1);
const pageSize = ref(10);
const loadError = ref(false);
const selected = ref<IndicatorModelApi.Model[]>([]);
const busyIds = ref(new Set<string>());
const deleting = ref(false);
const canQuery = computed(() =>
  hasAccessByCodes(['modeling:indicator-model:query']),
);
const canMaintainSource = computed(
  () =>
    indicatorType.value !== 'ATOMIC' ||
    hasAccessByCodes(['modeling:model:query']),
);
const {
  domains,
  subjects,
  models,
  loading: optionsLoading,
  reload: reloadOptions,
  domainLabel,
  subjectLabel,
  userLabel,
  userPhone,
} = useMetricOptions();
let generation = 0;
let active = true;
const requests = useObjectRequest(
  () => scope.value,
  () => {
    generation++;
    busyIds.value = new Set();
    deleting.value = false;
    selected.value = [];
    gridApi.setGridOptions({
      data: [],
      loading: false,
      pagerConfig: { total: 0 },
    });
  },
);
const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: IndicatorForm,
  destroyOnClose: true,
});
const [Grid, gridApi] = useVbenVxeGrid<IndicatorModelApi.Model>({
  formOptions: {
    submitOnEnter: true,
    schema: [
      {
        fieldName: 'keyword',
        label: '指标名称',
        component: 'Input',
        componentProps: {
          allowClear: true,
          placeholder: '请输入指标中文或英文名称',
        },
      },
      {
        fieldName: 'sourceModelId',
        label: '来源逻辑表',
        component: 'Select',
        componentProps: () => ({
          allowClear: true,
          disabled: navFilter.value.sourceModelId !== undefined,
          options: sourceOptions.value,
          placeholder: '请选择来源逻辑表',
          showSearch: true,
          optionFilterProp: 'label',
        }),
      },
      {
        fieldName: 'status',
        label: '状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: options(STATUS_LABELS),
          placeholder: '请选择状态',
        },
      },
    ],
    handleSubmit: async (values) => {
      keyword.value = values.keyword ?? '';
      sourceModelId.value = values.sourceModelId;
      status.value = values.status;
      await load(true);
    },
    handleReset: async () => {
      await gridApi.formApi.reset();
      reset();
    },
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    data: [],
    height: 'auto',
    minHeight: 0,
    proxyConfig: { enabled: false },
    rowConfig: { keyField: 'id', isHover: true },
    pagerConfig: {
      align: 'right',
      currentPage: 1,
      pageSize: 10,
      pageSizes: [10, 20, 50, 100],
      total: 0,
    },
    toolbarConfig: {
      refresh: true,
      refreshOptions: { queryMethod: () => load() },
      search: true,
      zoom: false,
    },
    columns: [
      { type: 'checkbox', width: 48 },
      { type: 'seq', title: '编号', width: 64 },
      {
        field: 'name',
        title: '原子指标信息',
        align: 'left',
        width: indicatorType.value === 'ATOMIC' ? 320 : undefined,
        minWidth: indicatorType.value === 'ATOMIC' ? undefined : 198,
        slots: { default: 'indicator' },
        showOverflow: false,
      },
      {
        field: 'sourceModelName',
        title: '归属信息',
        align: 'left',
        width: indicatorType.value === 'ATOMIC' ? 400 : undefined,
        minWidth: indicatorType.value === 'ATOMIC' ? undefined : 241,
        slots: { default: 'belonging' },
        showOverflow: false,
      },
      {
        field: 'resultDataType',
        title: '字段类型/度量信息',
        align: 'left',
        width: 153,
        slots: { default: 'result' },
        showOverflow: false,
      },
      {
        field: 'dataFreshness',
        title: '数据时效',
        width: 89,
        slots: { default: 'freshness' },
      },
      {
        field: 'expression',
        title: '指标表达式/计算字段',
        align: 'left',
        minWidth: 224,
        slots: { default: 'expression' },
        showOverflow: false,
      },
      {
        field: 'derivedIndicatorCount',
        title: '关联派生指标数',
        width: 123,
        slots: { default: 'derivedCount' },
      },
      {
        field: 'status',
        title: '状态',
        width: 64,
        slots: { default: 'status' },
      },
      {
        field: 'creator',
        title: '创建人',
        align: 'left',
        width: 105,
        slots: { default: 'creator' },
        showOverflow: false,
      },
      {
        field: 'createTime',
        title: '创建时间',
        visible: false,
        width: 165,
        formatter: ({ cellValue }: { cellValue?: number }) =>
          formatDateTime(cellValue) || '-',
      },
      {
        title: '操作',
        width: 239,
        fixed: 'right',
        showOverflow: false,
        slots: { default: 'actions' },
      },
    ],
  },
  gridEvents: {
    pageChange: ({
      currentPage,
      pageSize: size,
    }: {
      currentPage: number;
      pageSize: number;
    }) => {
      pageNo.value = currentPage;
      pageSize.value = size;
      void load();
    },
    checkboxAll: ({ records }: { records: IndicatorModelApi.Model[] }) => {
      selected.value = records;
    },
    checkboxChange: ({ records }: { records: IndicatorModelApi.Model[] }) => {
      selected.value = records;
    },
  },
});

async function load(reset = false) {
  if (reset) pageNo.value = 1;
  const current = requests.begin();
  loadError.value = false;
  selected.value = [];
  gridApi.grid?.clearCheckboxRow?.();
  gridApi.setGridOptions({
    data: [],
    pagerConfig: {
      currentPage: pageNo.value,
      pageSize: pageSize.value,
      total: 0,
    },
    loading: canQuery.value && active,
  });
  if (!canQuery.value || !active) return;
  try {
    const result = await getIndicatorPage({
      ...indicatorPageParams({
        pageNo: pageNo.value,
        pageSize: pageSize.value,
        indicatorType: indicatorType.value,
        keyword: keyword.value,
        status: status.value,
        filter: navFilter.value,
        sourceModelId: sourceModelId.value,
      }),
      baseIndicatorId:
        typeof route.query.baseIndicatorId === 'string' &&
        /^[1-9][0-9]*$/.test(route.query.baseIndicatorId)
          ? route.query.baseIndicatorId
          : undefined,
    });
    if (current())
      gridApi.setGridOptions({
        data: result.list,
        pagerConfig: { total: result.total },
      });
  } catch {
    if (current()) loadError.value = true;
  } finally {
    if (current()) gridApi.setGridOptions({ loading: false });
  }
}
function reset() {
  keyword.value = '';
  status.value = undefined;
  sourceModelId.value = undefined;
  navFilter.value = {};
  void load(true);
}
function openDetail(row: IndicatorModelApi.Model) {
  void router.push(indicatorDetailLocation(row, route.path));
}
function edit(row?: IndicatorModelApi.Model, clone = false) {
  formModalApi
    .setData({
      id: clone ? undefined : row?.id,
      cloneId: clone ? row?.id : undefined,
      indicatorType: indicatorType.value,
    })
    .open();
}
function pending(id: IndicatorModelApi.Id, value: boolean) {
  const next = new Set(busyIds.value);
  if (value) next.add(String(id));
  else next.delete(String(id));
  busyIds.value = next;
}
async function changeStatus(row: IndicatorModelApi.Model) {
  if (busyIds.value.has(String(row.id))) return;
  const version = generation;
  const next = row.status === 'ACTIVE' ? 'DRAFT' : 'ACTIVE';
  pending(row.id, true);
  try {
    await confirm(
      `确认要${next === 'ACTIVE' ? '启用' : '禁用'}${TYPE_LABELS[row.indicatorType]}“${row.name}”吗？`,
    );
    if (version !== generation || !active) return;
    await updateIndicatorStatus({
      id: row.id,
      expectedRevision: row.revision,
      status: next,
    });
    if (version === generation && active) {
      message.success(next === 'ACTIVE' ? '指标已启用' : '指标已禁用');
      await load();
    }
  } catch {
    /* 取消及接口错误不改变当前行，接口错误由请求层统一提示。 */
  } finally {
    if (version === generation && active) pending(row.id, false);
  }
}
async function remove(rows: IndicatorModelApi.Model[]) {
  if (deleting.value || rows.length === 0) return;
  const version = generation;
  deleting.value = true;
  try {
    await confirm(
      `是否确认删除选中的 ${rows.length} 条${TYPE_LABELS[indicatorType.value]}？`,
    );
    if (version !== generation || !active) return;
    let succeeded = 0;
    for (const row of rows) {
      if (version !== generation || !active) return;
      try {
        await deleteIndicator(row.id, row.revision);
        succeeded++;
      } catch {
        /* 单条失败后继续其余对象，并在结束时报告结果。 */
      }
    }
    if (version !== generation || !active) return;
    if (succeeded === rows.length) message.success('删除成功');
    else
      message.warning(
        `已删除 ${succeeded} 条，${rows.length - succeeded} 条未删除，请刷新后重试。`,
      );
    selected.value = [];
    await load();
  } catch {
    /* 取消确认时保持列表。 */
  } finally {
    if (version === generation && active) deleting.value = false;
  }
}
const sourceOptions = computed(() =>
  models.value.map((model) => ({
    value: String(model.id),
    label: `${model.name || model.code} (${model.code})`,
  })),
);
function belonging(row: IndicatorModelApi.Model) {
  return `${domainLabel(row.dataDomainId)} / ${subjectLabel(row.subjectDomainId)}`;
}
watch(
  [indicatorType, isMobile],
  () => {
    const columns = gridApi.state?.gridOptions?.columns;
    if (columns)
      gridApi.setGridOptions({
        columns: columns.flatMap((column) => {
          if (!column) return [];
          let fixed = column.fixed;
          if (column.title === '操作') {
            fixed = undefined;
            if (!isMobile.value) fixed = 'right';
          }
          return [
            {
              ...column,
              title:
                column.field === 'name'
                  ? `${TYPE_LABELS[indicatorType.value]}信息`
                  : column.title,
              ...(column.field === 'name' || column.field === 'sourceModelName'
                ? {
                    width:
                      indicatorType.value === 'ATOMIC'
                        ? column.field === 'name'
                          ? 320
                          : 400
                        : undefined,
                    minWidth:
                      indicatorType.value === 'ATOMIC'
                        ? undefined
                        : column.field === 'name'
                          ? 198
                          : 241,
                  }
                : {}),
              fixed,
            },
          ];
        }),
      });
  },
  { immediate: true },
);
watch(indicatorType, () => {
  navFilter.value = {};
  void load(true);
});
watch(
  () => `${access.accessToken}/${access.refreshToken}/${scope.value}`,
  () => {
    void load(true);
  },
  { immediate: true },
);
watch(
  () => route.query.baseIndicatorId,
  () => {
    void load(true);
  },
);
onDeactivated(() => {
  active = false;
  generation++;
  requests.begin();
  busyIds.value = new Set();
  deleting.value = false;
  gridApi.setGridOptions({ loading: false });
  void formModalApi.close();
});
onBeforeUnmount(() => {
  active = false;
  generation++;
  busyIds.value = new Set();
  deleting.value = false;
});
onActivated(() => {
  if (!active) {
    active = true;
    void load();
  }
});

const impactOpen = ref(false);
const impactId = ref<IndicatorModelApi.Id>();
function openImpact(row: IndicatorModelApi.Model) {
  impactId.value = row.id;
  impactOpen.value = true;
}
</script>

<template>
  <Page
    auto-content-height
    class="qd-metric-page"
    content-class="qd-metric-content"
  >
    <FormModal @success="load(true)" />
    <MetricLayout @resize="active && gridApi.grid.recalculate?.(true)">
      <template #tree>
        <MetricCategoryTree
          v-model:value="navFilter"
          :kind="kind"
          :domains="domains"
          :subjects="subjects"
          :models="models"
          :loading="optionsLoading"
          @refresh="reloadOptions"
          @select="load(true)"
        />
      </template>
      <Tabs
        v-if="!fixedType"
        v-model:active-key="legacyType"
        class="shrink-0 rounded-lg bg-card px-4"
      >
        <TabPane key="ATOMIC" tab="原子指标" />
        <TabPane key="DERIVED" tab="派生指标" />
      </Tabs>
      <Alert v-if="!canQuery" type="warning" message="暂无指标模型查询权限" />
      <Grid class="metric-list-grid min-h-0 flex-1" table-title="指标模型">
        <template #toolbar-tools>
          <TableAction
            :actions="[
              {
                label: '新增',
                type: 'primary',
                icon: ACTION_ICON.ADD,
                auth: ['modeling:indicator-model:create'],
                disabled: !canQuery || !canMaintainSource,
                onClick: () => edit(),
              },
              {
                label: '批量删除',
                type: 'default',
                icon: ACTION_ICON.DELETE,
                danger: true,
                auth: ['modeling:indicator-model:delete'],
                disabled: !selected.length || deleting,
                onClick: () => remove([...selected]),
              },
            ]"
          />
        </template>
        <template #indicator="{ row }">
          <div class="metric-info metric-info--primary">
            <button
              type="button"
              class="metric-info__link"
              :title="`${row.name} (${row.code})`"
              @click="openDetail(row)"
            >
              <img
                v-if="indicatorType === 'ATOMIC'"
                class="qd-metric-symbol"
                src="./reference-assets/atomic-indicator-icon.png"
                alt=""
              />
              {{ row.name }} ({{ row.code }})
            </button>
            <TextEllipsisCell
              class="metric-muted"
              :content="row.description"
              :line="2"
            />
          </div>
        </template>
        <template #belonging="{ row }">
          <div class="metric-info">
            <div class="metric-ellipsis" :title="belonging(row)">
              <img class="qd-metric-symbol" :src="domainIcon" alt="" />
              {{ belonging(row) }}
            </div>
            <div
              class="metric-ellipsis"
              :title="row.sourceModelName || row.baseIndicatorName"
            >
              {{ row.sourceModelName || row.baseIndicatorName || '-'
              }}<template v-if="row.sourceModelCode">
                （{{ row.sourceModelCode }}）
              </template>
            </div>
          </div>
        </template>
        <template #result="{ row }">
          <div class="metric-info">
            <span>{{ row.resultDataType || '-' }}</span><span>{{ row.unit || '-' }}</span>
          </div>
        </template>
        <template #freshness="{ row }">
          <Tag v-if="row.dataFreshness" color="success">
            {{ freshnessLabel(row.dataFreshness) }}
</Tag><span v-else>-</span>
        </template>
        <template #expression="{ row }">
          <span v-if="indicatorType === 'ATOMIC'" class="metric-expression">{{ row.expression || '-' }}/{{ row.timeFieldName || '-' }}</span><span v-else class="metric-expression">{{ row.expression || row.businessCondition || '-' }}<br />{{
              row.timePeriod
                ? PERIOD_LABELS[row.timePeriod as IndicatorModelApi.Period]
                : '-'
            }}</span>
        </template>
        <template #derivedCount="{ row }">
          <button
            v-if="row.indicatorType === 'ATOMIC' && row.derivedIndicatorCount"
            class="metric-count"
            @click="
              router.push({
                path: '/model/indicator-model/derived',
                query: { baseIndicatorId: String(row.id) },
              })
            "
          >
            {{ row.derivedIndicatorCount }}
</button><span v-else>{{
            row.indicatorType === 'ATOMIC'
              ? (row.derivedIndicatorCount ?? 0)
              : '-'
          }}</span>
        </template>
        <template #status="{ row }">
          <Switch
            size="small"
            :checked="row.status === 'ACTIVE'"
            :loading="busyIds.has(String(row.id))"
            :disabled="
              busyIds.has(String(row.id)) ||
              !hasAccessByCodes(['modeling:indicator-model:update'])
            "
            :aria-label="`${row.status === 'ACTIVE' ? '禁用' : '启用'}${row.name}`"
            @change="changeStatus(row)"
          />
        </template>
        <template #creator="{ row }">
          <div class="metric-info">
            <span>{{ row.creatorName || userLabel(row.creator) }}</span><span>{{
              row.creatorPhone || userPhone(row.creator) || '-'
            }}</span>
          </div>
        </template>
        <template #actions="{ row }">
          <TableAction
            class="qd-metric-row-actions"
            :divider="false"
            :actions="[
              {
                label: '详情',
                type: 'link',
                icon: ACTION_ICON.VIEW,
                auth: ['modeling:indicator-model:query'],
                onClick: () => openDetail(row),
              },
              {
                label: '修改',
                type: 'link',
                icon: ACTION_ICON.EDIT,
                auth: ['modeling:indicator-model:update'],
                disabled:
                  row.status === 'ACTIVE' ||
                  !canMaintainSource ||
                  busyIds.has(String(row.id)),
                onClick: () => edit(row),
              },
            ]"
            :drop-down-actions="[
              {
                label: '影响分析',
                icon: 'carbon:flow',
                auth: ['modeling:indicator-model:query'],
                onClick: () => openImpact(row),
              },
              {
                label: '克隆',
                icon: 'qdata:documentcopy',
                auth: ['modeling:indicator-model:create'],
                disabled: !canMaintainSource,
                onClick: () => edit(row, true),
              },
              {
                label: '删除',
                icon: ACTION_ICON.DELETE,
                danger: true,
                auth: ['modeling:indicator-model:delete'],
                disabled: row.status === 'ACTIVE' || deleting,
                onClick: () => remove([row]),
              },
            ]"
          />
        </template>
      </Grid>
    </MetricLayout>
    <IndicatorImpactModal v-model:open="impactOpen" :indicator-id="impactId" />
  </Page>
</template>

<style scoped>
.metric-list-grid :deep(.vxe-body--column) {
  font-size: 12px;
}

.metric-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  min-width: 0;
  padding: 6px 0;
  line-height: 1.4;
}

.metric-info__link {
  display: block;
  width: 100%;
  min-width: 0;
  padding: 0;
  font: inherit;
  font-size: 12px;
  line-height: 22px;
  color: hsl(var(--primary));
  text-align: left;
  overflow-wrap: anywhere;
  white-space: normal;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.metric-info--primary {
  padding-inline-end: 20px;
}

.metric-info__link:focus-visible {
  outline: 2px solid hsl(var(--primary));
  outline-offset: 2px;
}

.metric-muted {
  font-size: 12px;
  line-height: 18px;
  color: hsl(var(--muted-foreground));
  overflow-wrap: anywhere;
  white-space: normal;
}

.metric-ellipsis {
  overflow-wrap: anywhere;
  white-space: normal;
}

.metric-expression {
  display: block;
  line-height: 20px;
  overflow-wrap: anywhere;
}

.metric-count {
  color: hsl(var(--primary));
  cursor: pointer;
  background: transparent;
  border: 0;
}
</style>
