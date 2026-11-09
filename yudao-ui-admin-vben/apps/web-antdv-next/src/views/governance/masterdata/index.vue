<script setup lang="ts">
import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';
import type { GovernanceMasterDomainApi } from '#/api/governance/masterdomain';

import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import { useMediaQuery } from '@vueuse/core';
import {
  Alert,
  Button,
  Input,
  message,
  Modal,
  Switch,
  Tag,
  Tooltip,
  Tree,
} from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  cancelReleaseMasterModel,
  deleteMasterModel,
  deleteMasterModelList,
  getMasterModelDataCount,
  getMasterModelPage,
  getMasterModelStatistics,
  releaseMasterModel,
  verifyMasterModelRelease,
} from '#/api/governance/masterdata';
import { getMasterDomainSimpleList } from '#/api/governance/masterdomain';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { parseResourceId } from '#/utils/resource-id';

import { useTreeExpansion } from '../common/use-tree-expansion';
import MasterWorkspace from '../mastercommon/master-workspace.vue';
import { useMasterScope } from '../mastercommon/use-master-scope';
import {
  MODEL_STATUS_OPTIONS,
  useGridColumns,
  useGridFormSchema,
} from './data';
import { canDeleteMasterModel } from './model-delete';
import ModelForm from './modules/model-form.vue';

defineOptions({ name: 'GovernanceMasterData' });
const router = useRouter();
const compact = useMediaQuery('(max-width: 767px)');
const { hasAccessByCodes } = useAccess();
const canReadDataCount = computed(
  () =>
    hasAccessByCodes(['governance:master-model:query']) &&
    hasAccessByCodes(['governance:master-data:query']),
);
const pendingWrites = ref(new Set<string>());
const dataCounts = ref<
  Record<
    string,
    Partial<GovernanceMasterDataApi.ModelDataCount> & { loading: boolean }
  >
