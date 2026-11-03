<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { FoundationCategoryDataQualityApi } from '#/api/governance/categorydataquality';

import { ref } from 'vue';

import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';

import { message } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteCategoryDataQuality,
  getCategoryDataQuality,
  getCategoryDataQualityList,
  updateCategoryDataQuality,
} from '#/api/governance/categorydataquality';
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

/** 创建数据质量类目管理 */
function handleCreate() {
  formModalApi.setData(null).open();
}

/** 添加下级数据质量类目管理 */
function handleAppend(
  row: FoundationCategoryDataQualityApi.CategoryDataQuality,
) {
  formModalApi.setData({ parentId: row.id }).open();
}

/** 编辑数据质量类目管理 */
function handleEdit(row: FoundationCategoryDataQualityApi.CategoryDataQuality) {
  formModalApi.setData(row).open();
}

function handleDetail(
  row: FoundationCategoryDataQualityApi.CategoryDataQuality,
) {
  const rows =
    (gridApi.grid?.getFullData?.() as FoundationCategoryDataQualityApi.CategoryDataQuality[]) ??
    [];
  const parent = rows.find((item) => String(item.id) === String(row.parentId));
  detailModalApi
    .setData({
      id: row.id,
      loader: getCategoryDataQuality,
      parentName: parent?.categoryName ?? '',
      title: '数据质量类目',
    })
    .open();
}

/** 删除数据质量类目管理 */
async function handleDelete(
  row: FoundationCategoryDataQualityApi.CategoryDataQuality,
) {
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.id]),
    duration: 0,
  });
  try {
    await deleteCategoryDataQuality(row.id!);
    message.success($t('ui.actionMessage.deleteSuccess', [row.id]));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

/** 更新数据质量类目管理状态 */
async function handleStatusChange(
  newStatus: number,
  row: FoundationCategoryDataQualityApi.CategoryDataQuality,
): Promise<boolean | undefined> {
  const text = newStatus === CommonStatusEnum.ENABLE ? '启用' : '停用';
  try {
    await confirm(`确认要${text}"${row.categoryName}"吗?`);
  } catch {
    return false;
  }
  await updateCategoryDataQuality({
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
          const list = await getCategoryDataQualityList(formValues);
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
  } as VxeTableGridOptions<FoundationCategoryDataQualityApi.CategoryDataQuality>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <DetailModal />
    <Grid table-title="数据质量类目列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['governance:category-data-quality:create'],
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
              auth: ['governance:category-data-quality:create'],
              onClick: handleAppend.bind(null, row),
            },
            {
              label: $t('common.edit'),
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['governance:category-data-quality:update'],
              onClick: handleEdit.bind(null, row),
            },
          ]"
          :drop-down-actions="[
            {
              label: $t('common.detail'),
              icon: ACTION_ICON.VIEW,
              type: 'link',
              auth: ['governance:category-data-quality:query'],
              onClick: handleDetail.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['governance:category-data-quality:delete'],
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
