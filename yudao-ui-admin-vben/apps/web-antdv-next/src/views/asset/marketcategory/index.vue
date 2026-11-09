<script lang="ts" setup>
import type { AssetMarketCategoryApi } from '#/api/asset/marketcategory';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  ref,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import { message, Switch } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteMarketCategory,
  deleteMarketCategoryList,
  getMarketCategoryList,
  updateMarketCategoryStatus,
} from '#/api/asset/marketcategory';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { parseResourceId } from '#/utils/resource-id';

import { marketCategoryTree } from './category-tree';
import { useGridColumns, useGridFormSchema } from './data';
import Detail from './modules/detail.vue';
import Form from './modules/form.vue';

defineOptions({ name: 'AssetMarketCategory' });
const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const user = useUserStore();
const scope = computed(() =>
  JSON.stringify([
    user.userInfo?.userId,
    user.userRoles,
    access.accessCodes,
    access.accessToken,
    access.refreshToken,
  ]),
);
const canQuery = computed(
  () =>
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes(['asset:market-category:query']),
);
const checkedIds = ref<AssetMarketCategoryApi.Id[]>([]);
const pendingStatus = ref(new Set<string>());
const updatingStatus = ref(new Set<string>());
const deleting = ref(false);
const expanded = ref(true);
let querySequence = 0;
let activeQuery: number | undefined;
let lastStatusFilter: number | undefined;
let contextRevision = 0;
let inactive = false;
let displayedRows: ReturnType<typeof marketCategoryTree> = [];

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [DetailModal, detailModalApi] = useVbenModal({
  connectedComponent: Detail,
  destroyOnClose: true,
});

function refresh() {
  void gridApi.query();
}
function create() {
  if (
    inactive ||
    !canQuery.value ||
    !hasAccessByCodes(['asset:market-category:create'])
  )
    return;
  formModalApi.setData(undefined).open();
}
function append(row: AssetMarketCategoryApi.MarketCategory) {
  if (
    inactive ||
    !canQuery.value ||
    !hasAccessByCodes(['asset:market-category:create'])
  )
    return;
  formModalApi.setData({ parentId: row.id }).open();
}
function edit(row: AssetMarketCategoryApi.MarketCategory) {
  if (
    inactive ||
    !canQuery.value ||
    !hasAccessByCodes(['asset:market-category:update'])
  )
    return;
  formModalApi.setData({ id: row.id }).open();
}
function detail(row: AssetMarketCategoryApi.MarketCategory) {
  if (inactive || !canQuery.value || !parseResourceId(row.id)) return;
  detailModalApi.setData({ id: row.id }).open();
}
function toggleExpand() {
  expanded.value = !expanded.value;
  void gridApi.grid.setAllTreeExpand(expanded.value);
}
function selectRows({
  records,
}: {
  records: AssetMarketCategoryApi.MarketCategory[];
}) {
  checkedIds.value = records.map((row) => row.id);
}

/** 开关始终受后端结果驱动，失败时保留原状态。 */
async function changeStatus(row: AssetMarketCategoryApi.MarketCategory) {
  const key = String(row.id);
  if (
    inactive ||
    !canQuery.value ||
    !parseResourceId(row.id) ||
    pendingStatus.value.has(key) ||
    !hasAccessByCodes(['asset:market-category:update'])
  )
    return;
  const current = contextRevision;
  const queryAtStart = querySequence;
  const queryWasActive = activeQuery !== undefined;
  pendingStatus.value = new Set([...pendingStatus.value, key]);
  try {
    const nextStatus = row.status === 0 ? 1 : 0;
    await confirm(
      `确认要${nextStatus === 0 ? '启用' : '停用'}“${row.name}”吗？`,
    );
    if (current !== contextRevision) return;
    updatingStatus.value = new Set([...updatingStatus.value, key]);
    await updateMarketCategoryStatus(row.id, nextStatus);
    if (current !== contextRevision) return;
    row.status = nextStatus;
    message.success('状态更新成功');
    if (
      lastStatusFilter !== undefined ||
      queryWasActive ||
      activeQuery !== undefined ||
      querySequence !== queryAtStart
    ) {
      refresh();
    }
  } catch {
    // 请求层统一提示失败，受控开关仍显示服务端原状态。
  } finally {
    if (current === contextRevision) {
      const next = new Set(pendingStatus.value);
      next.delete(key);
      pendingStatus.value = next;
      const updating = new Set(updatingStatus.value);
      updating.delete(key);
      updatingStatus.value = updating;
    }
  }
}