>({});
const countRequests = new Set<Promise<GovernanceMasterDataApi.ModelDataCount>>();
const maxConcurrentCounts = 2;
let countGeneration = 0;
const domains = ref<GovernanceMasterDomainApi.Domain[]>([]);
const domainKeyword = ref('');
const allModelCount = computed(() =>
  domains.value
    .reduce((count, row) => count + BigInt(row.modelCount ?? 0), 0n)
    .toString(),
);
const selectedDomain = ref<GovernanceMasterDataApi.Id>();
const selectedStatus = ref<number>();
const visibleDomains = computed(() =>
  domains.value.filter((row) =>
    row.name.toLowerCase().includes(domainKeyword.value.trim().toLowerCase()),
  ),
);
const domainExpandedKeys = ref<string[]>(['root']);
const domainTree = computed(() => [
  {
    key: 'root',
    title: '全部模型',
    count: allModelCount.value,
    children: visibleDomains.value
      .filter((domain) => domain.id !== undefined && domain.id !== null)
      .map((domain) => ({
        key: `domain:${String(domain.id)}`,
        title: domain.name,
        count: domain.modelCount ?? 0,
      })),
  },
]);
const domainTreeSelectedKeys = computed(() => [
  selectedDomain.value === undefined
    ? 'root'
    : `domain:${String(selectedDomain.value)}`,
]);
const statistics = ref<GovernanceMasterDataApi.Statistics>({
  total: 0,
  released: 0,
  draft: 0,
  offline: 0,
});
const loading = ref(false);
const failed = ref(false);
const domainsFailed = ref(false);
const domainsLoading = ref(false);
const selectedKeys = ref<GovernanceMasterDataApi.Id[]>([]);
const rows = ref<GovernanceMasterDataApi.Model[]>([]);
const canDeleteSelection = computed(
  () =>
    selectedKeys.value.length > 0 && isDeletableSelection(selectedKeys.value),
);
const pagination = reactive({ pageNo: 1, pageSize: 20, total: 0 });
const scope = useMasterScope(() => {
  selectedDomain.value = undefined;
  selectedStatus.value = undefined;
  selectedKeys.value = [];
  rows.value = [];
  domains.value = [];
  pendingWrites.value.clear();
  dataCounts.value = {};
  ++countGeneration;
  statistics.value = { total: 0, released: 0, draft: 0, offline: 0 };
  gridApi.setGridOptions({ data: [], pagerConfig: { total: 0 } });
  void refresh();
});
const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: ModelForm,
  destroyOnClose: true,
});
const cards = computed(() => [
  {
    name: '全部模型',
    value: statistics.value.total,
    color: 'blue',
    icon: 'lucide:layout-grid',
    status: undefined,
  },
  {
    name: '已发布',
    value: statistics.value.released,
    color: 'green',
    icon: 'lucide:circle-check',
    status: 1,
  },
  {
    name: '草稿',
    value: statistics.value.draft,
    color: 'orange',
    icon: 'lucide:pencil-line',
    status: 0,
  },
  {
    name: '已下线',
    value: statistics.value.offline,
    color: 'red',
    icon: 'lucide:ban',
    status: 2,
  },
]);
const [Grid, gridApi] = useVbenVxeGrid<GovernanceMasterDataApi.Model>({
  formOptions: {
    schema: useGridFormSchema(),
    handleSubmit: search,
    handleReset: async () => {
      await gridApi.formApi.reset();
      await search();
    },
  },
  gridOptions: {
    checkboxConfig: {
      checkMethod: ({ row }: { row: GovernanceMasterDataApi.Model }) =>
        canDeleteMasterModel(row),
    },
    columns: useGridColumns(compact.value),
    data: [],
    height: 'auto',
    rowConfig: { keyField: 'id', isHover: true },
    stripe: true,
    showOverflow: false,
    pagerConfig: { pageSize: 20, currentPage: 1, total: 0 },
    proxyConfig: { enabled: false, autoLoad: false },
    toolbarConfig: {
      refresh: true,
      refreshOptions: { queryMethod: load },
      search: true,
    },
  },
  gridEvents: {
    checkboxAll: ({
      records,
    }: {
      records: GovernanceMasterDataApi.Model[];
    }) => {
      selectedKeys.value = records
        .filter((row) => canDeleteMasterModel(row))
        .map((row) => row.id!);
    },
    checkboxChange: ({
      records,
    }: {
      records: GovernanceMasterDataApi.Model[];
    }) => {
      selectedKeys.value = records
        .filter((row) => canDeleteMasterModel(row))
        .map((row) => row.id!);
    },
    pageChange: ({
      currentPage,
      pageSize,
    }: {
      currentPage: number;
      pageSize: number;
    }) => {
      pagination.pageNo = currentPage;
      pagination.pageSize = pageSize;
      void load();
    },
  },
});
watch(compact, (value) => {
  gridApi.setGridOptions({ columns: useGridColumns(value) });
});
function status(row: GovernanceMasterDataApi.Model) {
  return MODEL_STATUS_OPTIONS.find((item) => item.value === row.status);
}
async function loadDomains() {
  const current = scope.begin('domains');
  domainsFailed.value = false;
  domainsLoading.value = true;
  try {
    const list = await getMasterDomainSimpleList();
    if (current()) domains.value = list;
  } catch {
    if (current()) domainsFailed.value = true;
  } finally {
    if (current()) domainsLoading.value = false;
  }
}
async function load() {
  const current = scope.begin();
  ++countGeneration;
  dataCounts.value = {};
  loading.value = true;
  gridApi.setLoading(true);
  failed.value = false;
  try {
    const query = await gridApi.formApi.getValues();
    if (!current()) return;
    selectedStatus.value = query.status;
    const [page, summary] = await Promise.all([
      getMasterModelPage({
        ...query,
        domainId: selectedDomain.value,
        pageNo: pagination.pageNo,
        pageSize: pagination.pageSize,
      }),
      getMasterModelStatistics(selectedDomain.value),
    ]);
    if (!current()) return;
    pagination.total = page.total;
    statistics.value = summary;
    rows.value = page.list;
    selectedKeys.value = [];
    gridApi.setGridOptions({
      data: page.list,
      pagerConfig: {
        currentPage: pagination.pageNo,
        pageSize: pagination.pageSize,
        total: page.total,
      },
    });
    await gridApi.grid.clearCheckboxRow();
    if (current()) {
      for (const model of page.list) void queryDataCount(model);
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) {
      loading.value = false;
      gridApi.setLoading(false);
    }
  }
}
async function refresh() {
  await Promise.all([loadDomains(), load()]);
}
async function search() {
  pagination.pageNo = 1;
  await load();
}
async function selectStatus(value?: number) {
  await gridApi.formApi.setFieldValue('status', value);
  await search();
}
function selectDomain(id?: GovernanceMasterDataApi.Id) {
  selectedDomain.value = id;
  selectedKeys.value = [];
  void search();
}
function selectDomainTree(keys: (number | string)[]) {
  if (keys.length === 0) return;
  const key = String(keys[0]);
  if (key === 'root') {
    selectDomain();
    return;
  }
  const domain = domains.value.find(
    (row) => `domain:${String(row.id)}` === key,
  );
  if (domain?.id !== undefined && domain.id !== null) selectDomain(domain.id);
}
function edit(row?: GovernanceMasterDataApi.Model) {
  formModalApi.setData({ id: row?.id, domainId: selectedDomain.value }).open();
}
function detail(row: GovernanceMasterDataApi.Model) {
  const id = parseResourceId(row.id);
  if (
    !id ||
    !hasAccessByCodes(['governance:master-model:query'])
  )
    return;
  void router.push({ name: 'GovernanceMasterModelDetail', params: { id } });
}
function modelTitle(row: GovernanceMasterDataApi.Model) {
  return `${row.tableCn || '-'}${row.tableName ? ` (${row.tableName})` : ''}`;
}
function isCountableModel(row: GovernanceMasterDataApi.Model) {
  return row.id !== undefined && row.id !== null && isReadyModel(row);
}

