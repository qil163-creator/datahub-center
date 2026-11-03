<script setup lang="ts">
import type { StandardDocApi } from '#/api/governance/doc';

import { computed, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Alert, Button, Input, message, Table } from 'antdv-next';

import { getDocVersions, publishDocVersion } from '#/api/governance/doc';
import { useObjectRequest } from '#/hooks/use-object-request';

const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const canPublish = computed(() => hasAccessByCodes(['governance:doc:update']));
const document = ref<{ id: StandardDocApi.Id; name?: string }>();
const versions = ref<StandardDocApi.DocVersion[]>([]);
const loading = ref(false);
const failed = ref(false);
const publishing = ref(false);
const changeSummary = ref('');
const snapshot = ref('');
const identity = ref('closed');
const scope = () => identity.value + access.accessCodes.join(',');
const requests = useObjectRequest(scope, () => {
  versions.value = [];
  loading.value = false;
  snapshot.value = '';
});
const mutation = useObjectRequest(scope, () => {
  publishing.value = false;
});
let sequence = 0;
const columns = [
  { dataIndex: 'version', title: '快照版本', width: 100 },
  { dataIndex: 'changeSummary', title: '变更说明' },
  { dataIndex: 'creator', title: '操作人', width: 130 },
  { key: 'createTime', title: '生成时间', width: 180 },
  { key: 'action', title: '操作', width: 120, fixed: 'right' as const },
];
async function load() {
  if (!document.value || !hasAccessByCodes(['governance:doc:query'])) return;
  const current = requests.begin();
  loading.value = true;
  failed.value = false;
  try {
    const result = await getDocVersions(document.value.id);
    if (current()) versions.value = result;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
async function publish() {
  if (!document.value || publishing.value || !canPublish.value) return;
  const current = mutation.begin();
  publishing.value = true;
  modalApi.lock();
  try {
    const version = await publishDocVersion(
      document.value.id,
      changeSummary.value || undefined,
    );
    if (!current()) return;
    message.success(`已生成版本 v${version}`);
    changeSummary.value = '';
    await load();
  } finally {
    if (current()) {
      publishing.value = false;
      modalApi.unlock();
    }
  }
}
function showSnapshot(value: null | string) {
  try {
    snapshot.value = JSON.stringify(JSON.parse(value || '{}'), null, 2);
  } catch {
    snapshot.value = value || '{}';
  }
}
const [Modal, modalApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(open) {
    identity.value = open ? String(++sequence) : 'closed';
    changeSummary.value = '';
    snapshot.value = '';
    failed.value = false;
    if (!open) {
      document.value = undefined;
      modalApi.unlock();
      return;
    }
    document.value = modalApi.getData() as {
      id: StandardDocApi.Id;
      name?: string;
    };
    await load();
  },
});
watch(
  () => [access.accessToken, access.refreshToken, access.accessCodes.join(',')],
  () => modalApi.close(),
);
</script>
<template>
  <Modal
    :title="`标准版本快照${document?.name ? `：${document.name}` : ''}`"
    class="w-[960px]"
  >
    <div class="flex flex-col gap-3">
      <div v-if="canPublish" class="flex gap-2">
        <Input
          v-model:value="changeSummary"
          placeholder="变更说明（可选）"
          :maxlength="500"
          @press-enter="publish"
        />
        <Button type="primary" :loading="publishing" @click="publish">
          生成新版本
        </Button>
      </div>
      <Alert v-if="failed" type="error" message="版本快照加载失败">
        <template #action><Button @click="load">重试</Button></template>
      </Alert>
      <Table
        :columns="columns"
        :data-source="versions"
        :loading="loading"
        :pagination="false"
        :scroll="{ x: 'max-content' }"
        row-key="id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'createTime'">
            {{ formatDateTime(record.createTime) || '-' }}
          </template>
          <Button
            v-else-if="column.key === 'action'"
            type="link"
            @click="showSnapshot(record.snapshot)"
          >
            查看快照
          </Button>
        </template>
      </Table>
      <pre
        v-if="snapshot"
        class="bg-muted max-h-[45vh] overflow-auto rounded p-3 text-xs"
        >{{ snapshot }}</pre>
    </div>
  </Modal>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
