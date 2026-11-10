<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { computed, onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Input, message, Modal, Tag, Tooltip } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  deletePlan,
  deletePlanCategory,
  disablePlan,
  generateTask,
  getPlanCategoryList,
  getPlanPage,
  publishPlan,
} from '#/api/dataforge/fill';
import SidebarCollapse from '#/components/sidebar-collapse/index.vue';
import { $t } from '#/locales';

import { usePlanGridColumns, usePlanGridFormSchema } from '../data';
import { PERIOD_TYPE_TEXT, PLAN_STATUS_TEXT } from '../shared';
import CategoryForm from './modules/category-form.vue';
import CategorySideTree from './modules/category-side-tree.vue';
import PlanDetail from './modules/detail.vue';
import PlanForm from './modules/plan-form.vue';

defineOptions({ name: 'DataforgeFillPlan' });

const access = useAccessStore();
const user = useUserStore();
const identity = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
  ]),
);
let alive = true;
onBeforeUnmount(() => {
  alive = false;
  ++categoryRequest;
});
const isCurrent = (started: string) => alive && started === identity.value;
const categories = ref<DataforgeFillApi.PlanCategory[]>([]);
const selectedCategoryId = ref<number | string>();
const listIdentity = computed(() =>
  JSON.stringify([identity.value, selectedCategoryId.value]),
);
const categoriesLoading = ref(false);
const sidebarCollapsed = ref(false);
let categoryRequest = 0;

async function loadCategories() {
  const request = ++categoryRequest;
  const started = identity.value;
  categoriesLoading.value = true;
  try {
    const result = await getPlanCategoryList();
    if (request === categoryRequest && isCurrent(started)) {
      categories.value = result;
      if (
        selectedCategoryId.value !== undefined &&
        String(selectedCategoryId.value) !== '0' &&
        !result.some(
          (item) => String(item.id) === String(selectedCategoryId.value),
        )
      ) {
        selectedCategoryId.value = undefined;
      }
    }
  } catch {
    // 请求层已提示错误，保留当前分类并允许重新加载。
  } finally {
    if (request === categoryRequest && isCurrent(started)) {
      categoriesLoading.value = false;
    }
  }
}

onMounted(loadCategories);
let activatedOnce = false;
onActivated(() => {
  if (activatedOnce) void loadCategories();
  activatedOnce = true;
});

const [CategoryFormModal, categoryFormModalApi] = useVbenModal({
  connectedComponent: CategoryForm,
  destroyOnClose: true,
});

function handleCreateCategory(parentId: number | string) {
  categoryFormModalApi.setData({ parentId }).open();
}

function handleEditCategory(id: number | string) {
  categoryFormModalApi.setData({ id }).open();
}

function handleDeleteCategory(category: { id: number | string; name: string }) {
  const started = identity.value;
  Modal.confirm({
    title: `删除分类「${category.name}」`,
    content: '分类下有子分类或采集计划时不能删除，请先调整归属。',
    okButtonProps: { danger: true },
    onOk: async () => {
      if (!isCurrent(started)) return;
      await deletePlanCategory(category.id);
      if (!isCurrent(started)) return;
      if (String(selectedCategoryId.value) === String(category.id)) {
        selectedCategoryId.value = undefined;
      }
      message.success('分类已删除');
      await loadCategories();
    },
  });
}
const formVisible = ref(false);
const formData = ref<{ categoryId?: number | string; id?: number | string }>();

function closeForm() {
  formVisible.value = false;
  formData.value = undefined;
}

watch(identity, () => {
  closeForm();
  ++categoryRequest;
  categories.value = [];
  selectedCategoryId.value = undefined;
  categoriesLoading.value = false;
  generateOpen.value = false;
  generatePlanId.value = undefined;
  void loadCategories();
});

function handleCreate() {
  formData.value = {
    categoryId:
      selectedCategoryId.value === undefined ||
      String(selectedCategoryId.value) === '0'
        ? undefined
        : selectedCategoryId.value,
  };
  formVisible.value = true;
}

