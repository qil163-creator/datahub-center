<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { onMounted } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { AutoComplete, Button, Checkbox, Input, Select } from 'antdv-next';

import './editor-style.css';

/**
 * 加密/解密字段编辑器（共用）：mode=encrypt 支持全部算法，mode=decrypt 仅可逆算法
 */
defineOptions({ name: 'EtlNodeCryptoFields' });

const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
  mode: 'decrypt' | 'encrypt';
}>();

const configKey = props.mode === 'encrypt' ? 'encryptFields' : 'decryptFields';

const ENCRYPT_ALGORITHMS = [
  'AES256',
  'AES128',
  'DES',
  '3DES',
  'MD5',
  'SHA1',
  'SHA256',
  'SHA512',
  'BASE64',
].map((v) => ({ label: v, value: v }));

const DECRYPT_ALGORITHMS = ['AES256', 'AES128', 'DES', '3DES', 'BASE64'].map(
  (v) => ({ label: v, value: v }),
);

const algorithms =
  props.mode === 'encrypt' ? ENCRYPT_ALGORITHMS : DECRYPT_ALGORITHMS;

function fieldOptions() {
  return (props.inputFields ?? []).map((field) => ({
    value: field.name!,
    label: `${field.name}（${field.dataType ?? '-'}）`,
  }));
}

function needKey(algorithm?: string) {
  return !['BASE64', 'MD5', 'SHA1', 'SHA256', 'SHA512'].includes(
    algorithm ?? '',
  );
}

function addField() {
  props.config[configKey].push({
    fieldName: '',
    algorithm: 'AES256',
    secretKey: '',
    outputFieldName: '',
    keepOriginal: false,
  });
}

onMounted(() => {
  if (!Array.isArray(props.config[configKey])) {
    props.config[configKey] = [];
    addField();
  }
});
</script>

<template>
  <div class="etl-editor">
    <div class="form-item__tip mb-2">
      {{
        mode === 'encrypt'
          ? '对称算法密文以 Base64 表示；哈希算法不可逆；输出字段名为空时覆盖原字段'
          : '仅支持可逆算法（AES/DES/3DES/BASE64）；密钥须与加密时一致'
      }}
    </div>
    <div
      v-for="(field, index) in config[configKey] ?? []"
      :key="index"
      class="crypto-field"
    >
      <div class="editor-row">
        <AutoComplete
          v-model:value="field.fieldName"
          :options="fieldOptions()"
          class="w-44"
          placeholder="字段名"
        />
        <Select
          v-model:value="field.algorithm"
          :options="algorithms"
          class="w-28"
        />
        <Input.Password
          v-if="needKey(field.algorithm)"
          v-model:value="field.secretKey"
          class="flex-1"
          placeholder="密钥"
        />
        <IconifyIcon
          class="editor-row__remove size-4"
          icon="lucide:trash-2"
          @click="config[configKey].splice(Number(index), 1)"
        />
      </div>
      <div class="editor-row">
        <Input
          v-model:value="field.outputFieldName"
          class="flex-1"
          placeholder="输出字段名（空=覆盖原字段）"
        />
        <Checkbox
          :checked="field.keepOriginal === true"
          @update:checked="(v: boolean) => (field.keepOriginal = v)"
        >
          保留原字段
        </Checkbox>
      </div>
    </div>
    <Button class="self-start" size="small" type="dashed" @click="addField">
      <template #icon><IconifyIcon icon="lucide:plus" /></template>
      添加字段
    </Button>
  </div>
</template>

<style scoped>
.crypto-field {
  padding: 8px;
  margin-bottom: 8px;
  background: hsl(var(--muted) / 30%);
  border-radius: 6px;
}
</style>
