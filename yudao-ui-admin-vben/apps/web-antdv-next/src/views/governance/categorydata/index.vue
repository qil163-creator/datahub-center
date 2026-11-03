<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { FoundationCategoryDataApi } from '#/api/governance/categorydata';

import { ref } from 'vue';

import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';

import { message } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteCategoryData,
  getCategoryData,
  getCategoryDataList,
  updateCategoryData,
} from '#/api/governance/categorydata';
import { $t } from '#/locales';
import { resourceIdKey } from '#/utils/resource-id';
import { validCategoryRevision } from '#/views/governance/category/reference-data';
import { useCategorySession } from '#/views/governance/category/use-session';
import TreeCategoryDetail from '#/views/governance/common/tree-category-detail.vue';

import { useGridColumns, useGridFormSchema } from './data';
import Form from './modules/form.vue';
const { identity, can } = useCategorySession();

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [DetailModal, detailModalApi] = useVbenModal({
  connectedComponent: TreeCategoryDetail,
  destroyOnClose: true,
});

/** 切换树形展开/收缩状态 */
const isExpanded = ref(true);
function handleExpand() {
  isExpanded.value = !isExpanded.value;
  gridApi.grid.setAllTreeExpand(isExpanded.value);
}

/** 刷新表格 */
function handleRefresh() {
  gridApi.query();
}

/** 创建数据分类 类目管理 */
function handleCreate() {
  formModalApi.setData(null).open();
}

/** 添加下级数据分类 类目管理 */
function handleAppend(row: FoundationCategoryDataApi.CategoryData) {
  formModalApi.setData({ parentId: row.id }).open();
}

/** 编辑数据分类 类目管理 */
function handleEdit(row: FoundationCategoryDataApi.CategoryData) {
  formModalApi.setData(row).open();
}

function handleDetail(row: FoundationCategoryDataApi.CategoryData) {
  const rows =
    (gridApi.grid?.getFullData?.() as FoundationCategoryDataApi.CategoryData[]) ??
    [];
  const parent = rows.find((item) => String(item.id) === String(row.parentId));
  detailModalApi
    .setData({
      id: row.id,
      loader: getCategoryData,
      parentName: parent?.categoryName ?? '',
      title: '数据分类类目',
    })
    .open();
}

/** 删除数据分类 类目管理 */
async function handleDelete(row: FoundationCategoryDataApi.CategoryData) {
  if (!can('delete', 'category-data') || !validCategoryRevision(row.revision))
    return;
  const subject = identity.value;
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.id]),
    duration: 0,
  });
  try {
    await deleteCategoryData(row.id, row.revision);
    if (subject !== identity.value) return;
    message.success($t('ui.actionMessage.deleteSuccess', [row.id]));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

/** 更新数据分类类目管理状态 */
async function handleStatusChange(
  newStatus: number,
  row: FoundationCategoryDataApi.CategoryData,
): Promise<boolean | undefined> {
  const text = newStatus === CommonStatusEnum.ENABLE ? '启用' : '停用';
  const subject = identity.value;
  try {
    await confirm(`确认要${text}"${row.categoryName}"吗?`);
  } catch {
    return false;
  }
  if (subject !== identity.value || !can('update', 'category-data'))
    return false;
  const fresh = await getCategoryData(row.id);
  if (
    subject !== identity.value ||
    resourceIdKey(fresh.id) !== resourceIdKey(row.id) ||
    !validCategoryRevision(fresh.revision)
  )
    return false;
  await updateCategoryData({
    id: fresh.id,
    revision: fresh.revision,
    parentId: fresh.parentId ?? '0',
    categoryName: fresh.categoryName,
    sort: fresh.sort,
    status: newStatus,
    remark: fresh.remark,
  });
  if (subject !== identity.value) return false;
  message.success(`${text}成功`);
  handleRefresh();
  return true;
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useGridFormSchema(),
  },
  gridOptions: {
    showOverflow: false,
    columns: useGridColumns(handleStatusChange),
    height: 'auto',
    pagerConfig: {
      enabled: false,
    },
    proxyConfig: {
      ajax: {
        query: async (_, formValues) => {
          const list = await getCategoryDataList(formValues);
          return list.map((item) => ({
            ...item,
            id: resourceIdKey(item.id),
            parentId: resourceIdKey(item.parentId) || '0',
          }));
        },
      },
    },
    rowConfig: {
      keyField: 'id',
      isHover: true,
    },
    toolbarConfig: {
      refresh: true,
      search: true,
    },
    treeConfig: {
      parentField: 'parentId',
      rowField: 'id',
      transform: true,
      expandAll: true,
      indent: 12,
      reserve: true,
    },
  } as VxeTableGridOptions<FoundationCategoryDataApi.CategoryData>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <DetailModal />
    <Grid table-title="数据分类类目列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['governance:category-data:create'],
              onClick: handleCreate,
            },
            {
              label: isExpanded ? '收缩' : '展开',
              icon: isExpanded
                ? 'lucide:fold-vertical'
                : 'lucide:unfold-vertical',
              type: 'primary',
              onClick: handleExpand,
            },
          ]"
        />
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '新增下级',
              type: 'link',
              icon: ACTION_ICON.ADD,
              auth: ['governance:category-data:create'],
              onClick: handleAppend.bind(null, row),
            },
            {
              label: $t('common.edit'),
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['governance:category-data:update'],
              onClick: handleEdit.bind(null, row),
            },
          ]"
          :drop-down-actions="[
            {
              label: $t('common.detail'),
              icon: ACTION_ICON.VIEW,
              type: 'link',
              auth: ['governance:category-data:query'],
              onClick: handleDetail.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['governance:category-data:delete'],
              popConfirm: {
                title: $t('ui.actionMessage.deleteConfirm', [row.id]),
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
