<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 仅编辑父设计器的节点草稿，由父级保存。 */
import { computed, defineAsyncComponent, ref, watch } from 'vue';

import {
  Alert,
  AutoComplete,
  Button,
  Input,
  InputNumber,
  Select,
  TextArea,
} from 'antdv-next';

import { validateNativeScript } from './native-script-schema';

import './reference-node-editor.css';
import './reference-transform-layout.css';

const props = defineProps<{
  config: Record<string, any>;
  disabled?: boolean;
  engine?: string;
  mode?: string;
  toolCode: string;
}>();
const emit=defineEmits<{ requestCheck: [] }>();
const CodeEditor = defineAsyncComponent(() =>
  import('#/components/monaco/sql-editor.vue').then((module) => module.default),
);

const errors = ref<string[]>([]);
const realtime = computed(() => props.mode === 'REALTIME');
const token = computed(() => props.toolCode === 'custom-token');
const realtimeBatch = computed(() => realtime.value && !token.value);
const fields = computed<Record<string, any>[]>(() =>
  Array.isArray(props.config.outputFields) ? props.config.outputFields : [],
);
const batchLabel = computed(() => {
  if (token.value) return '作业启动初始化';
  return realtimeBatch.value ? '条数或时间窗口' : '完整分区批次';
});
const batchOptions = computed(() => [
  {
    label: batchLabel.value,
    value: realtimeBatch.value ? 'COUNT_OR_TIME' : 'PARTITION',
  },
  ...(!props.config.batchPolicy ||
  props.config.batchPolicy === (realtimeBatch.value ? 'COUNT_OR_TIME' : 'PARTITION')
    ? []
    : [
        {
          label: `${props.config.batchPolicy}（与当前模式不符）`,
          value: props.config.batchPolicy,
          disabled: true,
        },
      ]),
]);
const types = [
  'STRING',
  'BIGINT',
  'INT',
  'DOUBLE',
  'BOOLEAN',
  'DATE',
  'TIMESTAMP(6)',
  'DECIMAL(38,18)',
].map((value) => ({ label: value, value }));
function set(key: string, value: unknown) {
  if (!props.disabled) props.config[key] = value;
}
function removeLegacy(key: string) {
  if (!props.disabled) Reflect.deleteProperty(props.config, key);
}
function addField() {
  if (!props.disabled)
    props.config.outputFields = [
      ...fields.value,
      { columnName: '', columnType: 'STRING' },
    ];
}
function removeField(index: number) {
  if (!props.disabled)
    props.config.outputFields = fields.value.filter(
      (_, position) => position !== index,
    );
}
function setField(field: Record<string, any>, key: string, value: unknown) {
  if (props.disabled) return;
  field[key] = value;
  let legacy: string | undefined;
  if (key === 'columnName') legacy = 'name';
  if (key === 'columnType') legacy = 'type';
  if (legacy && legacy in field) field[legacy] = value;
}
function nullableValue(field: Record<string, any>) {
  if (field.nullable === undefined || field.nullable === null) return undefined;
  return field.nullable ? 1 : 0;
}
function validate() {
  errors.value = validateNativeScript(props.config, props);
  return errors.value.length === 0;
}
watch(
  () => [props.config, props.mode, props.engine, props.toolCode],
  () => {
    errors.value = [];
  },
);
defineExpose({ validate });
</script>

