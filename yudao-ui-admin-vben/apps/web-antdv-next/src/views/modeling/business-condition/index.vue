<script setup lang="ts">
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { BusinessConditionApi } from '#/api/modeling/business-condition';

import { computed, onActivated, onDeactivated, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { confirm, Page } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { useMediaQuery } from '@vueuse/core';
import { Alert, Button, message, Switch, Tag } from 'antdv-next';

import { TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteBusinessCondition,
  getBusinessConditionPage,
  updateBusinessConditionStatus,
} from '#/api/modeling/business-condition';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { useObjectRequest } from '#/hooks/use-object-request';
import domainIcon from '#/views/modeling/logictable/assets/data-section-default-icon-small.svg';

import MetricCategoryTree from '../indicator-model/modules/metric-category-tree.vue';
import { METRIC_ACTION_ICON as ACTION_ICON } from '../indicator-model/modules/metric-icons';
import MetricLayout from '../indicator-model/modules/metric-layout.vue';
import { useMetricOptions } from '../indicator-model/modules/use-metric-options';
import conditionIcon from '../indicator-model/reference-assets/business-condition.svg';
import { CONFIG_LABELS, STATUS_LABELS } from './data';

import '../indicator-model/metric-list.css';

defineOptions({ name: 'ModelingBusinessCondition' });
const router = useRouter();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const scope = computed(() =>
  JSON.stringify([user.userInfo?.userId, user.userRoles, access.accessCodes]),
);
const canQuery = computed(() =>
  hasAccessByCodes(['modeling:business-condition:query']),
);
const canSource = computed(() => hasAccessByCodes(['modeling:model:query']));
const {
  domains,
  subjects,
  models,
  loading: optionsLoading,
  error: optionsError,
  reload: reloadOptions,
  domainLabel,
  subjectLabel,
  userLabel,
  userPhone,
} = useMetricOptions();
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
const configType = ref<BusinessConditionApi.ConfigType>();
const freshness = ref<string>();
const status = ref<BusinessConditionApi.Status>();
const navFilter = ref<{
  dataDomainId?: BusinessConditionApi.Id;
  sourceModelId?: BusinessConditionApi.Id;
  subjectDomainId?: BusinessConditionApi.Id;
}>({});
const pageNo = ref(1);
const pageSize = ref(10);
const isMobile = useMediaQuery('(max-width: 767px)');
const selected = ref<BusinessConditionApi.Model[]>([]);
const loadError = ref(false);
const busy = ref(false);
const busyId = ref('');
let active = true;
const requests = useObjectRequest(
  () => scope.value,
  () => {
    selected.value = [];
    loadError.value = false;
    gridApi.setGridOptions({
      data: [],
      loading: false,
      pagerConfig: { total: 0 },
    });
  },
);
const actions = useObjectRequest(
  () => scope.value,
  () => {
    busy.value = false;
    busyId.value = '';
  },
);
function createColumns(): VxeTableGridOptions<BusinessConditionApi.Model>['columns'] {
  return [
    { type: 'checkbox', width: 48 },
    { type: 'seq', title: '编号', width: 64 },
    {
      field: 'name',
      title: '业务限定信息',
      align: 'left',
      minWidth: 277,
      slots: { default: 'info' },
      showOverflow: false,
    },
    {
      field: 'sourceModelName',
      title: '归属信息',
      align: 'left',
      minWidth: 400,
      slots: { default: 'belonging' },
      showOverflow: false,
    },
    {
      field: 'configType',
      title: '配置方式',
      width: 94,
      slots: { default: 'config' },
    },
    {
      field: 'dataFreshness',
      title: '数据时效',
      width: 91,
      slots: { default: 'freshness' },
    },
    {
      field: 'expression',
      title: '限定定义',
      align: 'left',
      minWidth: 229,
      showOverflow: 'tooltip',
    },
    {
      field: 'status',
      title: '状态',
      width: 60,
      slots: { default: 'status' },
    },
    {
      field: 'creator',
      title: '创建人',
      align: 'left',
      width: 124,
      slots: { default: 'creator' },
      showOverflow: false,
    },
    {
      field: 'createTime',
      title: '创建时间',
      width: 162,
      formatter: ({ cellValue }: { cellValue?: number }) =>
        cellValue ? formatDateTime(cellValue) : '-',
    },
    {
      title: '操作',
      width: 214,
      fixed: isMobile.value ? undefined : 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
const [Grid, gridApi] = useVbenVxeGrid<BusinessConditionApi.Model>({
  formOptions: {
    submitOnEnter: true,
    schema: [
      {
        fieldName: 'keyword',
        label: '业务限定名称',
        component: 'Input',
        componentProps: {
          allowClear: true,
          placeholder: '请输入业务限定名称',
        },
      },
      {
        fieldName: 'configType',
        label: '配置方式',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: Object.entries(CONFIG_LABELS).map(([value, label]) => ({
            value,
            label,
          })),
          placeholder: '请选择配置方式',
        },
      },
      {
        fieldName: 'dataFreshness',
        label: '数据时效',
        component: 'Select',
        componentProps: () => ({
          allowClear: true,
          options: freshnessOptions.value,
          placeholder: '请选择数据时效',
        }),
      },
      {
        fieldName: 'status',
        label: '状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: Object.entries(STATUS_LABELS).map(([value, label]) => ({
            value,
            label,
          })),
          placeholder: '请选择状态',
        },
      },
    ],
    handleSubmit: async (values) => {
      keyword.value = values.keyword ?? '';
      configType.value = values.configType;
      freshness.value = values.dataFreshness;
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
    checkboxConfig: {
      checkMethod: ({ row }: { row: BusinessConditionApi.Model }) =>
        row.status === 'DRAFT',
    },
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
    columns: createColumns(),
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
    checkboxChange: ({
      records,
    }: {
      records: BusinessConditionApi.Model[];
    }) => {
      selected.value = records;
    },
    checkboxAll: ({ records }: { records: BusinessConditionApi.Model[] }) => {
      selected.value = records;
    },
  },
});

