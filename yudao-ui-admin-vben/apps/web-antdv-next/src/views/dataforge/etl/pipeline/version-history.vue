<script lang="ts" setup>
import type { DataforgeEtlApi } from '#/api/dataforge/etl';
import type { EtlVersionCompare } from '#/api/dataforge/etl/version';

import { onBeforeUnmount, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Drawer,
  Input,
  Modal,
  Spin,
  Table,
  Tag,
} from 'antdv-next';

import {
  diffEtlVersion,
  getEtlVersion,
  getEtlVersionPage,
  restoreEtlVersion,
} from '#/api/dataforge/etl';
import { compareEtlVersions } from '#/api/dataforge/etl/version';

const props = defineProps<{ canRestore: boolean }>();
const emit = defineEmits<{ restored: [revision: number] }>();
const accessStore = useAccessStore();
const userStore = useUserStore();
const visible = ref(false);
const pipelineId = ref<DataforgeEtlApi.Id>();
const expectedRevision = ref(0);
const versions = ref<DataforgeEtlApi.Version[]>([]);
const pageNo = ref(1);
const total = ref(0);
const loading = ref(false);
const loadError = ref('');
const detailVisible = ref(false);
const detail = ref<DataforgeEtlApi.Version>();
const differences = ref<DataforgeEtlApi.VersionDiff[]>([]);
const detailLoading = ref(false);
const detailError = ref('');
const diffError = ref('');
const compareSelected = ref<DataforgeEtlApi.Version[]>([]);
const compareVisible = ref(false);
const comparing = ref(false);
const comparison = ref<EtlVersionCompare>();
const compareError = ref('');
const restoreVisible = ref(false);
const restoring = ref(false);
const restoreTarget = ref<DataforgeEtlApi.Version>();
const reason = ref('');
const restoreError = ref('');
let listSeq = 0;
let detailSeq = 0;
let restoreSeq = 0;
let compareSeq = 0;
const diffLabels: Record<DataforgeEtlApi.VersionDiff['type'], string> = {
  EDGES_CHANGED: '连线或端口变化',
  ENVIRONMENT_CHANGED: '运行环境变化',
  NODE_ADDED: '新增节点',
  NODE_CHANGED: '节点配置变化',
  NODE_REMOVED: '删除节点',
  PARAMETERS_CHANGED: '流程参数变化',
  QUALITY_TASK_CHANGED: '写入后质量验收变化',
  SCHEDULE_CHANGED: '调度配置变化',
};
const compareLabels: Record<
  EtlVersionCompare['changes'][number]['type'],
  string
> = {
  EDGE_ADDED: '新增连线',
  EDGE_REMOVED: '删除连线',
  ENVIRONMENT_CHANGED: '运行环境变化',
  NODE_ADDED: '新增节点',
  NODE_CONFIG_CHANGED: '节点配置变化',
  NODE_METADATA_CHANGED: '节点名称或执行策略变化',
  NODE_REMOVED: '删除节点',
  NODE_TOOL_CHANGED: '节点组件变化',
  PARAMETERS_CHANGED: '流程参数定义变化',
  QUALITY_TASK_CHANGED: '质量验收任务变化',
  SCHEDULE_CHANGED: '调度配置变化',
  SUBFLOW_VERSION_CHANGED: '子流程固定版本变化',
};
const columns = [
  { title: '版本 ID', dataIndex: 'id', key: 'id', width: 170 },
  { title: '草稿修订', dataIndex: 'revision', key: 'revision', width: 90 },
  { title: '事件', key: 'eventType', width: 100 },
  { title: '操作人ID', dataIndex: 'actorId', key: 'actorId', width: 150 },
  {
    title: '时间',
    dataIndex: 'createTime',
    key: 'createTime',
    width: 180,
    customRender: ({ text }: { text: number }) => formatDateTime(text),
  },
  { title: '原因', dataIndex: 'reason', key: 'reason' },
  { title: '操作', key: 'actions', width: 400, fixed: 'right' as const },
];

