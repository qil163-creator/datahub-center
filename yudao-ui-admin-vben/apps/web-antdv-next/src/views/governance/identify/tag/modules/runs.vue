<script lang="ts" setup>
import type { SafetyIdentifyApi } from '#/api/governance/identify';

import { reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { formatDateTime } from '@vben/utils';

import { Button, Select, Table, Tag } from 'antdv-next';

import { getScanRunPage } from '#/api/governance/identify';
import TextPreview from '#/views/governance/common/text-preview.vue';

import { optionColor, optionLabel, SCAN_STATUS_OPTIONS } from '../data';

defineOptions({ name: 'SafetyScanRuns' });

const rows = ref<SafetyIdentifyApi.ScanRun[]>([]);
const loading = ref(false);
const pagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const runStatus = ref<number>();

const tableColumns = [
  { title: '编号', dataIndex: 'id', key: 'id', width: 70 },
  {
    title: '数据源编号',
    dataIndex: 'databaseId',
    key: 'databaseId',
    width: 100,
  },
  { title: '扫描范围', key: 'scope', width: 200 },
  { title: '状态', key: 'runStatus', width: 90 },
  { title: '表数', dataIndex: 'tableCount', key: 'tableCount', width: 70 },
  { title: '字段数', dataIndex: 'columnCount', key: 'columnCount', width: 80 },
  { title: '新增打标', dataIndex: 'hitCount', key: 'hitCount', width: 90 },
  {
    title: '开始时间',
    dataIndex: 'startTime',
    key: 'startTime',
    width: 180,
  },
  {
    title: '结束时间',
    dataIndex: 'endTime',
    key: 'endTime',
    width: 180,
  },
  { title: '操作', key: 'actions', width: 140, fixed: 'right' as const },
];

async function loadData() {
  loading.value = true;
  try {
    const result = await getScanRunPage({
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
      runStatus: runStatus.value,
    });
    rows.value = result.list ?? [];
    pagination.total = Number(result.total ?? 0);
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.pageNo = 1;
  loadData();
}

function handlePageChange(page: number, pageSize: number) {
  pagination.pageNo = page;
  pagination.pageSize = pageSize;
  loadData();
}

const [TextPreviewModal, textPreviewModalApi] = useVbenModal({
  connectedComponent: TextPreview,
  destroyOnClose: true,
});

function handleShowError(row: SafetyIdentifyApi.ScanRun) {
  textPreviewModalApi
    .setData({
      title: '错误详情',
      text: row.errorInfo ?? '',
    })
    .open();
}

const [ModalComponent] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  class: 'w-[960px]',
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      rows.value = [];
      runStatus.value = undefined;
      return;
    }
    pagination.pageNo = 1;
    await loadData();
  },
});
</script>

<template>
  <ModalComponent title="敏感数据扫描记录">
    <TextPreviewModal />
    <div class="px-2 pb-2">
      <div class="mb-3 flex items-center gap-2">
        <span class="text-sm">运行状态</span>
        <Select
          v-model:value="runStatus"
          :options="
            SCAN_STATUS_OPTIONS.map(({ label, value }) => ({ label, value }))
          "
          allow-clear
          class="w-36"
          placeholder="全部"
          @change="handleSearch"
        />
        <Button size="small" @click="loadData">
          <template #icon>
            <IconifyIcon icon="lucide:refresh-cw" />
          </template>
          刷新
        </Button>
      </div>
      <Table
        :columns="tableColumns"
        :scroll="{ x: 'max-content' }"
        :data-source="rows"
        :loading="loading"
        :pagination="{
          current: pagination.pageNo,
          pageSize: pagination.pageSize,
          total: pagination.total,
          showSizeChanger: true,
          showTotal: (total: number) => `共 ${total} 条`,
          onChange: handlePageChange,
        }"
        row-key="id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'scope'">
            <span class="font-mono text-xs">
              {{ (record as any).tableScope || '全部表' }}
            </span>
          </template>
          <template v-else-if="column.key === 'runStatus'">
            <Tag
              :color="
                optionColor(SCAN_STATUS_OPTIONS, (record as any).runStatus)
              "
            >
              {{ optionLabel(SCAN_STATUS_OPTIONS, (record as any).runStatus) }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'startTime'">
            {{ formatDateTime((record as any).startTime) || '-' }}
          </template>
          <template v-else-if="column.key === 'endTime'">
            {{ formatDateTime((record as any).endTime) || '-' }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <Button
              v-if="(record as any).errorInfo"
              danger
              size="small"
              type="link"
              @click="handleShowError(record as any)"
            >
              <template #icon>
                <IconifyIcon icon="lucide:file-warning" />
              </template>
              错误详情
            </Button>
            <span v-else>-</span>
          </template>
        </template>
      </Table>
    </div>
  </ModalComponent>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
