<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceAuditRuleApi } from '#/api/governance/auditrule';

import {
  computed,
  onActivated,
  onDeactivated,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Input,
  message,
  Modal,
  Spin,
  Switch,
  Tag,
  Tooltip,
  Tree,
} from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  deleteAuditRule,
  getAuditRuleDimensionList,
  getAuditRulePage,
  updateAuditRuleStatus,
} from '#/api/governance/auditrule';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import { useTreeExpansion } from '../common/use-tree-expansion';
import StandardListLayout from '../standardcommon/standard-list-layout.vue';
import RuleForm from './modules/form.vue';
import PreviewModal from './modules/preview.vue';

defineOptions({ name: 'GovernanceAuditRule' });

const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canQuery = computed(() =>
  hasAccessByCodes(['governance:audit-rule:query']),
);
const identity = computed(() =>
  JSON.stringify([user.userInfo?.userId, access.accessCodes]),
);
const listRequest = useObjectRequest(() => identity.value);
const dimensionRequest = useObjectRequest(() => identity.value);
let active = true;

const rows = ref<GovernanceAuditRuleApi.AuditRule[]>([]);
const loading = ref(false);
const collapsed = ref(false);
const pagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const query = reactive<{
  description?: string;
  name?: string;
  status?: number;
}>({});

const ruleFormRef = ref<InstanceType<typeof RuleForm>>();
const previewModalRef = ref<InstanceType<typeof PreviewModal>>();

/** 左侧质量维度树 */
const dimensions = ref<GovernanceAuditRuleApi.Dimension[]>([]);
const dimensionLoading = ref(false);
const dimensionError = ref(false);
const dimensionFilter = ref('');
const selectedDimensionId = ref<number | string>();
const dimensionTree = computed(() => {
  const keyword = dimensionFilter.value.trim();
  const list = keyword
    ? dimensions.value.filter((item) => item.name.includes(keyword))
    : dimensions.value;
  return [
    {
      id: '__all__',
      name: '质量维度',
      children: list.toSorted((left, right) => left.sort - right.sort),
    },
  ];
});

async function loadDimensions() {
  if (!active || !canQuery.value) return;
  const ticket = dimensionRequest.begin();
  dimensionLoading.value = true;
  dimensionError.value = false;
  try {
    const result = await getAuditRuleDimensionList();
    if (ticket()) dimensions.value = result;
  } catch {
    if (ticket()) dimensionError.value = true;
  } finally {
    if (ticket()) dimensionLoading.value = false;
  }
}

function handleDimensionSelect(keys: (number | string)[]) {
  selectedDimensionId.value = keys[0] === '__all__' ? undefined : keys[0];
  handleSearch();
}

/** 规则列表 */
const columns: VxeTableGridOptions<GovernanceAuditRuleApi.AuditRule>['columns'] =
  [
    { title: '编号', field: 'id', width: 70 },
    { title: '规则名称', field: 'name', minWidth: 180, align: 'left' },
    { title: '描述', field: 'description', minWidth: 220, align: 'left' },
    {
      title: '质量维度',
      field: 'dimensionName',
      width: 100,
      slots: { default: 'dimension' },
    },
    { title: '使用场景', field: 'useCase', minWidth: 220, align: 'left' },
    {
      title: '引擎策略',
      field: 'strategyKey',
      width: 150,
      slots: { default: 'strategy' },
    },
    {
      title: '状态',
      field: 'status',
      width: 100,
      slots: { default: 'status' },
    },
    { title: '创建人', field: 'creatorName', width: 100 },
    {
      title: '创建时间',
      field: 'createTime',
      width: 165,
      formatter: ({ cellValue }: { cellValue?: number | string }) =>
        formatDateTime(cellValue) || '-',
    },
    {
      title: '操作',
      width: 260,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];

const [Grid, gridApi] = useVbenVxeGrid<GovernanceAuditRuleApi.AuditRule>({
  formOptions: {
    schema: [
      {
        fieldName: 'name',
        label: '规则名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入规则名称' },
      },
      {
        fieldName: 'description',
        label: '描述',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入描述' },
      },
      {
        fieldName: 'status',
        label: '状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          placeholder: '请选择状态',
          options: [
            { label: '启用', value: 0 },
            { label: '停用', value: 1 },
          ],
        },
      },
    ],
    handleSubmit: async (values) => {
      query.name = values.name;
      query.description = values.description;
      query.status = values.status;
      handleSearch();
    },
    handleReset: async () => {
      await gridApi.formApi.reset();
      handleReset();
    },
  },
  gridOptions: {
    columns,
    data: [],
    height: 'auto',
    showOverflow: false,
    rowConfig: { keyField: 'id', isHover: true },
    pagerConfig: { currentPage: 1, pageSize: 10, total: 0 },
    proxyConfig: { enabled: false, autoLoad: false },
    toolbarConfig: {
      refresh: true,
      refreshOptions: { queryMethod: loadData },
      search: true,
    },
  },
  gridEvents: {
    pageChange: ({
      currentPage,
      pageSize,
    }: {
      currentPage: number;
      pageSize: number;
    }) => handlePageChange(currentPage, pageSize),
  },
});