function closeDetail() {
  ++detailSeq;
  detailVisible.value = false;
  detailLoading.value = false;
  detail.value = undefined;
  differences.value = [];
  detailError.value = '';
  diffError.value = '';
}
function closeComparison() {
  ++compareSeq;
  compareVisible.value = false;
  comparing.value = false;
  comparison.value = undefined;
  compareError.value = '';
}
function closeRestore() {
  ++restoreSeq;
  restoreVisible.value = false;
  restoring.value = false;
  restoreTarget.value = undefined;
  reason.value = '';
  restoreError.value = '';
}
function close() {
  ++listSeq;
  visible.value = false;
  loading.value = false;
  versions.value = [];
  total.value = 0;
  closeDetail();
  closeComparison();
  compareSelected.value = [];
  closeRestore();
}
async function open(id: DataforgeEtlApi.Id, revision: number) {
  close();
  pipelineId.value = id;
  expectedRevision.value = revision;
  visible.value = true;
  await loadVersions();
}
defineExpose({ open, close });

async function loadVersions(page = 1) {
  if (pipelineId.value === undefined) return;
  const seq = ++listSeq;
  loading.value = true;
  loadError.value = '';
  pageNo.value = page;
  try {
    const result = await getEtlVersionPage({
      pipelineId: pipelineId.value,
      pageNo: page,
      pageSize: 20,
    });
    if (seq !== listSeq || !visible.value) return;
    versions.value = result.list;
    total.value = result.total;
  } catch {
    if (seq === listSeq) loadError.value = '版本历史加载失败，请重试。';
  } finally {
    if (seq === listSeq) loading.value = false;
  }
}

async function showVersion(version: DataforgeEtlApi.Version) {
  closeDetail();
  const seq = detailSeq;
  detailVisible.value = true;
  detailLoading.value = true;
  const [snapshot, diff] = await Promise.allSettled([
    getEtlVersion(version.id),
    diffEtlVersion(version.id),
  ]);
  if (seq !== detailSeq || !visible.value || !detailVisible.value) return;
  if (snapshot.status === 'fulfilled') detail.value = snapshot.value;
  else detailError.value = '版本快照加载失败，请关闭后重试。';
  if (diff.status === 'fulfilled') differences.value = diff.value;
  else diffError.value = '差异比较失败，无法判断与当前草稿的差异。';
  detailLoading.value = false;
}

function toggleCompare(version: DataforgeEtlApi.Version) {
  closeComparison();
  const selected = compareSelected.value;
  const existing = selected.findIndex(
    (item) => String(item.id) === String(version.id),
  );
  if (existing !== -1) {
    selected.splice(existing, 1);
    return;
  }
  if (selected.length >= 2) {
    compareError.value = '一次最多选择两个版本；请先移除已选版本。';
    return;
  }
  selected.push(version);
  compareSelected.value = [...selected];
}
function isCompareSelected(version: DataforgeEtlApi.Version) {
  return compareSelected.value.some(
    (item) => String(item.id) === String(version.id),
  );
}
function clearCompareSelection() {
  closeComparison();
  compareSelected.value = [];
}

function actorContext() {
  return [
    String(accessStore.accessToken ?? ''),
    String(accessStore.refreshToken ?? ''),
    String(userStore.userInfo?.userId ?? ''),
  ].join('|');
}

async function compareSelectedVersions() {
  if (compareSelected.value.length !== 2 || comparing.value) return;
  const [source, target] = compareSelected.value;
  if (!source || !target) return;
  closeComparison();
  compareVisible.value = true;
  comparing.value = true;
  const seq = ++compareSeq;
  const context = actorContext();
  try {
    const result = await compareEtlVersions({
      sourceVersionId: source.id,
      targetVersionId: target.id,
    });
    if (
      seq !== compareSeq ||
      !visible.value ||
      !compareVisible.value ||
      actorContext() !== context ||
      compareSelected.value.length !== 2 ||
      String(compareSelected.value[0]?.id) !== String(source.id) ||
      String(compareSelected.value[1]?.id) !== String(target.id)
    )
      return;
    comparison.value = result;
  } catch {
    if (
      seq === compareSeq &&
      visible.value &&
      compareVisible.value &&
      actorContext() === context
    ) {
      compareError.value = '版本比较失败，请确认两个版本仍属于当前流程并重试。';
    }
  } finally {
    if (seq === compareSeq) comparing.value = false;
  }
}

