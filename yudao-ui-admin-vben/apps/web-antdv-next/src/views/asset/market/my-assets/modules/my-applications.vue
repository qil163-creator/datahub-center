<script setup lang="ts">
import type { MyAssetsApi } from '#/api/asset/my-assets';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

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

import { useVbenDrawer } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Alert,
  Button,
  Empty,
  Input,
  Select,
  Spin,
  Tag,
  Timeline,
  TimelineItem,
} from 'antdv-next';

import { getMyApplication, getMyApplicationsPage } from '#/api/asset/my-assets';
import ListPagination from '#/components/list-pagination/index.vue';
import TableViewport from '#/components/table-viewport/index.vue';
import {
  createLatestWins,
  parseResourceId,
  sameResourceId,
} from '#/utils/resource-id';
import { PROCESS_ACTION_OPTIONS } from '#/views/market/data';
import {
  APPLY_STATUS_OPTIONS,
  optionColor,
  optionLabel,
} from '#/views/market/market-labels';
import { formatMarketTime } from '#/views/market/market-time';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

const access = useAccessStore();
const user = useUserStore();
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
const canQuery = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    access.accessCodes.includes('asset:market:my-assets:query'),
);
const listGuard = createLatestWins();
const detailGuard = createLatestWins();
const query = reactive<MyAssetsApi.ApplicationPageReq>({
  pageNo: 1,
  pageSize: 10,
});
const filters = reactive<{ keyword: string; status?: number }>({ keyword: '' });
const rows = ref<MyAssetsApi.Application[]>([]);
const total = ref(0);
const loading = ref(false);
const listError = ref('');
const detail = ref<MyAssetsApi.ApplicationDetail>();
const detailId = ref<string>();
const detailLoading = ref(false);
const detailError = ref('');
let alive = true;
let active = false;

