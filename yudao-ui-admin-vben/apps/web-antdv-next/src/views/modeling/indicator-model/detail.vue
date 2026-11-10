<script setup lang="ts">
import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

import { computed, onActivated, onDeactivated, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Alert, Button, Spin, TabPane, Tabs, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { getIndicator, getIndicatorPage } from '#/api/modeling/indicator-model';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';
import domainIcon from '#/views/modeling/logictable/assets/data-section-default-icon-small.svg';
import { QdIcon } from '#/views/modeling/logictable/icons';

import { PERIOD_LABELS, TYPE_LABELS } from './data';
import { useMetricOptions } from './modules/use-metric-options';
import { indicatorDetailLocation } from './state';

defineOptions({ name: 'ModelingIndicatorDetail' });
const route = useRoute();
const router = useRouter();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const scope = computed(() =>
  JSON.stringify([
    user.userInfo?.userId,
    user.userRoles,
    access.accessCodes,
    route.params.id,
    route.name,
  ]),
);
const id = computed(() =>
  typeof route.params.id === 'string' && /^[1-9][0-9]*$/.test(route.params.id)
    ? route.params.id
    : undefined,
);
const detail = ref<IndicatorModelApi.Model>();
const loading = ref(false);
const failed = ref(false);
const relatedError = ref(false);
const tab = ref('derived');
const canQuery = computed(() =>
  hasAccessByCodes(['modeling:indicator-model:query']),
);
const { domainLabel, subjectLabel, userLabel, userPhone } = useMetricOptions();
const kind = computed(() =>
  detail.value?.indicatorType === 'DERIVED' ||
  route.name === 'ModelingDerivedIndicatorDetail'
    ? 'derived'
    : 'atomic',
);
const defaultListPath = computed(() =>
  String(route.meta.activePath || `/model/indicator-model/${kind.value}`),
);
const listPath = computed(() =>
  typeof route.query.listPath === 'string' &&
  /^\/(?!\/)/.test(route.query.listPath)
    ? route.query.listPath
    : defaultListPath.value,
);
const belonging = computed(() =>
  detail.value
    ? `${domainLabel(detail.value.dataDomainId)} / ${subjectLabel(detail.value.subjectDomainId)}`
    : '-',
);
const owner = computed(
  () => detail.value?.ownerUserName || userLabel(detail.value?.ownerUserId),
);
const ownerPhone = computed(
  () => detail.value?.ownerUserPhone || userPhone(detail.value?.ownerUserId),
);
const freshnessLabel = computed(
  () =>
    getDictOptions(DICT_TYPE.MODELING_LOGIC_DATA_TIMELINESS, 'string').find(
      (item) => item.value === detail.value?.dataFreshness,
    )?.label ||
    detail.value?.dataFreshness ||
    '-',
);
let active = true;
const requests = useObjectRequest(
  () => scope.value,
  () => {
    detail.value = undefined;
    loading.value = false;
  },
);
const relatedRequests = useObjectRequest(
  () => scope.value,
  () => {
    relatedGrid.setGridOptions({ data: [], loading: false });
  },
);
const [RelatedGrid, relatedGrid] = useVbenVxeGrid<IndicatorModelApi.Model>({
  gridOptions: {
    data: [],
    height: 'auto',
    headerCellConfig: { height: 34 },
    border: false,
    stripe: true,
    rowConfig: { keyField: 'id', height: 58 },
    pagerConfig: { enabled: false },
    columns: [
      { field: 'id', title: '编号', width: 100 },
      {
        field: 'name',
        title: '派生指标信息',
        minWidth: 260,
        slots: { default: 'name' },
      },
      {
        field: 'timePeriod',
        title: '统计周期',
        width: 120,
        formatter: ({ cellValue }: { cellValue?: IndicatorModelApi.Period }) =>
          cellValue ? PERIOD_LABELS[cellValue] : '-',
      },
      { field: 'businessCondition', title: '业务限定', minWidth: 260 },
      {
        field: 'status',
        title: '状态',
        width: 100,
        slots: { default: 'status' },
      },
      {
        title: '操作',
        width: 100,
        fixed: 'right',
        showOverflow: false,
        slots: { default: 'actions' },
      },
    ],
  },
});
async function load() {
  const current = requests.begin();
  detail.value = undefined;
  failed.value = false;
  loading.value = false;
  if (!id.value || !canQuery.value || !active) return;
  loading.value = true;
  try {
    const result = await getIndicator(id.value);
    if (current()) {
      if ((kind.value === 'atomic') !== (result.indicatorType === 'ATOMIC')) {
        failed.value = true;
        return;
      }
      detail.value = result;
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
async function loadRelated() {
  const current = relatedRequests.begin();
  relatedError.value = false;
  relatedGrid.setGridOptions({ data: [], loading: false });
  if (
    !detail.value ||
    detail.value.indicatorType !== 'ATOMIC' ||
    tab.value !== 'derived' ||
    !active
  )
    return;
  relatedGrid.setGridOptions({ loading: true });
  try {
    const result = await getIndicatorPage({
      pageNo: 1,
      pageSize: 100,
      indicatorType: 'DERIVED',
      baseIndicatorId: detail.value.id,
    });
    if (current()) relatedGrid.setGridOptions({ data: result.list });
  } catch {
    if (current()) relatedError.value = true;
  } finally {
    if (current()) relatedGrid.setGridOptions({ loading: false });
  }
}
watch(
  () => `${access.accessToken}/${access.refreshToken}/${scope.value}`,
  () => {
    tab.value =
      route.name === 'ModelingDerivedIndicatorDetail' ? 'basic' : 'derived';
    void load();
  },
  { immediate: true },
);
watch([tab, detail], () => {
  void loadRelated();
});
onDeactivated(() => {
  active = false;
  requests.begin();
  relatedRequests.begin();
});
onActivated(() => {
  if (!active) {
    active = true;
    void load();
  }
});
</script>

<template>
  <Page auto-content-height content-class="indicator-detail-page">
    <Alert v-if="!id" type="error" message="无效的指标编号" />
    <Alert
      v-else-if="!canQuery"
      type="warning"
      message="暂无指标模型查询权限"
    />
    <Alert
      v-else-if="failed"
      type="error"
      message="指标详情加载失败或指标类型不匹配，请返回列表重试。"
    >
      <template #action>
        <Button size="small" @click="load">
          <template #icon>
            <QdIcon name="toolbarRefresh" />
          </template>
          重试
        </Button>
      </template>
    </Alert>
    <Spin v-else :spinning="loading" class="indicator-detail-spin">
      <template v-if="detail">
        <section class="indicator-summary">
          <header>
            <h1>
              <span class="indicator-id">{{ detail.id }}</span>
              {{ detail.name }}
              <Tag :color="detail.status === 'ACTIVE' ? 'success' : 'error'">
                {{ detail.status === 'ACTIVE' ? '启用' : '禁用' }}
              </Tag>
            </h1>
            <DetailBackButton @click="router.push(listPath)" />
          </header>
          <table class="indicator-description">
            <tbody>
              <tr>
                <th>{{ TYPE_LABELS[detail.indicatorType] }}英文名</th>
                <td>{{ detail.code }}</td>
                <th>归属信息</th>
                <td>
                  <span class="indicator-belonging">
                    <img :src="domainIcon" alt="" />{{ belonging }}
                  </span>
                </td>
                <th>{{ kind === 'atomic' ? '来源主表' : '原子指标' }}</th>
                <td>
                  {{
                    detail.sourceModelCode ||
                    detail.sourceModelName ||
                    detail.baseIndicatorName ||
                    '-'
                  }}
                </td>
              </tr>
              <tr>
                <th>描述</th>
                <td colspan="5">{{ detail.description || '-' }}</td>
              </tr>
              <tr>
                <th>负责人</th>
                <td>
                  {{ owner
                  }}<template v-if="ownerPhone"> / {{ ownerPhone }}</template>
                </td>
                <th>创建人</th>
                <td>
                  {{ detail.creatorName || userLabel(detail.creator)
                  }}<template
                    v-if="detail.creatorPhone || userPhone(detail.creator)"
                  >
                    /
                    {{ detail.creatorPhone || userPhone(detail.creator) }}
                  </template>
                </td>
                <th>创建时间</th>
                <td>{{ formatDateTime(detail.createTime) || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </section>
        <section
          class="indicator-details"
          :class="{ 'indicator-details--derived': tab === 'derived' }"
        >
          <Tabs v-model:active-key="tab">
            <TabPane v-if="kind === 'atomic'" key="derived" tab="派生指标">
              <Alert
                v-if="relatedError"
                type="error"
                message="关联派生指标加载失败，请重新选择页签重试。"
              />
              <RelatedGrid class="metric-related-grid">
                <template #name="{ row }">
                  <div>{{ row.name }} ({{ row.code }})</div>
                  <div class="indicator-muted">
                    {{ row.description }}
                  </div>
                </template>
                <template #status="{ row }">
                  <Tag :color="row.status === 'ACTIVE' ? 'success' : 'default'">
                    {{ row.status === 'ACTIVE' ? '启用' : '禁用' }}
                  </Tag>
                </template>
                <template #actions="{ row }">
                  <TableAction
                    :actions="[
                      {
                        label: '详情',
                        icon: ACTION_ICON.VIEW,
                        type: 'link',
                        onClick: () =>
                          router.push(indicatorDetailLocation(row, listPath)),
                      },
                    ]"
                  />
                </template>
              </RelatedGrid>
              <Button
                v-if="(detail.derivedIndicatorCount ?? 0) > 100"
                size="small"
                @click="
                  router.push({
                    path: '/model/indicator-model/derived',
                    query: { baseIndicatorId: String(detail.id) },
                  })
                "
              >
                <template #icon>
                  <IconifyIcon icon="lucide:external-link" />
                </template>
                查看全部派生指标
              </Button>
            </TabPane>
            <TabPane key="basic" tab="基础信息">
              <table class="indicator-description indicator-basic">
                <tbody>
                  <tr>
                    <th>数据时效</th>
                    <td>
                      <Tag v-if="detail.dataFreshness" color="success">
                        {{ freshnessLabel }}
                      </Tag>
                      <span v-else>-</span>
                    </td>
                    <th>数据类型</th>
                    <td>{{ detail.resultDataType || '-' }}</td>
                  </tr>
                  <tr>
                    <th>度量单位</th>
                    <td>{{ detail.unit || '-' }}</td>
                    <th>{{ kind === 'atomic' ? '业务实体' : '统计周期' }}</th>
                    <td>
                      {{
                        kind === 'atomic'
                          ? detail.entityName || '-'
                          : detail.timePeriod
                            ? PERIOD_LABELS[detail.timePeriod]
                            : '-'
                      }}
                    </td>
                  </tr>
                  <tr v-if="kind === 'derived'">
                    <th>业务限定</th>
                    <td colspan="3">{{ detail.businessCondition || '-' }}</td>
                  </tr>
                  <tr v-if="kind === 'derived'">
                    <th>指标表达式</th>
                    <td colspan="3">{{ detail.expression || '-' }}</td>
                  </tr>
                  <tr>
                    <th>指标口径</th>
                    <td colspan="3">{{ detail.definition || '-' }}</td>
                  </tr>
                </tbody>
              </table>
              <details
                v-if="kind === 'atomic'"
                class="indicator-source-definition"
              >
                <summary>来源定义</summary>
                <table class="indicator-description indicator-basic">
                  <tbody>
                    <tr>
                      <th>指标表达式</th>
                      <td colspan="3">{{ detail.expression || '-' }}</td>
                    </tr>
                    <tr>
                      <th>计算字段</th>
                      <td>
                        {{
                          detail.valueFieldName || detail.valueFieldUid || '-'
                        }}
                      </td>
                      <th>统计时间字段</th>
                      <td>
                        {{ detail.timeFieldName || detail.timeFieldUid || '-' }}
                      </td>
                    </tr>
                  </tbody>
                </table>
              </details>
            </TabPane>
          </Tabs>
        </section>
      </template>
    </Spin>
  </Page>
</template>

<style scoped>
.metric-related-grid :deep(.vxe-body--column) {
  font-size: 12px;
}

:deep(.indicator-detail-page) {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 15px !important;
}

.indicator-detail-spin {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.indicator-detail-spin :deep(.ant-spin-container) {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
}

.indicator-summary,
.indicator-details {
  padding: 20px;
  font-size: 14px;
  color: var(--metric-text, #515a6e);
  background: var(--metric-surface, #fff);
  border-radius: var(--radius);
}

.indicator-summary header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.indicator-summary {
  flex-shrink: 0;
}

.indicator-summary h1 {
  display: flex;
  gap: 9px;
  align-items: center;
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--metric-text, #3c424e);
}

.indicator-summary header button {
  height: 26px;
  font-size: 12px;
  color: #4e6aff;
  background: #edf0ff;
  border-color: #cbd4ff;
  border-radius: calc(var(--radius) - 2px);
}

.indicator-id {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  min-height: 20px;
  padding: 0 2px;
  font-size: 12px;
  font-weight: 400;
  color: white;
  background: #4b6bff;
}

.indicator-summary :deep(.ant-tag) {
  font-size: 12px;
  border: 0;
  border-radius: calc(var(--radius) - 2px);
}

.indicator-description {
  width: 100%;
  overflow: hidden;
  table-layout: fixed;
  border-spacing: 0;
  border-collapse: separate;
  border: 1px solid var(--metric-border, #f0f2f7);
  border-radius: var(--radius);
}

.indicator-description th,
.indicator-description td {
  height: 38px;
  padding: 8px 10px;
  text-align: left;
  overflow-wrap: anywhere;
  border-right: 1px solid var(--metric-border, #f0f2f7);
  border-bottom: 1px solid var(--metric-border, #f0f2f7);
}

.indicator-description tr > :last-child {
  border-right: 0;
}

.indicator-description tr:last-child > * {
  border-bottom: 0;
}

.indicator-description th {
  width: 135px;
  font-weight: 400;
  color: #a0a5b0;
  background: var(--metric-table-head, #fafafb);
}

.indicator-details {
  flex: 1;
  min-height: 500px;
}

.indicator-details--derived {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.indicator-details--derived :deep(.ant-tabs),
.indicator-details--derived :deep(.ant-tabs-body-holder),
.indicator-details--derived :deep(.ant-tabs-body),
.indicator-details--derived :deep(.ant-tabs-content-active) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.indicator-details--derived .metric-related-grid {
  flex: 1;
  min-height: 0;
}

.indicator-details :deep(.ant-tabs-nav) {
  margin-bottom: 20px;
}

.indicator-details :deep(.ant-tabs-tab) {
  font-size: 14px;
}

.indicator-details :deep(.ant-tabs-ink-bar) {
  background: #526fff;
}

.indicator-basic th {
  width: 180px;
  color: var(--metric-text, #515a6e);
  background: var(--metric-table-head, #f6f8fb);
}

.indicator-basic td {
  white-space: pre-wrap;
}

.indicator-belonging {
  display: inline-flex;
  gap: 5px;
  align-items: center;
}

.indicator-belonging img {
  width: 16px;
  height: 16px;
}

.indicator-source-definition {
  margin-top: 14px;
}

.indicator-source-definition summary {
  margin-bottom: 10px;
  color: #909399;
  cursor: pointer;
}

.indicator-basic :deep(.ant-tag) {
  color: #5aa548;
  background: #eaf4e7;
  border: 0;
  border-radius: calc(var(--radius) - 2px);
}

.indicator-muted {
  font-size: 12px;
  color: #9da3af;
}

:global(.dark) .indicator-summary,
:global(.dark) .indicator-details {
  --metric-surface: #1f2329;
  --metric-text: #d2d7df;
  --metric-table-head: #282d36;
  --metric-border: #353b46;
}

@media (max-width: 767px) {
  .indicator-description {
    table-layout: auto;
  }

  .indicator-description th {
    width: auto;
    min-width: 70px;
  }

  .indicator-description td {
    min-width: 90px;
  }

  .indicator-summary {
    overflow: auto;
  }

  .indicator-basic {
    min-width: 500px;
  }

  .indicator-details {
    overflow: auto;
  }
}
</style>
