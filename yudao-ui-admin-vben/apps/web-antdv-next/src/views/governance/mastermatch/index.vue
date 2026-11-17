<script setup lang="ts">
import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';
import type { GovernanceMasterMatchApi } from '#/api/governance/mastermatch';

import { computed, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Modal as ConfirmModal,
  Empty,
  message,
  Select,
  Spin,
  Table,
  TabPane,
  Tabs,
  Tag,
} from 'antdv-next';

import {
  getGoldenPage,
  getMatchGroupPage,
  getMatchModel,
  getMatchModelPage,
  getMatchRule,
  getMatchRunPage,
  runMatch,
} from '#/api/governance/mastermatch';

import {
  displayValue,
  GROUP_STATUS_OPTIONS,
  groupStatus,
  keyLabel,
  memberSummary,
  RUN_STATUS,
} from './data';
import GroupDetail from './modules/group-detail.vue';
import RuleForm from './modules/rule-form.vue';

defineOptions({ name: 'GovernanceMasterMatch' });

const route = useRoute();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const can = {
  rule: computed(() => hasAccessByCodes(['governance:master-match:rule'])),
  run: computed(() => hasAccessByCodes(['governance:master-match:run'])),
  merge: computed(() => hasAccessByCodes(['governance:master-match:merge'])),
  ignore: computed(() => hasAccessByCodes(['governance:master-match:ignore'])),
};

const modelId = computed(() =>
  typeof route.query.modelId === 'string' && /^\d+$/u.test(route.query.modelId)
    ? route.query.modelId
    : undefined,
);
const modelOptions = ref<{ label: string; value: string }[]>([]);
const modelSearching = ref(false);
const model = ref<GovernanceMasterDataApi.Model>();
const rule = ref<GovernanceMasterMatchApi.Rule>();
const loading = ref(false);
const activeTab = ref('groups');
let modelSearchSeq = 0;
let loadSeq = 0;

async function searchModels(keyword = '') {
  const current = ++modelSearchSeq;
  modelSearching.value = true;
  try {
    const page = await getMatchModelPage({
      pageNo: 1,
      pageSize: 50,
      name: keyword.trim() || undefined,
    });
    if (current !== modelSearchSeq) return;
    modelOptions.value = page.list.map((row) => ({
      label: row.tableCn ? `${row.tableCn}（${row.tableName}）` : row.tableName!,
      value: String(row.id),
    }));
  } finally {
    if (current === modelSearchSeq) modelSearching.value = false;
  }
}
function selectModel(value: unknown) {
  void router.push({
    path: route.path,
    query: value ? { modelId: String(value) } : {},
  });
}

const groups = reactive({
  status: 'PENDING' as GovernanceMasterMatchApi.GroupStatus | undefined,
  pageNo: 1,
  pageSize: 10,
  total: 0,
  list: [] as GovernanceMasterMatchApi.Group[],
  loading: false,
});
const latestRun = ref<GovernanceMasterMatchApi.Run>();
const runs = reactive({
  pageNo: 1,
  pageSize: 10,
  total: 0,
  list: [] as GovernanceMasterMatchApi.Run[],
  loading: false,
});
const golden = reactive({
  pageNo: 1,
  pageSize: 10,
  total: 0,
  list: [] as GovernanceMasterMatchApi.Golden[],
  loading: false,
});
const running = ref(false);
const detailRef = ref<InstanceType<typeof GroupDetail>>();

const ruleFieldNames = computed(
  () => rule.value?.fields.map((field) => field.name) ?? [],
);

async function load() {
  const current = ++loadSeq;
  model.value = undefined;
  rule.value = undefined;
  latestRun.value = undefined;
  groups.list = [];
  if (!modelId.value) return;
  loading.value = true;
  try {
    const [detail, matchRule] = await Promise.all([
      getMatchModel(modelId.value),
      getMatchRule(modelId.value),
    ]);
    if (current !== loadSeq) return;
    model.value = detail;
    rule.value = matchRule;
    if (!modelOptions.value.some((row) => row.value === modelId.value)) {
      modelOptions.value = [
        {
          label: detail.tableCn
            ? `${detail.tableCn}（${detail.tableName}）`
            : detail.tableName!,
          value: String(detail.id),
        },
        ...modelOptions.value,
      ];
    }
    activeTab.value = matchRule.configured ? 'groups' : 'rule';
    groups.pageNo = 1;
    runs.pageNo = 1;
    golden.pageNo = 1;
    await Promise.all([loadGroups(), loadRuns()]);
  } finally {
    if (current === loadSeq) loading.value = false;
  }
}

