<script setup lang="ts">
import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import {
  Descriptions,
  DescriptionsItem,
  Spin,
} from 'antdv-next';

import { getMaintainModel } from '#/api/governance/masterdata';
import DetailBackButton from '#/components/detail-back-button/index.vue';

import { useMasterScope } from '../mastercommon/use-master-scope';
import DataContent from './modules/data-content.vue';
import ModelList from './modules/model-list.vue';

import '../mastercommon/qdata-master.css';

defineOptions({ name: 'GovernanceMasterMaintain' });
const route = useRoute();
const router = useRouter();
const model = ref<GovernanceMasterDataApi.Model>();
const loading = ref(false);
const failed = ref(false);
const modelId = computed(() =>
  typeof route.query.modelId === 'string' && /^\d+$/u.test(route.query.modelId)
    ? route.query.modelId
    : undefined,
);
const scope = useMasterScope(() => {
  model.value = undefined;
  void load();
});
async function load() {
  const current = scope.begin('detail');
  model.value = undefined;
  failed.value = false;
  loading.value = !!modelId.value;
  if (!modelId.value) return;
  try {
    const detail = await getMaintainModel(modelId.value);
    if (current()) model.value = detail;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
function enter(row: GovernanceMasterDataApi.Model) {
  void router.push({ path: route.path, query: { modelId: String(row.id) } });
}
function back() {
  void router.push({ path: route.path });
}
watch(modelId, load, { immediate: true });
</script>

<template>
  <Page auto-content-height content-class="metadata-page qdata-master-page">
    <ModelList v-if="!modelId" @enter="enter" />
    <Spin v-else-if="loading" class="m-auto" />
    <template v-else-if="failed"><DetailBackButton @click="back" /></template>
    <div v-else-if="model" class="metadata-detail">
      <section class="metadata-panel">
        <div class="metadata-detail__title">
          <span class="metadata-detail__id">{{ model.id }}</span>
          <h2>{{ model.tableCn || model.tableName }}</h2>
          <span class="metadata-tag metadata-tag--success">已发布</span>
          <DetailBackButton class="ml-auto" @click="back" />
        </div>
        <Descriptions bordered size="small" :column="{ xs: 1, sm: 2, xl: 3 }">
          <DescriptionsItem label="实体模型编码">
            {{ model.tableName || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="模型表名">
            {{ model.tableName || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="所属类目">
            {{ model.domainName || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="模型状态">
            <span class="metadata-tag metadata-tag--success">已发布</span>
          </DescriptionsItem>
          <DescriptionsItem label="字段数">
            {{ model.fieldCount ?? model.columns?.length ?? '-' }}
            条
          </DescriptionsItem>
          <DescriptionsItem label="最近维护时间">
            {{
              model.lastMaintenanceTime === null ||
              model.lastMaintenanceTime === undefined
                ? '-'
                : formatDateTime(model.lastMaintenanceTime)
            }}
          </DescriptionsItem>
          <DescriptionsItem label="数据源">
            {{ model.databaseName || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="数据库类型">
            {{ model.databaseType || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="数据库地址">
            {{ model.databaseHost || '-' }}
          </DescriptionsItem>
        </Descriptions>
      </section>
      <section class="metadata-panel">
        <DataContent :key="String(model.id)" :model="model" />
      </section>
    </div>
  </Page>
</template>
