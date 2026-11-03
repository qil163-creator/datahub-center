<script lang="ts" setup>
import type { FoundationDwLayerApi } from '#/api/modeling/dwlayer';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  ref,
  watch,
} from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { confirm, Page, useVbenDrawer, useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Alert,
  Button,
  Dropdown,
  Empty,
  Input,
  Menu,
  message,
  Select,
  Skeleton,
  Table,
  Tag,
} from 'antdv-next';

import { ACTION_ICON, TableAction } from '#/adapter/vxe-table';
import {
  deleteDwLayer,
  getDwLayerList,
  updateDwLayerStatus,
} from '#/api/modeling/dwlayer';
import { useObjectRequest } from '#/hooks/use-object-request';
import {
  DASHBOARD_TARGETS,
  useDashboardNav,
} from '#/views/dashboard/shared/route-resolver';

import { getCategoryCreateDefaults, getLayerCreateDefaults } from './data';
import Architecture from './modules/architecture.vue';
import Detail from './modules/detail.vue';
import Form from './modules/form.vue';

defineOptions({ name: 'DwLayer' });

const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const { resolve } = useDashboardNav();
const router = useRouter();
const rows = ref<FoundationDwLayerApi.DwLayer[]>([]);
const loading = ref(false);
const failed = ref(false);
const active = ref(true);
let contextRevision = 0;
const keyword = ref('');
const status = ref<number>();
const categoryId = ref<string>();
const busyIds = ref(new Set<string>());
const scope = computed(() =>
  JSON.stringify([
    user.userInfo?.userId,
    user.userRoles,
    access.accessCodes,
    access.accessToken,
    access.refreshToken,
  ]),
);

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});
const [DetailDrawer, detailDrawerApi] = useVbenDrawer({
  connectedComponent: Detail,
  destroyOnClose: true,
});
const [CategoryDrawer, categoryDrawerApi] = useVbenDrawer({
  title: '分层分类管理',
  showConfirmButton: false,
  cancelText: '关闭',
});

function resetLayers() {
  rows.value = [];
  busyIds.value = new Set();
  loading.value = false;
  failed.value = false;
}
const requests = useObjectRequest(() => scope.value, resetLayers);

function closeOverlays() {
  formModalApi.close?.();
  detailDrawerApi.close?.();
  categoryDrawerApi.close?.();
}

async function loadLayers() {
  const current = requests.begin();
  if (!active.value || !access.accessToken) {
    resetLayers();
    return;
  }
  loading.value = true;
  failed.value = false;
  try {
    const result = await getDwLayerList({});
    if (current()) {
      rows.value = result;
      if (
        categoryId.value &&
        !result.some((row) => String(row.id) === categoryId.value)
      )
        categoryId.value = undefined;
    }
  } catch {
    if (current()) {
      rows.value = [];
      failed.value = true;
    }
  } finally {
    if (current()) loading.value = false;
  }
}

watch(
  scope,
  (_, previousScope) => {
    contextRevision += 1;
    keyword.value = '';
    status.value = undefined;
    categoryId.value = undefined;
    if (previousScope !== undefined) closeOverlays();
    void loadLayers();
  },
  { immediate: true, flush: 'sync' },
);
onDeactivated(() => {
  contextRevision += 1;
  active.value = false;
  requests.begin();
  closeOverlays();
  resetLayers();
});
onBeforeUnmount(() => {
  active.value = false;
  contextRevision += 1;
});
onActivated(() => {
  if (!active.value) {
    active.value = true;
    void loadLayers();
  }
});

const categories = computed(() =>
  rows.value
    .filter((row) => row.nodeType === 'CATEGORY')
    .toSorted((left, right) => (left.sortOrder ?? 0) - (right.sortOrder ?? 0)),
);
const layers = computed(() =>
  rows.value
    .filter((row) => row.nodeType === 'LAYER')
    .toSorted((left, right) => (left.sortOrder ?? 0) - (right.sortOrder ?? 0)),
);
const visibleLayers = computed(() => {
  const text = keyword.value.trim().toLowerCase();
  return layers.value.filter(
    (row) =>
      (!categoryId.value || String(row.parentId) === categoryId.value) &&
      (status.value === undefined || row.status === status.value) &&
      (!text ||
        [row.layerName, row.layerCode, row.description].some((value) =>
          value?.toLowerCase().includes(text),
        )),
  );
});
const categoryOptions = computed(() =>
  categories.value.map((row) => ({
    label: row.layerName,
    value: String(row.id),
  })),
);
const modelPath = computed(() =>
  hasAccessByCodes(['modeling:model:query'])
    ? resolve(DASHBOARD_TARGETS.logicModel)
    : undefined,
);
const statusOptions = [
  { label: '启用', value: CommonStatusEnum.ENABLE },
  { label: '停用', value: CommonStatusEnum.DISABLE },
];
const categoryColumns = [
  { title: '分类名称', dataIndex: 'layerName', key: 'layerName' },
  { title: '状态', key: 'status', width: 80 },
  { title: '分层数量', key: 'layers', width: 90 },
  { title: '负责人', dataIndex: 'ownerName', key: 'ownerName', width: 120 },
  { title: '操作', key: 'action', width: 250, fixed: 'right' as const },
];

