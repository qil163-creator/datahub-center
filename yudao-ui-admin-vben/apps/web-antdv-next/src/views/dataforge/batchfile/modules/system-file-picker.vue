<script setup lang="ts">
import type { DataforgeDataFileApi as Api } from '#/api/dataforge/datafile';

import { computed, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';

import { Alert, Button, Checkbox, Modal, Select, Spin } from 'antdv-next';

import { getDataFilePage, getFileConnections } from '#/api/dataforge/datafile';
import { useObjectRequest } from '#/hooks/use-object-request';
import { formatSize, parentPath } from '#/views/dataforge/datafile/data';

import { useBatchScope } from '../use-scope';
import EmptyState from './empty-state.vue';
import Pagination from './pagination.vue';

export interface SelectedSystemFile {
  connectionId: Api.Id;
  entry: Api.Entry;
}
const emit = defineEmits<{ select: [files: SelectedSystemFile[]] }>();
const open = defineModel<boolean>('open', { required: true });
const { hasAccessByCodes } = useAccess();
const allowed = computed(
  () =>
    hasAccessByCodes(['dataforge:data-file:query']) &&
    hasAccessByCodes(['dataforge:data-file:download']),
);
const connections = ref<Api.Connection[]>([]);
const connectionId = ref<Api.Id>();
const path = ref('/');
const rows = ref<Api.Entry[]>([]);
const selected = ref<Api.Entry[]>([]);
const page = ref(1);
const pageSize = ref(10);
const total = ref(0);
const loading = ref(false);
const failed = ref(false);
const { active, scope } = useBatchScope(() => open.value);
const request = useObjectRequest(() => scope.value, clear);
const listRequest = useObjectRequest(
  () => JSON.stringify([scope.value, connectionId.value, path.value]),
  () => {
    rows.value = [];
    selected.value = [];
    total.value = 0;
    loading.value = false;
  },
);
function clear() {
  connections.value = [];
  connectionId.value = undefined;
  rows.value = [];
  selected.value = [];
  total.value = 0;
  page.value = 1;
  path.value = '/';
  loading.value = false;
  failed.value = false;
}
async function load() {
  if (
    !open.value ||
    !active.value ||
    !allowed.value ||
    connectionId.value === undefined
  )
    return;
  const current = listRequest.begin();
  loading.value = true;
  failed.value = false;
  selected.value = [];
  try {
    const result = await getDataFilePage({
      connectionId: connectionId.value,
      path: path.value,
      pageNo: page.value,
      pageSize: pageSize.value,
    });
    if (current()) {
      rows.value = result.list;
      total.value = result.total;
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
async function start() {
  clear();
  if (!open.value || !active.value || !allowed.value) return;
  const current = request.begin();
  loading.value = true;
  try {
    const result = await getFileConnections();
    if (!current()) return;
    connections.value = result;
    connectionId.value = result[0]?.id;
    await load();
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
function enter(location: string) {
  path.value = location;
  page.value = 1;
  void load();
}
function chooseConnection(value: unknown) {
  if (typeof value === 'number' || typeof value === 'string') {
    connectionId.value = value;
    enter('/');
  }
}
function choose(row: Api.Entry, checked: boolean) {
  selected.value = checked
    ? [...selected.value.filter((entry) => entry.path !== row.path), row]
    : selected.value.filter((entry) => entry.path !== row.path);
}
function confirm() {
  if (connectionId.value === undefined || loading.value || !allowed.value)
    return;
  const id = connectionId.value;
  emit(
    'select',
    selected.value.map((entry) => ({ connectionId: id, entry })),
  );
  open.value = false;
}
watch(
  scope,
  () => {
    void start();
  },
  { immediate: true },
);
</script>
<template>
  <Modal
    v-model:open="open"
    title="选择系统文件"
    width="1000px"
    :destroy-on-close="true"
    :ok-button-props="{ disabled: !selected.length || loading || !allowed }"
    @ok="confirm"
  >
    <div class="qdata-batch-file bf-modal-body">
      <Alert
        v-if="!allowed"
        type="warning"
        title="需要文件查询与下载权限才能选择系统文件"
        show-icon
      />
      <Alert
        v-if="failed"
        class="bf-error"
        type="error"
        title="系统文件加载失败，请重试"
        show-icon
        >
<template #action>
<Button @click="start"><template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>重试</Button>
</template>
</Alert>
      <div class="bf-system-path">
        <Select
          :value="connectionId"
          :options="
            connections.map((row) => ({ value: row.id, label: row.name }))
          "
          placeholder="请选择文件连接"
          aria-label="文件连接"
          @update:value="chooseConnection"
        /><Button
          :disabled="path === '/' || loading"
          @click="enter(parentPath(path))"
          >
上一级
</Button><span>{{ path }}</span>
      </div>
      <Spin :spinning="loading">
<div class="bf-table-wrap">
          <table class="bf-table">
            <thead>
              <tr>
                <th></th>
                <th>文件名称</th>
                <th>文件类型</th>
                <th>文件大小</th>
                <th>所在路径</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.path">
                <td>
                  <Checkbox
                    v-if="!row.directory"
                    :disabled="!/\.(csv|xlsx?)$/iu.test(row.name)"
                    :checked="selected.some((entry) => entry.path === row.path)"
                    :aria-label="`选择${row.name}`"
                    @change="choose(row, $event.target.checked)"
                  />
                </td>
                <td>
                  <button
                    v-if="row.directory"
                    class="bf-link"
                    @click="enter(row.path)"
                  >
                    ▱ {{ row.name }}
</button><span v-else>{{ row.name }}</span>
                </td>
                <td>{{ row.directory ? '文件夹' : row.type }}</td>
                <td>{{ row.directory ? '-' : formatSize(row.size) }}</td>
                <td>{{ row.path }}</td>
              </tr>
            </tbody>
          </table>
          <EmptyState v-if="!rows.length && !loading" />
</div>
</Spin>
      <Pagination
        v-model:page="page"
        v-model:page-size="pageSize"
        :total="total"
        @change="load"
      />
    </div>
  </Modal>
</template>