async function queryDataCount(row: GovernanceMasterDataApi.Model) {
  const modelId = row.id;
  if (
    modelId === undefined ||
    modelId === null ||
    !isReadyModel(row) ||
    !canReadDataCount.value
  )
    return;
  const id = String(modelId);
  if (dataCounts.value[id]?.loading) return;
  const current = scope.begin(`data-count:${id}`);
  const generation = countGeneration;
  const active = () =>
    current() && generation === countGeneration && canReadDataCount.value;
  dataCounts.value[id] = { loading: true };
  let request: Promise<GovernanceMasterDataApi.ModelDataCount> | undefined;
  try {
    // DB Switch 默认每个用户允许同时执行两次计数，翻页或重试也共用额度。
    while (active() && countRequests.size >= maxConcurrentCounts) {
      await Promise.race(countRequests).catch(() => {});
    }
    if (!active()) return;
    request = getMasterModelDataCount(modelId);
    countRequests.add(request);
    const result = await request;
    if (active()) dataCounts.value[id] = { ...result, loading: false };
  } catch {
    if (active())
      dataCounts.value[id] = { loading: false, message: '查询失败，请重试' };
  } finally {
    if (request) countRequests.delete(request);
  }
}
watch(canReadDataCount, (allowed) => {
  if (allowed) {
    void load();
    return;
  }
  ++countGeneration;
  dataCounts.value = {};
});
function isDeletableSelection(ids: readonly GovernanceMasterDataApi.Id[]) {
  return ids.every((id) =>
    canDeleteMasterModel(
      rows.value.find((row) => String(row.id) === String(id)),
    ),
  );
}
function remove(ids: GovernanceMasterDataApi.Id[]) {
  if (loading.value || ids.length === 0) return;
  const deleteIds = [...ids];
  if (!isDeletableSelection(deleteIds)) {
    message.warning('仅可删除草稿或已下线且发布结果明确的模型。');
    return;
  }
  const authorized = scope.begin('confirmation');
  Modal.confirm({
    title: '删除模型',
    cancelText: '取消',
    okText: '删除',
    content: `确认删除选中的 ${deleteIds.length} 个模型定义？目标物理表及已有数据会保留。`,
    okButtonProps: { danger: true },
    async onOk() {
      if (!authorized()) return;
      if (!isDeletableSelection(deleteIds)) {
        message.warning('模型状态已变化，请刷新后重新选择。');
        return;
      }
      const current = scope.begin('write');
      await (deleteIds.length === 1
        ? deleteMasterModel(deleteIds[0]!)
        : deleteMasterModelList(deleteIds));
      if (!current()) return;
      selectedKeys.value = [];
      await gridApi.grid.clearCheckboxRow();
      if (!current()) return;
      message.success('模型已删除');
      await refresh();
    },
  });
}
function isKnownModelStatus(row: GovernanceMasterDataApi.Model) {
  return row.status === 0 || row.status === 1 || row.status === 2;
}
function isReadyModel(row: GovernanceMasterDataApi.Model) {
  return (
    isKnownModelStatus(row) &&
    (row.publishState === undefined ||
      row.publishState === null ||
      row.publishState === 'READY')
  );
}
function canVerifyModel(row: GovernanceMasterDataApi.Model) {
  return (
    isKnownModelStatus(row) &&
    (row.publishState === 'UNKNOWN' || row.publishState === 'APPLYING')
  );
}
function publish(row: GovernanceMasterDataApi.Model) {
  if (
    !isReadyModel(row) ||
    loading.value ||
    pendingWrites.value.has(String(row.id))
  )
    return;
  const offline = row.status === 1;
  let content = '发布模型将在目标数据源创建或复用物理表。确认发布？';
  if (offline) {
    content = '下线后暂停数据维护，已有物理表与数据会保留。确认下线？';
  } else if (
    row.status === 0 &&
    row.releaseTime !== undefined &&
    row.releaseTime !== null
  ) {
    content =
      '发布将按当前草稿同步目标表结构。删除字段、缩短长度或小数位、转换类型可能丢失原数据，类型和主键变更也可能因现有数据不符而失败。请确认已核对字段并备份需要保留的数据。';
  }
  const authorized = scope.begin('confirmation');
  Modal.confirm({
    title: offline ? '下线模型' : '发布模型',
    content,
    async onOk() {
      if (!authorized() || pendingWrites.value.has(String(row.id))) return;
      const current = scope.begin(`publish:${String(row.id)}`);
      pendingWrites.value.add(String(row.id));
      try {
        await (offline
          ? cancelReleaseMasterModel(row.id!)
          : releaseMasterModel(row.id!));
        if (!current()) return;
        message.success(offline ? '模型已下线' : '模型已发布');
        await refresh();
      } finally {
        if (current()) pendingWrites.value.delete(String(row.id));
      }
    },
  });
}
async function verify(row: GovernanceMasterDataApi.Model) {
  if (!canVerifyModel(row)) return;
  const current = scope.begin(`verify:${String(row.id)}`);
  await verifyMasterModelRelease(row.id!);
  if (current()) {
    message.success('发布状态已核查');
    await refresh();
  }
}
onMounted(refresh);
const { allExpanded, toggleExpand } = useTreeExpansion(
  domainTree,
  domainExpandedKeys,
  domainKeyword,
);
</script>