function handleEdit(plan: DataforgeFillApi.Plan) {
  formData.value = { id: plan.id };
  formVisible.value = true;
}

const [DetailModal, detailModalApi] = useVbenModal({
  connectedComponent: PlanDetail,
  destroyOnClose: true,
});

function handleDetail(plan: DataforgeFillApi.Plan) {
  detailModalApi.setData({ id: plan.id }).open();
}

function handleRefresh() {
  gridApi.query();
}

function handleFormSuccess() {
  closeForm();
  handleRefresh();
}

function handlePublish(plan: DataforgeFillApi.Plan) {
  Modal.confirm({
    title: `发布计划「${plan.name}」`,
    content:
      plan.periodType === 1
        ? '发布后立即生成一次性任务并通知各组织填报人。'
        : '发布后按周期规则自动生成任务；请确认模板版本、目标组织与填报人无误。',
    onOk: async () => {
      await publishPlan(plan.id);
      message.success('已发布启用');
      handleRefresh();
    },
  });
}

function handleDisable(plan: DataforgeFillApi.Plan) {
  Modal.confirm({
    title: `停用计划「${plan.name}」`,
    content: '停用后不再生成新任务；已生成任务不受影响。',
    onOk: async () => {
      await disablePlan(plan.id);
      message.success('已停用');
      handleRefresh();
    },
  });
}

async function handleDelete(plan: DataforgeFillApi.Plan) {
  await deletePlan(plan.id);
  message.success('已删除');
  handleRefresh();
}

/** 手工生成/补生成：一次性任务重新生成；周期任务传历史时点补期次 */
const generateOpen = ref(false);
const generatePlanId = ref<number | string>();
const generateTime = ref('');

function openGenerate(plan: DataforgeFillApi.Plan) {
  generatePlanId.value = plan.id;
  generateTime.value = '';
  generateOpen.value = true;
}

async function handleGenerate() {
  if (!generatePlanId.value) return;
  const taskId = await generateTask({
    generateTime: generateTime.value || undefined,
    planId: generatePlanId.value,
  });
  message.success(`已生成任务（编号 ${taskId}）`);
  generateOpen.value = false;
  handleRefresh();
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: usePlanGridFormSchema(),
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    columns: usePlanGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          const started = listIdentity.value;
          const result = await getPlanPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
            categoryId: selectedCategoryId.value,
          });
          return alive && started === listIdentity.value
            ? result
            : { list: [], total: 0 };
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<DataforgeFillApi.Plan>,
});
</script>

