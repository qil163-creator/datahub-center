<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 编辑父设计器隔离草稿，确认后由设计器写回流程。 */
import type { IntegrationApi } from '#/api/dataforge/integration';

import { computed, ref, watch } from 'vue';

import { Alert, Input, InputNumber, TextArea } from 'antdv-next';

import RestApiEditor from './reference-request-editor.vue';

import './reference-node-editor.css';

const props = defineProps<{
  config: Record<string, any>;
  disabled?: boolean;
  revision?: number;
  taskId?: IntegrationApi.Id;
}>();
const editor = ref<InstanceType<typeof RestApiEditor>>();
const issue = ref('');
const secretHeaders = ref('{}');
const contextHeaders = ref('{}');
watch(
  () => props.config,
  (config) => {
    secretHeaders.value = JSON.stringify(
      config.headerSecretRefs ?? {},
      null,
      2,
    );
    contextHeaders.value = JSON.stringify(
      config.headerContextRefs ?? {},
      null,
      2,
    );
  },
  { immediate: true },
);
function set(key: string, value: unknown) {
  if (!props.disabled) props.config[key] = value;
}
function contextReference(value: unknown) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const entry = value as Record<string, unknown>;
  return (
    Object.keys(entry).every((key) => ['prefix', 'tokenKey'].includes(key)) &&
    typeof entry.tokenKey === 'string' &&
    /^[A-Za-z][A-Za-z0-9_.-]{0,99}$/u.test(entry.tokenKey) &&
    (entry.prefix === undefined ||
      entry.prefix === '' ||
      (typeof entry.prefix === 'string' &&
        /^[A-Za-z][A-Za-z0-9_-]{0,31} $/u.test(entry.prefix)))
  );
}
function validate() {
  if (
    !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$/u.test(
      props.config.resourceBindingId ?? '',
    )
  ) {
    issue.value = '请填写管理员为当前任务授权的接口执行资源编号';
    return false;
  }
  for (const [name, fallback, maximum] of [
    ['maxPages', 10, 1000],
    ['maxRows', 100_000, 1_000_000],
    ['maxResponseBytes', 8_388_608, 16_777_216],
  ] as const) {
    const value = props.config[name] ?? fallback;
    if (!Number.isInteger(value) || value < 1 || value > maximum) {
      issue.value = '读取预算必须为有效范围内的正整数';
      return false;
    }
  }
  const headers = props.config.apiConfig?.requestHeader ?? [];
  if (
    headers.some((item: { name?: string }) =>
      /authorization|cookie|secret|token|api[-_]?key/iu.test(item.name ?? ''),
    )
  ) {
    issue.value = '认证请求头需要受管秘密适配，不能在节点内保存明文';
    return false;
  }
  try {
    const references: unknown = JSON.parse(secretHeaders.value);
    const variables: unknown = JSON.parse(contextHeaders.value);
    if (
      !references ||
      typeof references !== 'object' ||
      Array.isArray(references) ||
      Object.keys(references).length > 32 ||
      Object.entries(references).some(
        ([name, value]) =>
          !/^[A-Za-z][A-Za-z0-9-]{0,99}$/u.test(name) ||
          typeof value !== 'string' ||
          !/^[A-Za-z][A-Za-z0-9_.-]{0,99}$/u.test(value),
      )
    ) {
      issue.value = '秘密请求头必须填写请求头名称到管理员授权引用的JSON对象';
      return false;
    }
    if (
      !variables ||
      typeof variables !== 'object' ||
      Array.isArray(variables) ||
      Object.keys(variables).length > 32 ||
      Object.entries(variables).some(
        ([name, value]) =>
          !/^[A-Za-z][A-Za-z0-9-]{0,99}$/u.test(name) ||
          !contextReference(value),
      )
    ) {
      issue.value =
        '认证变量请求头必须声明tokenKey；前缀可留空或填写Bearer等认证方案并以空格结束';
      return false;
    }
    const referenceNames = [
      ...Object.keys(references),
      ...Object.keys(variables),
    ];
    if (referenceNames.length > 32) {
      issue.value = '秘密与认证变量请求头合计最多32个';
      return false;
    }
    if (
      referenceNames.some((name) =>
        [
          'connection',
          'content-length',
          'host',
          'transfer-encoding',
          'upgrade',
        ].includes(name.toLowerCase()),
      )
    ) {
      issue.value = '秘密或认证变量不能用于协议保留请求头';
      return false;
    }
    const names = [
      ...headers.map((entry: { name?: string }) => entry.name ?? ''),
      ...Object.keys(references),
      ...Object.keys(variables),
    ]
      .filter(Boolean)
      .map((name) => name.toLowerCase());
    if (new Set(names).size !== names.length) {
      issue.value =
        '同一请求头只能从普通参数、秘密引用或认证变量中选择一种来源';
      return false;
    }
    if (!(editor.value?.validate() ?? false)) return false;
    if (!props.disabled) {
      if (Object.keys(references).length > 0 || props.config.headerSecretRefs)
        props.config.headerSecretRefs = references;
      if (Object.keys(variables).length > 0 || props.config.headerContextRefs)
        props.config.headerContextRefs = variables;
    }
  } catch {
    issue.value = '秘密或认证变量请求头引用不是有效的JSON对象';
    return false;
  }
  issue.value = '';
  return true;
}
const stepNav = computed(() => editor.value?.stepNav);
defineExpose({ stepNav, validate });
</script>