/** 批量删除保存点击时的原始 ID，允许父子一起交由服务端原子校验。 */
async function remove(ids: AssetMarketCategoryApi.Id[], ask: boolean) {
  if (
    inactive ||
    !canQuery.value ||
    !hasAccessByCodes(['asset:market-category:delete']) ||
    deleting.value ||
    ids.length === 0 ||
    ids.some((id) => !parseResourceId(id))
  )
    return;
  if (ids.length > 200) {
    message.warning('一次最多删除200个类目');
    return;
  }
  const current = contextRevision;
  deleting.value = true;
  try {
    if (ask) {
      try {
        await confirm('确认删除选中的集市类目吗？');
      } catch {
        return;
      }
    }
    if (current !== contextRevision) return;
    await (ask ? deleteMarketCategoryList(ids) : deleteMarketCategory(ids[0]!));
    if (current !== contextRevision) return;
    checkedIds.value = [];
    await gridApi.grid.clearCheckboxRow();
    message.success('删除成功');
    refresh();
  } catch {
    // 删除失败由请求层提示；保留选中项供用户修正关联后重试。
  } finally {
    if (current === contextRevision) deleting.value = false;
  }
}

const [Grid, gridApi] = useVbenVxeGrid<AssetMarketCategoryApi.MarketCategory>({
  formOptions: {
    schema: useGridFormSchema(),
    commonConfig: { labelWidth: 100 },
    wrapperClass: 'grid-cols-1 md:grid-cols-2 xl:grid-cols-3',
  },
  gridOptions: {
    columns: useGridColumns(),
    height: 'auto',
    keepSource: true,
    pagerConfig: { enabled: false },
    rowConfig: { keyField: 'id', isHover: true },
    showOverflow: false,
    showHeaderOverflow: true,
    toolbarConfig: { refresh: true, search: true, custom: true },
    treeConfig: { childrenField: 'children', expandAll: true, reserve: true },
    proxyConfig: {
      ajax: {
        query: async (
          _params: unknown,
          values: AssetMarketCategoryApi.CategoryQuery,
        ) => {
          const current = ++querySequence;
          if (inactive || !canQuery.value) return [];
          activeQuery = current;
          const params: AssetMarketCategoryApi.CategoryQuery = {
            name: values.name || undefined,
            status: values.status ?? undefined,
          };
          try {
            const rows = await getMarketCategoryList(params);
            if (current !== querySequence) return displayedRows;
            displayedRows = marketCategoryTree(rows);
            lastStatusFilter = params.status;
            checkedIds.value = [];
            return displayedRows;
          } catch (error) {
            if (current !== querySequence) return displayedRows;
            throw error;
          } finally {
            if (activeQuery === current) activeQuery = undefined;
          }
        },
      },
    },
  },
  gridEvents: { checkboxAll: selectRows, checkboxChange: selectRows },
});

/** 会话、权限与组件生命周期变化同时作废旧列表和进行中的操作。 */
function invalidate() {
  ++querySequence;
  activeQuery = undefined;
  lastStatusFilter = undefined;
  ++contextRevision;
  checkedIds.value = [];
  pendingStatus.value = new Set();
  updatingStatus.value = new Set();
  deleting.value = false;
  displayedRows = [];
  void gridApi.grid?.loadData([]);
  void formModalApi.close();
  void detailModalApi.close();
}
watch(
  scope,
  () => {
    invalidate();
    if (!inactive) refresh();
  },
  { flush: 'sync' },
);
onDeactivated(() => {
  inactive = true;
  invalidate();
});
onActivated(() => {
  if (inactive) {
    inactive = false;
    refresh();
  }
});
onBeforeUnmount(invalidate);
</script>

<template>
  <Page auto-content-height data-testid="market-category-page">
    <FormModal @success="refresh" />
    <DetailModal />
    <Grid class="market-category-grid" table-title="集市类目列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: '新增',
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['asset:market-category:create'],
              onClick: create,
            },
            {
              label: '批量删除',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['asset:market-category:delete'],
              disabled: checkedIds.length === 0 || deleting,
              onClick: () => remove([...checkedIds], true),
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
      <template #description="{ row }">
        <TextEllipsisCell
          class="whitespace-normal break-words"
          :content="row.description || '—'"
          :line="2"
          center
        />
      </template>
      <template #status="{ row }">
        <Switch
          :checked="row.status === 0"
          checked-children="已启用"
          un-checked-children="已停用"
          :loading="updatingStatus.has(String(row.id))"
          :disabled="
            !canQuery || !hasAccessByCodes(['asset:market-category:update'])
          "
          :aria-label="`${row.name}状态`"
          @change="changeStatus(row)"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '修改',
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['asset:market-category:update'],
              onClick: () => edit(row),
            },
            {
              label: '新增',
              type: 'link',
              icon: ACTION_ICON.ADD,
              auth: ['asset:market-category:create'],
              onClick: () => append(row),
            },
            {
              label: '删除',
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['asset:market-category:delete'],
              disabled: deleting,
              popConfirm: {
                title: `确认删除“${row.name}”吗？`,
                confirm: () => remove([row.id], false),
              },
            },
            {
              label: '详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['asset:market-category:query'],
              onClick: () => detail(row),
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
