<script setup lang="ts">
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Button,
  Dropdown,
  Menu,
  message,
  Modal,
  Switch,
  Tag,
} from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { getDwLayerList } from '#/api/modeling/dwlayer';
import {
  deleteTableModel,
  getTableModelPage,
  updateTableModelEnabled,
} from '#/api/modeling/tablemodel';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { useObjectRequest } from '#/hooks/use-object-request';

import {
  formatBelongingHierarchy,
  getUserMobile,
  getUserNickname,
  optionLabel,
  TABLE_TYPE_OPTIONS,
} from '../modelcommon/data';
import ModelListLayout from '../modelcommon/model-list-layout.vue';
import ModelForm from './modules/form.vue';

defineOptions({ name: 'ModelingTableModel' });
const router = useRouter();
const route = useRoute();
const pagePath = route.path;
const access = useAccessStore();
const selectedLayerId = ref<number | string>();
const navFilter = ref<{
  bizCategoryId?: number | string;
  dataDomainId?: number | string;
  subjectDomainId?: number | string;
}>({});
const form = ref<InstanceType<typeof ModelForm>>();
const layerOptions = ref<{ label: string; value: number | string }[]>([]);
const layerRequest = useObjectRequest(
  () => '',
  () => {
    layerOptions.value = [];
  },
);
async function loadLayerOptions() {
  const current = layerRequest.begin();
  try {
    const list = await getDwLayerList({});
    if (!current()) return;
    layerOptions.value = (list || [])
      .filter((item) => item.nodeType === 'LAYER')
      .map((item) => ({
        label: item.layerCode
          ? `${item.layerCode} ${item.layerName || ''}`
          : item.layerName || String(item.id),
        value: String(item.id),
      }));
  } catch {
    if (current()) layerOptions.value = [];
  }
}
watch(() => `${access.accessToken}/${access.refreshToken}`, loadLayerOptions, {
  immediate: true,
});

/** 路由携带的 Long ID 始终保留十进制字符串，避免大数精度丢失。 */
function routeLayerId(value: unknown) {
  return typeof value === 'string' && /^[1-9]\d*$/.test(value)
    ? value
    : undefined;
}
let queryController: AbortController | undefined;
const [Grid, grid] = useVbenVxeGrid<TableModelApi.Model>({
  formOptions: {
    schema: [
      {
        fieldName: 'name',
        label: '中文名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入中文名称' },
      },
      {
        fieldName: 'code',
        label: '英文名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入英文名称' },
      },
      {
        fieldName: 'layerId',
        label: '数仓分层',
        defaultValue: routeLayerId(route.query.layerId),
        component: 'Select',
        componentProps: () => ({
          allowClear: true,
          options: layerOptions.value,
          placeholder: '请选择数仓分层',
          showSearch: true,
          optionFilterProp: 'label',
        }),
      },
    ],
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    columns: [
      {
        type: 'seq',
        title: '编号',
        width: 64,
      },
      {
        field: 'name',
        title: '模型信息',
        minWidth: 280,
        align: 'left',
        showOverflow: false,
        slots: { default: 'name' },
      },
      {
        field: 'belongingHierarchy',
        title: '归属层级',
        minWidth: 260,
        slots: { default: 'hierarchy' },
      },
      {
        field: 'tableType',
        title: '表类型',
        width: 110,
        slots: { default: 'tableType' },
      },
      {
        field: 'enabled',
        title: '状态',
        width: 90,
        slots: { default: 'enabled' },
      },
      {
        field: 'creator',
        title: '创建人',
        width: 140,
        align: 'left',
        showOverflow: false,
        slots: { default: 'creator' },
      },
      {
        field: 'createTime',
        title: '创建时间',
        width: 170,
        formatter: ({ cellValue }: { cellValue?: number | string }) =>
          formatDateTime(cellValue) || '-',
      },
      {
        field: 'action',
        title: '操作',
        width: 210,
        fixed: 'right',
        showOverflow: false,
        slots: { default: 'action' },
      },
    ],
    height: 'auto',
    rowConfig: { keyField: 'id' },
    toolbarConfig: { refresh: true, search: true },
    proxyConfig: {
      ajax: {
        query: (
          { page }: { page: { currentPage: number; pageSize: number } },
          values: Pick<TableModelApi.PageReq, 'code' | 'layerId' | 'name'>,
        ) => {
          queryController?.abort();
          queryController = new AbortController();
          return getTableModelPage(
            {
              ...values,
              pageNo: page.currentPage,
              pageSize: page.pageSize,
              bizCategoryId: navFilter.value.bizCategoryId,
              dataDomainId: navFilter.value.dataDomainId,
              subjectDomainId: navFilter.value.subjectDomainId,
            },
            queryController.signal,
          );
        },
      },
    },
  },
});
let routeRequest = 0;
watch(
  () => [route.path, route.query.layerId],
  async () => {
    const sequence = ++routeRequest;
    if (route.path !== pagePath) return;
    queryController?.abort();
    await nextTick();
    if (sequence !== routeRequest || route.path !== pagePath) return;
    selectedLayerId.value = undefined;
    navFilter.value = {};
    await grid.formApi.setValues({
      layerId: routeLayerId(route.query.layerId),
      name: undefined,
      code: undefined,
    });
    if (sequence === routeRequest && route.path === pagePath)
      await grid.query();
  },
);
function openDetail(id?: number | string) {
  if (id === undefined) return;
  router.push({ name: 'ModelingTableModelDetail', params: { id: String(id) } });
}
function modelInfoTitle(row: TableModelApi.Model) {
  const name = row.name?.trim() || '';
  const code = row.code?.trim() || '';
  if (name && code) return `${name} (${code})`;
  return name || code || '-';
}
function modelDescription(row: TableModelApi.Model) {
  return row.description?.trim() || '';
}
const CREATE_TABLE_OPTIONS = [
  {
    key: 'DETAIL',
    title: '明细表',
    icon: 'lucide:list',
    desc: '记录尚未深度加工的流水明细，例如单次水位上报、设备告警日志。',
  },
  {
    key: 'SUMMARY',
    title: '汇总表',
    icon: 'lucide:grid-2x2',
    desc: '按条件汇总计算后的结果，例如测站日降雨量、月均水位。',
  },
  {
    key: 'DIMENSION',
    title: '维度表',
    icon: 'lucide:git-branch',
    desc: '存放相对稳定的基础档案，例如测站属性表、水库名录。',
  },
  {
    key: 'APPLICATION',
    title: '应用表',
    icon: 'lucide:sliders-horizontal',
    desc: '直接服务大屏或报表的数据，例如防汛综合指标。',
  },
] as const;