async function loadGroups() {
  if (!modelId.value) return;
  const current = loadSeq;
  groups.loading = true;
  try {
    const page = await getMatchGroupPage({
      modelId: modelId.value,
      status: groups.status,
      pageNo: groups.pageNo,
      pageSize: groups.pageSize,
    });
    if (current !== loadSeq) return;
    groups.list = page.list;
    groups.total = page.total;
  } finally {
    groups.loading = false;
  }
}

async function loadRuns() {
  if (!modelId.value) return;
  const current = loadSeq;
  runs.loading = true;
  try {
    const page = await getMatchRunPage({
      modelId: modelId.value,
      pageNo: runs.pageNo,
      pageSize: runs.pageSize,
    });
    if (current !== loadSeq) return;
    runs.list = page.list;
    runs.total = page.total;
    if (runs.pageNo === 1) latestRun.value = page.list[0];
  } finally {
    runs.loading = false;
  }
}

async function loadGolden() {
  if (!modelId.value) return;
  const current = loadSeq;
  golden.loading = true;
  try {
    const page = await getGoldenPage({
      modelId: modelId.value,
      pageNo: golden.pageNo,
      pageSize: golden.pageSize,
    });
    if (current !== loadSeq) return;
    golden.list = page.list;
    golden.total = page.total;
  } finally {
    golden.loading = false;
  }
}

function confirmRun() {
  if (!modelId.value || !rule.value?.configured) return;
  ConfirmModal.confirm({
    title: '执行相似匹配',
    okText: '执行匹配',
    cancelText: '取消',
    content:
      '将读取该模型的全部记录并按规则两两比较。新结果会取代尚未处理的旧疑似重复组；已忽略的组不会再次列出。',
    async onOk() {
      running.value = true;
      try {
        const run = await runMatch(modelId.value!);
        message.success(
          `匹配完成：读取 ${run.scannedRows ?? 0} 条记录，新增 ${run.groupCount ?? 0} 组疑似重复`,
        );
        groups.status = 'PENDING';
        groups.pageNo = 1;
        runs.pageNo = 1;
      } finally {
        running.value = false;
        await Promise.all([loadGroups(), loadRuns()]);
      }
    },
  });
}

function onTabChange(key: unknown) {
  if (key === 'golden') void loadGolden();
  if (key === 'runs') void loadRuns();
  if (key === 'groups') void loadGroups();
}

function onRuleSaved(saved: GovernanceMasterMatchApi.Rule) {
  rule.value = saved;
}

function onGroupChanged() {
  void loadGroups();
  if (activeTab.value === 'golden') void loadGolden();
}

const groupColumns = [
  { title: '编号', dataIndex: 'id', key: 'id', width: 90 },
  { title: '疑似重复记录', key: 'members' },
  { title: '最高相似度', dataIndex: 'maxScore', key: 'maxScore', width: 110 },
  { title: '状态', key: 'status', width: 140 },
  { title: '处理', key: 'handled', width: 200 },
  { title: '操作', key: 'action', width: 100 },
];
const runColumns = [
  { title: '开始时间', key: 'startedAt', width: 180 },
  { title: '状态', key: 'status', width: 90 },
  { title: '读取记录', dataIndex: 'scannedRows', key: 'scannedRows', width: 100 },
  { title: '比较对数', dataIndex: 'comparedPairs', key: 'comparedPairs', width: 110 },
  { title: '新增疑似重复组', dataIndex: 'groupCount', key: 'groupCount', width: 130 },
  { title: '说明', dataIndex: 'message', key: 'message' },
  { title: '执行人', dataIndex: 'runByName', key: 'runByName', width: 100 },
];
const goldenColumns = [
  { title: '黄金记录', key: 'goldenKey' },
  { title: '已合并记录数', dataIndex: 'mergedCount', key: 'mergedCount', width: 130 },
  { title: '最近合并', key: 'lastMergedAt', width: 180 },
];
const sourceColumns = computed(() => [
  { title: '被合并记录', key: 'sourceKey', width: 140 },
  { title: '合并前内容', key: 'sourceRow' },
  { title: '合并时间', key: 'mergedAt', width: 170 },
  { title: '操作人', dataIndex: 'mergedByName', key: 'mergedByName', width: 100 },
  { title: '变更单', dataIndex: 'changeId', key: 'changeId', width: 90 },
]);

