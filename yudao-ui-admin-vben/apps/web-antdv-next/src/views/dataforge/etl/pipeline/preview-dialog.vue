<script lang="ts" setup>
import type { DataforgeEtlApi } from '#/api/dataforge/etl';
import type {
  EtlPreviewSession,
  EtlPreviewSessionKey,
  EtlPreviewSessionRequest,
} from '#/api/dataforge/etl/preview-session';
import type { MaskedColumnInfo } from '#/components/masked-column';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, InputNumber, Modal, Select, Tag } from 'antdv-next';

import {
  cancelEtlPreviewSession,
  createEtlPreviewSession,
  getEtlPreviewCapabilities,
  getEtlPreviewSession,
} from '#/api/dataforge/etl/preview-session';
import { DataWatermarkLayer } from '#/components/data-watermark';
import { MaskedColumnTitle } from '#/components/masked-column';

import FieldContractSummary from './field-contract-summary.vue';

const props = defineProps<{
  graph: DataforgeEtlApi.GraphRequest;
  initialTargetNodeId?: string;
  open: boolean;
  parameters: Record<string, string>;
}>();
const emit = defineEmits<{ close: [] }>();
const access = useAccessStore();
const user = useUserStore();
const targetNodeId = ref<string>();
const limit = ref(100);
const loading = ref(false);
const error = ref('');
const result = ref<DataforgeEtlApi.PreviewResult>();
const selectedOutputPort = ref('');
let requestSeq = 0;
const session = ref<EtlPreviewSession>();
const sessionKey = ref<EtlPreviewSessionKey>();
const pendingRequest = ref<EtlPreviewSessionRequest>();
const cancelling = ref(false);
const closeRequested = ref(false);
let pollTimer: ReturnType<typeof setTimeout> | undefined;
let pollDeadline = 0;
let readSeq = 0;
let mounted = true;
const owner = () =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
  ]);
let sessionOwner = '';
const statuses: Record<EtlPreviewSession['status'], string> = {
  RUNNING: '预览执行中',
  SUCCEEDED: '预览完成',
  FAILED: '预览未通过',
  CANCEL_REQUESTED: '已请求取消，等待退出',
  CANCELLED: '已取消且资源退出已确认',
  TERMINATION_UNCONFIRMED: '资源退出尚未确认',
  EXPIRED: '会话已过期',
  RESULT_RESTRICTED: '结果权限复核未通过',
};
function stopPolling() {
  if (pollTimer) clearTimeout(pollTimer);
  pollTimer = undefined;
}
function schedulePoll(seq: number) {
  stopPolling();
  if (!mounted || !props.open || seq !== requestSeq) return;
  if (Date.now() > pollDeadline) {
    loading.value = false;
    error.value =
      '自动核查时间已到，尚不能确认服务端资源退出。可手动刷新会话状态。';
    return;
  }
  pollTimer = setTimeout(() => {
    void refreshSession(seq);
  }, 750);
}
function receive(response: EtlPreviewSession, seq: number, fromCreate = false) {
  if (!mounted || seq !== requestSeq || owner() !== sessionOwner) return;
  if (
    response.instanceId !== sessionKey.value?.instanceId ||
    response.requestId !== sessionKey.value?.requestId
  ) {
    error.value = '会话回执与当前请求不一致，未展示结果。';
    loading.value = false;
    return;
  }
  session.value = response;
  if (!props.open) {
    result.value = undefined;
    loading.value = false;
    return;
  }
  result.value = cancelling.value ? undefined : (response.result ?? undefined);
  if (
    response.cleanupStatus !== 'CONFIRMED' ||
    (fromCreate && response.status === 'SUCCEEDED')
  ) {
    loading.value = true;
    schedulePoll(seq);
    return;
  }
  loading.value = false;
  stopPolling();
  if (closeRequested.value) emit('close');
}
async function refreshSession(seq = requestSeq) {
  const key = sessionKey.value;
  if (!key || owner() !== sessionOwner) return;
  const currentRead = ++readSeq;
  try {
    const response = await getEtlPreviewSession(key);
    if (currentRead === readSeq) receive(response, seq);
  } catch {
    if (seq === requestSeq && currentRead === readSeq && mounted) {
      loading.value = false;
      error.value =
        '会话状态读取失败。未找到会话可能是路由实例不同、重启或过期，不能据此确认资源退出。';
      stopPolling();
    }
  }
}
async function cancelSession(close = false) {
  if (close) closeRequested.value = true;
  const key = sessionKey.value;
  if (!key) {
    ++requestSeq;
    loading.value = false;
    if (close) emit('close');
    return;
  }
  if (owner() !== sessionOwner) return;
  const seq = ++requestSeq;
  ++readSeq;
  cancelling.value = true;
  result.value = undefined;
  error.value = '';
  loading.value = true;
  stopPolling();
  try {
    receive(await cancelEtlPreviewSession(key), seq);
  } catch {
    if (seq === requestSeq && mounted) {
      loading.value = false;
      error.value =
        '取消请求的结果尚未确认。请核查或再次取消同一会话；关闭浏览器不会作为资源已退出的证明。';
    }
  }
}
/**
 * 预览可选目标节点白名单，必须与后端 EtlPreviewEngine.ALLOWED 保持一致：
 * 生成来源（generate-records / random-records）、授权整表采样（read-db，仅 ifSql=0 时可选）、
 * 受控质量节点（quality-check）以及纯内存转换；多输入节点（cartesian-product /
 * data-merge / multi-join / sort-merge）各来源最多“展示行数 + 1”行，结果放大超出预算时显式失败。
 * 不含 SQL、脚本、加解密（data-encrypt / data-decrypt）及读写外部系统（文件、HTTP、Kafka、数据库写入）节点；
 * 后端额外放行的 boundary-bridge 是内联子流程时生成的引擎内部节点，不会出现在画布上。
 */
