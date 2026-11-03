<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 仅修改父设计器的隔离草稿，确认节点后才写回流程。 */
import { computed, ref, watch } from 'vue';

import {
  Alert,
  AutoComplete,
  Button,
  Checkbox,
  Input,
  Select,
  Tooltip,
} from 'antdv-next';

import ReferenceIcon from './reference-icon.vue';

import './reference-node-editor.css';
import './reference-transform-layout.css';
import './managed-crypto-editor.css';

const props = defineProps<{
  config: Record<string, any>;
  disabled?: boolean;
  inputFields?: { dataType?: null | string; name?: string }[];
  toolCode: 'data-decrypt' | 'data-encrypt';
}>();
const issues = ref<string[]>([]);
const configKey = computed(() =>
  props.toolCode === 'data-encrypt' ? 'encryptFields' : 'decryptFields',
);
const fields = computed<Record<string, any>[]>(() =>
  Array.isArray(props.config[configKey.value])
    ? props.config[configKey.value]
    : [],
);
const fieldOptions = computed(() =>
  (props.inputFields ?? [])
    .filter((field) => field.name)
    .map((field) => ({ label: field.name!, value: field.name! })),
);
const algorithms = [
  { label: 'AES', value: 'AES' },
  { label: 'SM4', value: 'SM4' },
];
const keylessLegacy = new Set(['BASE64', 'MD5', 'SHA1', 'SHA256', 'SHA512']);
function options(algorithm: string) {
  return ['AES', 'SM4'].includes(algorithm) || !algorithm
    ? algorithms
    : [
        ...algorithms,
        { label: `${algorithm}（历史配置）`, value: algorithm, disabled: true },
      ];
}
function set(field: Record<string, any>, key: string, value: unknown) {
  if (!props.disabled) field[key] = value;
}
function add() {
  if (!props.disabled)
    props.config[configKey.value] = [
      ...fields.value,
      {
        fieldName: '',
        algorithm: 'AES',
        secretKeyRef: '',
        outputFieldName: '',
        keepOriginal: false,
      },
    ];
}
function remove(index: number) {
  if (!props.disabled)
    props.config[configKey.value] = fields.value.filter(
      (_, position) => position !== index,
    );
}
function clearLegacyKey(field: Record<string, any>) {
  if (!props.disabled) delete field.secretKey;
}
watch(
  () => [props.config, props.toolCode, props.disabled],
  () => {
    issues.value = [];
    if (!props.disabled && !Array.isArray(props.config[configKey.value])) add();
  },
  { immediate: true },
);
function validate() {
  const errors: string[] = [];
  if (fields.value.length === 0) errors.push('请配置至少一个加解密字段');
  const targets = new Set<string>();
  for (const [index, field] of fields.value.entries()) {
    const prefix = `第${index + 1}行：`;
    if (!field.fieldName?.trim()) errors.push(`${prefix}请选择来源字段`);
    const legacySupported =
      keylessLegacy.has(field.algorithm) &&
      (props.toolCode === 'data-encrypt' || field.algorithm === 'BASE64');
    if (!['AES', 'SM4'].includes(field.algorithm) && !legacySupported)
      errors.push(`${prefix}请选择AES或SM4，并确认历史配置的算法语义`);
    if (field.secretKey) errors.push(`${prefix}请清除旧密钥后填写密钥引用`);
    if (
      !legacySupported &&
      !/^[A-Za-z][A-Za-z0-9_.-]{0,99}$/u.test(field.secretKeyRef ?? '')
    )
      errors.push(`${prefix}请填写管理员授权的密钥引用`);
    const target = field.outputFieldName?.trim() || field.fieldName?.trim();
    if (target && targets.has(target)) errors.push(`${prefix}输出字段重复`);
    if (target) targets.add(target);
  }
  issues.value = errors;
  return errors.length === 0;
}
defineExpose({ validate });
</script>

