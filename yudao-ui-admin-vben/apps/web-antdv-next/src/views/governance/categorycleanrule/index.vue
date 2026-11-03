<script setup lang="ts">
import QdataCategoryPage from '#/views/governance/common/qdata-category-page/page.vue';
import { cleanRuleCategoryAdapter } from '#/views/governance/common/qdata-category-page/adapters';
</script>

<template>
  <QdataCategoryPage :adapter="cleanRuleCategoryAdapter" />
</template>
