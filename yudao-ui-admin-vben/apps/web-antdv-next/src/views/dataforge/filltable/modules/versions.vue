<script lang="ts" setup>
import type { DataforgeFillApi } from '#/api/dataforge/fill';
import type { DataforgeFillTableApi } from '#/api/dataforge/filltable';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import {
  Button,
  message,
  Modal,
  Select,
  Table,
  Tag,
  Tooltip,
} from 'antdv-next';

import {
  disableTableVersion,
  getTableVersionListByTable,
  publishTableVersion,
} from '#/api/dataforge/fill';
import { getFillTableColumnList } from '#/api/dataforge/filltable';

defineOptions({ name: 'DataforgeFillTableVersions' });

const MERGE_STRATEGY_OPTIONS = [
  { label: 'APPEND（每次上报新增历史记录）', value: 'APPEND' },
  { label: 'UPSERT（按业务键新增或更新）', value: 'UPSERT' },
  { label: 'REPLACE_SCOPE（按组织+期次整体替换）', value: 'REPLACE_SCOPE' },
];

const fillTable = ref<DataforgeFillTableApi.FillTable>();
const versions = ref<DataforgeFillApi.TableVersion[]>([]);
const loading = ref(false);
const publishing = ref(false);
const mergeStrategy = ref('UPSERT');
const businessKeyFields = ref<string[]>([]);
const fieldOptions = ref<{ label: string; value: string }[]>([]);

const tableColumns = [
  { key: 'versionNo', title: '版本', width: 70 },
  {
    dataIndex: 'mergeStrategy',
    key: 'mergeStrategy',
    title: '合并策略',
    width: 130,
  },
  {
    dataIndex: 'businessKeyFields',
    key: 'businessKeyFields',
    title: '业务键',
    width: 160,
  },
  { key: 'status', title: '状态', width: 90 },
  { key: 'contentHash', title: '内容摘要', width: 110 },
  {
    dataIndex: 'releaseTime',
    key: 'releaseTime',
    title: '发布时间',
    width: 165,
  },
  { key: 'actions', title: '操作', width: 100, fixed: 'right' as const },
];

async function loadData() {
  if (!fillTable.value?.id) return;
  loading.value = true;
  try {
    const [versionList, columnList] = await Promise.all([
      getTableVersionListByTable(fillTable.value.id),
      getFillTableColumnList(fillTable.value.id),
    ]);
    versions.value = versionList;
    fieldOptions.value = columnList
      .filter((column) => column.fieldCode)
      .map((column) => ({
        label: `${column.cnName}（${column.fieldCode}）`,
        value: column.fieldCode!,
      }));
  } finally {
    loading.value = false;
  }
}

async function handlePublish() {
  if (!fillTable.value?.id) return;
  if (
    mergeStrategy.value === 'UPSERT' &&
    businessKeyFields.value.length === 0
  ) {
    message.warning('UPSERT 策略必须选择业务键字段');
    return;
  }
  publishing.value = true;
  try {
    const versionId = await publishTableVersion({
      businessKeyFields:
        mergeStrategy.value === 'UPSERT' ? businessKeyFields.value : undefined,
      mergeStrategy: mergeStrategy.value,
      tableId: fillTable.value.id,
    });
    message.success(`版本已发布（编号 ${versionId}），模板进入审批流工作模式`);
    await loadData();
  } finally {
    publishing.value = false;
  }
}

function handleDisable(version: DataforgeFillApi.TableVersion) {
  Modal.confirm({
    content: '停用后新计划不能再引用该版本；进行中的任务不受影响。',
    onOk: async () => {
      await disableTableVersion(version.id);
      message.success('已停用');
      await loadData();
    },
    title: `停用版本 V${version.versionNo}`,
  });
}

const getTitle = computed(() =>
  fillTable.value
    ? `模板版本 - ${fillTable.value.tableCnName ?? fillTable.value.tableEngName}`
    : '模板版本',
);

const [ModalComponent, modalApi] = useVbenModal({
  class: 'w-[900px]',
  showCancelButton: false,
  showConfirmButton: false,
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      versions.value = [];
      businessKeyFields.value = [];
      return;
    }
    fillTable.value = modalApi.getData() as DataforgeFillTableApi.FillTable;
    await loadData();
  },
});
</script>

<template>
  <ModalComponent :title="getTitle">
    <div class="px-2 pb-2">
      <div class="mb-3 rounded-md border p-3">
        <div class="mb-2 text-sm font-medium">发布新版本</div>
        <p class="text-muted-foreground mb-2 text-xs">
          从当前字段配置固化不可变版本（字段结构、校验规则、目标映射、合并策略）。
          采集计划只能引用已发布版本；进行中的任务始终使用其绑定版本，不受新版本影响。
        </p>
        <div class="flex items-center gap-2">
          <Select
            v-model:value="mergeStrategy"
            :options="MERGE_STRATEGY_OPTIONS"
            style="width: 300px"
          />
          <Select
            v-if="mergeStrategy === 'UPSERT'"
            v-model:value="businessKeyFields"
            :options="fieldOptions"
            class="flex-1"
            mode="multiple"
            placeholder="选择业务键字段（含组织/期次维度）"
          />
          <Button :loading="publishing" type="primary" @click="handlePublish">
            <template #icon><IconifyIcon icon="lucide:send" /></template>
            发布版本
          </Button>
        </div>
      </div>

      <Table
        :columns="tableColumns"
        :data-source="versions"
        :loading="loading"
        :pagination="false"
        :scroll="{ x: 'max-content', y: 360 }"
        row-key="id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'versionNo'"
            >V{{ record.versionNo }}</template
          >
          <template v-else-if="column.key === 'status'">
            <Tag :color="record.status === 1 ? 'success' : 'default'">
              {{ record.status === 1 ? '已发布' : '已停用' }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'contentHash'">
            <Tooltip :title="record.contentHash">
              <span class="font-mono text-xs"
                >{{ record.contentHash?.slice(0, 8) }}…</span
              >
            </Tooltip>
          </template>
          <template v-else-if="column.key === 'actions'">
            <Button
              v-if="record.status === 1"
              danger
              size="small"
              type="link"
              @click="handleDisable(record as any)"
            >
              <template #icon><IconifyIcon icon="lucide:pause" /></template>
              停用
            </Button>
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
