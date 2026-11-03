<script lang="ts" setup>
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Alert, Button, Spin } from 'antdv-next';

import { diagnoseDatabase } from '#/api/dataforge/database';

import {
  DIAGNOSIS_STATUS,
  diagnosisSummary,
  InvalidDiagnosisError,
  parseDiagnosis,
} from '../diagnosis';
import { readNativeConnectionFields } from '../native-connection';
import TypeIconCell from './type-icon-cell.vue';

const props = defineProps<{
  connection: FoundationDatabaseApi.Database;
  selectedType?: FoundationDatabaseTypeApi.DatabaseType;
  testBlockedReason?: string;
}>();
const emit = defineEmits<{
  result: [value: FoundationDatabaseApi.Diagnosis | undefined];
  testing: [value: boolean];
}>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canQuery = computed(() => hasAccessByCodes(['dataforge:database:query']));
const canWrite = computed(
  () => canQuery.value && hasAccessByCodes(['dataforge:database:update']),
);
const supportsFileWriteChecks = computed(() => {
  const code = (
    props.selectedType?.typeCode ||
    props.connection.typeCode ||
    props.connection.productId ||
    ''
  ).toUpperCase();
  return code === 'FTP' || code === 'HDFS';
});
const scope = computed(() =>
  JSON.stringify([
    user.userInfo?.userId,
    user.userRoles,
    access.accessCodes,
    access.accessToken,
    access.refreshToken,
  ]),
);
const summary = computed(() =>
  diagnosisSummary({
    ...props.connection,
    databaseTypeName:
      props.selectedType?.typeName ?? props.connection.databaseTypeName,
    iconPath: props.selectedType?.iconPath ?? props.connection.iconPath,
  }),
);
const publicConfig = computed(() =>
  readNativeConnectionFields(props.connection.extendConfig),
);
const metadata = computed(() => [
  { label: '连接名称', value: summary.value.name },
  { label: '连接类型', value: summary.value.typeName },
  {
    label:
      publicConfig.value.connectionEndpoint ||
      publicConfig.value.connectionServers
        ? '服务地址'
        : '主机',
    value:
      publicConfig.value.connectionEndpoint ||
      publicConfig.value.connectionServers ||
      summary.value.host,
  },
  { label: '端口', value: summary.value.port },
  { label: '账号', value: summary.value.userName },
]);
const result = ref<FoundationDatabaseApi.Diagnosis>();
const loading = ref(false);
const failure = ref('');
let revision = 0;
let active = true;
let controller: AbortController | undefined;

function reset() {
  revision += 1;
  controller?.abort();
  controller = undefined;
  result.value = undefined;
  loading.value = false;
  failure.value = '';
  emit('testing', false);
  emit('result', undefined);
}

async function testConnection(manual = false) {
  if (!active || loading.value || !canQuery.value || props.testBlockedReason)
    return;
  const current = ++revision;
  const requestController = new AbortController();
  controller = requestController;
  const connection: FoundationDatabaseApi.Database & { secretKey?: unknown } = {
    ...props.connection,
  };
  delete connection.secretKey;
  result.value = undefined;
  failure.value = '';
  loading.value = true;
  emit('result', undefined);
  emit('testing', true);
  try {
    const response = await diagnoseDatabase(
      {
        connection,
        includeWriteChecks: manual && supportsFileWriteChecks.value && canWrite.value,
      },
      requestController.signal,
    );
    if (current !== revision || !active || requestController.signal.aborted)
      return;
    result.value = parseDiagnosis(response);
    emit('result', result.value);
  } catch (error) {
    if (current !== revision || !active || requestController.signal.aborted)
      return;
    failure.value =
      error instanceof InvalidDiagnosisError
        ? error.message
        : '测试未完成，请检查连接配置后重新测试。';
  } finally {
    if (current === revision && active) {
      controller = undefined;
      loading.value = false;
      emit('testing', false);
    }
  }
}

watch(
  () => [props.connection, props.selectedType, props.testBlockedReason],
  reset,
  {
    deep: true,
    flush: 'sync',
  },
);
watch(scope, reset, { flush: 'sync', immediate: true });
onMounted(() => {
  void testConnection();
});
onActivated(() => {
  active = true;
});
onDeactivated(() => {
  active = false;
  reset();
});
onBeforeUnmount(() => {
  active = false;
  reset();
});
</script>