watch(
  [
    rows,
    loading,
    () => pagination.pageNo,
    () => pagination.pageSize,
    () => pagination.total,
  ],
  () => {
    gridApi.setGridOptions({
      data: rows.value,
      loading: loading.value,
      pagerConfig: {
        currentPage: pagination.pageNo,
        pageSize: pagination.pageSize,
        total: pagination.total,
      },
    });
  },
  { immediate: true },
);

const DIMENSION_COLORS: Record<string, string> = {
  一致性: 'geekblue',
  完整性: 'green',
  规范性: 'purple',
  时效性: 'orange',
  准确性: 'cyan',
};

async function loadData() {
  if (!active || !canQuery.value) return;
  const ticket = listRequest.begin();
  loading.value = true;
  try {
    const result = await getAuditRulePage({
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
      name: query.name,
      description: query.description,
      status: query.status,
      dimensionId: selectedDimensionId.value,
    });
    if (ticket()) {
      rows.value = result.list ?? [];
      pagination.total = result.total ?? 0;
    }
  } catch {
    if (ticket()) {
      rows.value = [];
      pagination.total = 0;
    }
  } finally {
    if (ticket()) loading.value = false;
  }
}

function handleSearch() {
  pagination.pageNo = 1;
  loadData();
}

function handleReset() {
  query.name = undefined;
  query.description = undefined;
  query.status = undefined;
  handleSearch();
}

function handlePageChange(pageNo: number, pageSize: number) {
  pagination.pageNo = pageNo;
  pagination.pageSize = pageSize;
  loadData();
}

async function handleStatusChange(row: GovernanceAuditRuleApi.AuditRule) {
  const newStatus = row.status === 0 ? 1 : 0;
  const text = newStatus === 0 ? '启用' : '停用';
  try {
    await updateAuditRuleStatus(row.id!, newStatus);
    message.success(`${text}成功`);
    loadData();
  } catch {
    loadData();
  }
}

function handleDelete(row: GovernanceAuditRuleApi.AuditRule) {
  Modal.confirm({
    title: '系统提示',
    content: `确认删除稽查规则"${row.name}"吗？`,
    okButtonProps: { danger: true },
    onOk: async () => {
      await deleteAuditRule(row.id!);
      message.success('删除成功');
      loadData();
    },
  });
}

onMounted(() => {
  loadDimensions();
  loadData();
});

function resetContext() {
  listRequest.begin();
  dimensionRequest.begin();
  rows.value = [];
  dimensions.value = [];
  selectedDimensionId.value = undefined;
  pagination.pageNo = 1;
  pagination.total = 0;
  loading.value = false;
  dimensionLoading.value = false;
  dimensionError.value = false;
}

watch(
  [identity, () => access.accessToken, () => access.refreshToken],
  () => {
    resetContext();
    loadDimensions();
    loadData();
  },
  { flush: 'sync' },
);

onDeactivated(() => {
  active = false;
  resetContext();
});
onActivated(() => {
  if (active) return;
  active = true;
  loadDimensions();
  loadData();
});
const expandedKeys = ref<string[]>([]);
const { allExpanded, toggleExpand } = useTreeExpansion(
  dimensionTree,
  expandedKeys,
  dimensionFilter,
);
</script>

