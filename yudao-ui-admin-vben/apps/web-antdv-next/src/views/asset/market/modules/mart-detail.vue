<script lang="ts" setup>
import type { DataAssetApi } from '#/api/asset/dataasset';
import type { MarketApi } from '#/api/asset/market';
import type { MyAssetsApi } from '#/api/asset/my-assets';

import { computed, h, onBeforeUnmount, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, Spin, Table, Tag } from 'antdv-next';

import {
  checkMarketAccess,
  getMarketResourceDetail,
  previewMarketAsset,
} from '#/api/asset/market';
import { getMyAssetApiDocument } from '#/api/asset/my-assets';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import {
  createLatestWins,
  parseResourceId,
  sameResourceId,
} from '#/utils/resource-id';
import { formatMarketTime } from '#/views/market/market-time';

import { deliveryLabels } from '../data';
import ApplyForm from './mart-apply-form.vue';

defineOptions({ name: 'AssetMarketMartDetail' });
defineProps<{ embedded?: boolean }>();
const emit = defineEmits<{ back: [] }>();
const { hasAccessByCodes } = useAccess();
const canApply = computed(() => hasAccessByCodes(['asset:market:apply']));
const [ApplyModal, applyApi] = useVbenModal({
  connectedComponent: ApplyForm,
  destroyOnClose: true,
});
const access = useAccessStore();
const user = useUserStore();
const current = ref<MarketApi.Resource>();
const detail = ref<MarketApi.ConsumerDetail>();
const activeTab = ref('fields');
const detailLoading = ref(false);
const contentLoading = ref(false);
const detailError = ref('');
const contentError = ref('');
const permitted = ref(false);
const preview = ref<DataAssetApi.Preview>();
const apiDocument = ref<MyAssetsApi.ApiDocument>();
const guard = createLatestWins();
const contentGuard = createLatestWins();
let alive = true;
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
const deliveries = computed(() =>
  (current.value?.deliveryTypes ?? []).map((value) => ({
    value,
    label: deliveryLabels([value])[0],
  })),
);
const typeLabel = computed(
  () =>
    ({ 1: '数据库表', 2: 'API接口', 3: '文件' })[
      current.value?.assetType ?? 0
    ] || '-',
);
const tabs = computed(() => {
  const types = current.value?.deliveryTypes?.length
    ? current.value.deliveryTypes
    : [current.value?.assetType];
  const values: { key: string; label: string }[] = [];
  if (types.includes(1))
    values.push(
      { key: 'fields', label: '资产字段' },
      { key: 'preview', label: '数据预览' },
    );
  if (types.includes(2)) values.push({ key: 'api', label: 'API接口文档' });
  if (types.includes(3)) values.push({ key: 'file', label: '文件' });
  return values;
});
const authorizationLabel = computed(() => {
  if (permitted.value) return '已授权';
  return current.value?.myApplyStatus === 1 ? '待授权' : '未授权';
});
function applyAsset() {
  const asset = current.value;
  if (
    !alive ||
    !canApply.value ||
    !parseResourceId(asset?.id) ||
    asset?.publicationNotice ||
    permitted.value ||
    asset?.myApplyStatus === 1
  )
    return;
  applyApi
    .setData({
      assetId: asset?.id,
      assetName: asset?.marketName || asset?.assetName,
    })
    .open();
}
function onApplied() {
  const id = parseResourceId(current.value?.id);
  if (id) void open(id);
}
const previewColumns = computed(() =>
  (preview.value?.columns ?? []).map((column) => ({
    key: column.name,
    dataIndex: column.name,
    title: column.label || column.name,
    width: 160,
    ellipsis: true,
  })),
);
const fieldColumns = [
  { title: '序号', key: 'index', width: 70 },
  { title: '字段名称', dataIndex: 'columnName', key: 'columnName' },
  { title: '字段描述', dataIndex: 'businessName', key: 'businessName' },
  { title: '字段类型', dataIndex: 'dataType', key: 'dataType', width: 180 },
  {
    title: '是否为空',
    dataIndex: 'nullableFlag',
    key: 'nullableFlag',
    width: 120,
  },
];
const apiColumns = [
  { title: '参数名称', dataIndex: 'name', key: 'name' },
  { title: '类型', dataIndex: 'type', key: 'type' },
  {
    title: '说明',
    dataIndex: 'description',
    key: 'description',
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
];
function close() {
  guard.invalidate();
  contentGuard.invalidate();
  applyApi.setState({ isOpen: false });
  current.value = undefined;
  detail.value = undefined;
  preview.value = undefined;
  apiDocument.value = undefined;
  permitted.value = false;
  detailLoading.value = false;
  contentLoading.value = false;
  detailError.value = '';
  contentError.value = '';
  activeTab.value = 'fields';
}
async function open(assetId: MarketApi.Id) {
  const id = parseResourceId(assetId);
  if (
    !alive ||
    !access.accessToken ||
    access.loginExpired ||
    !parseResourceId(user.userInfo?.userId) ||
    !id
  )
    return;
  close();
  const ticket = guard.next();
  detailLoading.value = true;
  try {
    const value = await getMarketResourceDetail(id);
    if (!ticket.isCurrent()) return;
    if (!sameResourceId(value.asset?.id, id))
      throw new Error('RESOURCE_IDENTITY');
    current.value = value.asset;
    activeTab.value = tabs.value[0]?.key || 'fields';
    detail.value = value;
    const allowed = await checkMarketAccess(id);
    if (ticket.isCurrent())
      permitted.value = allowed === true && value.asset?.usable !== false;
  } catch {
    if (ticket.isCurrent())
      detailError.value = current.value
        ? '授权状态读取失败，请重新打开详情。'
        : '详情读取失败或资产身份不一致，请重试。';
  } finally {
    if (ticket.isCurrent()) detailLoading.value = false;
  }
}
async function selectTab(tab: string) {
  if (!tabs.value.some((item) => item.key === tab)) return;
  activeTab.value = tab;
  const ticket = contentGuard.next();
  contentLoading.value = false;
  contentError.value = '';
  if (!permitted.value || !current.value || tab === 'fields') return;
  const id = parseResourceId(current.value.id);
  if (!id) return;
  if (tab === 'preview' && preview.value) return;
  if (tab === 'api' && apiDocument.value) return;
  contentLoading.value = true;
  try {
    if (tab === 'preview' && current.value.assetType === 1) {
      const value = await previewMarketAsset(id, 20, crypto.randomUUID());
      if (ticket.isCurrent()) preview.value = value;
    } else if (tab === 'api' && parseResourceId(current.value.myApplyId)) {
      const applyId = parseResourceId(current.value.myApplyId)!;
      const value = await getMyAssetApiDocument(applyId);
      if (!sameResourceId(value.applyId, applyId))
        throw new Error('APPLY_IDENTITY');
      if (ticket.isCurrent()) apiDocument.value = value;
    }
  } catch {
    if (ticket.isCurrent())
      contentError.value = '读取失败，授权或资源状态可能已经变化，请重试。';
  } finally {
    if (ticket.isCurrent()) contentLoading.value = false;
  }
}
function cellText(value: unknown) {
  if (value === undefined || value === null) return '-';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}
watch(identity, close, { flush: 'sync' });
onBeforeUnmount(() => {
  alive = false;
  close();
});
defineExpose({ open, close });
function assetImage(name: string) {
  return `/assets/qdata-market/${name}.png`;
}
</script>

<template>
  <div class="mart-detail">
    <ApplyModal @success="onApplied" />
    <Spin v-if="detailLoading && !current" spinning />
    <Alert v-if="detailError" :message="detailError" type="warning" show-icon />
    <template v-if="current">
      <section class="mart-detail__metadata">
        <header class="mart-detail__heading">
          <h1>
            <span class="mart-detail__badge">{{
              current.sourceRecordId || current.id
            }}</span
            >{{ current.marketName || current.assetName
            }}<Tag class="mart-detail__state">
              {{ authorizationLabel }}
            </Tag>
          </h1>
          <Button size="small" @click="emit('back')">
            <template #icon>
              <img
                :src="assetImage('icon-return')"
                alt=""
                aria-hidden="true"
              /> </template
            >返回
          </Button>
        </header>
        <div class="mart-detail__grid">
          <div class="mart-detail__field">
            <label>资产名称</label><span>{{ current.assetName || '-' }}</span>
          </div>
          <div class="mart-detail__field">
            <label>所属集市</label
            ><span>{{ current.marketCategoryName || '-' }}</span>
          </div>
          <div class="mart-detail__field">
            <label>归属信息</label
            ><span class="mart-detail__ownership"
              ><img
                :src="assetImage('icon-folder')"
                alt=""
                aria-hidden="true"
              />{{ current.categoryName || '-' }} /
              {{ current.subjectDomainName || '-' }}</span
            >
          </div>
          <div class="mart-detail__field">
            <label>资产类型</label
            ><span
              ><Tag class="mart-detail__tag mart-detail__tag--1">{{
                typeLabel
              }}</Tag></span
            >
          </div>
          <div class="mart-detail__field">
            <label>更新频率</label
            ><span
              ><Tag class="mart-detail__tag mart-detail__tag--1">{{
                current.frequency || current.updateCycle || '-'
              }}</Tag></span
            >
          </div>
          <div class="mart-detail__field">
            <label>交付形态</label
            ><span
              ><Tag
                v-for="delivery in deliveries"
                :key="delivery.value"
                class="mart-detail__tag"
                :class="`mart-detail__tag--${delivery.value}`"
                >{{ delivery.label }}</Tag
              ></span
            >
          </div>
          <div class="mart-detail__field">
            <label>数据量</label><span>{{ current.rowCount ?? '-' }}</span>
          </div>
          <div class="mart-detail__field">
            <label>创建时间</label
            ><span>{{
              formatMarketTime(current.publishTime || current.createTime)
            }}</span>
          </div>
        </div>
        <div
          v-if="canApply && !permitted && current.myApplyStatus !== 1"
          class="mart-detail__actions"
        >
          <Button
            size="small"
            type="primary"
            :disabled="!!current.publicationNotice"
            @click="applyAsset"
          >
            申请权限
          </Button>
        </div>
      </section>
      <section class="mart-detail__content">
        <div class="mart-detail__tabs" role="tablist" aria-label="资产详情内容">
          <button
            v-for="tab in tabs"
            :id="`mart-tab-${tab.key}`"
            :key="tab.key"
            type="button"
            role="tab"
            :aria-selected="activeTab === tab.key"
            :class="{ 'is-active': activeTab === tab.key }"
            @click="selectTab(tab.key)"
          >
            {{ tab.label }}
          </button>
        </div>
        <div
          class="mart-detail__panel"
          role="tabpanel"
          :aria-labelledby="`mart-tab-${activeTab}`"
        >
          <Alert
            v-if="contentError"
            :message="contentError"
            type="error"
            show-icon
          >
            <template #action>
              <Button size="small" @click="selectTab(activeTab)"> 重试 </Button>
            </template>
          </Alert>
          <div v-if="!permitted && !detailLoading" class="mart-detail__empty">
            <img
              :src="assetImage('empty-permission')"
              alt=""
              aria-hidden="true"
            />
            <p>暂无权限</p>
          </div>
          <Spin
            v-else-if="detailLoading || contentLoading"
            spinning
            class="mart-detail__loading"
          />
          <Table
            v-else-if="activeTab === 'fields'"
            :data-source="detail?.columns ?? []"
            :columns="fieldColumns"
            :row-key="
              (row: DataAssetApi.MetadataColumn) =>
                String(row.id || row.columnName)
            "
            :pagination="false"
            :scroll="{ x: 680 }"
            size="small"
            bordered
          >
            <template #bodyCell="{ column, record, index }">
              <span v-if="column.key === 'index'">{{ index + 1 }}</span
              ><span v-else-if="column.key === 'nullableFlag'">{{
                record.nullableFlag === 1 ? '是' : '否'
              }}</span>
            </template>
          </Table>
          <Table
            v-else-if="activeTab === 'preview' && current.assetType === 1"
            :data-source="preview?.rows ?? []"
            :columns="previewColumns"
            :row-key="(_row: object, index?: number) => String(index)"
            :pagination="false"
            :scroll="{ x: Math.max(previewColumns.length * 160, 680) }"
            size="small"
            bordered
          >
            <template #bodyCell="{ column, record }">
              {{ cellText(record[String(column.key)]) }}
            </template>
          </Table>
          <div
            v-else-if="activeTab === 'api' && apiDocument"
            class="mart-detail__api"
          >
            <h2>{{ apiDocument.name }}</h2>
            <p>
              <Tag>{{ apiDocument.method }}</Tag
              >{{ apiDocument.path }}
            </p>
            <h3>请求参数</h3>
            <Table
              :data-source="apiDocument.requestParams ?? []"
              :columns="apiColumns"
              row-key="name"
              :pagination="false"
              :scroll="{ x: 600 }"
              size="small"
              bordered
            />
            <h3>响应字段</h3>
            <Table
              :data-source="apiDocument.responseFields ?? []"
              :columns="apiColumns"
              row-key="name"
              :pagination="false"
              :scroll="{ x: 600 }"
              size="small"
              bordered
            />
          </div>
          <div v-else class="mart-detail__empty">
            <img
              :src="assetImage('empty-permission')"
              alt=""
              aria-hidden="true"
            />
            <p>暂无数据</p>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.mart-detail {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
  min-height: 100%;
  font-family: Arial, 'Microsoft YaHei', sans-serif;
  font-size: 14px;
  color: #333;
}

.mart-detail__metadata {
  padding: 14px 16px;
  background: #fff;
  border-radius: var(--radius);
}

.mart-detail__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.mart-detail__heading h1 {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  line-height: 30px;
}

.mart-detail__badge {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 20px;
  padding: 0 2px;
  font-size: 14px;
  font-weight: 500;
  color: #fff;
  background: hsl(var(--primary));
}

.mart-detail__actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}

