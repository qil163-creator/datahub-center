<script setup lang="ts">
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceMetaFileApi as Api } from '#/api/governance/metafile';

import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { confirm, Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import { Alert, Button, message } from 'antdv-next';

import { TableAction } from '#/adapter/vxe-table';
import { deleteMetaFileList, getMetaFilePage } from '#/api/governance/metafile';
import SidebarCollapse from '#/components/sidebar-collapse/index.vue';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { useObjectRequest } from '#/hooks/use-object-request';

import { metadataSize, metadataTime } from '../metadata-common/helpers';
import { metadataIcon } from '../metadata-common/icons';
import MetadataIcon from '../metadata-common/metadata-icon.vue';
import { useMetadataGrid } from '../metadata-common/use-metadata-grid';
import FileTree from './modules/file-tree.vue';
import { fileTreeQuery } from './tree-query';

import '../metadata-common/metadata.css';

defineOptions({ name: 'GovernanceMetaFile' });
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const filters = reactive({
  name: '',
  fileType: undefined as string | undefined,
});
const selectedNode = ref<Api.TreeNode>();
const searchVisible = ref(true);
const deleting = ref(false);
const tree = ref<InstanceType<typeof FileTree>>();
const operations = useObjectRequest(
  () => selectedNode.value?.id ?? '',
  () => {
    deleting.value = false;
  },
);
const columns: VxeTableGridOptions<Api.MetaFile>['columns'] = [
  { type: 'checkbox', width: 40 },
  { field: 'id', title: '编号', width: 70 },
  {
    field: 'name',
    title: '非结构化数据信息',
    minWidth: 350,
    align: 'left',
    showOverflow: false,
    slots: { default: 'info' },
  },
  {
    field: 'path',
    title: '物理存储信息',
    minWidth: 230,
    align: 'left',
    slots: { default: 'storage' },
  },
  {
    title: '来源信息',
    minWidth: 190,
    align: 'left',
    slots: { default: 'source' },
  },
  {
    field: 'sourceModifiedTime',
    title: '修改时间',
    width: 160,
    formatter: ({ cellValue }) => metadataTime(cellValue, 'YYYY-MM-DD HH:mm'),
  },
  {
    title: '技术责任人',
    width: 125,
    align: 'left',
    slots: { default: 'tech' },
  },
  {
    title: '业务责任人',
    width: 125,
    align: 'left',
    slots: { default: 'business' },
  },
  {
    field: 'updaterName',
    title: '更新人',
    width: 100,
    formatter: ({ cellValue }) => cellValue || '-',
  },
  {
    field: 'creatorName',
    title: '创建人',
    width: 125,
    formatter: ({ cellValue }) => cellValue || '-',
  },
  {
    field: 'createTime',
    title: '创建时间',
    width: 165,
    formatter: ({ cellValue }) => metadataTime(cellValue, 'YYYY-MM-DD HH:mm'),
  },
  {
    title: '操作',
    width: 205,
    fixed: 'right',
    showOverflow: false,
    slots: { default: 'actions' },
  },
];
const { Grid, gridApi, load, selected, error } = useMetadataGrid<Api.MetaFile>(
  columns,
  (page) =>
    getMetaFilePage({
      ...page,
      ...filters,
      ...fileTreeQuery(selectedNode.value),
    }),
  {
    schema: [
      {
        fieldName: 'name',
        label: '文件名',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入文件名' },
      },
      {
        fieldName: 'fileType',
        label: '文件类型',
        component: 'Select',
        componentProps: {
          allowClear: true,
          showSearch: true,
          placeholder: '请选择文件类型',
          options: [
            'csv',
            'json',
            'sql',
            'md',
            'txt',
            'pdf',
            'doc',
            'docx',
            'xls',
            'xlsx',
            'xml',
            'png',
            'jpg',
            'zip',
          ].map((value) => ({ label: value.toUpperCase(), value })),
        },
      },
    ],
    handleSubmit: (values): Promise<void> => {
      Object.assign(filters, { name: '', fileType: undefined }, values);
      return load(true);
    },
    handleReset: async () => {
      await gridApi.formApi.reset();
      reset();
    },
  },
  () => {
    clearFilters();
    selectedNode.value = undefined;
  },
);
function select(node?: Api.TreeNode) {
  selectedNode.value = node;
  void load(true);
}
function clearFilters() {
  filters.name = '';
  filters.fileType = undefined;
}
function reset() {
  clearFilters();
  void load(true);
}
function detail(row: Api.MetaFile) {
  void router.push({
    name: 'GovernanceMetaFileDetail',
    params: { id: String(row.id) },
  });
}
function edit(row: Api.MetaFile) {
  void router.push({
    name: 'GovernanceMetaFileEdit',
    params: { id: String(row.id) },
  });
}
async function remove(rows = selected.value) {
  if (rows.length === 0 || deleting.value) return;
  await confirm(
    '确认删除所选文件元数据？源端文件将保留，后续采集可以重新发现。',
  );
  const current = operations.begin();
  deleting.value = true;
  try {
    await deleteMetaFileList(rows.map((row) => row.id));
    if (current()) {
      message.success('元数据已删除');
      tree.value?.reload();
      await load();
    }
  } finally {
    if (current()) deleting.value = false;
  }
}
const sidebarCollapsed = ref(false);
</script>
<template>
  <Page auto-content-height content-class="metadata-page">
    <div class="metadata-layout">
      <FileTree v-show="!sidebarCollapsed" ref="tree" @select="select" />
      <SidebarCollapse :collapsed="sidebarCollapsed" label="目录" @toggle="sidebarCollapsed = !sidebarCollapsed" />
      <div class="metadata-layout__main">
        <Alert v-if="error" type="error" message="文件元数据读取失败">
          <template #action>
            <Button size="small" @click="load()">
              <template #icon>
                <IconifyIcon :icon="metadataIcon('refresh')" />
              </template>
              重试
            </Button>
          </template>
        </Alert>
        <div class="metadata-grid-shell">
          <Grid
            class="metadata-grid"
            table-title="文件元数据列表"
            :show-search-form="searchVisible"
          >
            <template #toolbar-tools>
              <div class="metadata-toolbar">
                <div class="metadata-toolbar">
                  <Button
                    v-if="hasAccessByCodes(['governance:meta-file:delete'])"
                    danger
                    :disabled="!selected.length"
                    :loading="deleting"
                    @click="remove()"
                  >
                    <template #icon>
                      <IconifyIcon :icon="metadataIcon('delete')" />
                    </template>
                    删除
                  </Button>
                </div>

                <div class="metadata-tools">
                  <Button
                    title="显示/隐藏查询"
                    @click="searchVisible = !searchVisible"
                  >
                    <IconifyIcon :icon="metadataIcon('search')" />
</Button><Button title="刷新" @click="load()">
                    <IconifyIcon :icon="metadataIcon('refresh')" />
                  </Button>
                </div>
              </div>
            </template>
            <template #info="{ row }">
              <div class="metadata-info">
                <div class="metadata-info__title">
                  <button class="metadata-link" @click="detail(row)">
                    {{ row.name }}
</button><span class="metadata-tag">{{
                    row.fileType?.toUpperCase() || '-'
                  }}</span>
                </div>
                <TextEllipsisCell
                  class="metadata-muted"
                  :content="row.description || '-'"
                  :line="2"
                  center
                />
              </div>
            </template>
            <template #storage="{ row }">
              <div class="metadata-info">
                <span class="metadata-ellipsis" :title="row.path">{{
                  row.path || '-'
                }}</span><span>{{ metadataSize(row.size) }}</span>
              </div>
            </template>
            <template #source="{ row }">
              <div class="metadata-info">
                <span>{{ row.sourceSystemName || '-' }}</span><span class="metadata-tag metadata-tag--success"><MetadataIcon name="hdfs" :size="12" />{{
                    row.datasourceName || '-'
                  }}</span>
              </div>
            </template>
            <template #tech="{ row }">
              <div class="metadata-info">
                <span>{{ row.techLeaderName || '-' }}</span><span>{{ row.techLeaderPhone || '-' }}</span>
              </div>
            </template>
            <template #business="{ row }">
              <div class="metadata-info">
                <span>{{ row.businessLeaderName || row.ownerName || '-' }}</span><span>{{
                  row.businessLeaderPhone || row.ownerPhone || '-'
                }}</span>
              </div>
            </template>
            <template #actions="{ row }">
              <TableAction
                :actions="[
                  {
                    label: '详情',
                    type: 'link',
                    icon: metadataIcon('detail'),
                    auth: ['governance:meta-file:query'],
                    onClick: () => detail(row),
                  },
                  {
                    label: '修改',
                    type: 'link',
                    icon: metadataIcon('edit'),
                    auth: ['governance:meta-file:update'],
                    onClick: () => edit(row),
                  },
                  {
                    label: '删除',
                    type: 'link',
                    danger: true,
                    icon: metadataIcon('delete'),
                    auth: ['governance:meta-file:delete'],
                    onClick: () => remove([row]),
                  },
                ]"
              />
            </template>
          </Grid>
        </div>
      </div>
    </div>
  </Page>
</template>
