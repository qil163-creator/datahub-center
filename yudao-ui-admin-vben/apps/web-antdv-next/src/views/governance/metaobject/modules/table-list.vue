<script setup lang="ts">
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceMetaObjectApi as Api } from '#/api/governance/metaobject';

import { computed, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import { Alert, Button, message, Modal, Switch } from 'antdv-next';

import { TableAction } from '#/adapter/vxe-table';
import {
  cancelFinalizeMetaTable,
  confirmDeleteMetaDb,
  deleteMetaTable,
  getFinalizedMetaTablePage,
  getMetaTablePage,
  publishMetaDb,
  publishMetaTable,
  unpublishMetaTable,
} from '#/api/governance/metaobject';
import SidebarCollapse from '#/components/sidebar-collapse/index.vue';
import { useObjectDetailQuery } from '#/hooks/use-object-detail-query';

import MetaObjectTree from '../../components/meta-object-tree.vue';
import { metadataTime } from '../../metadata-common/helpers';
import {
  metadataConnectionIcon,
  metadataIcon,
} from '../../metadata-common/icons';
import MetadataIcon from '../../metadata-common/metadata-icon.vue';
import { useMetadataGrid } from '../../metadata-common/use-metadata-grid';
import { displayComment } from '../data';
import ImportForm from './import-form.vue';
import OwnerForm from './owner-form.vue';
import RegisterForm from './register-form.vue';

import '../../metadata-common/metadata.css';

const props = defineProps<{ finalized?: boolean }>();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const tree = ref<InstanceType<typeof MetaObjectTree>>();
const node = ref<Api.TreeNode>();
const dbOptions = ref<{ label: string; value: Api.Id }[]>([]);
const filter = reactive<{ comment: string; dbId?: Api.Id; tableName: string }>({
  tableName: '',
  comment: '',
});
const applied = ref<Api.TablePageReq>({ pageNo: 1, pageSize: 10 });
const filterVisible = ref(true);
const busy = ref(false);
const canPublish = computed(() =>
  hasAccessByCodes(['governance:meta-table:publish']),
);
const columns: VxeTableGridOptions<Api.MetaTable>['columns'] = [
  { type: 'checkbox', width: 40 },
  { field: 'id', title: '编号', width: 72 },
  {
    field: 'tableName',
    title: '表信息',
    minWidth: 270,
    slots: { default: 'tableInfo' },
  },
  {
    field: 'sourceComment',
    title: '表注释',
    minWidth: 140,
    formatter: ({ row }) => displayComment(row),
  },
  {
    field: 'sourceSystemName',
    title: '采集来源',
    minWidth: 170,
    slots: { default: 'source' },
  },
  ...(props.finalized
    ? []
    : [
        {
          field: 'publishStatus',
          title: '状态',
          width: 75,
          slots: { default: 'status' },
        },
        {
          field: 'techLeaderName',
          title: '技术责任人',
          width: 120,
          slots: { default: 'techOwner' },
        },
      ]),
  {
    field: 'businessLeaderName',
    title: props.finalized ? '责任人' : '业务责任人',
    width: 120,
    slots: { default: 'owner' },
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
    width: 120,
    slots: { default: 'creator' },
  },
  {
    field: 'createTime',
    title: '创建时间',
    width: 150,
    formatter: ({ cellValue }) => metadataTime(cellValue, 'YYYY-MM-DD HH:mm'),
  },
  {
    field: 'actions',
    title: '操作',
    width: props.finalized ? 280 : 240,
    fixed: 'right',
    showOverflow: false,
    slots: { default: 'actions' },
  },
];
const { Grid, gridApi, load, selected, error } = useMetadataGrid<Api.MetaTable>(
  columns,
  (page) =>
    (props.finalized ? getFinalizedMetaTablePage : getMetaTablePage)({
      ...applied.value,
      ...page,
    }),
  {
    schema: [
      {
        fieldName: 'tableName',
        label: '表名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入表名称' },
      },
      {
        fieldName: 'comment',
        label: '表注释',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入表注释' },
      },
      ...(props.finalized
        ? []
        : [
            {
              fieldName: 'dbId',
              label: '所属库名',
              component: 'Select' as const,
              componentProps: () => ({
                options: dbOptions.value,
                disabled: node.value?.type === 'db',
                allowClear: true,
                showSearch: true,
                optionFilterProp: 'label',
                placeholder: '请选择所属库名',
              }),
            },
          ]),
    ],
    handleSubmit: (values) => {
      filter.tableName = values.tableName ?? '';
      filter.comment = values.comment ?? '';
      filter.dbId = values.dbId;
      query();
    },
    handleReset: async () => {
      await gridApi.formApi.reset();
      reset();
    },
  },
  () => {
    clearFilters();
    node.value = undefined;
    dbOptions.value = [];
    applied.value = { pageNo: 1, pageSize: 10 };
  },
);
const [ImportModal, importApi] = useVbenModal({
  connectedComponent: ImportForm,
  destroyOnClose: true,
});
const [RegisterModal, registerApi] = useVbenModal({
  connectedComponent: RegisterForm,
  destroyOnClose: true,
});
const [OwnerModal, ownerApi] = useVbenModal({
  connectedComponent: OwnerForm,
  destroyOnClose: true,
});
function query() {
  applied.value = {
    pageNo: 1,
    pageSize: 10,
    tableName: filter.tableName.trim() || undefined,
    comment: filter.comment.trim() || undefined,
    dbId: filter.dbId,
  };
  if (node.value?.type === 'system')
    applied.value.sourceSystemId = node.value.id;
  if (node.value?.type === 'datasource')
    applied.value.datasourceId = node.value.id;
  if (node.value?.type === 'db') applied.value.dbId = node.value.id;
  void load(true);
}
function selectNode(value?: Api.TreeNode) {
  node.value = value;
  filter.dbId = undefined;
  void gridApi.formApi.setFieldValue('dbId', undefined);
  query();
}
function clearFilters() {
  filter.tableName = '';
  filter.comment = '';
  filter.dbId = undefined;
}
function reset() {
  clearFilters();
  query();
}
function onTreeLoaded(nodes: Api.TreeNode[]) {
  const options: typeof dbOptions.value = [];
  const visit = (items: Api.TreeNode[]) =>
    items.forEach((item) => {
      if (item.type === 'db' && item.id !== undefined)
        options.push({ label: item.name ?? String(item.id), value: item.id });
      visit(item.children ?? []);
    });
  visit(nodes);
  dbOptions.value = options;
}
function detail(row: Api.MetaTable, tab = 'fields') {
  if (row.id === undefined) return;
  void router.push({
    name: props.finalized
      ? 'GovernanceMetaFinalizedDetail'
      : 'GovernanceMetaTableDetail',
    params: { id: String(row.id) },
    query: { tab },
  });
}
useObjectDetailQuery((id) => detail({ id }));
async function refresh() {
  await load();
  await tree.value?.reload();
}
async function changeState(row: Api.MetaTable) {
  if (busy.value || row.id === undefined || row.stateRevision === undefined)
    return;
  busy.value = true;
  try {
    await (row.publishStatus === 1 ? unpublishMetaTable : publishMetaTable)(
      row.id,
      row.stateRevision,
    );
    message.success(row.publishStatus === 1 ? '已取消发布' : '已发布');
    await load();
  } finally {
    busy.value = false;
  }
}
async function remove(row: Api.MetaTable) {
  if (row.id === undefined || row.stateRevision === undefined) return;
  await deleteMetaTable({ id: row.id, stateRevision: row.stateRevision });
  await refresh();
}
async function revoke(row: Api.MetaTable) {
  if (row.id === undefined || row.stateRevision === undefined) return;
  await cancelFinalizeMetaTable({
    id: row.id,
    stateRevision: row.stateRevision,
  });
  message.success('已撤销定版');
  await load();
}
async function batch(action: 'delete' | 'publish' | 'unpublish') {
  const rows = [...selected.value];
  if (rows.length === 0 || busy.value) return;
  Modal.confirm({
    title: '系统提示',
    content: `确认对选中的 ${rows.length} 张表执行${{ publish: '发布', unpublish: '取消发布', delete: '删除' }[action]}？`,
    async onOk() {
      busy.value = true;
      let completed = 0;
      try {
        for (const row of rows) {
          if (row.id === undefined || row.stateRevision === undefined) continue;
          if (action === 'delete')
            await deleteMetaTable({
              id: row.id,
              stateRevision: row.stateRevision,
            });
          else if ((action === 'publish') !== (row.publishStatus === 1))
            await (
              action === 'publish' ? publishMetaTable : unpublishMetaTable
            )(row.id, row.stateRevision);
          completed++;
        }
        message.success(`已处理 ${completed} 张表`);
      } catch {
        message.warning(
          `已处理 ${completed} 张表，其余操作未完成，请刷新后重试`,
        );
      } finally {
        busy.value = false;
        await refresh();
      }
    },
  });
}
async function deleteDb() {
  if (node.value?.id === undefined) return;
  await confirmDeleteMetaDb(node.value.id);
  node.value = undefined;
  query();
  await tree.value?.reload();
}
async function publishDb() {
  if (node.value?.id === undefined) return;
  const result = await publishMetaDb(node.value.id);
  Modal.info({
    title: '批量发布结果',
    content: `本次发布 ${result.published ?? 0} 张，跳过 ${result.skipped ?? 0} 张。${result.skippedReasons?.join('；') ?? ''}`,
  });
  await load();
}
const sidebarCollapsed = ref(false);
</script>
<template>
  <Page auto-content-height content-class="metadata-page">
    <ImportModal @success="refresh" /><RegisterModal
      @success="refresh"
    /><OwnerModal @success="refresh" />
    <div class="metadata-layout">
      <MetaObjectTree v-show="!sidebarCollapsed" ref="tree" @select="selectNode" @loaded="onTreeLoaded" />
      <SidebarCollapse :collapsed="sidebarCollapsed" label="目录" @toggle="sidebarCollapsed = !sidebarCollapsed" />
      <div class="metadata-layout__main">
        <Alert v-if="error" type="error" message="加载失败，请重试" show-icon>
          <template #action>
            <Button size="small" @click="load()">
              <template #icon>
                <IconifyIcon :icon="metadataIcon('refresh')" />
              </template>
              重试
            </Button>
          </template>
        </Alert>
        <Alert
          v-if="node?.objectStatus === 2 && !finalized"
          type="warning"
          message="源端已未发现此库，等待确认删除。"
        >
          <template #action>
            <TableAction
              :actions="[
                {
                  label: '确认删除',
                  icon: metadataIcon('delete'),
                  auth: ['governance:meta-table:update'],
                  popConfirm: {
                    title: '确认删除此库的元数据？',
                    confirm: deleteDb,
                  },
                },
              ]"
            />
          </template>
        </Alert>
        <div class="metadata-grid-shell">
          <Grid
            class="metadata-grid"
            :table-title="finalized ? '定版元数据列表' : '最新元数据列表'"
            :show-search-form="filterVisible"
          >
            <template #toolbar-tools>
              <div class="metadata-toolbar">
                <div v-if="!finalized" class="metadata-toolbar">
                  <Button
                    v-access:code="['governance:meta-table:publish']"
                    :disabled="!selected.length || busy"
                    @click="batch('publish')"
                  >
                    <template #icon>
                      <IconifyIcon :icon="metadataIcon('publish')" />
                    </template>
                    发布
                  </Button>
                  <Button
                    v-access:code="['governance:meta-table:publish']"
                    :disabled="!selected.length || busy"
                    @click="batch('unpublish')"
                  >
                    <template #icon>
                      <IconifyIcon :icon="metadataIcon('unpublish')" />
                    </template>
                    取消发布
                  </Button>
                  <Button
                    v-access:code="['governance:meta-table:update']"
                    :disabled="!selected.length || busy"
                    danger
                    @click="batch('delete')"
                  >
                    <template #icon>
                      <IconifyIcon :icon="metadataIcon('delete')" />
                    </template>
                    删除
                  </Button>
                </div>

                <div class="metadata-tools">
                  <TableAction
                    v-if="!finalized"
                    :actions="[]"
                    :drop-down-actions="[
                      {
                        label: '手工登记',
                        icon: metadataIcon('finalize'),
                        auth: ['governance:meta-table:update'],
                        onClick: () => registerApi.setData(undefined).open(),
                      },
                      {
                        label: '导入数据字典',
                        icon: metadataIcon('publish'),
                        auth: ['governance:meta-table:update'],
                        onClick: () => importApi.setData(undefined).open(),
                      },
                      {
                        label: '批量认责',
                        icon: metadataIcon('settings'),
                        auth: ['governance:meta-table:update'],
                        disabled: !selected.length,
                        onClick: () =>
                          ownerApi
                            .setData({ ids: selected.map((row) => row.id) })
                            .open(),
                      },
                      {
                        label: '批量发布本库',
                        icon: metadataIcon('publish'),
                        auth: ['governance:meta-table:publish'],
                        ifShow: node?.type === 'db' && node.objectStatus === 1,
                        popConfirm: {
                          title: '确认发布本库符合条件的表？',
                          confirm: publishDb,
                        },
                      },
                    ]"
                  />
                  <Button
                    aria-label="显示或隐藏筛选"
                    @click="filterVisible = !filterVisible"
                  >
                    <IconifyIcon :icon="metadataIcon('search')" />
