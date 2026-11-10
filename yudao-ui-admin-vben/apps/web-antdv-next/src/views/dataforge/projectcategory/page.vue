<script setup lang="ts">
import type { CategoryNode } from './model';

import type { ProjectCategoryApi as Api } from '#/api/dataforge/projectcategory';

import {
  computed,
  nextTick,
  onActivated,
  onDeactivated,
  reactive,
  ref,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import { Alert, Button, message, Modal, Switch, Tooltip } from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  deleteProjectCategory,
  deleteProjectCategoryList,
  getProjectCategory,
  getProjectCategoryList,
  updateProjectCategoryStatus,
} from '#/api/dataforge/projectcategory';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { useObjectRequest } from '#/hooks/use-object-request';

import CategoryDetail from './detail.vue';
import CategoryForm from './form.vue';
import {
  branchKeys,
  categoryLabels,
  categoryParentOptions,
  categoryPermission,
  categoryTree,
  filterCategoryTree,
  isPositiveCount,
} from './model';
import ReferenceIcon from './reference-icon.vue';

import './style.css';

const props = defineProps<{ kind: Api.Kind }>();
const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const user = useUserStore();
const label = computed(() => categoryLabels[props.kind]);
const scope = computed(() =>
  JSON.stringify([
    props.kind,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const can = (action: string) =>
  hasAccessByCodes([categoryPermission(props.kind, action)]);
const canQuery = computed(() => can('query'));
const rows = ref<Api.Category[]>([]);
const loading = ref(false);
const failed = ref(false);
const busy = ref(false);
const showFilter = ref(true);
const selectedKeys = ref<string[]>([]);
const expandedKeys = ref<string[]>([]);
const expanded = ref(false);
const filter = reactive({
  name: '',
  parentId: undefined as string | undefined,
});
const applied = reactive({ ...filter });
const form = ref<InstanceType<typeof CategoryForm>>();
const detail = ref<InstanceType<typeof CategoryDetail>>();
let inactive = false;

type Confirmation =
  | { action: 'delete'; batch: boolean; ids: Api.Id[]; name?: string }
  | {
      action: 'status';
      childCount: Api.Count;
      row: Api.Category;
      status: number;
    };
const confirmation = ref<Confirmation>();
const listRequest = useObjectRequest(() => scope.value, invalidate);
const mutation = useObjectRequest(() => scope.value);
const tree = computed(() => categoryTree(rows.value));
const displayedRows = computed(() =>
  filterCategoryTree(tree.value, applied.name, applied.parentId),
);
const parentOptions = computed(() => categoryParentOptions(rows.value));
const [Grid, gridApi] = useVbenVxeGrid<CategoryNode>({
  formOptions: {
    submitOnEnter: true,
    commonConfig: { labelWidth: 140, componentProps: { class: 'w-full' } },
    wrapperClass: 'grid-cols-1 md:grid-cols-2 xl:grid-cols-3',
    schema: [
      {
        fieldName: 'name',
        label: () => `${label.value}名称`,
        component: 'Input',
        componentProps: () => ({
          allowClear: true,
          placeholder: `请输入${label.value}名称`,
        }),
      },
      {
        fieldName: 'parentId',
        label: '上级类目',
        component: 'TreeSelect',
        componentProps: () => ({
          allowClear: true,
          treeDefaultExpandAll: true,
          treeData: parentOptions.value,
          placeholder: '请选择上级类目',
        }),
      },
    ],
  },
  gridOptions: {
    height: 'auto',
    keepSource: true,
    data: [],
    columns: [
      { type: 'checkbox', width: 44 },
      {
        field: 'name',
        title: `${label.value}名称`,
        minWidth: 240,
        treeNode: true,
        align: 'left',
      },
      {
        field: 'description',
        title: '描述',
        minWidth: 260,
        slots: { default: 'description' },
      },
      {
        field: 'status',
        title: '状态',
        width: 100,
        slots: { default: 'status' },
      },
      { field: 'sort', title: '排序', width: 100, sortable: true },
      {
        field: 'creatorName',
        title: '创建人',
        width: 120,
        formatter: ({ row }: { row: CategoryNode }) =>
          row.creatorName || row.creator || '-',
      },
      {
        field: 'createTime',
        title: '创建时间',
        width: 190,
        sortable: true,
        formatter: ({
          cellValue,
        }: {
          cellValue?: Api.Category['createTime'];
        }) => (cellValue ? formatDate(cellValue, 'YYYY-MM-DD HH:mm') : '-'),
      },
      {
        field: 'actions',
        title: '操作',
        width: 340,
        fixed: 'right',
        showOverflow: false,
        slots: { default: 'actions' },
      },
    ],
    pagerConfig: { enabled: false },
    showOverflow: false,
    showHeaderOverflow: true,
    rowConfig: { keyField: 'key', isHover: true },
    checkboxConfig: { checkStrictly: false },
    treeConfig: {
      rowField: 'key',
      childrenField: 'children',
      reserve: true,
      indent: 24,
      iconOpen: 'qpc-tree-open',
      iconClose: 'qpc-tree-closed',
    },
    toolbarConfig: {
      refresh: false,
      search: false,
      custom: false,
      zoom: false,
    },
    proxyConfig: {
      autoLoad: false,
      ajax: {
        query: async (
          _: unknown,
          values: { name?: string; parentId?: string },
        ) => {
          filter.name = values.name ?? '';
          filter.parentId = values.parentId;
          search();
          await load();
          return displayedRows.value;
        },
      },
    },
  },
  gridEvents: {
    checkboxAll: selectRows,
    checkboxChange: selectRows,
    toggleTreeExpand: ({
      row,
      expanded: open,
    }: {
      expanded: boolean;
      row: CategoryNode;
    }) => {
      expandedKeys.value = open
        ? [...new Set([...expandedKeys.value, row.key])]
        : expandedKeys.value.filter((key) => key !== row.key);
    },
  },
});
function selectRows({ records }: { records: CategoryNode[] }) {
  selectedKeys.value = records.map((row) => String(row.id));
}
watch(
  displayedRows,
  (data) => {
    gridApi.setGridOptions({ data });
    void gridApi.grid?.clearCheckboxRow?.();
    void nextTick(() => {
      if (expandedKeys.value.length === 0) return;
      const keys = new Set(expandedKeys.value);
      const expandedRows: CategoryNode[] = [];
      function collect(nodes: CategoryNode[]) {
        for (const node of nodes) {
          if (keys.has(node.key)) expandedRows.push(node);
          if (node.children) collect(node.children);
        }
      }
      collect(data);
      void gridApi.grid?.setTreeExpand?.(expandedRows, true);
    });
  },
  { immediate: true },
);
watch(loading, (pending) => gridApi.setGridOptions({ loading: pending }), {
  immediate: true,
});
const confirmationText = computed(() => {
  const target = confirmation.value;
  if (!target) return '';
  if (target.action === 'status') {
    if (target.status === 1 && isPositiveCount(target.childCount))
      return props.kind === 'development'
        ? '停用后，该类目及其子类目将不能用于新建或保存开发任务；子类目自身的启用状态保持不变。是否继续？'
        : '确认停用该父类目？本次仅停用当前类目，子类目的启用状态保持不变。';
    return `确认要"${target.status === 0 ? '启用' : '禁用'}","${target.row.name}"${label.value}吗？`;
  }
  return target.batch
    ? `确认删除选中的${target.ids.length}条${label.value}数据？`
    : `是否确认删除${label.value}编号为"${target.ids[0]}"的数据项？`;
});

function invalidate() {
  rows.value = [];
  loading.value = false;
  failed.value = false;
  busy.value = false;
  selectedKeys.value = [];
  expandedKeys.value = [];
  expanded.value = false;
  confirmation.value = undefined;
  form.value?.close();
  detail.value?.close();
}

async function load() {
  const current = listRequest.begin();
  if (inactive || !canQuery.value) return;
  loading.value = true;
  failed.value = false;
  try {
    const result = await getProjectCategoryList(props.kind);
    if (!current()) return;
    rows.value = result;
    selectedKeys.value = [];
    if (expanded.value) expandedKeys.value = branchKeys(categoryTree(result));
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}

function search() {
  applied.name = filter.name;
  applied.parentId = filter.parentId;
  selectedKeys.value = [];
  expandedKeys.value = branchKeys(displayedRows.value);
  expanded.value = expandedKeys.value.length > 0;
}

function toggleExpand() {
  expanded.value = !expanded.value;
  expandedKeys.value = expanded.value ? branchKeys(displayedRows.value) : [];
  void gridApi.grid?.setAllTreeExpand(expanded.value);
}

function openForm(data: { id?: Api.Id; parentId?: Api.Id } = {}) {
  if (inactive || !canQuery.value || busy.value) return;
  void form.value?.open(data);
}

function openDetail(row: Api.Category) {
  if (inactive || !canQuery.value || busy.value) return;
  void detail.value?.open({ id: row.id });
}

function askDelete(row?: Api.Category) {
  if (inactive || !canQuery.value || !can('delete') || busy.value) return;
  const ids = row ? [row.id] : [...selectedKeys.value];
  if (ids.length === 0) return;
  if (ids.length > 200) {
    message.warning('一次最多删除200个类目');
    return;
  }
  if (row) {
    if (row.status === 0) return;
    const childCount = row.childCount;
    if (isPositiveCount(childCount) && isPositiveCount(row.taskCount)) {
      message.warning(
        `该类目包含${childCount}个子类目和${row.taskCount}个任务，不能直接删除。`,
      );
      return;
    }
    if (isPositiveCount(childCount)) {
      message.warning(`该类目下存在${childCount}个子类目，不能直接删除。`);
      return;
    }
    if (isPositiveCount(row.taskCount)) {
      message.warning(
        `该类目下存在${row.taskCount}个任务，不能直接删除，请先迁移。`,
      );
      return;
    }
  } else if (
    rows.value.some(
      (item) =>
        ids.some((id) => String(item.id) === String(id)) &&
        isPositiveCount(item.taskCount),
    )
  ) {
    message.warning(
      `本次选择 ${ids.length} 个类目，存在被任务引用的类目，不能删除。`,
    );
    return;
  } else if (
    rows.value.some(
      (item) =>
        ids.some((id) => String(item.id) === String(id)) && item.status === 0,
    )
  ) {
    message.warning('请先禁用所选类目后再删除');
    return;
  }
  confirmation.value = { action: 'delete', batch: !row, ids, name: row?.name };
}

async function askStatus(row: Api.Category) {
  if (inactive || !canQuery.value || !can('update') || busy.value) return;
  const status = row.status === 0 ? 1 : 0;
  const childCount = row.childCount;
  if (status === 0) {
    confirmation.value = { action: 'status', row, status, childCount };
    return;
  }
  const current = mutation.begin();
  busy.value = true;
  try {
    const detail = await getProjectCategory(props.kind, row.id);
    if (!current()) return;
    confirmation.value = {
      action: 'status',
      row: detail,
      status,
      childCount: detail.childCount,
    };
  } catch {
    // 详情读取失败时保持受控开关原值，统一请求层报告失败。
  } finally {
    if (current()) busy.value = false;
  }
}

async function confirmOperation() {
  const target = confirmation.value;
  if (!target || busy.value || inactive || !canQuery.value) return;
  if (!can(target.action === 'status' ? 'update' : 'delete')) return;
  busy.value = true;
  const current = mutation.begin();
  try {
    if (target.action === 'status') {
      await updateProjectCategoryStatus(
        props.kind,
        target.row.id,
        target.status,
      );
    } else if (target.batch) {
      await deleteProjectCategoryList(props.kind, target.ids);
    } else {
      await deleteProjectCategory(props.kind, target.ids[0]!);
    }
    if (!current()) return;
    confirmation.value = undefined;
    selectedKeys.value = [];
    message.success(target.action === 'status' ? '状态更新成功' : '删除成功');
    await load();
  } catch {
    // 服务端拒绝时保持原始状态与选择，由请求层显示具体原因。
  } finally {
    if (current()) busy.value = false;
  }
}

watch(
  () => [scope.value, access.accessToken, access.refreshToken],
  () => {
    invalidate();
    void load();
  },
  { immediate: true },
);
onDeactivated(() => {
  inactive = true;
  listRequest.begin();
  mutation.begin();
  invalidate();
});
onActivated(() => {
  if (!inactive) return;
  inactive = false;
  void load();
});
</script>

<template>
  <Page auto-content-height class="qpc">
    <Alert
      v-if="!canQuery"
      message="暂无类目查询权限"
      type="warning"
      show-icon
      class="mb-4"
    />

    <Grid :table-title="`${label}列表`" :show-search-form="showFilter">
      <template #toolbar-tools>
        <div class="qpc-toolbar-actions flex items-center gap-2">
          <Button
            v-if="can('create')"
            type="primary"
            :disabled="!canQuery || busy"
            @click="openForm()"
          >
            <template #icon><ReferenceIcon name="add" /></template>新增
          </Button>
          <Button
            v-if="can('delete')"
            danger
            :disabled="!selectedKeys.length || busy"
            @click="askDelete()"
          >
            <template #icon><ReferenceIcon name="delete" /></template>删除
          </Button>
          <Button type="primary" @click="toggleExpand">
            <template #icon>
              <ReferenceIcon :name="expanded ? 'collapse' : 'expand'" />
            </template>
            {{ expanded ? '折叠' : '展开' }}
          </Button>
          <Tooltip title="显示/隐藏查询">
            <Button
              aria-label="显示/隐藏查询"
              @click="showFilter = !showFilter"
            >
              <ReferenceIcon name="search" />
            </Button>
          </Tooltip>
          <Tooltip title="刷新">
            <Button aria-label="刷新" :loading="loading" @click="load">
              <ReferenceIcon name="refresh" />
            </Button>
          </Tooltip>
          <Tooltip title="列设置">
            <Button aria-label="列设置" @click="gridApi.grid?.openCustom()">
              <ReferenceIcon name="columns" />
            </Button>
          </Tooltip>
        </div>
      </template>
      <template #status="{ row }">
        <Switch
          :checked="row.status === 0"
          :disabled="!can('update') || busy"
          :aria-label="`${row.name}状态`"
          @change="askStatus(row)"
        />
      </template>
      <template #description="{ row }">
        <TextEllipsisCell
          class="qpc-description-cell"
          :content="row.description || '-'"
          :line="2"
          center
        />
      </template>
      <template #actions="{ row }">
        <div class="qpc-row-actions">
          <Button
            v-if="canQuery"
            type="link"
            :disabled="busy"
            @click="openDetail(row)"
          >
            <template #icon><ReferenceIcon name="query" /></template>详情
          </Button>
          <Button
            v-if="can('update')"
            type="link"
            :disabled="busy"
            @click="openForm({ id: row.id })"
          >
            <template #icon><ReferenceIcon name="edit" /></template>修改
          </Button>
          <Button
            v-if="can('create')"
            type="link"
            :disabled="busy"
            @click="openForm({ parentId: row.id })"
          >
            <template #icon><ReferenceIcon name="add" /></template>新增
          </Button>
          <Button
            v-if="can('delete')"
            type="link"
            danger
            :disabled="busy || row.status === 0"
            @click="askDelete(row)"
          >
            <template #icon><ReferenceIcon name="delete" /></template>删除
          </Button>
        </div>
      </template>
    </Grid>
    <CategoryForm ref="form" :kind="kind" @saved="load" />
    <CategoryDetail ref="detail" :kind="kind" />
    <Modal
      :open="!!confirmation"
      title="系统提示"
      :width="420"
      :confirm-loading="busy"
      :closable="!busy"
      :mask-closable="false"
      class="qpc-confirmation"
      ok-text="确定"
      cancel-text="取消"
      @ok="confirmOperation"
      @cancel="!busy && (confirmation = undefined)"
    >
      <template #closeIcon><ReferenceIcon name="close" /></template>
      <p class="whitespace-pre-wrap break-words">{{ confirmationText }}</p>
    </Modal>
  </Page>
</template>
