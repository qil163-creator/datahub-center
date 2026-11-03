<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceStandardCodeItemApi } from '#/api/governance/standardcodeitem';

import { computed, watch } from 'vue';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import { getStandardCodeItemPage } from '#/api/governance/standardcodeitem';

import { useRelatedItemGridColumns } from '../data';

const props = defineProps<{
  codeSetName?: string;
  dictType?: string;
}>();

const emit = defineEmits<{
  relate: [];
}>();

const tableTitle = computed(() =>
  props.codeSetName ? `当前码表：${props.codeSetName}` : '码值',
);

function handleRefresh() {
  gridApi.query();
}

const [Grid, gridApi] = useVbenVxeGrid({
  gridOptions: {
    columns: useRelatedItemGridColumns(),
    height: '100%',
    keepSource: true,
    pagerConfig: {
      enabled: true,
    },
    proxyConfig: {
      ajax: {
        query: async ({ page }) => {
          if (!props.dictType) {
            return { list: [], total: 0 };
          }
          return await getStandardCodeItemPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            dictType: props.dictType,
          });
        },
      },
    },
    rowConfig: {
      keyField: 'id',
      isHover: true,
    },
    toolbarConfig: {
      refresh: true,
      search: false,
    },
  } as VxeTableGridOptions<GovernanceStandardCodeItemApi.StandardCodeItem>,
});

watch(
  () => props.dictType,
  () => {
    if (props.dictType) {
      handleRefresh();
    }
  },
);
</script>

<template>
  <div class="h-full min-h-0">
    <Grid>
      <template #table-title>
        <div class="text-base font-medium">
          {{ tableTitle }}
        </div>
      </template>
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: '关联',
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['governance:standard-element:update'],
              onClick: () => emit('relate'),
            },
          ]"
        />
      </template>
    </Grid>
  </div>
</template>
