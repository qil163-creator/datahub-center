<script lang="ts" setup>
import type { DiagnosisModalData, DiagnosisSummary } from '../diagnosis';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';

import { computed, onBeforeUnmount, onDeactivated, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Spin } from 'antdv-next';

import {
  DIAGNOSIS_STATUS,
  diagnosisStatusCount,
  InvalidDiagnosisError,
  parseDiagnosis,
} from '../diagnosis';
import TypeIconCell from './type-icon-cell.vue';

const emit = defineEmits<{
  closed: [];
  completed: [result: FoundationDatabaseApi.Diagnosis];
  settled: [];
}>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const scope = computed(() =>
  JSON.stringify([
    user.userInfo?.userId,
    user.userRoles,
    access.accessCodes,
    access.accessToken,
    access.refreshToken,
  ]),
);
const canQuery = computed(() => hasAccessByCodes(['dataforge:database:query']));
const canWrite = computed(
  () => canQuery.value && hasAccessByCodes(['dataforge:database:update']),
);
const summary = ref<DiagnosisSummary>();
const result = ref<FoundationDatabaseApi.Diagnosis>();
const supportsWriteChecks = computed(
  () =>
    result.value?.groups.some(
      (group) =>
        group.groupCode === 'WRITE' ||
        (group.groupCode === 'FILE_CONNECTION' &&
          group.items.some((item) => item.checkCode === 'FILE_WRITE')),
    ) ?? true,
);
const loading = ref(false);
const playing = ref(false);
const revealedCount = ref(0);
const busy = computed(() => loading.value || playing.value);
const failure = ref('');
const open = ref(false);
let revision = 0;
let controller: AbortController | undefined;
let execute: DiagnosisModalData['execute'];
let revealTimer: ReturnType<typeof setTimeout> | undefined;

function stopPlayback() {
  if (revealTimer !== undefined) clearTimeout(revealTimer);
  revealTimer = undefined;
  playing.value = false;
}

function finishPlayback(current: number) {
  if (current !== revision || !open.value || !result.value) return;
  revealedCount.value = result.value.totalCount;
  stopPlayback();
  modalApi.setState({ confirmLoading: false, confirmDisabled: false });
  emit('completed', result.value);
}

/** 只播放已校验的真实结果，播放状态不进入服务端诊断协议。 */
function startPlayback(current: number) {
  if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
    finishPlayback(current);
    return;
  }
  playing.value = true;
  const revealNext = () => {
    if (current !== revision || !open.value || !result.value) return;
    revealedCount.value += 1;
    if (revealedCount.value >= result.value.totalCount) {
      finishPlayback(current);
      return;
    }
    revealTimer = setTimeout(revealNext, 300);
  };
  revealTimer = setTimeout(revealNext, 300);
}

function reset() {
  revision += 1;
  stopPlayback();
  controller?.abort();
  controller = undefined;
  execute = undefined;
  summary.value = undefined;
  result.value = undefined;
  revealedCount.value = 0;
  loading.value = false;
  failure.value = '';
  modalApi.setData({});
  modalApi.setState({ confirmLoading: false, confirmDisabled: false });
}

async function diagnose() {
  if (busy.value || !open.value) return;
  if (!execute || !canQuery.value) {
    failure.value = '当前无法执行连接诊断，请确认查询权限后重新打开。';
    return;
  }
  const current = ++revision;
  const request = execute;
  // 清除上一轮展示结果前，保留其写入诊断能力约束。
  const includeWriteChecks = canWrite.value && supportsWriteChecks.value;
  controller?.abort();
  controller = new AbortController();
  loading.value = true;
  failure.value = '';
  result.value = undefined;
  stopPlayback();
  revealedCount.value = 0;
  modalApi.setState({ confirmLoading: true, confirmDisabled: true });
  try {
    const response = await request(includeWriteChecks, controller.signal);
    if (current !== revision || !open.value) return;
    const checked = parseDiagnosis(response);
    result.value = checked;
    startPlayback(current);
  } catch (error) {
    if (current !== revision || !open.value) return;
    failure.value =
      error instanceof InvalidDiagnosisError
        ? error.message
        : '诊断未完成，请检查网络或连接配置后重新诊断。';
  } finally {
    if (current === revision && open.value) {
      loading.value = false;
      modalApi.setState({
        confirmLoading: playing.value,
        confirmDisabled: playing.value,
      });
      emit('settled');
    }
  }
}

