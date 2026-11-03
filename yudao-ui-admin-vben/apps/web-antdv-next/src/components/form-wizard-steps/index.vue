<script setup lang="ts">
defineProps<{
  busy?: boolean;
  current: number;
  steps: { title: string }[];
}>();
const emit = defineEmits<{ change: [step: number] }>();
</script>

<template>
  <nav class="sync-steps" aria-label="配置步骤">
    <button
      v-for="(step, index) in steps"
      :key="index"
      type="button"
      :class="{
        'sync-step--active': current === index,
        'sync-step--done': current > index,
      }"
      :aria-current="current === index ? 'step' : undefined"
      :disabled="busy"
      @click="emit('change', index)"
    >
      <span class="sync-step__number">{{ index + 1 }}</span>{{ step.title }}
    </button>
  </nav>
</template>

<style scoped>
.sync-steps {
  display: flex;
  gap: 3px;
  width: 100%;
}

.sync-steps > button {
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

.sync-steps > button:first-child {
  clip-path: polygon(
    0 0,
    calc(100% - 14px) 0,
    100% 50%,
    calc(100% - 14px) 100%,
    0 100%
  );
}

.sync-steps > .sync-step--active {
  color: hsl(var(--primary));
  background: linear-gradient(
    90deg,
    hsl(var(--primary) / 55%),
    hsl(var(--primary) / 12%)
  );
}

.sync-steps > .sync-step--done {
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 10%);
}

.sync-step__number {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 23px;
  height: 23px;
  border: 1px solid currentcolor;
  border-radius: 50%;
}

.sync-step--active .sync-step__number {
  color: white;
  background: hsl(var(--primary));
  border-color: hsl(var(--primary));
}

@media (max-width: 768px) {
  .sync-steps {
    min-width: 620px;
  }
}
</style>
