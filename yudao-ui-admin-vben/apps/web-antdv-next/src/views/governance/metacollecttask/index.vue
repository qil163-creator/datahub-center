<script setup lang="ts">
import type { GovernanceMetaCollectTaskApi } from '#/api/governance/metacollecttask';

import { computed, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import { Button, message, Switch } from 'antdv-next';

import { ACTION_ICON, TableAction } from '#/adapter/vxe-table';
import {
  deleteMetaCollectTaskList,
  getMetaCollectTaskPage,
  runMetaCollectTask,
  updateMetaCollectTaskEnabled,
  updateMetaCollectTaskScheduleEnabled,
} from '#/api/governance/metacollecttask';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { useObjectRequest } from '#/hooks/use-object-request';

import {
  collectTypeLabel,
  metadataSchedule,
  metadataTime,
} from '../metadata-common/helpers';
import { metadataConnectionIcon, metadataIcon } from '../metadata-common/icons';
import MetadataIcon from '../metadata-common/metadata-icon.vue';
import { useMetadataGrid } from '../metadata-common/use-metadata-grid';
import { useGridColumns } from './data';
import Form from './modules/form.vue';

import '../metadata-common/metadata.css';

defineOptions({ name: 'GovernanceMetaCollectTask' });
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const canUpdate = computed(() =>
  hasAccessByCodes(['governance:meta-collect-task:update']),
);
const filters = reactive({
  name: '',
  createTime: undefined as [string, string] | undefined,
});
const busy = ref(new Set<string>());
const deleting = ref(false);
const operations = useObjectRequest(
  () => '',
  () => {
    busy.value = new Set();
    deleting.value = false;
  },
);
const deletion = useObjectRequest(
  () => '',
  () => {
    deleting.value = false;
  },
);
const execution = useObjectRequest();
const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});
const { Grid, gridApi, load, selected } =
  useMetadataGrid<GovernanceMetaCollectTaskApi.CollectTask>(
    useGridColumns(),
    (page) => getMetaCollectTaskPage({ ...page, ...filters }),
    {
      schema: [
        {
          fieldName: 'name',
          label: '任务名称',
          component: 'Input',
          componentProps: { allowClear: true, placeholder: '请输入任务名称' },
        },
        {
          fieldName: 'createTime',
          label: '创建时间',
          component: 'RangePicker',
          componentProps: {
            valueFormat: 'YYYY-MM-DD HH:mm:ss',
            allowClear: true,
            placeholder: ['开始日期', '结束日期'],
          },
        },
      ],
      handleSubmit: (values): Promise<void> => {
        filters.name = values.name ?? '';
        filters.createTime = values.createTime;
        return load(true);
      },
      handleReset: async () => {
        await gridApi.formApi.reset();
        reset();
      },
    },
    clearFilters,
    'fill',
    true,
    true,
  );
function clearFilters() {
  filters.name = '';
  filters.createTime = undefined;
}
function reset() {
  clearFilters();
  void load(true);
}
function create(collectType: 1 | 2) {
  formModalApi.setData({ collectType }).open();
}
function edit(row: GovernanceMetaCollectTaskApi.CollectTask) {
  formModalApi.setData(row).open();
}
function detail(row: GovernanceMetaCollectTaskApi.CollectTask) {
  void router.push({
    name: 'GovernanceMetaCollectTaskDetail',
    params: { id: String(row.id) },
  });
}
function instances(row: GovernanceMetaCollectTaskApi.CollectTask) {
  void router.push({
    path: '/governance/metacollectinstance',
    query: { taskId: String(row.id) },
  });
}
async function changeState(
  row: GovernanceMetaCollectTaskApi.CollectTask,
  kind: 'enabled' | 'scheduleEnabled',
  checked: boolean | number | string,
) {
  if (!canUpdate.value || row.id === undefined || busy.value.size > 0) return;
  const current = operations.begin();
  busy.value.add(String(row.id));
  try {
    await (kind === 'enabled'
      ? updateMetaCollectTaskEnabled(row.id, checked ? 1 : 0)
      : updateMetaCollectTaskScheduleEnabled(row.id, checked ? 1 : 0));
    if (current()) {
      message.success('状态已更新');
      await load();
    }
  } finally {
    busy.value.delete(String(row.id));
  }
}
async function run(row: GovernanceMetaCollectTaskApi.CollectTask) {
  if (row.id === undefined) return;
  const current = execution.begin();
  await runMetaCollectTask(row.id);
  if (!current()) return;
  message.success('已触发采集，可在采集实例中查看进度与日志');
  await load();
}
async function remove(rows = selected.value) {
  const ids = rows.flatMap((row) => (row.id === undefined ? [] : [row.id]));
  if (ids.length === 0 || deleting.value) return;
  await confirm('确认删除选中的采集任务？已上线任务需要先下线。');
  const current = deletion.begin();
  deleting.value = true;
  try {
    await deleteMetaCollectTaskList(ids);
    if (current()) {
      message.success('删除成功');
      await load();
    }
  } finally {
    if (current()) deleting.value = false;
  }
}
</script>