const previewTools = new Set([
  'add-constant',
  'calculator',
  'cartesian-product',
  'column-map',
  'data-clean',
  'data-condition',
  'data-desensitize',
  'data-filter',
  'data-merge',
  'field-derive',
  'field-split',
  'flatten-row',
  'generate-records',
  'id-generator',
  'multi-join',
  'number-range',
  'quality-check',
  'random-records',
  'read-db',
  'row-column',
  'sort-merge',
  'sort-records',
  'split-to-rows',
  'value-map',
]);
const options = computed(() =>
  props.graph.nodes
    .filter(
      (node) =>
        previewTools.has(node.properties?.toolCode) &&
        (node.properties?.toolCode !== 'read-db' ||
          [0, '0'].includes(node.properties?.nodeJson?.ifSql ?? 0)),
    )
    .map((node) => ({
      value: node.id,
      label: node.properties?.name ?? node.id,
    })),
);
const columns = computed(() => result.value?.columns ?? []);
const hasInputSamples = computed(() =>
  Array.isArray(result.value?.inputSamples),
);
const inputSamples = computed(() => result.value?.inputSamples ?? []);
/** 脱敏标记：键为输入样本节点编号，目标输出用空串。 */
const maskedByNode = computed(() => {
  const groups = new Map<string, Map<string, MaskedColumnInfo>>();
  for (const item of result.value?.maskedFields ?? []) {
    const node = item.sourceNodeNo ?? '';
    const group = groups.get(node) ?? new Map<string, MaskedColumnInfo>();
    group.set(item.field, {
      categoryNames: item.categoryNames,
      name: item.field,
      reason: item.reason,
    });
    groups.set(node, group);
  }
  return groups;
});
function maskedInfo(node: null | string | undefined, field?: string) {
  return field ? maskedByNode.value.get(node ?? '')?.get(field) : undefined;
}

