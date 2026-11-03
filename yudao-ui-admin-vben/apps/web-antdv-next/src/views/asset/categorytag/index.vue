<script setup lang="ts">
import QdataCategoryPage from '#/views/governance/common/qdata-category-page/page.vue';
import { tagCategoryAdapter } from '#/views/governance/common/qdata-category-page/adapters';
</script>

<template>
  <QdataCategoryPage :adapter="tagCategoryAdapter" />
</template>