<template>
  <div
    class="qi-reference-editor qi-transform-reference qi-crypto-reference"
    :data-tool-code="toolCode"
  >
    <Alert v-if="issues.length" type="error" :message="issues.join('；')" />
    <section
      v-for="(field, index) in fields"
      :key="index"
      class="qi-crypto-field"
    >
      <div class="qi-reference-form-grid">
        <label><span class="qi-crypto-required">算法</span><Select
            :value="field.algorithm"
            :options="options(field.algorithm)"
            :disabled="disabled"
            :aria-label="`第${index + 1}行加解密算法`"
            aria-required="true"
            placeholder="请选择算法"
            @update:value="(value) => set(field, 'algorithm', value)"
        /></label>
        <label v-if="!keylessLegacy.has(field.algorithm)"><span class="qi-crypto-required">密钥引用 <Tooltip title="填写管理员授权的密钥引用编号。密钥在执行资源中维护，加解密须使用一致的算法和密钥引用。"><span class="qi-crypto-help" tabindex="0" aria-label="密钥引用说明"><ReferenceIcon name="info" /></span></Tooltip></span><Input
            :value="field.secretKeyRef"
            :disabled="disabled"
            :aria-label="`第${index + 1}行密钥引用`"
            aria-required="true"
            placeholder="请输入密钥引用"
            @update:value="(value) => set(field, 'secretKeyRef', value)"
        /></label>
        <label><span class="qi-crypto-required">{{ toolCode === 'data-encrypt' ? '明文字段' : '密文字段'
          }}</span><AutoComplete
            :value="field.fieldName"
            :options="fieldOptions"
            :disabled="disabled"
            :aria-label="`第${index + 1}行来源字段`"
            aria-required="true"
            :placeholder="
              toolCode === 'data-encrypt' ? '请选择明文字段' : '请选择密文字段'
            "
            @update:value="(value) => set(field, 'fieldName', value)"
        ><template #suffixIcon><span class="qi-crypto-select-arrow" aria-hidden="true"></span></template></AutoComplete></label>
        <label><span>{{ toolCode === 'data-encrypt' ? '密文字段' : '明文字段'
          }} <Tooltip :title="`${toolCode === 'data-encrypt' ? '会将加密后的数据输出到这一列。' : '会将解密后的数据输出到这一列。'}留空时覆盖来源字段。`"><span class="qi-crypto-help" tabindex="0" :aria-label="toolCode === 'data-encrypt' ? '密文字段说明' : '明文字段说明'"><ReferenceIcon name="info" /></span></Tooltip></span><Input
            :value="field.outputFieldName"
            :disabled="disabled"
            :aria-label="`第${index + 1}行输出字段`"
            :placeholder="toolCode === 'data-encrypt' ? '请输入密文字段' : '请输入明文字段'"
            @update:value="(value) => set(field, 'outputFieldName', value)"
        /></label>
      </div>
      <slot v-if="index === 0" name="description"></slot>
      <details class="qi-transform-advanced">
        <summary>
          字段保留与兼容配置{{ fields.length > 1 ? ` · 第${index + 1}组` : '' }}
        </summary>
        <p class="qi-reference-notice">
          {{
            toolCode === 'data-encrypt'
              ? '会将加密后的数据输出到指定字段。'
              : '会将解密后的数据输出到指定字段。'
          }}密钥在授权执行资源中维护；加解密须使用一致的算法和密钥引用。
        </p>
        <Checkbox
          :checked="field.keepOriginal === true"
          :disabled="disabled"
          @update:checked="(value) => set(field, 'keepOriginal', value)"
          >
保留来源字段
</Checkbox>
        <Button :disabled="disabled" type="link" danger @click="remove(index)">
删除字段
</Button>
        <Alert
          v-if="field.secretKey"
          type="warning"
          message="此字段有历史密钥配置，请清除后改用密钥引用。"
          >
<template #action>
<Button
              :disabled="disabled"
              size="small"
              @click="clearLegacyKey(field)"
              >
清除旧密钥
</Button>
</template>
</Alert>
      </details>
    </section>
    <details class="qi-transform-advanced">
      <summary>多个字段处理</summary>
      <Button class="qi-reference-button" :disabled="disabled" @click="add">
新增加解密字段
</Button>
    </details>
  </div>
</template>
