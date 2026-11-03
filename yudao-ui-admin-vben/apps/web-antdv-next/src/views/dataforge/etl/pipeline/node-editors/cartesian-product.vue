<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props */
import { onMounted } from 'vue';

import { Checkbox, InputNumber } from 'antdv-next';

import './editor-style.css';
defineOptions({ name: 'EtlNodeCartesianProduct' });
const props = defineProps<{ config: Record<string, any> }>();
onMounted(() => {
  if (
    props.config.prefixFields === null ||
    props.config.prefixFields === undefined
  )
    props.config.prefixFields = true;
  if (
    props.config.maxOutputRows === null ||
    props.config.maxOutputRows === undefined
  )
    props.config.maxOutputRows = 1_000_000;
});
</script>
<template><div class="etl-editor"><div class="form-item"><Checkbox :checked="config.prefixFields !== false" @update:checked="(v: boolean) => (config.prefixFields = v)">用上游节点编号作为字段前缀，避免同名字段覆盖</Checkbox></div><div class="form-item"><label class="form-item__label">最大输出行数</label><InputNumber v-model:value="config.maxOutputRows" :min="1" :max="10000000" class="w-full" /><div class="form-item__tip">笛卡尔积按所有上游行数相乘，超过上限会终止节点</div></div></div></template>
