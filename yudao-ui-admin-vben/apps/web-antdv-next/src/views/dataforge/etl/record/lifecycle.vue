<script setup lang="ts">
import type { DataforgeEtlApi } from '#/api/dataforge/etl';
import type { EtlArchive } from '#/api/dataforge/etl/lifecycle';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Alert, Button, InputNumber, Modal, TextArea } from 'antdv-next';

import {
  archiveEtlRecord,
  getEtlArchive,
  restoreEtlArchive,
} from '#/api/dataforge/etl/lifecycle';

const props = defineProps<{ recordId: DataforgeEtlApi.Id }>();
const emit = defineEmits<{ availability: [available: boolean] }>();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const canArchive = computed(
  () =>
    hasAccessByCodes(['dataforge:etl:query']) &&
    hasAccessByCodes(['dataforge:etl:save']),
);
const archive = ref<EtlArchive | null>(null);
const archiveKnown = ref(false);
const error = ref('');
const loading = ref(false);
const mode = ref<'archive' | 'restore'>();
const reason = ref('');
const retentionDays = ref(365);
const busy = ref(false);
const mutationError = ref('');
let mutationSequence = 0;
let sequence = 0;
const archived = computed(() => !!archive.value && !archive.value.restoredAt);
async function load() {
  const current = ++sequence;
  loading.value = true;
  error.value = '';
  archiveKnown.value = false;
  emit('availability', false);
  try {
    const retention = await getEtlArchive(props.recordId);
    if (current !== sequence) return;
    archive.value = retention;
    archiveKnown.value = true;
    emit('availability', !archived.value);
  } catch {
    if (current === sequence)
      error.value = '归档状态读取失败，暂不开放消费操作。';
  } finally {
    if (current === sequence) loading.value = false;
  }
}
function close() {
  ++mutationSequence;
  mode.value = undefined;
  reason.value = '';
  mutationError.value = '';
  busy.value = false;
}
function open(action: 'archive' | 'restore') {
  if (busy.value || !canArchive.value || !archiveKnown.value) return;
  close();
  mode.value = action;
  retentionDays.value = 365;
}
async function submit() {
  if (!mode.value || busy.value || !canArchive.value) return;
  if (
    !reason.value.trim() ||
    (mode.value === 'archive' &&
      (!Number.isInteger(retentionDays.value) ||
        retentionDays.value < 30 ||
        retentionDays.value > 3650))
  ) {
    mutationError.value = '请填写原因；归档保留期为 30 至 3650 个整天。';
    return;
  }
  const current = ++mutationSequence;
  busy.value = true;
  mutationError.value = '';
  const request = { recordId: props.recordId, reason: reason.value.trim() };
  try {
    await (mode.value === 'archive'
      ? archiveEtlRecord({ ...request, retentionDays: retentionDays.value })
      : restoreEtlArchive(request));
    if (current !== mutationSequence) return;
    close();
    await load();
  } catch {
    if (current === mutationSequence) {
      mutationError.value =
        '操作未确认或当前状态不允许，请核对刷新结果后再处理。';
      await load();
    }
  } finally {
    if (current === mutationSequence) busy.value = false;
  }
}
watch(
  () => [props.recordId, access.accessToken, access.refreshToken],
  () => {
    close();
    archive.value = null;
    void load();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  ++sequence;
  close();
});
</script>
<template>
  <section class="mb-3 rounded border p-3">
    <div class="flex flex-wrap items-center gap-2">
      <strong>运行归档</strong><Button size="small" :loading="loading" @click="load">
        <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
        刷新归档
</Button><Button
        v-if="canArchive && archiveKnown"
        size="small"
        :disabled="busy"
        @click="open(archived ? 'restore' : 'archive')"
      >
        <template #icon><IconifyIcon icon="lucide:archive" /></template>
        {{ archived ? '恢复归档' : '归档运行' }}
      </Button>
    </div>
    <Alert v-if="error" class="mt-2" type="error" :message="error" />
    <p v-if="archiveKnown" class="mt-2">
      {{ archived ? '已归档：受控消费与回放已停用' : '未归档' }}
    </p>
    <p v-if="archive">
      归档时间 {{ formatDateTime(archive.archivedAt) }} · 证据访问保留至
      {{ formatDateTime(archive.evidenceAccessUntil)
      }}<span v-if="archive.restoredAt">
        · 已于 {{ formatDateTime(archive.restoredAt) }} 恢复</span>
    </p>
  </section>
  <Modal
    :open="!!mode"
    :title="mode === 'restore' ? '恢复归档' : '归档运行'"
    :confirm-loading="busy"
    :closable="!busy"
    :mask-closable="false"
    :cancel-button-props="{ disabled: busy }"
    @ok="submit"
    @cancel="close"
  >
    <p class="mb-3">
      归档阻断受控消费和回放，需所有关联治理工单已最终确认。保留期届满后问题详情停止开放。原始证据仍保留，不物理删除目标数据；恢复后仍需满足运行准入与功能操作权限。
    </p>
    <div v-if="mode === 'archive'" class="mb-3">
      证据访问保留天数
      <InputNumber
        v-model:value="retentionDays"
        :min="30"
        :max="3650"
        :precision="0"
        :disabled="busy"
      />
    </div>
    <TextArea
      v-model:value="reason"
      :maxlength="1000"
      :disabled="busy"
      placeholder="原因（必填）"
    />
    <Alert
      v-if="mutationError"
      class="mt-3"
      type="error"
      :message="mutationError"
    />
  </Modal>
</template>
