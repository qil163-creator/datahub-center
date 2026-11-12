<script setup lang="ts">
import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

import { useAccess } from '@vben/access';
import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Alert, Button, message, Switch, Tag } from 'antdv-next';

import { TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  deleteIndicator,
  getIndicatorPage,
  updateIndicatorStatus,
} from '#/api/modeling/indicator-model';
import { useObjectRequest } from '#/hooks/use-object-request';

import { options, STATUS_LABELS } from '../data';
import { useMetricOptions } from '../modules/use-metric-options';
import CompositeForm from './composite-form.vue';

defineOptions({ name: 'ModelingCompositeIndicator' });

const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const scope = computed(() =>
  JSON.stringify([user.userInfo?.userId, user.userRoles, access.accessCodes]),
);
const canQuery = computed(() =>
  hasAccessByCodes(['modeling:indicator-model:query']),
);
const keyword = ref('');
const status = ref<IndicatorModelApi.Status>();
const pageNo = ref(1);
const pageSize = ref(10);
const loadError = ref(false);
const busyIds = ref(new Set<string>());
let active = true;
let generation = 0;
const { domainLabel } = useMetricOptions();

const requests = useObjectRequest(
  () => scope.value,
  () => {
    generation++;
    busyIds.value = new Set();
    gridApi.setGridOptions({
      data: [],
      loading: false,
      pagerConfig: { total: 0 },
    });
  },
);
const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: CompositeForm,
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
      status.value = values.status;
      await load(true);
    },
    handleReset: async () => {
      await gridApi.formApi.reset();
      keyword.value = '';
      status.value = undefined;
      await load(true);
    },
  },
  gridOptions: {
    data: [],
    height: 'auto',
    minHeight: 0,
    proxyConfig: { enabled: false },
    rowConfig: { keyField: 'id', isHover: true },
    pagerConfig: {
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
      { type: 'seq', title: '编号', width: 64 },
      {
        field: 'name',
        title: '复合指标',
        minWidth: 200,
        slots: { default: 'indicator' },
      },
      {
        field: 'formula',
        title: '计算公式',
        minWidth: 260,
        slots: { default: 'formula' },
      },
      {
        field: 'dataDomainId',
        title: '数据域',
        minWidth: 120,
        formatter: ({ cellValue }: { cellValue: any }) =>
          domainLabel(cellValue) || '-',
      },
      {
        field: 'unit',
        title: '单位',
        width: 80,
        formatter: ({ cellValue }: { cellValue: any }) => cellValue || '-',
      },
      {
        field: 'status',
        title: '状态',
        width: 90,
        slots: { default: 'status' },
      },
      {
        field: 'compositeReferenceCount',
        title: '被引用',
        width: 90,
        formatter: ({ cellValue }: { cellValue: any }) =>
          cellValue ? `${cellValue} 个` : '-',
      },
      {
        field: 'updateTime',
        title: '更新时间',
        width: 170,
        formatter: ({ cellValue }: { cellValue: any }) =>
          formatDateTime(cellValue) || '-',
      },
      {
        title: '操作',
        width: 160,
        fixed: 'right',
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
  },
});

async function load(reset = false) {
  if (reset) pageNo.value = 1;
  const current = requests.begin();
  loadError.value = false;
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
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      indicatorType: 'COMPOSITE',
      keyword: keyword.value.trim() || undefined,
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

function edit(row?: IndicatorModelApi.Model) {
  formModalApi.setData({ id: row?.id }).open();
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
      `确认要${next === 'ACTIVE' ? '启用' : '禁用'}复合指标“${row.name}”吗？`,
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

async function remove(row: IndicatorModelApi.Model) {
  if (busyIds.value.has(String(row.id))) return;
  const version = generation;
  pending(row.id, true);
  try {
    await confirm(`是否确认删除复合指标“${row.name}”？`);
    if (version !== generation || !active) return;
    await deleteIndicator(row.id, row.revision);
    if (version === generation && active) {
      message.success('删除成功');
      await load();
    }
  } catch {
    /* 取消确认或接口错误时保持列表。 */
  } finally {
    if (version === generation && active) pending(row.id, false);
  }
}

onMounted(() => void load(true));
onBeforeUnmount(() => {
  active = false;
  generation++;
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="load()" />
    <Alert
      v-if="!canQuery"
      class="mb-3"
      type="warning"
      show-icon
      message="没有指标查询权限，无法查看复合指标。"
    />
    <Alert
      v-else-if="loadError"
      class="mb-3"
      type="error"
      show-icon
      message="复合指标加载失败。"
    >
      <template #action>
        <Button size="small" @click="load()">重试</Button>
      </template>
    </Alert>
    <Grid table-title="复合指标">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: '新增复合指标',
              type: 'primary',
              icon: 'lucide:plus',
              auth: ['modeling:indicator-model:create'],
              onClick: () => edit(),
            },
          ]"
        />
      </template>
      <template #indicator="{ row }">
        <div class="leading-5">
          <div class="font-medium">{{ row.name }}</div>
          <div class="text-xs text-gray-500">{{ row.code }}</div>
        </div>
      </template>
      <template #formula="{ row }">
        <code class="break-all text-xs">{{ row.formula || '-' }}</code>
      </template>
      <template #status="{ row }">
        <Switch
          v-if="hasAccessByCodes(['modeling:indicator-model:update'])"
          :checked="row.status === 'ACTIVE'"
          :loading="busyIds.has(String(row.id))"
          size="small"
          @change="changeStatus(row)"
        />
        <Tag v-else :color="row.status === 'ACTIVE' ? 'green' : 'default'">
          {{ STATUS_LABELS[row.status as IndicatorModelApi.Status] }}
        </Tag>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '编辑',
              type: 'link',
              auth: ['modeling:indicator-model:update'],
              disabled: row.status !== 'DRAFT' || busyIds.has(String(row.id)),
              tooltip: row.status !== 'DRAFT' ? '请先禁用后编辑' : undefined,
              onClick: () => edit(row),
            },
            {
              label: '删除',
              type: 'link',
              danger: true,
              auth: ['modeling:indicator-model:delete'],
              disabled:
                row.status !== 'DRAFT' ||
                !!row.compositeReferenceCount ||
                busyIds.has(String(row.id)),
              onClick: () => remove(row),
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
