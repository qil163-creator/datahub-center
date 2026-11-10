<script lang="ts" setup>
import type { AssetTagApi } from '#/api/asset/tag';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { useTabs } from '@vben/hooks';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Alert,
  Button,
  CheckboxGroup,
  Descriptions,
  DescriptionsItem,
  Dropdown,
  message,
  Spin,
  Table,
  Tabs,
  Tag,
} from 'antdv-next';

import { ASSET_TYPE_OPTIONS } from '#/api/asset/dataasset';
import {
  getAssetTag,
  getAssetTagLinkedAssets,
  unbindAssetTag,
} from '#/api/asset/tag';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import ListPagination from '#/components/list-pagination/index.vue';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import {
  createLatestWins,
  parseResourceId,
  resourceIdKey,
} from '#/utils/resource-id';
import { useBatchConfirm as useOwnedConfirm } from '#/views/dataforge/batchfile/use-confirm';

import { tagAsset, tagDate, tagNumber } from '../data';

import '../reference.css';
import '#/components/detail-fill-table.css';

defineOptions({ name: 'AssetTagDetail' });
const route = useRoute();
const router = useRouter();
const tabs = useTabs();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const identity = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const dialogActive = ref(true);
const confirm = useOwnedConfirm(
  computed(() =>
    JSON.stringify([identity.value, dialogActive.value, route.params.id]),
  ),
);
const canQuery = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes(['asset:tag:query']),
);
const canUnbind = computed(
  () => canQuery.value && hasAccessByCodes(['asset:tag:update']),
);
const tagId = computed(() => parseResourceId(route.params.id));
const tag = ref<AssetTagApi.Tag>({});
const rows = ref<AssetTagApi.LinkedAsset[]>([]);
const loading = ref(false);
const assetLoading = ref(false);
const errorText = ref('');
const assetError = ref('');
const activeTab = ref('assets');
const pagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const sort = reactive<{
  sortField: 'assetId' | 'createTime';
  sortOrder: 'asc' | 'desc';
}>({ sortField: 'createTime', sortOrder: 'desc' });
const detailGuard = createLatestWins();
const assetGuard = createLatestWins();
let writeEpoch = 0;
let alive = true;
let active = true;
const columns = [
  {
    title: '编号',
    key: 'assetId',
    width: 70,
    sorter: true,
    align: 'center' as const,
  },
  { title: '资产名称', key: 'assetName', width: 180 },
  { title: '描述', key: 'description', width: 230 },
  { title: '资产类目', key: 'categoryName', width: 160 },
  { title: '资产类型', key: 'assetType', width: 140, align: 'center' as const },
  { title: '主题名称', key: 'subjectDomainName', width: 150 },
  { title: '创建人', key: 'creatorName', width: 120, align: 'center' as const },
  {
    title: '创建时间',
    key: 'createTime',
    width: 180,
    sorter: true,
    align: 'center' as const,
  },
  {
    title: '操作',
    key: 'actions',
    width: 120,
    fixed: 'right' as const,
    align: 'center' as const,
  },
];
const visibleKeys = ref(columns.map((column) => column.key));
const visibleColumns = computed(() =>
  columns.filter((column) => visibleKeys.value.includes(column.key)),
);
async function loadAssets() {
  if (
    !alive ||
    !active ||
    !canQuery.value ||
    !tagId.value ||
    resourceIdKey(tag.value.id) !== tagId.value
  )
    return;
  const ticket = assetGuard.next();
  assetLoading.value = true;
  assetError.value = '';
  try {
    const page = await getAssetTagLinkedAssets({
      id: tagId.value,
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
      ...sort,
    });
    if (ticket.isCurrent()) {
      rows.value = page.list || [];
      pagination.total = page.total || 0;
    }
  } catch {
    if (ticket.isCurrent()) {
      rows.value = [];
      pagination.total = 0;
      assetError.value = '关联资产读取失败，请重试。';
    }
  } finally {
    if (ticket.isCurrent()) assetLoading.value = false;
  }
}
async function loadTag() {
  if (!alive || !active || !canQuery.value || !tagId.value) return;
  const id = tagId.value;
  const ticket = detailGuard.next();
  loading.value = true;
  errorText.value = '';
  try {
    const data = await getAssetTag(id);
    if (!ticket.isCurrent()) return;
    if (resourceIdKey(data.id) !== id) throw new Error('TAG_IDENTITY');
    tag.value = data;
    await loadAssets();
  } catch {
    if (ticket.isCurrent()) {
      tag.value = {};
      errorText.value = '标签详情读取失败，请重试。';
    }
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
function back() {
  void tabs.closeCurrentTab();
  void router.push({ path: String(route.meta.activePath || '/asset/tag') });
}
function removeAsset(row: AssetTagApi.LinkedAsset) {
  if (
    !active ||
    !canUnbind.value ||
    !parseResourceId(row.relationId) ||
    !Number.isInteger(row.relationRevision)
  )
    return;
  const subject = identity.value;
  const epoch = writeEpoch;
  const target = tagId.value;
  confirm({
    wrapClassName: 'qdata-tag-confirm',
    centered: true,
    width: 420,
    okText: '确定',
    cancelText: '取消',
    title: '系统提示',
    content: `是否确认删除为"${row.sourceRecordId || row.assetId}"的数据项？`,
    onOk: async () => {
      if (
        !active ||
        epoch !== writeEpoch ||
        subject !== identity.value ||
        target !== tagId.value ||
        !canUnbind.value
      )
        return;
      await unbindAssetTag(row.relationId, row.relationRevision);
      if (
        alive &&
        active &&
        epoch === writeEpoch &&
        subject === identity.value &&
        target === tagId.value
      ) {
        message.success('删除成功');
        await loadTag();
      }
    },
  });
}
function tableChange(
  _page: unknown,
  _filter: unknown,
  sorter:
    | { columnKey?: number | string; order?: null | string }
    | { columnKey?: number | string; order?: null | string }[],
) {
  const value = Array.isArray(sorter) ? sorter[0] : sorter;
  if (value?.columnKey === 'assetId' || value?.columnKey === 'createTime') {
    sort.sortField = value.columnKey;
    sort.sortOrder = value.order === 'ascend' ? 'asc' : 'desc';
    pagination.pageNo = 1;
    void loadAssets();
  }
}
function clear() {
  detailGuard.invalidate();
  assetGuard.invalidate();
  writeEpoch++;
  tag.value = {};
  rows.value = [];
  pagination.total = 0;
  loading.value = false;
  assetLoading.value = false;
  errorText.value = '';
  assetError.value = '';
  activeTab.value = 'assets';
}
watch(
  [identity, tagId],
  () => {
    clear();
    pagination.pageNo = 1;
    if (tagId.value) void loadTag();
  },
  { immediate: true, flush: 'sync' },
);
onActivated(() => {
  if (!active) {
    active = true;
    dialogActive.value = true;
    void loadTag();
  }
});
onDeactivated(() => {
  active = false;
  dialogActive.value = false;
  clear();
});
onBeforeUnmount(() => {
  active = false;
  dialogActive.value = false;
  alive = false;
  clear();
});
</script>
<template>
  <Page
    auto-content-height
    content-class="flex min-h-0 flex-col overflow-hidden"
  >
    <div class="qdata-tag tag-detail">
      <Alert
        v-if="!canQuery || !tagId"
        message="当前登录身份无查询权限或标签编号无效。"
        type="warning"
        show-icon
      />
      <Alert v-else-if="errorText" :message="errorText" type="error">
        <template #action>
          <Button @click="loadTag">重试</Button>
        </template>
      </Alert>
      <template v-else>
        <Spin :spinning="loading">
          <section class="tag-summary">
            <header>
              <div>
                <span class="tag-summary__number">{{ tagNumber(tag) }}</span
                ><strong>{{ tag.name || '-' }}</strong
                ><Tag
                  v-if="tag.status !== undefined"
                  class="tag-summary__status"
                  :class="[{ 'is-enabled': tag.status === 0 }]"
                >
                  {{ tag.status === 0 ? '启用' : '禁用' }}
                </Tag>
              </div>
              <DetailBackButton @click="back" />
            </header>
            <Descriptions :column="3" bordered size="small">
              <DescriptionsItem label="标签类目">
                {{ tag.categoryName || '-' }} </DescriptionsItem
              ><DescriptionsItem label="近义词">
                {{ tag.nearSynonyms || '-' }} </DescriptionsItem
              ><DescriptionsItem label="同义词">
                {{ tag.synonyms || '-' }} </DescriptionsItem
              ><DescriptionsItem label="描述" :span="3">
                {{ tag.description || '-' }}
              </DescriptionsItem>
            </Descriptions>
          </section>
        </Spin>
        <section
          class="tag-detail__body"
          :class="{ 'tag-detail__body--assets': activeTab === 'assets' }"
        >
          <Tabs
            v-model:active-key="activeTab"
            :items="[
              { key: 'assets', label: '资产信息' },
              { key: 'detail', label: '详细信息' },
            ]"
          />
          <template v-if="activeTab === 'assets'">
            <div class="tag-detail__toolbar">
              <Button aria-label="刷新关联资产" @click="loadAssets">
                <span
                  class="ref-icon"
                  :style="{ maskImage: `url(${tagAsset('refresh.svg')})` }"
                ></span> </Button
              ><Dropdown :trigger="['click']">
                <Button aria-label="选择显示列">
                  <span
                    class="ref-icon"
                    :style="{ maskImage: `url(${tagAsset('columns.svg')})` }"
                  ></span> </Button
                ><template #popupRender>
                  <div class="tag-column-picker">
                    <CheckboxGroup
                      v-model:value="visibleKeys"
                      :options="
                        columns.map((column) => ({
                          label: column.title,
                          value: column.key,
                        }))
                      "
                    />
                  </div>
                </template>
              </Dropdown>
            </div>
            <Alert v-if="assetError" :message="assetError" type="error">
              <template #action>
                <Button size="small" @click="loadAssets"> 重试 </Button>
              </template>
            </Alert>
            <Table
              class="tag-table tag-detail__assets detail-fill-table"
              :columns="visibleColumns"
              :data-source="rows"
              :loading="assetLoading"
              :row-key="
                (row: AssetTagApi.LinkedAsset) => resourceIdKey(row.relationId)
              "
              :pagination="false"
              :scroll="{ x: 1350 }"
              @change="tableChange"
            >
              <template #bodyCell="{ column, record }">
                <span v-if="column.key === 'assetId'">{{
                  record.sourceRecordId || record.assetId
                }}</span
                ><TextEllipsisCell
                  v-else-if="column.key === 'description'"
                  class="tag-description"
                  :content="record.description || '-'"
                  :line="2"
                  center
                /><Tag
                  v-else-if="column.key === 'assetType'"
                  class="tag-category"
                >
                  {{
                    ASSET_TYPE_OPTIONS.find(
                      (item) => item.value === record.assetType,
                    )?.label || '-'
                  }} </Tag
                ><span v-else-if="column.key === 'createTime'">{{
                  tagDate(record.createTime)
                }}</span
                ><Button
                  v-else-if="column.key === 'actions' && canUnbind"
                  type="link"
                  danger
                  @click="removeAsset(record)"
                >
                  <span
                    class="ref-icon"
                    :style="{ maskImage: `url(${tagAsset('delete.svg')})` }"
                  ></span
                  >删除 </Button
                ><span v-else>{{
                  column.key
                    ? record[column.key as keyof AssetTagApi.LinkedAsset] || '-'
                    : '-'
                }}</span>
              </template>
            </Table>
            <footer class="tag-pagination">
              <ListPagination
                :current="pagination.pageNo"
                :page-size="pagination.pageSize"
                :total="pagination.total"
                :page-size-options="[10, 20, 50, 100]"
                @change="
                  (pageNo: number, pageSize: number) => {
                    pagination.pageNo = pageNo;
                    pagination.pageSize = pageSize;
                    void loadAssets();
                  }
                "
              />
            </footer>
          </template>
          <Descriptions
            v-else
            :column="2"
            bordered
            size="small"
            class="tag-detail__audit"
          >
            <DescriptionsItem label="创建人">
              {{
                tag.creatorName || tag.sourceCreatorName || tag.creator || '-'
              }} </DescriptionsItem
            ><DescriptionsItem label="创建时间">
              {{ tagDate(tag.createTime) }} </DescriptionsItem
            ><DescriptionsItem label="更新人">
              {{
                tag.updaterName || tag.sourceUpdaterName || tag.updater || '-'
              }} </DescriptionsItem
            ><DescriptionsItem label="更新时间">
              {{ tagDate(tag.updateTime) }}
            </DescriptionsItem>
          </Descriptions>
        </section>
      </template>
    </div>
  </Page>
</template>
<style scoped>
.tag-detail__assets :deep(.ant-table-container) {
  overflow: hidden;
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

.tag-detail__assets :deep(.ant-table-thead > tr > th) {
  padding-block: 6px;
  color: hsl(var(--foreground));
  background: hsl(var(--accent));
  border-bottom: 1px solid hsl(var(--border));
}

.tag-detail__assets :deep(.ant-table-thead > tr > th:not(:last-child)::before) {
  background-color: hsl(var(--border));
}

.tag-detail {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
}

.tag-summary {
  padding: 15px;
  background: hsl(var(--background));
  border-radius: var(--radius);
  box-shadow: 0 2px 12px #00000008;
}

.tag-summary header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 15px;
}

.tag-summary header > div {
  display: flex;
  gap: 8px;
  align-items: center;
}

.tag-summary header strong {
  font-size: 16px;
  font-weight: 600;
  color: hsl(var(--foreground));
}

.tag-summary__number {
  min-width: 20px;
  height: 20px;
  padding: 0 4px;
  line-height: 20px;
  color: hsl(var(--primary-foreground));
  text-align: center;
  background: hsl(var(--primary));
}

.tag-summary__status {
  margin: 0;
  font-size: 14px;
  line-height: 22px;
  color: hsl(var(--destructive));
  background: hsl(var(--destructive) / 10%);
  border: 0;
}

.tag-summary__status.is-enabled {
  color: #00a52b;
  background: #e5f5e8;
}

.tag-summary :deep(.ant-descriptions-item-label) {
  width: 150px;
}

.tag-summary :deep(.ant-descriptions-view table),
.tag-detail__audit :deep(.ant-descriptions-view table) {
  width: 100%;
  table-layout: fixed;
}

.tag-summary
  :deep(.ant-descriptions-row:first-child > .ant-descriptions-item-content) {
  width: calc((100% - 450px) / 3);
}

.tag-detail__audit
  :deep(.ant-descriptions-row:first-child > .ant-descriptions-item-content) {
  width: calc((100% - 400px) / 2);
}

.tag-detail__body {
  flex: 1;
  min-height: 0;
  padding: 10px 15px 15px;
  overflow: auto;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.tag-detail__body--assets {
  display: flex;
  flex-direction: column;
}

.tag-detail__body--assets .tag-detail__assets {
  flex: 1 1 0%;
}

.tag-detail__body :deep(.ant-tabs-nav) {
  margin-bottom: 14px;
}

.tag-detail__toolbar {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  margin-bottom: 14px;
}

.tag-detail__audit :deep(.ant-descriptions-item-label) {
  width: 200px;
}
</style>

<style scoped src="../../table-font.css"></style>
