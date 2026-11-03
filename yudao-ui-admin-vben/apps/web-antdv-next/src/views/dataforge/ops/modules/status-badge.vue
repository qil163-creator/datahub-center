<script setup lang="ts">
import type { OpsInstanceApi as Api } from '#/api/dataforge/ops';

import { queueStateLabel, statusLabel } from '../model';
import ReferenceIcon from './reference-icon.vue';

defineProps<{
  detail?: boolean;
  queueReason?: string;
  queueState?: Api.QueueState;
  status: Api.Status;
}>();
</script>

<template>
  <span class="qo-instance-status">
    <span class="qo-status" :class="status.toLowerCase()"><ReferenceIcon v-if="status !== 'UNKNOWN'" :name="status.toLowerCase()" />{{
        detail && status === 'RUNNING' ? '正在运行' : statusLabel(status)
      }}</span>
    <span v-if="queueState && (queueState !== 'DONE' || status !== 'SUCCESS')" class="qo-queue-state" :class="queueState.toLowerCase()" :title="queueReason">
      <ReferenceIcon v-if="['DISPATCHING', 'QUEUED', 'STOP_REQUESTED', 'UNKNOWN'].includes(queueState)" name="clock" />{{ queueStateLabel(queueState) }}
    </span>
    <span v-if="queueReason && queueState && (queueState !== 'DONE' || status !== 'SUCCESS')" class="qo-queue-reason" :title="queueReason">{{ queueReason }}</span>
  </span>
</template>
