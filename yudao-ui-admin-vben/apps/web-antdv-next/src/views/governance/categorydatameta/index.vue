<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { FoundationCategoryDatametaApi } from '#/api/governance/categorydatameta';

import { ref } from 'vue';

import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';

import { message } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteCategoryDatameta,
  getCategoryDatameta,
  getCategoryDatametaList,
  updateCategoryDatameta,
} from '#/api/governance/categorydatameta';
import { $t } from '#/locales';
import TreeCategoryDetail from '#/views/governance/common/tree-category-detail.vue';

import { useGridColumns, useGridFormSchema } from './data';
import Form from './modules/form.vue';

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

/** 创建标准数据元类目管理 */
function handleCreate() {
  formModalApi.setData(null).open();
}

/** 添加下级标准数据元类目管理 */
function handleAppend(row: FoundationCategoryDatametaApi.CategoryDatameta) {
  formModalApi.setData({ parentId: row.id }).open();
}

/** 编辑标准数据元类目管理 */
function handleEdit(row: FoundationCategoryDatametaApi.CategoryDatameta) {
  formModalApi.setData(row).open();
}

function handleDetail(row: FoundationCategoryDatametaApi.CategoryDatameta) {
  const rows =
    (gridApi.grid?.getFullData?.() as FoundationCategoryDatametaApi.CategoryDatameta[]) ??
    [];
  const parent = rows.find((item) => String(item.id) === String(row.parentId));
  detailModalApi
    .setData({
      id: row.id,
      loader: getCategoryDatameta,
      parentName: parent?.categoryName ?? '',
      title: '标准数据元类目',
    })
    .open();
}

/** 删除标准数据元类目管理 */
async function handleDelete(
  row: FoundationCategoryDatametaApi.CategoryDatameta,
) {
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.id]),
    duration: 0,
  });
  try {
    await deleteCategoryDatameta(row.id!);
    message.success($t('ui.actionMessage.deleteSuccess', [row.id]));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

/** 更新标准数据元类目管理状态 */
async function handleStatusChange(
  newStatus: number,
  row: FoundationCategoryDatametaApi.CategoryDatameta,
): Promise<boolean | undefined> {
  const text = newStatus === CommonStatusEnum.ENABLE ? '启用' : '停用';
  try {
    await confirm(`确认要${text}"${row.categoryName}"吗?`);
  } catch {
    return false;
  }
  await updateCategoryDatameta({
    id: row.id,
    parentId: row.parentId ?? 0,
    categoryName: row.categoryName,
    sort: row.sort,
    status: newStatus,
    remark: row.remark,
  });
  message.success(`${text}成功`);
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
          const list = await getCategoryDatametaList(formValues);
          return list.map((item) => ({
            ...item,
            parentId: Number(item.parentId ?? 0),
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
  } as VxeTableGridOptions<FoundationCategoryDatametaApi.CategoryDatameta>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <DetailModal />
    <Grid table-title="标准数据元类目列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['governance:category-datameta:create'],
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
              label: $t('common.edit'),
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['governance:category-datameta:update'],
              onClick: handleEdit.bind(null, row),
            },
            {
              label: '新增',
              type: 'link',
              icon: ACTION_ICON.ADD,
              auth: ['governance:category-datameta:create'],
              onClick: handleAppend.bind(null, row),
            },
            {
              label: '详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['governance:category-datameta:query'],
              onClick: handleDetail.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['governance:category-datameta:delete'],
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