const QUALITY_PORT_ORDER = ['main', 'pass', 'warn', 'reject'];
const QUALITY_PORT_LABELS: Record<string, string> = {
  main: '全部',
  pass: '合格',
  warn: '警告',
  reject: '不合格',
};
const previewedNode = computed(() =>
  props.graph.nodes.find((node) => node.id === targetNodeId.value),
);
const conditionBranches = computed<{ id?: string; name?: string }[]>(() =>
  previewedNode.value?.properties?.toolCode === 'data-condition'
    ? (previewedNode.value.properties.nodeJson?.branches ?? [])
    : [],
);
function portLabel(port: string) {
  if (previewedNode.value?.properties?.toolCode === 'quality-check')
    return QUALITY_PORT_LABELS[port] ?? port;
  return (
    conditionBranches.value.find((branch) => branch.id === port)?.name || port
  );
}
/** 只有目标节点产生分支输出时才展示端口统计，单一主输出与样本输出行数重复 */
const branchPorts = computed(() => {
  const counts = result.value?.portCounts ?? {};
  const ports = Object.keys(counts);
  if (!ports.some((port) => port !== 'main')) return [];
  const order: string[] =
    previewedNode.value?.properties?.toolCode === 'quality-check'
      ? QUALITY_PORT_ORDER
      : conditionBranches.value.flatMap((branch) =>
          branch.id ? [branch.id] : [],
        );
  const rank = (port: string) =>
    order.includes(port) ? order.indexOf(port) : order.length;
  return ports
    .toSorted((left, right) => rank(left) - rank(right))
    .map((port) => ({ port, label: portLabel(port), count: counts[port] }));
});
const showRowPorts = computed(
  () =>
    branchPorts.value.length > 0 && (result.value?.rowPorts?.length ?? 0) > 0,
);
const showRowViolations = computed(
  () =>
    previewedNode.value?.properties?.toolCode === 'quality-check' &&
    (result.value?.rowViolations?.length ?? 0) > 0,
);
const outputPortOptions = computed(() => [
  { value: '', label: '全部输出端口' },
  ...branchPorts.value.map((item) => ({ value: item.port, label: item.label })),
]);
/** 保留原展示下标，使筛选后的端口和质量诊断仍引用同一条输出。 */
const outputRows = computed(() =>
  (result.value?.rows ?? [])
    .map((row, index) => ({
      row,
      index,
      port: result.value?.rowPorts?.[index] ?? 'main',
    }))
    .filter(
      (item) =>
        !selectedOutputPort.value || item.port === selectedOutputPort.value,
    ),
);
watch(result, () => {
  selectedOutputPort.value = '';
});

function violationLabels(index: number) {
  const violations = result.value?.rowViolations?.[index];
  if (violations === undefined) return '未提供诊断';
  return (
    violations
      .map((item) =>
        [item.ruleCode, item.field, item.severity, item.code]
          .filter(Boolean)
          .join(' / '),
      )
      .join('；') || '无违规规则'
  );
}

function invalidate() {
  result.value = undefined;
  error.value = '';
  if (sessionKey.value && owner() === sessionOwner) {
    void cancelSession();
    return;
  }
  ++requestSeq;
  loading.value = false;
}
watch(
  () => props.open,
  (open) => {
    if (!open) {
      void cancelSession();
      stopPolling();
      return;
    }
    if (open) {
      closeRequested.value = false;
      targetNodeId.value = options.value.some(
        (option) => option.value === props.initialTargetNodeId,
      )
        ? props.initialTargetNodeId
        : options.value.at(-1)?.value;
      if (sessionKey.value && session.value?.cleanupStatus !== 'CONFIRMED')
        void refreshSession();
    }
  },
  { immediate: true },
);
watch([targetNodeId, limit], invalidate);
watch(() => [props.graph, props.parameters], invalidate, { deep: true });
watch(
  () => [access.accessToken, access.refreshToken, user.userInfo?.userId],
  () => {
    ++requestSeq;
    stopPolling();
    loading.value = false;
    result.value = undefined;
    session.value = undefined;
    sessionKey.value = undefined;
    pendingRequest.value = undefined;
    emit('close');
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  mounted = false;
  stopPolling();
  if (sessionKey.value && owner() === sessionOwner)
    void cancelEtlPreviewSession(sessionKey.value).catch(() => {});
  ++requestSeq;
});