function parentName(row: FoundationDwLayerApi.DwLayer) {
  return (
    categories.value.find(
      (category) => String(category.id) === String(row.parentId),
    )?.layerName ?? '未分类'
  );
}
function modelCount(row: FoundationDwLayerApi.DwLayer) {
  return row.logicModelCountAvailable &&
    row.logicModelCount !== null &&
    row.logicModelCount !== undefined
    ? BigInt(row.logicModelCount).toLocaleString('zh-CN')
    : '—';
}
function handleCreate() {
  formModalApi
    .setData({ createMode: 'category', ...getCategoryCreateDefaults() })
    .open();
}
function handleAppend(row: FoundationDwLayerApi.DwLayer) {
  formModalApi
    .setData({
      createMode: 'layer',
      parentLayerName: row.layerName,
      ...getLayerCreateDefaults(row),
    })
    .open();
}
function handleEdit(row: FoundationDwLayerApi.DwLayer) {
  formModalApi.setData({ id: row.id }).open();
}
function handleDetail(row: FoundationDwLayerApi.DwLayer) {
  detailDrawerApi.setData({ id: row.id, parentName: parentName(row) }).open();
}
function viewModels(row: FoundationDwLayerApi.DwLayer) {
  if (modelPath.value)
    void router.push({
      path: modelPath.value,
      query: { layerId: String(row.id) },
    });
}
function resetFilters() {
  keyword.value = '';
  categoryId.value = undefined;
  status.value = undefined;
}

async function handleDelete(row: FoundationDwLayerApi.DwLayer) {
  const context = contextRevision;
  const key = String(row.id);
  if (busyIds.value.has(key)) return;
  busyIds.value.add(key);
  try {
    await deleteDwLayer(row.id);
    if (active.value && context === contextRevision) {
      message.success('删除成功');
      await loadLayers();
    }
  } finally {
    if (context === contextRevision) busyIds.value.delete(key);
  }
}

async function handleStatus(row: FoundationDwLayerApi.DwLayer) {
  const context = contextRevision;
  const key = String(row.id);
  if (busyIds.value.has(key)) return;
  const next =
    row.status === CommonStatusEnum.ENABLE
      ? CommonStatusEnum.DISABLE
      : CommonStatusEnum.ENABLE;
  const text = next === CommonStatusEnum.ENABLE ? '启用' : '停用';
  try {
    await confirm(`确认${text}「${row.layerName ?? ''}」吗？`);
  } catch {
    return;
  }
  if (context !== contextRevision || !active.value) return;
  busyIds.value.add(key);
  try {
    await updateDwLayerStatus(row.id, next);
    if (active.value && context === contextRevision) {
      message.success(`${text}成功`);
      await loadLayers();
    }
  } finally {
    if (context === contextRevision) busyIds.value.delete(key);
  }
}
</script>

<template>
  <Page auto-content-height content-class="bg-background-deep">
    <FormModal @success="loadLayers" />
    <DetailDrawer />
    <div class="warehouse-page">
      <Architecture />
      <section
        class="warehouse-configuration"
        aria-labelledby="configuration-title"
      >
        <header class="configuration-heading">
          <div>
            <h2 id="configuration-title">分层配置</h2>
            <span>维护分层名称、说明、负责人及表前缀，查看各层关联的真实逻辑模型。</span>
          </div>
        </header>
        <div class="configuration-toolbar">
          <div class="configuration-filters">
            <Input
              v-model:value="keyword"
              allow-clear
              placeholder="搜索分层名称、编码或说明"
              class="layer-search"
            >
              <template #prefix>
                <IconifyIcon icon="lucide:search" />
              </template>
