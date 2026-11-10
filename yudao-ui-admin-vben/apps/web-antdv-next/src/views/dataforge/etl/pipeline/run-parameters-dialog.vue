<script lang="ts" setup>
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { onBeforeUnmount, ref, watch } from 'vue';

import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Checkbox, Input, Modal, Select, Spin } from 'antdv-next';

import { getEtlRunParameters } from '#/api/dataforge/etl';

import { parameterConstraintText, resolveParameterValues } from './parameters';

type Selection = {
  expectedDefinitionRevision?: number;
  parameters: Record<string, string>;
};
const access = useAccessStore();
const user = useUserStore();
const owner = () =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
  ]);
const visible = ref(false);
const loading = ref(false);
const definitions = ref<DataforgeEtlApi.ParamItem[]>([]);
const values = ref<Record<string, string>>({});
const supplied = ref<Record<string, boolean>>({});
const source = ref('');
const errors = ref<string[]>([]);
let revision: number | undefined;
let sequence = 0;
let done: ((value: Selection | undefined) => void) | undefined;
function close() {
  ++sequence;
  visible.value = false;
  loading.value = false;
  definitions.value = [];
  values.value = {};
  supplied.value = {};
  errors.value = [];
  source.value = '';
  revision = undefined;
  const resolve = done;
  done = undefined;
  resolve?.(undefined);
}
function initialize(items: DataforgeEtlApi.ParamItem[]) {
  definitions.value = items;
  values.value = Object.fromEntries(
    items.filter((item) => item.name).map((item) => [item.name!, '']),
  );
  supplied.value = Object.fromEntries(
    items
      .filter((item) => item.name)
      .map((item) => [
        item.name!,
        item.required === true &&
          (item.defaultValue === undefined || item.defaultValue === null),
      ]),
  );
  if (items.length === 0) confirm();
}
function open(pipelineId: DataforgeEtlApi.Id): Promise<Selection | undefined> {
  close();
  visible.value = true;
  loading.value = true;
  const current = sequence;
  const context = owner();
  const pending = new Promise<Selection | undefined>((resolve) => {
    done = resolve;
  });
  void getEtlRunParameters(pipelineId)
    .then((result) => {
      if (current !== sequence || !visible.value || context !== owner()) return;
      revision = result.definitionRevision;
      source.value = `${result.published ? '已发布版本' : '当前已保存草稿'} · 参数定义修订 ${result.definitionRevision}`;
      loading.value = false;
      initialize(result.parameters);
    })
    .catch(() => {
      if (current !== sequence || !visible.value) return;
      loading.value = false;
    });
  return pending;
}
function openPreview(
  items: DataforgeEtlApi.ParamItem[],
): Promise<Selection | undefined> {
  close();
  visible.value = true;
  source.value = '当前画布参数，仅用于本次样本预览';
  const pending = new Promise<Selection | undefined>((resolve) => {
    done = resolve;
  });
  initialize(JSON.parse(JSON.stringify(items)));
  return pending;
}
function confirm() {
  if (loading.value || !done || (!source.value && revision === undefined))
    return;
  const provided = Object.fromEntries(
    Object.entries(supplied.value)
      .filter(([, enabled]) => enabled)
      .map(([name]) => [name, values.value[name] ?? '']),
  );
  const result = resolveParameterValues(definitions.value, provided);
  errors.value = result.issues;
  if (result.issues.length > 0) return;
  const selection = {
    expectedDefinitionRevision: revision,
    parameters: result.parameters,
  };
  const resolve = done;
  done = undefined;
  close();
  resolve(selection);
}
watch(owner, close, { flush: 'sync' });
onBeforeUnmount(close);
defineExpose({ open, openPreview, close });
</script>
<template>
  <Modal
    :open="visible"
    title="运行参数"
    width="680px"
    :confirm-loading="loading"
    destroy-on-hidden
    @ok="confirm"
    @cancel="close"
  >
    <Spin :spinning="loading">
      <p>{{ source }}</p>
      <Alert
        type="info"
        class="my-3"
        message="未勾选“本次提供”的参数使用定义默认值；无默认值的可选参数保持未提供。提交时再次检查定义修订，版本变化需重新确认。"
      />
      <div v-for="item in definitions" :key="item.name" class="mb-3 space-y-1">
        <strong>{{ item.name }} · {{ parameterConstraintText(item) }}</strong>
        <p v-if="item.description">{{ item.description }}</p>
        <p v-if="item.defaultValue !== undefined && item.defaultValue !== null">
          默认值：{{
            item.defaultValue === '' ? '（空字符串）' : item.defaultValue
          }}
        </p>
        <Checkbox
          v-if="item.name"
          :checked="supplied[item.name]"
          @update:checked="(value) => (supplied[item.name!] = value)"
        >
          本次提供
        </Checkbox>
        <template v-if="item.name && supplied[item.name]">
          <Select
            v-if="item.type === 'BOOLEAN'"
            v-model:value="values[item.name]"
            class="w-full"
            :options="[
              { label: 'true', value: 'true' },
              { label: 'false', value: 'false' },
            ]"
            placeholder="请选择布尔值"
          />
          <Input
            v-else
            v-model:value="values[item.name]"
            :maxlength="4096"
            placeholder="原样字符串，不做浮点转换"
          />
        </template>
      </div>
      <Alert v-if="errors.length" type="error" :message="errors.join('；')" />
    </Spin>
  </Modal>
</template>