async function runPreview() {
  if (
    !targetNodeId.value ||
    loading.value ||
    (sessionKey.value && session.value?.cleanupStatus !== 'CONFIRMED')
  )
    return;
  const seq = ++requestSeq;
  loading.value = true;
  error.value = '';
  result.value = undefined;
  cancelling.value = false;
  closeRequested.value = false;
  sessionOwner = owner();
  try {
    if (sessionKey.value) await cancelEtlPreviewSession(sessionKey.value);
    sessionKey.value = undefined;
    session.value = undefined;
    pendingRequest.value = undefined;
    const capabilities = await getEtlPreviewCapabilities();
    if (seq !== requestSeq || !props.open || owner() !== sessionOwner) return;
    sessionKey.value = {
      instanceId: capabilities.instanceId,
      requestId: crypto.randomUUID(),
    };
    const request: EtlPreviewSessionRequest = JSON.parse(
      JSON.stringify({
        ...props.graph,
        targetNodeId: targetNodeId.value,
        limit: Math.max(1, Math.min(limit.value || 100, 100)),
        parameters: props.parameters,
        ...sessionKey.value,
        issuedAt: capabilities.issuedAt,
      }),
    );
    pendingRequest.value = request;
    pollDeadline = Date.now() + (capabilities.retentionSeconds + 60) * 1000;
    receive(await createEtlPreviewSession(request), seq, true);
  } catch {
    if (seq === requestSeq && props.open) {
      error.value =
        '预览会话受理结果尚未确认。保留当前请求编号，先核查状态或取消；不要把请求失败当作执行未开始。';
      loading.value = false;
    }
  }
}
async function retryCreate() {
  if (
    !pendingRequest.value ||
    loading.value ||
    cancelling.value ||
    owner() !== sessionOwner
  )
    return;
  const seq = requestSeq;
  loading.value = true;
  error.value = '';
  try {
    receive(await createEtlPreviewSession(pendingRequest.value), seq, true);
  } catch {
    if (seq === requestSeq) {
      loading.value = false;
      error.value = '原请求仍未确认或受理窗口已过期，请核查或取消同一会话。';
    }
  }
}
function closeWithoutConfirmation() {
  stopPolling();
  ++requestSeq;
  emit('close');
}

function displayValue(value: unknown) {
  if (value === null) return 'NULL';
  if (value === undefined) return '';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}
</script>