</Input><Select
              v-model:value="categoryId"
              :options="categoryOptions"
              allow-clear
              placeholder="全部分类"
              class="category-filter"
            /><Select
              v-model:value="status"
              :options="statusOptions"
              allow-clear
              placeholder="全部状态"
              class="status-filter"
            /><Button
              v-if="keyword || categoryId || status !== undefined"
              type="link"
              @click="resetFilters"
            >
              重置
            </Button>
          </div>
          <div class="configuration-actions">
            <Button
              :loading="loading"
              aria-label="刷新分层"
              @click="loadLayers"
            >
              <template #icon>
                <IconifyIcon icon="lucide:refresh-cw" />
              </template>
</Button><Button @click="categoryDrawerApi.open()"><template #icon><IconifyIcon icon="lucide:folder-tree" /></template>分类管理</Button><Dropdown
              v-if="hasAccessByCodes(['modeling:dwlayer:create'])"
              :trigger="['click']"
              :disabled="categories.length === 0"
            >
              <Button type="primary" :disabled="categories.length === 0">
                <template #icon><IconifyIcon icon="lucide:plus" /></template>新增
</Button><template #popupRender>
                <Menu>
                  <Menu.Item
                    v-for="category in categories"
                    :key="String(category.id)"
                    @click="handleAppend(category)"
                  >
                    <template #icon>
                      <IconifyIcon icon="lucide:plus" />
                    </template>
                    在「{{ category.layerName }}」下新增
                  </Menu.Item>
                </Menu>
              </template>
            </Dropdown>
          </div>
        </div>
      </section>
      <Alert
        v-if="failed"
        type="error"
        show-icon
        message="分层配置加载失败"
        description="请检查网络后重试，未加载的数据不会显示为零。"
      >
        <template #action>
          <Button size="small" @click="loadLayers">
            <template #icon>
              <IconifyIcon icon="lucide:refresh-cw" />
            </template>
            重试
          </Button>
        </template>
      </Alert>
      <div v-if="loading" class="layer-grid">
        <div
          v-for="item in 6"
          :key="item"
          class="layer-card layer-card--loading"
        >
          <Skeleton active :paragraph="{ rows: 4 }" />
        </div>
      </div>
      <div
        v-else-if="!failed && visibleLayers.length === 0"
        class="warehouse-empty"
      >
        <Empty
          :description="
            layers.length ? '没有符合筛选条件的分层' : '暂无分层配置'
          "
        /><Button v-if="layers.length" @click="resetFilters">重置筛选</Button><Button
          v-else-if="hasAccessByCodes(['modeling:dwlayer:create'])"
          type="primary"
          @click="categoryDrawerApi.open()"
        >
<template #icon><IconifyIcon icon="lucide:plus" /></template>
          创建分类与分层
        </Button>
      </div>
      <div v-else-if="!failed" class="layer-grid">
        <article
          v-for="row in visibleLayers"
          :key="String(row.id)"
          class="layer-card"
        >
          <header class="layer-card__heading">
            <div>
              <strong>{{ row.layerCode || '—' }}</strong><span class="layer-card__separator">|</span>
              <h3>{{ row.layerName }}</h3>
            </div>
            <Tag
              v-if="row.status !== CommonStatusEnum.ENABLE"
              class="layer-status"
            >
              停用
