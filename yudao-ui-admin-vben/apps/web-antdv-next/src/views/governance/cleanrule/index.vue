<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceCleanRuleApi } from '#/api/governance/cleanrule';

import {
  computed,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';

import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime, handleTree } from '@vben/utils';

import {
  Alert,
  Button,
  Input,
  message,
  Modal,
  Switch,
  Tag,
  Tooltip,
  Tree,
} from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import { getCategoryCleanRuleList } from '#/api/governance/categorycleanrule';
import {
  deleteCleanRule,
  getCleanRulePage,
  updateCleanRuleStatus,
} from '#/api/governance/cleanrule';
import TreeToolbar from '#/components/tree-toolbar/index.vue';

import { useTreeExpansion } from '../common/use-tree-expansion';
import StandardListLayout from '../standardcommon/standard-list-layout.vue';
import RuleForm from './modules/form.vue';
import TestModal from './modules/test.vue';

defineOptions({ name: 'GovernanceCleanRule' });

const rows = ref<GovernanceCleanRuleApi.CleanRule[]>([]);
const loading = ref(false);
const listFailed = ref(false);
const categoriesLoading = ref(false);
const categoriesFailed = ref(false);
const access = useAccessStore();
const user = useUserStore();
const owner = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
  ]),
);
let active = true;
let listSequence = 0;
let categorySequence = 0;
let deleteDialog: ReturnType<typeof Modal.confirm> | undefined;
const pagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const query = reactive<{
  description?: string;
  name?: string;
  status?: number;
}>({});
const collapsed = ref(false);

const ruleFormRef = ref<InstanceType<typeof RuleForm>>();
const testModalRef = ref<InstanceType<typeof TestModal>>();

/** 左侧清洗规则类目树 */
const categories = ref<any[]>([]);
const categoryFilter = ref('');
const selectedCategoryId = ref<number | string>();
const categoryTree = computed(() => {
  const keyword = categoryFilter.value.trim();
  const list = keyword
    ? categories.value.filter((item) => item.categoryName?.includes(keyword))
    : categories.value;
  return keyword ? list : handleTree(list);
});

async function loadCategories() {
  const request = ++categorySequence;
  const scope = owner.value;
  const current = () =>
    active && request === categorySequence && scope === owner.value;
  if (!active) return;
  categoriesLoading.value = true;
  categoriesFailed.value = false;
  try {
    const list = await getCategoryCleanRuleList({});
    if (!current()) return;
    categories.value = list.map((item) => ({
      ...item,
      id: String(item.id),
      parentId: item.parentId === undefined ? undefined : String(item.parentId),
    }));
  } catch {
    if (current()) categoriesFailed.value = true;
  } finally {
    if (current()) categoriesLoading.value = false;
  }
}

function handleCategorySelect(keys: (number | string)[]) {
  selectedCategoryId.value =
    keys[0] === undefined ? undefined : String(keys[0]);
  handleSearch();
}

