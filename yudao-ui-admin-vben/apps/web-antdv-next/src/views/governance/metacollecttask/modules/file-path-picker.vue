<script setup lang="ts">
import type { DataforgeDataFileApi as Api } from '#/api/dataforge/datafile';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { Alert, Button, Checkbox, message, Table } from 'antdv-next';

import { getDataFilePage } from '#/api/dataforge/datafile';
import { useObjectRequest } from '#/hooks/use-object-request';

import { metadataTime } from '../../metadata-common/helpers';
import MetadataIcon from '../../metadata-common/metadata-icon.vue';

interface PickerInput {
  connectionId: Api.Id;
  connectionName: string;
}

const emit = defineEmits<{ select: [path: string] }>();
const input = ref<PickerInput>();
const path = ref('/');
const selected = ref<string>();
const rows = ref<Api.Entry[]>([]);
const total = ref(0);
const pageNo = ref(1);
const pageSize = ref(10);
const loading = ref(false);
const failed = ref(false);
const requests = useObjectRequest(
  () => `${String(input.value?.connectionId ?? '')}/${path.value}`,
  () => {
    rows.value = [];
    selected.value = undefined;
    loading.value = false;
  },
);

async function load() {
  if (input.value?.connectionId === undefined) return;
  const current = requests.begin();
  loading.value = true;
  failed.value = false;
  selected.value = undefined;
  try {
    const result = await getDataFilePage({
      connectionId: input.value.connectionId,
      path: path.value,
      type: 'DIRECTORY',
      pageNo: pageNo.value,
      pageSize: pageSize.value,
    });
    if (current()) {
      rows.value = result.list;
      total.value = result.total;
    }
  } catch {
    if (current()) {
      rows.value = [];
      total.value = 0;
      failed.value = true;
    }
  } finally {
    if (current()) loading.value = false;
  }
}

function enter(next: string) {
  path.value = next;
  pageNo.value = 1;
  void load();
}

function parent() {
  const pieces = path.value.split('/').filter(Boolean);
  pieces.pop();
  enter(`/${pieces.join('/')}`);
}

const [Modal, modalApi] = useVbenModal({
  title: '选择目录',
  class:
    'metadata-modal top-[6vh] metadata-path-picker w-[900px] max-w-[calc(100vw-32px)]',
  fullscreenButton: false,
  confirmText: '确定',
  cancelText: '取消',
  headerClass: 'metadata-modal__header',
  contentClass: 'metadata-modal__body',
  footerClass: 'metadata-modal__footer',
  async onOpenChange(open) {
    if (!open) {
      requests.begin();
      rows.value = [];
      selected.value = undefined;
      return;
    }
    input.value = modalApi.getData() as PickerInput;
    path.value = '/';
    pageNo.value = 1;
    await load();
  },
  async onConfirm() {
    if (loading.value || !selected.value) {
      message.warning('请选择一个目录');
      return;
    }
    emit('select', selected.value);
    await modalApi.close();
  },
});
</script>

<template>
  <Modal>
    <div class="metadata-path-picker__location">
      <Button type="link" :disabled="path === '/' || loading" @click="parent">
        <MetadataIcon name="back" />返回
      </Button>
      <span>{{ input?.connectionName }}{{ path === '/' ? '' : path }}</span>
    </div>
    <Alert v-if="failed" type="error" message="目录读取失败，请重试" show-icon>
      <template #action>
        <Button size="small" @click="load">重试</Button>
      </template>
    </Alert>
    <Table
      size="small"
      :loading="loading"
      :data-source="rows"
      row-key="path"
      :columns="[
        { key: 'checked', width: 52 },
        { title: '目录名', dataIndex: 'name' },
        { title: '更新时间', dataIndex: 'updateTime', width: '46%' },
      ]"
      :pagination="{
        current: pageNo,
        pageSize,
        total,
        showSizeChanger: true,
        showQuickJumper: true,
        pageSizeOptions: ['10', '20', '50'],
        showTotal: (count: number) => `共 ${count} 条`,
        onChange: (page: number, size: number) => {
          pageNo = page;
          pageSize = size;
          void load();
        },
      }"
    >
      <template #bodyCell="{ column, record }">
        <Checkbox
          v-if="column.key === 'checked'"
          :checked="selected === record.path"
          :aria-label="`选择${record.name}`"
          @change="
            selected = selected === record.path ? undefined : record.path
          "
        />
        <button
          v-else-if="column.dataIndex === 'name'"
          type="button"
          class="metadata-link metadata-path-picker__directory"
          @click="enter(record.path)"
        >
          <MetadataIcon name="directory" :size="18" />{{ record.name }}
        </button>
        <template v-else-if="column.dataIndex === 'updateTime'">
          {{ metadataTime(record.updateTime, 'YYYY-MM-DD HH:mm') }}
        </template>
      </template>
    </Table>
  </Modal>
</template>

<style scoped>
.metadata-path-picker__location {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  min-height: 30px;
  margin-bottom: 12px;
}

.metadata-path-picker__directory {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  max-width: 100%;
  text-align: left;
  overflow-wrap: anywhere;
}

@media (max-width: 600px) {
  .metadata-path-picker__location {
    align-items: flex-start;
    overflow-wrap: anywhere;
  }
}
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
