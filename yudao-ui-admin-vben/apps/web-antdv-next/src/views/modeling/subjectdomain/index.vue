<script setup lang="ts">
import type { ModelingSubjectDomainApi } from '#/api/modeling/subjectdomain';

import { computed, onActivated, onDeactivated, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { useAntdDesignTokens } from '@vben/hooks';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Button, ConfigProvider, Input, message, Switch } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteSubjectDomain,
  getSubjectDomainList,
  updateSubjectDomainStatus,
} from '#/api/modeling/subjectdomain';
import SidebarCollapse from '#/components/sidebar-collapse/index.vue';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import treeDocument from '../indicator-model/reference-assets/tree-document.png';
import treeFolderOpen from '../indicator-model/reference-assets/tree-folder-open.png';
import treeFolder from '../indicator-model/reference-assets/tree-folder.png';
import { useModelingDetailQuery } from '../modelcommon/use-modeling-detail-query';
import EntityForm from '../planning/entity/form.vue';
import { planningPopupContainer, subjectDepth } from '../planning/state';
import { usePlanningOptions } from '../planning/use-planning-options';
import SubjectForm from './modules/form.vue';

import '../indicator-model/metric-list.css';
import '../planning/planning.css';

defineOptions({ name: 'ModelingSubjectDomain' });

const props = defineProps<{
  dataDomainId?: number | string;
  embedded?: boolean;
}>();

const { tokens } = useAntdDesignTokens();

