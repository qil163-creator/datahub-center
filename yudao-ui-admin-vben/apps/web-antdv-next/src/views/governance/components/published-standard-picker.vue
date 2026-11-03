<script lang="ts" setup>
import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';

import { h, reactive, ref } from 'vue';

import { Alert, Button, Input, Modal, Table, Tag } from 'antdv-next';

import { getStandardElementPage } from '#/api/governance/standardelement';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { useObjectRequest } from '#/hooks/use-object-request';

defineOptions({ name: 'GovernancePublishedStandardPicker' });

const emit = defineEmits<{
  picked: [standard: GovernanceStandardElementApi.StandardElement];
}>();
const visible = ref(false);
const loading = ref(false);
const error = ref(false);
const rows = ref<GovernanceStandardElementApi.StandardElement[]>([]);
const selectedId = ref<number>();
const name = ref('');
const pagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const request = useObjectRequest(() => visible.value, reset);
const columns = [
  { title: '编码', dataIndex: 'code', key: 'code', width: 130 },
  { title: '标准名称', dataIndex: 'name', key: 'name' },
  { title: '英文名称', dataIndex: 'engName', key: 'engName' },
  { title: '数据类型', dataIndex: 'columnType', key: 'columnType', width: 110 },
  {
    title: '业务含义',
    dataIndex: 'description',
    key: 'description',
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
];

function reset() {
  rows.value = [];
  selectedId.value = undefined;
  loading.value = false;
  error.value = false;
}

async function loadData() {
  if (!visible.value) return;
  const current = request.begin();
  loading.value = true;
  error.value = false;
  selectedId.value = undefined;
  try {
    const result = await getStandardElementPage({
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
      name: name.value.trim() || undefined,
      lifecycleStatus: 'PUBLISHED',
      status: 0,
    });
    if (!current()) return;
    rows.value = (result.list ?? []).filter(
      (row) => row.lifecycleStatus === 'PUBLISHED' && row.status === 0,
    );
    pagination.total = result.total;
  } catch {
    if (current()) {
      rows.value = [];
      error.value = true;
    }
  } finally {
    if (current()) loading.value = false;
  }
}

function search() {
  pagination.pageNo = 1;
  void loadData();
}

function changePage(page: number, size: number) {
  pagination.pageNo = page;
  pagination.pageSize = size;
  void loadData();
}

function open() {
  name.value = '';
  pagination.pageNo = 1;
  pagination.total = 0;
  visible.value = true;
  void loadData();
}

function close() {
  visible.value = false;
}

function handleOk() {
  const standard = rows.value.find((row) => row.id === selectedId.value);
  if (!standard || loading.value || error.value) return;
  emit('picked', standard);
  close();
}

defineExpose({ close, open });
</script>

<template>
  <Modal
    v-model:open="visible"
    :ok-button-props="{
      disabled: selectedId === undefined || loading || error,
    }"
    ok-text="选择标准"
    title="选择已发布标准"
    width="900px"
    @ok="handleOk"
  >
    <div class="mb-3 flex items-center gap-2">
      <Input
        v-model:value="name"
        allow-clear
        placeholder="按标准名称搜索"
        @press-enter="search"
      />
      <Button type="primary" @click="search">查询</Button>
      <Tag color="success">已发布且启用</Tag>
    </div>
    <Alert
      v-if="error"
      class="mb-3"
      message="标准读取失败，请重新查询。"
      show-icon
      type="error"
    />
    <Table
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="{
        current: pagination.pageNo,
        pageSize: pagination.pageSize,
        total: pagination.total,
        onChange: changePage,
      }"
      :row-selection="{
        type: 'radio',
        selectedRowKeys: selectedId === undefined ? [] : [selectedId],
        onChange: (keys: (number | string)[]) => {
          selectedId = rows.find(
            (row) => String(row.id) === String(keys[0]),
          )?.id;
        },
      }"
      :scroll="{ y: 380 }"
      row-key="id"
      size="small"
    />
  </Modal>
</template>
