<script setup lang="ts">
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type {
  DatabaseOption,
  Id,
  ResponsibleUser,
  Wizard,
} from '#/api/dataforge/fullsync';

import { computed, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { formatDate } from '@vben/utils';

import {
  Alert,
  Button,
  Dropdown,
  Input,
  Menu,
  MenuItem,
  message,
  Modal,
  Select,
  Switch,
  Tooltip,
} from 'antdv-next';

import {
  ACTION_ICON,
  NativeVxeButton,
  TableAction,
  useVbenVxeGrid,
} from '#/adapter/business-vxe-table';
import {
  deleteWizard,
  getDatabaseOptions,
  getResponsibleUsers,
  getWizard,
  getWizardPage,
  LIST_PATH,
  saveWizardStep,
} from '#/api/dataforge/fullsync';
import ListPagination from '#/components/list-pagination/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import ReferenceIcon from './modules/reference-icon.vue';
import { useFullSyncScope } from './use-scope';

import './fullsync.css';

defineOptions({ name: 'DataforgeFullSync' });
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const can = (action: string) =>
  hasAccessByCodes([`dataforge:full-sync:${action}`]);
const filters = reactive({
  taskName: '',
  sourceDatabaseId: undefined as Id | undefined,
  targetDatabaseId: undefined as Id | undefined,
});
const applied = reactive({ ...filters });
const rows = ref<Wizard[]>([]);
const databases = ref<DatabaseOption[]>([]);
const users = ref<ResponsibleUser[]>([]);
const options = computed(() =>
  databases.value.map((entry) => ({ value: entry.id, label: entry.name })),
);
const selected = ref<string[]>([]);
const pageNo = ref(1);
const pageSize = ref(10);
const total = ref(0);
const loading = ref(false);
const failed = ref(false);
const optionsFailed = ref(false);
const showSearch = ref(true);
const busyIds = ref(new Set<string>());
const { active, scope } = useFullSyncScope();
const request = useObjectRequest(() => scope.value);
const optionRequest = useObjectRequest(() => scope.value);
const actionRequest = useObjectRequest(() => scope.value);
const dbName = (id?: Id) =>
  databases.value.find((entry) => String(entry.id) === String(id))?.name ||
  (id ? `数据连接 ${id}` : '-');
const dbIcon = (id?: Id) => {
  const type =
    databases.value.find((entry) => String(entry.id) === String(id))?.type ||
    '';
  if (/doris/i.test(type)) return 'type-doris';
  if (/mysql/i.test(type)) return 'type-mysql';
  return '';
};
const creatorName = (id?: string) =>
  users.value.find((user) => String(user.id) === id)?.nickname || id || '-';
const creatorPhone = (id?: string) =>
  users.value.find((user) => String(user.id) === id)?.mobile;
const isBusy = (row: Wizard) => busyIds.value.has(String(row.id));
const editable = (row: Wizard) => ['DRAFT', 'FAILED'].includes(row.status);
const strategy = (value?: string) =>
  ({
    PARALLEL: '并行',
    SERIAL_WAIT: '串行等待',
    SERIAL_DISCARD: '串行抛弃',
    SERIAL_PRIORITY: '串行优先',
  })[value ?? 'PARALLEL'] || value;
async function load() {
  if (!active.value || !can('query')) return;
  const current = request.begin();
  loading.value = true;
  failed.value = false;
  try {
    const result = await getWizardPage({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      ...applied,
    });
    if (current()) {
      rows.value = result.list;
      total.value = result.total;
      selected.value = [];
      void gridApi.grid?.clearCheckboxRow();
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
async function loadOptions() {
  const current = optionRequest.begin();
  optionsFailed.value = false;
  try {
    const [databaseResult, userResult] = await Promise.allSettled([
      getDatabaseOptions(),
      getResponsibleUsers(),
    ]);
    if (!current()) return;
    if (databaseResult.status === 'fulfilled')
      databases.value = databaseResult.value;
    else optionsFailed.value = true;
    if (userResult.status === 'fulfilled') users.value = userResult.value;
  } catch {
    if (current()) optionsFailed.value = true;
  }
}
function search() {
  Object.assign(applied, filters);
  pageNo.value = 1;
  void load();
}
function reset() {
  filters.taskName = '';
  filters.sourceDatabaseId = undefined;
  filters.targetDatabaseId = undefined;
  search();
}
function openWizard(row?: Wizard) {
  void router.push({
    name: 'DataforgeFullSyncWizard',
    params: row ? { id: String(row.id) } : {},
    query: { mode: row ? 'edit' : 'create' },
  });
}
function details(row: Wizard) {
  void router.push(`${LIST_PATH}/detail/${encodeURIComponent(String(row.id))}`);
}
async function toggle(
  row: Wizard,
  field: 'scheduleEnabled' | 'taskStatus',
  checked: boolean,
) {
  if (busyIds.value.size > 0 || !can('update') || !editable(row)) return;
  const current = actionRequest.begin();
  busyIds.value.add(String(row.id));
  try {
    const latest = await getWizard(row.id);
    if (!current()) return;
    const config = { ...latest.config };
    if (field === 'taskStatus') config.taskStatus = checked ? 1 : 0;
    else config.scheduleEnabled = checked;
    const updated = await saveWizardStep(latest.id, 1, config, latest.revision);
    if (current()) {
      const index = rows.value.findIndex(
        (entry) => String(entry.id) === String(row.id),
      );
      if (index !== -1) rows.value[index] = updated;
      message.success('配置已保存');
      await load();
    }
  } catch {
    // 请求层统一展示错误，保留原有开关状态。
  } finally {
    busyIds.value.delete(String(row.id));
  }
}
function remove(items: Wizard[]) {
  if (items.length === 0) return;
  const capturedScope = scope.value;
  Modal.confirm({
    title: '删除整库同步任务',
    content: `确认删除选中的 ${items.length} 个整库同步配置？`,
    okText: '删除',
    okButtonProps: { danger: true },
    cancelText: '取消',
    async onOk() {
      if (scope.value !== capturedScope || !can('delete')) return;
      try {
        for (const row of items) {
          if (scope.value !== capturedScope) return;
          await deleteWizard(row.id, row.revision);
        }
        if (scope.value === capturedScope) {
          message.success('删除成功');
          if (rows.value.length === items.length && pageNo.value > 1)
            pageNo.value--;
        }
      } catch {
        // 请求层展示错误，刷新后可重新选择尚未删除的配置。
      } finally {
        if (scope.value === capturedScope) {
          selected.value = [];
          await load();
        }
      }
    },
  });
}
const [Grid, gridApi] = useVbenVxeGrid<Wizard>({
  gridOptions: {
    columns: [
      { type: 'checkbox', width: 45 },
      { field: 'id', title: '编号', width: 90 },
      { field: 'info', title: '任务信息', width: 450, slots: { default: 'info' } },
      { field: 'link', title: '数据链路 (源 -> 目标)', width: 235, slots: { default: 'link' } },
      { field: 'state', title: '运行状态', width: 145, slots: { default: 'state' } },
      { field: 'schedule', title: '策略与调度', width: 340, slots: { default: 'schedule' } },
      { field: 'responsible', title: '责任人', width: 140, slots: { default: 'responsible' } },
      { field: 'creator', title: '创建人', width: 140, slots: { default: 'creator' } },
      { field: 'createTime', title: '创建时间', width: 180, slots: { default: 'createTime' } },
      { field: 'actions', title: '操作', width: 245, fixed: 'right', showOverflow: false, slots: { default: 'actions' } },
    ],
    checkboxConfig: { checkMethod: ({ row }) => editable(row as Wizard) },
    data: [],
    loading: false,
    pagerConfig: { enabled: false },
    proxyConfig: { enabled: false },
    rowConfig: { keyField: 'id', isHover: true },
    scrollbarConfig: { height: 6 },
    showOverflow: false,
    toolbarConfig: { refresh: true, refreshOptions: { queryMethod: load }, custom: true, zoom: false },
  } as VxeTableGridOptions<Wizard>,
  gridEvents: {
    checkboxChange: ({ records }: { records: Wizard[] }) => {
      selected.value = records.map((row) => String(row.id));
    },
    checkboxAll: ({ records }: { records: Wizard[] }) => {
      selected.value = records.map((row) => String(row.id));
    },
  },
});
watch(loading, (value) => gridApi.setLoading(value), { immediate: true });
watch(
  scope,
  () => {
    rows.value = [];
    databases.value = [];
    users.value = [];
    selected.value = [];
    busyIds.value.clear();
    loading.value = false;
    failed.value = false;
    if (active.value) {
      void load();
      void loadOptions();
    }
  },
  { immediate: true },
);
</script>

<template>
    <Page auto-content-height content-class="qdata-fullsync fs-list-page">
      <div class="fs-page">
        <Alert
          v-if="!can('query')"
          type="warning"
          title="没有整库同步查询权限"
          show-icon
        />
        <Alert
          v-if="failed"
          type="error"
          title="任务列表加载失败，请重试"
          show-icon
        >
          <template #action><Button @click="load">重试</Button></template>
        </Alert>
        <Alert
          v-if="optionsFailed"
          type="warning"
          title="数据连接选项加载失败"
          show-icon
        >
          <template #action>
            <Button @click="loadOptions">重试</Button>
          </template>
        </Alert>
        <form
          v-if="showSearch"
          class="fs-panel fs-search"
          @submit.prevent="search"
        >
          <label><span>任务名称</span><Input
              v-model:value="filters.taskName"
              placeholder="请输入任务名称"
              allow-clear
          /></label>
          <label><span>源数据库</span><Select
              v-model:value="filters.sourceDatabaseId"
              :options="options"
              placeholder="请选择源数据库"
              allow-clear
              show-search
              option-filter-prop="label"
          /></label>
          <label><span>目标数据库</span><Select
              v-model:value="filters.targetDatabaseId"
              :options="options"
              placeholder="请选择目标数据库"
              allow-clear
              show-search
              option-filter-prop="label"
          /></label>
          <div class="fs-search-actions">
            <Button html-type="submit" type="primary">
              查询
</Button><Button @click="reset">重置</Button>
          </div>
        </form>
        <section class="fs-panel fs-list-panel">
          <Grid class="fs-list-grid" table-title="整库同步任务列表" :table-data="rows">
            <template #toolbar-tools>
              <TableAction
                :actions="[
                  { label: '新增', type: 'primary', icon: ACTION_ICON.ADD, ifShow: can('create'), onClick: () => openWizard() },
                  { label: '批量删除', type: 'primary', danger: true, icon: ACTION_ICON.DELETE, ifShow: can('delete'), disabled: !selected.length, onClick: () => remove(rows.filter((row) => selected.includes(String(row.id)))) },
                ]"
              />
              <NativeVxeButton
                class="ml-2"
                aria-label="显示或隐藏搜索"
                title="搜索"
                icon="vxe-icon-search"
                circle
                :status="showSearch ? 'primary' : undefined"
                :aria-pressed="showSearch"
                @click="showSearch = !showSearch"
              />
            </template>
              <template #info="{ row }">
                <div class="fs-task-title">
                  {{ row.config.taskName
                  }}<span class="fs-tag purple">同步{{
                      row.config.sourceSelectedTables?.length || 0
                    }}张表</span>
                </div>
                <div class="fs-secondary fs-description">
                  {{ row.config.description || '-' }}
                </div>
              </template>
              <template #link="{ row }">
                <div class="fs-data-link">
                  <Tooltip :title="dbName(row.config.sourceDatabaseId)">
                    <span class="fs-db-name"><ReferenceIcon
                        v-if="dbIcon(row.config.sourceDatabaseId)"
                        :name="dbIcon(row.config.sourceDatabaseId)"
                      />{{ dbName(row.config.sourceDatabaseId) }}</span>
</Tooltip><ReferenceIcon name="arrow" /><Tooltip
                    :title="dbName(row.config.targetDatabaseId)"
                  >
                    <span class="fs-db-name target"><ReferenceIcon
                        v-if="dbIcon(row.config.targetDatabaseId)"
                        :name="dbIcon(row.config.targetDatabaseId)"
                      />{{ dbName(row.config.targetDatabaseId) }}</span>
                  </Tooltip>
                </div>
              </template>
              <template #state="{ row }">
                <div class="fs-state">
                  任务状态:
                  <Switch
                    :checked="row.config.taskStatus === 1"
                    :loading="isBusy(row)"
                    :disabled="
                      busyIds.size > 0 || !can('update') || !editable(row)
                    "
                    @change="
                      (value) => toggle(row, 'taskStatus', Boolean(value))
                    "
                  />
                </div>
                <div class="fs-state">
                  调度状态:
                  <Switch
                    :checked="!!row.config.scheduleEnabled"
                    :loading="isBusy(row)"
                    :disabled="
                      !can('update') ||
                      !editable(row) ||
                      row.config.executionMethod !== 'CRON'
                    "
                    @change="
                      (value) => toggle(row, 'scheduleEnabled', Boolean(value))
                    "
                  />
                </div>
              </template>
              <template #schedule="{ row }">
                <div>{{ strategy(row.config.executionStrategy) }}</div>
                <div class="fs-schedule">
                  <ReferenceIcon name="clock" />{{
                    row.config.executionMethod === 'CRON'
                      ? row.config.cron || '未配置调度周期'
                      : '手动执行'
                  }}
                </div>
              </template>
              <template #responsible="{ row }">
                <div>{{ row.config.responsibleName || '-' }}</div>
                <div class="fs-second-line">
                  {{ row.config.responsiblePhone || '-' }}
                </div>
              </template>
              <template #creator="{ row }">
                <div>{{ creatorName(row.creator) }}</div>
                <div v-if="creatorPhone(row.creator)" class="fs-second-line">
                  {{ creatorPhone(row.creator) }}
                </div>
              </template>
              <template #createTime="{ row }">
                {{
                  row.createTime
                    ? formatDate(row.createTime, 'YYYY-MM-DD HH:mm')
                    : '-'
                }}
              </template>
              <template #actions="{ row }">
                <div class="fs-row-actions">
                  <button
                    v-if="can('update')"
                    class="fs-link"
                    :disabled="!editable(row)"
                    @click="openWizard(row)"
                  >
                    <ReferenceIcon name="config" />配置
</button><button class="fs-link" @click="details(row)">
                    <ReferenceIcon name="eye" />详情
</button><Dropdown :trigger="['click']">
                    <button class="fs-link">
                      <IconifyIcon icon="lucide:ellipsis-vertical" />更多
</button><template #popupRender>
                      <Menu>
                        <MenuItem
                          v-if="can('delete')"
                          :disabled="!editable(row)"
                          danger
                          @click="remove([row])"
                        >
                          <ReferenceIcon name="delete" /> 删除任务
</MenuItem><MenuItem @click="details(row)">
                          <ReferenceIcon name="eye" /> 配置详情
                        </MenuItem>
                      </Menu>
                    </template>
                  </Dropdown>
                </div>
              </template>
            <template #empty><div class="fs-empty">暂无记录</div></template>
            <template #pager>
              <ListPagination
            :current="pageNo"
            :page-size="pageSize"
            :page-size-options="[10, 20, 50, 100]"
            :total="total"
            @change="(page, size) => { pageNo = page; pageSize = size; load(); }"
              />
            </template>
          </Grid>
        </section>
      </div>
    </Page>
</template>
