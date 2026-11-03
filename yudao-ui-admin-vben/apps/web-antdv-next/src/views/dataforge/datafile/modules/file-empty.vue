<script setup lang="ts">
withDefaults(defineProps<{ compact?: boolean; description?: string }>(), {
  compact: false,
  description: '请选择数据连接',
});
</script>

<template>
  <div class="file-empty" :class="{ 'file-empty--compact': compact }">
    <img
      src="../reference-assets/empty-folder.png"
      alt=""
      aria-hidden="true"
      draggable="false"
    />
    <span>{{ description }}</span>
  </div>
</template>

<style scoped>
.file-empty {
  display: flex;
  flex-direction: column;
  gap: 38px;
  align-items: center;
  justify-content: center;
  min-height: 280px;
  font-size: 12px;
  color: #90939b;
}

.file-empty img {
  width: 202px;
  height: auto;
  pointer-events: none;
  object-fit: contain;
}

.file-empty--compact {
  gap: 16px;
  min-height: 400px;
}

.file-empty--compact img {
  width: 120px;
}
</style>
