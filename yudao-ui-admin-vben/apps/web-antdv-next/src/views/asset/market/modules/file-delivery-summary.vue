<script setup lang="ts">
import type { DeliveryFileBinding } from '#/api/asset/market/file-delivery';

defineProps<{ file: DeliveryFileBinding }>();
</script>

<template>
  <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 px-4 py-2">
    <dt>交付文件</dt><dd class="break-all">{{ file.fileName }}</dd>
    <dt>目录版本编号</dt><dd>{{ file.catalogVersionId }}</dd>
    <dt>文件大小</dt><dd>{{ file.size.toLocaleString() }} 字节</dd>
    <dt>SHA-256</dt><dd class="break-all font-mono">{{ file.sha256 }}</dd>
  </dl>
</template>
