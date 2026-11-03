<script lang="ts" setup>
import type { DataforgeWizardStep } from './form-wizard';

import { Button } from 'antdv-next';

defineProps<{
  busy?: boolean;
  current: number;
  showBackToList?: boolean;
  steps: DataforgeWizardStep[];
}>();
const emit = defineEmits<{
  change: [step: number];
  close: [];
  confirm: [];
}>();
</script>

<template>
  <div class="dataforge-wizard-footer">
    <div class="dataforge-wizard-footer__status">
      <span class="text-muted-foreground text-xs">
        第 {{ current + 1 }} / {{ steps.length }} 步 ·
        {{ steps[current]?.title }}
      </span>
      <slot name="actions"></slot>
    </div>
    <div class="flex shrink-0 items-center gap-2">
      <Button v-if="showBackToList" :disabled="busy" @click="emit('close')">
        返回列表
      </Button>
      <Button
        v-if="current > 0"
        :disabled="busy"
        @click="emit('change', current - 1)"
      >
        上一步
      </Button>
      <Button
        v-if="current < steps.length - 1"
        :loading="busy"
        type="primary"
        @click="emit('change', current + 1)"
      >
        下一步
      </Button>
      <Button v-else :loading="busy" type="primary" @click="emit('confirm')">
        确认提交
      </Button>
    </div>
  </div>
</template>

<style scoped>
.dataforge-wizard-footer {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.dataforge-wizard-footer__status {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  align-items: center;
  min-width: 0;
}
</style>