<template>
  <Page auto-content-height>
    <PlanForm
      v-if="formVisible"
      :key="`form:${identity}`"
      :initial-data="formData"
      @close="closeForm"
      @success="handleFormSuccess"
    />
    <div
      v-show="!formVisible"
      class="plan-layout flex h-full min-h-0 flex-col md:flex-row"
    >
      <CategorySideTree
        v-show="!sidebarCollapsed"
        v-model:selected="selectedCategoryId"
        :items="categories"
        :loading="categoriesLoading"
        @create="handleCreateCategory"
        @edit="handleEditCategory"
        @delete="handleDeleteCategory"
        @refresh="loadCategories"
      />
      <SidebarCollapse
        :collapsed="sidebarCollapsed"
        label="计划分类"
        @toggle="sidebarCollapsed = !sidebarCollapsed"
      />
      <Grid :key="listIdentity" class="min-w-0 flex-1" table-title="采集计划">
        <template #toolbar-tools>
          <TableAction
            :actions="[
              {
                label: $t('common.create'),
                type: 'primary',
                icon: ACTION_ICON.ADD,
                auth: ['dataforge:fill-plan:create'],
                onClick: handleCreate,
              },
            ]"
          />
        </template>
        <template #template="{ row }">
          <Tooltip :title="`模板版本编号 ${row.tableVersionId}`">
            {{ row.tableName }} · V{{ row.tableVersionNo }}
          </Tooltip>
        </template>
        <template #periodType="{ row }">
          <Tag>{{ PERIOD_TYPE_TEXT[row.periodType] ?? row.periodType }}</Tag>
        </template>
        <template #status="{ row }">
          <Tag :color="PLAN_STATUS_TEXT[row.status]?.color">
            {{ PLAN_STATUS_TEXT[row.status]?.text ?? row.status }}
          </Tag>
        </template>
        <template #actions="{ row }">
          <TableAction
            :actions="[
              {
                label: $t('common.edit'),
                type: 'link',
                icon: ACTION_ICON.EDIT,
                ifShow: row.status !== 1,
                auth: ['dataforge:fill-plan:update'],
                onClick: handleEdit.bind(null, row),
              },
              {
                label: '详情',
                type: 'link',
                icon: ACTION_ICON.VIEW,
                auth: ['dataforge:fill-plan:query'],
                onClick: handleDetail.bind(null, row),
              },
            ]"
            :drop-down-actions="[
              {
                label: '调整分类/人员',
                type: 'link',
                icon: ACTION_ICON.EDIT,
                ifShow: row.status === 1,
                auth: ['dataforge:fill-plan:update'],
                onClick: handleEdit.bind(null, row),
              },
              {
                label: '发布',
                type: 'link',
                icon: 'lucide:send',
                ifShow: row.status === 0 || row.status === 2,
                auth: ['dataforge:fill-plan:publish'],
                onClick: handlePublish.bind(null, row),
              },
              {
                label: '手工生成',
                type: 'link',
                icon: 'lucide:play',
                ifShow: row.status === 1,
                auth: ['dataforge:fill-task:create'],
                onClick: openGenerate.bind(null, row),
              },
              {
                label: '停用',
                type: 'link',
                danger: true,
                icon: 'lucide:square',
                ifShow: row.status === 1,
                auth: ['dataforge:fill-plan:publish'],
                onClick: handleDisable.bind(null, row),
              },
              {
                label: $t('common.delete'),
                type: 'link',
                danger: true,
                icon: ACTION_ICON.DELETE,
                ifShow: row.status !== 1,
                auth: ['dataforge:fill-plan:delete'],
                popConfirm: {
                  title: `确认删除计划「${row.name}」吗？仅草稿/停用状态可删除，删除不影响已生成的任务与数据。`,
                  confirm: handleDelete.bind(null, row),
                },
              },
            ]"
          />
        </template>
      </Grid>
    </div>
    <CategoryFormModal
      :key="`category:${identity}`"
      @success="loadCategories"
    />

    <DetailModal :key="`detail:${identity}`" />

    <Modal
      v-model:open="generateOpen"
      title="手工生成/补生成任务"
      width="800px"
      @ok="handleGenerate"
    >
      <p class="text-muted-foreground mb-2 text-sm">
        一次性计划：重新生成任务。周期计划：缺省按当前时间生成本期任务；
        补生成历史期次时填写对应历史时点。
      </p>
      <Input
        v-model:value="generateTime"
        placeholder="生成时点（可选），如 2026-07-01T08:00:00"
      />
    </Modal>
  </Page>
</template>

<style scoped>
@media (max-width: 767px) {
  .plan-layout > :deep(.sidebar-collapse) {
    flex: 0 0 12px;
    width: 100%;
    min-width: 0;
    height: 12px;
  }

  .plan-layout > :deep(.sidebar-collapse svg) {
    transform: rotate(90deg);
  }
}

@media (min-width: 641px) and (max-width: 767px) {
  .plan-layout > :deep(.sidebar-collapse .sidebar-collapse__button),
  .plan-layout
    > :deep(.sidebar-collapse.is-collapsed .sidebar-collapse__button) {
    top: -8px;
    right: 4px;
    left: auto;
    width: 24px;
    height: 24px;
  }
}
</style>
