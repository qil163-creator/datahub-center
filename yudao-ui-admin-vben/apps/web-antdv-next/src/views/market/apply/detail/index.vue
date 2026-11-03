<script lang="ts" setup>
import type { DepartmentAssetsApi } from '#/api/asset/market/department-assets';

import {
  computed,
  getCurrentInstance,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref,
  watch,
} from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';
import { useTabs } from '@vben/hooks';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, message, Spin } from 'antdv-next';

import {
  getDepartmentAsset,
  getDepartmentAssetApiDocument,
  getDepartmentAssetPreview,
} from '#/api/asset/market/department-assets';
import {
  createLatestWins,
  parseResourceId,
  sameResourceId,
} from '#/utils/resource-id';
import ApiDocument from '#/views/market/check/detail/modules/api-document.vue';
import FieldsTab from '#/views/market/check/detail/modules/fields-tab.vue';
import PreviewTab from '#/views/market/check/detail/modules/preview-tab.vue';
import ResourceSummary from '#/views/market/check/detail/modules/resource-summary.vue';

import {
  DEPARTMENT_ASSET_FIELD_WIDTHS,
  DEPARTMENT_ASSET_PERMISSION,
} from '../data';

import '../reference.css';

defineOptions({ name: 'MarketApplyAssetDetail' });
const route = useRoute();
const router = useRouter();
const tabNavigation = useTabs();
const access = useAccessStore();
const user = useUserStore();
const id = computed(() => parseResourceId(route.params.id));
const canQuery = computed(
  () =>
    !!id.value &&
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    access.accessCodes.includes(DEPARTMENT_ASSET_PERMISSION),
);
const identity = computed(() =>
  JSON.stringify([
    route.params.id,
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo,
    user.userRoles,
  ]),
);
const detail = ref<DepartmentAssetsApi.Detail>();
const loading = ref(false);
const errorText = ref('');
const activeTab = ref('fields');
const visited = ref<Record<string, boolean>>({ fields: true });
const epoch = ref(0);
const guard = createLatestWins();
type LazyTab = 'api' | 'preview';
const lazyGuards = { api: createLatestWins(), preview: createLatestWins() };
const tabLoading = ref({ api: false, preview: false });
const tabErrors = ref({ api: '', preview: '' });
const tabLoaded = ref({ api: false, preview: false });
const invocationMessageKey = `department-asset-invocation-${getCurrentInstance()?.uid}`;
let active = false;
const tabs = [
  { key: 'fields', label: '资产字段' },
  { key: 'preview', label: '数据预览' },
  { key: 'api', label: 'API接口文档' },
];
function clear() {
  message.destroy(invocationMessageKey);
  guard.invalidate();
  lazyGuards.api.invalidate();
  lazyGuards.preview.invalidate();
  tabLoading.value = { api: false, preview: false };
  tabErrors.value = { api: '', preview: '' };
  tabLoaded.value = { api: false, preview: false };
  detail.value = undefined;
  loading.value = false;
  errorText.value = '';
  activeTab.value = 'fields';
  visited.value = { fields: true };
  epoch.value++;
}
async function load() {
  clear();
  if (!active || !canQuery.value || !id.value) return;
  const requestedId = id.value;
  const ticket = guard.next();
  loading.value = true;
  try {
    const value = await getDepartmentAsset(requestedId);
    if (!ticket.isCurrent()) return;
    if (!sameResourceId(value.asset?.id, requestedId) || !value.asset.canView)
      throw new Error('APPLY_IDENTITY');
    detail.value = value;
    tabLoaded.value = {
      api: value.apiDocument !== undefined && value.apiDocument !== null,
      preview: value.preview !== undefined && value.preview !== null,
    };
  } catch {
    if (ticket.isCurrent()) errorText.value = '申请资产详情读取失败，请重试。';
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
function selectTab(key: string) {
  if (!tabs.some((tab) => tab.key === key)) return;
  activeTab.value = key;
  visited.value[key] = true;
  if (key === 'api' || key === 'preview') void loadTab(key);
}
async function loadTab(key: LazyTab) {
  const value = detail.value;
  if (
    !active ||
    !canQuery.value ||
    !value ||
    !sameResourceId(value.asset.id, id.value) ||
    tabLoading.value[key] ||
    tabLoaded.value[key] ||
    (key === 'preview' ? !value.canPreview : !value.canViewApiDocument)
  )
    return;
  const ticket = lazyGuards[key].next();
  tabLoading.value[key] = true;
  tabErrors.value[key] = '';
  try {
    if (key === 'preview') {
      const preview = await getDepartmentAssetPreview(value.asset.id);
      if (!ticket.isCurrent()) return;
      value.preview = preview;
    } else {
      const document = await getDepartmentAssetApiDocument(value.asset.id);
      if (!ticket.isCurrent()) return;
      value.apiDocument = document;
    }
    tabLoaded.value[key] = true;
  } catch {
    if (ticket.isCurrent()) tabErrors.value[key] = '资产内容读取失败，请重试。';
  } finally {
    if (ticket.isCurrent()) tabLoading.value[key] = false;
  }
}
function invoke() {
  const document = detail.value?.apiDocument;
  if (
    !active ||
    !canQuery.value ||
    activeTab.value !== 'api' ||
    !detail.value?.canViewApiDocument ||
    !document
  )
    return;
  const missing = document.requestParams?.find((parameter) => {
    const value =
      parameter.paramValue ?? parameter.defaultValue ?? parameter.example;
    return (
      parameter.required === true &&
      (value === undefined || value === null || value === '')
    );
  });
  message.warning({
    key: invocationMessageKey,
    content: missing
      ? `输入参数“${missing.name}”不能为空`
      : '当前申请未提供可执行的本地接口。',
  });
}
function back() {
  void tabNavigation.closeCurrentTab();
  void router.push(String(route.meta.activePath || '/asset/my-assets'));
}
function activate() {
  if (active) return;
  active = true;
  void load();
}
watch(
  identity,
  () => {
    void load();
  },
  { flush: 'sync' },
);
onMounted(activate);
onActivated(activate);
onDeactivated(() => {
  active = false;
  clear();
});
onBeforeUnmount(() => {
  active = false;
  clear();
});
</script>

<template>
  <Page
    auto-content-height
    content-class="flex min-h-0 flex-col overflow-hidden"
  >
    <div class="source-assets source-assets-detail">
      <Alert
        v-if="!canQuery"
        type="warning"
        message="申请编号无效或当前身份无权查看。"
        show-icon
      />
      <Alert v-if="errorText" type="error" :message="errorText">
        <template #action>
          <Button size="small" @click="load">重试</Button>
        </template>
      </Alert>
      <div v-if="loading" class="source-assets-loading">
        <Spin spinning />
      </div>
      <template v-if="detail">
        <ResourceSummary
          :resource="detail.resource"
          :status-label="detail.asset.statusLabel"
          :status-tone="detail.asset.statusTone"
          @back="back"
        />
        <section :key="epoch" class="source-assets-detail-content">
          <nav class="source-assets-tabs" aria-label="申请资产内容">
            <button
              v-for="tab in tabs"
              :key="tab.key"
              :class="{ 'is-active': activeTab === tab.key }"
              @click="selectTab(tab.key)"
            >
              {{ tab.label }}
            </button>
          </nav>
          <FieldsTab
            v-if="visited.fields"
            v-show="activeTab === 'fields'"
            :columns="detail.columns || []"
            :column-widths="DEPARTMENT_ASSET_FIELD_WIDTHS"
            :active="activeTab === 'fields'"
            :loading="loading"
            time-precision="minute"
            reference-pagination
            @refresh="load"
          />
          <div v-if="visited.preview" v-show="activeTab === 'preview'">
            <Alert
              v-if="tabErrors.preview"
              type="error"
              :message="tabErrors.preview"
            >
              <template #action>
                <Button size="small" @click="loadTab('preview')">重试</Button>
              </template>
            </Alert>
            <Spin :spinning="tabLoading.preview">
              <PreviewTab
                :preview="detail.canPreview ? detail.preview : undefined"
                reference-pagination
              />
            </Spin>
          </div>
          <div v-if="visited.api" v-show="activeTab === 'api'">
            <Alert v-if="tabErrors.api" type="error" :message="tabErrors.api">
              <template #action>
                <Button size="small" @click="loadTab('api')">重试</Button>
              </template>
            </Alert>
            <Spin :spinning="tabLoading.api">
              <ApiDocument
                :document="
                  detail.canViewApiDocument ? detail.apiDocument : undefined
                "
                :allow-invocation-check="
                  detail.canViewApiDocument && !!detail.apiDocument
                "
                :can-invoke="false"
                @invoke="invoke"
              />
            </Spin>
          </div>
        </section>
      </template>
    </div>
  </Page>
</template>
