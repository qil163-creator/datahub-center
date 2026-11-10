<script setup lang="ts">
import type { ModelingDataDomainApi } from '#/api/modeling/datadomain';

import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { useAntdDesignTokens } from '@vben/hooks';
import { useAccessStore } from '@vben/stores';

import { Button, ConfigProvider, Spin, TabPane, Tabs } from 'antdv-next';

import { getDataDomain } from '#/api/modeling/datadomain';
import { getSubjectDomainList } from '#/api/modeling/subjectdomain';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';
import { parseResourceId } from '#/utils/resource-id';

import DetailHeader from '../planning/detail-header.vue';
import EntityTable from '../planning/entity/table.vue';
import NamingRules from '../planning/naming-rules.vue';
import { planningPopupContainer } from '../planning/state';
import SubjectTable from '../subjectdomain/index.vue';

import '../planning/planning.css';
import '../planning/detail.css';

defineOptions({ name: 'ModelingDataDomainDetail' });

const { tokens } = useAntdDesignTokens();

const route = useRoute();
const router = useRouter();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const id = computed(() => parseResourceId(route.params.id));
const detail = ref<ModelingDataDomainApi.DataDomain>();
const subjectAbbreviation = ref('');
const tab = ref('subjects');
const loading = ref(false);
const failed = ref(false);
const request = useObjectRequest(
  () => `${id.value}/${access.accessCodes.join(',')}`,
  () => {
    detail.value = undefined;
    subjectAbbreviation.value = '';
    loading.value = false;
  },
);
async function load() {
  const current = request.begin();
  if (!id.value || !hasAccessByCodes(['modeling:data-domain:query'])) return;
  loading.value = true;
  failed.value = false;
  try {
    const item = await getDataDomain(id.value);
    if (!current()) return;
    detail.value = item;
    if (hasAccessByCodes(['modeling:subject-domain:query'])) {
      const subjects = await getSubjectDomainList({ dataDomainId: id.value });
      if (current()) subjectAbbreviation.value = subjects[0]?.abbreviation || '';
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
watch(
  () =>
    `${id.value}/${access.accessToken}/${access.refreshToken}/${access.accessCodes.join(',')}`,
  () => {
    tab.value = route.query.tab === 'entities' ? 'entities' : 'subjects';
    void load();
  },
  { immediate: true },
);
function back() {
  void router.push('/model/warehouse-plan/data-domain');
}
</script>

<template>
  <div class="h-full min-h-0">
    <ConfigProvider
      :get-popup-container="planningPopupContainer"
      :theme="{
        token: { borderRadius: tokens.borderRadius, zIndexPopupBase: 2100 },
      }"
    >
      <Page
        auto-content-height
        class="planning-page planning-detail-page"
        content-class="flex min-h-0 flex-col p-0"
      >
        <Spin class="planning-detail-spin" :spinning="loading">
          <div v-if="detail" class="planning-detail-content">
            <DetailHeader kind="domain" :item="detail" @back="back" />
            <section
              class="planning-detail-panel"
              :class="{
                'planning-detail-panel--compact': ['subjects', 'entities'].includes(tab),
              }"
            >
              <Tabs v-model:active-key="tab" class="planning-detail-tabs">
                <TabPane key="subjects" tab="主题域">
                  <SubjectTable embedded :data-domain-id="detail.id" />
                </TabPane>
                <TabPane key="entities" tab="业务实体">
                  <EntityTable :data-domain-id="detail.id" :show-search="false" />
                </TabPane>
                <TabPane key="rules" tab="命名规则">
                  <NamingRules
                    :domain="detail"
                    :subject-abbreviation="subjectAbbreviation"
                    @refresh="load"
                  />
                </TabPane>
              </Tabs>
            </section>
          </div>
          <div v-else-if="failed" class="planning-detail-error">
            数据板块加载失败。<Button type="link" @click="load">重试</Button>
          </div>
          <div v-else-if="!loading" class="planning-detail-error">
            {{ id ? '暂无可访问的数据板块' : '无效的数据板块编号'
            }}<DetailBackButton @click="back" />
          </div>
        </Spin>
      </Page>
    </ConfigProvider>
  </div>
</template>

<style scoped>
.planning-detail-panel--compact {
  padding-bottom: 8px;
}
</style>
