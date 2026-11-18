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
import { CommonStatusEnum } from '@vben/constants';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import { Alert, Button, message, Modal, Switch } from 'antdv-next';

import {
  ACTION_ICON,
  TableAction,
  useVbenVxeGrid,
} from '#/adapter/business-vxe-table';
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
const deletableIds = computed(
  () =>
    new Set(
      rows.value
        .filter((row) => row.status === CommonStatusEnum.DISABLE)
        .map((row) => String(row.id)),
    ),
);
const canDeleteSelection = computed(
  () =>
    !busy.value &&
    !loading.value &&
    !failed.value &&
    canDeleteIds(selectedKeys.value),
);

function canDeleteIds(ids: (number | string)[]) {
  return ids.length > 0 && ids.every((id) => deletableIds.value.has(String(id)));
}

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
      { field: 'id', title: '编号', minWidth: 160 },
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
      refresh: true,
      refreshOptions: { queryMethod: load },
      search: true,
      custom: true,
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
  if (inactive || !can('delete') || busy.value || loading.value || failed.value)
    return;
  const ids = row ? [row.id] : [...selectedKeys.value];
  if (ids.length === 0) return;
  if (ids.length > 200) {
    message.warning('一次最多删除200个类目');
    return;
  }
  if (!canDeleteIds(ids)) {
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
  if (target.action === 'delete') {
    if (loading.value || failed.value) return;
    if (!canDeleteIds(target.ids)) {
      confirmation.value = undefined;
      message.warning('所选类目已变化，请重新选择后再删除');
      return;
    }
  }
  busy.value = true;
  const current = mutation.begin();
  try {
    if (target.action === 'status') {
      await props.adapter.update({
        id: target.row.id,
        parentId: target.row.parentId,
        name: target.row.name,
        categoryCode: target.row.categoryCode,
        description: target.row.description ?? '',
        status: target.status,
        sort: target.row.sort,
      });
    } else if (target.batch) {
      await props.adapter.removeList(target.ids);
    } else {
      await props.adapter.remove(target.ids[0]!);
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
    <Grid :table-title="`${adapter.label}列表`">
      <template #toolbar-tools>
        <div class="flex items-center gap-2">
          <Button
            v-if="can('create')"
            type="primary"
            :disabled="!can('query') || busy"
            @click="openForm()"
          >
            <template #icon><IconifyIcon :icon="ACTION_ICON.ADD" /></template>新增
          </Button>
          <Button
            v-if="can('delete')"
            type="link"
            danger
            :disabled="!canDeleteSelection"
            @click="askDelete()"
          >
            <template #icon><IconifyIcon :icon="ACTION_ICON.DELETE" /></template>批量删除
          </Button>
          <Button type="primary" @click="toggleExpand">
            <template #icon>
              <IconifyIcon
                :icon="expanded ? 'lucide:chevrons-down-up' : 'lucide:chevrons-up-down'"
              />
            </template>{{ expanded ? '收缩' : '展开' }}
          </Button>
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
        <TableAction
          :actions="[
            {
              label: '详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: [`${adapter.permissionPrefix}:query`],
              disabled: busy,
              onClick: () => openDetail(row),
            },
            {
              label: '修改',
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: [`${adapter.permissionPrefix}:update`],
              disabled: busy,
              onClick: () => openForm({ id: row.id }),
            },
            {
              label: '新增',
              type: 'link',
              icon: ACTION_ICON.ADD,
              auth: [`${adapter.permissionPrefix}:create`],
              disabled: busy,
              onClick: () => openForm({ parentId: row.id }),
            },
            {
              label: '删除',
              type: 'link',
              icon: ACTION_ICON.DELETE,
              auth: [`${adapter.permissionPrefix}:delete`],
              danger: true,
              disabled: busy || row.status === 0,
              onClick: () => askDelete(row),
            },
          ]"
        />
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