.mart-detail__state {
  padding: 2px 8px;
  margin: 0;
  font-size: 14px;
  font-weight: 400;
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 10%);
  border: 0;
  border-radius: calc(var(--radius) - 2px);
}

.mart-detail__heading :deep(.ant-btn img) {
  width: 16px;
  height: 16px;
  object-fit: contain;
}

.mart-detail__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 2px;
}

.mart-detail__field {
  display: flex;
  min-width: 0;
  min-height: 42px;
  border: 1px solid #f0f2f5;
}

.mart-detail__field label {
  display: flex;
  flex: 0 0 150px;
  align-items: center;
  padding: 10px;
  font-weight: 400;
  color: #a0a4aa;
  background: #fafafa;
}

.mart-detail__field > span {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  min-width: 0;
  padding: 8px 10px;
  overflow-wrap: anywhere;
}

.mart-detail__ownership > img {
  width: 18px;
  height: 18px;
}

.mart-detail__tag {
  padding: 0 9px;
  margin: 0;
  font-size: 14px;
  line-height: 24px;
  border: 0;
  border-radius: calc(var(--radius) - 2px);
}

.mart-detail__tag--1 {
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 10%);
}

.mart-detail__tag--2 {
  color: #9650bd;
  background: #f1edf7;
}

.mart-detail__tag--3 {
  color: #e6a23c;
  background: #fdf8eb;
}

