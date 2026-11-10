<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import { Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { getMyWorkItems } from '#/api/dataforge/fill';

import { useMyGridColumns, useMyGridFormSchema } from '../data';
import { WORK_ITEM_STATUS_TEXT } from '../shared';

defineOptions({ name: 'DataforgeFillMy' });

const router = useRouter();

/** 可进入工作台编辑的状态：待填报/填报中/整改中；其余只读查看 */
const EDITABLE_STATUSES = new Set([0, 1, 5]);

function openWorkbench(row: DataforgeFillApi.WorkItem) {
  router.push({
    path: '/dataforge/fill/workbench',
    query: { workItemId: String(row.id) },
  });
}

function matchesKeyword(value: unknown, keyword: string) {
  if (!keyword) {
    return true;
  }
  return String(value ?? '')
    .toLowerCase()
    .includes(keyword.toLowerCase());
}

const [Grid] = useVbenVxeGrid({
  formOptions: {
    schema: useMyGridFormSchema(),
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    columns: useMyGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        // 后端 /fill-work-item/my 返回全量列表、不分页；搜索与分页在本地完成
        query: async ({ page }, formValues) => {
          const list = await getMyWorkItems();
          const taskName = String(formValues?.taskName ?? '').trim();
          const status = formValues?.status as number | undefined;
          const filtered = list.filter((item) => {
            if (!matchesKeyword(item.taskName, taskName)) {
              return false;
            }
            if (status === undefined || status === null) {
              return true;
            }
            return item.status === status;
          });
          const start = (page.currentPage - 1) * page.pageSize;
          return {
            list: filtered.slice(start, start + page.pageSize),
            total: filtered.length,
          };
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<DataforgeFillApi.WorkItem>,
});
</script>

<template>
  <Page auto-content-height>
    <Grid table-title="我的填报">
      <template #status="{ row }">
        <Tag :color="WORK_ITEM_STATUS_TEXT[row.status]?.color">
          {{ WORK_ITEM_STATUS_TEXT[row.status]?.text ?? row.status }}
        </Tag>
        <Tag v-if="row.overdue === 1" color="error">已逾期</Tag>
        <Tag v-if="row.submissionType === 2" color="warning">零上报</Tag>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: EDITABLE_STATUSES.has(row.status) ? '进入填报' : '查看',
              type: 'link',
              icon: EDITABLE_STATUSES.has(row.status)
                ? 'lucide:pen-line'
                : ACTION_ICON.VIEW,
              auth: ['dataforge:fill-work-item:my'],
              onClick: openWorkbench.bind(null, row),
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
