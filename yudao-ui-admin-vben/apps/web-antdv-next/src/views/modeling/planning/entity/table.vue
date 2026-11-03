<script setup lang="ts">
import type { BusinessEntityApi } from '#/api/modeling/business-entity';

import {
  computed,
  nextTick,
  onActivated,
  onDeactivated,
  ref,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { confirm, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Button, message, Switch, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteBusinessEntity,
  deleteBusinessEntityList,
  getBusinessEntityTreePage,
  updateBusinessEntityStatus,
  updateBusinessEntityStatusBatch,
} from '#/api/modeling/business-entity';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { useObjectRequest } from '#/hooks/use-object-request';

import EntityDetail from './detail.vue';
import EntityForm from './form.vue';
import { ENTITY_STATUSES, ENTITY_SUBTYPES, ENTITY_TYPES } from './state';
import { normalizeEntityTree } from './tree-state';

const props = defineProps<{
  dataDomainId?: BusinessEntityApi.Id;
  subjectDomainId?: BusinessEntityApi.Id;
}>();
const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const keyword = ref('');
const entitySubtypes = ref<BusinessEntityApi.EntitySubtype[]>([]);
const status = ref<BusinessEntityApi.Status>();
const selected = ref<BusinessEntityApi.Entity[]>([]);
const pageNo = ref(1);
const pageSize = ref(10);
const sortOrder = ref<'asc' | 'desc'>('desc');
const failed = ref(false);
const active = ref(true);
const busy = ref(false);
const isExpanded = ref(true);
const scope = computed(
  () =>
    `${props.dataDomainId}/${props.subjectDomainId}/${access.accessCodes.join(',')}/${active.value}`,
);
const request = useObjectRequest(
  () => scope.value,
  () => {
    selected.value = [];
    gridApi.setGridOptions({ data: [], loading: false });
  },
);
const mutation = useObjectRequest(
  () => scope.value,
  () => {
    busy.value = false;
  },
);
const [FormModal, formApi] = useVbenModal({
  connectedComponent: EntityForm,
  destroyOnClose: true,
});
const [DetailModal, detailApi] = useVbenModal({
  connectedComponent: EntityDetail,
  destroyOnClose: true,
});
const types = [
  {
    label: '业务对象',
    options: ['NORMAL', 'HIERARCHICAL'].map((value) => ({
      value,
      label: ENTITY_SUBTYPES[value as BusinessEntityApi.EntitySubtype].label,
    })),
  },
  {
    label: '业务活动',
    options: ['EVENT', 'PROCESS'].map((value) => ({
      value,
      label: ENTITY_SUBTYPES[value as BusinessEntityApi.EntitySubtype].label,
    })),
  },
];
const statuses = [
  { value: 0, label: ENTITY_STATUSES[0] },
  { value: 1, label: ENTITY_STATUSES[1] },
];
const [Grid, gridApi] = useVbenVxeGrid<BusinessEntityApi.Entity>({
  formOptions: {
    submitOnEnter: true,
    wrapperClass: 'grid-cols-1 lg:grid-cols-2 xl:grid-cols-3',
    schema: [
      {
        fieldName: 'keyword',
        label: '实体信息',
        component: 'Input',
        componentProps: {
          allowClear: true,
          placeholder: '请输入实体名称或实体编码',
        },
      },
      {
        fieldName: 'entitySubtypes',
        label: '实体类型',
        component: 'Select',
        defaultValue: [],
        componentProps: {
          mode: 'multiple',
          maxTagCount: 1,
          options: types,
          allowClear: true,
          placeholder: '请选择实体类型',
        },
      },
      {
        fieldName: 'status',
        label: '状态',
        component: 'Select',
        componentProps: {
          options: statuses,
          allowClear: true,
          placeholder: '请选择状态',
        },
      },
    ],
    handleSubmit: async (values) => {
      keyword.value = values.keyword ?? '';
      entitySubtypes.value = values.entitySubtypes ?? [];
      status.value = values.status;
      await load(true);
    },
    handleReset: async () => {
      await gridApi.formApi.reset();
      reset();
    },
  },
  gridOptions: {
    data: [],
    border: false,
    round: false,
    stripe: true,
    showOverflow: false,
    rowConfig: { keyField: 'id', isHover: true },
    treeConfig: {
      childrenField: 'children',
      expandAll: true,
      indent: 24,
    },
    checkboxConfig: { checkStrictly: true, showHeader: true },
    columnConfig: { resizable: false },
    proxyConfig: { enabled: false },
    sortConfig: {
      remote: true,
      defaultSort: { field: 'createTime', order: 'desc' },
    },
    pagerConfig: {
      currentPage: 1,
      pageSize: 10,
      pageSizes: [10, 20, 50, 100],
      total: 0,
      layouts: ['Total', 'Sizes', 'PrevPage', 'Number', 'NextPage', 'FullJump'],
    },
    toolbarConfig: {
      search: true,
      refresh: true,
      refreshOptions: { queryMethod: () => load() },
      custom: true,
      zoom: false,
    },
    columns: [
      { type: 'checkbox', width: 38 },
      {
        field: 'name',
        title: '实体信息',
        minWidth: 360,
        treeNode: true,
        align: 'left',
        slots: { default: 'info' },
      },
      {
        field: 'description',
        title: '描述',
        minWidth: 195,
        align: 'left',
        slots: { default: 'description' },
      },
      {
        field: 'subjectDomainName',
        title: '所属主题域',
        minWidth: 155,
        align: 'left',
      },
      {
        field: 'entityType',
        title: '实体类型',
        width: 145,
        align: 'left',
        slots: { default: 'entityType' },
      },
      {
        field: 'logicalModelCount',
        title: '相关逻辑表',
        width: 120,
        slots: { default: 'models' },
      },
      {
        field: 'status',
        title: '状态',
        width: 90,
        slots: { default: 'status' },
      },
      {
        field: 'leaderName',
        title: '负责人',
        width: 170,
        align: 'left',
        slots: { default: 'leader' },
      },
      {
        field: 'creatorName',
        title: '创建人',
        width: 170,
        align: 'left',
        slots: { default: 'creator' },
      },
      {
        field: 'createTime',
        title: '创建时间',
        width: 175,
        sortable: true,
        formatter: ({ cellValue }: { cellValue?: number | string }) =>
          formatDateTime(cellValue) || '-',
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
    toggleTreeExpand: () => {
      isExpanded.value =
        (gridApi.grid?.getTreeExpandRecords().length ?? 0) > 0;
    },
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
    sortChange: ({ order }: { order: string }) => {
      sortOrder.value = order === 'asc' ? 'asc' : 'desc';
      void load(true);
    },
    checkboxChange: ({ records }: { records: BusinessEntityApi.Entity[] }) => {
      selected.value = records;
    },
    checkboxAll: ({ records }: { records: BusinessEntityApi.Entity[] }) => {
      selected.value = records;
    },
  },
});
async function load(reset = false) {
  if (reset) pageNo.value = 1;
  const current = request.begin();
  if (
    !active.value ||
    props.dataDomainId === undefined ||
    !hasAccessByCodes(['modeling:business-entity:query'])
  )
    return;
  failed.value = false;
  selected.value = [];
  gridApi.grid?.clearCheckboxRow?.();
  gridApi.setGridOptions({ loading: true });
  try {
    const result = await getBusinessEntityTreePage({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      dataDomainId: props.dataDomainId,
      subjectDomainId: props.subjectDomainId,
      keyword: keyword.value.trim() || undefined,
      entitySubtypes:
        entitySubtypes.value.length > 0 ? entitySubtypes.value : undefined,
      status: status.value,
      sortOrder: sortOrder.value,
    });
    if (!current()) return;
    gridApi.setGridOptions({
      data: normalizeEntityTree(result.list),
      pagerConfig: {
        currentPage: pageNo.value,
        pageSize: pageSize.value,
        total: result.total,
      },
    });
    await nextTick();
    if (current()) await gridApi.grid?.setAllTreeExpand(isExpanded.value);
  } catch {
    if (current()) {
      failed.value = true;
      gridApi.setGridOptions({ data: [], pagerConfig: { total: 0 } });
    }
  } finally {
    if (current()) gridApi.setGridOptions({ loading: false });
  }
}
function toggleTree() {
  isExpanded.value = !isExpanded.value;
  void gridApi.grid?.setAllTreeExpand(isExpanded.value);
}
function reset() {
  keyword.value = '';
  entitySubtypes.value = [];
  status.value = undefined;
  void load(true);
}
function notAvailable() {
  message.info('功能开发中');
}
async function changeStatus(
  rows: BusinessEntityApi.Entity[],
  target: BusinessEntityApi.Status,
) {
  const candidates = rows.filter((row) => row.status !== target);
  if (
    candidates.length === 0 ||
    busy.value ||
    !hasAccessByCodes(['modeling:business-entity:publish'])
  )
    return;
  const current = mutation.begin();
  busy.value = true;
  try {
    try {
      await confirm(
        `确认${target === 0 ? '上线' : '下线'}${candidates.length === 1 ? `“${candidates[0]?.name}”` : `选中的 ${candidates.length} 个业务实体`}？`,
      );
    } catch {
      return;
    }
    if (!current()) return;
    const targets = candidates.map((row) => ({
      id: row.id,
      expectedRevision: row.revision,
    }));
    await (targets.length === 1
      ? updateBusinessEntityStatus({ ...targets[0]!, status: target })
      : updateBusinessEntityStatusBatch(targets, target));
    if (!current()) return;
    message.success(`${target === 0 ? '上线' : '下线'}成功`);
    await load();
  } finally {
    if (current()) busy.value = false;
  }
}
async function remove(rows: BusinessEntityApi.Entity[]) {
  if (
    rows.length === 0 ||
    rows.some((row) => row.status === 0) ||
    busy.value ||
    !hasAccessByCodes(['modeling:business-entity:delete'])
  )
    return;
  const current = mutation.begin();
  busy.value = true;
  try {
    try {
      await confirm(
        `确认删除${rows.length === 1 ? `“${rows[0]?.name}”` : `选中的 ${rows.length} 个业务实体`}？`,
      );
    } catch {
      return;
    }
    if (!current()) return;
    await (rows.length === 1
      ? deleteBusinessEntity(rows[0]!.id, rows[0]!.revision)
      : deleteBusinessEntityList(
          rows.map((row) => ({ id: row.id, expectedRevision: row.revision })),
        ));
    if (!current()) return;
    message.success('删除成功');
    await load();
  } finally {
    if (current()) busy.value = false;
  }
}
watch(
  () => `${scope.value}/${access.accessToken}/${access.refreshToken}`,
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
  <div class="planning-entity-table planning-embedded">
    <FormModal @success="load()" /><DetailModal />
    <div v-if="failed" class="planning-error">
      业务实体加载失败。<Button type="link" @click="load()">重试</Button>
    </div>
    <div class="planning-table">
      <Grid
        :style="{ '--vxe-ui-font-size-small': '12px' }"
        table-title="业务实体"
      >
        <template #toolbar-tools>
          <div class="planning-toolbar">
            <Button
              type="primary"
              v-if="hasAccessByCodes(['modeling:business-entity:create'])"
              @click="formApi.setData({ dataDomainId, subjectDomainId }).open()"
            >
              <IconifyIcon icon="lucide:plus" />新增
            </Button>
            <Button @click="toggleTree">
              <IconifyIcon
                :icon="isExpanded ? 'lucide:list-minus' : 'lucide:list-plus'"
              />{{ isExpanded ? '收起' : '展开' }}
            </Button>
            <Button
              v-if="hasAccessByCodes(['modeling:business-entity:publish'])"
              :disabled="busy || !selected.some((row) => row.status === 1)"
              @click="changeStatus(selected, 0)"
            >
              <IconifyIcon icon="lucide:send" />批量上线
            </Button>
            <Button
              v-if="hasAccessByCodes(['modeling:business-entity:publish'])"
              class="planning-warning"
              :disabled="busy || !selected.some((row) => row.status === 0)"
              @click="changeStatus(selected, 1)"
            >
              <IconifyIcon icon="lucide:send" />批量下线
            </Button>
            <Button
              v-if="hasAccessByCodes(['modeling:business-entity:delete'])"
              danger
              :disabled="
                busy ||
                !selected.length ||
                selected.some((row) => row.status === 0)
              "
              @click="remove(selected)"
            >
              <IconifyIcon icon="lucide:trash-2" />批量删除
            </Button>
          </div>
        </template>
        <template #info="{ row }">
          <div class="planning-info">
            <div class="planning-entity-name">
              <button
                type="button"
                class="planning-link"
                @click="detailApi.setData({ id: row.id }).open()"
              >
                <img
                  class="planning-icon"
                  :src="
                    ENTITY_SUBTYPES[
                      row.entitySubtype as BusinessEntityApi.EntitySubtype
                    ].icon
                  "
                  alt=""
                />
                <span>{{ row.name }}</span>
                <span class="planning-relation-symbol" aria-hidden="true">
                  ⌘
                </span>
              </button>
              <Tag v-if="row.children?.length" color="success">包含关系</Tag>
            </div>
            <span class="planning-muted">{{ row.code }}</span>
          </div>
        </template>
        <template #description="{ row }">
          <TextEllipsisCell
            class="planning-entity-description"
            :content="row.description || '-'"
            :line="2"
            center
          />
        </template>
        <template #entityType="{ row }">
          <div class="planning-info">
            <span>{{
              ENTITY_TYPES[row.entityType as BusinessEntityApi.EntityType]
            }}</span>
            <span class="planning-muted">{{
              ENTITY_SUBTYPES[
                row.entitySubtype as BusinessEntityApi.EntitySubtype
              ].label
            }}</span>
          </div>
        </template>
        <template #models="{ row }">
          {{
            row.logicalModelCount === null ||
            row.logicalModelCount === undefined
              ? '-'
              : `${row.logicalModelCount}张`
          }}
        </template>
        <template #status="{ row }">
          <Switch
            size="small"
            :checked="row.status === 0"
            :loading="busy"
            :disabled="!hasAccessByCodes(['modeling:business-entity:publish'])"
            :aria-label="`${row.status === 0 ? '下线' : '上线'}${row.name}`"
            @change="changeStatus([row], row.status === 0 ? 1 : 0)"
          />
        </template>
        <template #leader="{ row }">
          <div class="planning-info">
            <span>{{ row.leaderName || '-' }}</span>
            <span class="planning-muted">{{ row.leaderPhone || '' }}</span>
          </div>
        </template>
        <template #creator="{ row }">
          <div class="planning-info">
            <span>{{ row.creatorName || '-' }}</span>
            <span class="planning-muted">{{ row.creatorPhone || '' }}</span>
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
                auth: ['modeling:business-entity:query'],
                onClick: () => detailApi.setData({ id: row.id }).open(),
              },
              {
                label: '关系图',
                type: 'link',
                icon: 'lucide:network',
                auth: ['modeling:business-entity:query'],
                onClick: notAvailable,
              },
            ]"
            :drop-down-actions="[
              {
                label: '修改',
                icon: ACTION_ICON.EDIT,
                auth: ['modeling:business-entity:update'],
                disabled: row.status === 0 || busy,
                onClick: () => formApi.setData({ id: row.id }).open(),
              },
              {
                label: '版本',
                icon: 'lucide:file-clock',
                auth: ['modeling:business-entity:query'],
                onClick: notAvailable,
              },
              {
                label: row.status === 0 ? '下线' : '上线',
                icon: 'lucide:send',
                auth: ['modeling:business-entity:publish'],
                disabled: busy,
                onClick: () => changeStatus([row], row.status === 0 ? 1 : 0),
              },
              {
                label: '删除',
                icon: ACTION_ICON.DELETE,
                danger: true,
                auth: ['modeling:business-entity:delete'],
                disabled: row.status === 0 || busy,
                onClick: () => remove([row]),
              },
            ]"
          />
        </template>
      </Grid>
    </div>
  </div>
</template>

<style scoped>
.planning-entity-name {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
}

.planning-entity-name .planning-link {
  min-width: 0;
}

.planning-entity-name :deep(.ant-tag) {
  flex-shrink: 0;
  margin-inline-end: 0;
}
</style>
