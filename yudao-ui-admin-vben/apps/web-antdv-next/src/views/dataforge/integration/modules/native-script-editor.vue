<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 仅编辑父设计器的节点草稿，由父级保存。 */
import { computed, defineAsyncComponent, ref, watch } from 'vue';

import {
  Alert,
  AutoComplete,
  Button,
  Input,
  Select,
} from 'antdv-next';

import { validateNativeScriptDraft } from './native-script-schema';

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
const fields = computed<Record<string, any>[]>(() =>
  Array.isArray(props.config.outputFields) ? props.config.outputFields : [],
);
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
  errors.value = validateNativeScriptDraft(props.config, props);
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
          {{ realtime ? '认证结果写入此变量，用于已连接实时输入源的启动认证。' : '认证脚本返回的字符串写入此变量，供接口输入组件引用。' }}
        </p>
      </div>
    </div>
    <slot v-if="token" name="description"></slot>
    <div><Button type="primary" :disabled="disabled" @click="emit('requestCheck')">编译</Button></div>
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
    <p class="qi-reference-notice">请使用设计器中的“检查”，结合当前执行资源及上下游验证脚本。</p>

    <p v-if="token" class="qi-reference-notice">
      {{ realtime ? '作业启动时初始化认证，可连接表输入、Kafka输入或TDengine输入；认证节点不接收数据上游。' : '认证脚本返回字符串，并通过认证变量连接接口输入；当前仅支持无数据上游的初始化认证。' }}
    </p>
    <template v-else>
      <div class="qi-reference-section-header qi-reference-table-heading">
        <h3>输出字段</h3>
        <Button
          class="qi-reference-button"
          :disabled="disabled"
          @click="addField"
          >
新增输出字段
</Button>
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
  </div>
</template>
