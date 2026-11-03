<script setup lang="ts">
import ListPagination from '#/components/list-pagination/index.vue';

const props = defineProps<{ total: number }>();
const emit = defineEmits<{ change: [] }>();
const page = defineModel<number>('page', { required: true });
const pageSize = defineModel<number>('pageSize', { required: true });
function change(nextPage: number, nextSize: number) {
  page.value = nextSize === pageSize.value ? nextPage : 1;
  pageSize.value = nextSize;
  emit('change');
}
</script>

<template>
  <ListPagination
    :current="page"
    :page-size="pageSize"
    :total="props.total"
    :page-size-options="[10, 20, 50, 100]"
    @change="change"
  />
</template>
