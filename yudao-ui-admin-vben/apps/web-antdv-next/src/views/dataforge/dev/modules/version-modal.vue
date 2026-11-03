<script lang="ts" setup>
import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { h, onBeforeUnmount, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Button, Drawer, message, Modal, Table, Tag } from 'antdv-next';

import {
  getDevTask,
  getDevTaskVersionList,
  rollbackDevTaskVersion,
} from '#/api/dataforge/dev';
import SqlEditor from '#/components/monaco/sql-editor.vue';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';

const props = defineProps<{ canEdit?: boolean }>();
const emit = defineEmits<{ changed: [] }>();
const { hasAccessByCodes } = useAccess();
const accessStore = useAccessStore();

const visible = ref(false);
const loading = ref(false);
const rollingBack = ref(false);
const taskId = ref<DataforgeDevApi.Id>();
let loadSeq = 0;
const revision = ref<number>();
const currentVersionId = ref<DataforgeDevApi.Id>();
const versions = ref<DataforgeDevApi.TaskVersion[]>([]);
const previewVersion = ref<DataforgeDevApi.TaskVersion>();
let confirmation: ReturnType<typeof Modal.confirm> | undefined;

function close() {
  ++loadSeq;
  visible.value = false;
  loading.value = false;
  rollingBack.value = false;
  taskId.value = undefined;
  revision.value = undefined;
  currentVersionId.value = undefined;
  versions.value = [];
  previewVersion.value = undefined;
  confirmation?.destroy();
  confirmation = undefined;
}

onBeforeUnmount(close);
watch(() => [accessStore.accessToken, accessStore.refreshToken], close, {
  flush: 'sync',
});

const columns = [
  { title: '版本', dataIndex: 'version', key: 'version', width: 80 },
  {
    title: '备注',
    dataIndex: 'remark',
    key: 'remark',
    minWidth: 140,
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
  { title: '保存时间', key: 'createTime', width: 165 },
  { title: '操作', key: 'actions', width: 180, fixed: 'right' as const },
];

async function open(id: DataforgeDevApi.Id) {
  confirmation?.destroy();
  confirmation = undefined;
  taskId.value = id;
  rollingBack.value = false;
  previewVersion.value = undefined;
  visible.value = true;
  await loadData();
}

async function loadData() {
  const requestedTaskId = taskId.value;
  if (requestedTaskId === undefined || !visible.value) return false;
  const seq = ++loadSeq;
  loading.value = true;
  revision.value = undefined;
  currentVersionId.value = undefined;
  try {
    const [list, task] = await Promise.all([
      getDevTaskVersionList(requestedTaskId),
      getDevTask(requestedTaskId),
    ]);
    if (seq !== loadSeq || !visible.value) return false;
    versions.value = list;
    revision.value = task.revision;
    currentVersionId.value = task.currentVersionId ?? undefined;
    return true;
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

function handleRollback(version: DataforgeDevApi.TaskVersion) {
  if (
    loading.value ||
    rollingBack.value ||
    !visible.value ||
    !props.canEdit ||
    !hasAccessByCodes(['dataforge:dev-task:save']) ||
    revision.value === undefined ||
    taskId.value === undefined
  )
    return;
  const confirmedTaskId = taskId.value;
  const confirmedVersionId = version.id;
  const expectedRevision = revision.value;
  const confirmationSeq = loadSeq;
  function isCurrentConfirmation() {
    return (
      confirmationSeq === loadSeq &&
      visible.value &&
      String(taskId.value) === String(confirmedTaskId)
    );
  }
  confirmation?.destroy();
  confirmation = Modal.confirm({
    title: '系统提示',
    content: `确认回滚到版本 v${version.version} 吗？回滚会生成新当前版本并立即生效，历史版本与运行记录保留。`,
    onOk: async () => {
      if (
        !isCurrentConfirmation() ||
        rollingBack.value ||
        !props.canEdit ||
        !hasAccessByCodes(['dataforge:dev-task:save'])
      )
        return;
      rollingBack.value = true;
      let refreshSeq: number | undefined;
      try {
        await rollbackDevTaskVersion(confirmedVersionId, expectedRevision);
        if (!isCurrentConfirmation()) return;
        message.success('回滚成功');
        const refreshing = loadData();
        refreshSeq = loadSeq;
        if (await refreshing) emit('changed');
      } finally {
        if (
          visible.value &&
          (loadSeq === confirmationSeq || loadSeq === refreshSeq) &&
          String(taskId.value) === String(confirmedTaskId)
        )
          rollingBack.value = false;
      }
    },
  });
}

defineExpose({ open, close });
</script>

<template>
  <Drawer
    v-model:open="visible"
    :width="720"
    destroy-on-hidden
    title="版本信息"
    @close="close"
  >
    <Table
      :scroll="{ x: 'max-content' }"
      :columns="columns"
      :data-source="versions"
      :loading="loading"
      :pagination="false"
      row-key="id"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'version'">
          <Tag
            :color="
              String(record.id) === String(currentVersionId)
                ? 'success'
                : 'default'
            "
          >
            v{{ (record as any).version
            }}{{
              String(record.id) === String(currentVersionId)
                ? '（当前版本）'
                : ''
            }}
          </Tag>
        </template>
        <template v-else-if="column.key === 'createTime'">
          {{ formatDateTime((record as any).createTime) || '-' }}
        </template>
        <template v-else-if="column.key === 'actions'">
          <Button
            size="small"
            type="link"
            @click="previewVersion = record as any"
          >
            <template #icon><IconifyIcon icon="lucide:eye" /></template>
            查看
          </Button>
          <Button
            v-if="canEdit"
            v-access:code="'dataforge:dev-task:save'"
            :disabled="loading || rollingBack || revision === undefined"
            size="small"
            type="link"
            @click="handleRollback(record as any)"
          >
            <template #icon><IconifyIcon icon="lucide:rotate-ccw" /></template>
            回滚
          </Button>
        </template>
      </template>
    </Table>

    <template v-if="previewVersion">
      <div class="mb-1 mt-4 text-sm font-semibold">
        版本 v{{ previewVersion.version }} 脚本内容
      </div>
      <div class="overflow-hidden rounded border" style="height: 320px">
        <SqlEditor
          :model-value="previewVersion.content ?? ''"
          height="320px"
          readonly
        />
      </div>
    </template>
  </Drawer>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