const columns = [
  { title: '申请单号', key: 'applyNo', dataIndex: 'applyNo', width: 210 },
  { title: '申请标题', key: 'title', dataIndex: 'title', width: 220 },
  { title: '资产名称', key: 'assetName', dataIndex: 'assetName', width: 220 },
  { title: '申请时间', key: 'createTime', width: 180 },
  { title: '申请状态', key: 'status', width: 120 },
  {
    title: '申请用途',
    key: 'purpose',
    dataIndex: 'purpose',
    width: 240,
    ellipsis: true,
  },
  {
    title: '审批意见',
    key: 'checkNote',
    dataIndex: 'checkNote',
    width: 240,
    ellipsis: true,
  },
  { title: '操作', key: 'actions', fixed: 'right' as const, width: 100 },
];
const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  if (!row) return [];
  return [
    { key: 'applyNo', label: '申请单号', value: row.applyNo },
    { key: 'status', label: '申请状态', value: statusLabel(row.status) },
    { key: 'assetName', label: '资产名称', value: row.assetName },
    {
      key: 'createTime',
      label: '申请时间',
      value: formatMarketTime(row.createTime),
    },
    {
      key: 'purpose',
      label: '申请用途',
      value: row.purpose,
      span: 2,
      type: 'textarea',
    },
    { key: 'dutyMatter', label: '履职事项', value: row.dutyMatter, span: 2 },
    { key: 'legalBasis', label: '使用依据', value: row.legalBasis, span: 2 },
    { key: 'fieldScope', label: '字段范围', value: row.fieldScope, span: 2 },
    {
      key: 'validity',
      label: '申请有效期',
      value: `${formatMarketTime(row.applyStartTime)} 至 ${row.applyEndTime === null || row.applyEndTime === undefined ? '长期有效' : formatMarketTime(row.applyEndTime)}`,
      span: 2,
    },
    {
      key: 'supplementNote',
      label: '补正要求',
      value: row.supplementNote,
      span: 2,
      type: 'textarea',
    },
    {
      key: 'checkNote',
      label: '审批意见',
      value: row.checkNote,
      span: 2,
      type: 'textarea',
    },
  ];
});
function statusLabel(status?: number) {
  return optionLabel(APPLY_STATUS_OPTIONS, status) === '-' &&
    status !== null &&
    status !== undefined
    ? `未知状态（${status}）`
    : optionLabel(APPLY_STATUS_OPTIONS, status);
}
const [Drawer, drawerApi] = useVbenDrawer({
  title: '申请详情',
  class: 'w-[960px] max-w-full',
  placement: 'right',
  showConfirmButton: false,
  cancelText: '关闭',
  onOpenChange(open) {
    if (!open) clearDetail();
  },
});
function clearDetail() {
  detailGuard.invalidate();
  detailId.value = undefined;
  detail.value = undefined;
  detailError.value = '';
  detailLoading.value = false;
}
async function loadDetail() {
  const id = detailId.value;
  if (!alive || !active || !canQuery.value || !id) return;
  const ticket = detailGuard.next();
  detail.value = undefined;
  detailError.value = '';
  detailLoading.value = true;
  try {
    const result = await getMyApplication(id);
    if (!ticket.isCurrent()) return;
    if (!sameResourceId(result.id, id)) throw new Error('IDENTITY_MISMATCH');
    detail.value = result;
  } catch {
    if (ticket.isCurrent()) detailError.value = '申请详情读取失败，请重试。';
  } finally {
    if (ticket.isCurrent()) detailLoading.value = false;
  }
}
function openDetail(row: MyAssetsApi.Application) {
  const id = parseResourceId(row.id);
  if (
    !alive ||
    !active ||
    !canQuery.value ||
    !id ||
    !rows.value.some((item) => sameResourceId(item.id, id))
  )
    return;
  clearDetail();
  detailId.value = id;
  drawerApi.open();
  void loadDetail();
}
async function load() {
  if (!alive || !active || !canQuery.value) return;
  const ticket = listGuard.next();
  loading.value = true;
  listError.value = '';
  rows.value = [];
  total.value = 0;
  try {
    const result = await getMyApplicationsPage({ ...query });
    if (ticket.isCurrent()) {
      rows.value = result.list ?? [];
      total.value = result.total ?? 0;
    }
  } catch {
    if (ticket.isCurrent()) listError.value = '申请记录读取失败，请重试。';
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
function search() {
  query.pageNo = 1;
  query.keyword = filters.keyword.trim() || undefined;
  query.status = filters.status;
  void load();
}
function reset() {
  filters.keyword = '';
  filters.status = undefined;
  search();
}
function changePage(page: number, size: number) {
  query.pageNo = size === query.pageSize ? page : 1;
  query.pageSize = size;
  void load();
}
function clear() {
  listGuard.invalidate();
  clearDetail();
  drawerApi.setState({ isOpen: false });
  rows.value = [];
  total.value = 0;
  loading.value = false;
  listError.value = '';
}
watch(
  identity,
  () => {
    clear();
    reset();
  },
  { flush: 'sync' },
);
function activate() {
  if (!active) {
    active = true;
    void load();
  }
}
onMounted(activate);
onActivated(activate);
onDeactivated(() => {
  active = false;
  clear();
});
onBeforeUnmount(() => {
  active = false;
  alive = false;
  clear();
});
</script>

<template>
  <Drawer>
    <Spin v-if="detailLoading" spinning />
    <Alert v-if="detailError" type="error" :message="detailError" show-icon>
      <template #action
        ><Button size="small" @click="loadDetail">重试</Button></template
      >
    </Alert>
    <template v-if="detail">
      <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
      <div class="mx-4 mt-4 text-base font-medium">审批轨迹</div>
      <Timeline v-if="detail.processes?.length" class="mx-4 mt-3">
        <TimelineItem v-for="item in detail.processes" :key="String(item.id)">
          <Tag>{{ optionLabel(PROCESS_ACTION_OPTIONS, item.action) }}</Tag>
          {{ item.operatorName || '-' }} ·
          {{ formatMarketTime(item.createTime) }}
          <div v-if="item.note" class="text-muted-foreground mt-1 text-xs">
            {{ item.note }}
          </div>
        </TimelineItem>
      </Timeline>
      <Empty v-else class="mx-4" description="暂无审批轨迹" />
    </template>
  </Drawer>
  <div class="application-filters">
    <label
      >申请标题<Input
        v-model:value="filters.keyword"
        allow-clear
        :maxlength="100"
        placeholder="请输入申请标题"
        @press-enter="search"
    /></label>
    <label
      >申请状态<Select
        v-model:value="filters.status"
        allow-clear
        :options="APPLY_STATUS_OPTIONS"
        placeholder="请选择申请状态"
    /></label>
    <div class="application-filters__actions">
      <Button @click="reset">重置</Button
      ><Button type="primary" :loading="loading" @click="search">查询</Button>
    </div>
  </div>
  <section class="applications-results">
    <header class="applications-results__header">
      <h2>申请记录</h2>
      <Button :loading="loading" @click="load"
        ><template #icon><IconifyIcon icon="lucide:refresh-cw" /></template
        >刷新</Button
      >
    </header>
    <Alert
      v-if="listError"
      class="mb-3"
      type="error"
      :message="listError"
      show-icon
      ><template #action
        ><Button size="small" @click="load">重试</Button></template
      ></Alert
    >
    <TableViewport
      :columns="columns"
      :data-source="rows"
      :row-key="(row: MyAssetsApi.Application) => String(row.id)"
      :loading="loading"
      :pagination="false"
      :scroll="{ x: 1310 }"
      size="middle"
    >
      <template #bodyCell="{ column, record }">
        <span v-if="column.key === 'createTime'">{{
          formatMarketTime(record.createTime)
        }}</span>
        <Tag
          v-else-if="column.key === 'status'"
          :color="optionColor(APPLY_STATUS_OPTIONS, record.status)"
          >{{ statusLabel(record.status) }}</Tag
        >
        <Button
          v-else-if="column.key === 'actions'"
          type="link"
          size="small"
          @click="openDetail(record)"
          ><template #icon><IconifyIcon icon="lucide:eye" /></template
          >详情</Button
        >
        <span v-else>{{ record[column.dataIndex] || '-' }}</span>
      </template>
      <template #emptyText
        ><Empty
          v-if="!loading && !listError"
          description="暂无符合条件的申请记录"
        /><span v-else>{{
          listError ? '读取失败，请重试' : '正在读取申请记录'
        }}</span></template
      >
    </TableViewport>
    <ListPagination
      :current="query.pageNo"
      :page-size="query.pageSize"
      :total="total"
      @change="changePage"
    />
  </section>
</template>

<style scoped>
.application-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 16px 24px;
  align-items: center;
  padding: 20px;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.application-filters label {
  display: flex;
  flex: 1 1 260px;
  gap: 12px;
  align-items: center;
  white-space: nowrap;
}

.application-filters label > * {
  flex: 1;
  min-width: 0;
}

.application-filters__actions {
  display: flex;
  gap: 8px;
}

.applications-results {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  padding: 20px;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.applications-results__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.applications-results__header h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
</style>

<style scoped src="../../../table-font.css"></style>
