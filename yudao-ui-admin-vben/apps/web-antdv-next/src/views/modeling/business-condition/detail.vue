<script setup lang="ts">
import type { BusinessConditionApi } from '#/api/modeling/business-condition';

import { computed, onActivated, onDeactivated, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  ConfigProvider,
  Descriptions,
  DescriptionsItem,
  Spin,
  TabPane,
  Tabs,
  Tag,
} from 'antdv-next';

import { getBusinessCondition } from '#/api/modeling/business-condition';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import { useMetricOptions } from '../indicator-model/modules/use-metric-options';
import constructionImage from '../indicator-model/reference-assets/construction.png';
import domainIcon from '../indicator-model/reference-assets/domain-icon.png';
import { QdIcon } from '../logictable/icons';
import {
  CONDITION_LIST_PATH,
  conditionRouteId,
  CONFIG_LABELS,
  LOGIC_LABELS,
} from './data';

defineOptions({ name: 'ModelingBusinessConditionDetail' });
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
  ]),
);
const { domainLabel, subjectLabel, userLabel, userPhone } = useMetricOptions();
const detail = ref<BusinessConditionApi.Model>();
const loading = ref(false);
const error = ref('');
const tab = ref('derived');
const freshness = computed(
  () =>
    getDictOptions(DICT_TYPE.MODELING_LOGIC_DATA_TIMELINESS, 'string').find(
      (option) => String(option.value) === String(detail.value?.dataFreshness),
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
    error.value = '';
  },
);
async function load() {
  if (!active) return;
  const current = requests.begin();
  detail.value = undefined;
  error.value = '';
  loading.value = true;
  try {
    if (!hasAccessByCodes(['modeling:business-condition:query'])) {
      error.value = '暂无业务限定查询权限';
      return;
    }
    const id = conditionRouteId(route.params.id);
    if (!id) {
      error.value = '业务限定编号无效';
      return;
    }
    const result = await getBusinessCondition(id);
    if (current()) detail.value = result;
  } catch {
    if (current()) error.value = '业务限定详情加载失败，请重试。';
  } finally {
    if (current()) loading.value = false;
  }
}
watch(
  () => `${access.accessToken}/${access.refreshToken}/${scope.value}`,
  () => {
    tab.value = 'derived';
    void load();
  },
  { immediate: true },
);
onDeactivated(() => {
  active = false;
  requests.begin();
  detail.value = undefined;
  loading.value = false;
});
onActivated(() => {
  if (!active) {
    active = true;
    void load();
  }
});
</script>

<template>
  <ConfigProvider :theme="{ token: {} }">
    <Page auto-content-height content-class="condition-detail-page">
      <Spin :spinning="loading">
        <Alert v-if="error" type="error" :message="error" show-icon>
          <template #action>
            <Button size="small" @click="load">
              <template #icon>
                <QdIcon name="toolbarRefresh" />
              </template>
              重试
            </Button>
          </template>
        </Alert>
        <template v-if="detail">
          <section class="condition-summary">
            <div class="condition-heading">
              <div class="condition-title">
                <span class="condition-id">{{ detail.id }}</span>
                <h1>{{ detail.name }}</h1>
                <Tag :color="detail.status === 'ACTIVE' ? 'success' : 'error'">
                  {{ detail.status === 'ACTIVE' ? '启用' : '禁用' }}
                </Tag>
              </div>
              <DetailBackButton @click="router.push(CONDITION_LIST_PATH)" />
            </div>
            <Descriptions
              bordered
              :column="{ xs: 1, sm: 2, lg: 3 }"
              size="small"
              class="condition-summary-table"
            >
              <DescriptionsItem label="业务限定英文名">
                {{ detail.code }}
              </DescriptionsItem>
              <DescriptionsItem label="归属信息">
                <img :src="domainIcon" alt="" class="condition-domain" />{{
                  domainLabel(detail.dataDomainId)
                }}
                / {{ subjectLabel(detail.subjectDomainId) }}
              </DescriptionsItem>
              <DescriptionsItem label="来源主表">
                {{ detail.sourceModelCode || detail.sourceModelName || '-' }}
              </DescriptionsItem>
              <DescriptionsItem label="描述" span="filled">
                {{ detail.description || '-' }}
              </DescriptionsItem>
              <DescriptionsItem label="业务实体">
                {{ detail.entityName || detail.businessEntity || '-' }}
              </DescriptionsItem>
              <DescriptionsItem label="数据时效">
                <Tag v-if="detail.dataFreshness" class="condition-freshness">
                  {{ freshness }}
