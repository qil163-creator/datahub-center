<script lang="ts" setup>
import type { DataAssetApi, PublicationReadiness } from '#/api/asset/dataasset';

import { onBeforeUnmount, reactive, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  Form,
  FormItem,
  Input,
  message,
  Select,
  Table,
  Tag,
} from 'antdv-next';

import {
  addAssetTechLink,
  CATALOG_VERSION_STATUS_OPTIONS,
  checkCatalogVersion,
  claimReviewTicket,
  createReviewTicket,
  feedbackReviewTicket,
  getAssetCandidatePage,
  getAssetTechLinks,
  getCatalogVersions,
  getPublicationReadiness,
  getReviewTicketPage,
  publishCatalogVersion,
  reviewReviewTicket,
  submitCatalogVersion,
  TICKET_ISSUE_OPTIONS,
  TICKET_STATUS_OPTIONS,
} from '#/api/asset/dataasset';
import { resourceIdKey } from '#/utils/resource-id';

defineOptions({ name: 'AssetCatalogTab' });

const props = defineProps<{
  asset: DataAssetApi.DataAsset;
}>();

const emit = defineEmits<{ refresh: [] }>();

const access = useAccessStore();
const busy = ref(false);
const checkNote = ref('');
let epoch = 0;
let candidateEpoch = 0;
async function run(action: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  try {
    await action();
  } finally {
    busy.value = false;
  }
}
function snapshotText(json?: string) {
  try {
    return JSON.stringify(JSON.parse(json ?? '{}'), null, 2);
  } catch {
    return '历史证据格式不可读取';
  }
}

const versions = ref<DataAssetApi.CatalogVersion[]>([]);
const links = ref<DataAssetApi.TechLink[]>([]);
const tickets = ref<DataAssetApi.ReviewTicket[]>([]);
const loading = ref(false);
const readiness = ref<PublicationReadiness>();
async function assessPublication() {
  const current = epoch;
  if (!props.asset.id) return;
  const result = await getPublicationReadiness(props.asset.id);
  if (current === epoch) readiness.value = result;
}
const metaTableId = ref<DataAssetApi.Id>();
const candidates = ref<{ label: string; value: DataAssetApi.Id }[]>([]);
const ticketForm = reactive({ issueType: 1, evidence: '' });

async function load() {
  if (!props.asset.id) return;
  const current = ++epoch;
  loading.value = true;
  try {
    const [versionList, linkList, ticketPage] = await Promise.all([
      getCatalogVersions(props.asset.id),
      getAssetTechLinks(props.asset.id),
      getReviewTicketPage({
        assetId: props.asset.id,
        pageNo: 1,
        pageSize: 20,
      }),
    ]);
    if (current !== epoch) return;
    versions.value = versionList ?? [];
    links.value = linkList ?? [];
    tickets.value = ticketPage.list ?? [];
  } finally {
    if (current === epoch) loading.value = false;
  }
}

async function searchCandidates(keyword: string) {
  const current = ++candidateEpoch;
  const page = await getAssetCandidatePage({
    keyword,
    onlyUnlinked: false,
    pageNo: 1,
    pageSize: 20,
  });
  if (current !== candidateEpoch) return;
  candidates.value = (page.list ?? []).map((item) => ({
    label: `${item.tableName ?? item.id} / ${item.schemaName ?? ''}`,
    value: item.id as DataAssetApi.Id,
  }));
}

async function handleAddLink() {
  const current = epoch;
  if (!props.asset.id || !metaTableId.value) {
    message.warning('请选择要挂接的已发布元数据表');
    return;
  }
  await addAssetTechLink({
    assetId: props.asset.id,
    metaTableId: metaTableId.value,
    primaryFlag: links.value.length > 0 ? 0 : 1,
  });
  if (current !== epoch) return;
  message.success('挂接意图已登记，请稍后刷新查看后台确认结果');
  emit('refresh');
  await load();
}