<template>
  <div
    class="qi-reference-editor qi-transform-reference"
    :data-tool-code="toolCode"
  >
    <Alert v-if="errors.length" type="error" :message="errors.join('；')" />
    <div v-if="token" class="qi-reference-form-row">
      <label>返回token变量id</label>
      <div>
        <Input
          :value="config.tokenKey"
          :disabled="disabled"
          aria-label="脚本认证变量编号"
          placeholder="请输入返回token变量id"
          @update:value="(value) => set('tokenKey', value)"
        />
        <p class="qi-reference-notice">
          {{ realtime ? '认证结果写入此变量，用于已连接实时输入源的启动认证。' : '脚本认证成功返回的token将放入本变量中,API节点Header头中通过${变量id}引用' }}
        </p>
      </div>
    </div>
    <slot v-if="token" name="description"></slot>
    <div class="qi-script-compile">
      <Button
        type="primary"
        title="请使用设计器中的“检查”，结合当前执行资源及上下游验证脚本。"
        :auto-insert-space="false"
        :disabled="disabled"
        @click="emit('requestCheck')"
        >编译</Button
      >
    </div>
    <div class="qi-script-code" aria-label="原生脚本代码编辑器">
      <CodeEditor
        :model-value="config.code ?? ''"
        language="java"
        height="400px"
        :readonly="disabled"
        :minimap="true"
        :font-size="13"
        :line-numbers-min-chars="3"
        @update:model-value="(value) => set('code', value)"
      />
    </div>

    <template v-if="!token">
      <div class="qi-reference-section-header qi-reference-table-heading">
        <h3>输出字段</h3>
        <Button
          class="qi-reference-button qi-script-add-button"
          :auto-insert-space="false"
          :disabled="disabled"
          aria-label="新增输出字段"
          @click="addField"
          >+ 新增</Button
        >
      </div>
      <div class="qi-reference-fields-scroll">
        <table class="qi-reference-fields-table qi-script-output-fields">
          <thead>
            <tr>
              <th>序号</th>
              <th>字段名称</th>
              <th>字段类型</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(field, index) in fields" :key="index">
              <td>{{ index + 1 }}</td>
              <td>
                <Input
                  :value="field.columnName ?? field.name"
                  :disabled="disabled"
                  :aria-label="`脚本第${index + 1}行输出名称`"
                  placeholder="请输入字段名称"
                  @update:value="
                    (value) => setField(field, 'columnName', value)
                  "
                />
              </td>
              <td>
                <AutoComplete
                  :value="field.columnType ?? field.type"
                  :disabled="disabled"
                  :options="types"
                  :aria-label="`脚本第${index + 1}行输出类型`"
                  placeholder="请选择或输入"
                  @update:value="
                    (value) => setField(field, 'columnType', value)
                  "
                />
              </td>

              <td>
                <Button
                  :disabled="disabled"
                  type="link"
                  danger
                  @click="removeField(index)"
                  >
删除输出字段{{ index + 1 }}
</Button>
              </td>
            </tr>
            <tr v-if="!fields.length">
              <td colspan="4" class="qi-reference-empty">暂无记录</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="qi-reference-notice">
        不声明输出字段时继承输入字段；修改数据形状时请明确声明输出名称和类型。
      </p>
    </template>
    <details v-if="!token && fields.length" class="qi-transform-advanced">
<summary>输出字段空值契约</summary><template v-for="(field,index) in fields" :key="index">
<div class="qi-reference-form-row">
<label>{{ field.columnName ?? field.name }}允许空值</label>
                <Select
                  :value="nullableValue(field)"
                  :disabled="disabled"
                  :allow-clear="true"
                  :options="[
                    { label: '允许', value: 1 },
                    { label: '不允许', value: 0 },
                  ]"
                  :aria-label="`脚本第${index + 1}行允许空值`"
                  @update:value="
                    (value) =>
                      setField(
                        field,
                        'nullable',
                        value === undefined ? undefined : value === 1,
                      )
                  "
                />
