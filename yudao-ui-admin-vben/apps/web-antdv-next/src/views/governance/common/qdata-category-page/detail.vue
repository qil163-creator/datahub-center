<script setup lang="ts">
import type { CategoryAdapter, CategoryRow, ResourceId } from './model';

import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, onDeactivated, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import { Alert, Button, Modal, Spin } from 'antdv-next';

import { useObjectRequest } from '#/hooks/use-object-request';
import { parseResourceId, sameResourceId } from '#/utils/resource-id';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

const props = defineProps<{ adapter: CategoryAdapter }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const visible = ref(false);
const id = ref<string>();
const detail = ref<CategoryRow>();
const parentName = ref('');
const loading = ref(false);
const failed = ref(false);
const canQuery = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes([`${props.adapter.permissionPrefix}:query`]),
);
const identity = computed(() =>
  JSON.stringify([
    props.adapter.permissionPrefix,
    user.userInfo?.userId,
    user.userRoles,
    access.accessCodes,
    canQuery.value,
  ]),
);
const request = useObjectRequest(
  () => `${identity.value}/${id.value ?? ''}`,
  reset,
);
const fields = computed<DetailFieldItem[]>(() => [
  { key: 'id', label: '编号', value: detail.value?.id },
  { key: 'parentName', label: '上级类目', value: parentName.value },
  { key: 'name', label: props.adapter.nameLabel, value: detail.value?.name },
  {
    key: 'status',
    label: '状态',
    value: statusLabel(detail.value?.status),
  },
  { key: 'sort', label: '排序', value: detail.value?.sort },
  { key: 'creatorName', label: '创建人', value: detail.value?.creatorName },
  {
    key: 'createTime',
    label: '创建时间',
    value:
      detail.value?.createTime === undefined
        ? '-'
        : formatDate(detail.value.createTime, 'YYYY-MM-DD HH:mm:ss'),
  },
  {
    key: 'description',
    label: '备注',
    value: detail.value?.description,
    span: 2,
    type: 'textarea',
  },
]);

function statusLabel(value?: number) {
  if (value === 0) return '启用';
  if (value === 1) return '禁用';
  return `未知状态（${value ?? '-'}）`;
}

function reset() {
  visible.value = false;
  detail.value = undefined;
  parentName.value = '';
  loading.value = false;
  failed.value = false;
}

function close() {
  request.begin();
  reset();
}

async function load() {
  if (!visible.value || !id.value || !canQuery.value) return;
  const selectedId = id.value;
  const adapter = props.adapter;
  const current = request.begin();
  detail.value = undefined;
  parentName.value = '';
  failed.value = false;
  loading.value = true;
  try {
    const [rawDetail, rawRows] = await Promise.all([
      adapter.get(selectedId),
      adapter.list(),
    ]);
    if (!current() || !visible.value || !canQuery.value) return;
    const row = adapter.normalize(rawDetail);
    if (!sameResourceId(row.id, selectedId))
      throw new Error('CATEGORY_IDENTITY');
    const parent = rawRows
      .map((value) => adapter.normalize(value))
      .find((item) => sameResourceId(item.id, row.parentId));
    parentName.value =
      String(row.parentId) === '0'
        ? '顶级节点'
        : parent?.name || `类目#${row.parentId}`;
    detail.value = row;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}

async function open(value: ResourceId) {
  close();
  const selectedId = parseResourceId(value);
  if (!selectedId || !canQuery.value) return;
  id.value = selectedId;
  visible.value = true;
  await load();
}

watch(() => props.adapter, close, { flush: 'sync' });
onDeactivated(close);
defineExpose({ open, close });
</script>

<template>
  <Modal
    :open="visible"
    :title="`${adapter.label}详情${detail?.name ? ` - ${detail.name}` : ''}`"
    :width="800"
    :style="{ maxWidth: 'calc(100vw - 32px)' }"
    centered
    destroy-on-hidden
    class="qpc-modal qpc-detail-modal"
    @cancel="close"
  >
    <Alert
      v-if="failed"
      class="mb-4"
      type="error"
      message="类目详情读取失败或对象不匹配，请重试。"
      show-icon
    >
      <template #action>
<Button size="small" @click="load">重试</Button>
</template>
    </Alert>
    <Spin :spinning="loading">
      <DetailFieldForm v-if="detail" :fields="fields" label-width="130px" />
      <div v-else-if="loading" class="min-h-24"></div>
    </Spin>
    <template #footer><Button @click="close">关闭</Button></template>
  </Modal>
</template>
