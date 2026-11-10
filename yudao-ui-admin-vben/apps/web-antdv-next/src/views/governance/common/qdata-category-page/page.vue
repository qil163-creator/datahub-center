<script setup lang="ts">
import type { CategoryAdapter, CategoryRow } from './model';

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
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import { Alert, Button, message, Modal, Switch, Tooltip } from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { useObjectRequest } from '#/hooks/use-object-request';

import CategoryDetail from './detail.vue';
import CategoryForm from './form.vue';
import {
  branchKeys,
  categoryTree,
  filterCategoryTree,
  parentOptions,
} from './model';
import ReferenceIcon from './reference-icon.vue';

import './style.css';

const props = defineProps<{ adapter: CategoryAdapter }>();
const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const user = useUserStore();
const scope = computed(() =>
  JSON.stringify([
    props.adapter.permissionPrefix,
    access.accessCodes,
    user.userInfo?.userId,
  ]),
);
const can = (action: string) =>
  hasAccessByCodes([`${props.adapter.permissionPrefix}:${action}`]);
const rows = ref<CategoryRow[]>([]);
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
const confirmation = ref<
  | { action: 'delete'; batch: boolean; ids: (number | string)[] }
  | { action: 'status'; row: CategoryRow; status: number }
>();
const listRequest = useObjectRequest(() => scope.value, invalidate);
const mutation = useObjectRequest(() => scope.value);
const tree = computed(() => categoryTree(rows.value));
const parentTreeOptions = computed(() => parentOptions(rows.value));
const displayedRows = computed(() =>
  filterCategoryTree(tree.value, applied.name, applied.parentId),
);

const [Grid, gridApi] = useVbenVxeGrid<CategoryRow>({
  formOptions: {
    submitOnEnter: true,
    commonConfig: { labelWidth: 140, componentProps: { class: 'w-full' } },
    wrapperClass: 'grid-cols-1 md:grid-cols-2 xl:grid-cols-3',
    schema: [
      {
        fieldName: 'name',
        label: () => props.adapter.nameLabel,
        component: 'Input',
        componentProps: () => ({
          allowClear: true,
          placeholder: `请输入${props.adapter.nameLabel}`,
        }),
      },
      {
        fieldName: 'parentId',
        label: '上级类目',
        component: 'TreeSelect',
        componentProps: () => ({
          allowClear: true,
          treeDefaultExpandAll: true,
          treeData: parentTreeOptions.value,
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
        title: props.adapter.nameLabel,
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
        formatter: ({ cellValue }: { cellValue?: string }) => cellValue || '-',
      },
      {
        field: 'createTime',
        title: '创建时间',
        width: 190,
        sortable: true,
        formatter: ({ cellValue }: { cellValue?: number | string }) =>
          cellValue ? formatDate(cellValue, 'YYYY-MM-DD HH:mm') : '-',
      },
      {
        field: 'actions',
        title: '操作',
        width: 320,
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
      row: CategoryRow;
    }) => {
      expandedKeys.value = open
        ? [...new Set([...expandedKeys.value, String(row.id)])]
        : expandedKeys.value.filter((key) => key !== String(row.id));
    },
  },
});

function selectRows({ records }: { records: CategoryRow[] }) {
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
      const expandedRows: CategoryRow[] = [];
      const collect = (nodes: CategoryRow[]) =>
        nodes.forEach((node) => {
          if (keys.has(String(node.id))) expandedRows.push(node);
          if (node.children) collect(node.children);
        });
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
  if (target.action === 'status')
    return `确认要"${target.status === 0 ? '启用' : '禁用'}","${target.row.name}"${props.adapter.label}吗？`;
  return target.batch
    ? `确认删除选中的${target.ids.length}条${props.adapter.label}数据？`
    : `是否确认删除${props.adapter.label}编号为"${target.ids[0]}"的数据项？`;
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
  if (inactive || !can('query')) return;
  loading.value = true;
  failed.value = false;
  try {
    const data = await props.adapter.list();
    if (!current()) return;
    rows.value = data.map((row) => props.adapter.normalize(row));
    selectedKeys.value = [];
    if (expanded.value)
      expandedKeys.value = branchKeys(categoryTree(rows.value));
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

function openForm(
  data: { id?: number | string; parentId?: number | string } = {},
) {
  if (
    !inactive &&
    can(data.id === undefined ? 'create' : 'update') &&
    !busy.value
  )
    void form.value?.open(data);
}

function openDetail(row: CategoryRow) {
  if (!inactive && can('query') && !busy.value) void detail.value?.open(row.id);
}

function askDelete(row?: CategoryRow) {
  if (inactive || !can('delete') || busy.value) return;
  const ids = row ? [row.id] : [...selectedKeys.value];
  if (ids.length === 0) return;
  const selected = rows.value.filter((item) =>
    ids.some((id) => String(item.id) === String(id)),
  );
  if (selected.some((item) => item.status === 0)) {
    message.warning('请先禁用所选类目后再删除');
    return;
  }
  confirmation.value = { action: 'delete', batch: !row, ids };
}

function askStatus(row: CategoryRow) {
  if (inactive || !can('update') || busy.value) return;
  confirmation.value = {
    action: 'status',
    row,
    status: row.status === 0 ? 1 : 0,
  };
}

async function confirmOperation() {
  const target = confirmation.value;
  if (
    !target ||
    busy.value ||
    inactive ||
    !can(target.action === 'status' ? 'update' : 'delete')
  )
    return;
  busy.value = true;
  const current = mutation.begin();
  try {
    if (target.action === 'status') {
      await props.adapter.update(
        props.adapter.toPayload({
          id: target.row.id,
          parentId: target.row.parentId,
          name: target.row.name,
          description: target.row.description ?? '',
          status: target.status,
          sort: target.row.sort,
        }) as any,
      );
    } else {
      for (const id of target.ids) await props.adapter.remove(id);
    }
    if (!current()) return;
    confirmation.value = undefined;
    selectedKeys.value = [];
    message.success(target.action === 'status' ? '状态更新成功' : '删除成功');
    await load();
  } catch {
    // 服务端错误保持当前页面状态，用户可在列表中再次尝试。
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
      v-if="!can('query')"
      message="暂无类目查询权限"
      type="warning"
      show-icon
      class="mb-4"
    />
    <template v-else-if="failed"></template>
    <Grid :table-title="`${adapter.label}列表`" :show-search-form="showFilter">
      <template #toolbar-tools>
        <div class="qpc-toolbar-actions flex items-center gap-2">
          <Button
            v-if="can('create')"
            type="primary"
            :disabled="!can('query') || busy"
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
<ReferenceIcon
                :name="expanded ? 'collapse' : 'expand'"
/>
</template>{{ expanded ? '折叠' : '展开' }}
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
            v-if="can('query')"
            type="link"
            :disabled="busy"
            @click="openDetail(row)"
          >
            <template #icon><IconifyIcon icon="lucide:eye" /></template>详情
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
    <CategoryDetail ref="detail" :adapter="adapter" />
    <CategoryForm ref="form" :adapter="adapter" @saved="load" />
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