</div>
</template>
</details>
    <details class="qi-transform-advanced">
      <summary>执行资源与批次配置</summary>
      <p class="qi-reference-notice">
        {{ token ? (realtime ? '认证脚本须使用管理员授权的执行资源，每次作业启动尝试仅初始化一次。作业启动时初始化认证，可连接表输入、Kafka输入或TDengine输入；认证节点不接收数据上游。' : '认证脚本须使用管理员授权的执行资源，每次作业启动尝试仅初始化一次。认证脚本返回字符串，并通过认证变量连接接口输入；当前仅支持无数据上游的初始化认证。') : '脚本须使用管理员授权的执行资源。每批独立运行；字段类型与返回数据必须一致。' }}
      </p>
      <div class="qi-reference-form-grid">
        <label>脚本资源编号<Input
            :value="config.resourceBindingId"
            :disabled="disabled"
            aria-label="脚本资源编号"
            @update:value="(value) => set('resourceBindingId', value)"
        /></label>
        <label>运行状态<Select
            :value="config.workerState"
            :disabled="disabled"
            :options="[{ label: '每批独立', value: 'BATCH' }]"
            aria-label="脚本运行状态"
            @update:value="(value) => set('workerState', value)"
        /></label>
        <label>批次策略<Select
            :value="config.batchPolicy"
            :disabled="disabled"
            :options="batchOptions"
            aria-label="脚本批次策略"
            @update:value="(value) => set('batchPolicy', value)"
        /></label>
        <label>最大批次行数<InputNumber
            :value="config.maximumRows"
            :disabled="disabled"
            :min="1"
            :max="10000"
            :precision="0"
            aria-label="脚本最大批次行数"
            @update:value="(value) => set('maximumRows', value)"
        /></label>
        <label v-if="realtimeBatch">最长等待毫秒<InputNumber
            :value="config.flushMillis"
            :disabled="disabled"
            :min="10"
            :max="300000"
            :precision="0"
            aria-label="脚本最长等待毫秒"
            @update:value="(value) => set('flushMillis', value)"
        /></label>
        <label v-if="config.classPath !== undefined">历史类路径<Input
            :value="config.classPath"
            disabled
            aria-label="脚本历史类路径"
        /></label>
      </div>
      <p class="qi-reference-notice">
        {{
          token
            ? '每次作业启动尝试初始化一次；算子从checkpoint恢复不重复获取认证。认证变量只在受管上下文中传递，不写入数据字段。'
            : realtimeBatch
            ? '达到条数或最长等待时间后处理当前批次。'
            : '分区数据超过最大批次行数时任务失败，请按数据规模明确设置预算。'
        }}
      </p>
      <Alert
        v-if="config.batchSize !== undefined && config.batchSize !== null"
        type="warning"
        message="历史批次行数尚未转换，请明确设置最大批次行数后清除旧值。"
        >
<template #action>
<Button :disabled="disabled" @click="removeLegacy('batchSize')">
清除历史批次行数
</Button>
</template>
</Alert>
      <Alert
        v-if="!realtimeBatch && config.flushMillis"
        type="warning"
        :message="token ? '初始化认证不使用条数或时间窗口，请明确移除旧等待时间并选择作业启动初始化。' : '当前离线模式不使用等待时间，请明确移除旧值。'"
        >
<template #action>
<Button :disabled="disabled" @click="removeLegacy('flushMillis')">
移除历史等待时间
</Button>
</template>
</Alert>
      <Alert
        v-if="
          token &&
          config.outputFields &&
          (!Array.isArray(config.outputFields) || config.outputFields.length)
        "
        type="warning"
        message="初始化认证返回认证变量，不生成数据输出字段，请明确清除旧声明。"
        >
<template #action>
<Button :disabled="disabled" @click="removeLegacy('outputFields')">
清除历史输出字段
</Button>
</template>
</Alert>
      <p v-if="config.classPath !== undefined" class="qi-reference-notice">
        历史类路径保留回显；可用类库由管理员授权的执行资源提供。
      </p>
      <label class="qi-reference-form-row">代码文本模式<TextArea
          :value="config.code"
          :disabled="disabled"
          :rows="16"
          spellcheck="false"
          aria-label="原生脚本代码"
          @update:value="(value) => set('code', value)"
      /></label>
    </details>
  </div>
</template>