function create(tableType: string) {
  const fromSubject =
    navFilter.value.subjectDomainId !== null &&
    navFilter.value.subjectDomainId !== undefined;
  form.value?.open('FORWARD', undefined, {
    tableType: fromSubject ? 'APPLICATION' : tableType,
    bizCategoryId: fromSubject ? undefined : navFilter.value.bizCategoryId,
    dataDomainId: fromSubject ? undefined : navFilter.value.dataDomainId,
    subjectDomainId: navFilter.value.subjectDomainId,
  });
}
function edit(row: TableModelApi.Model) {
  form.value?.open('FORWARD', row);
}
function confirmEnabled(row: TableModelApi.Model, enabled: boolean) {
  const text = enabled ? '启用' : '禁用';
  Modal.confirm({
    title: `确认要${text}「${row.name || ''}」逻辑模型吗？`,
    onOk: async () => {
      await updateTableModelEnabled(row.id, enabled ? 1 : 0, row.revision);
      message.success(`已${text}`);
      await grid.query();
    },
  });
}
async function remove(row: TableModelApi.Model) {
  await deleteTableModel(row.id!, row.revision);
  message.success('模型已删除');
  await grid.query();
}
watch(
  () => `${access.accessToken}/${access.refreshToken}`,
  () => {
    queryController?.abort();
    grid.grid?.loadData([]);
    selectedLayerId.value = undefined;
    navFilter.value = {};
    grid.query();
  },
);
onBeforeUnmount(() => {
  routeRequest += 1;
  queryController?.abort();
});
</script>

