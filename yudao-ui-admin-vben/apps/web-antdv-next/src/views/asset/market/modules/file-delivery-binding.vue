<script lang="ts" setup>
import type { MarketApi } from '#/api/asset/market';
import type { DeliveryFileBinding } from '#/api/asset/market/file-delivery';

import { onBeforeUnmount, ref, watch } from 'vue';

import { Alert, Button, message, Spin } from 'antdv-next';

import { bindDeliveryFile, getDeliveryFileBinding } from '#/api/asset/market/file-delivery';
import { createLatestWins, parseResourceId, sameResourceId } from '#/utils/resource-id';

const props = defineProps<{ assetId: MarketApi.Id; versionId?: MarketApi.Id }>();
const emit = defineEmits<{ bound: [value: boolean] }>();
const binding = ref<DeliveryFileBinding>();
const file = ref<File>();
const input = ref<HTMLInputElement>();
const reading = ref(false);
const saving = ref(false);
const failed = ref(false);
const guard = createLatestWins();

function applyBinding(value: DeliveryFileBinding | null) {
  if (value && !sameResourceId(value.catalogVersionId, props.versionId))
    throw new Error('文件所属目录版本已变化');
  binding.value = value ?? undefined;
  emit('bound', !!binding.value);
}
async function load() {
  const ticket = guard.next();
  applyBinding(null);
  file.value = undefined;
  saving.value = false;
  failed.value = false;
  const version = parseResourceId(props.versionId);
  reading.value = !!version;
  if (!version) return;
  try {
    const result = await getDeliveryFileBinding(props.assetId, version);
    if (ticket.isCurrent()) applyBinding(result);
  } catch {
    if (ticket.isCurrent()) failed.value = true;
  } finally {
    if (ticket.isCurrent()) reading.value = false;
  }
}
function selectFile(event: Event) {
  const picker = event.target as HTMLInputElement;
  const chosen = picker.files?.[0];
  picker.value = '';
  if (!chosen) return;
  if (chosen.size === 0 || chosen.size > 20 * 1024 * 1024) {
    file.value = undefined;
    message.warning('请选择非空且不超过 20 MiB 的文件');
    return;
  }
  file.value = chosen;
}
async function bind() {
  const version = parseResourceId(props.versionId);
  if (!file.value || !version || binding.value || reading.value || saving.value || failed.value) return;
  const ticket = guard.next();
  saving.value = true;
  try {
    const saved = await bindDeliveryFile(props.assetId, version, file.value);
    if (!ticket.isCurrent()) return;
    applyBinding(saved);
    file.value = undefined;
    message.success('文件已绑定至当前目录版本');
  } catch {
    if (ticket.isCurrent()) await load();
  } finally {
    if (ticket.isCurrent()) saving.value = false;
  }
}
watch([() => props.assetId, () => props.versionId], load, { immediate: true });
onBeforeUnmount(() => guard.invalidate());
</script>

<template>
  <div v-if="!parseResourceId(versionId)" class="text-muted-foreground">
    请先在资产详情的「管理 → 目录审核」发布目录版本，再绑定交付文件。
  </div>
  <Spin v-else :spinning="reading">
    <div v-if="failed" class="flex items-center gap-2">
      <Alert message="交付文件读取失败" type="error" show-icon />
      <Button size="small" @click="load">重试</Button>
    </div>
    <div v-else-if="binding">
      {{ binding.fileName }}（{{ binding.size.toLocaleString() }} 字节）
      <div class="text-muted-foreground text-sm">已绑定目录版本 {{ binding.catalogVersionId }}</div>
    </div>
    <div v-else-if="!reading">
      <input ref="input" type="file" class="hidden" @change="selectFile" />
      <div class="flex flex-wrap items-center gap-2">
        <Button :disabled="saving" @click="input?.click()">选择文件</Button>
        <span v-if="file">{{ file.name }}</span>
        <Button :disabled="!file" :loading="saving" @click="bind">绑定交付文件</Button>
      </div>
      <div class="text-muted-foreground mt-1 text-sm">
        单个文件最多 20 MiB。绑定后如需更换，请先发布新目录版本。
      </div>
    </div>
  </Spin>
</template>