const route = useRoute();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const user = useUserStore();
const scope = computed(() =>
  JSON.stringify([user.userInfo?.userId, user.userRoles, access.accessCodes]),
);
const {
  domains,
  userName,
  userPhone,
  loading: optionsLoading,
  failed: optionsFailed,
  reload: reloadOptions,
} = usePlanningOptions(true);
const selectedDomain = ref<string | undefined>(
  props.dataDomainId === undefined ? undefined : String(props.dataDomainId),
);
const treeKeyword = ref('');
const name = ref('');
const parentId = ref<string>();
const isExpanded = ref(false);
const pageNo = ref(1);
const pageSize = ref(props.embedded ? 20 : 10);
const rows = ref<ModelingSubjectDomainApi.SubjectDomain[]>([]);
const allSubjects = ref<ModelingSubjectDomainApi.SubjectDomain[]>([]);
const busyIds = ref(new Set<string>());
const failed = ref(false);
const active = ref(true);
const sortField = ref('createTime');
const sidebarCollapsed = ref(false);
const treeExpanded = ref(true);
const descending = ref(true);
const enabledDomains = computed(() =>
  domains.value.filter((item) => item.status === 0),
);
const visibleDomains = computed(() =>
  enabledDomains.value.filter((item) =>
    item.name?.toLowerCase().includes(treeKeyword.value.trim().toLowerCase()),
  ),
);
const parents = computed(() =>
  allSubjects.value.map((item) => ({
    label: item.name ?? String(item.id),
    value: String(item.id),
  })),
);
const mutationPending = ref(false);
const mutations = useObjectRequest(
  () => `${scope.value}/${active.value}/${selectedDomain.value}`,
  () => {
    mutationPending.value = false;
  },
);
const request = useObjectRequest(
  () => `${scope.value}/${active.value}/${selectedDomain.value}`,
  () => {
    rows.value = [];
    allSubjects.value = [];
    busyIds.value = new Set();
    gridApi.setGridOptions({ data: [], loading: false });
  },
);
const [FormModal, formApi] = useVbenModal({
  connectedComponent: SubjectForm,
  destroyOnClose: true,
});
const [EntityModal, entityApi] = useVbenModal({
  connectedComponent: EntityForm,
  destroyOnClose: true,
});
function detail(row: ModelingSubjectDomainApi.SubjectDomain) {
  if (row.id !== undefined)
    void router.push({
      name: 'ModelingSubjectDomainDetail',
      params: { id: String(row.id) },
      query: props.embedded ? { fromDomain: String(props.dataDomainId) } : {},
    });
}
function create(parent?: ModelingSubjectDomainApi.SubjectDomain) {
  formApi
    .setData({
      dataDomainId: parent?.dataDomainId ?? selectedDomain.value,
      parentId: parent?.id,
    })
    .open();
}
const [Grid, gridApi] = useVbenVxeGrid<ModelingSubjectDomainApi.SubjectDomain>({
  ...(props.embedded
    ? {}
    : {
        formOptions: {
          submitOnEnter: true,
          schema: [
            {
              fieldName: 'name',
              label: '主题域信息',
              component: 'Input',
              componentProps: {
                allowClear: true,
                placeholder: '请输入名称或英文名',
              },
            },
            {
              fieldName: 'parentId',
              label: '上级主题域',
              component: 'Select',
              componentProps: () => ({
                allowClear: true,
                showSearch: true,
                optionFilterProp: 'label',
                options: parents.value,
                placeholder: '请选择上级主题域',
              }),
            },
          ],
          handleSubmit: async (values) => {
            name.value = values.name ?? '';
            parentId.value = values.parentId;
            await load(true);
          },
          handleReset: async () => {
            await gridApi.formApi.reset();
            resetSearch();
          },
        },
      }),
  gridOptions: {
    headerCellConfig: props.embedded ? { height: 34 } : undefined,
    showHeaderOverflow: 'tooltip',
    data: [],
    height: 'auto',
    minHeight: 0,
    showOverflow: false,
    proxyConfig: { enabled: false },
    rowConfig: { keyField: 'id', isHover: true },
    treeConfig: {
      transform: true,
      rowField: 'id',
      parentField: 'parentId',
      expandAll: false,
    },
    sortConfig: {
      remote: true,
      defaultSort: { field: 'createTime', order: 'desc' },
    },
    pagerConfig: {
      enabled: true,
      currentPage: 1,
      pageSize: pageSize.value,
      pageSizes: [10, 20, 50, 100],
      total: 0,
    },
    toolbarConfig: {
      search: !props.embedded,
      refresh: true,
      refreshOptions: { queryMethod: () => load() },
      custom: true,
      zoom: false,
    },
    columns: [
      {
        field: 'id',
        title: '编号',
        width: props.embedded ? 88 : 105,
        showOverflow: 'tooltip',
      },
      {
        field: 'name',
        title: '主题域信息',
        minWidth: 280,
        align: 'left',
        treeNode: true,
        slots: { default: 'info' },
      },
      { field: 'description', title: '描述', minWidth: 280, align: 'left' },
      {
        field: 'businessObjectCount',
        title: '业务实体',
        width: 105,
        sortable: true,
        formatter: ({ cellValue }: { cellValue?: null | number | string }) =>
          cellValue === null || cellValue === undefined
            ? '-'
            : `${cellValue}个`,
      },
      {
        field: 'status',
        title: '状态',
        width: 105,
        slots: { default: 'status' },
      },
      {
        title: '负责人',
        width: 140,
        align: 'left',
        slots: { default: 'leader' },
      },
      {
        title: '创建人',
        width: 140,
        align: 'left',
        slots: { default: 'creator' },
      },
      {
        field: 'createTime',
        title: '创建时间',
        minWidth: 170,
        sortable: true,
        formatter: ({ cellValue }: { cellValue?: null | number | string }) =>
          formatDateTime(cellValue ?? undefined)?.slice(0, 16) || '-',
      },
      {
        title: '操作',
        width: 260,
        fixed: 'right',
        showOverflow: false,
        slots: { default: 'actions' },
      },
    ],
  },
  gridEvents: {
    pageChange: ({
      currentPage,
      pageSize: size,
    }: {
      currentPage: number;
      pageSize: number;
    }) => {
      pageNo.value = currentPage;
      pageSize.value = size;
      renderRows();
    },
    sortChange: ({ field, order }: { field: string; order: string }) => {
      sortField.value = field;
      descending.value = order !== 'asc';
      renderRows();
    },
  },
});
function renderRows() {
  const ids = new Set(rows.value.map((row) => String(row.id)));
  const sorted = rows.value.toSorted((a, b) => {
    const field = sortField.value as 'businessObjectCount' | 'createTime';
    const av = a[field] ?? 0;
    const bv = b[field] ?? 0;
    const order =
      typeof av === 'number' && typeof bv === 'number'
        ? av - bv
        : String(av).localeCompare(String(bv));
    if (order === 0) {
      return String(a.id).localeCompare(String(b.id), undefined, {
        numeric: true,
      });
    }
    return descending.value ? -order : order;
  });
  const roots = sorted.filter((row) => !ids.has(String(row.parentId)));
  pageNo.value = Math.min(
    pageNo.value,
    Math.max(1, Math.ceil(roots.length / pageSize.value)),
  );
  const included = new Set(
    roots
      .slice((pageNo.value - 1) * pageSize.value, pageNo.value * pageSize.value)
      .map((row) => String(row.id)),
  );
  let added = true;
  while (added) {
    added = false;
    for (const row of sorted) {
      if (included.has(String(row.parentId)) && !included.has(String(row.id))) {
        included.add(String(row.id));
        added = true;
      }
    }
  }
  const data = sorted
    .filter((row) => included.has(String(row.id)))
    .map((row) => ({
      ...row,
      id: String(row.id),
      parentId: included.has(String(row.parentId)) ? String(row.parentId) : '0',
    }));
  gridApi.setGridOptions({
    data,
    pagerConfig: {
      total: roots.length,
      currentPage: pageNo.value,
      pageSize: pageSize.value,
    },
  });
  void gridApi.grid?.setAllTreeExpand?.(isExpanded.value);
}
async function load(reset = false) {
  if (reset) pageNo.value = 1;
  const current = request.begin();
  if (!active.value || !selectedDomain.value) {
    gridApi.setGridOptions({ data: [], pagerConfig: { total: 0 } });
    return;
  }
  failed.value = false;
  gridApi.setGridOptions({ loading: true });
  try {
    const [result, choices] = await Promise.all([
      getSubjectDomainList({
        dataDomainId: selectedDomain.value,
        name: name.value.trim() || undefined,
        parentId: parentId.value,
      }),
      getSubjectDomainList({ dataDomainId: selectedDomain.value }),
    ]);
    if (!current()) return;
    rows.value = result;
    allSubjects.value = choices;
    renderRows();
  } catch {
    if (current()) {
      failed.value = true;
      rows.value = [];
      renderRows();
    }
  } finally {
    if (current()) gridApi.setGridOptions({ loading: false });
  }
}
function resetSearch() {
  name.value = '';
  parentId.value = undefined;
  void load(true);
}
function chooseDomain(id?: number | string) {
  selectedDomain.value = id === undefined ? undefined : String(id);
  name.value = '';
  parentId.value = undefined;
  void gridApi.formApi?.setValues({ name: '', parentId: undefined });
  gridApi.formApi?.setLatestSubmissionValues({ name: '', parentId: undefined });
}
function expand() {
  isExpanded.value = !isExpanded.value;
  void gridApi.grid.setAllTreeExpand(isExpanded.value);
}
async function changeStatus(row: ModelingSubjectDomainApi.SubjectDomain) {
  if (
    row.id === undefined ||
    mutationPending.value ||
    !hasAccessByCodes(['modeling:subject-domain:update'])
  )
    return;
  const current = mutations.begin();
  const id = row.id;
  const next = row.status === 0 ? 1 : 0;
  const action = next === 0 ? '启用' : '禁用';
  mutationPending.value = true;
  busyIds.value.add(String(id));
  try {
    try {
      await confirm(`确认${action}“${row.name}”？`);
    } catch {
      return;
    }
    if (!current()) return;
    await updateSubjectDomainStatus(id, next);
    if (!current()) return;
    message.success(`${action}成功`);
    await load();
  } finally {
    if (current()) {
      mutationPending.value = false;
      busyIds.value.delete(String(id));
    }
  }
}
async function remove(row: ModelingSubjectDomainApi.SubjectDomain) {
  if (
    row.id === undefined ||
    mutationPending.value ||
    !hasAccessByCodes(['modeling:subject-domain:delete'])
  )
    return;
  const current = mutations.begin();
  mutationPending.value = true;
  busyIds.value.add(String(row.id));
  try {
    try {
      await confirm(`确认删除“${row.name}”？`);
    } catch {
      return;
    }
    if (!current()) return;
    await deleteSubjectDomain(row.id);
    if (!current()) return;
    message.success('删除成功');
    await load();
  } finally {
    if (current()) {
      mutationPending.value = false;
      busyIds.value.delete(String(row.id));
    }
  }
}
watch(
  enabledDomains,
  (list) => {
    if (props.embedded) return;
    const fromRoute =
      typeof route.query.dataDomainId === 'string'
        ? route.query.dataDomainId
        : undefined;
    if (fromRoute && list.some((item) => String(item.id) === fromRoute))
      selectedDomain.value = fromRoute;
    else if (!list.some((item) => String(item.id) === selectedDomain.value))
      selectedDomain.value =
        list[0]?.id === undefined ? undefined : String(list[0].id);
  },
  { immediate: true },
);
watch(
  () => route.query.dataDomainId,
  (id) => {
    if (
      typeof id === 'string' &&
      enabledDomains.value.some((item) => String(item.id) === id)
    )
      chooseDomain(id);
  },
);
watch(
  () =>
    `${access.accessToken}/${access.refreshToken}/${scope.value}/${selectedDomain.value}`,
  () => load(true),
  { immediate: true },
);
useModelingDetailQuery((id) => {
  if (!props.embedded) detail({ id });
});
watch(
  () => props.dataDomainId,
  (value) => {
    if (props.embedded) chooseDomain(value);
  },
);
onActivated(() => {
  if (!active.value) {
    active.value = true;
    void load();
  }
});
onDeactivated(() => {
  active.value = false;
});
</script>

