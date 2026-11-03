<script setup lang="ts">
import type { BusinessEntityApi } from '#/api/modeling/business-entity';

import { computed, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Button } from 'antdv-next';

import { getBusinessEntity } from '#/api/modeling/business-entity';
import { useObjectRequest } from '#/hooks/use-object-request';

import { ENTITY_SUBTYPES, ENTITY_TYPES } from './state';

import '../planning.css';
import '../detail.css';

const detail = ref<BusinessEntityApi.Entity>();
const loading = ref(false);
const failed = ref(false);
const identity = ref('closed');
const access = useAccessStore();
const request = useObjectRequest(
  () => `${identity.value}/${access.accessCodes.join(',')}`,
  () => {
    detail.value = undefined;
    loading.value = false;
  },
);
const names = (rows?: BusinessEntityApi.Summary[]) =>
  rows?.map((item) => item.name).join('、') || '-';
const fields = computed(() => {
  const row = detail.value;
  if (!row) return [];
  return [
    { label: '数据板块', value: row.dataDomainName },
    { label: '所属主题域', value: row.subjectDomainName },
    { label: '实体名称', value: row.name },
    { label: '实体英文名称', value: row.code },
    { label: '实体类型', value: ENTITY_TYPES[row.entityType] },
    { label: '对象类型', value: ENTITY_SUBTYPES[row.entitySubtype].label },
    { label: '负责人', value: row.leaderName },
    { label: '联系方式', value: row.leaderPhone },
    { label: '描述', value: row.description, wide: true, description: true },
    { label: '创建人', value: row.creatorName },
    { label: '创建时间', value: formatDateTime(row.createTime) },
    { label: '修改人', value: row.updaterName },
    { label: '修改时间', value: formatDateTime(row.updateTime) },
  ];
});
const [Modal, modalApi] = useVbenModal({
  title: '业务实体详情',
  fullscreenButton: false,
  showConfirmButton: false,
  showCancelButton: false,
  async onOpenChange(open) {
    const data = modalApi.getData() as
      | undefined
      | { id?: BusinessEntityApi.Id };
    identity.value = open ? String(data?.id ?? '') : 'closed';
    const current = request.begin();
    detail.value = undefined;
    failed.value = false;
    loading.value = false;
    if (!open || data?.id === undefined) return;
    loading.value = true;
    try {
      const item = await getBusinessEntity(data.id);
      if (current()) detail.value = item;
    } catch {
      if (current()) failed.value = true;
    } finally {
      if (current()) loading.value = false;
    }
  },
});
watch(
  () =>
    `${access.accessToken}/${access.refreshToken}/${access.accessCodes.join(',')}`,
  () => modalApi.close(),
);
</script>

<template>
  <Modal
    class="planning-modal planning-entity-modal"
    header-class="planning-modal__header"
    :loading="loading"
  >
    <div class="planning-modal__body">
      <div v-if="failed" class="planning-error">
        业务实体加载失败，请关闭后重试。
      </div>
      <template v-if="detail">
        <div class="planning-section-title">基础信息</div>
        <dl class="planning-entity-fields">
          <template v-for="field in fields" :key="field.label">
            <dt>{{ field.label }}</dt>
            <dd :class="{ wide: field.wide, description: field.description }">
              {{ field.value || '-' }}
            </dd>
          </template>
        </dl>
        <template v-if="detail.entitySubtype !== 'HIERARCHICAL'">
          <div class="planning-section-title planning-entity-relations-title">
            关系配置
          </div>
          <dl class="planning-entity-fields relations">
            <template v-if="detail.entitySubtype === 'NORMAL'">
              <dt>继承自实体</dt>
              <dd>{{ detail.inheritedEntity?.name || '-' }}</dd>
            </template>
            <dt>关联实体</dt>
            <dd>{{ names(detail.associatedEntities) }}</dd>
            <template v-if="detail.entitySubtype === 'PROCESS'">
              <dt>前序业务流程</dt>
              <dd>{{ names(detail.predecessorEntities) }}</dd>
              <dt>流程事件</dt>
              <dd>{{ names(detail.processEvents) }}</dd>
            </template>
          </dl>
        </template>
      </template>
    </div>
    <template #footer>
      <div class="planning-modal__footer">
        <Button @click="modalApi.close()">关闭</Button>
      </div>
    </template>
  </Modal>
</template>
