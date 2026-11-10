<script setup lang="ts">
import type { ProjectCategoryApi as Api } from '#/api/dataforge/projectcategory';

import { computed, onBeforeUnmount, onDeactivated, ref } from 'vue';

import { useAccess } from '@vben/access';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import {
  Button,
  Descriptions,
  DescriptionsItem,
  Modal,
  Spin,
  Tag,
} from 'antdv-next';

import {
  getProjectCategory,
  getProjectCategoryList,
} from '#/api/dataforge/projectcategory';
import { useObjectRequest } from '#/hooks/use-object-request';
import { parseResourceId, sameResourceId } from '#/utils/resource-id';

import { categoryLabels, categoryPermission } from './model';
import ReferenceIcon from './reference-icon.vue';

const props = defineProps<{ kind: Api.Kind }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const label = computed(() => categoryLabels[props.kind]);
const canRead = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes([categoryPermission(props.kind, 'query')]),
);
const scope = computed(() =>
  JSON.stringify([
    props.kind,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
    canRead.value,
  ]),
);
const visible = ref(false);
const loading = ref(false);
const failed = ref(false);
const selectedId = ref<Api.Id>();
const detail = ref<Api.Category>();
const parents = ref<Api.Category[]>([]);
const request = useObjectRequest(() => scope.value, close);
const parentName = computed(() => {
  const parentId = detail.value?.parentId;
  if (String(parentId) === '0') return '顶级类目';
  return (
    parents.value.find((row) => sameResourceId(row.id, parentId))?.name ||
    (parentId === undefined ? '-' : `类目#${parentId}`)
  );
});
const statusLabel = computed(() => {
  if (detail.value?.status === 0) return '启用';
  if (detail.value?.status === 1) return '禁用';
  return `未知状态（${detail.value?.status ?? '-'}）`;
});

function close() {
  request.begin();
  visible.value = false;
  loading.value = false;
  failed.value = false;
  selectedId.value = undefined;
  detail.value = undefined;
  parents.value = [];
}

async function open(data: { id: Api.Id }) {
  close();
  if (!canRead.value || !parseResourceId(data.id)) return;
  selectedId.value = data.id;
  visible.value = true;
  loading.value = true;
  const current = request.begin();
  try {
    const [result, parentList] = await Promise.all([
      getProjectCategory(props.kind, data.id),
      getProjectCategoryList(props.kind),
    ]);
    if (!current() || !visible.value || !canRead.value) return;
    if (!sameResourceId(result?.id, data.id))
      throw new Error('CATEGORY_IDENTITY');
    detail.value = result;
    parents.value = parentList;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}

function displayTime(value?: number) {
  return value === undefined ? '-' : formatDate(value, 'YYYY-MM-DD HH:mm:ss');
}

onDeactivated(close);
onBeforeUnmount(close);
defineExpose({ open, close });
</script>

<template>
  <Modal
    :open="visible"
    :title="`${label}详情`"
    width="min(800px, calc(100vw - 32px))"
    centered
    :mask-closable="false"
    class="qpc-detail-modal"
    destroy-on-hidden
    @cancel="close"
  >
    <template #closeIcon><ReferenceIcon name="close" /></template>
    <template #footer><Button @click="close">关闭</Button></template>

    <Spin :spinning="loading">
      <Descriptions
        v-if="detail"
        :column="{ xs: 1, sm: 2, md: 2, lg: 2, xl: 2, xxl: 2 }"
        bordered
        size="small"
      >
        <DescriptionsItem label="编号">{{ detail.id }}</DescriptionsItem>
        <DescriptionsItem :label="`${label}名称`">
{{
          detail.name
        }}
</DescriptionsItem>
        <DescriptionsItem label="上级类目">{{ parentName }}</DescriptionsItem>
        <DescriptionsItem label="状态">
          <Tag :color="detail.status === 0 ? 'success' : 'default'">
{{
            statusLabel
          }}
</Tag>
        </DescriptionsItem>
        <DescriptionsItem label="排序">{{ detail.sort }}</DescriptionsItem>
        <DescriptionsItem label="子类目数量">
{{
          detail.childCount ?? '-'
        }}
</DescriptionsItem>
        <DescriptionsItem label="任务数量">
{{
          detail.taskCount ?? '-'
        }}
</DescriptionsItem>
        <DescriptionsItem label="创建人">
{{
          detail.creatorName || detail.creator || '-'
        }}
</DescriptionsItem>
        <DescriptionsItem label="创建时间">
{{
          displayTime(detail.createTime)
        }}
</DescriptionsItem>
        <DescriptionsItem label="更新时间">
{{
          displayTime(detail.updateTime)
        }}
</DescriptionsItem>
        <DescriptionsItem label="描述" span="filled">
          <span class="whitespace-pre-wrap break-words">{{
            detail.description || '-'
          }}</span>
        </DescriptionsItem>
      </Descriptions>
    </Spin>
  </Modal>
</template>
