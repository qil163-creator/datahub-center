<script lang="ts" setup>
import ImageUpload from '#/components/upload/image-upload.vue';

import ConnectionIcon from './connection-icon.vue';

defineOptions({ inheritAttrs: false });
withDefaults(defineProps<{ typeCode?: string; value?: string }>(), {
  typeCode: '',
  value: '',
});
const emit = defineEmits<{
  change: [value: string];
  'update:value': [value: string];
}>();

function update(value: string | string[]) {
  const path = Array.isArray(value) ? value[0] || '' : value;
  emit('update:value', path);
  emit('change', path);
}
</script>

<template>
  <div class="flex items-start gap-3">
    <ConnectionIcon
      :icon-path="value"
      :type-code="typeCode"
      class="size-16 shrink-0"
    />
    <ImageUpload
      v-bind="$attrs"
      :value="value"
      :max-number="1"
      list-type="text"
      :show-upload-list="{ showPreviewIcon: false }"
      @update:value="update"
    />
  </div>
</template>
