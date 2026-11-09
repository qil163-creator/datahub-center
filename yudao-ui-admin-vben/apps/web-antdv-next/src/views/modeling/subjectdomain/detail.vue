<script setup lang="ts">
import type { ModelingDataDomainApi } from '#/api/modeling/datadomain';
import type { ModelingSubjectDomainApi } from '#/api/modeling/subjectdomain';

import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { useAntdDesignTokens } from '@vben/hooks';
import { useAccessStore } from '@vben/stores';

import { Button, ConfigProvider, Spin, TabPane, Tabs } from 'antdv-next';

import { getDataDomain } from '#/api/modeling/datadomain';
import { getSubjectDomain } from '#/api/modeling/subjectdomain';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';
import { parseResourceId } from '#/utils/resource-id';

import DetailHeader from '../planning/detail-header.vue';
import EntityTable from '../planning/entity/table.vue';
import { planningPopupContainer } from '../planning/state';

import '../planning/planning.css';
import '../planning/detail.css';

defineOptions({ name: 'ModelingSubjectDomainDetail' });

const { tokens } = useAntdDesignTokens();

const route = useRoute();
const router = useRouter();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const id = computed(() => parseResourceId(route.params.id));
const detail = ref<ModelingSubjectDomainApi.SubjectDomain>();
const board = ref<ModelingDataDomainApi.DataDomain>();
const loading = ref(false);
const failed = ref(false);
const request = useObjectRequest(
  () => `${id.value}/${access.accessCodes.join(',')}`,
  () => {
    detail.value = undefined;
    board.value = undefined;
    loading.value = false;
  },
);
async function load() {
  const current = request.begin();
  if (!id.value || !hasAccessByCodes(['modeling:subject-domain:query'])) return;
  loading.value = true;
  failed.value = false;
  try {
    const item = await getSubjectDomain(id.value);
    if (!current()) return;
    detail.value = item;
    if (
      item.dataDomainId !== undefined &&
      hasAccessByCodes(['modeling:data-domain:query'])
    ) {
      const dataDomain = await getDataDomain(item.dataDomainId);
      if (current()) board.value = dataDomain;
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
  load,
  { immediate: true },
);
function back() {
  const parent = parseResourceId(route.query.fromDomain);
  void router.push(
    parent
      ? { name: 'ModelingDataDomainDetail', params: { id: parent } }
      : {
          path: '/model/warehouse-plan/subject-domain',
          query: { dataDomainId: detail.value?.dataDomainId },
        },
  );
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
        content-class="p-0"
      >
        <Spin :spinning="loading">
          <div v-if="detail" class="planning-detail-content">
            <DetailHeader
              kind="subject"
              :item="detail"
              :board="board"
              @back="back"
            />
            <section class="planning-detail-panel">
              <Tabs active-key="entities" class="planning-detail-tabs">
                <TabPane key="entities" tab="业务实体">
                  <EntityTable
                    :data-domain-id="detail.dataDomainId"
                    :subject-domain-id="detail.id"
                  />
                </TabPane>
              </Tabs>
            </section>
          </div>
          <div v-else-if="failed" class="planning-detail-error">
            主题域加载失败。<Button type="link" @click="load">重试</Button>
          </div>
          <div v-else-if="!loading" class="planning-detail-error">
            {{ id ? '暂无可访问的主题域' : '无效的主题域编号'
            }}<DetailBackButton @click="back" />
          </div>
        </Spin>
      </Page>
    </ConfigProvider>
  </div>
</template>
