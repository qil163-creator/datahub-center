<script setup lang="ts">
import type { VbenFormSchema } from '#/adapter/form';

import { watch } from 'vue';

import { Button } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';

const props = defineProps<{
  icons?: boolean;
  schema: VbenFormSchema[];
  values?: Record<string, unknown>;
}>();
const emit = defineEmits<{
  reset: [];
  submit: [];
  values: [values: Record<string, unknown>];
}>();
const [Form, formApi] = useVbenForm({
  compact: true,
  commonConfig: { componentProps: { class: 'w-full' } },
  schema: props.schema,
  showCollapseButton: true,
  submitOnEnter: true,
  wrapperClass: 'grid-cols-1 md:grid-cols-2 lg:grid-cols-3',
  resetButtonOptions: { show: false },
  submitButtonOptions: { show: false },
  handleValuesChange: (values) => emit('values', values),
  handleSubmit: (values) => {
    emit('values', values);
    emit('submit');
  },
});
watch(
  () => props.schema,
  (schema) => formApi.setState({ schema }),
);
watch(
  () => props.values,
  (values) => {
    if (!values) return;
    const fields = Object.fromEntries(
      props.schema.map(({ fieldName }) => [
        String(fieldName),
        values[String(fieldName)],
      ]),
    );
    void formApi.setValues(fields);
  },
  { immediate: true, deep: true },
);
async function reset() {
  await formApi.reset();
  emit('reset');
}
</script>

<template>
  <Form>
    <template #reset-before>
      <Button @click="reset">
        <i v-if="icons" class="category-reset-icon"></i>重置
      </Button>
    </template>
    <template #submit-before>
      <Button type="primary" @click="formApi.submit()">
        <i v-if="icons" class="category-query-icon"></i>搜索
      </Button>
    </template>
  </Form>
</template>