<template>
  <Page
    auto-content-height
    content-class="flex min-h-0 flex-col overflow-hidden"
  >
    <FormModal @success="refresh" />
    <MasterWorkspace>
      <template #sidebar>
        <aside class="master-sidebar master-model-sidebar">
          <div class="flex items-center gap-2">
            <Input
              class="min-w-0 flex-1"
              v-model:value="domainKeyword"
              allow-clear
              placeholder="请输入主数据域名称"
              aria-label="搜索主数据域"
            >
              <template #prefix><IconifyIcon icon="lucide:search" /></template>
            </Input>
            <TreeToolbar
              :expanded="allExpanded"
              :loading="domainsLoading"
              @toggle-expand="toggleExpand"
              @refresh="loadDomains"
            />
          </div>
          <Alert
            v-if="domainsFailed"
            class="mt-3"
            type="error"
            message="域加载失败"
            show-icon
          />
          <Button v-if="domainsFailed" class="mt-2" @click="loadDomains">
            <template #icon>
              <IconifyIcon icon="lucide:refresh-cw" />
            </template>
            重试
          </Button>
          <Tree
            v-model:expanded-keys="domainExpandedKeys"
            class="master-model-domains subtle-tree-scrollbar"
            :selected-keys="domainTreeSelectedKeys"
            :tree-data="domainTree"
            block-node
            @select="selectDomainTree"
          >
            <template #titleRender="node">
              <span class="master-model-domain-node">
                <IconifyIcon
                  class="master-model-domain-icon"
                  :icon="
                    node.key === 'root' ? 'lucide:folder-open' : 'lucide:folder'
                  "
                />
                <span
                  class="master-model-domain-name"
                  :title="String(node.title)"
                >
                  {{ node.title }}
                </span>
                <span class="master-sidebar__item-count">{{ node.count }}</span>
              </span>
            </template>
          </Tree>
        </aside>
      </template>
      <main class="master-main">
        <div class="master-stats">
          <button
            v-for="card in cards"
            :key="card.name"
            class="master-stat"
            :class="`master-stat--${card.color}`"
            :aria-pressed="selectedStatus === card.status"
            @click="selectStatus(card.status)"
          >
            <span class="master-stat__icon">
              <IconifyIcon :icon="card.icon" class="size-5" />
            </span>
            <span class="text-left">
              <strong>{{ card.value }}</strong>
              <small>{{ card.name }}</small>
            </span>
          </button>
        </div>
        <Alert
          v-if="failed"
          class="mb-3"
          type="error"
          message="模型加载失败，请点击刷新重试。"
          show-icon
        />
        <div class="min-h-0 flex-1">
          <Grid class="master-model-grid" table-title="主数据模型">
            <template #toolbar-tools>
              <TableAction
                :actions="[
                  {
                    label: '新增',
                    type: 'primary',
                    icon: ACTION_ICON.ADD,
                    auth: ['governance:master-model:create'],
                    onClick: () => edit(),
                  },
                  {
                    label: '批量删除',
                    type: 'primary',
                    danger: true,
                    icon: ACTION_ICON.DELETE,
                    auth: ['governance:master-model:delete'],
                    disabled: !canDeleteSelection || loading,
                    onClick: () => remove(selectedKeys),
                  },
                ]"
              />
            </template>
            <template #modelInfo="{ row }">
              <div class="master-model-info">
                <div class="master-model-info__heading">
                  <button
                    type="button"
                    class="master-model-info__title"
                    :title="modelTitle(row)"
                    :disabled="
                      !hasAccessByCodes(['governance:master-model:query'])
                    "
                    @click="detail(row)"
                  >
                    <IconifyIcon
                      aria-hidden="true"
                      class="mt-px size-4 shrink-0 rounded-sm bg-sky-700 p-0.5 text-white"
                      icon="carbon:data-table"
                    />
                    <span class="min-w-0">{{ modelTitle(row) }}</span>
                  </button>
                  <Tag
                    :color="status(row)?.color"
                    class="master-model-info__tag"
                  >
                    {{ status(row)?.label ?? '未知状态' }}
                  </Tag>
                  <Tag
                    v-if="row.publishState === 'UNKNOWN'"
                    color="error"
                    class="master-model-info__tag"
                  >
                    发布待核查
                  </Tag>
                  <Tag
                    v-else-if="row.publishState === 'APPLYING'"
                    color="processing"
                    class="master-model-info__tag"
                  >
                    发布中
                  </Tag>
                </div>
                <TextEllipsisCell
                  class="master-model-info__description"
                  :content="row.description || '-'"
                  :line="2"
                  center
                />
              </div>
            </template>
            <template #domain="{ row }">
              <Tag
                v-if="row.domainName"
                color="blue"
                class="master-model-domain"
                :title="row.domainName"
              >
                {{ row.domainName }}
              </Tag>
              <span v-else>-</span>
            </template>
            <template #statistics="{ row }">
              <div class="master-model-counts">
                <span>字段数：{{ row.fieldCount ?? '-' }} 个</span>
                <span class="master-model-counts__data">
                  数据量：
                  <Tooltip
                    v-if="!canReadDataCount || !isCountableModel(row)"
                    :title="
                      canReadDataCount ? '暂无数据量' : '需要主数据查询权限'
                    "
                  >
                    <span>-</span>
                  </Tooltip>
                  <template
                    v-else-if="dataCounts[String(row.id)]?.dataCount != null"
                  >
                    {{ dataCounts[String(row.id)]?.dataCount }} 条
                  </template>
                  <span
                    v-else-if="
                      !dataCounts[String(row.id)] ||
                      dataCounts[String(row.id)]?.loading
                    "
                    class="text-muted-foreground"
                  >
                    读取中…
                  </span>
                  <Tooltip
                    v-else
                    :title="dataCounts[String(row.id)]?.message || '暂无数据量'"
                  >
                    <Button
                      v-if="dataCounts[String(row.id)]?.available === undefined"
                      type="link"
                      size="small"
                      class="master-model-counts__query"
                      @click="queryDataCount(row)"
                    >
                      <template #icon>
                        <IconifyIcon icon="lucide:refresh-cw" />
                      </template>
                      重试
                    </Button>
                    <span v-else>-</span>
                  </Tooltip>
                </span>
              </div>
            </template>
            <template #status="{ row }">
              <Tooltip
                :title="
                  isReadyModel(row)
                    ? row.status === 1
                      ? '已发布，点击下线'
                      : '未发布，点击发布'
                    : '发布结果待核查，暂不可操作'
                "
              >
                <Switch
                  size="small"
                  :checked="row.status === 1"
                  :aria-label="row.status === 1 ? '下线模型' : '发布模型'"
                  :loading="pendingWrites.has(String(row.id))"
                  :disabled="
                    !isReadyModel(row) ||
                    loading ||
                    pendingWrites.has(String(row.id)) ||
                    !hasAccessByCodes(['governance:master-model:release'])
                  "
                  @change="() => publish(row)"
                />
              </Tooltip>
            </template>
            <template #actions="{ row }">
              <TableAction
                :actions="[
                  {
                    label: row.status === 1 ? '下线' : '发布',
                    type: 'link',
                    icon:
                      row.status === 1 ? 'lucide:circle-off' : 'lucide:send',
                    auth: ['governance:master-model:release'],
                    ifShow: isReadyModel(row),
                    disabled: loading || pendingWrites.has(String(row.id)),
                    onClick: () => publish(row),
                  },
                  {
                    label: '核查发布',
                    type: 'link',
                    icon: 'lucide:shield-check',
                    auth: ['governance:master-model:release'],
                    ifShow: canVerifyModel(row),
                    onClick: () => verify(row),
                  },
                  {
                    label: '详情',
                    type: 'link',
                    icon: ACTION_ICON.VIEW,
                    auth: ['governance:master-model:query'],
                    onClick: () => detail(row),
                  },
                ]"
                :drop-down-actions="[
                  {
                    label: '修改',
                    icon: ACTION_ICON.EDIT,
                    auth: ['governance:master-model:update'],
                    ifShow: isReadyModel(row),
                    disabled: loading || pendingWrites.has(String(row.id)),
                    onClick: () => edit(row),
                  },
                  {
                    label: '删除',
                    type: 'link',
                    danger: true,
                    icon: ACTION_ICON.DELETE,
                    auth: ['governance:master-model:delete'],
                    disabled: !canDeleteMasterModel(row) || loading,
                    onClick: () => remove([row.id!]),
                  },
                  {
                    label: '维护数据',
                    icon: 'lucide:table-properties',
                    auth: ['governance:master-data:query'],
                    ifShow: row.status === 1 && isReadyModel(row),
                    onClick: () =>
                      router.push({
                        name: 'GovernanceMasterMaintain',
                        query: { modelId: String(row.id) },
                      }),
                  },
                ]"
              />
            </template>
          </Grid>
        </div>
      </main>
    </MasterWorkspace>
  </Page>
