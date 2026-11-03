<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { onMounted } from 'vue';

import { AutoComplete, Input, RadioGroup, Select } from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeWriteKafka' });

const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();

const KEY_OPTIONS = [
  { label: '不设置', value: 'none' },
  { label: '取字段值', value: 'field' },
  { label: 'UUID', value: 'uuid' },
];

const ACKS_OPTIONS = [
  { label: '0（不等待）', value: '0' },
  { label: '1（Leader 确认）', value: '1' },
  { label: 'all（全副本确认）', value: 'all' },
];

const COMPRESSION_OPTIONS = ['none', 'gzip', 'snappy', 'lz4', 'zstd'].map(
  (v) => ({ label: v, value: v }),
);

const PROTOCOL_OPTIONS = ['PLAINTEXT', 'SASL_PLAINTEXT', 'SASL_SSL'].map(
  (v) => ({ label: v, value: v }),
);

const MECHANISM_OPTIONS = ['PLAIN', 'SCRAM-SHA-256', 'SCRAM-SHA-512'].map(
  (v) => ({ label: v, value: v }),
);

function fieldOptions() {
  return (props.inputFields ?? []).map((field) => ({
    value: field.name!,
    label: `${field.name}（${field.dataType ?? '-'}）`,
  }));
}

onMounted(() => {
  const defaults: Record<string, any> = {
    keyStrategy: 'none',
    acks: '1',
    compressionType: 'none',
    securityProtocol: 'PLAINTEXT',
    saslMechanism: 'PLAIN',
  };
  Object.entries(defaults).forEach(([key, value]) => {
    if (props.config[key] == null) {
      props.config[key] = value;
    }
  });
});
</script>

<template>
  <div class="etl-editor">
    <div class="form-item">
      <label class="form-item__label form-item__label--required">
        Kafka 服务地址
      </label>
      <Input
        v-model:value="config.bootstrapServers"
        allow-clear
        placeholder="127.0.0.1:9092，多个逗号分隔"
      />
    </div>
    <div class="form-item">
      <label class="form-item__label form-item__label--required">Topic</label>
      <Input v-model:value="config.topic" allow-clear />
    </div>
    <div class="form-item">
      <label class="form-item__label">消息键策略</label>
      <RadioGroup
        v-model:value="config.keyStrategy"
        :options="KEY_OPTIONS"
        option-type="button"
      />
    </div>
    <div v-if="config.keyStrategy === 'field'" class="form-item">
      <label class="form-item__label form-item__label--required">键字段</label>
      <AutoComplete
        v-model:value="config.keyField"
        :options="fieldOptions()"
        placeholder="选择或输入字段名"
      />
    </div>
    <div class="flex gap-4">
      <div class="form-item flex-1">
        <label class="form-item__label">确认级别（acks）</label>
        <Select v-model:value="config.acks" :options="ACKS_OPTIONS" />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label">压缩方式</label>
        <Select
          v-model:value="config.compressionType"
          :options="COMPRESSION_OPTIONS"
        />
      </div>
    </div>
    <div class="editor-section">安全配置</div>
    <div class="form-item">
      <label class="form-item__label">安全协议</label>
      <Select
        v-model:value="config.securityProtocol"
        :options="PROTOCOL_OPTIONS"
      />
    </div>
    <template v-if="config.securityProtocol !== 'PLAINTEXT'">
      <div class="form-item">
        <label class="form-item__label">SASL 机制</label>
        <Select
          v-model:value="config.saslMechanism"
          :options="MECHANISM_OPTIONS"
        />
      </div>
      <div class="flex gap-4">
        <div class="form-item flex-1">
          <label class="form-item__label">用户名</label>
          <Input v-model:value="config.saslUsername" allow-clear />
        </div>
        <div class="form-item flex-1">
          <label class="form-item__label">密码</label>
          <Input.Password v-model:value="config.saslPassword" allow-clear />
        </div>
      </div>
    </template>
  </div>
</template>
