<script setup lang="ts">
import type { GovernanceStandardCodeSetApi as Api } from '#/api/governance/standardcodeset';

import { computed, onActivated, onDeactivated, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { useTabs } from '@vben/hooks';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import { Alert, Button, Spin, Tabs } from 'antdv-next';

import { getStandardCodeSet } from '#/api/governance/standardcodeset';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import AssociationTable from '../../standardcommon/association-table.vue';
import ModelAssociations from '../../standardcommon/model-associations.vue';
import ItemGrid from './modules/item-grid.vue';
import RecordsGrid from './modules/records-grid.vue';

import '../../standardcommon/standard.css';
defineOptions({ name: 'GovernanceStandardCodeSetDetail' });
const route = useRoute();
const router = useRouter();
const tabs = useTabs();
const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const user = useUserStore();
const codeSetId = computed(() => String(route.params.id ?? ''));
const identity = computed(
  () =>
    `${codeSetId.value}/${access.accessToken}/${access.refreshToken}/${user.userInfo?.userId}/${access.accessCodes.join(',')}`,
);
const codeSet = ref<Api.StandardCodeSet>();
const loading = ref(false);
const error = ref('');
const activeTab = ref('models');
const active = ref(true);
const request = useObjectRequest(
  () => identity.value,
  () => {
    codeSet.value = undefined;
  },
);
const tabItems = [
  { key: 'models', label: '关联逻辑模型' },
  { key: 'assets', label: '关联资产' },
  { key: 'items', label: '代码字典' },
  { key: 'mapping', label: '代码映射' },
  { key: 'detail', label: '详细信息' },
];
const time = (value?: number | string) =>
  value ? formatDate(value, 'YYYY-MM-DD HH:mm') : '-';
async function load() {
  const current = request.begin();
  codeSet.value = undefined;
  error.value = '';
  if (!active.value) return;
  if (!/^[1-9]\d*$/.test(codeSetId.value)) {
    error.value = '代码表编号无效';
    loading.value = false;
    return;
  }
  if (!hasAccessByCodes(['governance:standard-code-set:query'])) {
    error.value = '暂无代码表查询权限';
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const result = await getStandardCodeSet(codeSetId.value);
    if (current()) codeSet.value = result;
  } catch {
    if (current()) error.value = '代码表详情加载失败，请重试';
  } finally {
    if (current()) loading.value = false;
  }
}
function back() {
  void tabs.closeCurrentTab();
  void router.push(
    String(route.meta.activePath || '/model/standard-data-element/code-table'),
  );
}
watch(
  identity,
  () => {
    activeTab.value = 'models';
    void load();
  },
  { immediate: true },
);
onDeactivated(() => {
  active.value = false;
  request.begin();
  codeSet.value = undefined;
});
onActivated(() => {
  if (!active.value) {
    active.value = true;
    void load();
  }
});
</script>
<template>
  <Page
    auto-content-height
    class="qdata-standard"
    content-class="flex min-h-0 flex-col gap-[14px]"
  >
    <Alert v-if="error" :message="error" type="error" show-icon>
      <template #action>
        <Button @click="load">
          <template #icon>
            <IconifyIcon icon="lucide:refresh-cw" />
          </template>
          重试
        </Button>
      </template>
    </Alert>
    <Spin :spinning="loading" class="shrink-0">
      <section class="standard-detail-header">
        <div class="standard-detail-title">
          <span class="standard-detail-id" :title="String(codeSet?.id || codeSetId)">{{ codeSet?.id || codeSetId }}</span><strong>{{ codeSet?.name || '代码表详情' }}</strong><span
            v-if="codeSet"
            class="standard-status-tag"
            :class="{ disabled: codeSet.status !== 0 }"
            >{{ codeSet.status === 0 ? '启用' : '停用' }}</span><DetailBackButton class="ml-auto" @click="back" />
        </div>
        <dl v-if="codeSet" class="standard-detail-grid">
          <dt>标准数据元类目</dt>
          <dd>{{ codeSet.categoryName || '-' }}</dd>
          <dt>字段类型</dt>
          <dd>{{ codeSet.columnType || '-' }}</dd>
          <dt>类型</dt>
          <dd><span class="standard-status-tag">代码表</span></dd>
          <dt>描述</dt>
          <dd class="standard-description">{{ codeSet.description || '-' }}</dd>
          <dt>创建人</dt>
          <dd>{{ codeSet.creatorName || '-' }}</dd>
          <dt>创建时间</dt>
          <dd>{{ time(codeSet.createTime) }}</dd>
          <dt>更新时间</dt>
          <dd>{{ time(codeSet.updateTime) }}</dd>
        </dl>
      </section>
    </Spin>
    <section
      v-if="codeSet"
      class="standard-detail-body"
      :class="{
        'standard-code-body--grid': activeTab === 'items',
        'standard-code-body--paged': ['models', 'assets', 'mapping'].includes(activeTab),
      }"
    >
      <Tabs
        v-model:active-key="activeTab"
        :items="tabItems"
        class="standard-detail-tabs"
        :class="{ 'standard-code-tabs--detail': activeTab === 'detail' }"
      >
        <template #contentRender="{ item }">
          <ItemGrid
            v-if="item.key === 'items' && activeTab === 'items'"
            :code-set-id="codeSet.id"
            :dict-type="codeSet.type"
          />
          <RecordsGrid
            v-else-if="item.key === 'mapping' && activeTab === 'mapping'"
            kind="mapping"
            :code-set-id="codeSet.id"
          />
          <ModelAssociations
            v-else-if="item.key === 'models' && activeTab === 'models'"
            kind="codeset"
            :owner-id="codeSet.id"
          />
          <AssociationTable
            kind="codeset"
            v-else-if="item.key === 'assets' && activeTab === 'assets'"
            :owner-id="codeSet.id"
          />
          <dl
            v-else-if="item.key === 'detail' && activeTab === 'detail'"
            class="standard-detail-grid standard-code-extra"
          >
            <dt>字段类型</dt>
            <dd>{{ codeSet.columnType || '-' }}</dd>
            <dt>责任人</dt>
            <dd>{{ codeSet.personChargeName || '-' }}</dd>
            <dt>联系电话</dt>
            <dd>{{ codeSet.contactNumber || '-' }}</dd>
            <dt>更新人</dt>
            <dd>{{ codeSet.updaterName || '-' }}</dd>
          </dl>
        </template>
      </Tabs>
    </section>
  </Page>
</template>
<style scoped>
.standard-detail-body.standard-code-body--grid {
  padding-bottom: 8px;
}

.standard-detail-body.standard-code-body--paged {
  padding-bottom: 16px;
}

.standard-code-extra {
  grid-template-columns: 150px minmax(0, 1fr) 150px minmax(0, 1fr);
  margin-top: 0;
}

.standard-code-tabs--detail :deep(.ant-tabs-nav) {
  margin-bottom: 8px;
}

@media (width <= 800px) {
  .standard-code-extra {
    grid-template-columns: 110px minmax(0, 1fr);
  }
}
</style>