watch(isMobile, () => {
  gridApi.setGridOptions({ columns: createColumns() });
});

async function load(reset = false) {
  if (reset) pageNo.value = 1;
  const current = requests.begin();
  selected.value = [];
  loadError.value = false;
  gridApi.grid?.clearCheckboxRow?.();
  gridApi.setGridOptions({
    data: [],
    loading: canQuery.value && active,
    pagerConfig: {
      currentPage: pageNo.value,
      pageSize: pageSize.value,
      total: 0,
    },
  });
  if (!canQuery.value || !active) return;
  try {
    const result = await getBusinessConditionPage({
      ...navFilter.value,
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      keyword: keyword.value.trim() || undefined,
      configType: configType.value,
      dataFreshness: freshness.value?.trim() || undefined,
      status: status.value,
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
  configType.value = undefined;
  freshness.value = undefined;
  status.value = undefined;
  navFilter.value = {};
  void load(true);
}
function edit(row?: BusinessConditionApi.Model, clone = false) {
  void router.push({
    name: 'ModelingBusinessConditionForm',
    query: row ? { [clone ? 'cloneId' : 'id']: String(row.id) } : {},
  });
}
function detail(row: BusinessConditionApi.Model) {
  void router.push({
    name: 'ModelingBusinessConditionDetail',
    params: { id: String(row.id) },
  });
}
async function changeStatus(row: BusinessConditionApi.Model) {
  if (busy.value || !hasAccessByCodes(['modeling:business-condition:update']))
    return;
  const current = actions.begin();
  busy.value = true;
  busyId.value = String(row.id);
  const next = row.status === 'ACTIVE' ? 'DRAFT' : 'ACTIVE';
  try {
    await confirm(
      `确认${next === 'ACTIVE' ? '启用' : '禁用'}业务限定“${row.name}”吗？`,
    );
    if (!current() || !active) return;
    await updateBusinessConditionStatus({
      id: row.id,
      expectedRevision: row.revision,
      status: next,
    });
    if (current()) {
      message.success(next === 'ACTIVE' ? '业务限定已启用' : '业务限定已禁用');
      await load();
    }
  } catch {
    /* 取消或失败时保留原状态，接口错误由请求层提示。 */
  } finally {
    if (current()) {
      busy.value = false;
      busyId.value = '';
    }
  }
}
async function remove(rows: BusinessConditionApi.Model[]) {
  if (
    busy.value ||
    rows.length === 0 ||
    !hasAccessByCodes(['modeling:business-condition:delete'])
  )
    return;
  if (rows.some((row) => row.status !== 'DRAFT')) {
    message.warning('请先禁用业务限定后再删除');
    return;
  }
  const current = actions.begin();
  busy.value = true;
  try {
    await confirm(`确认删除选中的 ${rows.length} 条业务限定吗？`);
    if (!current() || !active) return;
    let succeeded = 0;
    for (const row of rows) {
      if (!current() || !active) return;
      try {
        await deleteBusinessCondition(row.id, row.revision);
        succeeded++;
      } catch {
        /* 继续处理其余对象，结束后汇报部分失败。 */
      }
    }
    if (!current()) return;
    if (succeeded === rows.length) message.success('删除成功');
    else
      message.warning(
        `已删除 ${succeeded} 条，${rows.length - succeeded} 条未删除，列表已刷新，请检查后重试。`,
      );
    await load(true);
  } catch {
    /* 取消确认时保持当前列表。 */
  } finally {
    if (current()) busy.value = false;
  }
}
watch(
  () => `${access.accessToken}/${access.refreshToken}/${scope.value}`,
  () => {
    void load(true);
  },
  { immediate: true },
);
onDeactivated(() => {
  active = false;
  requests.begin();
  actions.begin();
  selected.value = [];
  busy.value = false;
  busyId.value = '';
  gridApi.setGridOptions({ loading: false });
});
onActivated(() => {
  if (!active) {
    active = true;
    void load();
  }
});
</script>

<template>
  <Page
    auto-content-height
    class="qd-metric-page"
    content-class="qd-metric-content"
  >
    <MetricLayout @resize="active && gridApi.grid.recalculate?.(true)">
      <template #tree>
        <MetricCategoryTree
          v-model:value="navFilter"
          kind="condition"
          :domains="domains"
          :subjects="subjects"
          :models="models"
          :loading="optionsLoading"
          @refresh="reloadOptions"
          @select="load(true)"
        /><Button v-if="optionsError" size="small" @click="reloadOptions">
          <template #icon>
            <IconifyIcon icon="lucide:refresh-cw" />
          </template>
          重试加载类目
        </Button>
      </template>
      <Alert v-if="!canQuery" type="warning" message="暂无业务限定查询权限" />
      <Alert
        v-if="loadError"
        type="error"
        message="业务限定列表加载失败，请刷新重试。"
      />
      <Grid class="condition-list-grid min-h-0 flex-1" table-title="业务限定">
        <template #toolbar-tools>
          <TableAction
            :actions="[
              {
                label: '新增',
                type: 'primary',
                icon: ACTION_ICON.ADD,
                auth: ['modeling:business-condition:create'],
                disabled: !canQuery || !canSource,
                onClick: () => edit(),
              },
              {
                label: '批量删除',
                type: 'default',
                icon: ACTION_ICON.DELETE,
                danger: true,
                auth: ['modeling:business-condition:delete'],
                disabled: !selected.length || busy,
                onClick: () => remove([...selected]),
              },
            ]"
          />
        </template>
        <template #info="{ row }">
          <div class="condition-info condition-info--primary">
            <button
              type="button"
              class="condition-link"
              :title="`${row.name} (${row.code})`"
              @click="detail(row)"
            >
              <img :src="conditionIcon" class="qd-metric-symbol" alt="" />{{
                row.name
              }}
              ({{ row.code }})</button
            ><TextEllipsisCell
              class="condition-muted"
              :content="row.description"
              :line="2"
            />
          </div>
        </template>
        <template #belonging="{ row }">
          <div class="condition-info">
            <span
              class="condition-ellipsis"
              :title="`${domainLabel(row.dataDomainId)} / ${subjectLabel(row.subjectDomainId)}`"
              ><img :src="domainIcon" alt="" class="qd-metric-symbol" />{{
                domainLabel(row.dataDomainId)
              }}
              / {{ subjectLabel(row.subjectDomainId) }}</span
            ><span class="condition-ellipsis" :title="row.sourceModelName">{{
              row.sourceModelCode || row.sourceModelName || '-'
            }}</span>
          </div>
        </template>
        <template #config="{ row }">
          <Tag color="blue">
            {{
              CONFIG_LABELS[
                row.configType as BusinessConditionApi.ConfigType
              ] || row.configType
            }}
          </Tag>
        </template>
        <template #freshness="{ row }">
          <Tag v-if="row.dataFreshness" color="success">
            {{ freshnessLabel(row.dataFreshness) }} </Tag
          ><span v-else>-</span>
        </template>
        <template #status="{ row }">
          <Switch
            size="small"
            :checked="row.status === 'ACTIVE'"
            :loading="busyId === String(row.id)"
            :disabled="
              busy || !hasAccessByCodes(['modeling:business-condition:update'])
            "
            :aria-label="`${row.status === 'ACTIVE' ? '禁用' : '启用'}${row.name}`"
            @change="changeStatus(row)"
          />
        </template>
        <template #creator="{ row }">
          <div class="condition-info">
            <span>{{ row.creatorName || userLabel(row.creator) }}</span
            ><span>{{
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
                auth: ['modeling:business-condition:query'],
                onClick: () => detail(row),
              },
              {
                label: '修改',
                type: 'link',
                icon: ACTION_ICON.EDIT,
                auth: ['modeling:business-condition:update'],
                disabled: row.status !== 'DRAFT' || busy || !canSource,
                onClick: () => edit(row),
              },
            ]"
            :drop-down-actions="[
              {
                label: '克隆',
                icon: 'qdata:documentcopy',
                auth: ['modeling:business-condition:create'],
                disabled: !canQuery || !canSource,
                onClick: () => edit(row, true),
              },
              {
                label: '删除',
                icon: ACTION_ICON.DELETE,
                danger: true,
                auth: ['modeling:business-condition:delete'],
                disabled: row.status !== 'DRAFT' || busy,
                onClick: () => remove([row]),
              },
            ]"
          />
        </template>
      </Grid>
    </MetricLayout>
  </Page>
</template>

<style scoped>
.condition-list-grid :deep(.vxe-body--column) {
  font-size: 12px;
}

.condition-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  min-width: 0;
  padding: 6px 0;
  line-height: 1.4;
}

.condition-link {
  display: flex;
  gap: 5px;
  align-items: center;
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

.condition-info--primary {
  padding-inline-end: 20px;
}

.condition-link:focus-visible {
  outline: 2px solid hsl(var(--primary));
  outline-offset: 2px;
}

.condition-link :deep(svg) {
  flex-shrink: 0;
}

.condition-muted {
  font-size: 12px;
  line-height: 18px;
  color: hsl(var(--muted-foreground));
  overflow-wrap: anywhere;
  white-space: normal;
}

.condition-ellipsis {
  overflow-wrap: anywhere;
  white-space: normal;
}

.condition-domain {
  display: inline;
  margin-right: 5px;
  vertical-align: -2px;
  color: hsl(var(--primary));
}
</style>
