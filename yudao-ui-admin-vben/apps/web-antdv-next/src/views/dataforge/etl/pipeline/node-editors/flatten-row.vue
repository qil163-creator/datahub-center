<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, onMounted } from 'vue';

import { AutoComplete, Checkbox, Input } from 'antdv-next';

import './editor-style.css';
defineOptions({ name: 'EtlNodeFlattenRow' });
const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();
const fieldOptions = computed(() =>
  (props.inputFields ?? []).map((field) => ({
    value: field.name!,
    label: field.name,
  })),
);
onMounted(() => {
  if (props.config.separator === null || props.config.separator === undefined)
    props.config.separator = '.';
  if (props.config.keepSource === null || props.config.keepSource === undefined)
    props.config.keepSource = false;
});
</script>
<template><div class="etl-editor"><div class="form-item"><label class="form-item__label">待展开字段（留空展开整行）</label><AutoComplete v-model:value="config.sourceField" :options="fieldOptions" placeholder="选择对象/数组字段" /></div><div class="form-item"><label class="form-item__label">字段分隔符</label><Input v-model:value="config.separator" class="w-32" placeholder="." /></div><div class="form-item"><Checkbox :checked="config.keepSource === true" @update:checked="(v: boolean) => (config.keepSource = v)">保留原始嵌套字段</Checkbox></div></div></template>
