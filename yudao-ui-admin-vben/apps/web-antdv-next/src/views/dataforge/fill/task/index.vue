<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { ref } from 'vue';

import { Page } from '@vben/common-ui';

import { Progress, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { getTaskPage } from '#/api/dataforge/fill';

import { useTaskGridColumns, useTaskGridFormSchema } from '../data';
import { TASK_STATUS_TEXT } from '../shared';
import TaskDetail from './modules/task-detail.vue';

defineOptions({ name: 'DataforgeFillTask' });

const detailRef = ref<InstanceType<typeof TaskDetail>>();

function percent(task: DataforgeFillApi.Task) {
  return task.totalOrgCount > 0
    ? Math.round((task.mergedOrgCount / task.totalOrgCount) * 100)
    : 0;
}

const [Grid] = useVbenVxeGrid({
  formOptions: {
    schema: useTaskGridFormSchema(),
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    columns: useTaskGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getTaskPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<DataforgeFillApi.Task>,
});
</script>

<template>
  <Page auto-content-height>
    <Grid table-title="采集任务">
      <template #status="{ row }">
        <Tag :color="TASK_STATUS_TEXT[row.status]?.color">
          {{ TASK_STATUS_TEXT[row.status]?.text ?? row.status }}
        </Tag>
      </template>
      <template #progress="{ row }">
        <div class="flex items-center justify-center gap-2">
          <Progress
            :percent="percent(row)"
            :show-info="false"
            class="w-24"
            size="small"
          />
          <span class="text-xs">
            {{ row.mergedOrgCount }}/{{ row.totalOrgCount }}
          </span>
        </div>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '进度详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              onClick: () => detailRef?.open(row),
            },
          ]"
        />
      </template>
    </Grid>

    <TaskDetail ref="detailRef" />
  </Page>
</template>
