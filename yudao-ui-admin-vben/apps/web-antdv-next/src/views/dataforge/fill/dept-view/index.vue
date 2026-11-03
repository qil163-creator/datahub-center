<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import { Space, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import { getDeptWorkItems } from '#/api/dataforge/fill';

import { useDeptViewGridColumns, useDeptViewGridFormSchema } from '../data';
import { WORK_ITEM_STATUS_TEXT } from '../shared';

defineOptions({ name: 'DataforgeFillDeptView' });

const router = useRouter();

function viewData(row: DataforgeFillApi.WorkItem) {
  router.push({
    path: '/dataforge/fill/workbench',
    query: { workItemId: row.id },
  });
}

const [Grid] = useVbenVxeGrid({
  formOptions: {
    schema: useDeptViewGridFormSchema(),
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    columns: useDeptViewGridColumns(),
    height: 'auto',
    keepSource: true,
    pagerConfig: { enabled: false },
    proxyConfig: {
      ajax: {
        query: async (_params, formValues) => {
          const list = await getDeptWorkItems(formValues?.deptId);
          return { list, total: list.length };
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
    <Grid table-title="部门填报查看">
      <template #toolbar-tools>
        <span class="text-muted-foreground mr-2 text-xs">
          部门负责人只读查看本部门（含下级）填报数据与进度，不能编辑或提交
        </span>
      </template>
      <template #status="{ row }">
        <Tag :color="WORK_ITEM_STATUS_TEXT[row.status]?.color">
          {{ WORK_ITEM_STATUS_TEXT[row.status]?.text ?? row.status }}
        </Tag>
        <Tag v-if="row.overdue === 1" color="error">逾期</Tag>
      </template>
      <template #assignees="{ row }">
        <Space :size="4" wrap>
          <Tag v-for="assignee in row.assignees ?? []" :key="assignee.userId">
            {{ assignee.userName ?? assignee.userId }}
          </Tag>
        </Space>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '查看数据',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              onClick: viewData.bind(null, row),
            },
          ]"
        />
      </template>
    </Grid>
  </Page>
</template>
