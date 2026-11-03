<script lang="ts" setup>
import type { DataforgeWizardStep } from './form-wizard';

import DataforgeFormWizardFooter from './form-wizard-footer.vue';
import DataforgeFormWizard from './form-wizard.vue';

defineProps<{
  busy?: boolean;
  current: number;
  steps: DataforgeWizardStep[];
}>();
const emit = defineEmits<{
  change: [step: number];
  close: [];
  confirm: [];
}>();
</script>

<template>
  <section class="dataforge-wizard-page" :aria-busy="busy">
    <DataforgeFormWizard
      page
      :current="current"
      :steps="steps"
      :busy="busy"
      @change="(step) => emit('change', step)"
    >
      <slot></slot>
    </DataforgeFormWizard>
    <footer class="dataforge-wizard-page__footer">
      <DataforgeFormWizardFooter
        show-back-to-list
        :current="current"
        :steps="steps"
        :busy="busy"
        @change="(step) => emit('change', step)"
        @close="emit('close')"
        @confirm="emit('confirm')"
      >
        <template #actions><slot name="actions"></slot></template>
      </DataforgeFormWizardFooter>
    </footer>
  </section>
</template>

<style scoped>
.dataforge-wizard-page {
  display: flex;
  flex: 1;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.dataforge-wizard-page__footer {
  flex-shrink: 0;
  padding: 18px 32px;
  background: hsl(var(--background));
  border-top: 1px solid hsl(var(--border));
}

@media (max-width: 768px) {
  .dataforge-wizard-page__footer {
    padding: 16px 20px;
  }
}
</style>
