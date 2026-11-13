<script lang="ts" setup>
import type { DataExportApi } from '#/api/asset/dataexport';

import { computed, onBeforeUnmount, reactive, ref } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { downloadFileFromBlobPart } from '@vben/utils';

import {
  Alert,
  Button,
  Drawer,
  InputNumber,
  message,
  Modal,
  Table,
  Tag,
  TextArea,
} from 'antdv-next';

import {
  cancelDataExport,
  downloadDataExport,
  getMyDataExportPage,
  submitDataExport,
} from '#/api/asset/dataexport';
import { createLatestWins } from '#/utils/resource-id';
import { formatTime } from '#/views/governance/accessalert/data';

import { canDownload, exportFormError, exportStatusView } from './export-data';

/** 数据查询结果导出：提交审批 → 批准后 24 小时内下载一次（文件经导出场景脱敏并带水印）。 */
defineOptions({ name: 'DataQueryExportPanel' });

const props = defineProps<{
  datasourceId?: number | string;
  limit: number;
  sqlProvider: () => string;
}>();

const { hasAccessByCodes } = useAccess();
const canCreate = computed(() =>
  hasAccessByCodes(['asset:data-export:create']),
);
const canQuery = computed(() => hasAccessByCodes(['asset:data-export:query']));

const applyOpen = ref(false);
const submitting = ref(false);
const form = reactive({ sql: '', reason: '', limit: 1000 });
const formError = ref<string>();

function openApply() {
  if (props.datasourceId === undefined) {
    message.warning('请先选择数据源');
    return;
  }
  form.sql = props.sqlProvider().trim();
  form.reason = '';
  form.limit = Math.min(1000, Math.max(1, props.limit || 1000));
  formError.value = exportFormError(form.sql, 'x', form.limit);
  applyOpen.value = true;
}

async function submit() {
  formError.value = exportFormError(form.sql, form.reason, form.limit);
  if (formError.value || submitting.value || props.datasourceId === undefined)
    return;
  submitting.value = true;
  try {
    await submitDataExport({
      datasourceId: props.datasourceId,
      sql: form.sql,
      limit: form.limit,
      reason: form.reason.trim(),
    });
    message.success('已提交导出申请，审批通过后可在“我的导出”中下载');
    applyOpen.value = false;
  } finally {
    submitting.value = false;
  }
}

const listOpen = ref(false);
const loading = ref(false);
const rows = ref<DataExportApi.Request[]>([]);
const total = ref(0);
const page = reactive({ pageNo: 1, pageSize: 10 });
const guard = createLatestWins();
const busy = reactive(new Set<string>());

const columns = [
  { title: '目标表', dataIndex: 'tableName', key: 'tableName', ellipsis: true },
  { title: '行数', dataIndex: 'rowLimit', key: 'rowLimit', width: 70 },
  { title: '状态', key: 'status', width: 130 },
  { title: '下载截止', key: 'expireTime', width: 150 },
  { title: '操作', key: 'actions', width: 120 },
];

async function load() {
  const ticket = guard.next();
  loading.value = true;
  try {
    const result = await getMyDataExportPage({
      pageNo: page.pageNo,
      pageSize: page.pageSize,
    });
    if (!ticket.isCurrent()) return;
    rows.value = result?.list ?? [];
    total.value = result?.total ?? 0;
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}

function openList() {
  listOpen.value = true;
  page.pageNo = 1;
  void load();
}

async function download(record: DataExportApi.Request) {
  const key = String(record.id);
  if (busy.has(key)) return;
  busy.add(key);
  try {
    const data = await downloadDataExport(record.id);
    downloadFileFromBlobPart({
      fileName: `数据导出_${key}.xlsx`,
      source: data,
    });
    message.success('已下载；该申请的下载已失效');
  } finally {
    busy.delete(key);
    await load();
  }
}

async function cancel(record: DataExportApi.Request) {
  const key = String(record.id);
  if (busy.has(key)) return;
  busy.add(key);
  try {
    await cancelDataExport(record.id);
    message.success('已撤回');
  } finally {
    busy.delete(key);
    await load();
  }
}

function changePage(pageNo: number, pageSize: number) {
  page.pageNo = pageNo;
  page.pageSize = pageSize;
  void load();
}

onBeforeUnmount(() => guard.invalidate());
</script>

<template>
  <Button v-if="canCreate" @click="openApply">
    <template #icon><IconifyIcon icon="carbon:document-export" /></template>
    申请导出
  </Button>
  <Button v-if="canQuery" @click="openList">
    <template #icon><IconifyIcon icon="carbon:list-checked" /></template>
    我的导出
  </Button>

  <Modal
    :confirm-loading="submitting"
    :open="applyOpen"
    title="申请导出查询结果"
    @cancel="applyOpen = false"
    @ok="submit"
  >
    <Alert
      class="mb-3"
      type="info"
      show-icon
      message="审批通过后 24 小时内可下载一次。下载时重新执行查询，结果按导出口径脱敏，文件带导出人水印。"
    />
    <pre class="bg-muted mb-3 max-h-40 overflow-auto rounded p-2 text-xs">{{
      form.sql || '（编辑器为空）'
    }}</pre>
    <div class="mb-3 flex items-center gap-2">
      <span class="text-sm">导出行数上限</span>
      <InputNumber
        v-model:value="form.limit"
        :max="1000"
        :min="1"
        :precision="0"
      />
    </div>
    <TextArea
      v-model:value="form.reason"
      :maxlength="500"
      :rows="3"
      placeholder="申请原因（必填）"
      show-count
    />
    <div v-if="formError" class="text-destructive mt-1 text-xs">
      {{ formError }}
    </div>
  </Modal>

  <Drawer
    :open="listOpen"
    title="我的导出"
    width="720"
    @close="listOpen = false"
  >
    <Table
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="{
        current: page.pageNo,
        pageSize: page.pageSize,
        total,
        onChange: changePage,
      }"
      :row-key="(record: DataExportApi.Request) => String(record.id)"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'status'">
          <Tag :color="exportStatusView(record.status).color">
            {{ exportStatusView(record.status).label }}
          </Tag>
        </template>
        <template v-else-if="column.key === 'expireTime'">
{{
          formatTime(record.expireTime)
        }}
</template>
        <template v-else-if="column.key === 'actions'">
          <Button
            v-if="canDownload(record)"
            :loading="busy.has(String(record.id))"
            size="small"
            type="link"
            @click="download(record)"
          >
            下载
          </Button>
          <Button
            v-else-if="record.status === 'PENDING' && canCreate"
            :loading="busy.has(String(record.id))"
            danger
            size="small"
            type="link"
            @click="cancel(record)"
          >
            撤回
          </Button>
        </template>
      </template>
    </Table>
  </Drawer>
</template>
