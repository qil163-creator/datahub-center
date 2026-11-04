<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 仅编辑设计器隔离草稿，保存由外层统一负责。 */
import type { DataforgeDataApiApi } from '#/api/dataforge/dataapi';
import type { IntegrationApi } from '#/api/dataforge/integration';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Button,
  Checkbox,
  Input,
  InputNumber,
  Radio,
  RadioGroup,
  Select,
  Switch,
  TextArea,
} from 'antdv-next';

import { getIntegrationHttpMetadata } from '#/api/dataforge/integration/service-metadata';
import { useObjectRequest } from '#/hooks/use-object-request';
import {
  defaultApiConfig,
  validateApiConfig,
} from '#/views/dataforge/dataapi/modules/api-config';

import ReferenceFieldMergeDialog from './reference-field-merge-dialog.vue';

import './reference-service-editor.css';

const props = defineProps<{
  config: Record<string, any>;
  disabled?: boolean;
  revision?: number;
  taskId?: IntegrationApi.Id;
}>();
const step = ref(0);
const selectedColumns = ref<number[]>([]);
const pendingColumns = ref<Record<string, any>[]>([]);
const mergeOpen = ref(false);
const issue = ref('');
const analyzing = ref(false);
const analysisWarnings = ref<string[]>([]);
const nodeVersion = ref(0);
watch(
  () => props.config,
  () => {
    nodeVersion.value++;
    selectedColumns.value = [];
    mergeOpen.value = false;
    pendingColumns.value = [];
  },
  { flush: 'sync' },
);
const config = computed<DataforgeDataApiApi.DataApi>(
  () => props.config.apiConfig ?? {},
);
const dataPaths = computed(
  () => config.value.dataKeys ?? [config.value.dataKey ?? ''],
);
const columns = computed(() => config.value.dataColumns ?? []);
const columnTypes = [
  { label: 'STRING', value: 12 },
  { label: 'INTEGER', value: 4 },
  { label: 'LONG', value: -5 },
  { label: 'DOUBLE', value: 8 },
  { label: 'DECIMAL', value: 3 },
  { label: 'DATE', value: 91 },
  { label: 'TIMESTAMP', value: 93 },
  { label: 'BOOLEAN', value: 16 },
];
const request = useObjectRequest(
  () =>
    JSON.stringify([
      props.taskId,
      nodeVersion.value,
      props.revision,
      props.disabled,
      props.config.resourceBindingId,
      config.value.requestUrl,
      config.value.requestMethod,
      config.value.requestHeader,
      config.value.requestParam,
      config.value.dataKey,
      config.value.dataKeys,
    ]),
  () => {
    analyzing.value = false;
    analysisWarnings.value = [];
    mergeOpen.value = false;
    pendingColumns.value = [];
  },
);
function setApi(key: keyof DataforgeDataApiApi.DataApi, value: unknown) {
  if (!props.disabled)
    props.config.apiConfig = {
      ...defaultApiConfig(config.value),
      [key]: value,
    };
}
function setNode(key: string, value: unknown) {
  if (!props.disabled) props.config[key] = value;
}
function updatePath(index: number, value: unknown) {
  setApi(
    'dataKeys',
    dataPaths.value.map((path, i) =>
      i === index ? String(value ?? '') : path,
    ),
  );
}
function removePath(index: number) {
  setApi(
    'dataKeys',
    dataPaths.value.filter((_, i) => i !== index),
  );
}
function pairs(key: 'requestHeader' | 'requestParam') {
  return config.value[key] ?? [];
}
function updatePair(
  key: 'requestHeader' | 'requestParam',
  index: number,
  field: string,
  value: string,
) {
  setApi(
    key,
    pairs(key).map((row, i) =>
      i === index ? { ...row, [field]: value } : { ...row },
    ),
  );
}
function addPair(key: 'requestHeader' | 'requestParam') {
  setApi(key, [...pairs(key), { name: '', value: '' }]);
}
function removePair(key: 'requestHeader' | 'requestParam', index: number) {
  setApi(
    key,
    pairs(key).filter((_, i) => i !== index),
  );
}
function updateColumn(index: number, key: string, value: unknown) {
  setApi(
    'dataColumns',
    columns.value.map((row, i) =>
      i === index ? { ...row, [key]: value } : { ...row },
    ),
  );
}
function nextStep() {
  if (step.value === 0) {
    issue.value =
      validateApiConfig(defaultApiConfig(config.value), {
        requireColumns: false,
        requireName: false,
      }) ?? '';
    if (config.value.dataKeys?.some((path) => !path.trim()))
      issue.value = '返回数据路径不能为空';
    if (issue.value) return;
  }
  step.value++;
}
function validate() {
  issue.value =
    validateApiConfig(defaultApiConfig(config.value), {
      requireColumns: true,
      requireName: false,
    }) ?? '';
  if (
    !issue.value &&
    columns.value.some(
      (column) => column.fieldType === undefined || column.fieldType === null,
    )
  )
    issue.value = '部分输出字段类型尚未确定，请手动确认';
  if (
    !issue.value &&
    config.value.dataKeys &&
    (config.value.dataKeys.length === 0 ||
      config.value.dataKeys.length > 32 ||
      config.value.dataKeys.some((path) => !path.trim()) ||
      new Set(config.value.dataKeys).size !== config.value.dataKeys.length)
  )
    issue.value = '返回数据路径不能为空或重复，最多配置32项';
  if (issue.value) step.value = config.value.requestUrl ? 3 : 0;
  return !issue.value;
}
async function analyze() {
  if (props.disabled || analyzing.value) return;
  issue.value = '';
  analysisWarnings.value = [];
  if (props.taskId === undefined || props.revision === undefined) {
    issue.value = '请先保存任务，再分析接口输出字段';
    return;
  }
  if (!props.config.resourceBindingId) {
    issue.value = '当前节点尚未关联任务授权的接口执行资源，暂不能分析输出字段';
    return;
  }
  const ticket = request.begin();
  analyzing.value = true;
  try {
    const result = await getIntegrationHttpMetadata({
      id: props.taskId,
      revision: props.revision,
      resourceBindingId: props.config.resourceBindingId,
      apiConfig: defaultApiConfig(config.value),
    });
    if (!ticket() || props.disabled) return;
    analysisWarnings.value = result.warnings ?? [];
    const discovered = result.fields.map((field) => ({
      fieldName: field.name,
      fieldType: jdbcType(field.dataType),
      ...(field.nullable === undefined || field.nullable === null
        ? {}
        : { nullable: field.nullable }),
    }));
    if (columns.value.length > 0) {
      pendingColumns.value = discovered;
      mergeOpen.value = true;
    } else setApi('dataColumns', discovered);
    if (result.partial)
      analysisWarnings.value.push(
        '字段来自有限响应采样，请核对类型；不会据此推断长度、精度或必填属性。',
      );
  } catch (error) {
    if (ticket())
      issue.value = error instanceof Error ? error.message : '接口字段分析失败';
  } finally {
    if (ticket()) analyzing.value = false;
  }
}
function mergeColumns(mode: 'addAll' | 'addNewOnly' | 'clearAndAddAll') {
  if (props.disabled) return;
  const known = new Set(columns.value.map((column) => column.fieldName));
  const incoming = pendingColumns.value.map((column) => ({ ...column }));
  setApi(
    'dataColumns',
    mode === 'clearAndAddAll'
      ? incoming
      : [
          ...columns.value,
          ...incoming.filter(
            (column) => mode === 'addAll' || !known.has(column.fieldName),
          ),
        ],
  );
  selectedColumns.value = [];
  pendingColumns.value = [];
}
function toggleColumn(index: number, checked: boolean) {
  selectedColumns.value = checked
    ? [...new Set([...selectedColumns.value, index])]
    : selectedColumns.value.filter((value) => value !== index);
}
function removeSelectedColumns() {
  setApi(
    'dataColumns',
    columns.value.filter((_, index) => !selectedColumns.value.includes(index)),
  );
  selectedColumns.value = [];
}
function jdbcType(type?: null | string) {
  const value = (type ?? '').toUpperCase();
  if (['INT', 'INTEGER'].includes(value)) return 4;
  if (['BIGINT', 'LONG'].includes(value)) return -5;
  if (value === 'DECIMAL') return 3;
  if (['DOUBLE', 'FLOAT', 'NUMBER'].includes(value)) return 8;
  if (value === 'DATE') return 91;
  if (value === 'TIMESTAMP') return 93;
  if (['STRING', 'TEXT', 'VARCHAR'].includes(value)) return 12;
  if (value === 'BOOLEAN') return 16;
  return undefined;
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
  <div class="qi-service-editor" data-component-code="rest-api">
    <nav class="qi-service-steps" aria-label="API配置步骤">
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
    <Alert v-if="issue" :message="issue" type="error" />
    <section v-show="step === 0">
      <slot name="basics"></slot>
      <div class="qi-service-form">
        <label class="required">API URL</label>
        <div class="qi-service-inline">
          <Select
            :value="config.requestMethod ?? 'GET'"
            :disabled="disabled"
            :options="[
              { label: 'GET', value: 'GET' },
              { label: 'POST', value: 'POST' },
            ]"
            aria-label="请求方式"
            @update:value="(value) => setApi('requestMethod', value)"
          />

          <Input
            :value="config.requestUrl"
            :disabled="disabled"
            placeholder="请输入API URL"
            aria-label="API URL"
            @update:value="(value) => setApi('requestUrl', value)"
          />
        </div>
        <span class="qi-service-form-hint">
          请指定API地址可用${变量id}获取变量值作为URL的一部分如:http://ip:80/api/${id}
        </span>
        <label class="required">返回数据格式</label><Select
          :value="config.dataType ?? 2"
          :disabled="disabled"
          :options="[
            { label: 'JSON对象(需要指定JSON中包含数据体所在字段)', value: 1 },
            { label: 'JSON数组(API返回的是一个JSON数组[])', value: 2 },
          ]"
          aria-label="返回数据格式"
          @update:value="(value) => setApi('dataType', value)"
        />
        <span class="qi-service-form-hint">
          指定返回数据的格式,普通字符串默认设置在data中第一行的responseBody字段中
        </span>
        <template v-if="config.dataType === 1">
          <label class="required">返回数据行所在字段</label>
          <div class="qi-service-data-paths">
            <div
              v-for="(path, index) in dataPaths"
              :key="index"
              class="qi-service-inline"
            >
              <Input
                :value="path"
                :disabled="disabled"
                placeholder="请输入返回数据行所在字段"
                :aria-label="`返回数据行所在字段${index + 1}`"
                @update:value="(value) => updatePath(index, value)"
              />
              <Button
                v-if="index === dataPaths.length - 1"
                :disabled="disabled || dataPaths.length >= 32"
                aria-label="增加返回数据路径"
                @click="setApi('dataKeys', [...dataPaths, ''])"
                >