</Tag><span v-else class="layer-category" :title="parentName(row)">{{
              parentName(row)
            }}</span>
          </header>
          <p class="layer-card__description" :title="row.description">
            {{
              row.description ||
              '暂无分层说明，可在修改中完善此分层的职责与使用约定。'
            }}
          </p>
          <div class="layer-card__metrics">
            <div>
              <span class="layer-metric-icon"><IconifyIcon icon="lucide:boxes" /></span>
              <div>
                <strong
                  :title="
                    row.logicModelCountAvailable
                      ? String(row.logicModelCount ?? '')
                      : '暂无逻辑模型查询权限'
                  "
                  >{{ modelCount(row)
                  }}<small v-if="row.logicModelCountAvailable">
                    个</small></strong><span>逻辑模型数</span>
              </div>
            </div>
            <div>
              <span class="layer-metric-icon"><IconifyIcon icon="lucide:clipboard-list" /></span>
              <div>
                <strong :title="row.tablePrefix">{{
                  row.tablePrefix || '—'
                }}</strong><span>表前缀</span>
              </div>
            </div>
            <div>
              <span class="layer-metric-icon"><IconifyIcon icon="lucide:user-round" /></span>
              <div>
                <strong :title="row.ownerName ?? undefined">{{
                  row.ownerName ||
                  (row.ownerUserId ? `用户 ${row.ownerUserId}` : '未设置')
                }}</strong><span>负责人</span>
              </div>
            </div>
          </div>
          <footer class="layer-card__actions">
            <TableAction
              :actions="[
                {
                  label: '查看模型',
                  type: 'link',
                  icon: 'lucide:file-text',
                  auth: ['modeling:model:query'],
                  ifShow: !!modelPath && row.logicModelCountAvailable,
                  onClick: () => viewModels(row),
                },
                {
                  label: '详情',
                  type: 'link',
                  icon: ACTION_ICON.VIEW,
                  auth: ['modeling:dwlayer:query'],
                  onClick: () => handleDetail(row),
                },
                {
                  label: '修改',
                  type: 'link',
                  icon: ACTION_ICON.EDIT,
                  auth: ['modeling:dwlayer:update'],
                  disabled: busyIds.has(String(row.id)),
                  onClick: () => handleEdit(row),
                },
              ]"
              :drop-down-actions="[
                {
                  label:
                    row.status === CommonStatusEnum.ENABLE ? '停用' : '启用',
                  icon: row.status === CommonStatusEnum.ENABLE ? 'lucide:circle-off' : 'lucide:circle-check',
                  auth: ['modeling:dwlayer:update'],
                  disabled: busyIds.has(String(row.id)),
                  onClick: () => handleStatus(row),
                },
                {
                  label: '删除',
                  icon: ACTION_ICON.DELETE,
                  danger: true,
                  auth: ['modeling:dwlayer:delete'],
                  ifShow: row.isBuiltin !== 1,
                  disabled: busyIds.has(String(row.id)),
                  popConfirm: {
                    title: `确认删除「${row.layerName}」？`,
                    confirm: () => handleDelete(row),
                  },
                },
              ]"
            />
          </footer>
        </article>
      </div>
      <p v-if="!failed && !loading" class="warehouse-summary">
        共 {{ layers.length }} 个分层，当前显示 {{ visibleLayers.length }} 个
      </p>
    </div>
    <CategoryDrawer class="w-[960px] max-w-full">
      <div class="category-drawer-toolbar">
        <p>通过分类组织内置与自定义分层。内置节点不支持删除。</p>
        <Button
          v-access:code="'modeling:dwlayer:create'"
          type="primary"
          @click="handleCreate"
        >
<template #icon><IconifyIcon icon="lucide:plus" /></template>
          新增分类
        </Button>
      </div>
      <Table
        :columns="categoryColumns"
        :data-source="categories"
        :pagination="false"
        :loading="loading"
        :row-key="(row: FoundationDwLayerApi.DwLayer) => String(row.id)"
        :scroll="{ x: 700 }"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <Tag
              :color="
                record.status === CommonStatusEnum.ENABLE
                  ? 'success'
                  : 'default'
              "
            >
              {{ record.status === CommonStatusEnum.ENABLE ? '启用' : '停用' }}
            </Tag>
</template><template v-else-if="column.key === 'layers'">
            {{
              layers.filter((row) => String(row.parentId) === String(record.id))
                .length
            }}
</template><template v-else-if="column.key === 'action'">
            <TableAction
              :actions="[
                {
                  label: '新增分层',
                  icon: ACTION_ICON.ADD,
                  type: 'link',
                  auth: ['modeling:dwlayer:create'],
                  onClick: () => handleAppend(record),
                },
                {
                  label: '修改',
                  icon: ACTION_ICON.EDIT,
                  type: 'link',
                  auth: ['modeling:dwlayer:update'],
                  disabled: busyIds.has(String(record.id)),
                  onClick: () => handleEdit(record),
                },
              ]"
              :drop-down-actions="[
                {
                  label:
                    record.status === CommonStatusEnum.ENABLE ? '停用' : '启用',
                  icon: record.status === CommonStatusEnum.ENABLE ? 'lucide:circle-off' : 'lucide:circle-check',
                  auth: ['modeling:dwlayer:update'],
                  disabled: busyIds.has(String(record.id)),
                  onClick: () => handleStatus(record),
                },
                {
                  label: '删除',
                  icon: ACTION_ICON.DELETE,
                  danger: true,
                  auth: ['modeling:dwlayer:delete'],
                  ifShow: record.isBuiltin !== 1,
                  disabled: busyIds.has(String(record.id)),
                  popConfirm: {
                    title: `确认删除分类「${record.layerName}」？`,
                    confirm: () => handleDelete(record),
                  },
                },
              ]"
            />
          </template>
        </template>
      </Table>
    </CategoryDrawer>
  </Page>