<template>
  <Page auto-content-height content-class="metadata-page">
    <FormModal @success="load" />

    <div class="metadata-grid-shell">
      <Grid class="metadata-grid subtle-grid-scrollbar" table-title="采集任务列表">
        <template #toolbar-tools>
          <div class="metadata-toolbar">
            <div class="metadata-toolbar">
              <Button
                v-if="hasAccessByCodes(['governance:meta-collect-task:create'])"
                type="primary"
                @click="create(1)"
              >
                ＋ 新增结构化采集任务
              </Button>
              <Button
                v-if="hasAccessByCodes(['governance:meta-collect-task:create'])"
                type="primary"
                @click="create(2)"
              >
                ＋ 新增非结构化采集任务
              </Button>
              <TableAction
                :actions="[
                  {
                    label: '删除',
                    type: 'default',
                    icon: ACTION_ICON.DELETE,
                    danger: true,
                    disabled: !selected.length,
                    loading: deleting,
                    auth: ['governance:meta-collect-task:delete'],
                    onClick: () => remove(),
                  },
                ]"
              />
            </div>
          </div>
        </template>
        <template #taskInfo="{ row }">
          <div class="metadata-info">
            <div class="metadata-info__title">
              <button class="metadata-link" @click="detail(row)">
                {{ row.name }}
</button><span
                class="metadata-tag"
                :class="{ 'metadata-tag--file': row.collectType === 2 }"
                >{{ collectTypeLabel(row.collectType) }}</span>
            </div>
            <TextEllipsisCell
              class="metadata-muted"
              :content="row.description || '-'"
              :line="2"
              center
            />
          </div>
        </template>
        <template #source="{ row }">
          <div class="metadata-info">
            <span class="metadata-ellipsis" :title="row.sourceSystemName">{{
              row.sourceSystemName || '-'
            }}</span><span class="metadata-tag metadata-tag--source"><MetadataIcon
                :name="metadataConnectionIcon(row.dbType)"
                :size="11"
              />{{ row.datasourceName || '-' }}</span>
          </div>
        </template>
        <template #state="{ row }">
          <div class="metadata-info">
            <div>
              任务状态：<Switch
                size="small"
                :checked="row.enabled === 1"
                :disabled="!canUpdate || busy.size > 0"
                :loading="busy.has(String(row.id))"
                @change="(value) => changeState(row, 'enabled', value)"
              />
            </div>
            <div>
              调度状态：<Switch
                size="small"
                :checked="row.scheduleEnabled === 1"
                :disabled="!canUpdate || busy.size > 0 || row.enabled !== 1"
                :loading="busy.has(String(row.id))"
                @change="(value) => changeState(row, 'scheduleEnabled', value)"
              />
            </div>
          </div>
        </template>
        <template #schedule="{ row }">
          <div class="metadata-info">
            <span :title="row.cron">{{ metadataSchedule(row.cron) }}</span><span>{{
              metadataTime(row.lastRunTime, 'YYYY-MM-DD HH:mm')
            }}</span>
          </div>
        </template>
        <template #owner="{ row }">
          <div class="metadata-info">
            <span>{{ row.ownerName || '-' }}</span><span>{{ row.ownerPhone || '-' }}</span>
          </div>
        </template>
        <template #creator="{ row }">
          <div class="metadata-info">
            <span>{{ row.creatorName || '-' }}</span><span>{{ row.creatorPhone || '-' }}</span>
          </div>
        </template>
        <template #actions="{ row }">
          <TableAction
            :actions="[
              {
                label: '修改',
                type: 'link',
                icon: metadataIcon('edit'),
                auth: ['governance:meta-collect-task:update'],
                disabled: row.enabled === 1,
                onClick: () => edit(row),
              },
              {
                label: '详情',
                type: 'link',
                icon: metadataIcon('detail'),
                auth: ['governance:meta-collect-task:query'],
                onClick: () => detail(row),
              },
            ]"
            :drop-down-actions="[
              {
                label: '采集实例',
                icon: metadataIcon('instances'),
                auth: ['governance:meta-collect-instance:query'],
                onClick: () => instances(row),
              },
              {
                label: '执行一次',
                icon: metadataIcon('play'),
                auth: ['governance:meta-collect-task:run'],
                popConfirm: {
                  title: '确认立即执行该采集任务？',
                  confirm: () => run(row),
                },
              },
              {
                label: '删除',
                icon: metadataIcon('delete'),
                danger: true,
                disabled: row.enabled === 1,
                auth: ['governance:meta-collect-task:delete'],
                onClick: () => remove([row]),
              },
            ]"
          >
            <template #more>
              <Button type="link"><IconifyIcon icon="lucide:ellipsis-vertical" />更多</Button>
            </template>
          </TableAction>
        </template>
      </Grid>
    </div>
  </Page>
</template>
