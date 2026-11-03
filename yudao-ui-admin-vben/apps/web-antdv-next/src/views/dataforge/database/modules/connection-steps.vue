<script lang="ts" setup>
defineProps<{ label: string; step: number; steps: readonly string[] }>();
</script>
<template>
  <ol class="connection-steps" :aria-label="label">
    <li
      v-for="(title, index) in steps"
      :key="title"
      :class="{
        'connection-step--active': step === index,
        'connection-step--done': step > index,
      }"
      :aria-current="step === index ? 'step' : undefined"
    >
      <span class="connection-step__number">{{ index + 1 }}</span><span>{{ title }}</span>
    </li>
  </ol>
</template>
<style scoped>
:global(.dark .connection-steps) {
  --connection-inactive: hsl(var(--muted));
  --connection-selected: rgb(73 100 255 / 16%);
}

.connection-steps {
  --connection-inactive: #f5f6f8;
  --connection-selected: #f2f4ff;

  display: flex;
  flex-shrink: 0;
  gap: 4px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.connection-steps li {
  display: flex;
  flex: 1;
  gap: 10px;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: 10px 20px;
  color: hsl(var(--muted-foreground));
  background: var(--connection-inactive);
  clip-path: polygon(
    0 0,
    calc(100% - 18px) 0,
    100% 50%,
    calc(100% - 18px) 100%,
    0 100%,
    18px 50%
  );
}

.connection-steps li:first-child {
  clip-path: polygon(
    0 0,
    calc(100% - 18px) 0,
    100% 50%,
    calc(100% - 18px) 100%,
    0 100%
  );
}

.connection-steps li:last-child {
  clip-path: polygon(0 0, 100% 0, 100% 100%, 0 100%, 18px 50%);
}

.connection-steps .connection-step--active {
  font-weight: 500;
  color: white;
  background: #4964ff;
}

.connection-steps .connection-step--done {
  color: #4964ff;
  background: var(--connection-selected);
}

.connection-step__number {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 23px;
  height: 23px;
  border: 1px solid currentcolor;
  border-radius: 50%;
}

@media (width <= 640px) {
  .connection-steps {
    gap: 2px;
  }

  .connection-steps li {
    gap: 5px;
    min-height: 48px;
    padding: 8px 14px;
    font-size: 14px;
  }

  .connection-step__number {
    width: 18px;
    height: 18px;
  }
}
</style>