<template>
  <Page auto-content-height>
    <RuleForm ref="ruleFormRef" @saved="loadData" />
    <PreviewModal ref="previewModalRef" />
    <StandardListLayout
      v-model:collapsed="collapsed"
      @resize="gridApi.grid?.recalculate?.(true)"
    >
      <template #tree>
        <div class="standard-tree-header flex items-center gap-2">
          <Input
            class="min-w-0 flex-1"
            v-model:value="dimensionFilter"
            allow-clear
            placeholder="请输入质量维度"
          >
            <template #prefix><IconifyIcon icon="lucide:search" /></template>
          </Input>
          <TreeToolbar
            :expanded="allExpanded"
            :loading="dimensionLoading"
            @toggle-expand="toggleExpand"
            @refresh="loadDimensions"
          />
        </div>
        <Spin v-if="dimensionLoading" class="p-2" />
        <Alert
          v-else-if="dimensionError"
          type="error"
          message="质量维度加载失败"
        >
          <template #action>
            <Button size="small" @click="loadDimensions">重试</Button>
          </template>
        </Alert>
        <Tree
          v-model:expanded-keys="expandedKeys"
          v-else
          v-show="dimensionTree.length > 0"
          :field-names="{
            children: 'children',
            title: 'name',
            key: 'id',
          }"
          :selected-keys="
            selectedDimensionId === undefined
              ? ['__all__']
              : [selectedDimensionId]
          "
          :tree-data="dimensionTree"
          block-node
          @select="handleDimensionSelect"
        >
          <template #titleRender="node">
            <span class="standard-tree-title">
              <IconifyIcon icon="lucide:folder" class="size-4 text-primary" />
              <span>{{ node.name }}</span>
            </span>
          </template>
        </Tree>
        <div
          v-if="
            !dimensionLoading &&
            !dimensionError &&
            !dimensionTree[0]?.children.length
          "
          class="text-muted-foreground p-2 text-xs"
        >
          暂无维度
        </div>
      </template>

      <Grid class="min-h-0 flex-1" table-title="稽查规则管理">
        <template #toolbar-tools>
          <TableAction
            :actions="[
              {
                label: '新增',
                type: 'primary',
                icon: ACTION_ICON.ADD,
                auth: ['governance:audit-rule:create'],
                onClick: () =>
                  ruleFormRef?.open(undefined, selectedDimensionId),
              },
            ]"
          />
        </template>
        <template #dimension="{ row }">
          <Tag :color="DIMENSION_COLORS[row.dimensionName ?? ''] ?? 'default'">
            {{ row.dimensionName ?? '-' }}
          </Tag>
        </template>
        <template #strategy="{ row }">
          <Tooltip v-if="row.strategyKey" :title="row.strategyKey">
            <Tag :color="row.engineSupported ? 'success' : 'default'">
              {{ row.engineSupported ? '已接入引擎' : '未接入' }}
            </Tag>
          </Tooltip>
          <span v-else class="text-muted-foreground">-</span>
        </template>
        <template #status="{ row }">
          <Switch
            :checked="row.status === 0"
            checked-children="启用"
            un-checked-children="停用"
            @change="handleStatusChange(row)"
          />
        </template>
        <template #actions="{ row }">
          <TableAction
            :actions="[
              {
                label: 'SQL预览',
                type: 'link',
                icon: 'lucide:code',
                disabled: !row.engineSupported,
                onClick: () => previewModalRef?.open(row),
              },
              {
                label: '编辑',
                type: 'link',
                icon: ACTION_ICON.EDIT,
                auth: ['governance:audit-rule:update'],
                onClick: () => ruleFormRef?.open(row.id),
              },
              {
                label: '删除',
                type: 'link',
                danger: true,
                icon: ACTION_ICON.DELETE,
                auth: ['governance:audit-rule:delete'],
                onClick: () => handleDelete(row),
              },
            ]"
          />
        </template>
      </Grid>
    </StandardListLayout>
  </Page>
</template>

<style scoped>
.standard-list-layout :deep(.vxe-grid .vxe-body--column) {
  font-size: 12px;
}
</style>