<template>
  <div class="qi-reference-editor">
    <RestApiEditor
      ref="editor"
      :config="config"
      :disabled="disabled"
      :task-id="taskId"
      :revision="revision"
      >
<template #basics><slot name="basics"></slot></template>
</RestApiEditor>
    <details class="qi-reference-advanced">
      <summary>高级执行配置</summary>
      <p class="qi-reference-notice">
        填写管理员授权的执行资源编号；请求地址须在该资源允许范围内。读取超过页数、行数或响应大小预算时会明确失败。
      </p>
      <Alert v-if="issue" type="error" :message="issue" />
      <div class="qi-reference-form-grid">
        <label>接口执行资源<Input
            :value="config.resourceBindingId"
            :disabled="disabled"
            aria-label="接口执行资源"
            placeholder="管理员提供的执行资源编号"
            @update:value="(value) => set('resourceBindingId', value)"
        /></label>
        <label>最大页数<InputNumber
            :value="config.maxPages ?? 10"
            :min="1"
            :max="1000"
            :precision="0"
            :disabled="disabled"
            aria-label="接口最大页数"
            @update:value="(value) => set('maxPages', value)"
        /></label>
        <label>最多读取行数<InputNumber
            :value="config.maxRows ?? 100000"
            :min="1"
            :max="1000000"
            :precision="0"
            :disabled="disabled"
            aria-label="接口最多读取行数"
            @update:value="(value) => set('maxRows', value)"
        /></label>
        <label>单页响应字节上限<InputNumber
            :value="config.maxResponseBytes ?? 8388608"
            :min="1"
            :max="16777216"
            :precision="0"
            :disabled="disabled"
            aria-label="接口单页响应字节上限"
            @update:value="(value) => set('maxResponseBytes', value)"
        /></label>
      </div>
      <label>秘密请求头引用<TextArea
          :value="secretHeaders"
          :disabled="disabled"
          aria-label="接口秘密请求头引用"
          placeholder="{&quot;Authorization&quot;:&quot;api_access&quot;}"
          :rows="3"
          @update:value="
            (value) => {
              if (!disabled) secretHeaders = String(value ?? '');
            }
          "
      /></label>
      <label>认证变量请求头<TextArea
          :value="contextHeaders"
          :disabled="disabled"
          aria-label="接口认证变量引用"
          placeholder="{&quot;Authorization&quot;:{&quot;tokenKey&quot;:&quot;accessToken&quot;,&quot;prefix&quot;:&quot;Bearer &quot;}}"
          :rows="3"
          @update:value="
            (value) => {
              if (!disabled) contextHeaders = String(value ?? '');
            }
          "
      /></label>
    </details>
  </div>
</template>
