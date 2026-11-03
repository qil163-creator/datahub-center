<script setup lang="ts">
import { Button, Modal } from 'antdv-next';

defineProps<{
  disabled?: boolean;
  existingCount: number;
  incomingCount: number;
  open: boolean;
}>();
const emit = defineEmits<{
  resolve: [mode: 'addAll' | 'addNewOnly' | 'clearAndAddAll'];
  'update:open': [value: boolean];
}>();
function resolve(mode: 'addAll' | 'addNewOnly' | 'clearAndAddAll') {
  emit('resolve', mode);
  emit('update:open', false);
}
</script>

<template>
  <Modal
    :open="open"
    title="字段冲突处理"
    :width="600"
    :footer="null"
    @cancel="emit('update:open', false)"
  >
    <p class="qi-field-merge-message">
      已有{{ existingCount }}个字段，检测到{{ incomingCount }}个新字段，如何处理？
    </p>
    <div class="qi-field-merge-actions">
      <Button :disabled="disabled" @click="resolve('addNewOnly')">增加新的</Button>
      <Button :disabled="disabled" @click="resolve('addAll')">增加所有</Button>
      <Button :disabled="disabled" type="primary" @click="resolve('clearAndAddAll')">
        清除并增加所有
      </Button>
      <Button @click="emit('update:open', false)">取消</Button>
    </div>
  </Modal>
</template>

<style scoped>
.qi-field-merge-message {
  margin: 14px 0 24px;
}

.qi-field-merge-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: flex-end;
}
</style>