</template>

<style>
@import '../mastercommon/master.css';
</style>

<style scoped>
.master-model-sidebar {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.master-model-sidebar > :not(.master-model-domains) {
  flex-shrink: 0;
}

.master-model-domains {
  flex: 1;
  min-height: 0;
  margin-top: 12px;
  overflow: auto;
  overscroll-behavior: contain;
  background: transparent;
}

.master-model-domains :deep(.ant-tree-treenode) {
  width: 100%;
}

.master-model-domains :deep(.ant-tree-node-content-wrapper) {
  flex: 1;
  min-width: 0;
}

.master-model-domains :deep(.ant-tree-title) {
  display: block;
  width: 100%;
  font-size: 14px;
}

.master-model-domain-node {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
  min-height: 28px;
}

.master-model-domain-icon {
  flex-shrink: 0;
  color: #688bff;
}

.master-model-domain-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.master-sidebar__item-count {
  flex-shrink: 0;
}

.master-model-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  line-height: 18px;
}

.master-model-info__heading {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  min-width: 0;
}

.master-model-info__title {
  display: inline-flex;
  gap: 6px;
  align-items: flex-start;
  min-width: 0;
  color: hsl(var(--primary));
  text-align: left;
  overflow-wrap: anywhere;
  white-space: normal;
  cursor: pointer;
}