const [Modal, modalApi] = useVbenModal<DiagnosisModalData>({
  title: '测试连接结果',
  confirmText: '重新诊断',
  cancelText: '关闭',
  onConfirm: diagnose,
  onOpenChange(isOpen) {
    const data = isOpen ? modalApi.getData() : undefined;
    reset();
    open.value = isOpen;
    if (!isOpen) {
      emit('closed');
      return;
    }
    summary.value = data?.summary;
    execute = data?.execute;
    void diagnose();
  },
});

function closeContext() {
  open.value = false;
  reset();
  void modalApi.close();
  emit('closed');
}
watch(scope, closeContext, { flush: 'sync' });
onDeactivated(closeContext);
onBeforeUnmount(() => {
  open.value = false;
  reset();
  emit('closed');
});

const metadata = computed(() => [
  { label: '连接名称', value: summary.value?.name, icon: 'lucide:network' },
  { label: '类型', value: summary.value?.typeName, icon: 'lucide:layers-3' },
  { label: 'IP / 主机', value: summary.value?.host, icon: 'lucide:server' },
  { label: '端口号', value: summary.value?.port, icon: 'lucide:ethernet-port' },
  { label: '账号', value: summary.value?.userName, icon: 'lucide:user-round' },
]);
const displayedGroups = computed(() => {
  let offset = 0;
  return (result.value?.groups ?? []).map((group) => {
    const completedCount = Math.min(
      group.totalCount,
      Math.max(0, revealedCount.value - offset),
    );
    const items = group.items.map((item, index) => ({
      ...item,
      revealed: offset + index < revealedCount.value,
    }));
    offset += group.totalCount;
    return { ...group, completedCount, items };
  });
});
const displayedCounts = computed(() => {
  const counts = {
    normalCount: 0,
    warningCount: 0,
    errorCount: 0,
    skippedCount: 0,
  };
  for (const group of displayedGroups.value) {
    for (const item of group.items) {
      if (!item.revealed) continue;
      switch (item.status) {
        case 'FAIL': {
          counts.errorCount += 1;
          break;
        }
        case 'PASS': {
          counts.normalCount += 1;
          break;
        }
        case 'SKIPPED': {
          counts.skippedCount += 1;
          break;
        }
        case 'WARN': {
          counts.warningCount += 1;
          break;
        }
      }
    }
  }
  return counts;
});
const overallLabel = computed(() => {
  if (!result.value) return '';
  return {
    PASS: '诊断通过',
    WARN: '诊断存在警告',
    FAIL: '诊断发现错误',
    SKIPPED: '本次检查均已跳过',
  }[result.value.overallStatus];
});
function groupIcon(code: string) {
  const icons: Record<string, string> = {
    BASIC: 'lucide:network',
    TARGET: 'lucide:database',
    METADATA: 'lucide:server',
    READ: 'lucide:rows-3',
    WRITE: 'lucide:between-horizontal-start',
    ADVANCED: 'lucide:layers-3',
  };
  return (
    (Object.hasOwn(icons, code) ? icons[code] : undefined) ??
    'lucide:list-checks'
  );
}
</script>

