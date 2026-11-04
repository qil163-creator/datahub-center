<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 仅编辑父设计器隔离草稿，保存由外层统一负责。 */
import type { IntegrationApi } from '#/api/dataforge/integration';
import type { WebServiceMetadata } from '#/api/dataforge/integration/service-metadata';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Button,
  Input,
  InputNumber,
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
const parsedUrl = ref('');
watch(
  () => props.config,
  () => {
    nodeVersion.value++;
    parsedUrl.value = '';
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
function selectService(name: unknown) {
  if (props.disabled) return;
  set('serviceName', name);
  set('portName', '');
  set('operationName', '');
}
function selectPort(name: unknown) {
  if (props.disabled) return;
  set('portName', name);
  set('operationName', '');
  if (port.value) set('apiMethod', port.value.apiMethod);
}
const operationChoices = computed(() => {
  const names = new Set<string>();
  for (const entry of metadata.value?.services ?? [])
    for (const item of entry.ports)
      for (const op of item.operations) names.add(op.name);
  if (props.config.operationName) names.add(props.config.operationName);
  return [...names].map((name) => ({ label: name, value: name }));
});
function selectOperation(name: unknown) {
  if (props.disabled) return;
  const matches = [];
  for (const entry of metadata.value?.services ?? [])
    for (const item of entry.ports) {
      const selected = item.operations.find((op) => op.name === name);
      if (selected) matches.push({ entry, item, selected });
    }
  const match =
    matches.find(({ item }) => item === port.value) ??
    (matches.length === 1 ? matches[0] : undefined);
  if (!match && matches.length > 1) {
    issue.value = '存在同名接口，请先在高级配置中选择服务和端口';
    return;
  }
  issue.value = '';
  set('operationName', name);
  if (!match) return;
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
// The reference parses the WSDL once the URL is committed (blur or Enter).
function commitUrl() {
  const url = String(props.config.apiUrl ?? '').trim();
  if (!url || url === parsedUrl.value) return;
  void parseWsdl();
}
async function parseWsdl() {
  if (props.disabled || parsing.value) return;
  parsedUrl.value = String(props.config.apiUrl ?? '').trim();
  issue.value = '';
  if (props.taskId === undefined || props.revision === undefined) {
    issue.value = '请先保存任务，再解析WSDL';
    return;
  }
  if (!props.config.resourceBindingId) {
    issue.value = '请在高级执行配置中填写当前任务授权的执行资源编号';
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
    if (ticket()) {
      parsedUrl.value = '';
      issue.value = error instanceof Error ? error.message : 'WSDL解析失败';
    }
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
// HTTP bindings are only shown for a WSDL that declares them; SOAP is selectable.
const methodOptions = computed(() =>
  [
    { label: 'SOAP 1.1', value: 'soap11' },
    { label: 'SOAP 1.2', value: 'soap12' },
    { label: 'HTTP GET', value: 'httpGet' },
    { label: 'HTTP POST', value: 'httpPost' },
  ].map((item) => ({
    ...item,
    disabled:
      item.value.startsWith('http') && props.config.apiMethod !== item.value,
  })),
);
function options<T extends { name: string }>(items?: T[], current?: string) {
  const result = (items ?? []).map((item) => ({
    label: item.name,
    value: item.name,
  }));
  if (current && !result.some((item) => item.value === current))
    result.push({ label: current, value: current });
  return result;
}
function validate() {
  const errors = validateConnectorConfig('read-webservice', props.config);
  issue.value = errors.map((error) => error.message).join('；');
  return errors.length === 0;
}
// 上一步/下一步由设计器弹窗页脚渲染，与参考界面一致。
const stepNav = computed(() => ({
  last: 3,
  next: nextStep,
  prev: () => {
    if (step.value > 0) step.value--;
  },
  step: step.value,
}));
defineExpose({ stepNav, validate });
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
            @blur="commitUrl"
            @press-enter="commitUrl"
          />
        </div>
        <span class="qi-service-form-hint">
          输入 WSDL 地址后自动解析服务、端口、端点地址、请求接口和请求方式。
        </span>
        <label class="required">请求接口</label><Select
          :value="config.operationName"
          :disabled="disabled || (!metadata && !config.operationName)"
          :options="operationChoices"
          placeholder="请先解析 WSDL"
          aria-label="WSDL请求接口"
          @update:value="selectOperation"
        />
        <label class="required">请求方式</label><Select
          :value="config.apiMethod"
          :disabled="disabled || (!port && !config.apiMethod)"
          :options="methodOptions"
          placeholder="请选择请求方式"
          aria-label="WSDL请求方式"
          @update:value="(value) => set('apiMethod', value)"
        />
        <template v-if="config.apiMethod && (config.serviceName || port)">
          <label>服务</label><Input
            :value="config.serviceName"
            disabled
            aria-label="WSDL服务名称"
          />
          <label>端口</label><Input
            :value="config.portName"
            disabled
            aria-label="WSDL端口名称"
          />
          <label>端点地址</label><Input
            :value="port?.endpoint"
            disabled
            aria-label="WSDL端点地址回显"
          />
        </template>
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
      <section v-show="step === (key === 'requestParams' ? 1 : 2)" class="qi-service-params">
        <div class="qi-service-toolbar">
          <Button
            type="primary"
            :disabled="disabled"
            @click="updateParameter(key, '', '', '')"
          >
            + {{ key === 'requestParams' ? '添加参数' : '添加Header头' }}
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
    <details class="qi-reference-advanced">
      <summary>高级执行配置</summary>
      <div class="qi-service-form">
        <label>服务</label><Select
          :value="config.serviceName"
          :disabled="disabled"
          :options="options(metadata?.services, config.serviceName)"
          placeholder="-"
          aria-label="WSDL服务"
          @update:value="selectService"
        />
        <label>端口</label><Select
          :value="config.portName"
          :disabled="disabled"
          :options="options(service?.ports, config.portName)"
          placeholder="-"
          aria-label="WSDL端口"
          @update:value="selectPort"
        />
        <label>端点地址</label><Input
          :value="port?.endpoint"
          disabled
          placeholder="-"
          aria-label="WSDL端点地址"
        />
        <label>命名空间</label><Input
          :value="config.targetNamespace"
          :disabled="disabled"
          placeholder="-"
          aria-label="WSDL命名空间"
          @update:value="(value) => set('targetNamespace', value)"
        />
        <label class="required">执行资源编号</label><Input
          :value="config.resourceBindingId"
          :disabled="disabled"
          aria-label="WSDL执行资源编号"
          @update:value="(value) => set('resourceBindingId', value)"
        /><label>最多读取行数</label><InputNumber
          :value="config.maxRows ?? 10000"
          :min="1"
          :disabled="disabled"
          @update:value="(value) => set('maxRows', value)"
        /><label>响应字节上限</label><InputNumber
          :value="config.maxResponseBytes ?? 8388608"
          :min="1"
          :disabled="disabled"
          @update:value="(value) => set('maxResponseBytes', value)"
        /><label>请求头凭据引用</label><TextArea
          :value="
            typeof config.headerSecretRefs === 'string'
              ? config.headerSecretRefs
              : JSON.stringify(config.headerSecretRefs ?? {}, null, 2)
          "
          :rows="3"
          :disabled="disabled"
          @update:value="(value) => set('headerSecretRefs', value)"
        />
      </div>
    </details>
  </div>
</template>