.master-model-info__title:hover {
  text-decoration: underline;
}

.master-model-info__title:disabled {
  cursor: default;
}

.master-model-info__title:focus-visible {
  outline: 2px solid hsl(var(--ring));
  outline-offset: 2px;
}

.master-model-info__tag {
  flex-shrink: 0;
  padding: 0 4px;
  margin: 0;
  font-size: 12px;
  line-height: 16px;
  border: 0;
  border-radius: 2px;
}

.master-model-info__description {
  font-size: 12px;
  line-height: 16px;
  color: hsl(var(--muted-foreground));
  overflow-wrap: anywhere;
  white-space: normal;
}

.master-model-domain {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  border: 0;
  border-radius: 2px;
}

.master-model-counts {
  display: flex;
  flex-direction: column;
  font-size: 12px;
  line-height: 20px;
}

.master-model-counts__data {
  display: flex;
  align-items: center;
}

.master-model-counts__query {
  height: 20px;
  padding: 0;
  font-size: 12px;
}

.master-model-grid :deep(.table-actions) {
  white-space: nowrap;
}

@media (max-width: 767px) {
  .master-model-sidebar {
    height: auto;
    max-height: 208px;
  }

  .master-model-domains {
    max-height: 150px;
    margin-top: 8px;
  }

  .master-stats {
    display: flex;
    gap: 6px;
    margin-bottom: 8px;
    overflow-x: auto;
  }

  .master-stat {
    flex-shrink: 0;
    padding: 8px;
  }

  .master-stat__icon {
    display: none;
  }

  .master-stat > .text-left {
    display: flex;
    gap: 6px;
    align-items: center;
  }

  .master-stat strong {
    font-size: 16px;
    line-height: 20px;
  }

  .master-stat small {
    font-size: 12px;
  }
}
</style>