<template>
  <Page auto-content-height>
    <ModelListLayout
      v-model:selected-layer="selectedLayerId"
      v-model:nav-filter="navFilter"
      @select="grid.query()"
    >
      <Grid class="model-list-grid" table-title="模型管理">
        <template #toolbar-tools>
          <div class="flex gap-2">
            <Dropdown
              v-access:code="'modeling:model:save'"
              :trigger="['click']"
            >
              <Button type="primary">+ 新增</Button>
              <template #popupRender>
                <Menu class="create-table-menu min-w-[280px]">
                  <Menu.Item
                    v-for="opt in CREATE_TABLE_OPTIONS"
                    :key="opt.key"
                    @click="create(opt.key)"
                  >
                    <div class="flex items-start gap-3 py-1">
                      <div
                        class="flex size-9 shrink-0 items-center justify-center rounded-lg border border-blue-200 bg-blue-50 text-blue-600"
                      >
                        <IconifyIcon :icon="opt.icon" class="size-4" />
                      </div>
                      <div class="min-w-0">
                        <div class="font-medium">{{ opt.title }}</div>
                        <div class="text-xs text-gray-500 whitespace-normal">
                          {{ opt.desc }}
                        </div>
                      </div>
                    </div>
                  </Menu.Item>
                </Menu>
              </template>
            </Dropdown>
            <Button
              @click="
                router.push({
                  name: 'ModelingTableModelEr',
                  query: selectedLayerId
                    ? { layerId: String(selectedLayerId) }
                    : {},
                })
              "
            >
              <template #icon><IconifyIcon icon="lucide:network" /></template>
              ER 关系图
            </Button>
          </div>
        </template>
        <template #name="{ row }">
          <div class="model-info">
            <button
              type="button"
              class="model-info__title"
              :title="modelInfoTitle(row)"
              @click="openDetail(row.id)"
            >
              {{ modelInfoTitle(row) }}
            </button>
            <TextEllipsisCell
              v-if="modelDescription(row)"
              class="model-info__remark"
              :content="modelDescription(row)"
              :line="2"
            />
          </div>
        </template>
        <template #tableType="{ row }">
          <Tag color="blue">
            {{ optionLabel(TABLE_TYPE_OPTIONS, row.tableType) }}
          </Tag>
        </template>
        <template #enabled="{ row }">
          <Switch
            :checked="row.enabled === 1"
            :disabled="row.revision == null"
            @change="(checked: boolean) => confirmEnabled(row, checked)"
          />
        </template>
        <template #hierarchy="{ row }">
          <span class="text-gray-700">
            {{ formatBelongingHierarchy(row) }}
          </span>
        </template>
        <template #creator="{ row }">
          <div class="model-info">
            <div
              class="whitespace-normal break-words"
              :title="getUserNickname(row.creator)"
            >
              {{ getUserNickname(row.creator) }}
            </div>
            <div
              v-if="getUserMobile(row.creator)"
              class="model-info__remark"
              :title="getUserMobile(row.creator)"
            >
              {{ getUserMobile(row.creator) }}
            </div>
          </div>
        </template>
        <template #action="{ row }">
          <TableAction
            :actions="[
              {
                label: '修改',
                type: 'link',
                icon: ACTION_ICON.EDIT,
                auth: ['modeling:model:save'],
                onClick: () => edit(row as TableModelApi.Model),
              },
              {
                label: '删除',
                type: 'link',
                danger: true,
                icon: ACTION_ICON.DELETE,
                auth: ['modeling:model:delete'],
                popConfirm: {
                  title: `确认删除模型 ${row.name}？`,
                  confirm: () => remove(row as TableModelApi.Model),
                },
              },
              {
                label: '详情',
                type: 'link',
                icon: ACTION_ICON.VIEW,
                auth: ['modeling:model:query'],
                onClick: () => openDetail(row.id),
              },
            ]"
          />
        </template>
      </Grid>
    </ModelListLayout>
    <ModelForm ref="form" @saved="grid.query()" />
  </Page>
</template>

<style scoped>
.model-list-grid :deep(.vxe-body--column) {
  font-size: 14px;
}

.model-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  min-width: 0;
  padding: 6px 0;
  line-height: 1.4;
}

.model-info__title {
  display: block;
  width: 100%;
  min-width: 0;
  padding: 0;
  font: inherit;
  line-height: 22px;
  color: #1677ff;
  text-align: left;
  overflow-wrap: anywhere;
  white-space: normal;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.model-info__title:hover {
  color: #4096ff;
}

.model-info__title:focus-visible {
  outline: 2px solid #1677ff;
  outline-offset: 2px;
}

.model-info__remark {
  width: 100%;
  min-width: 0;
  font-size: 12px;
  line-height: 18px;
  color: #595959;
  overflow-wrap: anywhere;
  white-space: normal;
}
</style>