<template>
  <Modal
    class="w-[1200px] max-w-[calc(100vw-32px)]"
    content-class="diagnosis-modal-body"
  >
    <div
      class="diagnosis-panel"
      :aria-busy="busy"
      :data-phase="
        loading
          ? 'loading'
          : playing
            ? 'revealing'
            : failure
              ? 'error'
              : result
                ? 'complete'
                : 'idle'
      "
    >
      <section class="diagnosis-summary" aria-label="连接摘要">
        <div class="diagnosis-summary__logo">
          <TypeIconCell
            :icon-path="summary?.iconPath"
            :type-name="summary?.typeName"
          />
        </div>
        <div
          v-for="item in metadata"
          :key="item.label"
          class="diagnosis-summary__item"
        >
          <span class="diagnosis-summary__icon"><IconifyIcon :icon="item.icon" /></span>
          <div>
            <span>{{ item.label }}</span><strong :title="item.value">{{ item.value || '—' }}</strong>
          </div>
        </div>
      </section>

      <div
        v-if="loading"
        class="diagnosis-waiting"
        role="status"
        aria-live="polite"
      >
        <Spin />
        <div>
          <strong>正在诊断，请等待本次检查完成…</strong>
          <p>检查完成后将逐项展示结果。关闭窗口不会中止已发起的服务端检查。</p>
        </div>
      </div>
      <Alert
        v-else-if="failure"
        type="error"
        show-icon
        message="诊断失败"
        :description="failure"
      />
      <template v-else-if="result">
        <section
          class="diagnosis-result"
          :class="`diagnosis-result--${playing ? 'running' : result.overallStatus.toLowerCase()}`"
          aria-live="polite"
        >
          <span
            v-if="playing"
            class="diagnosis-spinner"
            aria-hidden="true"
          ></span>
          <IconifyIcon
            v-else
            :icon="DIAGNOSIS_STATUS[result.overallStatus].icon"
          />
          <strong>{{
            playing
              ? `正在展示诊断结果 ${revealedCount}/${result.totalCount}`
              : overallLabel
          }}</strong>
          <span>通过
            <b class="count-pass">{{ displayedCounts.normalCount }}</b> 个</span><span>警告
            <b class="count-warn">{{ displayedCounts.warningCount }}</b>
            个</span><span>错误
            <b class="count-fail">{{ displayedCounts.errorCount }}</b> 个</span><span>跳过 <b>{{ displayedCounts.skippedCount }}</b> 个</span>
          <span v-if="!playing" class="diagnosis-result__connection">{{
            result.connectionSuccessful ? '连接正常' : '连接未确认'
          }}</span>
        </section>
        <div class="diagnosis-table-scroll">
          <table class="diagnosis-table">
            <caption class="sr-only">
              数据库连接分组诊断结果
            </caption>
            <colgroup>
              <col class="diagnosis-col-group" />
              <col class="diagnosis-col-name" />
              <col class="diagnosis-col-content" />
              <col class="diagnosis-col-status" />
              <col class="diagnosis-col-impact" />
            </colgroup>
            <thead>
              <tr>
                <th scope="col">诊断详情</th>
                <th scope="col">诊断项</th>
                <th scope="col">校验内容</th>
                <th scope="col">结果状态</th>
                <th scope="col">功能影响</th>
              </tr>
            </thead>
            <tbody v-for="group in displayedGroups" :key="group.groupCode">
              <tr v-if="group.items.length === 0">
                <th scope="row" class="diagnosis-group">
                  <strong>{{ group.groupName }}</strong>
                  <p>{{ group.description }}</p>
                </th>
                <td colspan="4">
                  <span class="diagnosis-status diagnosis-status--skipped">跳过</span><span class="ml-2">本组没有可执行的检查项</span>
                </td>
              </tr>
              <tr v-for="(item, index) in group.items" :key="item.checkCode">
                <th
                  v-if="index === 0"
                  :rowspan="group.items.length"
                  scope="rowgroup"
                  class="diagnosis-group"
                >
                  <div class="diagnosis-group__top">
                    <span class="diagnosis-group__icon"><IconifyIcon :icon="groupIcon(group.groupCode)" /></span><span
                      class="diagnosis-status"
                      :class="`diagnosis-status--${group.completedCount < group.totalCount ? 'running' : group.status.toLowerCase()}`"
                      ><template v-if="group.completedCount < group.totalCount"><span
                          class="diagnosis-spinner"
                          aria-hidden="true"
                        ></span>{{ group.completedCount }}/{{
                          group.totalCount
                        }}
                        诊断中</template><template v-else>{{ diagnosisStatusCount(group, group.status) }}/{{
                          group.totalCount
                        }}
                        {{ DIAGNOSIS_STATUS[group.status].label }}</template></span>
                  </div>
                  <strong>{{ group.groupName }}</strong>
                  <p>{{ group.description }}</p>
                </th>
                <td>{{ item.checkName }}</td>
                <td>
                  {{ item.checkContent || '—' }}
                  <details
                    v-if="item.message"
                    class="diagnosis-message"
                    :class="{ 'diagnosis-message--pending': !item.revealed }"
                    :inert="!item.revealed"
                  >
                    <summary>查看说明</summary>
                    <p>{{ item.revealed ? item.message : '' }}</p>
                  </details>
                </td>
                <td>
                  <span
                    class="diagnosis-status"
                    :class="`diagnosis-status--${item.revealed ? item.status.toLowerCase() : 'running'}`"
                    ><span
                      v-if="!item.revealed"
                      class="diagnosis-spinner"
                      aria-hidden="true"
                    ></span><IconifyIcon
                      v-else
                      :icon="DIAGNOSIS_STATUS[item.status].icon"
                    />{{
                      item.revealed
                        ? DIAGNOSIS_STATUS[item.status].label
                        : '诊断中'
                    }}</span>
                </td>
                <td>
                  <span
                    :class="{ 'diagnosis-message--pending': !item.revealed }"
                    >{{ item.impact || '—' }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>
  </Modal>
</template>

<style scoped>
.diagnosis-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 8px 12px;
}