</Button><Button aria-label="刷新" @click="load()">
                    <IconifyIcon :icon="metadataIcon('refresh')" />
                  </Button>
                </div>
              </div>
            </template>
            <template #tableInfo="{ row }">
              <div class="metadata-info text-left">
                <div class="metadata-info__title">
                  <button
                    class="metadata-link metadata-ellipsis"
                    @click="detail(row)"
                  >
                    {{ row.tableName }}
</button><span class="metadata-tag metadata-tag--source">{{
                    finalized ? row.finalizedVersion : row.structVersion
                  }}</span>
                </div>
                <span class="metadata-muted">{{
                  row.businessName || displayComment(row)
                }}</span>
              </div>
            </template>
            <template #source="{ row }">
              <div class="metadata-info">
                <span class="metadata-ellipsis">{{
                  row.sourceSystemName || '-'
                }}</span><span class="metadata-tag metadata-tag--source"><MetadataIcon
                    :name="metadataConnectionIcon(row.dbType)"
                    :size="12"
                  />{{ row.datasourceName || row.dbName || '-' }}</span>
              </div>
            </template>
            <template #status="{ row }">
              <Switch
                :checked="row.publishStatus === 1"
                :disabled="
                  !canPublish || busy || row.stateRevision === undefined
                "
                size="small"
                :aria-label="`${row.tableName}发布状态`"
                @change="changeState(row)"
              />
            </template>
            <template #owner="{ row }">
              <div class="metadata-info">
                <span>{{ row.businessLeaderName || '-' }}</span><span>{{ row.businessLeaderPhone || '-' }}</span>
              </div>
            </template>
            <template #techOwner="{ row }">
              <div class="metadata-info">
                <span>{{ row.techLeaderName || '-' }}</span><span>{{ row.techLeaderPhone || '-' }}</span>
              </div>
            </template>
            <template #creator="{ row }">
              <div class="metadata-info">
                <span class="metadata-ellipsis">{{
                  row.creatorName || '-'
                }}</span><span>{{ row.creatorPhone || '' }}</span>
              </div>
            </template>
            <template #actions="{ row }">
              <TableAction
                v-if="finalized"
                :actions="[
                  {
                    label: '详情',
                    type: 'link',
                    icon: metadataIcon('detail'),
                    onClick: () => detail(row, 'base'),
                    auth: ['governance:meta-table:query'],
                  },
                  {
                    label: '字段列表',
                    type: 'link',
                    icon: metadataIcon('fields'),
                    onClick: () => detail(row),
                    auth: ['governance:meta-table:query'],
                  },
                  {
                    label: '撤销定版',
                    type: 'link',
                    icon: metadataIcon('cancel-finalize'),
                    auth: ['governance:meta-table:publish'],
                    popConfirm: {
                      title: '确认撤销定版该版本吗？',
                      confirm: () => revoke(row),
                    },
                  },
                ]"
              />
              <TableAction
                v-else
                :actions="[
                  {
                    label: '详情',
                    type: 'link',
                    icon: metadataIcon('detail'),
                    onClick: () => detail(row),
                    auth: ['governance:meta-table:query'],
                  },
                  {
                    label: '修改',
                    type: 'link',
                    icon: metadataIcon('edit'),
                    disabled: row.publishStatus === 1,
                    onClick: () =>
                      router.push({
                        name: 'GovernanceMetaTableEdit',
                        params: { id: String(row.id) },
                      }),
                    auth: ['governance:meta-table:update'],
                  },
                ]"
                :drop-down-actions="[
                  {
                    label: '删除',
                    danger: true,
                    icon: metadataIcon('delete'),
                    disabled: row.publishStatus === 1,
                    auth: ['governance:meta-table:update'],
                    popConfirm: {
                      title: '确认删除此表元数据？',
                      confirm: () => remove(row),
                    },
                  },
                  {
                    label: '版本与变更',
                    icon: metadataIcon('compare'),
                    auth: ['governance:meta-table:query'],
                    onClick: () => detail(row, 'versions'),
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