.mart-detail__content {
  flex: 1;
  min-width: 0;
  min-height: 700px;
  padding: 16px;
  background: #fff;
  border-radius: var(--radius);
}

.mart-detail__tabs {
  display: flex;
  gap: 40px;
  height: 36px;
  border-bottom: 2px solid #e4e7ed;
}

.mart-detail__tabs button {
  padding: 0 0 12px;
  margin-bottom: -2px;
  font-size: 14px;
  color: #333;
  cursor: pointer;
  background: none;
  border: 0;
  border-bottom: 2px solid transparent;
}

.mart-detail__tabs button.is-active {
  color: hsl(var(--primary));
  border-bottom-color: hsl(var(--primary));
}

.mart-detail__panel {
  min-height: 450px;
  padding-top: 20px;
}

.mart-detail__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 46px 0 80px;
}

.mart-detail__empty img {
  width: 168px;
  height: 162px;
  object-fit: contain;
}

.mart-detail__empty p {
  margin: 22px 0 0;
  font-size: 14px;
  color: #909399;
}

.mart-detail__loading {
  display: block;
  padding: 80px;
  text-align: center;
}

.mart-detail__api h2 {
  margin: 0 0 16px;
  font-size: 16px;
}

.mart-detail__api h3 {
  margin: 24px 0 12px;
}

@media (width <= 1200px) {
  .mart-detail__field label {
    flex-basis: 110px;
  }
}

@media (width <= 900px) {
  .mart-detail__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (width <= 640px) {
  .mart-detail__grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .mart-detail__field label {
    flex-basis: 100px;
  }

  .mart-detail__tabs {
    gap: 24px;
  }

  .mart-detail__heading h1 {
    font-size: 16px;
  }
}
</style>

<style scoped src="../../table-font.css"></style>