<template>
  <ConfigProvider
    :get-popup-container="planningPopupContainer"
    :theme="{
      token: {
        borderRadius: tokens.borderRadius,
        zIndexPopupBase: 2100,
      },
    }"
  >
    <Page
      :auto-content-height="!embedded"
      class="qd-metric-page subjectdomain-page"
      :class="embedded ? 'planning-page planning-embedded' : undefined"
      :content-class="embedded ? 'qd-metric-content p-0' : 'qd-metric-content'"
    >
      <FormModal @success="load()" /><EntityModal @success="load()" />
      <div
        class="metric-layout subjectdomain-layout flex h-full min-h-0 w-full overflow-hidden"
        :class="{ 'is-embedded': embedded }"
      >
        <div
          v-if="!embedded"
          v-show="!sidebarCollapsed"
          class="metric-layout__tree"
        >
          <aside
            class="planning-sidebar"
            :class="{ 'is-collapsed': sidebarCollapsed }"
          >
            <div
              v-show="!sidebarCollapsed"
              class="mb-2 flex items-center gap-2"
            >
              <Input
                v-model:value="treeKeyword"
                class="min-w-0 flex-1"
                allow-clear
                placeholder="请输入数据板块名称"
              >
                <template #prefix>
                  <IconifyIcon icon="lucide:search" />
                </template>
              </Input>
              <TreeToolbar
                compact
                :expanded="treeExpanded"
                :loading="optionsLoading"
                @toggle-expand="treeExpanded = !treeExpanded"
                @refresh="reloadOptions"
              />
            </div>
            <button
              type="button"
              class="planning-tree-root"
              :aria-expanded="treeExpanded"
              @click="treeExpanded = !treeExpanded"
            >
              <IconifyIcon
                :icon="
                  treeExpanded ? 'lucide:chevron-down' : 'lucide:chevron-right'
                "
                :size="12"
              /><img
                :src="treeExpanded ? treeFolderOpen : treeFolder"
                alt=""
              />主题域类目
            </button>
            <template v-if="treeExpanded">
              <div v-if="optionsLoading" class="planning-muted">加载中…</div>
              <Button v-if="optionsFailed" type="link" @click="reloadOptions">
                加载失败，重试
              </Button>
              <button
                v-for="domain in visibleDomains"
                :key="String(domain.id)"
                type="button"
                class="planning-tree-node"
                :title="domain.name"
                :class="{ active: selectedDomain === String(domain.id) }"
                @click="chooseDomain(domain.id)"
              >
                <img :src="treeDocument" alt="" /><span>{{ domain.name }}</span>
              </button>
              <div
                v-if="
                  !optionsLoading && !optionsFailed && !visibleDomains.length
                "
                class="planning-muted"
              >
                暂无数据板块
              </div>
            </template>
          </aside>
        </div>
        <SidebarCollapse
          v-if="!embedded"
          :collapsed="sidebarCollapsed"
          label="数据板块目录"
          @toggle="sidebarCollapsed = !sidebarCollapsed"
        />
        <main class="planning-main">
          <div v-if="failed" class="planning-error">
            主题域加载失败。<Button type="link" @click="load()">重试</Button>
          </div>
          <Grid
            :style="{
              '--vxe-ui-font-size-small': '12px',
              '--vxe-ui-font-size-mini': '12px',
            }"
            class="min-h-0 flex-1"
            :table-title="embedded ? undefined : '主题域'"
          >
            <template #toolbar-actions>
              <Button
                v-if="embedded && hasAccessByCodes(['modeling:subject-domain:create'])"
                type="primary"
                size="small"
                :disabled="!selectedDomain"
                @click="create()"
              >
                <IconifyIcon icon="lucide:plus" />新增
              </Button>
            </template>
            <template #toolbar-tools>
              <div class="planning-toolbar">
                <Button
                  type="primary"
                  v-if="!embedded && hasAccessByCodes(['modeling:subject-domain:create'])"
                  :disabled="!selectedDomain"
                  @click="create()"
                >
                  <IconifyIcon icon="lucide:plus" />新增
                </Button>
                <Button v-if="!embedded" @click="expand">
                  <IconifyIcon
                    :icon="
                      isExpanded ? 'lucide:list-minus' : 'lucide:list-plus'
                    "
                  />{{ isExpanded ? '收起' : '展开' }}
                </Button>
              </div>
            </template>
            <template #info="{ row }">
              <div class="planning-info subject-domain-info">
                <button
                  class="planning-link subject-domain-name"
                  type="button"
                  @click="detail(row)"
                >
                  <span class="subject-domain-icon" aria-hidden="true">
                    <IconifyIcon icon="lucide:shapes" />
                  </span>
                  <span>{{ row.name }}（{{ row.code }}）</span>
                </button>
                <span class="planning-muted">{{ row.code }}</span>
              </div>
            </template>
            <template #status="{ row }">
              <Switch
                class="subject-domain-status-switch"
                size="small"
                :checked="row.status === 0"
                :loading="busyIds.has(String(row.id))"
                :disabled="
                  !hasAccessByCodes(['modeling:subject-domain:update'])
                "
                :aria-label="`${row.status === 0 ? '禁用' : '启用'}${row.name}`"
                @change="changeStatus(row)"
              />
            </template>
            <template #leader="{ row }">
              <div class="planning-info">
                <span>{{ row.leaderName || userName(row.leaderUserId) }}</span>
                <span>{{
                  row.leaderPhone || userPhone(row.leaderUserId)
                }}</span>
              </div>
            </template>
            <template #creator="{ row }">
              <div class="planning-info">
                <span>{{ row.creatorName || userName(row.creator) }}</span>
                <span>{{ row.creatorPhone || userPhone(row.creator) }}</span>
              </div>
            </template>
            <template #actions="{ row }">
              <TableAction
                :divider="false"
                :actions="[
                  {
                    label: '详情',
                    type: 'link',
                    icon: ACTION_ICON.VIEW,
                    auth: ['modeling:subject-domain:query'],
                    onClick: () => detail(row),
                  },
                  {
                    label: '修改',
                    type: 'link',
                    icon: ACTION_ICON.EDIT,
                    auth: ['modeling:subject-domain:update'],
                    disabled: row.status === 0,
                    onClick: () => formApi.setData({ id: row.id }).open(),
                  },
                ]"
                :drop-down-actions="[
                  {
                    label: '新增子主题域',
                    icon: ACTION_ICON.ADD,
                    auth: ['modeling:subject-domain:create'],
                    disabled: subjectDepth(allSubjects, row.id) >= 5,
                    onClick: () => create(row),
                  },
                  {
                    label: '新增业务实体',
                    icon: 'lucide:file-plus',
                    auth: ['modeling:business-entity:create'],
                    disabled: row.status !== 0,
                    onClick: () =>
                      entityApi
                        .setData({
                          dataDomainId: row.dataDomainId,
                          subjectDomainId: row.id,
                        })
                        .open(),
                  },
                  {
                    label: '删除',
                    icon: ACTION_ICON.DELETE,
                    danger: true,
                    auth: ['modeling:subject-domain:delete'],
                    disabled: row.status === 0 || busyIds.has(String(row.id)),
                    onClick: () => remove(row),
                  },
                ]"
              />
            </template>
          </Grid>
        </main>
      </div>
    </Page>
  </ConfigProvider>
</template>

<style scoped>
.subjectdomain-layout :deep(.vxe-table--scroll-x-handle),
.subjectdomain-layout :deep(.vxe-table--scroll-y-handle) {
  scrollbar-color: #d4d7dc transparent;
  scrollbar-width: thin;
}

:deep(.subject-domain-status-switch.ant-switch-small) {
  min-width: 40px;
  height: 20px;
  line-height: 20px;
}

:deep(.subject-domain-status-switch.ant-switch-small .ant-switch-handle) {
  width: 16px;
  height: 16px;
}

:deep(
  .subject-domain-status-switch.ant-switch-small.ant-switch-checked
    .ant-switch-handle
) {
  inset-inline-start: calc(100% - 18px);
}

.subject-domain-info {
  padding-inline-end: 20px;
}

.subject-domain-name {
  line-height: 20px;
}

.subject-domain-icon {
  display: inline-flex;
  flex: 0 0 14px;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  font-size: 11px;
  color: white;
  background: hsl(var(--primary));
  border-radius: 2px;
}
</style>
