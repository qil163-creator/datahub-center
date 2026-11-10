<script setup lang="ts">
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
  Checkbox,
  Dropdown,
  Input,
  Menu,
  MenuItem,
  message,
  Modal,
  Select,
  Switch,
  Table,
  Tooltip,
} from 'antdv-next';

import {
  ACTION_ICON,
  NativeVxeButton,
} from '#/adapter/vxe-table';
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
const tableMaximized = ref(false);
const busyIds = ref(new Set<string>());
const { active, scope } = useFullSyncScope();
watch(active, (isActive) => {
  if (!isActive) tableMaximized.value = false;
});
const request = useObjectRequest(() => scope.value);
const optionRequest = useObjectRequest(() => scope.value);
const actionRequest = useObjectRequest(() => scope.value);
const definitions = [
  { key: 'id', title: '编号', width: 90 },
  { key: 'info', title: '任务信息', width: 450 },
  { key: 'link', title: '数据链路 (源 -> 目标)', width: 235 },
  { key: 'state', title: '运行状态', width: 145 },
  { key: 'schedule', title: '策略与调度', width: 340 },
  { key: 'responsible', title: '责任人', width: 140 },
  { key: 'creator', title: '创建人', width: 140 },
  { key: 'createTime', title: '创建时间', width: 180 },
];
const visible = ref(definitions.map((entry) => entry.key));
const columns = computed(() => [
  ...definitions.filter((entry) => visible.value.includes(entry.key)),
  { key: 'actions', title: '操作', width: 245, fixed: 'right' as const },
]);
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
  tableMaximized.value = false;
  void router.push({
    name: 'DataforgeFullSyncWizard',
    params: row ? { id: String(row.id) } : {},
    query: { mode: row ? 'edit' : 'create' },
  });
}
function details(row: Wizard) {
  tableMaximized.value = false;
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
        <section class="fs-panel fs-list-panel" :class="{ 'is-maximized': tableMaximized }">
          <div class="fs-toolbar">
            <h2 class="fs-list-title">整库同步任务列表</h2>
            <div class="fs-toolbar-group">
              <Button
                v-if="can('create')"
                type="primary"
                @click="openWizard()"
              >
                <IconifyIcon :icon="ACTION_ICON.ADD" />新增
</Button><Button
                v-if="can('delete')"
                danger
                :disabled="!selected.length"
                @click="
                  remove(
                    rows.filter((row) => selected.includes(String(row.id))),
                  )
                "
              >
                <IconifyIcon :icon="ACTION_ICON.DELETE" />删除
              </Button>
              <Tooltip title="显示/隐藏搜索">
                <NativeVxeButton
                  aria-label="显示或隐藏搜索"
                  icon="vxe-icon-search"
                  circle
                  :status="showSearch ? 'primary' : undefined"
                  @click="showSearch = !showSearch"
                />
</Tooltip><Tooltip title="刷新">
                <NativeVxeButton aria-label="刷新列表" icon="vxe-icon-repeat" circle @click="load" />
</Tooltip><Tooltip :title="tableMaximized ? '还原' : '全屏'">
                <NativeVxeButton
                  circle
                  :icon="tableMaximized ? 'vxe-icon-minimize' : 'vxe-icon-fullscreen'"
                  :aria-label="tableMaximized ? '还原表格' : '全屏表格'"
                  :aria-pressed="tableMaximized"
                  @click="tableMaximized = !tableMaximized"
                />
</Tooltip><Dropdown :trigger="['click']">
                <NativeVxeButton aria-label="设置显示列" icon="vxe-icon-setting" circle />
<template #popupRender>
                  <Menu>
                    <MenuItem v-for="column in definitions" :key="column.key">
                      <Checkbox
                        :checked="visible.includes(column.key)"
                        @change="
                          (event) => {
                            visible = event.target.checked
                              ? [...visible, column.key]
                              : visible.filter((key) => key !== column.key);
                          }
                        "
                      >
                        {{ column.title }}
                      </Checkbox>
                    </MenuItem>
                  </Menu>
                </template>
              </Dropdown>
            </div>
          </div>
          <Table
            class="fs-list-table"
            :columns="columns"
            :data-source="rows"
            :loading="loading"
            :pagination="false"
            :row-key="(row: Wizard) => String(row.id)"
            :scroll="{ x: 'max-content' }"
            :row-selection="{
              selectedRowKeys: selected,
              onChange: (keys) => {
                selected = keys.map(String);
              },
              getCheckboxProps: (row: Wizard) => ({ disabled: !editable(row) }),
            }"
          >
            <template #bodyCell="{ column, record: row }">
              <template v-if="column.key === 'id'">{{ row.id }}</template>
              <template v-else-if="column.key === 'info'">
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
              <template v-else-if="column.key === 'link'">
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
              <template v-else-if="column.key === 'state'">
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
              <template v-else-if="column.key === 'schedule'">
                <div>{{ strategy(row.config.executionStrategy) }}</div>
                <div class="fs-schedule">
                  <ReferenceIcon name="clock" />{{
                    row.config.executionMethod === 'CRON'
                      ? row.config.cron || '未配置调度周期'
                      : '手动执行'
                  }}
                </div>
              </template>
              <template v-else-if="column.key === 'responsible'">
                <div>{{ row.config.responsibleName || '-' }}</div>
                <div class="fs-second-line">
                  {{ row.config.responsiblePhone || '-' }}
                </div>
              </template>
              <template v-else-if="column.key === 'creator'">
                <div>{{ creatorName(row.creator) }}</div>
                <div v-if="creatorPhone(row.creator)" class="fs-second-line">
                  {{ creatorPhone(row.creator) }}
                </div>
              </template>
              <template v-else-if="column.key === 'createTime'">
                {{
                  row.createTime
                    ? formatDate(row.createTime, 'YYYY-MM-DD HH:mm')
                    : '-'
                }}
              </template>
              <template v-else-if="column.key === 'actions'">
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
            </template>
            <template #emptyText><div class="fs-empty">暂无记录</div></template>
          </Table>
          <ListPagination
            :current="pageNo"
            :page-size="pageSize"
            :page-size-options="[10, 20, 50, 100]"
            :total="total"
            @change="(page, size) => { pageNo = page; pageSize = size; load(); }"
          />
        </section>
      </div>
    </Page>
</template>
