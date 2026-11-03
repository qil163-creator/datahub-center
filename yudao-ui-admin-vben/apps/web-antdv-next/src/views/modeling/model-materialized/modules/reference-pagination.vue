<script setup lang="ts">
import ListPagination from '#/components/list-pagination/index.vue';

const props = defineProps<{
  pageNo: number;
  pageSize: number;
  total: number;
}>();
const emit = defineEmits<{ change: [pageNo: number, pageSize: number] }>();
</script>

<template>
  <ListPagination
    :current="props.pageNo"
    :page-size="props.pageSize"
    :total="props.total"
    :page-size-options="[10, 20, 30, 50]"
    @change="
      (page, size) => emit('change', size !== props.pageSize ? 1 : page, size)
    "
  />
</template>
