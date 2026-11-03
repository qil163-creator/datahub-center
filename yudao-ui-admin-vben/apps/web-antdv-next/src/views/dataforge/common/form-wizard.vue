<script lang="ts" setup>
import type { DataforgeWizardStep } from './form-wizard';

import { computed, nextTick, useTemplateRef, watch } from 'vue';

import { Alert, Steps } from 'antdv-next';

const props = defineProps<{
  busy?: boolean;
  current: number;
  page?: boolean;
  steps: DataforgeWizardStep[];
}>();
const emit = defineEmits<{ change: [step: number] }>();
const currentMeta = computed(() => props.steps[props.current]);
const confirming = computed(() => props.current === props.steps.length - 1);
const railItems = computed(() =>
  props.steps.map((step) => ({
    title: step.title,
    description: step.description,
    disabled: props.busy,
  })),
);
const content = useTemplateRef<HTMLDivElement>('content');
watch(
  () => props.current,
  async () => {
    await nextTick();
    if (content.value) content.value.scrollTop = 0;
  },
);
</script>

<template>
  <div class="dataforge-wizard" :class="{ 'dataforge-wizard--page': page }">
    <aside class="dataforge-wizard__rail" aria-label="配置步骤">
      <nav v-if="page" class="dataforge-wizard__steps" aria-label="配置步骤">
        <button
          v-for="(step, index) in steps"
          :key="index"
          type="button"
          :class="{
            'dataforge-wizard__step--active': current === index,
            'dataforge-wizard__step--done': current > index,
          }"
          :aria-current="current === index ? 'step' : undefined"
          :disabled="busy"
          @click="emit('change', index)"
        >
          <span class="dataforge-wizard__step-number">{{ index + 1 }}</span>
          {{ step.title }}
        </button>
      </nav>
      <Steps
        v-else
        :current="current"
        :items="railItems"
        direction="vertical"
        size="small"
        @change="(step) => !busy && emit('change', step)"
      />
    </aside>
    <div class="dataforge-wizard__main" :inert="busy || undefined">
      <header class="dataforge-wizard__header">
        <div class="dataforge-wizard__title">{{ currentMeta?.title }}</div>
        <div class="dataforge-wizard__hint">{{ currentMeta?.hint }}</div>
      </header>
      <div ref="content" class="dataforge-wizard__content">
        <div
          v-if="page && !confirming"
          class="dataforge-wizard__section-heading"
        >
          {{ current === 0 ? '基础信息' : currentMeta?.title }}
        </div>
        <Alert
          v-if="confirming"
          class="mb-4"
          title="请核对以下配置，需要调整时可返回对应步骤。"
          type="info"
          show-icon
        />
        <fieldset
          :disabled="confirming || busy"
          :class="{ 'pointer-events-none': confirming || busy }"
          class="m-0 min-w-0 border-0 p-0"
        >
          <slot></slot>
        </fieldset>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dataforge-wizard {
  display: flex;
  height: 560px;
  max-height: calc(100dvh - 180px);
  overflow: hidden;
}

.dataforge-wizard__rail {
  flex-shrink: 0;
  width: 216px;
  padding: 24px 20px;
  overflow-y: auto;
  background: hsl(var(--muted) / 35%);
  border-right: 1px solid hsl(var(--border));
}

.dataforge-wizard__rail :deep(.ant-steps-item-title) {
  font-size: 13px;
  font-weight: 500;
}

.dataforge-wizard__rail :deep(.ant-steps-item-description) {
  font-size: 12px;
}

.dataforge-wizard__main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.dataforge-wizard__header {
  flex-shrink: 0;
  padding: 20px 28px 14px;
  border-bottom: 1px solid hsl(var(--border) / 60%);
}

.dataforge-wizard__title {
  font-size: 15px;
  font-weight: 600;
}

.dataforge-wizard__hint {
  margin-top: 2px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.dataforge-wizard__content {
  flex: 1;
  min-height: 0;
  padding: 18px 28px 24px;
  overflow-y: auto;
}

.dataforge-wizard__content :deep(.col-span-2) {
  grid-column: 1 / -1;
}

.dataforge-wizard--page {
  flex: 1;
  flex-direction: column;
  gap: 16px;
  height: auto;
  min-height: 0;
  max-height: none;
}

.dataforge-wizard--page .dataforge-wizard__rail {
  width: 100%;
  padding: 26px max(32px, 8%);
  overflow-y: visible;
  background: hsl(var(--background));
  border: 0;
}

.dataforge-wizard--page .dataforge-wizard__main {
  min-height: 0;
  background: hsl(var(--background));
}

.dataforge-wizard--page .dataforge-wizard__header {
  padding: 24px 32px 0;
  border: 0;
}

.dataforge-wizard--page .dataforge-wizard__title,
.dataforge-wizard__section-heading {
  padding-left: 10px;
  font-weight: 600;
  border-left: 3px solid hsl(var(--primary));
}

.dataforge-wizard--page .dataforge-wizard__hint {
  margin-top: 12px;
}

.dataforge-wizard--page .dataforge-wizard__content {
  padding: 24px 32px 40px;
}

.dataforge-wizard__section-heading {
  margin: 8px 0 24px;
  font-size: 14px;
}

.dataforge-wizard__steps {
  display: flex;
  gap: 3px;
  width: 100%;
}

.dataforge-wizard__steps > button {
  display: flex;
  flex: 1;
  gap: 10px;
  align-items: center;
  justify-content: center;
  min-height: 42px;
  padding: 8px 12px;
  font-size: 14px;
  background: hsl(var(--muted));
  clip-path: polygon(
    0 0,
    calc(100% - 14px) 0,
    100% 50%,
    calc(100% - 14px) 100%,
    0 100%,
    14px 50%
  );
}

.dataforge-wizard__steps > button:first-child {
  clip-path: polygon(
    0 0,
    calc(100% - 14px) 0,
    100% 50%,
    calc(100% - 14px) 100%,
    0 100%
  );
}

.dataforge-wizard__steps > .dataforge-wizard__step--active {
  color: hsl(var(--primary));
  background: linear-gradient(
    90deg,
    hsl(var(--primary) / 55%),
    hsl(var(--primary) / 12%)
  );
}

.dataforge-wizard__steps > .dataforge-wizard__step--done {
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 10%);
}

.dataforge-wizard__step-number {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 23px;
  height: 23px;
  border: 1px solid currentcolor;
  border-radius: 50%;
}

.dataforge-wizard__step--active .dataforge-wizard__step-number {
  color: white;
  background: hsl(var(--primary));
  border-color: hsl(var(--primary));
}

@media (max-width: 768px) {
  .dataforge-wizard__rail {
    display: none;
  }

  .dataforge-wizard__header {
    padding: 18px 20px 12px;
  }

  .dataforge-wizard__content {
    padding: 16px 20px;
  }

  .dataforge-wizard--page .dataforge-wizard__rail {
    display: block;
    padding: 12px;
    overflow-x: auto;
  }

  .dataforge-wizard__steps {
    min-width: 620px;
  }

  .dataforge-wizard--page .dataforge-wizard__header {
    padding: 18px 20px 0;
  }

  .dataforge-wizard--page .dataforge-wizard__content {
    padding: 16px 20px 24px;
  }
}
</style>
