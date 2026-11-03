<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import type { DataforgeDataApiApi } from '#/api/dataforge/dataapi';

import { computed, ref, watch } from 'vue';

import { Select } from 'antdv-next';

import { defaultApiConfig } from '#/views/dataforge/dataapi/modules/api-config';
import ApiConfigEditor from '#/views/dataforge/dataapi/modules/api-config-editor.vue';

import { defaultRestApiNodeConfig } from './rest-api-config';

import './editor-style.css';

defineOptions({ name: 'EtlNodeRestApi' });

const props = defineProps<{
  config: Record<string, any>;
  disabled?: boolean;
  nodeName?: string;
}>();

const apiEditor = ref<InstanceType<typeof ApiConfigEditor>>();
const apiConfig = computed<DataforgeDataApiApi.DataApi>({
  get: () => props.config.apiConfig,
  set: (value) => {
    props.config.apiConfig = value;
  },
});

const FETCH_OPTIONS = [100, 200, 500, 1000].map((v) => ({
  label: String(v),
  value: v,
}));

watch(
  () => props.config,
  (config) => {
    config.apiConfig = defaultApiConfig({
      ...config.apiConfig,
      apiName: props.nodeName?.trim() || 'API接口',
    });
    config.fetchSize ??= defaultRestApiNodeConfig().fetchSize;
  },
  { immediate: true },
);
watch(
  () => props.nodeName,
  (name) => {
    apiConfig.value.apiName = name?.trim() || 'API接口';
  },
);

defineExpose({
  validate: () =>
    apiEditor.value?.validate({ requireColumns: true, requireName: false }) ??
    false,
});
</script>

<template>
  <div class="etl-editor">
    <ApiConfigEditor ref="apiEditor" v-model="apiConfig" :disabled="disabled" />
    <div class="form-item">
      <label class="form-item__label">每页条数</label>
      <Select
        v-model:value="config.fetchSize"
        :disabled="disabled"
        :options="FETCH_OPTIONS"
      />
      <div class="form-item__tip">
        分页接口按配置循环拉取，返回字段随当前节点独立保存。
      </div>
    </div>
  </div>
</template>
