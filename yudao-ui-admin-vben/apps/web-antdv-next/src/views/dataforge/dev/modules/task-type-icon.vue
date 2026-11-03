<script lang="ts" setup>
import type { DevTaskIconInput } from '../task-icon-model';

import { computed } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { ConnectionIcon } from '#/components/connection-icon';

import { getDevTaskIcon } from '../task-icon-model';

defineOptions({ name: 'DevTaskTypeIcon' });

const props = withDefaults(
  defineProps<DevTaskIconInput & { size?: number }>(),
  { size: 18 },
);

const icon = computed(() => getDevTaskIcon(props));
</script>

<template>
  <span
    class="dev-task-type-icon"
    :style="{ color: icon.color, width: `${size}px`, height: `${size}px` }"
    :title="icon.label"
    :aria-label="icon.label"
    role="img"
  >
    <ConnectionIcon
      v-if="icon.imagePath"
      :icon-path="icon.imagePath"
      alt=""
      class="dev-task-type-icon__image"
    >
      <template #fallback>
        <span
          v-if="icon.mark"
          class="dev-task-type-icon__mark"
          aria-hidden="true"
        >
          {{ icon.mark }}
        </span>
        <IconifyIcon
          v-else
          :icon="icon.icon"
          :width="size"
          :height="size"
          aria-hidden="true"
        />
      </template>
    </ConnectionIcon>
    <span
      v-else-if="icon.mark"
      class="dev-task-type-icon__mark"
      aria-hidden="true"
    >
      {{ icon.mark }}
    </span>
    <IconifyIcon
      v-else
      :icon="icon.icon"
      :width="size"
      :height="size"
      aria-hidden="true"
    />
  </span>
</template>

<style scoped>
.dev-task-type-icon {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  vertical-align: middle;
}

.dev-task-type-icon__image {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.dev-task-type-icon__mark {
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.5px;
}
</style>
