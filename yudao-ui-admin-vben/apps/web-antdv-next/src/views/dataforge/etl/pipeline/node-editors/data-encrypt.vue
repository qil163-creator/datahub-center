<script lang="ts" setup>
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import CryptoFields from './crypto-fields.vue';

defineOptions({ name: 'EtlNodeDataEncrypt' });

defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();
</script>

<template>
  <CryptoFields :config="config" :input-fields="inputFields" mode="encrypt" />
</template>
