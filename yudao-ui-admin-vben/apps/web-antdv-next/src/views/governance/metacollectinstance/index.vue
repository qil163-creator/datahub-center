<script setup lang="ts">
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceMetaCollectInstanceApi as Api } from '#/api/governance/metacollectinstance';

import { reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';

import { useAccess } from '@vben/access';
import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { downloadFileFromBlobPart } from '@vben/utils';

import { Alert, Button, message } from 'antdv-next';

import {
  deleteMetaCollectInstanceList,
  downloadMetaCollectInstanceLog,
  getMetaCollectInstancePage,
} from '#/api/governance/metacollectinstance';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { useObjectRequest } from '#/hooks/use-object-request';

import { collectTypeLabel, metadataTime } from '../metadata-common/helpers';
import { metadataConnectionIcon } from '../metadata-common/icons';
import MetadataIcon from '../metadata-common/metadata-icon.vue';
import { useMetadataGrid } from '../metadata-common/use-metadata-grid';
import { INSTANCE_STATUS_OPTIONS, optionLabel } from './data';
import Log from './modules/log.vue';

import '../metadata-common/metadata.css';

defineOptions({ name: 'GovernanceMetaCollectInstance' });
const route = useRoute();
const { hasAccessByCodes } = useAccess();
const filters = reactive({
  name: '',
  collectType: undefined as number | undefined,
  createTime: undefined as [string, string] | undefined,
});
const searchVisible = ref(true);
const deleting = ref(false);
const downloadIds = ref(new Set<string>());
const operations = useObjectRequest(
  () => String(route.query.taskId ?? ''),
  () => {
    downloadIds.value = new Set();
  },
);
const deletion = useObjectRequest(
  () => String(route.query.taskId ?? ''),
  () => {
    deleting.value = false;
  },
);
const [LogModal, logApi] = useVbenModal({
  connectedComponent: Log,
  destroyOnClose: true,
});
const columns: VxeTableGridOptions<Api.CollectInstance>['columns'] = [
  { type: 'checkbox', width: 42 },
  { field: 'id', title: '编号', width: 70 },
  {
    field: 'taskName',
    title: '任务信息',
    minWidth: 390,
    align: 'left',
    showOverflow: false,
    slots: { default: 'taskInfo' },
  },
  {
    field: 'sourceSystemName',
    title: '采集来源',
    minWidth: 240,
    align: 'left',
    slots: { default: 'source' },
  },
  {
    title: '采集信息',
    width: 180,
    align: 'left',
    slots: { default: 'statistics' },
  },
  {
    title: '采集起止时间',
    width: 220,
    showOverflow: false,
    slots: { default: 'time' },
  },
  { title: '负责人', width: 140, align: 'left', slots: { default: 'owner' } },
  { title: '创建人', width: 140, align: 'left', slots: { default: 'creator' } },
  {
    field: 'createTime',
    title: '创建时间',
    width: 170,
    formatter: ({ cellValue }) => metadataTime(cellValue, 'YYYY-MM-DD HH:mm'),
  },
  {
    title: '操作',
    width: 205,
    fixed: 'right',
    showOverflow: false,
    slots: { default: 'actions' },
  },
];
const { Grid, gridApi, load, selected, error } =
  useMetadataGrid<Api.CollectInstance>(
    columns,
    (page) =>
      getMetaCollectInstancePage({
        ...page,
        ...filters,
        taskId: route.query.taskId || undefined,
      }),
    {
      schema: [
        {
          fieldName: 'name',
          label: '任务名称',
          component: 'Input',
          componentProps: { allowClear: true, placeholder: '请输入任务名称' },
        },
        {
          fieldName: 'collectType',
          label: '元数据来源',
          component: 'Select',
          componentProps: {
            allowClear: true,
            placeholder: '请选择元数据来源',
            options: [
              { label: '数据库表', value: 1 },
              { label: '非结构化数据', value: 2 },
            ],
          },
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
        Object.assign(
          filters,
          { name: '', collectType: undefined, createTime: undefined },
          values,
        );
        return load(true);
      },
      handleReset: async () => {
        await gridApi.formApi.reset();
        reset();
      },
    },
    clearFilters,
  );
watch(
  () => route.query.taskId,
  () => load(true),
);
function clearFilters() {
  filters.name = '';
  filters.collectType = undefined;
  filters.createTime = undefined;
}
function reset() {
  clearFilters();
  void load(true);
}
function showLog(row: Api.CollectInstance) {
  logApi.setData(row).open();
}
async function download(row: Api.CollectInstance) {
  if (row.id === undefined || downloadIds.value.has(String(row.id))) return;
  const id = row.id;
  const current = operations.begin();
  downloadIds.value.add(String(id));
  try {
    const blob = await downloadMetaCollectInstanceLog(id);
    if (current())
      downloadFileFromBlobPart({
        source: blob,
        fileName: `meta-collect-instance-${id}.log`,
      });
  } finally {
    downloadIds.value.delete(String(id));
  }
}
async function remove() {
  const ids = selected.value.flatMap((row) =>
    row.id === undefined ? [] : [row.id],
  );
  if (ids.length === 0 || deleting.value) return;
  await confirm('确认删除所选已结束采集实例及其日志？');
  const current = deletion.begin();
  deleting.value = true;
  try {
    await deleteMetaCollectInstanceList(ids);
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
    <LogModal />

    <Alert
      v-if="error"
      type="error"
      show-icon
      message="采集实例加载失败，请重试"
    >
      <template #action>
        <Button size="small" @click="load()">
          <template #icon>
            <MetadataIcon name="refresh" />
          </template>
          重试
        </Button>
      </template>
    </Alert>
    <div class="metadata-grid-shell">
      <Grid
        class="metadata-grid"
        table-title="采集实例列表"
        :show-search-form="searchVisible"
      >
        <template #toolbar-tools>
          <div class="metadata-toolbar">
            <div class="metadata-toolbar">
              <Button
                v-if="
                  hasAccessByCodes(['governance:meta-collect-instance:delete'])
                "
                danger
                :disabled="!selected.length"
                :loading="deleting"
                @click="remove"
              >
                <template #icon><MetadataIcon name="delete" /></template>
                删除
              </Button>
            </div>

            <div class="metadata-tools">
              <Button
                title="显示/隐藏查询"
                @click="searchVisible = !searchVisible"
              >
                <MetadataIcon name="search" />
</Button><Button title="刷新" @click="load()">
                <MetadataIcon name="refresh" />
              </Button>
            </div>
          </div>
        </template>
        <template #taskInfo="{ row }">
          <div class="metadata-info">
            <div class="metadata-info__title">
              <span>{{ row.taskName || '-' }}</span><span
                class="metadata-tag"
                :class="{
                  'metadata-tag--success': row.status === 2,
                  'metadata-tag--error': row.status === 3 || row.status === 5,
                }"
                >{{ optionLabel(INSTANCE_STATUS_OPTIONS, row.status) }}</span><span
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
            <span class="metadata-ellipsis">{{
              row.sourceSystemName || '-'
            }}</span><span class="metadata-tag metadata-tag--source"><MetadataIcon
                :name="metadataConnectionIcon(row.dbType)"
                :size="11"
              />{{ row.datasourceName || '-' }}</span>
          </div>
        </template>
        <template #statistics="{ row }">
          <div class="metadata-info">
            <span>采集耗时：{{
                row.durationMs === undefined
                  ? '-'
                  : Math.round(row.durationMs / 1000)
              }}
              秒</span><span>采集数量：{{
                row.collectType === 2
                  ? (row.fileCount ?? 0)
                  : (row.tableCount ?? 0)
              }}
              {{ row.collectType === 2 ? '个' : '张' }}</span>
          </div>
        </template>
        <template #time="{ row }">
          {{ metadataTime(row.startTime) }} ~
          {{ metadataTime(row.endTime) }}
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
          <div class="flex items-center">
            <Button
              v-if="
                hasAccessByCodes(['governance:meta-collect-instance:query'])
              "
              type="link"
              size="small"
              @click="showLog(row)"
            >
              <MetadataIcon name="detail" />查看日志
</Button><Button
              v-if="
                hasAccessByCodes(['governance:meta-collect-instance:query'])
              "
              type="link"
              size="small"
              :loading="downloadIds.has(String(row.id))"
              @click="download(row)"
            >
              <MetadataIcon name="download" />下载日志
            </Button>
          </div>
        </template>
      </Grid>
    </div>
  </Page>
</template>
