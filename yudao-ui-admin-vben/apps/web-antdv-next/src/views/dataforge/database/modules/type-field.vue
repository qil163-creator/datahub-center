<script lang="ts" setup>
import { IconifyIcon } from '@vben/icons';

import { ConnectionIcon } from '#/components/connection-icon';

defineOptions({ name: 'DatabaseTypeField' });

const props = withDefaults(
  defineProps<{
    compact?: boolean;
    iconPath?: string;
    modelValue?: number | string;
    onEdit?: () => void;
    readonly?: boolean;
    typeCode?: string;
    typeName?: string;
  }>(),
  {
    compact: false,
    iconPath: '',
    modelValue: undefined,
    onEdit: undefined,
    readonly: false,
    typeCode: '',
    typeName: '',
  },
);

const emit = defineEmits<{
  edit: [];
  'update:modelValue': [value?: number | string];
}>();

function handleEdit() {
  props.onEdit?.();
  emit('edit');
}
</script>

<template>
  <div
    class="type-field"
    :class="{
      'type-field--readonly': props.readonly,
      'type-field--badge': props.compact,
    }"
  >
    <ConnectionIcon
      :icon-path="props.iconPath"
      :type-code="props.typeCode"
      alt=""
      class="shrink-0"
      :class="props.readonly || props.compact ? 'size-5' : 'size-4'"
    />
    <span class="type-field__name">{{ props.typeName || '请选择类型' }}</span>
    <button
      v-if="!props.readonly"
      type="button"
      class="type-field__edit"
      @click="handleEdit"
    >
      <IconifyIcon class="size-4" icon="lucide:pencil" />
    </button>
  </div>
</template>

<style scoped>
.type-field {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  height: 32px;
  padding: 0 11px;
  background: hsl(var(--background));
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.type-field__name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.type-field__edit {
  display: flex;
  align-items: center;
  justify-content: center;
  color: hsl(var(--muted-foreground));
  cursor: pointer;
  background: transparent;
  border: none;
}

.type-field__edit:hover {
  color: hsl(var(--foreground));
}

.type-field--readonly,
.type-field--badge {
  width: fit-content;
  max-width: 100%;
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 8%);
  border-color: hsl(var(--primary) / 25%);
}
</style>
