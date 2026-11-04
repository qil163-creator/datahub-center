<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 仅编辑父设计器隔离草稿，保存由外层统一负责。 */
import type { IntegrationApi } from '#/api/dataforge/integration';
import type { WebServiceMetadata } from '#/api/dataforge/integration/service-metadata';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Button,
  Input,
  Select,
  TextArea,
} from 'antdv-next';

import { getIntegrationWebServiceMetadata } from '#/api/dataforge/integration/service-metadata';
import { useObjectRequest } from '#/hooks/use-object-request';

import { validateConnectorConfig } from './connector-schema';
import { referenceFieldTypes } from './reference-node-schema';

import './reference-node-editor.css';
import './reference-service-editor.css';

const props = defineProps<{
  config: Record<string, any>;
  disabled?: boolean;
  revision?: number;
  taskId?: IntegrationApi.Id;
}>();
const step = ref(0);
const parsing = ref(false);
const issue = ref('');
const metadata = ref<WebServiceMetadata>();
const nodeVersion = ref(0);
watch(
  () => props.config,
  () => {
    nodeVersion.value++;
  },
  { flush: 'sync' },
);
const service = computed(() =>
  metadata.value?.services.find(
    (item) => item.name === props.config.serviceName,
  ),
);
const port = computed(() =>
  service.value?.ports.find((item) => item.name === props.config.portName),
);
const operation = computed(() =>
  port.value?.operations.find(
    (item) => item.name === props.config.operationName,
  ),
);
const columns = computed<Record<string, any>[]>(() =>
  Array.isArray(props.config.columns) ? props.config.columns : [],
);
const request = useObjectRequest(
  () =>
    JSON.stringify([
      props.taskId,
      nodeVersion.value,
      props.revision,
      props.disabled,
      props.config.apiUrl,
      props.config.resourceBindingId,
    ]),
  () => {
    metadata.value = undefined;
    parsing.value = false;
  },
);
function set(key: string, value: unknown) {
  if (!props.disabled) props.config[key] = value;
}
function parameters(
  key: 'headerParams' | 'requestParams',
): Record<string, unknown> {
  const raw = props.config[key];
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) return raw;
  try {
    const value: unknown = JSON.parse(raw || '{}');
    return value && typeof value === 'object' && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : {};
  } catch {
    return {};
  }
}
function updateParameter(
  key: 'headerParams' | 'requestParams',
  oldName: string,
  name: string,
  value: unknown,
) {
  const next = { ...parameters(key) };
  if (oldName !== name) Reflect.deleteProperty(next, oldName);
  next[name] = value;
  set(key, JSON.stringify(next, null, 2));
}
function removeParameter(key: 'headerParams' | 'requestParams', name: string) {
  const next = { ...parameters(key) };
  Reflect.deleteProperty(next, name);
  set(key, JSON.stringify(next, null, 2));
}
function updateColumn(index: number, key: string, value: unknown) {
  set(
    'columns',
    columns.value.map((row, i) =>
      i === index ? { ...row, [key]: value } : { ...row },
    ),
  );
}
const serviceOperations = computed(() =>
  (metadata.value?.services ?? []).flatMap((entry) =>
    entry.ports.flatMap((item) =>
      item.operations.map((selected) => ({
        entry,
        item,
        selected,
        value: JSON.stringify([entry.name, item.name, selected.name]),
      })),
    ),
  ),
);
const selectedOperationValue = computed(() => {
  const matches = serviceOperations.value.filter(
    ({ selected }) => selected.name === props.config.operationName,
  );
  const match =
    matches.find(
      ({ entry, item }) =>
        entry.name === props.config.serviceName &&
        item.name === props.config.portName,
    ) ?? (matches.length === 1 ? matches[0] : undefined);
  return match?.value ?? props.config.operationName;
});
const operationChoices = computed(() => {
  const choices = serviceOperations.value.map(
    ({ entry, item, selected, value }) => ({
      label: serviceOperations.value.some(
        (candidate) =>
          candidate.value !== value && candidate.selected.name === selected.name,
      )
        ? `${selected.name}（${entry.name} / ${item.name}）`
        : selected.name,
      value,
    }),
  );
  if (
    props.config.operationName &&
    !choices.some((choice) => choice.value === selectedOperationValue.value)
  )
    choices.push({
      label: props.config.operationName,
      value: props.config.operationName,
    });
  return choices;
});
function selectOperation(value: unknown) {
  if (props.disabled) return;
  const matches = serviceOperations.value.filter(
    ({ selected }) => selected.name === value,
  );
  const match =
    serviceOperations.value.find((candidate) => candidate.value === value) ??
    (matches.length === 1 ? matches[0] : undefined);
  if (!match && matches.length > 1) {
    issue.value = '存在同名接口，请选择包含服务和端口名称的请求接口';
    return;
  }
  issue.value = '';
  if (!match) return;
  set('operationName', match.selected.name);
  set('serviceName', match.entry.name);
  set('portName', match.item.name);
  set('apiMethod', match.item.apiMethod);
  set('targetNamespace', match.selected.targetNamespace);
}
function nextStep() {
  if (
    step.value === 0 &&
    (!props.config.apiUrl ||
      !props.config.operationName ||
      !props.config.apiMethod)
  ) {
    issue.value = '请先解析WSDL并选择请求接口、请求方式';
    return;
  }
  issue.value = '';
  step.value++;
}
async function parseWsdl() {
  if (props.disabled || parsing.value) return;
  issue.value = '';
  if (props.taskId === undefined || props.revision === undefined) {
    issue.value = '请先保存任务，再解析WSDL';
    return;
  }
  if (!props.config.resourceBindingId) {
    issue.value = '当前节点尚未关联任务授权的执行资源，暂不能解析WSDL';
    return;
  }
  const ticket = request.begin();
  parsing.value = true;
  try {
    const result = await getIntegrationWebServiceMetadata({
      id: props.taskId,
      revision: props.revision,
      resourceBindingId: props.config.resourceBindingId,
      apiUrl: String(props.config.apiUrl ?? ''),
    });
    if (!ticket() || props.disabled) return;
    metadata.value = result;
  } catch (error) {
    if (ticket())
      issue.value = error instanceof Error ? error.message : 'WSDL解析失败';
  } finally {
    if (ticket()) parsing.value = false;
  }
}
function useDeclaredParameters() {
  if (props.disabled || !operation.value) return;
  const current = parameters('requestParams');
  const next = { ...current };
  for (const parameter of operation.value.parameters)
    if (!(parameter.name in next)) next[parameter.name] = '';
  set('requestParams', JSON.stringify(next, null, 2));
}
function validate() {
  const errors = validateConnectorConfig('read-webservice', props.config).filter(
    (error) =>
      error.field !== 'resourceBindingId' ||
      (props.config.resourceBindingId !== undefined &&
        props.config.resourceBindingId !== null &&
        props.config.resourceBindingId !== ''),
  );
  issue.value = errors.map((error) => error.message).join('；');
  return errors.length === 0;
}
defineExpose({ validate });
</script>

