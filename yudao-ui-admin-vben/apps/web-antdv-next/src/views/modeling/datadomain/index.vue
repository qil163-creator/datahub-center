<script setup lang="ts">
import type { ModelingDataDomainApi } from '#/api/modeling/datadomain';

import { computed, onActivated, onDeactivated, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { useAntdDesignTokens } from '@vben/hooks';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Button, ConfigProvider, message, Switch } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteDataDomain,
  deleteDataDomainList,
  getDataDomainPage,
  updateDataDomainStatus,
} from '#/api/modeling/datadomain';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { useObjectRequest } from '#/hooks/use-object-request';

import EntityForm from '../planning/entity/form.vue';
import defaultIcon from '../planning/reference-assets/domain-icon.png';
import { planningPopupContainer } from '../planning/state';
import { usePlanningOptions } from '../planning/use-planning-options';
import SubjectForm from '../subjectdomain/modules/form.vue';
import DomainForm from './modules/form.vue';

import '../planning/planning.css';

defineOptions({ name: 'ModelingDataDomain' });

const { tokens } = useAntdDesignTokens();

const { hasAccessByCodes } = useAccess();
const router = useRouter();
const access = useAccessStore();
const user = useUserStore();
const scope = computed(() =>
  JSON.stringify([user.userInfo?.userId, user.userRoles, access.accessCodes]),
);
const {
  userOptions,
  userName,
  userPhone,
  loading: optionsLoading,
  failed: optionsFailed,
  reload: reloadOptions,
} = usePlanningOptions();
const name = ref('');
const leaderUserId = ref<string>();
const pageNo = ref(1);
const pageSize = ref(10);
const sortField = ref<'createTime' | 'id'>('createTime');
const sortOrder = ref<'asc' | 'desc'>('desc');
const selected = ref<ModelingDataDomainApi.DataDomain[]>([]);
const busyIds = ref(new Set<string>());
const deleting = ref(false);
const failed = ref(false);
const active = ref(true);
const mutationPending = ref(false);
const mutations = useObjectRequest(
  () => `${scope.value}/${active.value}`,
  () => {
    mutationPending.value = false;
    deleting.value = false;
  },
);
const request = useObjectRequest(
  () => `${scope.value}/${active.value}`,
  () => {
    selected.value = [];
    busyIds.value = new Set();
    gridApi.setGridOptions({ data: [], loading: false });
  },
);
const [FormModal, formApi] = useVbenModal({
  connectedComponent: DomainForm,
  destroyOnClose: true,
});
const [SubjectModal, subjectApi] = useVbenModal({
  connectedComponent: SubjectForm,
  destroyOnClose: true,
});
const [EntityModal, entityApi] = useVbenModal({
  connectedComponent: EntityForm,
  destroyOnClose: true,
});
function detail(row: ModelingDataDomainApi.DataDomain) {
  if (row.id !== undefined)
    void router.push({
      name: 'ModelingDataDomainDetail',
      params: { id: String(row.id) },
    });
}
const [Grid, gridApi] = useVbenVxeGrid<ModelingDataDomainApi.DataDomain>({
  formOptions: {
    submitOnEnter: true,
    schema: [
      {
        fieldName: 'name',
        label: '模块名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入名称或英文名' },
      },
      {
        fieldName: 'leaderUserId',
        label: '板块架构师',
        component: 'Select',
        componentProps: () => ({
          allowClear: true,
          showSearch: true,
          optionFilterProp: 'label',
          options: userOptions.value,
          loading: optionsLoading.value,
          placeholder: '请选择板块架构师',
        }),
      },
    ],
    handleSubmit: async (values) => {
      name.value = values.name ?? '';
      leaderUserId.value = values.leaderUserId;
      await load(true);
    },
    handleReset: async () => {
      await gridApi.formApi.reset();
      resetSearch();
    },
  },
  gridOptions: {
    showHeaderOverflow: 'tooltip',
    data: [],
    height: 'auto',
    showOverflow: false,
    proxyConfig: { enabled: false },
    rowConfig: { keyField: 'id', isHover: true },
    sortConfig: {
      remote: true,
      defaultSort: { field: 'createTime', order: 'desc' },
    },
    checkboxConfig: {
      checkMethod: ({ row }: { row: ModelingDataDomainApi.DataDomain }) =>
        row.status === 1,
    },
    pagerConfig: {
      currentPage: 1,
      pageSize: 10,
      pageSizes: [10, 20, 50, 100],
      total: 0,
    },
    toolbarConfig: {
      search: true,
      refresh: true,
      refreshOptions: { queryMethod: () => load() },
      custom: true,
      zoom: false,
    },
    columns: [
      { type: 'checkbox', width: 48 },
      { field: 'id', title: '编号', width: 105, sortable: true },
      {
        field: 'name',
        title: '数据板块信息',
        minWidth: 300,
        align: 'left',
        slots: { default: 'info' },
      },
      {
        title: '板块规划',
        minWidth: 240,
        align: 'left',
        slots: { default: 'planning' },
      },
      {
        field: 'status',
        title: '状态',
        width: 100,
        slots: { default: 'status' },
      },
      {
        title: '板块架构师',
        width: 135,
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
        width: 210,
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
      void load();
    },
    checkboxChange: ({
      records,
    }: {
      records: ModelingDataDomainApi.DataDomain[];
    }) => {
      selected.value = records;
    },
    checkboxAll: ({
      records,
    }: {
      records: ModelingDataDomainApi.DataDomain[];
    }) => {
      selected.value = records;
    },
    sortChange: ({ field, order }: { field: string; order: string }) => {
      sortField.value = field === 'id' ? 'id' : 'createTime';
      sortOrder.value = order === 'asc' ? 'asc' : 'desc';
      void load(true);
    },
  },
});
async function load(reset = false) {
  if (reset) pageNo.value = 1;
  const current = request.begin();
  if (!active.value) return;
  failed.value = false;
  selected.value = [];
  gridApi.grid?.clearCheckboxRow?.();
  gridApi.setGridOptions({
    loading: true,
    pagerConfig: { currentPage: pageNo.value, pageSize: pageSize.value },
  });
  try {
    const result = await getDataDomainPage({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      name: name.value.trim() || undefined,
      leaderUserId: leaderUserId.value,
      sortField: sortField.value,
      sortOrder: sortOrder.value,
    });
    if (current())
      gridApi.setGridOptions({
        data: result.list,
        pagerConfig: { total: result.total },
      });
  } catch {
    if (current()) {
      failed.value = true;
      gridApi.setGridOptions({ data: [], pagerConfig: { total: 0 } });
    }
  } finally {
    if (current()) gridApi.setGridOptions({ loading: false });
  }
}
function resetSearch() {
  name.value = '';
  leaderUserId.value = undefined;
  void load(true);
}
async function changeStatus(row: ModelingDataDomainApi.DataDomain) {
  if (
    row.id === undefined ||
    mutationPending.value ||
    !hasAccessByCodes(['modeling:data-domain:update'])
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
    await updateDataDomainStatus(id, next);
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
async function remove(rows: ModelingDataDomainApi.DataDomain[]) {
  if (
    mutationPending.value ||
    rows.length === 0 ||
    !hasAccessByCodes(['modeling:data-domain:delete'])
  )
    return;
  const current = mutations.begin();
  mutationPending.value = true;
  deleting.value = true;
  try {
    try {
      await confirm(
        rows.length === 1
          ? `确认删除“${rows[0]?.name}”？`
          : `确认删除选中的 ${rows.length} 个数据板块？`,
      );
    } catch {
      return;
    }
    if (!current()) return;
    const ids = rows.flatMap((row) => (row.id === undefined ? [] : [row.id]));
    await (ids.length === 1
      ? deleteDataDomain(ids[0]!)
      : deleteDataDomainList(ids));
    if (!current()) return;
    selected.value = [];
    gridApi.grid?.clearCheckboxRow?.();
    message.success('删除成功');
    await load();
  } finally {
    if (current()) {
      mutationPending.value = false;
      deleting.value = false;
    }
  }
}
watch(
  () => `${access.accessToken}/${access.refreshToken}/${scope.value}`,
  () => load(true),
  { immediate: true },
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
    <Page auto-content-height class="data-domain-page" content-class="flex flex-col">
      <FormModal @success="load()" /><SubjectModal
        @success="load()"
      /><EntityModal @success="load()" />
      <div v-if="optionsFailed" class="planning-error">
        人员加载失败，<Button type="link" @click="reloadOptions">重试</Button>
      </div>
      <div v-if="failed" class="planning-error">
        数据板块加载失败。<Button type="link" @click="load()">重试</Button>
      </div>
      <Grid
        :style="{ '--vxe-ui-font-size-small': '12px' }"
        class="min-h-0 flex-1"
        table-title="数据板块"
      >
        <template #toolbar-tools>
          <div class="planning-toolbar">
            <Button
              type="primary"
              v-if="hasAccessByCodes(['modeling:data-domain:create'])"
              @click="formApi.setData(undefined).open()"
            >
              <IconifyIcon icon="lucide:plus" />新增
            </Button>
            <Button
              v-if="hasAccessByCodes(['modeling:data-domain:delete'])"
              danger
              :disabled="!selected.length || deleting"
              @click="remove([...selected])"
            >
              <IconifyIcon icon="lucide:trash-2" />批量删除
            </Button>
          </div>
        </template>
        <template #info="{ row }">
          <div class="planning-info data-domain-info">
            <button
              class="planning-link data-domain-name"
              type="button"
              @click="detail(row)"
            >
              <img
                class="planning-icon"
                :src="row.icon || defaultIcon"
                alt=""
              /><span>{{ row.name }}（{{ row.code }}）</span>
            </button>
            <TextEllipsisCell
              class="planning-muted"
              :content="row.description"
              :line="2"
              :line-height="18"
              :reserve-lines="false"
              center
            />
          </div>
        </template>
        <template #planning="{ row }">
          <div class="planning-badges">
            <button
              class="planning-badge"
              @click="
                router.push({
                  name: 'ModelingSubjectDomain',
                  query: { dataDomainId: String(row.id) },
                })
              "
            >
              主题域{{ row.subjectCount ?? '-' }}
            </button>
            <span class="planning-badge">业务对象{{ row.businessObjectCount ?? '-' }}</span>
            <span class="planning-badge">业务活动{{ row.businessActivityCount ?? '-' }}</span>
          </div>
        </template>
        <template #status="{ row }">
          <Switch
            class="data-domain-status-switch"
            size="small"
            :checked="row.status === 0"
            :loading="busyIds.has(String(row.id))"
            :disabled="!hasAccessByCodes(['modeling:data-domain:update'])"
            :aria-label="`${row.status === 0 ? '禁用' : '启用'}${row.name}`"
            @change="changeStatus(row)"
          />
        </template>
        <template #leader="{ row }">
          <div class="planning-info">
            <span>{{ row.leaderName || userName(row.leaderUserId) }}</span>
            <span class="planning-muted">{{
              row.leaderPhone || userPhone(row.leaderUserId)
            }}</span>
          </div>
        </template>
        <template #creator="{ row }">
          <div class="planning-info">
            <span>{{ row.creatorName || userName(row.creator) }}</span>
            <span class="planning-muted">{{
              row.creatorPhone || userPhone(row.creator)
            }}</span>
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
                auth: ['modeling:data-domain:query'],
                onClick: () => detail(row),
              },
              {
                label: '修改',
                type: 'link',
                icon: ACTION_ICON.EDIT,
                auth: ['modeling:data-domain:update'],
                disabled: row.status === 0,
                onClick: () => formApi.setData({ id: row.id }).open(),
              },
            ]"
            :drop-down-actions="[
              {
                label: '新增主题域',
                icon: ACTION_ICON.ADD,
                auth: ['modeling:subject-domain:create'],
                disabled: row.status !== 0,
                onClick: () =>
                  subjectApi.setData({ dataDomainId: row.id }).open(),
              },
              {
                label: '新增业务实体',
                icon: 'lucide:file-plus',
                auth: ['modeling:business-entity:create'],
                disabled: row.status !== 0,
                onClick: () =>
                  entityApi.setData({ dataDomainId: row.id }).open(),
              },
              {
                label: '删除',
                icon: ACTION_ICON.DELETE,
                danger: true,
                auth: ['modeling:data-domain:delete'],
                disabled: row.status === 0 || deleting,
                onClick: () => remove([row]),
              },
            ]"
          />
        </template>
      </Grid>
    </Page>
  </ConfigProvider>
</template>

<style scoped>
.data-domain-page {
  --planning-blue: hsl(var(--primary));
  --planning-muted: hsl(var(--muted-foreground));
}

:deep(.data-domain-status-switch.ant-switch-small) {
  min-width: 40px;
  height: 20px;
  line-height: 20px;
}

:deep(.data-domain-status-switch.ant-switch-small .ant-switch-handle) {
  width: 16px;
  height: 16px;
}

:deep(
  .data-domain-status-switch.ant-switch-small.ant-switch-checked
    .ant-switch-handle
) {
  inset-inline-start: calc(100% - 18px);
}

.data-domain-info {
  padding-inline-end: 20px;
}

.data-domain-name {
  line-height: 20px;
}
</style>
