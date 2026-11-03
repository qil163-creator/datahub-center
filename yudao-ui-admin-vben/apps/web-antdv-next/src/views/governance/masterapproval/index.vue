<script setup lang="ts">
import { Page } from '@vben/common-ui';

import HistoryList from '../mastercommon/history-list.vue';

defineOptions({ name: 'GovernanceMasterApproval' });
</script>
<template>
  <Page auto-content-height><HistoryList mode="approval" /></Page>
</template>
