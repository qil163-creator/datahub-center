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
    :width="800"
    :closable="false"
    class="qi-node-modal qi-field-merge-dialog"
    @cancel="emit('update:open', false)"
  >
    <p class="qi-field-merge-message">
      已有 {{ existingCount }} 个字段，检测到 {{ incomingCount }} 个新字段，如何处理?
    </p>
    <template #footer>
      <div class="qi-field-merge-actions">
        <Button
          class="qi-merge-add-new"
          :auto-insert-space="false"
          :disabled="disabled"
          @click="resolve('addNewOnly')"
          >增加新的</Button
        >
        <Button
          class="qi-merge-add-all"
          :auto-insert-space="false"
          :disabled="disabled"
          @click="resolve('addAll')"
          >增加所有</Button
        >
        <Button
          class="qi-merge-clear-all"
          :auto-insert-space="false"
          :disabled="disabled"
          @click="resolve('clearAndAddAll')"
          >清除并增加所有</Button
        >
        <Button :auto-insert-space="false" @click="emit('update:open', false)"
          >取消</Button
        >
      </div>
    </template>
  </Modal>
</template>

<style scoped>
.qi-field-merge-message {
  margin: 0;
}

.qi-field-merge-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: flex-end;
}

.qi-field-merge-actions .ant-btn.qi-merge-add-new,
.qi-field-merge-actions .ant-btn.qi-merge-add-all,
.qi-field-merge-actions .ant-btn.qi-merge-clear-all {
  color: #fff;
}

.qi-field-merge-actions .ant-btn.qi-merge-add-new {
  background: #e6a23c;
  border-color: #e6a23c;
}

.qi-field-merge-actions .ant-btn.qi-merge-add-all {
  background: #2f62ff;
  border-color: #2f62ff;
}

.qi-field-merge-actions .ant-btn.qi-merge-clear-all {
  background: #f56c6c;
  border-color: #f56c6c;
}

.qi-field-merge-actions .ant-btn.qi-merge-add-new:not(:disabled):hover {
  color: #fff;
  background: #ebb563;
  border-color: #ebb563;
}

.qi-field-merge-actions .ant-btn.qi-merge-add-all:not(:disabled):hover {
  color: #fff;
  background: #5581ff;
  border-color: #5581ff;
}

.qi-field-merge-actions .ant-btn.qi-merge-clear-all:not(:disabled):hover {
  color: #fff;
  background: #f78989;
  border-color: #f78989;
}

.qi-field-merge-actions .ant-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
</style>