<template>
  <div
    class="connection-review"
    :aria-busy="loading"
    :data-phase="
      loading ? 'testing' : failure ? 'error' : result ? 'complete' : 'idle'
    "
  >
    <section class="review-summary" aria-label="连接摘要">
      <TypeIconCell
        :icon-path="summary.iconPath"
        :type-name="summary.typeName"
      />
      <dl v-for="item in metadata" :key="item.label">
        <dt>{{ item.label }}</dt>
        <dd>{{ item.value || '—' }}</dd>
      </dl>
    </section>

    <div class="review-actions">
      <div>
        <strong>测试连接</strong>
        <p v-if="supportsFileWriteChecks && !canQuery">
          当前账号没有连接查询权限，无法执行检查。
        </p>
        <p v-else-if="supportsFileWriteChecks && canWrite">
          进入时执行只读检查；手动测试会创建临时文件并尝试清理，以检查文件写入权限。
        </p>
        <p v-else-if="supportsFileWriteChecks">
          当前账号没有连接修改权限，仅执行只读检查，写入检查将跳过。
        </p>
        <p v-else>执行只读检查，结果仅用于本次确认；保存后可在连接列表重新检测。</p>
      </div>
      <Button
        data-testid="connection-wizard-test"
        type="primary"
        :loading="loading"
        :disabled="!canQuery || !!testBlockedReason || loading"
        @click="testConnection(true)"
      >
        <template #icon><IconifyIcon icon="lucide:plug-zap" /></template>{{ result || failure ? '重新测试' : '测试连接' }}
      </Button>
    </div>

    <Alert
      v-if="!canQuery"
      type="info"
      show-icon
      message="当前账号没有连接查询权限"
      description="可继续保存为未检测记录，之后由有权限的用户测试连接。"
    />
    <Alert
      v-else-if="testBlockedReason"
      type="warning"
      show-icon
      message="请重新输入密码后测试"
      :description="testBlockedReason"
    />
    <div
      v-else-if="loading"
      class="review-waiting"
      role="status"
      aria-live="polite"
    >
      <Spin />
      <div>
        <strong>正在测试连接…</strong>
        <p>等待真实检查结果返回，检查完成前不会显示通过结论。</p>
      </div>
    </div>
    <Alert
      v-else-if="failure"
      type="error"
      show-icon
      message="测试未完成"
      :description="failure"
    />
    <template v-else-if="result">
      <section
        class="review-result"
        :data-connected="result.connectionSuccessful"
        aria-live="polite"
      >
        <IconifyIcon
          :icon="
            result.connectionSuccessful
              ? 'lucide:circle-check'
              : 'lucide:circle-help'
          "
        />
        <div>
          <strong>{{
            result.connectionSuccessful ? '连接验证通过' : '连接状态未确认'
          }}</strong>
          <p>
            检查汇总：通过 {{ result.normalCount }}，警告
            {{ result.warningCount }}，错误 {{ result.errorCount }}，未验证
            {{ result.skippedCount }}
          </p>
        </div>
        <span class="review-duration">耗时 {{ result.durationMs }} ms</span>
      </section>
      <div
        class="review-table-wrap"
        tabindex="0"
        role="region"
        aria-label="诊断检查结果，可横向滚动"
      >
        <table class="review-table">
          <caption class="sr-only">
            连接诊断检查结果
          </caption>
          <colgroup>
            <col class="review-column-group" />
            <col class="review-column-item" />
            <col class="review-column-content" />
            <col class="review-column-status" />
            <col class="review-column-impact" />
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
          <tbody v-for="group in result.groups" :key="group.groupCode">
            <tr v-for="(item, index) in group.items" :key="item.checkCode">
              <th
                v-if="index === 0"
                :rowspan="group.items.length"
                scope="rowgroup"
                class="review-group"
              >
                <strong>{{ group.groupName }}</strong>
                <p>{{ group.description }}</p>
                <span class="review-state" :data-status="group.status">{{ DIAGNOSIS_STATUS[group.status].label }} ·
                  {{ group.totalCount }} 项</span>
              </th>
              <td>{{ item.checkName }}</td>
              <td>
                <p>{{ item.checkContent || '—' }}</p>
                <p v-if="item.message" class="review-check-message">
                  {{ item.message }}
                </p>
              </td>
              <td>
                <span class="review-state" :data-status="item.status">{{
                  DIAGNOSIS_STATUS[item.status].label
                }}</span>
                <span class="review-item-duration">{{ item.durationMs }} ms</span>
              </td>
              <td class="review-impact">{{ item.impact || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="review-footnote">
        检查时间：{{
          formatDateTime(result.checkedAt)
        }}。警告与未验证项目不表示该能力已通过。
      </p>
    </template>
    <div v-else class="review-idle" role="status">
      <IconifyIcon icon="lucide:plug-zap" /><strong>尚未测试连接</strong>
      <span>点击“测试连接”查看结果，也可直接保存为未检测记录。</span>
    </div>
  </div>
</template>

<style scoped>
.connection-review {
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-width: 0;
  color: hsl(var(--foreground));
}

.review-summary {
  display: grid;
  grid-template-columns: 60px repeat(5, minmax(0, 1fr));
  gap: 20px;
  align-items: center;
  padding: 20px;
  background: hsl(var(--muted) / 45%);
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.review-summary dl {
  min-width: 0;
  margin: 0;
}

.review-summary dt {
  margin-bottom: 8px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.review-summary dd {
  margin: 0;
  font-weight: 500;
  overflow-wrap: anywhere;
}

.review-actions {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
}

.review-actions p,
.review-waiting p {
  margin: 6px 0 0;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.review-idle,
.review-waiting {
  display: flex;
  gap: 14px;
  align-items: center;
  justify-content: center;
  min-height: 180px;
  padding: 24px;
  background: hsl(var(--muted) / 28%);
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.review-idle {
  flex-direction: column;
  color: hsl(var(--muted-foreground));
  text-align: center;
}

.review-idle > :first-child {
  font-size: 28px;
  color: #4c67fa;
}

.review-result {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 18px;
  background: color-mix(in srgb, hsl(var(--card)) 94%, #4c67fa);
  border: 1px solid color-mix(in srgb, hsl(var(--border)) 60%, #4c67fa);
  border-radius: 6px;
}

.review-result > :first-child {
  flex-shrink: 0;
  font-size: 24px;
  color: #4c67fa;
}

.review-result[data-connected='true'] > :first-child {
  color: #30a176;
}

.review-result p {
  margin: 6px 0 0;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.review-duration {
  flex-shrink: 0;
  margin-left: auto;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.review-table-wrap {
  max-width: 100%;
  overflow-x: auto;
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.review-table {
  width: 100%;
  min-width: 860px;
  font-size: 14px;
  table-layout: fixed;
  border-collapse: collapse;
}

.review-column-group {
  width: 21%;
}

.review-column-item {
  width: 14%;
}

.review-column-content {
  width: 28%;
}

.review-column-status {
  width: 15%;
}

.review-column-impact {
  width: 22%;
}

.review-table th,
.review-table td {
  padding: 14px 16px;
  line-height: 1.7;
  vertical-align: top;
  text-align: left;
  overflow-wrap: anywhere;
  border-right: 1px solid hsl(var(--border));
  border-bottom: 1px solid hsl(var(--border));
}

.review-table th:last-child,
.review-table td:last-child {
  border-right: 0;
}

.review-table tbody:last-child tr:last-child td {
  border-bottom: 0;
}

.review-table tbody:last-child .review-group {
  border-bottom: 0;
}

.review-table thead {
  background: hsl(var(--muted) / 55%);
}

.review-group {
  font-weight: 400;
  background: color-mix(in srgb, hsl(var(--card)) 97%, #4c67fa);
}

.review-group strong {
  font-size: 14px;
}

.review-group p,
.review-check-message {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.review-table p {
  margin: 0 0 8px;
  white-space: pre-wrap;
}

.review-table p:last-child {
  margin-bottom: 0;
}

.review-item-duration {
  display: block;
  margin-top: 8px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.review-impact,
.review-footnote {
  color: hsl(var(--muted-foreground));
}

.review-footnote {
  margin: 0;
  font-size: 12px;
}

.review-state {
  padding: 2px 8px;
  font-size: 12px;
  border-radius: 4px;
}

.review-state[data-status='PASS'] {
  color: #279368;
  background: color-mix(in srgb, hsl(var(--card)) 90%, #30a176);
}

.review-state[data-status='WARN'] {
  color: #be861d;
  background: color-mix(in srgb, hsl(var(--card)) 90%, #e8ae40);
}

.review-state[data-status='FAIL'] {
  color: #d45d5d;
  background: color-mix(in srgb, hsl(var(--card)) 90%, #de7474);
}

.review-state[data-status='SKIPPED'] {
  color: hsl(var(--muted-foreground));
  background: hsl(var(--muted));
}

@media (max-width: 760px) {
  .review-summary {
    grid-template-columns: 44px repeat(2, minmax(0, 1fr));
    gap: 16px;
    padding: 16px;
  }

  .review-summary > :first-child {
    grid-row: span 3;
    align-self: start;
  }

  .review-result {
    flex-wrap: wrap;
  }

  .review-duration {
    width: 100%;
    margin-left: 36px;
  }

  .review-actions {
    align-items: flex-start;
  }
}

@media (max-width: 420px) {
  .review-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .review-summary > :first-child {
    grid-row: auto;
    grid-column: 1 / -1;
  }

  .review-actions {
    flex-direction: column;
  }
}
</style>
