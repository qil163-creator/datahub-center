<script setup lang="ts">
import type { BatchFileApi as Api } from '#/api/dataforge/batchfile';

import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { formatDate } from '@vben/utils';

import { Alert, Button, Modal, Spin } from 'antdv-next';

import { getBatchFile } from '#/api/dataforge/batchfile';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';
import { formatSize } from '#/views/dataforge/datafile/data';

import { idOf, LIST_PATH, routeId, statusOf } from './model';
import EmptyState from './modules/empty-state.vue';
import GridTools from './modules/grid-tools.vue';
import ReferenceIcon from './modules/reference-icon.vue';
import RunList from './modules/run-list.vue';
import { useBatchScope } from './use-scope';

import './batch-file.css';

defineOptions({ name: 'DataforgeBatchFileDetail' });
const route = useRoute();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const id = computed(() => routeId(route.params.id));
const task = ref<Api.Detail>();
const loading = ref(false);
const failed = ref(false);
const fieldOpen = ref(false);
const fileOpen = ref(false);
const selectedFile = ref<Api.File>();
const tabs = [
  { key: 'files', title: '文件列表' },
  { key: 'targets', title: '目标表关系' },
  { key: 'runs', title: '任务实例' },
  { key: 'info', title: '详细信息' },
];
const tab = computed(() =>
  tabs.some((entry) => entry.key === route.query.tab)
    ? String(route.query.tab)
    : 'files',
);
const fileColumns = [
  { key: 'id', title: '编号' },
  { key: 'info', title: '文件信息' },
  { key: 'type', title: '文件类型' },
  { key: 'size', title: '文件大小' },
  { key: 'target', title: '目标表' },
  { key: 'status', title: '执行状态' },
  { key: 'writtenRows', title: '写入行数' },
  { key: 'actions', title: '操作' },
];
const targetColumns = [
  { key: 'name', title: '目标表' },
  { key: 'files', title: '文件名' },
  { key: 'order', title: '写入顺序' },
  { key: 'count', title: '文件数量' },
  { key: 'structure', title: '结构校验' },
  { key: 'result', title: '入库结果' },
];
const visibleFiles = ref(fileColumns.map((entry) => entry.key));
const visibleTargets = ref(targetColumns.map((entry) => entry.key));
const { active, scope } = useBatchScope(() => id.value);
const request = useObjectRequest(() => scope.value);
async function load() {
  if (
    !active.value ||
    !id.value ||
    !hasAccessByCodes(['dataforge:batch-file:query'])
  )
    return;
  const current = request.begin();
  loading.value = true;
  failed.value = false;
  try {
    const result = await getBatchFile(id.value);
    if (current()) task.value = result;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
function showFile(file: Api.File, fields: boolean) {
  selectedFile.value = file;
  if (fields) fieldOpen.value = true;
  else fileOpen.value = true;
}
function fileFor(fileId: Api.Id) {
  return task.value?.files.find((file) => idOf(file.id) === idOf(fileId));
}
function targetFor(fileId: Api.Id) {
  return (
    task.value?.targets.find((target) =>
      target.fileIds.some((id) => idOf(id) === idOf(fileId)),
    )?.name ?? '-'
  );
}
function targetStatus(target: Api.Target) {
  const states = target.fileIds.map(
    (fileId) => fileFor(fileId)?.lastStatus ?? 'NOT_EXECUTED',
  );
  if (states.every((state) => state === 'SUCCESS')) return 'SUCCESS';
  if (states.includes('UNKNOWN')) return 'UNKNOWN';
  if (states.includes('RUNNING') || states.includes('QUEUED')) return 'RUNNING';
  if (states.includes('FAILED') || states.includes('PARTIAL_SUCCESS'))
    return states.includes('SUCCESS') ? 'PARTIAL_SUCCESS' : 'FAILED';
  return 'NOT_EXECUTED';
}
watch(
  scope,
  () => {
    task.value = undefined;
    selectedFile.value = undefined;
    fieldOpen.value = false;
    fileOpen.value = false;
    loading.value = false;
    failed.value = false;
    void load();
  },
  { immediate: true },
);
</script>
<template>
  <Page auto-content-height content-class="qdata-batch-file">
    <div class="bf-page">
      <Alert
        v-if="!id || !hasAccessByCodes(['dataforge:batch-file:query'])"
        type="warning"
        title="任务编号无效或没有查询权限"
        show-icon
      />
      <Alert
        v-if="failed"
        type="error"
        title="任务详情加载失败，请重试"
        show-icon
        >
<template #action><Button @click="load"><template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>重试</Button></template>
</Alert>
      <Spin :spinning="loading && !task">
<section class="bf-detail-header">
          <div class="bf-detail-heading">
            <span v-if="task" class="bf-id">{{ task.id }}</span><span>{{ task?.taskName || '批量文件入库任务' }}</span><span v-if="task" class="bf-tag blue">{{
              task.online ? '已上线' : '未上线'
            }}</span><DetailBackButton @click="router.push(LIST_PATH)" />
          </div>
          <div v-if="task" class="bf-description-grid">
            <div class="bf-description-label">文件数量</div>
            <div>{{ task.fileCount }}</div>
            <div class="bf-description-label">目标表数量</div>
            <div>{{ task.targetCount }}</div>
            <div class="bf-description-label">目标数据源</div>
            <div>{{ task.targetDatabaseName }}</div>
            <div class="bf-description-label">描述</div>
            <div class="bf-description-wide">{{ task.description || '-' }}</div>
          </div>
        </section>
</Spin>
      <section v-if="task" class="bf-panel bf-main-panel">
        <nav class="bf-tabs" aria-label="任务详情">
          <button
            v-for="entry in tabs"
            :key="entry.key"
            class="bf-tab"
            :class="{ active: tab === entry.key }"
            :aria-current="tab === entry.key ? 'page' : undefined"
            @click="
              router.replace({ query: { ...route.query, tab: entry.key } })
            "
          >
            {{ entry.title }}
          </button>
        </nav>
        <template v-if="tab === 'files'">
<GridTools
            v-model:visible="visibleFiles"
            :columns="fileColumns"
            hide-search
            @refresh="load"
/>
          <div class="bf-table-wrap">
            <table class="bf-table bf-fixed-actions-table">
              <thead>
                <tr>
                  <th
                    v-for="column in fileColumns.filter((entry) =>
                      visibleFiles.includes(entry.key),
                    )"
                    :key="column.key"
                    :class="{ 'bf-sticky-actions': column.key === 'actions' }"
                  >
                    {{ column.title }}
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(file, index) in task.files" :key="idOf(file.id)">
                  <td v-if="visibleFiles.includes('id')">{{ index + 1 }}</td>
                  <td v-if="visibleFiles.includes('info')">
                    {{ file.name
                    }}<span class="bf-secondary">固定结构文件，已完成结构确认。</span>
                  </td>
                  <td v-if="visibleFiles.includes('type')">
                    <span class="bf-tag blue">{{ file.type }}</span>
                  </td>
                  <td v-if="visibleFiles.includes('size')">
                    {{ formatSize(file.size) }}
                  </td>
                  <td v-if="visibleFiles.includes('target')">
                    <span class="bf-tag blue">{{
                      file.targetTable || targetFor(file.id)
                    }}</span>
                  </td>
                  <td v-if="visibleFiles.includes('status')">
                    <span
                      class="bf-tag"
                      :class="statusOf(file.lastStatus).tone"
                      >{{ statusOf(file.lastStatus).label }}</span>
                  </td>
                  <td v-if="visibleFiles.includes('writtenRows')">
                    {{ file.writtenRows ?? '-' }}
                  </td>
                  <td
                    v-if="visibleFiles.includes('actions')"
                    class="bf-cell-actions bf-sticky-actions"
                  >
                    <button class="bf-link" @click="showFile(file, true)">
                      <ReferenceIcon name="field" />字段
</button><button class="bf-link" @click="showFile(file, false)">
                      <ReferenceIcon name="eye" />详情
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
            <EmptyState v-if="!task.files.length" />
</div>
</template>
        <template v-if="tab === 'targets'">
<GridTools
            v-model:visible="visibleTargets"
            :columns="targetColumns"
            hide-search
            @refresh="load"
          />
          <div class="bf-table-wrap">
            <table class="bf-table">
              <thead>
                <tr>
                  <th
                    v-for="column in targetColumns.filter((entry) =>
                      visibleTargets.includes(entry.key),
                    )"
                    :key="column.key"
                  >
                    {{ column.title }}
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="target in task.targets" :key="target.name">
                  <td v-if="visibleTargets.includes('name')">
                    {{ target.name
                    }}<span class="bf-secondary">{{
                      target.fileIds.length > 1 ? '合并表' : '单文件'
                    }}</span>
                  </td>
                  <td v-if="visibleTargets.includes('files')">
                    <div
                      v-for="fileId in target.fileIds"
                      :key="idOf(fileId)"
                      class="bf-file-order"
                    >
                      {{ fileFor(fileId)?.name }}
                    </div>
                  </td>
                  <td v-if="visibleTargets.includes('order')">
                    <div
                      v-for="(_, index) in target.fileIds"
                      :key="index"
                      class="bf-file-order"
                    >
                      <span class="bf-tag blue">{{ index + 1 }}</span>
                    </div>
                  </td>
                  <td v-if="visibleTargets.includes('count')">
                    {{ target.fileIds.length }}
                  </td>
                  <td v-if="visibleTargets.includes('structure')">
                    <span class="bf-tag success"><ReferenceIcon name="check" />通过</span>
                  </td>
                  <td v-if="visibleTargets.includes('result')">
                    <span
                      class="bf-tag"
                      :class="statusOf(targetStatus(target)).tone"
                      >{{ statusOf(targetStatus(target)).label }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
</template>
        <RunList v-if="tab === 'runs'" :task="task" @refresh="load" />
        <div v-if="tab === 'info'" class="bf-description-grid bf-details-grid">
          <div class="bf-description-label">数据库类型</div>
          <div>MySQL</div>
          <div class="bf-description-label">最近执行时间</div>
          <div>
            {{
              task.lastRunTime
                ? formatDate(task.lastRunTime, 'YYYY-MM-DD HH:mm:ss')
                : '-'
            }}
          </div>
          <div class="bf-description-label">执行结果</div>
          <div>
            <span class="bf-tag" :class="statusOf(task.lastStatus).tone">{{
              statusOf(task.lastStatus).label
            }}</span>
          </div>
          <div class="bf-description-label">成功文件</div>
          <div>{{ task.successFiles ?? '-' }}</div>
          <div class="bf-description-label">失败文件</div>
          <div>{{ task.failedFiles ?? '-' }}</div>
          <div class="bf-description-label">创建人</div>
          <div>{{ task.creator || '-' }}</div>
          <div class="bf-description-label">创建时间</div>
          <div>{{ formatDate(task.createTime, 'YYYY-MM-DD HH:mm') }}</div>
          <div class="bf-description-label">备注</div>
          <div>{{ task.remark || '-' }}</div>
          <div class="bf-description-label">责任人</div>
          <div>{{ task.responsibleName }}</div>
          <div class="bf-description-label">责任人电话</div>
          <div>{{ task.responsiblePhone || '-' }}</div>
        </div>
      </section>
    </div>
    <Modal
      v-model:open="fieldOpen"
      :title="`字段列表 - ${selectedFile?.name ?? ''}`"
      width="800px"
      :footer="null"
      >
<div class="qdata-batch-file bf-modal-body bf-table-wrap">
        <table class="bf-table">
          <thead>
            <tr>
              <th>序号</th>
              <th>字段名称</th>
              <th>字段类型</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(field, index) in selectedFile?.fields"
              :key="field.sourceIndex"
            >
              <td>{{ index + 1 }}</td>
              <td>{{ field.name }}</td>
              <td>{{ field.type }}</td>
            </tr>
          </tbody>
        </table>
      </div>
</Modal>
    <Modal v-model:open="fileOpen" title="文件详情" width="800px" :footer="null">
<div v-if="selectedFile" class="qdata-batch-file bf-modal-body">
        <div class="bf-description-grid bf-details-grid">
          <div class="bf-description-label">文件名称</div>
          <div>{{ selectedFile.name }}</div>
          <div class="bf-description-label">文件类型</div>
          <div>{{ selectedFile.type }}</div>
          <div class="bf-description-label">文件来源</div>
          <div>
            {{ selectedFile.source === 'LOCAL' ? '本地上传' : '系统文件' }}
          </div>
          <div class="bf-description-label">文件大小</div>
          <div>{{ formatSize(selectedFile.size) }}</div>
          <div class="bf-description-label">所在路径</div>
          <div>{{ selectedFile.path || '-' }}</div>
          <div class="bf-description-label">文件行数</div>
          <div>{{ selectedFile.rowCount }}</div>
          <div class="bf-description-label">目标表</div>
          <div>
            {{ selectedFile.targetTable || targetFor(selectedFile.id) }}
          </div>
          <div class="bf-description-label">执行状态</div>
          <div>{{ statusOf(selectedFile.lastStatus).label }}</div>
          <div class="bf-description-label">写入行数</div>
          <div>{{ selectedFile.writtenRows ?? '-' }}</div>
          <div class="bf-description-label">字段数</div>
          <div>{{ selectedFile.fields.length }}</div>
        </div>
      </div>
</Modal>
  </Page>
</template>

<style scoped>
.bf-page .bf-detail-header,
.bf-page .bf-main-panel {
  padding: 20px;
  background: hsl(var(--card));
  border-radius: var(--radius);
}

.bf-page .bf-main-panel {
  padding-top: 8px;
}

.bf-detail-heading {
  margin-bottom: 20px;
}

.bf-tabs {
  margin-bottom: 20px;
}

.bf-page .bf-description-grid {
  gap: 1px;
  overflow: hidden;
  background: hsl(var(--border));
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

.bf-page .bf-description-grid > div {
  color: hsl(var(--foreground));
  background: hsl(var(--card));
  border: 0;
}

.bf-page .bf-description-grid > .bf-description-label {
  color: hsl(var(--muted-foreground));
  background: hsl(var(--muted) / 45%);
}

.bf-fixed-actions-table {
  min-width: 1000px;
}

.bf-sticky-actions {
  position: sticky;
  right: 0;
  z-index: 1;
  width: var(--batch-action-width, 160px);
  min-width: var(--batch-action-width, 160px);
  white-space: nowrap;
  background: hsl(var(--card));
  box-shadow: -4px 0 6px -5px hsl(var(--foreground) / 20%);
}

th.bf-sticky-actions {
  z-index: 2;
  background: hsl(var(--accent));
}

@media (width <= 640px) {
  .bf-page .bf-detail-header {
    padding: 16px;
  }

  .bf-page .bf-main-panel {
    padding: 8px 16px 16px;
  }
}
</style>