async function handleSubmitVersion() {
  const current = epoch;
  if (!props.asset.id) return;
  await submitCatalogVersion(props.asset.id, props.asset.catalogRevision);
  if (current !== epoch) return;
  message.success('已提交目录版本');
  emit('refresh');
  await load();
}

async function handleCheck(row: DataAssetApi.CatalogVersion, pass: boolean) {
  const current = epoch;
  if (!row.id) return;
  if (!checkNote.value.trim()) {
    message.warning('请填写审核意见并核对冻结内容');
    return;
  }
  await checkCatalogVersion({
    id: row.id,
    status: pass ? 3 : 2,
    checkNote: checkNote.value.trim(),
  });
  if (current !== epoch) return;
  message.success(pass ? '已通过' : '已退回');
  await load();
}

async function handlePublish(row: DataAssetApi.CatalogVersion) {
  const current = epoch;
  if (!row.id) return;
  await publishCatalogVersion(row.id);
  if (current !== epoch) return;
  message.success('目录版本已发布');
  emit('refresh');
  await load();
}

async function handleCreateTicket() {
  if (!props.asset.id) return;
  await createReviewTicket({
    assetId: props.asset.id,
    catalogVersionId: props.asset.publishedCatalogVersionId,
    issueType: ticketForm.issueType,
    evidence: ticketForm.evidence,
  });
  message.success('已提交校核工单');
  ticketForm.evidence = '';
  await load();
}

async function handleTicketAction(
  row: DataAssetApi.ReviewTicket,
  action: 'claim' | 'feedback' | 'pass' | 'return',
) {
  if (!row.id) return;
  if (action === 'claim') await claimReviewTicket(row.id);
  if (action === 'feedback') await feedbackReviewTicket(row.id, '已整改');
  if (action === 'pass') await reviewReviewTicket(row.id, true, '复核通过');
  if (action === 'return') await reviewReviewTicket(row.id, false, '退回补充');
  await load();
}

function versionStatus(status?: number) {
  return (
    CATALOG_VERSION_STATUS_OPTIONS.find((item) => item.value === status)
      ?.label ?? String(status ?? '-')
  );
}

function ticketStatus(status?: number) {
  return (
    TICKET_STATUS_OPTIONS.find((item) => item.value === status)?.label ??
    String(status ?? '-')
  );
}

function ticketIssue(type?: number) {
  return (
    TICKET_ISSUE_OPTIONS.find((item) => item.value === type)?.label ??
    String(type ?? '-')
  );
}

watch(
  () => [props.asset.id, access.accessToken, access.refreshToken],
  () => {
    epoch++;
    candidateEpoch++;
    versions.value = [];
    links.value = [];
    tickets.value = [];
    readiness.value = undefined;
    candidates.value = [];
    metaTableId.value = undefined;
    checkNote.value = '';
    void load();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  epoch++;
  candidateEpoch++;
});
</script>