<template>
  <Modal
    :open="open"
    title="样本预览"
    width="1200px"
    :footer="null"
    destroy-on-hidden
    @cancel="cancelSession(true)"
  >
    <Alert
      class="mb-3"
      type="info"
      message="仅执行所选节点及其上游快照。支持生成记录、生成随机数和 PostgreSQL 整表采样（含环境逻辑连接），再执行受控字典、质量节点和纯内存转换（常量、值映射、扁平化、排序、字段映射/拆分/派生、过滤、清洗、脱敏、条件分流、计算、ID 生成、数值范围、列转多行、行列转换、合并、排序合并、多 JOIN、笛卡尔积）。自定义 SQL、HTTP、文件、脚本、加解密和目标写入不参与预览。最多 32 个节点、展示 100 行；查询和引擎执行预算 30 秒，请求总耗时可能包含额外连接检查时间。"
    />
    <p class="mb-3 text-sm text-muted-foreground">
      每个来源最多读取“展示行数 +
      1”行用于判断截断；权限过滤后可能少于请求行数，不继续补扫。输入、输出计数仅代表本次样本，不是全表总数；排序等转换也只处理这批样本，不代表全表结果；样本检测结果也不能证明全表唯一性等跨行规则成立。目标各输入端口合计最多展示指定行数，输出也最多展示指定行数，两侧共享响应字节预算。
    </p>
    <Alert
      class="mb-3"
      type="info"
      message="预览会话仅保存在当前服务实例，样本短时保留；不支持跨实例迁移或重启恢复。关闭时会请求取消，只有执行线程和数据库资源均确认退出后才显示已取消。切换身份或离开页面后仍由服务端期限触发清理。"
    />
    <div class="mb-3 flex items-center gap-3">
      <label for="etl-preview-target">预览至</label>
      <Select
        id="etl-preview-target"
        v-model:value="targetNodeId"
        class="min-w-56 flex-1"
        :options="options"
        placeholder="选择目标节点"
      />
      <label for="etl-preview-limit">行数</label>
      <InputNumber
        id="etl-preview-limit"
        v-model:value="limit"
        :min="1"
        :max="100"
        :precision="0"
      />
      <Button
        type="primary"
        :loading="loading"
        :disabled="
          !targetNodeId ||
          (!!sessionKey && session?.cleanupStatus !== 'CONFIRMED')
        "
        @click="runPreview"
      >
        <template #icon><IconifyIcon icon="lucide:play" /></template>
        执行预览
      </Button>
      <Button
        v-if="sessionKey && session?.cleanupStatus !== 'CONFIRMED'"
        @click="cancelSession()"
      >
        <template #icon><IconifyIcon icon="lucide:circle-x" /></template>
        取消服务端预览
      </Button>
    </div>
    <Alert v-if="error" class="mb-3" type="error" :message="error" />
    <div v-if="sessionKey" class="mb-3 space-y-2 text-sm">
      <p class="break-all">
        会话编号：{{ sessionKey.requestId }} · 所属实例：{{
          sessionKey.instanceId
        }}
      </p>
      <p v-if="session">
        {{ statuses[session.status] }} · 资源退出：{{
          session.cleanupStatus === 'CONFIRMED' ? '已确认' : '尚未确认'
        }}
      </p>
      <Alert
        v-if="session?.message"
        type="warning"
        :message="session.message"
      />
      <div class="flex gap-2">
        <Button @click="refreshSession()">
          <template #icon><IconifyIcon icon="lucide:shield-check" /></template>核查会话状态
        </Button>
        <Button
          v-if="error && pendingRequest && !cancelling"
          :disabled="loading"
          @click="retryCreate"
        >
          <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
          原样重试受理
        </Button>
        <Button
          v-if="closeRequested && session?.cleanupStatus !== 'CONFIRMED'"
          @click="closeWithoutConfirmation"
        >
          关闭窗口（退出仍未确认）
        </Button>
      </div>
    </div>
    <template v-if="result">
      <div class="mb-3 flex gap-3">
        <Tag :color="result.success ? 'success' : 'error'">
          {{ result.success ? '预览完成' : '预览未通过' }}
        </Tag>
        <span>来源样本输入 {{ result.inputRows }} 行 · 样本输出
          {{ result.outputRows }} 行</span>
        <Tag v-if="result.truncated" color="warning">
          来源采样或结果已截断，仅展示样本
        </Tag>
      </div>
      <p v-if="result.previewId" class="mb-2 text-sm text-muted-foreground">
        预览编号：{{ result.previewId }}
      </p>
      <p v-if="result.budget" class="mb-3 text-sm text-muted-foreground">
        累计传输 {{ result.budget.usedRows }} /
        {{ result.budget.maxRows }} 行（含各层转换）· 估算传输
        {{ result.budget.usedBytes }} / {{ result.budget.maxBytes }} 字节 ·
        中间缓存峰值 {{ result.budget.peakBufferedBytes }} 字节 · 展示数据与诊断
        {{ result.budget.responseBytes }} 字节
      </p>
      <Alert
        v-for="(issue, index) in result.issues"
        :key="index"
        class="mb-2"
        :type="issue.severity === 'ERROR' ? 'error' : 'warning'"
        :message="`${issue.nodeId ? `[${issue.nodeId}] ` : ''}${issue.message}`"
      />
      <Alert
        v-if="result.success"
        class="mb-3"
        type="info"
        message="输入与输出是独立样本，不能按行号一一对应。过滤、排序、展开和 JOIN 会改变行数、顺序或关联关系；两侧行的位置不代表同一条记录。"
      />
      <div
        v-if="result.success"
        class="preview-comparison grid gap-4 lg:grid-cols-2"
      >
        <section class="preview-inputs min-w-0" aria-label="目标输入样本">
          <h3 class="mb-2 font-semibold">目标输入样本</h3>
          <p
            v-if="
              result.targetInputRows !== undefined &&
              result.targetInputRows !== null
            "
            class="mb-3 text-sm"
          >
            流入目标 {{ result.targetInputRows }} 行（本次样本执行）
          </p>
          <Alert
            v-if="!hasInputSamples"
            type="warning"
            message="当前服务端未提供输入样本，无法判断输入是否为空；本次仅展示输出。"
          />
          <p
            v-else-if="!inputSamples.length"
            class="text-sm text-muted-foreground"
          >
            本次未记录目标输入流；来源节点本身没有上游输入。
          </p>
          <section
            v-for="(sample, sampleIndex) in inputSamples"
            :key="sampleIndex"
            class="preview-input-stream mb-4 rounded border p-3"
          >
            <h4 class="break-all font-medium">
              {{ sample.sourceNodeName || sample.sourceNodeNo }}（{{
                sample.sourceNodeNo
              }}）
            </h4>
            <p class="my-2 break-all text-sm">
              来源输出端口 {{ sample.sourceOutputPort }} → 目标输入端口
              {{ sample.inputPort }}
            </p>
            <p class="mb-2 text-sm">
              样本流经 {{ sample.rowCount }} 行 · 展示
              {{ sample.rows.length }} 行
            </p>
            <Tag v-if="sample.truncated" color="warning">
              本端口展示配额已截断
            </Tag>
            <div class="preview-table relative mt-2">
              <DataWatermarkLayer />
              <table v-if="sample.columns.length">
                <thead>
                  <tr>
                    <th v-for="column in sample.columns" :key="column.name">
                      <MaskedColumnTitle
                        :info="maskedInfo(sample.sourceNodeNo, column.name)"
                        :title="column.name ?? ''"
                      />
                      <FieldContractSummary
                        :field="column"
                        class="block whitespace-normal font-normal"
                      />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(row, index) in sample.rows" :key="index">
                    <td v-for="column in sample.columns" :key="column.name">
                      {{ displayValue(row[column.name ?? '']) }}
                    </td>
                  </tr>
                </tbody>
              </table>
              <p
                v-if="!sample.rows.length"
                class="p-3 text-sm text-muted-foreground"
              >
                本端口没有可展示的样本行；请结合样本流经行数与截断标识判断。
              </p>
            </div>
          </section>
        </section>
        <section class="preview-output min-w-0" aria-label="目标输出样本">
          <h3 class="mb-2 font-semibold">目标输出样本</h3>
          <p class="mb-3 text-sm">
            样本输出 {{ result.outputRows }} 行 · 当前展示
            {{ outputRows.length }} / {{ result.rows.length }} 行
          </p>
          <div
            v-if="branchPorts.length"
            class="preview-ports mb-3 flex flex-wrap items-center gap-2 text-sm"
          >
            <span class="text-muted-foreground">按输出端口</span>
            <Tag v-for="item in branchPorts" :key="item.port">
              {{ item.label }} {{ item.count }}
            </Tag>
          </div>
          <Select
            v-if="showRowPorts"
            id="etl-preview-output-port"
            v-model:value="selectedOutputPort"
            :options="outputPortOptions"
            class="mb-3 w-full"
            aria-label="筛选输出端口"
          />
          <div class="preview-table relative">
            <DataWatermarkLayer />
            <table v-if="columns.length">
              <thead>
                <tr>
                  <th v-if="showRowPorts">输出端口</th>
                  <th v-if="showRowViolations">违规规则</th>
                  <th v-for="column in columns" :key="column.name">
                    <MaskedColumnTitle
                      :info="maskedInfo(null, column.name)"
                      :title="column.name ?? ''"
                    />
                    <FieldContractSummary
                      :field="column"
                      class="block whitespace-normal font-normal"
                    />
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in outputRows" :key="item.index">
                  <td v-if="showRowPorts">
                    {{ portLabel(item.port) }}
                  </td>
                  <td v-if="showRowViolations">
                    {{ violationLabels(item.index) }}
                  </td>
                  <td v-for="column in columns" :key="column.name">
                    {{ displayValue(item.row[column.name ?? '']) }}
                  </td>
                </tr>
              </tbody>
            </table>
            <p
              v-if="!outputRows.length"
              class="p-3 text-center text-muted-foreground"
            >
              {{
                result.rows.length
                  ? '当前端口没有可展示的输出样本'
                  : '该节点没有输出样本'
              }}
            </p>
          </div>
        </section>
      </div>
    </template>
  </Modal>
</template>

<style scoped>
.preview-table {
  max-height: 420px;
  overflow: auto;
}

.preview-table table {
  width: 100%;
  border-collapse: collapse;
}

.preview-table th,
.preview-table td {
  padding: 8px 12px;
  text-align: left;
  white-space: nowrap;
  border: 1px solid hsl(var(--border));
}

.preview-table th {
  position: sticky;
  top: 0;
  background: hsl(var(--card));
}
</style>

<style scoped>
.preview-table thead th,
.preview-table tbody td {
  font-size: 12px;
}
</style>