/** 规则列表 */
const columns: VxeTableGridOptions<GovernanceCleanRuleApi.CleanRule>['columns'] =
  [
    { title: '编号', field: 'id', width: 70 },
    { title: '规则名称', field: 'name', minWidth: 190, align: 'left' },
    {
      title: '描述',
      field: 'description',
      minWidth: 240,
      align: 'left',
    },
    {
      title: '清洗规则类目',
      field: 'categoryName',
      width: 130,
      slots: { default: 'category' },
    },
    { title: '使用场景', field: 'useCase', minWidth: 240, align: 'left' },
    {
      title: '引擎策略',
      field: 'strategyKey',
      width: 130,
      slots: { default: 'strategy' },
    },
    {
      title: '状态',
      field: 'status',
      width: 80,
      slots: { default: 'status' },
    },
    { title: '创建人', field: 'creatorName', width: 90 },
    {
      title: '创建时间',
      field: 'createTime',
      width: 165,
      formatter: ({ cellValue }) => formatDateTime(cellValue) || '-',
    },
    {
      title: '操作',
      width: 240,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];

const [Grid, gridApi] = useVbenVxeGrid<GovernanceCleanRuleApi.CleanRule>({
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
    handleValuesChange: (values) => {
      Object.assign(query, {
        name: values.name,
        description: values.description,
        status: values.status,
      });
    },
    handleSubmit: (values) => {
      Object.assign(query, {
        name: values.name,
        description: values.description,
        status: values.status,
      });
      return handleSearch();
    },
    handleReset: async () => {
      await gridApi.formApi.reset();
      await handleReset();
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
    }) => {
      void handlePageChange(currentPage, pageSize);
    },
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

function listParams() {
  return {
    pageNo: pagination.pageNo,
    pageSize: pagination.pageSize,
    name: query.name,
    description: query.description,
    status: query.status,
    categoryId: selectedCategoryId.value,
  };
}

async function loadData() {
  if (!active) return;
  const request = ++listSequence;
  const scope = owner.value;
  const current = () =>
    active && request === listSequence && scope === owner.value;
  const params = listParams();
  const context = JSON.stringify(params);
  const currentQuery = () => context === JSON.stringify(listParams());
  loading.value = true;
  listFailed.value = false;
  rows.value = [];
  pagination.total = 0;
  try {
    const result = await getCleanRulePage(params);
    if (!current() || !currentQuery()) return;
    rows.value = result.list ?? [];
    pagination.total = result.total ?? 0;
  } catch {
    if (current() && currentQuery()) listFailed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}

function handleSearch() {
  pagination.pageNo = 1;
  return loadData();
}

function handleReset() {
  query.name = undefined;
  query.description = undefined;
  query.status = undefined;
  return handleSearch();
}

function handlePageChange(pageNo: number, pageSize: number) {
  pagination.pageNo = pageNo;
  pagination.pageSize = pageSize;
  return loadData();
}

async function handleStatusChange(row: GovernanceCleanRuleApi.CleanRule) {
  const scope = owner.value;
  if (!active) return;
  const newStatus = row.status === 0 ? 1 : 0;
  const text = newStatus === 0 ? '启用' : '停用';
  try {
    await updateCleanRuleStatus(row.id!, newStatus);
    if (!active || scope !== owner.value) return;
    message.success(`${text}成功`);
    loadData();
  } catch {
    if (active && scope === owner.value) void loadData();
  }
}

function handleDelete(row: GovernanceCleanRuleApi.CleanRule) {
  const scope = owner.value;
  if (!active) return;
  deleteDialog?.destroy();
  deleteDialog = Modal.confirm({
    title: '系统提示',
    content: `确认删除清洗规则"${row.name}"吗？`,
    okButtonProps: { danger: true },
    onOk: async () => {
      if (!active || scope !== owner.value) return;
      await deleteCleanRule(row.id!);
      if (!active || scope !== owner.value) return;
      message.success('删除成功');
      loadData();
    },
  });
}

onMounted(() => {
  loadCategories();
  loadData();
});

function invalidate() {
  ++listSequence;
  ++categorySequence;
  rows.value = [];
  categories.value = [];
  loading.value = false;
  categoriesLoading.value = false;
  listFailed.value = false;
  categoriesFailed.value = false;
  deleteDialog?.destroy();
  deleteDialog = undefined;
}
watch(
  owner,
  () => {
    invalidate();
    selectedCategoryId.value = undefined;
    categoryFilter.value = '';
    query.name = undefined;
    query.description = undefined;
    query.status = undefined;
    void gridApi.formApi.reset();
    pagination.pageNo = 1;
    if (active) {
      void loadCategories();
      void loadData();
    }
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  active = false;
  invalidate();
});
const expandedKeys = ref<string[]>([]);
const { allExpanded, toggleExpand } = useTreeExpansion(
  categoryTree,
  expandedKeys,
  categoryFilter,
);
</script>

<template>
  <Page auto-content-height>
    <StandardListLayout
      v-model:collapsed="collapsed"
      @resize="active && gridApi.grid?.recalculate?.(true)"
    >
      <template #tree>
        <div class="standard-tree-header flex items-center gap-2">
          <Input
            class="min-w-0 flex-1"
            v-model:value="categoryFilter"
            allow-clear
            placeholder="请输入清洗规则类目"
          >
            <template #prefix><IconifyIcon icon="lucide:search" /></template>
          </Input>
          <TreeToolbar
            :expanded="allExpanded"
            :loading="categoriesLoading"
            @toggle-expand="toggleExpand"
            @refresh="loadCategories"
          />
        </div>
        <Button
          :class="{
            'bg-primary/10 text-primary': selectedCategoryId === undefined,
          }"
          class="mb-1 w-full text-left"
          type="text"
          @click="handleCategorySelect([])"
        >
          <template #icon
            ><IconifyIcon icon="lucide:folder-open" class="text-primary"
          /></template>
          清洗规则类目
        </Button>
        <Tree
          v-model:expanded-keys="expandedKeys"
          v-if="categoryTree.length > 0"
          :field-names="{
            children: 'children',
            title: 'categoryName',
            key: 'id',
          }"
          :selected-keys="
            selectedCategoryId === undefined ? [] : [selectedCategoryId]
          "
          :tree-data="categoryTree as any"
          block-node
          @select="handleCategorySelect"
        >
          <template #titleRender="node">
            <span class="standard-tree-title">
              <IconifyIcon icon="lucide:folder" class="size-4 text-primary" />
              <span>{{ node.categoryName }}</span>
            </span>
          </template>
        </Tree>
        <div v-else class="text-muted-foreground p-2 text-xs">
          {{
            categoriesLoading
              ? '类目加载中…'
              : categoriesFailed
                ? '类目加载失败，请刷新重试'
                : '暂无类目'
          }}
        </div>
      </template>

      <Alert
        v-if="listFailed"
        class="mb-3"
        message="清洗规则加载失败，请重新查询"
        show-icon
        type="error"
      />
      <Grid class="min-h-0 flex-1" table-title="清洗规则管理">
        <template #toolbar-tools>
          <Button
            v-access:code="'governance:clean-rule:create'"
            type="primary"
            @click="ruleFormRef?.open(undefined, selectedCategoryId)"
          >
            <template #icon><IconifyIcon icon="lucide:plus" /></template>
            新增
          </Button>
        </template>
        <template #category="{ row }">
          <Tag color="blue">{{ row.categoryName ?? '-' }}</Tag>
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
          <div class="flex items-center justify-center gap-1 whitespace-nowrap">
            <Button
              :disabled="!row.engineSupported"
              size="small"
              type="link"
              @click="testModalRef?.open(row)"
            >
              <template #icon>
                <IconifyIcon icon="lucide:flask-conical" />
              </template>
              测试
            </Button>
            <Button
              v-access:code="'governance:clean-rule:update'"
              size="small"
              type="link"
              @click="ruleFormRef?.open(row.id)"
            >
              <template #icon><IconifyIcon icon="lucide:edit" /></template>
              编辑
            </Button>
            <Button
              v-access:code="'governance:clean-rule:delete'"
              danger
              size="small"
              type="link"
              @click="handleDelete(row)"
            >
              <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
              删除
            </Button>
          </div>
        </template>
      </Grid>
    </StandardListLayout>

    <RuleForm :key="`rule-form-${owner}`" ref="ruleFormRef" @saved="loadData" />
    <TestModal :key="`rule-test-${owner}`" ref="testModalRef" />
  </Page>
</template>

<style scoped>
.standard-list-layout :deep(.vxe-grid .vxe-body--column) {
  font-size: 12px;
}
</style>