＋
</Button>
              <Button
                v-if="index > 0"
                :disabled="disabled"
                :aria-label="`删除返回数据路径${index + 1}`"
                @click="removePath(index)"
                >
－
</Button>
            </div>
          </div>
          <span class="qi-service-form-hint">
            API返回的JSON对象如果数据行不在data字段中时请指定字段Id,详细语法请参考：
            <a
              href="https://docs.apifox.com/jsonpath"
              target="_blank"
              rel="noopener noreferrer"
              >https://docs.apifox.com/jsonpath</a>
          </span>
        </template>
        <label class="required">分页读取</label><RadioGroup
          :value="config.ifPage ?? 0"
          :disabled="disabled"
          @update:value="(value) => setApi('ifPage', value)"
        >
          <Radio :value="0">否</Radio><Radio :value="1">是</Radio>
        </RadioGroup>
        <span class="qi-service-form-hint">
          是表示分页循环读取,同时必须在API输入参数指定${pageNo}变量作为API的输入参数,否则不能自动分页
        </span>
        <template v-if="config.ifPage === 1">
          <label class="required">分页参数</label>
          <div class="qi-service-inline">
            <Input
              :value="config.pageKey"
              :disabled="disabled"
              placeholder="页码参数"
              aria-label="页码参数"
              @update:value="(value) => setApi('pageKey', value)"
            /><Input
              :value="config.pageSizeKey"
              :disabled="disabled"
              placeholder="每页条数参数"
              aria-label="每页条数参数"
              @update:value="(value) => setApi('pageSizeKey', value)"
            />
          </div>
          <label>最大读取页数</label><InputNumber
            :value="props.config.maxPages ?? 10"
            :min="1"
            :max="1000"
            :disabled="disabled"
            aria-label="最大读取页数"
            @update:value="(value) => setNode('maxPages', value)"
          /><label>每页条数</label><InputNumber
            :value="props.config.fetchSize ?? 100"
            :min="1"
            :disabled="disabled"
            aria-label="每页条数"
            @update:value="(value) => setNode('fetchSize', value)"
          />
        </template>
        <label>读取模式</label><RadioGroup value="FULL" :disabled="disabled">
          <Radio value="FULL">全量</Radio><Radio
            value="TIME"
            disabled
            title="当前执行协议未支持持久时间增量游标"
          >
            时间增量更新
          </Radio>
        </RadioGroup>
        <span class="qi-service-form-hint">
          全量读取全部数据，时间增量仅读取指定时间范围内的数据。
        </span>
      </div>
    </section>
    <template
      v-for="key in ['requestParam', 'requestHeader'] as const"
      :key="key"
    >
      <section v-show="step === (key === 'requestParam' ? 1 : 2)" class="qi-service-params">
        <div class="qi-service-toolbar">
          <Button type="primary" :disabled="disabled" @click="addPair(key)">
            + {{ key === 'requestParam' ? '添加参数' : '添加Header头' }}
          </Button>
        </div>
        <div
          class="qi-service-table-area"
          v-show="key === 'requestHeader' || config.bodyType !== 'raw'"
        >
          <table class="qi-reference-fields-table">
            <thead>
              <tr>
                <th>{{ key === 'requestParam' ? '参数字段' : 'header' }}</th>
                <th>参数值</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in pairs(key)" :key="index">
                <td>
                  <Input
                    :value="row.name"
                    :disabled="disabled"
                    :aria-label="`${key}参数字段${index + 1}`"
                    @update:value="
                      (value) =>
                        updatePair(key, index, 'name', String(value ?? ''))
                    "
                  />
                </td>
                <td>
                  <TextArea
                    :value="row.value"
                    :maxlength="256"
                    :auto-size="{ minRows: 1 }"
                    :show-count="true"
                    :disabled="disabled"
                    :aria-label="`${key}参数值${index + 1}`"
                    @update:value="
                      (value) =>
                        updatePair(key, index, 'value', String(value ?? ''))
                    "
                  />
                </td>
                <td>
                  <Button
                    type="link"
                    danger
                    :disabled="disabled"
                    @click="removePair(key, index)"
                  >
                    删除
                  </Button>
                </td>
              </tr>
              <tr v-if="!pairs(key).length">
                <td colspan="3" class="qi-reference-empty">暂无记录</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div
          v-if="key === 'requestParam' && config.bodyType === 'raw'"
          class="qi-service-body"
        >
          <p>自定义Body字符串：</p>
          <TextArea
            :value="config.bodyRaw"
            :rows="18"
            :disabled="disabled"
            aria-label="自定义Body字符串"
            @update:value="(value) => setApi('bodyRaw', value)"
          />
        </div>
        <div v-if="key === 'requestParam'" class="qi-service-body-switch">
          <span v-if="config.bodyType !== 'raw'">说明: 分页读取参数传基本属性中设置的值例如&#36;{pageNo}</span>
          <Switch
            :checked="config.bodyType === 'raw'"
            :disabled="disabled || config.requestMethod !== 'POST'"
            checked-children="Body请求参数"
            un-checked-children="表单请求参数"
            aria-label="Body请求参数"
            @update:checked="
              (value) => setApi('bodyType', value ? 'raw' : 'none')
            "
          />
        </div>
        <details
          v-if="key === 'requestParam' && config.requestMethod === 'POST'"
          class="qi-reference-advanced"
        >
          <summary>请求体配置</summary>
          <div class="qi-service-form">
            <label>请求体类型</label><Select
              :value="config.bodyType ?? 'none'"
              :disabled="disabled"
              :options="
                ['none', 'form-data', 'urlencoded', 'raw'].map((value) => ({
                  value,
                  label: value,
                }))
              "
              @update:value="(value) => setApi('bodyType', value)"
            /><template v-if="config.bodyType === 'raw'">
              <label>JSON请求体</label><TextArea
                :value="config.bodyRaw"
                :rows="6"
                :disabled="disabled"
                @update:value="(value) => setApi('bodyRaw', value)"
              />