<template>
  <div class="flex flex-col gap-4">
    <Alert
      v-if="asset.publicationNotice"
      type="warning"
      show-icon
      :message="asset.publicationNotice"
    />
    <div class="rounded border p-3">
      <Button
        v-access:code="['asset:data-asset:update', 'asset:data-asset:publish']"
        :loading="busy"
        @click="run(assessPublication)"
      >
        <template #icon><IconifyIcon icon="lucide:file-check" /></template>
        评估发布证据与补齐步骤
      </Button>
      <div v-if="readiness" class="mt-3 flex flex-col gap-2">
        <Tag
          :color="readiness.publishedEvidenceComplete ? 'success' : 'warning'"
        >
          {{
            readiness.publishedEvidenceComplete
              ? '发布证据完整'
              : '发布证据待补齐'
          }}
        </Tag>
        <p>
          在途申请 {{ readiness.openApplications }} 笔，撤权待确认
          {{ readiness.pendingRevocations }} 笔，未关闭来源
          {{ readiness.unclosedSources }} 笔。评估不会更改资产或授权状态。
        </p>
        <ul v-if="readiness.missingItems.length" class="list-inside list-disc">
          <li v-for="item in readiness.missingItems" :key="item">{{ item }}</li>
        </ul>
        <ol class="list-inside list-decimal">
          <li v-for="step in readiness.steps" :key="step">{{ step }}</li>
        </ol>
      </div>
    </div>
    <Alert
      v-if="asset.status === 2"
      type="warning"
      :message="`已停止供数，撤权待确认 ${asset.pendingGrantRevocations ?? 0} 笔。${asset.stopSupplyReason ?? ''}`"
    />
    <div class="flex justify-between">
      <span
        >当前发布版本编号：{{
          asset.publishedCatalogVersionId ?? '尚无完整版本'
        }}</span
      >
    </div>
    <div>
      <div class="mb-2 font-medium">技术资源挂接</div>
      <div class="mb-2 flex gap-2">
        <Select
          v-model:value="metaTableId"
          :options="candidates"
          class="min-w-64 flex-1"
          placeholder="搜索已发布元数据表"
          show-search
          @search="searchCandidates"
        />
        <Button
          v-access:code="'asset:data-asset:update'"
          type="primary"
          :loading="busy"
          @click="run(handleAddLink)"
        >
          <template #icon><IconifyIcon icon="lucide:link" /></template>
          登记挂接
        </Button>
      </div>
      <Table
        :columns="[
          { title: '对象', dataIndex: 'objectName' },
          { title: '模式', dataIndex: 'schemaName' },
          { title: '主关联', dataIndex: 'primaryFlag' },
          { title: '快照', dataIndex: 'snapshotId' },
        ]"
        :data-source="links"
        :loading="loading"
        :pagination="false"
        :row-key="(row) => resourceIdKey(row.id)"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'primaryFlag'">
            <Tag v-if="record.primaryFlag === 1" color="blue">主</Tag>
            <span v-else>否</span>
          </template>
        </template>
      </Table>
    </div>

    <div>
      <div class="mb-2 flex items-center justify-between">
        <span class="font-medium">目录业务版本</span>
        <Input
          v-model:value="checkNote"
          class="mx-4 max-w-sm"
          :maxlength="500"
          placeholder="审核意见（通过或退回前填写）"
        />
        <Button
          v-access:code="'asset:data-asset:update'"
          type="primary"
          :loading="busy"
          @click="run(handleSubmitVersion)"
        >
          <template #icon><IconifyIcon icon="lucide:send" /></template>
          提交审核
        </Button>
      </div>
      <Table
        :columns="[
          { title: '版本号', dataIndex: 'versionNo', width: 80 },
          { title: '状态', dataIndex: 'status', width: 120 },
          { title: '冻结内容', dataIndex: 'contentJson' },
          { title: '审核意见', dataIndex: 'checkNote' },
          { title: '操作', key: 'action', width: 220, fixed: 'right' },
        ]"
        :data-source="versions"
        :loading="loading"
        :pagination="false"
        :scroll="{ x: 'max-content' }"
        :row-key="(row) => resourceIdKey(row.id)"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'status'">
            {{ versionStatus(record.status) }}
          </template>
          <template v-else-if="column.dataIndex === 'contentJson'">
            <details>
              <summary>查看审核时的完整冻结内容</summary>
              <pre
                class="max-h-72 max-w-xl overflow-auto whitespace-pre-wrap"
                >{{ snapshotText(record.contentJson) }}</pre>
            </details>
          </template>
          <template v-else-if="column.key === 'action'">
            <Button
              v-if="record.status === 1"
              size="small"
              type="link"
              v-access:code="'asset:data-asset:publish'"
              :disabled="busy"
              @click="run(() => handleCheck(record, true))"
            >
              <template #icon><IconifyIcon icon="lucide:check" /></template>
              通过
            </Button>
            <Button
              v-if="record.status === 1"
              size="small"
              type="link"
              v-access:code="'asset:data-asset:publish'"
              :disabled="busy"
              @click="run(() => handleCheck(record, false))"
            >
              <template #icon><IconifyIcon icon="lucide:undo-2" /></template>
              退回
            </Button>
            <Button
              v-if="
                record.status === 3 ||
                (record.status === 4 &&
                  resourceIdKey(record.id) ===
                    resourceIdKey(asset.publishedCatalogVersionId) &&
                  asset.status !== 1 &&
                  !asset.retirementRequested)
              "
              size="small"
              type="link"
              v-access:code="'asset:data-asset:publish'"
              :disabled="busy"
              @click="run(() => handlePublish(record))"
            >
              <template #icon>
                <IconifyIcon
                  :icon="
                    record.status === 4 ? 'lucide:rotate-ccw' : 'lucide:send'
                  "
                />
              </template>
              {{ record.status === 4 ? '恢复该版本新申请' : '发布' }}
            </Button>
          </template>
        </template>
      </Table>
    </div>

    <div>
      <div class="mb-2 font-medium">校核工单</div>
      <Form layout="inline" class="mb-2">
        <FormItem label="问题类型">
          <Select
            v-model:value="ticketForm.issueType"
            :options="TICKET_ISSUE_OPTIONS"
            class="w-40"
          />
        </FormItem>
        <FormItem label="证据">
          <Input
            v-model:value="ticketForm.evidence"
            class="w-64"
            :maxlength="200"
            placeholder="必要证据，勿贴明文敏感样本"
          />
        </FormItem>
        <FormItem>
          <Button type="primary" @click="handleCreateTicket">
            <template #icon><IconifyIcon icon="lucide:send" /></template>
            提交工单
          </Button>
        </FormItem>
      </Form>
      <Table
        :columns="[
          { title: '类型', dataIndex: 'issueType', width: 120 },
          { title: '状态', dataIndex: 'status', width: 100 },
          { title: '证据', dataIndex: 'evidence' },
          { title: '结论', dataIndex: 'conclusion' },
          { title: '操作', key: 'action', width: 220, fixed: 'right' },
        ]"
        :data-source="tickets"
        :loading="loading"
        :pagination="false"
        :scroll="{ x: 'max-content' }"
        :row-key="(row) => resourceIdKey(row.id)"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'issueType'">
            {{ ticketIssue(record.issueType) }}
          </template>
          <template v-else-if="column.dataIndex === 'status'">
            {{ ticketStatus(record.status) }}
          </template>
          <template v-else-if="column.key === 'action'">
            <Button
              v-if="record.status === 0"
              size="small"
              type="link"
              @click="handleTicketAction(record, 'claim')"
            >
              <template #icon>
                <IconifyIcon icon="lucide:user-check" />
              </template>
              认领
            </Button>
            <Button
              v-if="record.status === 1 || record.status === 2"
              size="small"
              type="link"
              @click="handleTicketAction(record, 'feedback')"
            >
              <template #icon>
                <IconifyIcon icon="lucide:message-square" />
              </template>
              反馈
            </Button>
            <Button
              v-if="record.status === 3 || record.status === 4"
              size="small"
              type="link"
              @click="handleTicketAction(record, 'pass')"
            >
              <template #icon>
                <IconifyIcon icon="lucide:check-check" />
              </template>
              复核通过
            </Button>
            <Button
              v-if="record.status === 3 || record.status === 4"
              size="small"
              type="link"
              @click="handleTicketAction(record, 'return')"
            >
              <template #icon><IconifyIcon icon="lucide:undo-2" /></template>
              退回
            </Button>
          </template>
        </template>
      </Table>
    </div>
  </div>
</template>

<style scoped src="../../../../table-font.css"></style>