</template>

<style scoped>
.warehouse-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 100%;
  background: hsl(var(--background-deep));
}

.warehouse-configuration {
  background: hsl(var(--card));
  border-radius: 4px;
}

.configuration-heading {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 16px 18px;
}

.configuration-heading > div {
  display: flex;
  gap: 12px;
  align-items: center;
}

.configuration-heading h2 {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0;
  font-size: 16px;
  font-weight: 500;
  white-space: nowrap;
}

.configuration-heading h2::before {
  width: 4px;
  height: 14px;
  content: '';
  background: #4966ff;
  border-radius: 4px;
}

.configuration-heading span {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.configuration-toolbar {
  display: flex;
  gap: 14px;
  align-items: center;
  justify-content: space-between;
  padding: 0 18px 14px;
}

.configuration-filters,
.configuration-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.configuration-filters {
  flex-wrap: wrap;
}

.configuration-actions {
  flex-shrink: 0;
}

.layer-search {
  width: 240px;
}

.category-filter {
  width: 150px;
}

.status-filter {
  width: 110px;
}

.layer-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.layer-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border) / 35%);
  border-radius: 4px;
}

.layer-card--loading {
  min-height: 225px;
  padding: 20px;
}

.layer-card__heading {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: linear-gradient(
    90deg,
    color-mix(in srgb, hsl(var(--card)) 96%, #4966ff),
    hsl(var(--card))
  );
}

.layer-card__heading > div {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
}

.layer-card__heading strong {
  font-size: 18px;
  font-weight: 600;
  color: #4966ff;
}

.layer-card__separator {
  color: #b6bfd7;
}

.layer-card__heading h3 {
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 14px;
  font-weight: 500;
  color: hsl(var(--foreground));
  white-space: nowrap;
}

.layer-category {
  max-width: 25%;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
  white-space: nowrap;
}

.layer-status {
  flex-shrink: 0;
  margin: 0;
  font-size: 12px;
}

.layer-card__description {
  display: -webkit-box;
  min-height: 54px;
  padding: 8px 16px 0;
  margin: 0;
  overflow: hidden;
  -webkit-line-clamp: 2;
  font-size: 12px;
  line-height: 1.8;
  color: hsl(var(--muted-foreground));
  -webkit-box-orient: vertical;
}

.layer-card__metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  padding: 20px 16px;
}

.layer-card__metrics > div {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
}

.layer-metric-icon {
  display: grid;
  flex-shrink: 0;
  place-items: center;
  width: 29px;
  height: 29px;
  font-size: 17px;
  color: #5e79fa;
  background: color-mix(in srgb, hsl(var(--card)) 95%, #4966ff);
  border-radius: 4px;
}

.layer-card__metrics > div > div {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.layer-card__metrics strong {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 14px;
  font-weight: 400;
  white-space: nowrap;
}

.layer-card__metrics small {
  font-size: 12px;
}

.layer-card__metrics div > span:not(.layer-metric-icon) {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.layer-card__actions {
  display: flex;
  justify-content: flex-end;
  padding: 7px 12px;
  margin-top: auto;
  border-top: 1px solid hsl(var(--border) / 40%);
}

.layer-card__actions :deep(.ant-btn) {
  font-size: 14px;
}

.warehouse-empty {
  display: flex;
  flex-direction: column;
  gap: 15px;
  align-items: center;
  padding: 45px 20px;
  background: hsl(var(--card));
  border-radius: 4px;
}

.warehouse-summary {
  margin: 0;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
  text-align: right;
}

.category-drawer-toolbar {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}

.category-drawer-toolbar p {
  margin: 0;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

@media (max-width: 1250px) {
  .layer-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .configuration-toolbar {
    flex-wrap: wrap;
  }
}

@media (max-width: 720px) {
  .configuration-heading > div {
    flex-direction: column;
    gap: 6px;
    align-items: flex-start;
  }

  .layer-grid {
    grid-template-columns: 1fr;
  }

  .configuration-filters {
    width: 100%;
  }

  .layer-search {
    width: 100%;
  }

  .category-filter {
    flex: 1;
  }

  .configuration-actions {
    margin-left: auto;
  }

  .layer-card__metrics {
    padding: 18px 16px;
  }
}
</style>
