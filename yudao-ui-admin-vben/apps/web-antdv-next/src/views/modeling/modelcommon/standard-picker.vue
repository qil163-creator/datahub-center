<script lang="ts" setup>
import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';

import { reactive, ref } from 'vue';

import { Button, Input, Modal, Table, Tag } from 'antdv-next';

import { getStandardElementPage } from '#/api/governance/standardelement';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';

defineOptions({ name: 'ModelingStandardPicker' });

const emit = defineEmits<{
  (e: 'picked', standard: GovernanceStandardElementApi.StandardElement): void;
}>();

const visible = ref(false);
const loading = ref(false);
const rows = ref<GovernanceStandardElementApi.StandardElement[]>([]);
const pagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const query = reactive<{ name?: string }>({});
const selectedId = ref<number | string>();

const columns = [
  { title: '编码', dataIndex: 'code', key: 'code', width: 110 },
  { title: '中文名称', dataIndex: 'name', key: 'name', minWidth: 130 },
  { title: '英文名称', dataIndex: 'engName', key: 'engName', minWidth: 130 },
  { title: '数据类型', dataIndex: 'columnType', key: 'columnType', width: 100 },
  { title: '状态', key: 'status', width: 80 },
  {
    title: '业务含义',
    dataIndex: 'description',
    key: 'description',
    minWidth: 120,
  },
];

function open() {
  selectedId.value = undefined;
  visible.value = true;
  loadData();
}

defineExpose({ open });

async function loadData() {
  loading.value = true;
  try {
    const result = await getStandardElementPage({
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
      name: query.name,
    } as any);
    rows.value = result.list ?? [];
    pagination.total = Number(result.total ?? 0);
  } finally {
    loading.value = false;
  }
}

function handleOk() {
  const picked = rows.value.find((row) => row.id === selectedId.value);
  if (picked) {
    emit('picked', picked);
  }
  visible.value = false;
}
</script>

<template>
  <Modal
    v-model:open="visible"
    :ok-button-props="{ disabled: !selectedId }"
    ok-text="保存"
    title="关联标准"
    width="860px"
    @ok="handleOk"
  >
    <div class="mb-2 flex justify-end gap-2">
      <Input
        v-model:value="query.name"
        allow-clear
        class="w-48"
        placeholder="名称"
        @press-enter="loadData"
      />
      <Button type="primary" @click="loadData">查询</Button>
    </div>
    <Table
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="{
        current: pagination.pageNo,
        pageSize: pagination.pageSize,
        total: pagination.total,
        onChange: (page: number, size: number) => {
          pagination.pageNo = page;
          pagination.pageSize = size;
          loadData();
        },
      }"
      :row-selection="{
        type: 'radio',
        selectedRowKeys: selectedId != null ? [selectedId] : [],
        onChange: (keys: any[]) => (selectedId = keys[0]),
      }"
      :scroll="{ y: 380 }"
      row-key="id"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'status'">
          <Tag :color="(record as any).status === 0 ? 'success' : 'default'">
            {{ (record as any).status === 0 ? '已发布' : '停用' }}
          </Tag>
        </template>
        <TextEllipsisCell
          v-else-if="column.key === 'description'"
          :content="record.description"
          :line="2"
          center
        />
      </template>
    </Table>
  </Modal>
</template>
