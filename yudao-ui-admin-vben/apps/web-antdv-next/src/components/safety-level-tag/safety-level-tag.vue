<script lang="ts" setup>
import { computed } from 'vue';

import { IconifyIcon } from '@vben/icons';

import {
  SAFETY_LEVEL_DEFAULT_COLORS,
  SAFETY_LEVEL_FALLBACK_COLOR,
} from './constants';

/** 安全等级标志：盾牌图标 + 等级文字的圆角胶囊，颜色优先取记录自定义颜色 */
defineOptions({ name: 'SafetyLevelTag' });

const props = withDefaults(
  defineProps<{
    /** 自定义颜色（hex，如 #52c41a），为空时按等级取默认色 */
    color?: string;
    /** 安全等级，如 L1、L2 */
    level?: string;
  }>(),
  {
    color: '',
    level: '',
  },
);

const backgroundColor = computed(() => {
  if (props.color && /^#[0-9a-f]{3,8}$/i.test(props.color)) {
    return props.color;
  }
  return (
    SAFETY_LEVEL_DEFAULT_COLORS[props.level] ?? SAFETY_LEVEL_FALLBACK_COLOR
  );
});
</script>

<template>
  <span v-if="level" class="safety-level-tag" :style="{ backgroundColor }">
    <IconifyIcon class="safety-level-tag__icon" icon="lucide:shield-check" />
    <span>{{ level }}</span>
  </span>
  <span v-else>-</span>
</template>

<style scoped>
.safety-level-tag {
  display: inline-flex;
  gap: 3px;
  align-items: center;
  padding: 1px 10px;
  font-size: 12px;
  font-weight: 600;
  line-height: 18px;
  color: #fff;
  border-radius: 9999px;
}

.safety-level-tag__icon {
  width: 12px;
  height: 12px;
}
</style>