function requestRestore(version: DataforgeEtlApi.Version) {
  if (!props.canRestore) return;
  closeRestore();
  restoreTarget.value = version;
  restoreVisible.value = true;
}

async function restore() {
  if (!props.canRestore || restoring.value || !restoreTarget.value) return;
  if (!reason.value.trim()) {
    restoreError.value = '请填写恢复原因。';
    return;
  }
  const seq = ++restoreSeq;
  restoring.value = true;
  restoreError.value = '';
  try {
    const revision = await restoreEtlVersion({
      versionId: restoreTarget.value.id,
      expectedRevision: expectedRevision.value,
      reason: reason.value.trim(),
    });
    if (seq !== restoreSeq || !visible.value) return;
    close();
    emit('restored', revision);
  } catch {
    if (seq === restoreSeq && visible.value)
      restoreError.value =
        '恢复未确认，当前画布保持不变。若草稿已被他人修改，请重新载入后再比较和恢复。';
  } finally {
    if (seq === restoreSeq) restoring.value = false;
  }
}
watch(
  () => [
    accessStore.accessToken,
    accessStore.refreshToken,
    userStore.userInfo?.userId,
  ],
  close,
);
onBeforeUnmount(close);
</script>

<template>
  <Drawer
    :open="visible"
    title="流程版本历史"
    size="1050px"
    destroy-on-hidden
    @close="close"
  >
    <div class="mb-3 flex items-center justify-between gap-2">
      <span>流程 #{{ pipelineId }} · 打开时草稿 r{{ expectedRevision }}</span>
      <Button :loading="loading" @click="loadVersions(pageNo)"
        ><template #icon><IconifyIcon icon="lucide:refresh-cw" /></template
        >刷新列表</Button
      >
    </div>
    <div class="mb-3 flex items-center gap-3">
      <span
        >已选比较版本：{{
          compareSelected.map((item) => `#${item.id}`).join(' → ') || '未选择'
        }}</span
      >
      <Button
        :disabled="compareSelected.length !== 2 || comparing"
        :loading="comparing"
        @click="compareSelectedVersions"
      >
        <template #icon
          ><IconifyIcon icon="lucide:git-compare-arrows"
        /></template>
        比较两个版本
      </Button>
      <Button v-if="compareSelected.length" @click="clearCompareSelection">
        <template #icon><IconifyIcon icon="lucide:list-x" /></template>
        清空选择
      </Button>
    </div>
    <Alert
      v-if="compareError"
      class="mb-3"
      type="error"
      :message="compareError"
    />
    <Alert
      class="mb-3"
      type="info"
      message="版本记录发布和恢复时的不可变快照；差异与当前已保存草稿比较。恢复只创建新草稿，当前发布版和调度继续使用原快照。"
    />
    <Alert v-if="loadError" class="mb-3" type="error" :message="loadError" />
    <Table
      :scroll="{ x: 'max-content' }"
      :data-source="versions"
      :columns="columns"
      :loading="loading"
      :row-key="(row: DataforgeEtlApi.Version) => String(row.id)"
      :pagination="{
        current: pageNo,
        pageSize: 20,
        total,
        showSizeChanger: false,
        onChange: loadVersions,
      }"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <Tag v-if="column.key === 'eventType'">
          {{ record.eventType === 'PUBLISH' ? '发布' : '恢复草稿' }}
        </Tag>
        <div v-else-if="column.key === 'actions'" class="flex gap-2">
          <Button type="link" size="small" @click="toggleCompare(record)">
            <template #icon
              ><IconifyIcon icon="lucide:git-compare-arrows"
            /></template>
            {{ isCompareSelected(record) ? '取消比较选择' : '选择比较' }}
          </Button>
          <Button type="link" size="small" @click="showVersion(record)">
            <template #icon
              ><IconifyIcon icon="lucide:git-compare-arrows"
            /></template>
            快照与差异
          </Button>
          <Button
            v-if="canRestore"
            type="link"
            size="small"
            @click="requestRestore(record)"
          >
            <template #icon><IconifyIcon icon="lucide:rotate-ccw" /></template>
            恢复为新草稿
          </Button>
        </div>
      </template>
    </Table>
    <Modal
      :open="detailVisible"
      title="版本快照与差异"
      width="850px"
      :footer="null"
      destroy-on-hidden
      @cancel="closeDetail"
    >
      <Spin :spinning="detailLoading">
        <Alert
          v-if="detailError"
          type="error"
          :message="detailError"
          class="mb-3"
        />
        <template v-if="detail">
          <p class="mb-2">
            版本 #{{ detail.id }} · 草稿 r{{ detail.revision }} · 来源版本
            {{ detail.sourceVersionId ?? '无' }} · 发布代次
            {{ detail.releaseGeneration ?? '无' }}
          </p>
          <p class="mb-3 break-all text-xs text-muted-foreground">
            内容摘要 {{ detail.contentHash }}
          </p>
        </template>
        <p class="mb-2 text-xs text-muted-foreground">
          差异方向：历史版本 → 服务端当前已保存草稿
        </p>
        <Alert v-if="diffError" type="error" :message="diffError" />
        <p v-else-if="!detailLoading && !differences.length" class="mb-3">
          与当前已保存草稿无差异
        </p>
        <ul v-else class="mb-3 list-disc pl-5">
          <li v-for="(item, index) in differences" :key="index">
            {{ diffLabels[item.type] ?? item.type }} {{ item.objectId }}
          </li>
        </ul>
        <details v-if="detail?.configJson">
          <summary class="cursor-pointer">查看原始快照 JSON</summary>
          <pre class="version-json">{{ detail.configJson }}</pre>
        </details>
      </Spin>
    </Modal>
    <Modal
      :open="compareVisible"
      title="不可变版本比较"
      width="850px"
      :footer="null"
      destroy-on-hidden
      @cancel="closeComparison"
    >
      <Spin :spinning="comparing">
        <Alert
          type="info"
          class="mb-3"
          :message="`版本 #${compareSelected[0]?.id} → #${compareSelected[1]?.id} · 仅显示差异类别和对象 ID，不展示配置内容`"
        />
        <Alert v-if="compareError" type="error" :message="compareError" />
        <p v-else-if="!comparing && comparison?.changes.length === 0">
          两个不可变版本没有业务差异。
        </p>
        <ul v-else-if="comparison" class="list-disc pl-5">
          <li v-for="(item, index) in comparison.changes" :key="index">
            {{ compareLabels[item.type] ?? item.type }} {{ item.objectId }}
            <span v-if="item.count > 1">（{{ item.count }} 项）</span>
          </li>
        </ul>
      </Spin>
    </Modal>
    <Modal
      :open="restoreVisible"
      title="恢复为新草稿"
      :confirm-loading="restoring"
      :ok-button-props="{ disabled: !canRestore || restoring }"
      :cancel-button-props="{ disabled: restoring }"
      :closable="!restoring"
      :mask-closable="!restoring"
      :keyboard="!restoring"
      @ok="restore"
      @cancel="closeRestore"
    >
      <Alert
        type="warning"
        message="恢复节点、连线、参数、质量验收与调度配置，保留流程名称、机构和描述；不会替换当前发布版或当前调度。再次发布时将重新获取当前授权的规则和映射快照。"
      />
      <p class="my-3">
        来源版本 #{{ restoreTarget?.id }} · 以打开时草稿 r{{ expectedRevision }}
        为基准执行并发校验。
      </p>
      <Input.TextArea
        v-model:value="reason"
        :rows="3"
        :maxlength="500"
        :disabled="restoring"
        placeholder="恢复原因（必填）"
      />
      <Alert
        v-if="restoreError"
        type="error"
        :message="restoreError"
        class="mt-3"
      />
    </Modal>
  </Drawer>
</template>

<style scoped>
.version-json {
  max-height: 400px;
  padding: 12px;
  margin-top: 10px;
  overflow: auto;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  background: hsl(var(--muted));
  border-radius: 6px;
}
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
