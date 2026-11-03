<script setup lang="ts">
/**
 * 过渡期：旧菜单 component=modeling/modelpublish/index 仍指向本文件。
 * 0141 菜单切到 modeling/model-materialized/index 后可删除本目录。
 */
import ModelMaterializedPage from '../model-materialized/index.vue';

defineOptions({ name: 'ModelingModelPublish' });
</script>

<template>
  <ModelMaterializedPage />
</template>
