<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import { onMounted } from 'vue';

import { Input, InputNumber, RadioGroup, Select } from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeReadKafka' });

const props = defineProps<{
  config: Record<string, any>;
}>();

const FORMAT_OPTIONS = [
  { label: 'JSON（展开字段）', value: 'JSON' },
  { label: '纯文本（value 字段）', value: 'STRING' },
];

const OFFSET_OPTIONS = [
  { label: '最早（earliest）', value: 'earliest' },
  { label: '最新（latest）', value: 'latest' },
];

const PROTOCOL_OPTIONS = ['PLAINTEXT', 'SASL_PLAINTEXT', 'SASL_SSL'].map(
  (v) => ({ label: v, value: v }),
);

const MECHANISM_OPTIONS = ['PLAIN', 'SCRAM-SHA-256', 'SCRAM-SHA-512'].map(
  (v) => ({ label: v, value: v }),
);

onMounted(() => {
  const defaults: Record<string, any> = {
    messageFormat: 'JSON',
    autoOffsetReset: 'earliest',
    maxMessageCount: 100_000,
    pollTimeoutMs: 3000,
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
      <label class="form-item__label">消费组（空则自动生成）</label>
      <Input
        v-model:value="config.groupId"
        allow-clear
        placeholder="etl-group"
      />
    </div>
    <div class="flex gap-4">
      <div class="form-item flex-1">
        <label class="form-item__label">起始位点</label>
        <Select
          v-model:value="config.autoOffsetReset"
          :options="OFFSET_OPTIONS"
        />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label">消息格式</label>
        <RadioGroup
          v-model:value="config.messageFormat"
          :options="FORMAT_OPTIONS"
          option-type="button"
        />
      </div>
    </div>
    <div class="flex gap-4">
      <div class="form-item flex-1">
        <label class="form-item__label">最大读取条数</label>
        <InputNumber
          v-model:value="config.maxMessageCount"
          :min="1"
          class="w-full"
        />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label">单次拉取超时（毫秒）</label>
        <InputNumber
          v-model:value="config.pollTimeoutMs"
          :min="100"
          class="w-full"
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
