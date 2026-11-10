<script setup lang="ts">
import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';
import type { GovernanceMasterDomainApi } from '#/api/governance/masterdomain';

import { computed, onMounted, reactive, ref } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { formatDateTime } from '@vben/utils';

import { Button, Input, Tooltip, Tree } from 'antdv-next';

import { TableAction } from '#/adapter/vxe-table';
import {
  getMaintainModelDataCount,
  getMaintainModelPage,
} from '#/api/governance/masterdata';
import { getMasterDomainSimpleList } from '#/api/governance/masterdomain';
import SidebarCollapse from '#/components/sidebar-collapse/index.vue';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import TreeToolbar from '#/components/tree-toolbar/index.vue';

import { useTreeExpansion } from '../../common/use-tree-expansion';
import { useMasterScope } from '../../mastercommon/use-master-scope';
import { useMetadataGrid } from '../../metadata-common/use-metadata-grid';

const emit = defineEmits<{
  enter: [model: GovernanceMasterDataApi.Model];
}>();
const filters = reactive({
  createTime: undefined as [string, string] | undefined,
  name: '',
  tableName: '',
  status: undefined as number | undefined,
  domainId: undefined as GovernanceMasterDataApi.Id | undefined,
});
const applied = reactive({ ...filters });
const treeSearch = ref('');
const domains = ref<GovernanceMasterDomainApi.Domain[]>([]);
const treeFailed = ref(false);
const treeLoading = ref(false);
const counts = ref<Record<string, GovernanceMasterDataApi.ModelDataCount>>({});
const scope = useMasterScope(() => {
  domains.value = [];
  counts.value = {};
  treeSearch.value = '';
  filters.domainId = undefined;
  clearFilters();
  Object.assign(applied, filters);
  gridApi.formApi.setLatestSubmissionValues?.({});
  void gridApi.formApi.reset?.();
  void load(true);
  void loadDomains();
});
const treeData = computed(() => [
  {
    title: '实体模型类目',
    key: 'all',
    children: domains.value
      .filter((row) => row.name.includes(treeSearch.value.trim()))
      .map((row) => ({ title: row.name, key: String(row.id) })),
  },
]);
async function loadDomains() {
  const current = scope.begin('domains');
  treeFailed.value = false;
  treeLoading.value = true;
  try {
    const result = await getMasterDomainSimpleList();
    if (current()) domains.value = result;
  } catch {
    if (current()) treeFailed.value = true;
  } finally {
    if (current()) treeLoading.value = false;
  }
}
async function loadCounts(rows: GovernanceMasterDataApi.Model[]) {
  const current = scope.begin('counts');
  counts.value = {};
  let index = 0;
  // 数据量来自外部数据源；每页最多三个并行读取，未知结果保持未知。
  await Promise.all(
    Array.from({ length: Math.min(3, rows.length) }, async () => {
      while (current() && index < rows.length) {
        const row = rows[index++];
        if (row?.id === undefined) continue;
        try {
          const value = await getMaintainModelDataCount(row.id);
          if (current()) counts.value[String(row.id)] = value;
        } catch {
          if (current())
            counts.value[String(row.id)] = {
              available: false,
              message: '数据量读取失败',
            };
        }
      }
    }),
  );
}
const { Grid, gridApi, load } =
  useMetadataGrid<GovernanceMasterDataApi.Model>(
    [
      { type: 'checkbox', width: 45 },
      { field: 'id', title: '编号', width: 90, sortable: true },
      {
        field: 'tableCn',
        title: '实体模型信息',
        minWidth: 400,
        slots: { default: 'model' },
      },
      {
        field: 'domainName',
        title: '所属类目',
        minWidth: 180,
        slots: { default: 'domain' },
      },
      {
        field: 'databaseName',
        title: '数据源信息',
        minWidth: 260,
        slots: { default: 'source' },
      },
      {
        field: 'fieldCount',
        title: '统计信息',
        minWidth: 220,
        slots: { default: 'statistics' },
      },
      {
        field: 'lastMaintenanceTime',
        title: '最近维护时间',
        minWidth: 190,
        formatter: ({ cellValue }) =>
          cellValue === null || cellValue === undefined
            ? '-'
            : formatDateTime(cellValue),
      },
      {
        title: '操作',
        width: 265,
        fixed: 'right',
        slots: { default: 'actions' },
        showOverflow: false,
      },
    ],
    async (page) => {
      const current = scope.begin('page');
      const result = await getMaintainModelPage({
        ...page,
        ...applied,
        status: 1,
      });
      if (current()) void loadCounts(result.list);
      return result;
    },
    {
      schema: [
        {
          fieldName: 'name',
          label: '实体模型名称',
          component: 'Input',
          componentProps: {
            allowClear: true,
            placeholder: '请输入实体模型名称',
          },
        },
        {
          fieldName: 'tableName',
          label: '实体模型编码',
          component: 'Input',
          componentProps: {
            allowClear: true,
            placeholder: '请输入实体模型编码',
          },
        },
        {
          fieldName: 'status',
          label: '状态',
          component: 'Select',
          componentProps: {
            allowClear: true,
            placeholder: '请选择状态',
            options: [{ label: '已发布', value: 1 }],
          },
        },
        {
          fieldName: 'createTime',
          label: '创建时间',
          component: 'RangePicker',
          componentProps: {
            valueFormat: 'YYYY-MM-DD HH:mm:ss',
            allowClear: true,
            placeholder: ['开始日期', '结束日期'],
          },
        },
      ],
      handleSubmit: (values) => {
        Object.assign(
          filters,
          { name: '', tableName: '', status: undefined, createTime: undefined },
          values,
        );
        search();
      },
      handleReset: async () => {
        await gridApi.formApi.reset();
        reset();
      },
    },
    undefined,
    'fill',
    true,
    true,
  );