</Tag><span v-else>-</span>
              </DescriptionsItem>
              <DescriptionsItem label="创建时间">
                {{
                  detail.createTime ? formatDateTime(detail.createTime) : '-'
                }}
              </DescriptionsItem>
            </Descriptions>
          </section>
          <section class="condition-detail-content">
            <Tabs v-model:active-key="tab">
              <TabPane key="derived" tab="派生指标" /><TabPane
                key="versions"
                tab="版本与变更"
              /><TabPane key="basic" tab="基本信息" />
            </Tabs>
            <Descriptions
              v-if="tab === 'basic'"
              bordered
              :column="{ xs: 1, sm: 2 }"
              size="small"
              class="condition-basic-table"
            >
              <DescriptionsItem label="业务限定类型">
                <Tag class="condition-type">
                  {{ CONFIG_LABELS[detail.configType] || detail.configType }}
                </Tag>
              </DescriptionsItem>
              <DescriptionsItem label="计算逻辑方式">
                <Tag class="condition-type">
                  {{ LOGIC_LABELS[detail.logicMode] || detail.logicMode }}
                </Tag>
              </DescriptionsItem>
              <DescriptionsItem label="计算逻辑" span="filled">
                <span class="condition-expression">{{
                  detail.expression
                }}</span>
              </DescriptionsItem>
              <DescriptionsItem label="负责人">
                {{ detail.ownerUserName || userLabel(detail.ownerUserId) }}
              </DescriptionsItem>
              <DescriptionsItem label="负责人联系方式">
                {{
                  detail.ownerUserPhone || userPhone(detail.ownerUserId) || '-'
                }}
              </DescriptionsItem>
              <DescriptionsItem label="创建人">
                {{ detail.creatorName || userLabel(detail.creator) }}
              </DescriptionsItem>
              <DescriptionsItem label="创建人联系方式">
                {{ detail.creatorPhone || userPhone(detail.creator) || '-' }}
              </DescriptionsItem>
              <DescriptionsItem label="修改人">
                {{ detail.updaterName || userLabel(detail.updater) }}
              </DescriptionsItem>
              <DescriptionsItem label="修改人联系方式">
                {{ detail.updaterPhone || userPhone(detail.updater) || '-' }}
              </DescriptionsItem>
            </Descriptions>
            <div v-else class="condition-construction">
              <img
                :src="constructionImage"
                alt=""
                aria-hidden="true"
                draggable="false"
              /><strong>功能建设中</strong><span class="condition-construction-rule" aria-hidden="true">— ⚒ —</span>
              <p>工程师们正在疯狂敲键盘，马上就好！</p>
              <p>你先逛逛别处，回头见 ✨</p>
            </div>
          </section>
        </template>
      </Spin>
      <DetailBackButton v-if="!detail" class="condition-empty-back" @click="router.push(CONDITION_LIST_PATH)" />
    </Page>
  </ConfigProvider>
</template>

<style scoped>
:deep(.condition-detail-page) {
  min-height: 100%;
  padding: 15px !important;
}

.condition-summary {
  padding: 20px;
  color: #515a6e;
  background: #fff;
  border-radius: var(--radius);
}

.condition-heading {
  display: flex;
  gap: 15px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.condition-title {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
}

.condition-title h1 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #333;
}

.condition-title :deep(.ant-tag) {
  margin: 0;
  font-size: 12px;
  border: 0;
  border-radius: calc(var(--radius) - 2px);
}

.condition-id {
  display: inline-block;
  min-width: 18px;
  padding: 0 2px;
  font-size: 12px;
  line-height: 18px;
  color: #fff;
  background: #4266f4;
  border-radius: 0;
}

.condition-summary-table :deep(.ant-descriptions-item-label) {
  width: 130px;
  color: #a1a1a1;
}

.condition-summary-table :deep(.ant-descriptions-row > th),
.condition-summary-table :deep(.ant-descriptions-row > td) {
  padding: 8px 10px;
  font-size: 14px;
}

.condition-summary-table :deep(.ant-descriptions-view),
.condition-basic-table :deep(.ant-descriptions-view) {
  overflow: hidden;
  border-color: #eceef4;
  border-radius: var(--radius);
}

.condition-summary-table :deep(.ant-descriptions-item-content) {
  overflow-wrap: anywhere;
}

.condition-domain {
  display: inline;
  width: 14px;
  height: 14px;
  margin-right: 6px;
  vertical-align: -2px;
  color: #4266f4;
}

.condition-freshness {
  margin: 0;
  font-size: 12px;
  color: #4e9b34;
  background: #edf5ea;
  border: 0;
  border-radius: calc(var(--radius) - 2px);
}

.condition-detail-content {
  min-height: 650px;
  padding: 20px;
  margin-top: 16px;
  color: #515a6e;
  background: #fff;
  border-radius: var(--radius);
}

.condition-detail-content :deep(.ant-tabs-nav) {
  margin-bottom: 20px;
}

.condition-detail-content :deep(.ant-tabs-tab) {
  padding: 12px 0;
  font-size: 14px;
}

.condition-detail-content :deep(.ant-tabs-tab + .ant-tabs-tab) {
  margin-left: 32px;
}

.condition-basic-table :deep(.ant-descriptions-item-label) {
  width: 170px;
  color: #333;
  background: #f5f7fa;
}

.condition-basic-table :deep(.ant-descriptions-row > th),
.condition-basic-table :deep(.ant-descriptions-row > td) {
  padding: 8px 10px;
  font-size: 14px;
}

.condition-type {
  margin: 0;
  font-size: 12px;
  color: #4266f4;
  background: #ebeffd;
  border: 0;
  border-radius: calc(var(--radius) - 2px);
}

.condition-expression {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.condition-construction {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 480px;
}

.condition-construction img {
  width: 375px;
  max-width: 90%;
  height: auto;
  pointer-events: none;
  user-select: none;
}

.condition-construction strong {
  margin-top: 14px;
  font-size: 14px;
  font-weight: 500;
  color: #4266f4;
}

.condition-construction-rule {
  margin: 4px 0;
  color: #8fa8ff;
}

.condition-construction p {
  margin: 0 0 5px;
  font-size: 12px;
  color: #a2a8b8;
}

.condition-empty-back {
  margin-top: 15px;
}

@media (max-width: 767px) {
  .condition-summary-table :deep(.ant-descriptions-item-label),
  .condition-basic-table :deep(.ant-descriptions-item-label) {
    width: 110px;
  }

  .condition-title {
    flex-wrap: wrap;
  }

  .condition-detail-content {
    min-height: 500px;
  }
}
</style>
