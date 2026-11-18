<script setup lang="ts">
import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { computed, nextTick, onActivated, onDeactivated, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { useAccessStore, useUserStore } from '@vben/stores';
import { handleTree } from '@vben/utils';

import { Alert, Descriptions, DescriptionsItem, message, Modal } from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { deletePlanCategory, getPlanCategory, getPlanCategoryList } from '#/api/dataforge/fill';
import { ACTION_ICON, TableAction } from '#/components/table-action';
import { useObjectRequest } from '#/hooks/use-object-request';

import CategoryForm from '../plan/modules/category-form.vue';

import '../../projectcategory/style.css';

defineOptions({ name: 'DataforgeFillCategory' });

interface CategoryNode extends DataforgeFillApi.PlanCategory {
  children?: CategoryNode[];
  key: string;
}

const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const user = useUserStore();
const can = (action: string) => hasAccessByCodes([`dataforge:fill-plan-category:${action}`]);
const canQuery = computed(() => can('query'));
const rows = ref<DataforgeFillApi.PlanCategory[]>([]);
const loading = ref(false);
const failed = ref(false);
const busy = ref(false);
const expanded = ref(false);
const keyword = ref('');
const appliedKeyword = ref('');
const detail = ref<DataforgeFillApi.PlanCategory>();
const toDelete = ref<DataforgeFillApi.PlanCategory>();
let inactive = false;

function invalidate() {
  rows.value = [];
  loading.value = false;
  failed.value = false;
  busy.value = false;
  detail.value = undefined;
  toDelete.value = undefined;
}
const scope = () => JSON.stringify([user.userInfo?.userId, user.userRoles, access.accessCodes]);
const listRequest = useObjectRequest(scope, invalidate);
const mutationRequest = useObjectRequest(scope);
const detailRequest = useObjectRequest(scope);
const tree = computed(() => handleTree<CategoryNode>(rows.value
  .toSorted((a, b) => a.sort - b.sort)
  .map((item) => ({ ...item, id: String(item.id), parentId: String(item.parentId), key: String(item.id) }))));

function filterTree(nodes: CategoryNode[], text: string): CategoryNode[] {
  return nodes.flatMap((node) => {
    const children = filterTree(node.children ?? [], text);
    const hasChildren = children.length > 0;
    return !text || node.name.toLocaleLowerCase().includes(text) || hasChildren
      ? [{ ...node, children: hasChildren ? children : undefined }]
      : [];
  });
}
const displayed = computed(() => filterTree(tree.value, appliedKeyword.value.trim().toLocaleLowerCase()));
const parentName = computed(() => {
  if (!detail.value || String(detail.value.parentId) === '0') return '顶级类目';
  return rows.value.find((row) => String(row.id) === String(detail.value?.parentId))?.name ?? '-';
});

const [Grid, gridApi] = useVbenVxeGrid<CategoryNode>({
  formOptions: {
    submitOnEnter: true,
    commonConfig: { labelWidth: 140, componentProps: { class: 'w-full' } },
    wrapperClass: 'grid-cols-1 md:grid-cols-2 xl:grid-cols-3',
    schema: [{ fieldName: 'name', label: '数据填报类目名称', component: 'Input', componentProps: { allowClear: true, placeholder: '请输入数据填报类目名称' } }],
  },
  gridOptions: {
    height: 'auto',
    data: [],
    columns: [
      { field: 'id', title: '编号', minWidth: 160 },
      { field: 'name', title: '数据填报类目名称', minWidth: 240, treeNode: true, align: 'left' },
      { field: 'sort', title: '排序', width: 120 },
      {
        field: 'status',
        title: '状态',
        width: 100,
        cellRender: {
          name: 'CellDict',
          props: { type: DICT_TYPE.COMMON_STATUS },
        },
      },
      {
        field: 'remark',
        title: '备注',
        minWidth: 200,
        showOverflow: 'tooltip',
        formatter: ({ row }: { row: CategoryNode }) => row.remark || '—',
      },
      {
        field: 'createTime',
        title: '创建时间',
        width: 180,
        formatter: 'formatDateTime',
      },
      { field: 'actions', title: '操作', width: 290, fixed: 'right', showOverflow: false, slots: { default: 'actions' } },
    ],
    pagerConfig: { enabled: false },
    showOverflow: false,
    rowConfig: { keyField: 'key', isHover: true },
    treeConfig: { rowField: 'key', childrenField: 'children', indent: 24, iconOpen: 'qpc-tree-open', iconClose: 'qpc-tree-closed' },
    toolbarConfig: {
      refresh: true,
      refreshOptions: { queryMethod: () => (loading.value ? undefined : load()) },
      search: true,
      custom: true,
    },
    proxyConfig: { autoLoad: false, ajax: { query: async (_: unknown, values: { name?: string }) => {
      keyword.value = values.name ?? '';
      appliedKeyword.value = keyword.value;
      await load();
      expanded.value = Boolean(appliedKeyword.value.trim());
      await nextTick();
      void gridApi.grid?.setAllTreeExpand(expanded.value);
      return displayed.value;
    } } },
  },
});
watch(displayed, (data) => {
  gridApi.setGridOptions({ data });
  void nextTick(() => gridApi.grid?.setAllTreeExpand(expanded.value));
}, { immediate: true });
watch(loading, (value) => gridApi.setGridOptions({ loading: value }), { immediate: true });

async function load() {
  const current = listRequest.begin();
  if (inactive || !canQuery.value) return;
  loading.value = true;
  failed.value = false;
  try {
    const result = await getPlanCategoryList();
    if (current()) rows.value = result;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}

const [CategoryFormModal, categoryFormModalApi] = useVbenModal({ connectedComponent: CategoryForm, destroyOnClose: true });
function openForm(data: { id?: number | string; parentId?: number | string } = {}) {
  if (inactive || busy.value || !canQuery.value) return;
  categoryFormModalApi.setData({ ...data, categoryLabel: '数据填报类目' }).open();
}
function toggleExpand() {
  expanded.value = !expanded.value;
  void gridApi.grid?.setAllTreeExpand(expanded.value);
}
async function openDetail(row: CategoryNode) {
  if (!canQuery.value || busy.value) return;
  const current = detailRequest.begin();
  busy.value = true;
  try {
    const result = await getPlanCategory(row.id);
    if (current()) detail.value = result;
  } catch {
    // 请求层负责提示错误，详情保持关闭。
  } finally {
    if (current()) busy.value = false;
  }
}
async function confirmDelete() {
  const target = toDelete.value;
  if (
    !target ||
    target.status === CommonStatusEnum.ENABLE ||
    busy.value ||
    !can('delete')
  ) return;
  const current = mutationRequest.begin();
  busy.value = true;
  try {
    await deletePlanCategory(target.id);
    if (!current()) return;
    toDelete.value = undefined;
    message.success('类目已删除');
    await load();
  } catch {
    // 删除失败时保留确认框，便于用户重试或取消。
  } finally {
    if (current()) busy.value = false;
  }
}
watch(
  () => [scope(), access.accessToken, access.refreshToken, canQuery.value],
  (_, previous) => {
    invalidate();
    if (previous) void categoryFormModalApi.close();
    void load();
  },
  { immediate: true },
);
onDeactivated(() => {
  inactive = true;
  listRequest.begin();
  mutationRequest.begin();
  detailRequest.begin();
  invalidate();
  void categoryFormModalApi.close();
});
onActivated(() => { if (inactive) { inactive = false; void load(); } });
</script>

<template>
  <Page auto-content-height class="qpc">
    <Alert v-if="!canQuery" message="暂无类目查询权限" type="warning" show-icon class="mb-4" />

    <Grid table-title="数据填报类目列表">
      <template #toolbar-tools>
        <TableAction
          class="qpc-toolbar-actions"
          :actions="[
            {
              label: '新增',
              type: 'primary',
              icon: ACTION_ICON.ADD,
              ifShow: can('create'),
              disabled: !canQuery || busy,
              onClick: () => openForm(),
            },
            {
              label: expanded ? '收缩' : '展开',
              icon: expanded
                ? 'lucide:chevrons-down-up'
                : 'lucide:chevrons-up-down',
              type: 'primary',
              onClick: toggleExpand,
            },
          ]"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          class="qpc-row-actions"
          :actions="[
            {
              label: '详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              ifShow: canQuery,
              disabled: busy,
              onClick: () => openDetail(row),
            },
            {
              label: '修改',
              type: 'link',
              icon: ACTION_ICON.EDIT,
              ifShow: can('update'),
              disabled: busy,
              onClick: () => openForm({ id: row.id }),
            },
            {
              label: '新增',
              type: 'link',
              icon: ACTION_ICON.ADD,
              ifShow: can('create'),
              disabled: busy,
              onClick: () => openForm({ parentId: row.id }),
            },
            {
              label: '删除',
              type: 'link',
              icon: ACTION_ICON.DELETE,
              ifShow: can('delete'),
              danger: true,
              disabled: busy || row.status === CommonStatusEnum.ENABLE,
              onClick: () => (toDelete = row),
            },
          ]"
        />
      </template>
    </Grid>
    <CategoryFormModal @success="load" />
    <Modal :open="!!detail" title="数据填报类目详情" :footer="null" @cancel="detail = undefined">
      <Descriptions :column="1" bordered><DescriptionsItem label="类目名称">{{ detail?.name }}</DescriptionsItem><DescriptionsItem label="上级类目">{{ parentName }}</DescriptionsItem><DescriptionsItem label="排序">{{ detail?.sort }}</DescriptionsItem></Descriptions>
    </Modal>
    <Modal :open="!!toDelete" title="系统提示" :confirm-loading="busy" :closable="!busy" :mask-closable="false" ok-text="删除" cancel-text="取消" @ok="confirmDelete" @cancel="!busy && (toDelete = undefined)">
      删除类目「{{ toDelete?.name }}」？类目下有子类目或采集计划时不能删除。
    </Modal>
  </Page>
</template>
