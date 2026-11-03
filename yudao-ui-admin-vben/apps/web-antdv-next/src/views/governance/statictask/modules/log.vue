<script lang="ts" setup>
import type { SafetyStaticTaskApi } from '#/api/governance/statictask';

import { computed, reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { formatDateTime } from '@vben/utils';

import { Button, Select, Table, Tag } from 'antdv-next';

import { getSafetyStaticTaskLogPage } from '#/api/governance/statictask';
import TextPreview from '#/views/governance/common/text-preview.vue';

import {
  optionColor,
  optionLabel,
  RUN_STATUS_OPTIONS,
  RUN_TYPE_OPTIONS,
} from '../data';

defineOptions({ name: 'SafetyStaticTaskLog' });

const task = ref<SafetyStaticTaskApi.Task>();
const rows = ref<SafetyStaticTaskApi.TaskLog[]>([]);
const loading = ref(false);
const pagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const runStatus = ref<number>();

const tableColumns = [
  { title: '编号', dataIndex: 'id', key: 'id', width: 70 },
  { title: '触发方式', key: 'runType', width: 100 },
  { title: '运行状态', key: 'runStatus', width: 90 },
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
  { title: '处理行数', dataIndex: 'dataCount', key: 'dataCount', width: 90 },
  {
    title: '脱敏字段数',
    dataIndex: 'maskedColumnCount',
    key: 'maskedColumnCount',
    width: 100,
  },
  { title: '操作', key: 'actions', width: 100, fixed: 'right' as const },
];

async function loadData() {
  if (!task.value?.id) {
    return;
  }
  loading.value = true;
  try {
    const result = await getSafetyStaticTaskLogPage({
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
      taskId: task.value.id,
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

function handleShowError(row: SafetyStaticTaskApi.TaskLog) {
  textPreviewModalApi
    .setData({
      title: '错误详情',
      text: row.errorInfo ?? '',
    })
    .open();
}

const getTitle = computed(() =>
  task.value ? `执行记录 - ${task.value.taskName}` : '执行记录',
);

const [ModalComponent, modalApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  class: 'w-[960px]',
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      rows.value = [];
      runStatus.value = undefined;
      return;
    }
    task.value = modalApi.getData() as SafetyStaticTaskApi.Task;
    pagination.pageNo = 1;
    await loadData();
  },
});
</script>

<template>
  <ModalComponent :title="getTitle">
    <TextPreviewModal />
    <div class="px-2 pb-2">
      <div class="mb-3 flex items-center gap-2">
        <span class="text-sm">运行状态</span>
        <Select
          v-model:value="runStatus"
          :options="
            RUN_STATUS_OPTIONS.map(({ label, value }) => ({ label, value }))
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
          <template v-if="column.key === 'runType'">
            {{ optionLabel(RUN_TYPE_OPTIONS, (record as any).runType) }}
          </template>
          <template v-else-if="column.key === 'runStatus'">
            <Tag
              :color="
                optionColor(RUN_STATUS_OPTIONS, (record as any).runStatus)
              "
            >
              {{ optionLabel(RUN_STATUS_OPTIONS, (record as any).runStatus) }}
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
