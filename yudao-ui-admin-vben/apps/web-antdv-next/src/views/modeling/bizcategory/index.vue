<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { ModelingBizCategoryApi } from '#/api/modeling/bizcategory';

import { onMounted, ref } from 'vue';

import { confirm, Page, useVbenDrawer, useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';

import { message, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  deleteBizCategory,
  getBizCategoryList,
  updateBizCategoryStatus,
} from '#/api/modeling/bizcategory';
import { getDataDomainSimpleList } from '#/api/modeling/datadomain';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { $t } from '#/locales';

import { useGridColumns, useGridFormSchema } from './data';
import Detail from './modules/detail.vue';
import Form from './modules/form.vue';

defineOptions({ name: 'ModelingBizCategory' });

const domainNameMap = ref<Record<string, string>>({});
onMounted(async () => {
  try {
    const list = await getDataDomainSimpleList();
    const map: Record<string, string> = {};
    for (const item of list || []) {
      if (item?.id !== null && item?.id !== undefined) {
        map[String(item.id)] = item.name || item.code || String(item.id);
      }
    }
    domainNameMap.value = map;
  } catch {
    domainNameMap.value = {};
  }
});

function resolveDataDomains(
  row: ModelingBizCategoryApi.BizCategory,
): NonNullable<ModelingBizCategoryApi.BizCategory['dataDomains']> {
  if (row.dataDomains?.length) {
    return row.dataDomains;
  }
  const ids = row.dataDomainIds || [];
  return ids.map((id) => ({
    id,
    name: domainNameMap.value[String(id)] || String(id),
  }));
}

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [DetailDrawer, detailDrawerApi] = useVbenDrawer({
  connectedComponent: Detail,
  destroyOnClose: true,
});

/** 切换树形展开/收缩状态 */
const isExpanded = ref(true);
function handleExpand() {
  isExpanded.value = !isExpanded.value;
  gridApi.grid.setAllTreeExpand(isExpanded.value);
}

function handleRefresh() {
  gridApi.query();
}

function handleCreate() {
  formModalApi.setData(null).open();
}

/** 新增下级 */
function handleAppend(row: ModelingBizCategoryApi.BizCategory) {
  formModalApi.setData({ parentId: row.id }).open();
}

function handleEdit(row: ModelingBizCategoryApi.BizCategory) {
  formModalApi.setData(row).open();
}

/** 详情（传入上级名称用于展示） */
function handleDetail(row: ModelingBizCategoryApi.BizCategory) {
  const rows: ModelingBizCategoryApi.BizCategory[] =
    gridApi.grid?.getFullData() ?? [];
  const parent = rows.find((item) => item.id === row.parentId);
  detailDrawerApi
    .setData({ id: row.id, parentName: parent?.name ?? '' })
    .open();
}

async function handleDelete(row: ModelingBizCategoryApi.BizCategory) {
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.name || row.id]),
    duration: 0,
  });
  try {
    await deleteBizCategory(row.id!);
    message.success($t('ui.actionMessage.deleteSuccess', [row.name || row.id]));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

/** 更新状态 */
async function handleStatusChange(
  newStatus: number,
  row: ModelingBizCategoryApi.BizCategory,
): Promise<boolean | undefined> {
  const text = newStatus === CommonStatusEnum.ENABLE ? '启用' : '停用';
  try {
    await confirm(`确认要${text}"${row.name}"吗?`);
  } catch {
    return false;
  }
  await updateBizCategoryStatus(row.id!, newStatus);
  message.success(`${text}成功`);
  return true;
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useGridFormSchema(),
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    columns: useGridColumns(handleStatusChange),
    height: 'auto',
    pagerConfig: {
      enabled: false,
    },
    proxyConfig: {
      ajax: {
        query: async (_, formValues) => {
          return await getBizCategoryList(formValues);
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
      reserve: true,
    },
  } as VxeTableGridOptions<ModelingBizCategoryApi.BizCategory>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <DetailDrawer />
    <Grid class="biz-category-grid" table-title="业务分类列表">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['modeling:biz-category:create'],
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
      <template #description="{ row }">
        <TextEllipsisCell
          class="block whitespace-pre-wrap break-words"
          :content="row.description || '-'"
          :line="2"
          center
        />
      </template>
      <template #remark="{ row }">
        <TextEllipsisCell
          class="block whitespace-pre-wrap break-words"
          :content="row.remark || '-'"
          :line="2"
          center
        />
      </template>
      <template #dataDomains="{ row }">
        <div v-if="resolveDataDomains(row).length" class="flex flex-wrap gap-1">
          <Tag
            v-for="item in resolveDataDomains(row).slice(0, 2)"
            :key="String(item.id)"
            color="blue"
          >
            {{ item.name || item.code || item.id }}
          </Tag>
          <Tag v-if="resolveDataDomains(row).length > 2" color="default">
            +{{ resolveDataDomains(row).length - 2 }}
          </Tag>
        </div>
        <span v-else>-</span>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: $t('common.edit'),
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['modeling:biz-category:update'],
              onClick: handleEdit.bind(null, row),
            },
            {
              label: '新增',
              type: 'link',
              icon: ACTION_ICON.ADD,
              auth: ['modeling:biz-category:create'],
              onClick: handleAppend.bind(null, row),
            },
            {
              label: '详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['modeling:biz-category:query'],
              onClick: handleDetail.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['modeling:biz-category:delete'],
              popConfirm: {
                title: $t('ui.actionMessage.deleteConfirm', [
                  row.name || row.id,
                ]),
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>

<style scoped>
.biz-category-grid :deep(.vxe-body--column) {
  font-size: 14px;
}
</style>