<template>
  <div class="qi-service-editor" data-component-code="read-webservice">
    <nav class="qi-service-steps" aria-label="WebService配置步骤">
      <button
        v-for="(label, index) in ['基本属性', '输入参数', 'Header', '输出字段']"
        :key="label"
        type="button"
        :class="{ active: step === index, complete: step > index }"
        :aria-current="step === index ? 'step' : undefined"
        :disabled="index > step"
        @click="step = index"
      >
        <span>{{ index + 1 }}</span>{{ label }}
      </button>
    </nav>
    <Alert v-if="issue" type="error" :message="issue" />
    <section v-show="step === 0">
      <slot name="basics"></slot>
      <div class="qi-service-form">
        <label class="required">API URL</label>
        <div class="qi-service-inline">
          <Input
            :value="config.apiUrl"
            :disabled="disabled"
            placeholder="请输入 WSDL URL"
            aria-label="WSDL URL"
            @update:value="(value) => set('apiUrl', value)"
          /><Button :loading="parsing" :disabled="disabled" @click="parseWsdl">
            解析WSDL
          </Button>
        </div>
        <label class="required">请求接口</label><Select
          :value="selectedOperationValue"
          :disabled="disabled || (!metadata && !config.operationName)"
          :options="operationChoices"
          placeholder="请先解析 WSDL"
          aria-label="WSDL请求接口"
          @update:value="selectOperation"
        />
        <label class="required">请求方式</label><Select
          :value="config.apiMethod"
          :disabled="disabled || (!port && !config.apiMethod)"
          :options="[
            { label: 'SOAP 1.1', value: 'soap11' },
            { label: 'SOAP 1.2', value: 'soap12' },
            { label: 'HTTP GET', value: 'httpGet' },
            { label: 'HTTP POST', value: 'httpPost' },
          ]"
          aria-label="WSDL请求方式"
          @update:value="(value) => set('apiMethod', value)"
        />
      </div>
      <slot name="description"></slot>
      <Alert
        v-for="warning in metadata?.warnings ?? []"
        :key="warning"
        type="info"
        :message="warning"
      />
    </section>
    <template
      v-for="key in ['requestParams', 'headerParams'] as const"
      :key="key"
    >
      <section v-show="step === (key === 'requestParams' ? 1 : 2)">
        <div class="qi-service-toolbar">
          <Button
            type="primary"
            :disabled="disabled"
            @click="updateParameter(key, '', '', '')"
          >
            {{ key === 'requestParams' ? '添加参数' : '添加Header头' }}
          </Button>
        </div>
        <div class="qi-service-table-area">
          <div
            v-if="key === 'requestParams' && operation"
            class="qi-service-step-actions"
          >
            <Button :disabled="disabled" @click="useDeclaredParameters">
              获取接口参数
            </Button>
          </div>
          <table class="qi-reference-fields-table">
            <thead>
              <tr>
                <th>{{ key === 'requestParams' ? '参数字段' : 'header' }}</th>
                <th v-if="key === 'requestParams'">参数类型</th>
                <th>参数值</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(value, name) in parameters(key)" :key="name">
                <td>
                  <Input
                    :value="name"
                    :disabled="disabled"
                    :aria-label="`${key}名称${name}`"
                    @update:value="
                      (next) =>
                        updateParameter(
                          key,
                          String(name),
                          String(next ?? ''),
                          value,
                        )
                    "
                  />
                </td>
                <td v-if="key === 'requestParams'">
                  {{
                    operation?.parameters.find(
                      (parameter) => parameter.name === name,
                    )?.dataType ?? '-'
                  }}
                </td>
                <td>
                  <TextArea
                    :value="String(value ?? '')"
                    :maxlength="256"
                    :auto-size="{ minRows: 1 }"
                    :show-count="true"
                    :disabled="disabled"
                    :aria-label="`${key}取值${name}`"
                    @update:value="
                      (next) =>
                        updateParameter(key, String(name), String(name), next)
                    "
                  />
                </td>
                <td>
                  <Button
                    type="link"
                    danger
                    :disabled="disabled"
                    @click="removeParameter(key, String(name))"
                  >
                    删除
                  </Button>
                </td>
              </tr>
              <tr v-if="!Object.keys(parameters(key)).length">
                <td
                  :colspan="key === 'requestParams' ? 4 : 3"
                  class="qi-reference-empty"
                >
                  暂无记录
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
    <section v-show="step === 3">
      <div class="qi-reference-section-header">
        <h3>输出字段</h3>
        <Button
          :disabled="disabled"
          @click="set('columns', [...columns, { name: '', type: '' }])"
        >
          新增
        </Button>
      </div>
      <div class="qi-service-table-area">
        <table class="qi-reference-fields-table">
          <thead>
            <tr>
              <th>序号</th>
              <th>字段名称</th>
              <th>字段类型</th>
              <th>字段格式</th>
              <th>缺省值</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(column, index) in columns" :key="index">
              <td>{{ index + 1 }}</td>
              <td>
                <Input
                  :value="column.name"
                  :disabled="disabled"
                  @update:value="(value) => updateColumn(index, 'name', value)"
                />
              </td>
              <td>
                <Select
                  :value="column.type"
                  :options="referenceFieldTypes"
                  :disabled="disabled"
                  @update:value="(value) => updateColumn(index, 'type', value)"
                />
              </td>
              <td>
                <Input
                  :value="column.format"
                  :disabled="disabled"
                  @update:value="
                    (value) => updateColumn(index, 'format', value)
                  "
                />
              </td>
              <td>
                <Input
                  :value="column.defaultValue"
                  :disabled="disabled"
                  @update:value="
                    (value) => updateColumn(index, 'defaultValue', value)
                  "
                />
              </td>
              <td>
                <Button
                  type="link"
                  danger
                  :disabled="disabled"
                  @click="
                    set(
                      'columns',
                      columns.filter((_, i) => i !== index),
                    )
                  "
                >
                  删除
                </Button>
              </td>
            </tr>
            <tr v-if="!columns.length">
              <td colspan="6" class="qi-reference-empty">暂无记录</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="qi-service-form">
        <label>返回数据格式</label><Select
          :value="config.returnFormat ?? 'XML'"
          :disabled="disabled"
          :options="[
            { label: 'XML', value: 'XML' },
            { label: 'JSON', value: 'JSON' },
          ]"
          @update:value="(value) => set('returnFormat', value)"
        /><label>返回数据行所在字段</label><Input
          :value="config.returnDataLine"
          :disabled="disabled"
          @update:value="(value) => set('returnDataLine', value)"
        />
      </div>
    </section>
    <div class="qi-service-step-actions">
      <Button v-if="step > 0" @click="step--">上一步</Button><Button v-if="step < 3" type="primary" @click="nextStep">下一步</Button>
    </div>
  </div>
</template>