.diagnosis-summary {
  display: grid;
  grid-template-columns: 100px minmax(130px, 1.4fr) repeat(
      4,
      minmax(100px, 1fr)
    );
  gap: 20px;
  align-items: center;
  padding: 16px;
  border: 1px solid hsl(var(--border));
  border-radius: 3px;
}

.diagnosis-summary__logo {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 52px;
  border: 1px solid hsl(var(--border));
  border-radius: 4px;
}

.diagnosis-summary__item {
  display: flex;
  gap: 12px;
  align-items: center;
  min-width: 0;
}

.diagnosis-summary__icon {
  display: grid;
  flex-shrink: 0;
  place-items: center;
  width: 40px;
  height: 40px;
  font-size: 22px;
  color: #4c67fa;
  background: color-mix(in srgb, hsl(var(--card)) 94%, #4c67fa);
  border-radius: 4px;
}

.diagnosis-summary__item > div {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.diagnosis-summary__item span:not(.diagnosis-summary__icon) {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.diagnosis-summary__item strong {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
}

.diagnosis-waiting {
  display: flex;
  gap: 20px;
  align-items: center;
  justify-content: center;
  min-height: 270px;
  background: hsl(var(--muted) / 30%);
  border: 1px solid hsl(var(--border));
  border-radius: 4px;
}

.diagnosis-waiting strong {
  font-size: 14px;
  font-weight: 500;
}

.diagnosis-waiting p {
  max-width: 440px;
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.7;
  color: hsl(var(--muted-foreground));
}

.diagnosis-result {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  align-items: center;
  padding: 12px 16px;
  font-size: 14px;
  background: color-mix(in srgb, hsl(var(--card)) 93%, #409eff);
  border-radius: 3px;
}

.diagnosis-result > svg {
  font-size: 18px;
  color: #4c67fa;
}

.diagnosis-result strong {
  font-weight: 500;
}

.diagnosis-result--fail {
  background: color-mix(in srgb, hsl(var(--card)) 93%, #ed6060);
}

.diagnosis-result--warn {
  background: color-mix(in srgb, hsl(var(--card)) 92%, #e6b34d);
}

.diagnosis-result__connection {
  margin-left: auto;
  color: hsl(var(--muted-foreground));
}

.count-pass {
  color: #52a55f;
}

.count-warn {
  color: #cf9a2b;
}

.count-fail {
  color: #df6060;
}

.diagnosis-table-scroll {
  overflow-x: auto;
  border: 1px solid hsl(var(--border));
}

.diagnosis-table {
  width: 100%;
  min-width: 850px;
  font-size: 14px;
  table-layout: fixed;
  border-collapse: collapse;
}

.diagnosis-col-group {
  width: 23%;
}

.diagnosis-col-name {
  width: 16%;
}

.diagnosis-col-content {
  width: 29%;
}

.diagnosis-col-status {
  width: 12%;
}

.diagnosis-col-impact {
  width: 20%;
}

.diagnosis-table thead {
  background: hsl(var(--muted));
}

.diagnosis-table th,
.diagnosis-table td {
  padding: 11px 12px;
  font-weight: 400;
  text-align: left;
  overflow-wrap: anywhere;
}

.diagnosis-table thead th {
  font-weight: 500;
  color: hsl(var(--muted-foreground));
}

.diagnosis-table td,
.diagnosis-group {
  border-bottom: 1px solid hsl(var(--border) / 65%);
}

.diagnosis-table tbody:last-child tr:last-child td,
.diagnosis-table tbody:last-child .diagnosis-group {
  border-bottom: 0;
}

.diagnosis-group {
  vertical-align: middle;
  border-right: 1px solid hsl(var(--border) / 60%);
}

.diagnosis-group__top {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 9px;
}

.diagnosis-group__icon {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  font-size: 23px;
  color: #4c67fa;
  background: color-mix(in srgb, hsl(var(--card)) 95%, #4c67fa);
  border-radius: 3px;
}

.diagnosis-group > strong {
  font-weight: 500;
}

.diagnosis-group > p {
  margin: 5px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: hsl(var(--muted-foreground));
}

.diagnosis-status {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 3px 6px;
  font-size: 12px;
  line-height: 1.3;
  color: hsl(var(--muted-foreground));
  white-space: nowrap;
  background: hsl(var(--muted));
  border-radius: 2px;
}

.diagnosis-status--pass {
  color: #4e9a59;
  background: color-mix(in srgb, hsl(var(--card)) 90%, #66b97a);
}

.diagnosis-status--warn {
  color: #bb8823;
  background: color-mix(in srgb, hsl(var(--card)) 88%, #edc25f);
}

.diagnosis-status--fail {
  color: #d65f5f;
  background: color-mix(in srgb, hsl(var(--card)) 90%, #ed7979);
}

.diagnosis-status--running {
  color: #8860c7;
  background: color-mix(in srgb, hsl(var(--card)) 90%, #a27bd9);
}

.diagnosis-spinner {
  display: inline-block;
  flex-shrink: 0;
  width: 12px;
  height: 12px;
  color: #8860c7;
  border: 2px solid currentcolor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: diagnosis-spin 800ms linear infinite;
}

@keyframes diagnosis-spin {
  to {
    transform: rotate(360deg);
  }
}

.diagnosis-message--pending {
  visibility: hidden;
}

.diagnosis-message {
  margin-top: 6px;
  font-size: 12px;
}

.diagnosis-message summary {
  width: fit-content;
  color: #4c67fa;
  cursor: pointer;
}

.diagnosis-message p {
  margin: 6px 0 0;
  line-height: 1.6;
  color: hsl(var(--muted-foreground));
  white-space: pre-wrap;
}

@media (prefers-reduced-motion: reduce) {
  .diagnosis-spinner {
    animation: none;
  }
}

@media (max-width: 1000px) {
  .diagnosis-summary {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 16px;
  }

  .diagnosis-summary__logo {
    grid-row: span 2;
    min-height: 100%;
  }
}

@media (max-width: 600px) {
  .diagnosis-panel {
    gap: 12px;
    padding: 2px;
  }

  .diagnosis-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 14px;
    padding: 12px;
  }

  .diagnosis-summary__logo {
    grid-row: auto;
    min-height: 54px;
  }

  .diagnosis-summary__item {
    gap: 8px;
  }

  .diagnosis-summary__icon {
    width: 30px;
    height: 30px;
    font-size: 18px;
  }

  .diagnosis-waiting {
    align-items: flex-start;
    min-height: 240px;
    padding: 20px;
  }

  .diagnosis-result__connection {
    width: 100%;
    margin-left: 0;
  }
}
</style>
