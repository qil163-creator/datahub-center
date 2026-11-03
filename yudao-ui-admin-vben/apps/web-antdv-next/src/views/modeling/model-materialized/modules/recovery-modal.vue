<script setup lang="ts">
import type { ModelMaterializedApi } from '#/api/modeling/model-materialized';

import { computed, ref, watch } from 'vue';

import { Alert, message, Modal, Tag } from 'antdv-next';

import {
  getModelMaterialized,
  reconcileModelMaterialized,
} from '#/api/modeling/model-materialized';

import { useModelingRequest } from '../../modelcommon/use-modeling-request';
import {
  MATERIALIZED_STATUS_OPTIONS,
  materializedStatus,
  recoveryAction,
} from '../state';

const emit = defineEmits<{ refresh: [] }>();
const open = ref(false);
const loading = ref(false);
const submitting = ref(false);
const detail = ref<ModelMaterializedApi.Materialized>();
const status = computed(() =>
  MATERIALIZED_STATUS_OPTIONS.find(
    (option) => option.value === materializedStatus(detail.value),
  ),
);
const session = useModelingRequest(
  () => '',
  () => {
    open.value = false;
  },
);

watch(open, (isOpen) => {
  if (isOpen) return;
  session.begin();
  loading.value = false;
  submitting.value = false;
  detail.value = undefined;
});

async function openRecovery(row: ModelMaterializedApi.Materialized) {
  if (submitting.value) return;
  const id = row.materializedId ?? row.id;
  if (id === null || id === undefined || id === '') return;
  const current = session.begin();
  detail.value = undefined;
  open.value = true;
  loading.value = true;
  try {
    const latest = await getModelMaterialized(id);
    if (!current()) return;
    if (!recoveryAction(latest)) {
      message.info('当前记录已无需核验，请查看最新状态');
      open.value = false;
      emit('refresh');
      return;
    }
    detail.value = latest;
  } finally {
    if (current()) loading.value = false;
  }
}

async function submit() {
  if (loading.value || submitting.value || !detail.value) return;
  const id = detail.value.materializedId ?? detail.value.id;
  if (id === null || id === undefined || id === '') return;
  if (!recoveryAction(detail.value)) return;
  const current = session.begin();
  submitting.value = true;
  try {
    const outcome = await reconcileModelMaterialized(id);
    if (!current()) return;
    const label = MATERIALIZED_STATUS_OPTIONS.find(
      (item) => item.value === outcome,
    )?.label;
    message.info(
      outcome === 'UNKNOWN'
        ? '外部结果仍无法确认，保持结果未知；请继续核查，勿重新发布'
        : `核验结果：${label || outcome}`,
    );
    open.value = false;
  } finally {
    if (current()) {
      submitting.value = false;
      emit('refresh');
    }
  }
}

defineExpose({ open: openRecovery });
</script>

<template>
  <Modal
    v-model:open="open"
    title="核验部署结果"
    :confirm-loading="submitting"
    :ok-button-props="{ disabled: loading || submitting }"
    :cancel-button-props="{ disabled: submitting }"
    :closable="!submitting"
    :mask-closable="!submitting"
    ok-text="确认核验"
    cancel-text="取消"
    destroy-on-hidden
    @ok="submit"
  >
    <Alert
      type="info"
      show-icon
      message="根据已记录的部署证据核验外部结果，不执行新的建表或变更。结果仍无法确认时保留结果未知，请勿重新发布。"
    />
    <p v-if="detail" class="mt-4">
      当前状态：<Tag :color="status?.color">{{ status?.label }}</Tag>
    </p>
  </Modal>
</template>