function search() {
  Object.assign(applied, filters);
  void load(true);
}
function clearFilters() {
  Object.assign(filters, {
    name: '',
    tableName: '',
    status: undefined,
    createTime: undefined,
  });
}
function reset() {
  clearFilters();
  search();
}
function selectDomain(keys: (number | string)[]) {
  filters.domainId = keys[0] && keys[0] !== 'all' ? String(keys[0]) : undefined;
  search();
}
onMounted(loadDomains);
const expandedKeys = ref<string[]>([]);
const sidebarCollapsed = ref(false);
const { allExpanded, toggleExpand } = useTreeExpansion(
  treeData,
  expandedKeys,
  treeSearch,
);
</script>

<template>
  <div class="qdata-master-layout">
    <aside v-show="!sidebarCollapsed" class="qdata-master-tree subtle-tree-scrollbar">
      <div class="flex items-center gap-2">
        <Input
          class="min-w-0 flex-1"
          v-model:value="treeSearch"
          placeholder="请输入实体模型类目名称"
          allow-clear
        >
          <template #prefix><IconifyIcon icon="lucide:search" /></template>
        </Input>
        <TreeToolbar
          :expanded="allExpanded"
          :loading="treeLoading"
          @toggle-expand="toggleExpand"
          @refresh="loadDomains"
        />
      </div>
      <template v-if="treeFailed"></template>
      <Tree
        v-model:expanded-keys="expandedKeys"
        v-else
        :tree-data="treeData"
        :selected-keys="[String(filters.domainId ?? 'all')]"
        block-node
        @select="selectDomain"
      >
        <template #titleRender="node">
          <span class="flex min-w-0 items-center gap-2">
            <IconifyIcon
              aria-hidden="true"
              class="size-3.5 shrink-0"
              icon="lucide:folder-closed"
            />
            <span class="min-w-0 truncate" :title="String(node.title)">
              {{ node.title }}
            </span>
          </span>
        </template>
      </Tree>
    </aside>
    <SidebarCollapse :collapsed="sidebarCollapsed" label="实体模型类目" @toggle="sidebarCollapsed = !sidebarCollapsed" />
    <main class="qdata-master-main">
      <div class="metadata-grid-shell">
        <Grid
          class="metadata-grid subtle-grid-scrollbar"
          table-title="主数据维护模型"
        >
          <template #toolbar-tools>
            <div class="metadata-toolbar">
              <div class="metadata-toolbar">
                <Tooltip title="参考功能尚未开放">
                  <span><Button type="primary" disabled><IconifyIcon icon="lucide:upload" />导入</Button></span>
                </Tooltip>
                <Tooltip title="参考功能尚未开放">
                  <span><Button disabled><IconifyIcon icon="lucide:search-check" />查重</Button></span>
                </Tooltip>
              </div>
            </div>
          </template>
          <template #model="{ row }">
            <div class="metadata-info">
              <div class="metadata-info__title">
                <button class="metadata-link" @click="emit('enter', row)">
                  {{ row.tableCn }} ({{ row.tableName }})
</button><span class="metadata-tag metadata-tag--success">已发布</span><span
                  class="metadata-tag"
                  :class="{ 'metadata-tag--success': row.maintenanceSupported }"
                  >{{ row.maintenanceSupported ? '可维护' : '只读' }}</span>
              </div>
              <TextEllipsisCell
                class="metadata-muted"
                :content="row.description || '-'"
                :line="2"
                center
              />
            </div>
          </template>
          <template #domain="{ row }">
            <span class="metadata-tag">{{ row.domainName || '-' }}</span>
          </template>
          <template #source="{ row }">
            <div class="metadata-info">
              <span>{{ row.databaseName || '-' }}</span><span v-if="row.databaseType" class="metadata-tag">{{
                row.databaseType
              }}</span>
            </div>
          </template>
          <template #statistics="{ row }">
            <div class="metadata-info">
              <span>字段数：{{ row.fieldCount ?? '-' }}个</span><span :title="counts[String(row.id)]?.message">数据量：{{
                  counts[String(row.id)]?.available
                    ? counts[String(row.id)]?.dataCount
                    : '-'
                }}条</span>
            </div>
          </template>
          <template #actions="{ row }">
            <TableAction
              :actions="[
                {
                  label: '进入维护',
                  type: 'link',
                  icon: 'lucide:log-in',
                  auth: ['governance:master-data:query'],
                  onClick: () => emit('enter', row),
                },
                {
                  label: '导入',
                  type: 'link',
                  icon: 'lucide:upload',
                  disabled: true,
                  tooltip: '参考功能尚未开放',
                },
                {
                  label: '查重',
                  type: 'link',
                  icon: 'lucide:search-check',
                  disabled: true,
                  tooltip: '参考功能尚未开放',
                },
              ]"
            />
          </template>
        </Grid>
      </div>
    </main>
  </div>
</template>