function rowText(row: GovernanceMasterMatchApi.Row) {
  const columns = model.value?.columns ?? [];
  return columns
    .filter((column) => column.pk !== 1)
    .map(
      (column) =>
        `${column.commentStr || column.name}：${displayValue(row[column.name!])}`,
    )
    .join('；');
}

watch(modelId, load, { immediate: true });
void searchModels();
</script>

<template>
  <Page auto-content-height>
    <div class="master-match">
      <div class="master-match__bar">
        <span>主数据模型</span>
        <Select
          :value="modelId"
          :options="modelOptions"
          :loading="modelSearching"
          show-search
          :filter-option="false"
          allow-clear
          placeholder="选择已发布的主数据模型"
          class="master-match__model"
          @search="searchModels"
          @update:value="selectModel"
        />
        <span class="master-match__tip">
          按规则找出疑似重复的主数据，人工确认后合并为一条黄金记录；合并沿用模型的审批配置，并保留来源追溯。
        </span>
      </div>

      <Empty
        v-if="!modelId"
        class="mt-16"
        description="请选择要治理的主数据模型"
      />
      <Spin v-else-if="loading" class="mt-16 block text-center" />
      <Tabs
        v-else-if="model && rule"
        v-model:active-key="activeTab"
        class="master-match__tabs"
        @change="onTabChange"
      >
        <TabPane key="groups" tab="疑似重复">
          <Alert
            v-if="!rule.configured"
            type="warning"
            show-icon
            class="mb-3"
            message="请先在“匹配规则”中配置并保存规则"
          />
          <div class="master-match__toolbar">
            <Select
              v-model:value="groups.status"
              :options="GROUP_STATUS_OPTIONS"
              allow-clear
              placeholder="全部状态"
              class="w-40"
              @change="
                () => {
                  groups.pageNo = 1;
                  void loadGroups();
                }
              "
            />
            <span v-if="latestRun" class="master-match__run">
              最近一次匹配：{{
                latestRun.startedAt ? formatDateTime(latestRun.startedAt) : '-'
              }}
              <Tag :color="RUN_STATUS[latestRun.status]?.color">
                {{ RUN_STATUS[latestRun.status]?.label ?? latestRun.status }}
              </Tag>
              <template v-if="latestRun.status === 'SUCCESS'">
                读取 {{ latestRun.scannedRows }} 条，新增
                {{ latestRun.groupCount }} 组
              </template>
              <span v-if="latestRun.message" class="master-match__muted">
                {{ latestRun.message }}
              </span>
            </span>
            <Button
              v-if="can.run.value"
              type="primary"
              class="ml-auto"
              :loading="running"
              :disabled="!rule.configured"
              @click="confirmRun"
            >
              执行匹配
            </Button>
          </div>
          <Table
            :columns="groupColumns"
            :data-source="groups.list"
            :loading="groups.loading"
            row-key="id"
            size="middle"
            :pagination="{
              current: groups.pageNo,
              pageSize: groups.pageSize,
              total: groups.total,
              showSizeChanger: true,
              onChange: (page: number, size: number) => {
                groups.pageNo = page;
                groups.pageSize = size;
                void loadGroups();
              },
            }"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'members'">
                <div
                  v-for="member in record.members"
                  :key="member.key"
                  class="master-match__member"
                >
                  {{ memberSummary(member, ruleFieldNames) }}
                </div>
              </template>
              <template v-else-if="column.key === 'status'">
                <Tag :color="groupStatus(record.status).color">
                  {{ groupStatus(record.status).label }}
                </Tag>
                <div
                  v-if="record.changeStatus === 'REJECTED'"
                  class="master-match__muted"
                >
                  上次合并申请已驳回
                </div>
              </template>
              <template v-else-if="column.key === 'handled'">
                <template v-if="record.handledAt">
                  {{ record.handledByName || '-' }}
                  <div class="master-match__muted">
                    {{ formatDateTime(record.handledAt) }}
                  </div>
                  <div v-if="record.handleRemark" class="master-match__muted">
                    {{ record.handleRemark }}
                  </div>
                </template>
                <template v-else>-</template>
              </template>
              <template v-else-if="column.key === 'action'">
                <Button
                  type="link"
                  size="small"
                  @click="detailRef?.open(record.id)"
                >
                  {{ record.status === 'PENDING' ? '处理' : '查看' }}
                </Button>
              </template>
            </template>
          </Table>
        </TabPane>

        <TabPane key="rule" tab="匹配规则">
          <RuleForm
            :model="model"
            :rule="rule"
            :can-edit="can.rule.value"
            @saved="onRuleSaved"
          />
        </TabPane>

        <TabPane key="golden" tab="黄金记录">
          <p class="master-match__muted">
            合并后保留记录即为黄金记录；这里列出每条黄金记录合并了哪些记录及其合并前的内容。黄金记录再被合并时，来源随之归入新的黄金记录。
          </p>
          <Table
            :columns="goldenColumns"
            :data-source="golden.list"
            :loading="golden.loading"
            row-key="goldenKey"
            size="middle"
            :pagination="{
              current: golden.pageNo,
              pageSize: golden.pageSize,
              total: golden.total,
              onChange: (page: number, size: number) => {
                golden.pageNo = page;
                golden.pageSize = size;
                void loadGolden();
              },
            }"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'goldenKey'">
                {{ keyLabel(record.goldenKey) }}
              </template>
              <template v-else-if="column.key === 'lastMergedAt'">
                {{
                  record.lastMergedAt ? formatDateTime(record.lastMergedAt) : '-'
                }}
              </template>
            </template>
            <template #expandedRowRender="{ record }">
              <Table
                :columns="sourceColumns"
                :data-source="record.sources"
                :pagination="false"
                row-key="sourceKey"
                size="small"
              >
                <template #bodyCell="{ column, record: source }">
                  <template v-if="column.key === 'sourceKey'">
                    {{ keyLabel(source.sourceKey) }}
                  </template>
                  <template v-else-if="column.key === 'sourceRow'">
                    {{ rowText(source.sourceRow) }}
                  </template>
                  <template v-else-if="column.key === 'mergedAt'">
                    {{ source.mergedAt ? formatDateTime(source.mergedAt) : '-' }}
                  </template>
                </template>
              </Table>
            </template>
          </Table>
        </TabPane>

        <TabPane key="runs" tab="执行记录">
          <Table
            :columns="runColumns"
            :data-source="runs.list"
            :loading="runs.loading"
            row-key="id"
            size="middle"
            :pagination="{
              current: runs.pageNo,
              pageSize: runs.pageSize,
              total: runs.total,
              onChange: (page: number, size: number) => {
                runs.pageNo = page;
                runs.pageSize = size;
                void loadRuns();
              },
            }"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'startedAt'">
                {{ record.startedAt ? formatDateTime(record.startedAt) : '-' }}
              </template>
              <template v-else-if="column.key === 'status'">
                <Tag :color="RUN_STATUS[record.status]?.color">
                  {{ RUN_STATUS[record.status]?.label ?? record.status }}
                </Tag>
              </template>
            </template>
          </Table>
        </TabPane>
      </Tabs>
    </div>
    <GroupDetail
      v-if="model"
      ref="detailRef"
      :model="model"
      :can-merge="can.merge.value"
      :can-ignore="can.ignore.value"
      @changed="onGroupChanged"
    />
  </Page>
</template>

<style scoped>
.master-match {
  padding: 16px;
  background: var(--ant-color-bg-container, #fff);
  border-radius: 8px;
}

.master-match__bar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  margin-bottom: 8px;
}

.master-match__model {
  width: 360px;
  max-width: 100%;
}

.master-match__tip,
.master-match__muted {
  font-size: 12px;
  color: var(--ant-color-text-tertiary, #8c8c8c);
}

.master-match__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}

.master-match__run {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  font-size: 13px;
}

.master-match__member {
  line-height: 1.8;
}
</style>
