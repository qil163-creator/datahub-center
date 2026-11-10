<script setup lang="ts">
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { StandardDocApi } from '#/api/governance/doc';

import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import { Alert, Button } from 'antdv-next';

import { TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { getStandardCodeSetPage } from '#/api/governance/standardcodeset';
import { getStandardElementPage } from '#/api/governance/standardelement';
import { getTableModelPage } from '#/api/modeling/tablemodel';
import { DictTag } from '#/components/dict-tag';
import { useObjectRequest } from '#/hooks/use-object-request';
import CodeSetForm from '#/views/governance/standardcodeset/modules/form.vue';
import ElementForm from '#/views/governance/standardelement/modules/form.vue';

interface RelatedRow {
  id: number | string;
  name?: string;
  englishName?: string;
  description?: string;
  categoryName?: string;
  categoryId?: number | string;
  bizCategoryName?: string;
  subjectDomainName?: string;
  createMode?: string;
  status?: number | string;
  creator?: string;
  createTime?: number | string;
  itemCount?: number;
}
const props = defineProps<{
  documentId: StandardDocApi.Id;
  kind: 'codeSet' | 'element' | 'model';
  standardType: string;
}>();
const router = useRouter();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const permission = computed(
  () =>
    ({
      codeSet: 'governance:standard-code-set',
      element: 'governance:standard-element',
      model: 'modeling:model',
    })[props.kind],
);
const canQuery = computed(() =>
  hasAccessByCodes([`${permission.value}:query`]),
);
const canCreate = computed(
  () =>
    props.kind !== 'model' && hasAccessByCodes([`${permission.value}:create`]),
);
const rows = ref<RelatedRow[]>([]);
const page = ref(1);
const pageSize = ref(10);
const total = ref(0);
const loading = ref(false);
const failed = ref(false);
const request = useObjectRequest(
  () =>
    JSON.stringify([
      props.documentId,
      props.kind,
      access.accessCodes,
      page.value,
      pageSize.value,
    ]),
  () => {
    rows.value = [];
    total.value = 0;
    loading.value = false;
  },
);
const [ElementModal, elementApi] = useVbenModal({
  connectedComponent: ElementForm,
  destroyOnClose: true,
});
const [CodeSetModal, codeSetApi] = useVbenModal({
  connectedComponent: CodeSetForm,
  destroyOnClose: true,
});
const columns = computed<VxeTableGridOptions<RelatedRow>['columns']>(() => [
  { field: 'id', title: '编号', width: 100 },
  { field: 'name', title: '中文名称', minWidth: 200 },
  {
    field: 'englishName',
    title: props.kind === 'model' ? '模型编码' : '英文名称',
    minWidth: 180,
  },
  ...(props.kind === 'model'
    ? [
        { field: 'bizCategoryName', title: '业务板块', minWidth: 150 },
        { field: 'subjectDomainName', title: '主题域', minWidth: 150 },
        { field: 'createMode', title: '来源信息', width: 120 },
      ]
    : [
        { field: 'description', title: '描述', minWidth: 200 },
        {
          field: 'categoryName',
          title: '标准数据元类目',
          minWidth: 170,
          formatter: ({ row }: { row: RelatedRow }) =>
            row.categoryName || row.categoryId || '-',
        },
        ...(props.kind === 'codeSet'
          ? [{ field: 'itemCount', title: '代码项数量', width: 120 }]
          : []),
      ]),
  { field: 'status', title: '状态', width: 110, slots: { default: 'status' } },
  { field: 'creator', title: '创建人', width: 130 },
  {
    field: 'createTime',
    title: '创建时间',
    width: 180,
    formatter: 'formatDateTime',
  },
  {
    title: '操作',
    width: 110,
    align: 'center',
    headerAlign: 'center',
    fixed: 'right',
    showOverflow: false,
    slots: { default: 'actions' },
  },
]);
const [Grid, gridApi] = useVbenVxeGrid<RelatedRow>({
  gridOptions: {
    align: 'left',
    border: false,
    columns: columns.value,
    data: [],
    headerAlign: 'left',
    headerCellConfig: { height: 34 },
    height: 'auto',
    minHeight: 0,
    round: false,
    rowConfig: { keyField: 'id', isHover: true },
    proxyConfig: { enabled: false, autoLoad: false },
    pagerConfig: { currentPage: 1, pageSize: 10, total: 0 },
    toolbarConfig: {
      refresh: true,
      refreshOptions: { queryMethod: load },
      zoom: false,
    },
  },
  gridEvents: {
    pageChange: ({
      currentPage,
      pageSize: size,
    }: {
      currentPage: number;
      pageSize: number;
    }) => {
      page.value = currentPage;
      pageSize.value = size;
      void load();
    },
  },
});
watch([rows, loading, total, page, pageSize, columns], () =>
  gridApi.setGridOptions({
    columns: columns.value,
    data: rows.value,
    loading: loading.value,
    pagerConfig: {
      currentPage: page.value,
      pageSize: pageSize.value,
      total: total.value,
    },
  }),
);
async function load() {
  if (!canQuery.value) return;
  const current = request.begin();
  loading.value = true;
  failed.value = false;
  const params = {
    documentId: props.documentId,
    pageNo: page.value,
    pageSize: pageSize.value,
  };
  try {
    let result: { list: RelatedRow[]; total: number };
    if (props.kind === 'element') {
      const source = await getStandardElementPage(params);
      result = {
        ...source,
        list: source.list.map((row) => ({
          ...row,
          englishName: row.engName,
          creator: row.creatorName || row.creator,
        })),
      };
    } else if (props.kind === 'codeSet') {
      const source = await getStandardCodeSetPage(params);
      result = {
        ...source,
        list: source.list.map((row) => ({
          ...row,
          englishName: row.type,
          creator: row.creatorName || row.creator,
        })),
      };
    } else {
      const source = await getTableModelPage(params);
      result = {
        ...source,
        list: source.list.map((row) => ({ ...row, englishName: row.code })),
      };
    }
    if (current()) {
      rows.value = result.list;
      total.value = result.total;
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
function create() {
  const context = {
    documentId: props.documentId,
    standardType: props.standardType,
  };
  if (props.kind === 'element') elementApi.setData(context).open();
  else codeSetApi.setData(context).open();
}
function detail(row: RelatedRow) {
  const name = {
    model: 'ModelingTableModelDetail',
    element: 'GovernanceStandardElementDetail',
    codeSet: 'GovernanceStandardCodeSetDetail',
  }[props.kind];
  void router.push({ name, params: { id: String(row.id) } });
}
watch(
  () => [
    props.documentId,
    props.kind,
    access.accessToken,
    access.refreshToken,
    access.accessCodes,
  ],
  () => {
    page.value = 1;
    void load();
  },
  { deep: true, immediate: true },
);
</script>
<template>
  <ElementModal @success="load" />
  <CodeSetModal @success="load" />
  <div class="doc-related-list">
  <Alert v-if="!canQuery" type="info" message="暂无该类关联对象的查询权限" />
  <Grid v-else class="doc-related-list__grid">
    <template #table-title>
      <Button v-if="canCreate" size="small" type="primary" @click="create">
        <template #icon><IconifyIcon icon="lucide:plus" /></template>
        新增
      </Button>
    </template>
    <template #status="{ row }">
      <span v-if="kind === 'model'">{{ row.status || '-' }}</span>
      <DictTag v-else :type="DICT_TYPE.COMMON_STATUS" :value="row.status" />
    </template>
    <template #actions="{ row }">
      <TableAction
        :actions="[
          {
            label: '详情',
            type: 'link',
            icon: 'lucide:eye',
            auth: [`${permission}:query`],
            onClick: () => detail(row),
          },
        ]"
      />
    </template>
  </Grid>
  </div>
</template>

<style scoped>
.doc-related-list {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.doc-related-list__grid {
  flex: 1;
  min-height: 0;
}

.doc-related-list__grid :deep(.vxe-grid) {
  padding: 0;
}

.doc-related-list__grid :deep(.vxe-toolbar) {
  height: 30px;
  min-height: 30px;
  margin-bottom: 8px;
}

.doc-related-list__grid :deep(.vxe-toolbar .vxe-button.is--circle) {
  width: 26px;
  min-width: 26px;
  height: 26px;
}

.doc-related-list__grid :deep(.vxe-table--border-line) {
  border: 0;
  border-bottom: 1px solid hsl(var(--border));
}

.doc-related-list__grid :deep(.vxe-cell--col-resizable::before) {
  background-color: transparent;
}

.doc-related-list__grid :deep(.vxe-cell--col-resizable:hover::before) {
  background-color: hsl(var(--border));
}

.doc-related-list__grid :deep(.vxe-header--column .vxe-cell),
.doc-related-list__grid :deep(.vxe-body--column .vxe-cell) {
  padding-inline: 10px;
}
</style>
