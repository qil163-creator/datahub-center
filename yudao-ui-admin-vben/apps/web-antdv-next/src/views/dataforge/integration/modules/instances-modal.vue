<script setup lang="ts">
import type { IntegrationApi as Api } from '#/api/dataforge/integration';

import { ref, watch } from 'vue';

import { formatDate } from '@vben/utils';

import { Button, Empty, Modal, Pagination, Spin } from 'antdv-next';

import {
  getIntegrationRecords,
  getIntegrationRecordStatistics,
} from '#/api/dataforge/integration';
import { useObjectRequest } from '#/hooks/use-object-request';

import { duration, labelOf, runOptions } from '../model';
import ReferenceIcon from './reference-icon.vue';

const props = defineProps<{ open: boolean; task?: Api.Task }>();
defineEmits<{ 'update:open': [value: boolean] }>();
const rows = ref<Api.Record[]>([]);
const page = ref(1);
const pageSize = ref(10);
const total = ref(0);
const status = ref<Api.RunStatus>();
const statistics = ref<Api.RecordStatistics>();
const loading = ref(false);
const failed = ref(false);
const selected = ref<Api.Record>();
const showLog = ref(false);
const request = useObjectRequest(() => `${props.open}/${props.task?.id ?? ''}`);
const tabs: { label: string; value?: Api.RunStatus }[] = [
  { label: '全部' },
  { label: '运行中', value: 'RUNNING' },
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILURE' },
];
async function load() {
  if (!props.open || !props.task) return;
  const current = request.begin();
  loading.value = true;
  failed.value = false;
  try {
    const [result, summary] = await Promise.all([
      getIntegrationRecords({
        taskId: props.task.id,
        pageNo: page.value,
        pageSize: pageSize.value,
        status: status.value,
      }),
      getIntegrationRecordStatistics(props.task.id),
    ]);
    if (current()) {
      rows.value = result.list;
      total.value = result.total;
      statistics.value = summary;
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
function selectStatus(value?: Api.RunStatus) {
  status.value = value;
  page.value = 1;
  void load();
}
watch(
  () => [props.open, props.task?.id],
  () => {
    rows.value = [];
    total.value = 0;
    page.value = 1;
    statistics.value = undefined;
    status.value = undefined;
    selected.value = undefined;
    if (props.open) void load();
  },
  { immediate: true },
);
const date = (value?: number) =>
  value ? formatDate(value, 'YYYY-MM-DD HH:mm:ss') : '-';
function inspect(record: Api.Record, log = false) {
  selected.value = record;
  showLog.value = log;
}
function download(record: Api.Record) {
  if (!record.message) return;
  const url = URL.createObjectURL(
    new Blob([record.message], { type: 'text/plain;charset=utf-8' }),
  );
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `integration-${record.id}.log`;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
</script>

<template>
  <Modal
    :open="open"
    title="运行实例"
    width="min(1200px, calc(100vw - 32px))"
    wrap-class-name="qi-modal qi-instances-modal"
    @cancel="$emit('update:open', false)"
  >
    <div class="qi-instance-summary">
      <p>
        当前共有
        <strong class="running">运行中{{ statistics?.running ?? '-' }}个</strong>，<strong class="success">成功{{ statistics?.success ?? '-' }}个</strong>，<strong class="failure">失败{{ statistics?.failed ?? '-' }}个</strong>实例
      </p>
      <span>最后刷新时间：{{ date(statistics?.statisticTime) }}
        <button type="button" aria-label="刷新运行实例" @click="load">
          <ReferenceIcon name="refresh" /></button></span>
    </div>
    <nav class="qi-tabs" aria-label="运行实例状态">
      <button
        v-for="tab in tabs"
        :key="tab.label"
        type="button"
        :class="{ active: status === tab.value }"
        @click="selectStatus(tab.value)"
      >
        {{ tab.label }}
      </button>
    </nav>

    <Spin :spinning="loading">
      <div class="qi-table-scroll qi-instance-scroll">
        <table class="qi-table qi-instance-table">
          <colgroup>
            <col style="width: 6%" />
            <col style="width: 28%" />
            <col style="width: 9%" />
            <col style="width: 9%" />
            <col style="width: 11%" />
            <col style="width: 15%" />
            <col style="width: 8%" />
            <col style="width: 14%" />
          </colgroup>
          <thead>
            <tr>
              <th>编号</th>
              <th>任务名称</th>
              <th>执行类型</th>
              <th>执行状态</th>
              <th>当前节点</th>
              <th>开始时间</th>
              <th>运行时长</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="record in rows" :key="String(record.id)">
              <td>{{ record.id }}</td>
              <td>
                <button
                  class="qi-task-name"
                  type="button"
                  :title="record.instanceName || record.taskName"
                  @click="inspect(record)"
                >
                  {{ record.instanceName || record.taskName || task?.name }}
                </button>
              </td>
              <td>
                {{ record.commandType === '9' ? '自动调度' : '手动执行' }}
              </td>
              <td>
                <span
                  class="qi-run-badge"
                  :class="record.status.toLowerCase()"
                  >{{ labelOf(runOptions, record.status) }}</span>
              </td>
              <td>
                <span :title="record.currentNode">{{
                  record.currentNode || '-'
                }}</span>
              </td>
              <td>{{ date(record.startTime) }}</td>
              <td>{{ duration(record.duration) }}</td>
              <td>
                <div class="qi-instance-actions">
                  <button
                    type="button"
                    class="qi-link"
                    @click="inspect(record)"
                  >
                    查看
</button><button
                    type="button"
                    class="qi-link"
                    @click="inspect(record, true)"
                  >
                    实时日志
</button><button
                    type="button"
                    class="qi-link"
                    :disabled="!record.message"
                    @click="download(record)"
                  >
                    下载日志
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <Empty
          v-if="rows.length === 0 && !loading && !failed"
          description="暂无运行实例"
        />
      </div>
    </Spin>
    <Pagination
      v-model:current="page"
      v-model:page-size="pageSize"
      :total="total"
      show-size-changer
      show-quick-jumper
      :show-total="(count) => `共 ${count} 条`"
      @change="load"
    />
    <template #footer>
      <Button @click="$emit('update:open', false)">关闭</Button>
    </template>
  </Modal>
  <Modal
    :open="!!selected"
    :title="showLog ? '实时日志' : '运行实例详情'"
    width="min(880px, calc(100vw - 32px))"
    wrap-class-name="qi-modal"
    :footer="null"
    @cancel="selected = undefined"
  >
    <template v-if="selected">
      <table v-if="!showLog" class="qi-info-table">
        <tbody>
          <tr>
            <th>实例名称</th>
            <td colspan="3">
              {{ selected.instanceName || selected.taskName }}
            </td>
          </tr>
          <tr>
            <th>执行状态</th>
            <td>{{ labelOf(runOptions, selected.status) }}</td>
            <th>执行人</th>
            <td>{{ selected.executorName || '-' }}</td>
          </tr>
          <tr>
            <th>开始时间</th>
            <td>{{ date(selected.startTime) }}</td>
            <th>结束时间</th>
            <td>{{ date(selected.endTime) }}</td>
          </tr>
          <tr>
            <th>当前节点</th>
            <td>{{ selected.currentNode || '-' }}</td>
            <th>运行时长</th>
            <td>{{ duration(selected.duration) }}</td>
          </tr>
        </tbody>
      </table>
      <pre class="qi-record-message">{{
        selected.message || '暂无执行日志'
      }}</pre>
    </template>
  </Modal>
</template>