</template><template
              v-else-if="
                config.bodyType === 'form-data' ||
                config.bodyType === 'urlencoded'
              "
            >
              <label>请求体参数</label><TextArea
                :value="
                  JSON.stringify(
                    config.bodyType === 'form-data'
                      ? (config.bodyFormData ?? [])
                      : (config.bodyUrlencoded ?? []),
                    null,
                    2,
                  )
                "
                :rows="6"
                :disabled="disabled"
                @update:value="
                  (value) => {
                    try {
                      const rows = JSON.parse(String(value));
                      if (Array.isArray(rows))
                        setApi(
                          config.bodyType === 'form-data'
                            ? 'bodyFormData'
                            : 'bodyUrlencoded',
                          rows,
                        );
                    } catch {
                      issue = '请求体参数须为JSON数组';
                    }
                  }
                "
              />
            </template>
          </div>
        </details>
      </section>
    </template>
    <section v-show="step === 3">
      <div class="qi-service-toolbar">
        <Button
          type="primary"
          :disabled="disabled"
          @click="
            setApi('dataColumns', [
              ...columns,
              { fieldName: '', fieldType: 12 },
            ])
          "
        >
          添加字段
        </Button>
        <Button
          danger
          type="primary"
          :disabled="disabled || !selectedColumns.length"
          @click="removeSelectedColumns"
        >
          删除字段
        </Button>
        <Button :loading="analyzing" :disabled="disabled" @click="analyze">
          从结果JSON中自动分析
        </Button>
      </div>
      <Alert
        v-for="warning in analysisWarnings"
        :key="warning"
        type="info"
        :message="warning"
      />
      <div class="qi-service-table-area qi-service-output-table">
        <table class="qi-reference-fields-table">
          <thead>
            <tr>
              <th>
                <Checkbox
                  :checked="
                    !!columns.length &&
                    selectedColumns.length === columns.length
                  "
                  :indeterminate="
                    selectedColumns.length > 0 &&
                    selectedColumns.length < columns.length
                  "
                  :disabled="disabled"
                  aria-label="选择全部字段"
                  @update:checked="
                    (checked) =>
                      (selectedColumns = checked
                        ? columns.map((_, index) => index)
                        : [])
                  "
                />
              </th>
              <th>序号</th>
              <th>字段名称</th>
              <th>字段类型</th>
              <th>字段格式</th>
              <th>缺省值</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in columns" :key="index">
              <td>
                <Checkbox
                  :checked="selectedColumns.includes(index)"
                  :disabled="disabled"
                  :aria-label="`选择字段${index + 1}`"
                  @update:checked="(checked) => toggleColumn(index, checked)"
                />
              </td>
              <td>{{ index + 1 }}</td>
              <td>
                <Input
                  :value="row.fieldName"
                  :disabled="disabled"
                  :aria-label="`输出字段名称${index + 1}`"
                  @update:value="
                    (value) => updateColumn(index, 'fieldName', value)
                  "
                />
              </td>
              <td>
                <Select
                  :value="row.fieldType"
                  :options="columnTypes"
                  :disabled="disabled"
                  :aria-label="`输出字段类型${index + 1}`"
                  @update:value="
                    (value) => updateColumn(index, 'fieldType', value)
                  "
                />
              </td>
              <td>
                <Input
                  :value="(row as any).format"
                  :disabled="disabled"
                  :aria-label="`输出字段格式${index + 1}`"
                  @update:value="
                    (value) => updateColumn(index, 'format', value)
                  "
                />
              </td>
              <td>
                <Input
                  :value="(row as any).defaultValue"
                  :disabled="disabled"
                  :aria-label="`输出字段缺省值${index + 1}`"
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
                    setApi(
                      'dataColumns',
                      columns.filter((_, i) => i !== index),
                    )
                  "
                >
                  删除
                </Button>
              </td>
            </tr>
            <tr v-if="!columns.length">
              <td colspan="7" class="qi-reference-empty">暂无记录</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <ReferenceFieldMergeDialog
      v-model:open="mergeOpen"
      :disabled="disabled"
      :existing-count="columns.length"
      :incoming-count="pendingColumns.length"
      @resolve="mergeColumns"
    />
  </div>
</template>
