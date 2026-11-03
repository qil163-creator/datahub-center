<script setup lang="ts">
import type { GovernanceStandardCodeItemApi } from '#/api/governance/standardcodeitem';

import RecordsGrid from './records-grid.vue';
defineProps<{
  codeSetId?: GovernanceStandardCodeItemApi.Id;
  dictType?: string;
}>();
const emit = defineEmits<{ change: [] }>();
</script>
<template>
  <RecordsGrid
    kind="items"
    :code-set-id="codeSetId"
    :dict-type="dictType"
    @change="emit('change')"
  />
</template>
