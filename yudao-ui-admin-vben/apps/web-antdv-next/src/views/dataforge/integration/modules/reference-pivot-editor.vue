<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 仅编辑设计器的隔离草稿，历史mode不自动交换。 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, ref } from 'vue';

import {
  Alert,
  AutoComplete,
  Button,
  Checkbox,
  Input,
  Select,
  Tooltip,
} from 'antdv-next';

import NativeStreamingWindowEditor from './native-streaming-window-editor.vue';
import { validateStreamingWindow } from './native-streaming-window-schema';
import ReferenceFieldMergeDialog from './reference-field-merge-dialog.vue';
import ReferenceIcon from './reference-icon.vue';
import { referenceFieldTypes } from './reference-node-schema';

import './reference-pivot-editor.css';

const props = defineProps<{
  componentCode?: string;
  config: Record<string, any>;
  disabled?: boolean;
  inputFields?: DataforgeEtlApi.FieldMeta[];
  mode?: string;
}>();
const v2 = computed(() => props.config.rowColumnVersion === 2);
const unpivot = computed(() => (props.config.mode ?? 'COL2ROW') === 'COL2ROW');
const sourceFields = computed<string[]>(() =>
  Array.isArray(props.config.unpivotFields) ? props.config.unpivotFields : [],
);
const groups = computed<string[]>(() =>
  Array.isArray(props.config.groupFields) ? props.config.groupFields : [],
);
const mappings = computed<Record<string, any>[]>(() =>
  Array.isArray(props.config.mappings) ? props.config.mappings : [],
);
const fields = computed(() =>
  (props.inputFields ?? [])
    .filter((field) => field.name)
    .map((field) => ({ value: field.name!, label: field.name! })),
);
const expectedMode = computed(() =>
  props.componentCode === 'row-to-column' ? 'COL2ROW' : 'ROW2COL',
);
const mergeOpen = ref(false);
function set(key: string, value: unknown) {
  if (!props.disabled) props.config[key] = value;
}
function setSource(index: number, value: unknown) {
  const next = [...sourceFields.value];
  next[index] = String(value ?? '');
  set('unpivotFields', next);
}
function setGroup(index: number, value: unknown) {
  const next = [...groups.value];
  next[index] = String(value ?? '');
  set('groupFields', next);
}
function setMapping(index: number, key: string, value: unknown) {
  if (!props.disabled && mappings.value[index])
    mappings.value[index][key] = value;
}
function merge(mode: 'addAll' | 'addNewOnly' | 'clearAndAddAll') {
  const names = fields.value
    .map((field) => field.value)
    .filter((name) => name !== props.config.keyField);
  set(
    'groupFields',
    mode === 'addNewOnly' ? [...new Set([...groups.value, ...names])] : names,
  );
}
const unpivotMappings = computed<Record<string, any>[]>(() =>
  Array.isArray(props.config.unpivotMappings)
    ? props.config.unpivotMappings
    : [],
);
const pivotMappings = computed<Record<string, any>[]>(() =>
  Array.isArray(props.config.pivotMappings) ? props.config.pivotMappings : [],
);
const dataFields = computed(() =>
  fields.value.filter(
    (field) =>
      field.value !== props.config.keyField &&
      !groups.value.includes(field.value),
  ),
);
const aggregations = [
  { label: 'Sum', value: 'Sum' },
  { label: 'Average', value: 'Average' },
  { label: 'Minimum', value: 'Minimum' },
  { label: 'Maximum', value: 'Maximum' },
];
function setV2Mapping(index: number, key: string, value: unknown) {
  if (props.disabled) return;
  const rows = unpivot.value ? unpivotMappings.value : pivotMappings.value;
  if (rows[index]) rows[index][key] = value;
}
function addV2Mapping() {
  if (props.disabled) return;
  const key = unpivot.value ? 'unpivotMappings' : 'pivotMappings';
  const rows = unpivot.value ? unpivotMappings.value : pivotMappings.value;
  set(key, [
    ...rows,
    unpivot.value
      ? { sourceField: '', keyValue: '', targetField: '' }
      : {
          targetField: '',
          sourceField: '',
          keyValue: '',
          type: 'STRING',
          format: '',
          length: '',
          precision: '',
          nullIf: '',
          aggregation: '',
        },
  ]);
}
function removeV2Mapping(index: number) {
  const key = unpivot.value ? 'unpivotMappings' : 'pivotMappings';
  const rows = unpivot.value ? unpivotMappings.value : pivotMappings.value;
  set(
    key,
    rows.filter((_, position) => position !== index),
  );
}
function upgrade() {
  if (props.disabled) return;
  if (unpivot.value)
    set(
      'unpivotMappings',
      sourceFields.value.map((sourceField) => ({
        sourceField,
        keyValue: sourceField,
        targetField: props.config.valueField || 'value',
      })),
    );
  else
    set(
      'pivotMappings',
      mappings.value.map((mapping) => ({
        targetField: mapping.fieldName,
        sourceField: props.config.valueField || '',
        keyValue: mapping.keyValue,
        aggregation: '',
      })),
    );
  set('rowColumnVersion', 2);
}
const errors = ref<string[]>([]);
function validate() {
  if (!v2.value) {
    errors.value =
      props.mode === 'REALTIME' && !unpivot.value
        ? validateStreamingWindow(props.config.streamingWindow)
        : [];
    return errors.value.length === 0;
  }
  const issues: string[] = [];
  if (!String(props.config.keyField ?? '').trim()) issues.push('请填写Key字段');
  const rows = unpivot.value ? unpivotMappings.value : pivotMappings.value;
  if (rows.length === 0) issues.push('请至少添加一个字段');
  if (
    rows.some(
      (row) =>
        !row.sourceField?.trim() ||
        !row.targetField?.trim() ||
        !row.keyValue?.trim(),
    )
  )
    issues.push('请填写字段名、关键字与输出字段');
  if (
    unpivot.value &&
    new Set(rows.map((row) => row.sourceField)).size !== rows.length
  )
    issues.push('来源字段不能重复');
  if (!unpivot.value) {
    if (
      groups.value.length === 0 ||
      groups.value.some((field) => !field.trim()) ||
      new Set(groups.value).size !== groups.value.length
    )
      issues.push('请添加不重复的分组字段');
    if (
      groups.value.includes(props.config.keyField) ||
      rows.some(
        (row) =>
          groups.value.includes(row.sourceField) ||
          row.sourceField === props.config.keyField,
      )
    )
      issues.push('Key、分组字段与数据字段不能重复');
    if (
      new Set(
        rows.map((row) => JSON.stringify([row.targetField, row.keyValue])),
      ).size !== rows.length
    )
      issues.push('目标字段与关键字组合不能重复');
  }
  if (props.mode === 'REALTIME' && !unpivot.value)
    issues.push(...validateStreamingWindow(props.config.streamingWindow));
  errors.value = issues;
  return issues.length === 0;
}
defineExpose({ validate });
</script>
<template>
  <div class="qi-pivot-reference" :data-unpivot="unpivot">
    <Alert v-if="errors.length" type="error" :message="errors.join('；')" />
    <p
      v-if="config.mode && config.mode !== expectedMode"
      class="qi-reference-notice"
    >
      此历史节点按已保存模式{{ config.mode }}执行：{{
        unpivot ? '宽表转长表' : '长表转宽表'
      }}。
    </p>
    <div class="qi-reference-form-grid qi-pivot-key-grid">
      <div class="qi-reference-form-row">
        <label class="qi-pivot-key-label"><span class="qi-pivot-required" aria-hidden="true">*</span>key字段<Tooltip
            :title="
              unpivot
                ? '新增一列，用来存放每行拆分出的字段名（例如：name、age）'
                : '选择原表中用于匹配关键字的列。'
            "
            ><button
              type="button"
              class="qi-pivot-info"
              aria-label="key字段说明"
            >
              <ReferenceIcon name="info" /></button></Tooltip></label>
        <div :class="{ 'qi-pivot-source-select': !unpivot }">
          <AutoComplete
            :value="config.keyField"
            :disabled="disabled"
            :options="unpivot ? [] : fields"
            aria-required="true"
            aria-label="key字段"
            :placeholder="
              unpivot ? '未设置时，key 的值与字段名相同' : '请输入key字段'
            "
            @update:value="(value) => set('keyField', value)"
          />
        </div>
      </div>
    </div>
    <template v-if="v2">
      <template v-if="!unpivot">
        <div class="qi-reference-section-header qi-reference-table-heading">
          <h3>分组字段</h3>
          <div class="qi-reference-file-actions">
            <Button
              class="qi-reference-button"
              :disabled="disabled"
              @click="set('groupFields', [...groups, ''])"
              >
<ReferenceIcon name="plus" />新增
</Button><Button
              class="qi-reference-merge-button"
              :disabled="disabled || !fields.length"
              @click="mergeOpen = true"
              >
<ReferenceIcon name="plus" />获取字段
</Button>
          </div>
        </div>
        <div class="qi-pivot-group-scroll">
          <table class="qi-reference-fields-table qi-pivot-group-table">
            <colgroup>
              <col class="qi-pivot-index-column" />
              <col />
              <col class="qi-pivot-action-column" />
            </colgroup>
            <thead>
              <tr>
                <th>序号</th>
                <th>
                  <span class="qi-pivot-column-title">分组字段<Tooltip
                      title="相同分组字段值的数据合并为一行；分组字段不能与 key 字段或数据字段重复。"
                      ><button
                        type="button"
                        class="qi-pivot-info"
                        aria-label="分组字段说明"
                      >
                        <ReferenceIcon name="info" /></button></Tooltip></span>
                </th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(field, index) in groups" :key="index">
                <td>{{ index + 1 }}</td>
                <td>
                  <div class="qi-pivot-source-select">
                    <AutoComplete
                      :value="field"
                      :options="
                        fields.filter(
                          (option) => option.value !== config.keyField,
                        )
                      "
                      :disabled="disabled"
                      placeholder="请选择分组字段"
                      @update:value="(value) => setGroup(index, value)"
                    />
                  </div>
                </td>
                <td>
                  <Button
                    type="link"
                    danger
                    :disabled="disabled"
                    @click="
                      set(
                        'groupFields',
                        groups.filter((_, position) => position !== index),
                      )
                    "
                    >
<ReferenceIcon name="delete" />删除
</Button>
                </td>
              </tr>
              <tr v-if="!groups.length">
                <td
                  colspan="3"
                  class="qi-reference-empty qi-pivot-empty qi-pivot-group-empty"
                >
                  <img
                    src="/assets/qdata-integration/node-empty-reference.png"
                    alt=""
                    aria-hidden="true"
                    draggable="false"
                  />
                  <p>暂无记录</p>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
      <div class="qi-reference-section-header qi-reference-table-heading">
        <h3>{{ unpivot ? '字段' : '新增字段' }}</h3>
        <Button
          class="qi-reference-button"
          :disabled="disabled"
          @click="addV2Mapping"
          >
<ReferenceIcon name="plus" />新增
</Button>
      </div>
      <div class="qi-reference-fields-scroll qi-pivot-fields-scroll">
        <table
          class="qi-reference-fields-table"
          :class="{
            'qi-pivot-mapping-table': !unpivot,
            'qi-unpivot-mapping-table': unpivot,
          }"
          :aria-label="unpivot ? '行转列字段配置' : '列转行新增字段'"
        >
          <colgroup v-if="unpivot">
            <col class="qi-pivot-index-column" />
            <col />
            <col />
            <col />
            <col class="qi-pivot-action-column" />
          </colgroup>
          <colgroup v-else>
            <col class="qi-pivot-index-column" />
            <col
              v-for="(width, index) in [
                150, 150, 130, 130, 150, 150, 150, 130, 150,
              ]"
              :key="index"
              :style="{ width: `${width}px` }"
            />
            <col class="qi-pivot-action-column" />
          </colgroup>
          <thead>
            <tr>
              <th>序号</th>
              <template v-if="unpivot">
<th>
                  <span class="qi-pivot-column-title">字段名<Tooltip
                      title="选择需要拆分为多行的来源字段；每条映射按顺序生成一行。"
                      ><button
                        type="button"
                        class="qi-pivot-info"
                        aria-label="字段名说明"
                      >
                        <ReferenceIcon name="info" /></button></Tooltip></span>
                </th>
                <th>
                  <span class="qi-pivot-column-title">Key<Tooltip
                      title="拆分后写入 key 字段的值，请为每个来源字段填写对应的 Key。"
                      ><button
                        type="button"
                        class="qi-pivot-info"
                        aria-label="Key说明"
                      >
                        <ReferenceIcon name="info" /></button></Tooltip></span>
                </th>
                <th>
                  <span class="qi-pivot-column-title">输出字段<Tooltip
                      title="接收来源字段值的输出列；写入同一输出列的来源字段类型须一致。"
                      ><button
                        type="button"
                        class="qi-pivot-info"
                        aria-label="输出字段说明"
                      >
                        <ReferenceIcon name="info" /></button></Tooltip></span>
</th>
</template><template v-else>
<th>
                  <span class="qi-pivot-column-title">新增字段<Tooltip
                      title="转换后生成的目标列名称；每个关键字对应的值写入指定目标列。"
                      ><button
                        type="button"
                        class="qi-pivot-info"
                        aria-label="新增字段说明"
                      >
                        <ReferenceIcon name="info" /></button></Tooltip></span>
                </th>
                <th>
                  <span class="qi-pivot-column-title">数据字段<Tooltip
                      title="指定原表中的列，用于填充【目标字段】中的值（需结合关键字字段来确保数据正确映射）"
                      ><button
                        type="button"
                        class="qi-pivot-info"
                        aria-label="数据字段说明"
                      >
                        <ReferenceIcon name="info" /></button></Tooltip></span>
                </th>
                <th>
                  <span class="qi-pivot-column-title">关键字<Tooltip
                      title="可填写【key字段】列中的全部数据。结合【key字段】+分组+【目标字段】，锁定具体值并写入目标字段。"
                      ><button
                        type="button"
                        class="qi-pivot-info"
                        aria-label="关键字说明"
                      >
                        <ReferenceIcon name="info" /></button></Tooltip></span>
                </th>
                <th>字段类型</th>
                <th>格式</th>
                <th>字段长度</th>
                <th>字段精度</th>
                <th>null If</th>
                <th>聚合方式</th>
</template>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(mapping, index) in unpivot
                ? unpivotMappings
                : pivotMappings"
              :key="index"
            >
              <td>{{ index + 1 }}</td>
              <template v-if="unpivot">
<td>
                  <div class="qi-pivot-source-select">
                    <AutoComplete
                      :value="mapping.sourceField"
                      :options="fields"
                      :disabled="disabled"
                      placeholder="字段名"
                      :aria-label="`第${index + 1}行字段名`"
                      @update:value="
                        (value) => setV2Mapping(index, 'sourceField', value)
                      "
                    />
                  </div>
                </td>
                <td>
                  <Input
                    :value="mapping.keyValue"
                    :disabled="disabled"
                    placeholder="输入Key"
                    @update:value="
                      (value) => setV2Mapping(index, 'keyValue', value)
                    "
                  />
                </td>
                <td>
                  <Input
                    :value="mapping.targetField"
                    :disabled="disabled"
                    placeholder="请输入Value"
                    @update:value="
                      (value) => setV2Mapping(index, 'targetField', value)
                    "
                  />
</td>
</template><template v-else>
<td>
                  <Input
                    :value="mapping.targetField"
                    :disabled="disabled"
                    placeholder="请输入字段"
                    @update:value="
                      (value) => setV2Mapping(index, 'targetField', value)
                    "
                  />
                </td>
                <td>
                  <div class="qi-pivot-source-select">
                    <AutoComplete
                      :value="mapping.sourceField"
                      :options="dataFields"
                      :disabled="disabled"
                      placeholder="请选择数据字段"
                      @update:value="
                        (value) => setV2Mapping(index, 'sourceField', value)
                      "
                    />
                  </div>
                </td>
                <td>
                  <Input
                    :value="mapping.keyValue"
                    :disabled="disabled"
                    placeholder="请输入关键字"
                    @update:value="
                      (value) => setV2Mapping(index, 'keyValue', value)
                    "
                  />
                </td>
                <td>
                  <Select
                    :value="mapping.type"
                    :options="referenceFieldTypes"
                    :disabled="disabled"
                    placeholder="请选择字段类型"
                    @update:value="
                      (value) => setV2Mapping(index, 'type', value)
                    "
                  />
                </td>
                <td>
                  <Input
                    :value="mapping.format"
                    :disabled="disabled"
                    placeholder="请输入格式"
                    @update:value="
                      (value) => setV2Mapping(index, 'format', value)
                    "
                  />
                </td>
                <td>
                  <Input
                    :value="mapping.length"
                    :disabled="disabled"
                    placeholder="请输入字段长度"
                    @update:value="
                      (value) => setV2Mapping(index, 'length', value)
                    "
                  />
                </td>
                <td>
                  <Input
                    :value="mapping.precision"
                    :disabled="disabled"
                    placeholder="请输入字段精度"
                    @update:value="
                      (value) => setV2Mapping(index, 'precision', value)
                    "
                  />
                </td>
                <td>
                  <Input
                    :value="mapping.nullIf"
                    :disabled="disabled"
                    placeholder="请输入nullIf"
                    @update:value="
                      (value) => setV2Mapping(index, 'nullIf', value)
                    "
                  />
                </td>
                <td>
                  <Select
                    :value="mapping.aggregation || undefined"
                    :options="aggregations"
                    :disabled="disabled"
                    allow-clear
                    placeholder="请选择聚合方式"
                    @update:value="
                      (value) => setV2Mapping(index, 'aggregation', value ?? '')
                    "
                  />
</td>
</template>
              <td>
                <Button
                  type="link"
                  danger
                  :disabled="disabled"
                  @click="removeV2Mapping(index)"
                  >
<ReferenceIcon name="delete" />删除
</Button>
              </td>
            </tr>
            <tr v-if="!(unpivot ? unpivotMappings : pivotMappings).length">
              <td
                :colspan="unpivot ? 5 : 11"
                class="qi-reference-empty qi-pivot-empty"
              >
                <template v-if="unpivot">
<img
                    src="/assets/qdata-integration/node-empty-reference.png"
                    alt=""
                    aria-hidden="true"
                    draggable="false"
                  />
                  <p>暂无记录</p>
</template><template v-else>
<img
                    src="/assets/qdata-integration/node-empty-reference.png"
                    alt=""
                    aria-hidden="true"
                    draggable="false"
                  />
                  <p>暂无记录</p>
</template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <details class="qi-transform-advanced">
        <summary>执行契约</summary>
        <p v-if="!unpivot" class="qi-reference-notice">
          未选择聚合时按下方确定全序保留最后匹配值。Sum/Average要求数值目标类型；格式用于日期时间解析；null
          If在类型转换前将相同文本值设为空。
        </p>
        <Checkbox
          v-if="unpivot"
          :checked="config.skipNull === true"
          :disabled="disabled"
          @update:checked="(value) => set('skipNull', value)"
          >
跳过空值
</Checkbox>
      </details>
    </template>
    <template v-else>
      <template v-if="unpivot">
        <div class="qi-reference-section-header qi-reference-table-heading">
          <h3>字段</h3>
          <Button
            class="qi-reference-button"
            :disabled="disabled"
            @click="set('unpivotFields', [...sourceFields, ''])"
            >
<ReferenceIcon name="plus" />新增
</Button>
        </div>
        <table class="qi-reference-fields-table">
          <thead>
            <tr>
              <th>序号</th>
              <th>字段名</th>
              <th>Key</th>
              <th>输出字段</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(field, index) in sourceFields" :key="index">
              <td>{{ index + 1 }}</td>
              <td>
                <AutoComplete
                  :value="field"
                  :options="fields"
                  :disabled="disabled"
                  @update:value="(value) => setSource(index, value)"
                />
              </td>
              <td>{{ field || '-' }}</td>
              <td>
                <Input
                  :value="config.valueField"
                  :disabled="disabled"
                  placeholder="value"
                  @update:value="(value) => set('valueField', value)"
                />
              </td>
              <td>
                <Button
                  type="link"
                  danger
                  :disabled="disabled"
                  @click="
                    set(
                      'unpivotFields',
                      sourceFields.filter((_, position) => position !== index),
                    )
                  "
                  >
删除
</Button>
              </td>
            </tr>
            <tr v-if="!sourceFields.length">
              <td colspan="5" class="qi-reference-empty">暂无记录</td>
            </tr>
          </tbody>
        </table>
        <p class="qi-reference-notice">
          所选列展开为多行，Key为来源列名，值写入同一个输出字段；来源字段须为相同类型。
        </p>
        <details class="qi-transform-advanced">
          <summary>转换兼容配置</summary>
          <Checkbox
            :checked="config.skipNull === true"
            :disabled="disabled"
            @update:checked="(value) => set('skipNull', value)"
            >
跳过空值
</Checkbox>
          <div class="qi-reference-form-row">
            <label>值字段名</label><Input
              :value="config.valueField"
              :disabled="disabled"
              @update:value="(value) => set('valueField', value)"
            />
          </div>
        </details>
      </template>
      <template v-else>
        <div class="qi-reference-section-header qi-reference-table-heading">
          <h3>分组字段</h3>
          <div class="qi-reference-file-actions">
            <Button
              class="qi-reference-button"
              :disabled="disabled"
              @click="set('groupFields', [...groups, ''])"
              >
<ReferenceIcon name="plus" />新增
</Button><Button
              class="qi-reference-merge-button"
              :disabled="disabled || !fields.length"
              @click="mergeOpen = true"
              >
<ReferenceIcon name="plus" />获取字段
</Button>
          </div>
        </div>
        <div class="qi-pivot-group-scroll">
          <table class="qi-reference-fields-table qi-pivot-group-table">
            <colgroup>
              <col class="qi-pivot-index-column" />
              <col />
              <col class="qi-pivot-action-column" />
            </colgroup>
            <thead>
              <tr>
                <th>序号</th>
                <th>
                  <span class="qi-pivot-column-title">分组字段<Tooltip
                      title="相同分组字段值的数据合并为一行；分组字段不能与 key 字段或数据字段重复。"
                      ><button
                        type="button"
                        class="qi-pivot-info"
                        aria-label="分组字段说明"
                      >
                        <ReferenceIcon name="info" /></button></Tooltip></span>
                </th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(field, index) in groups" :key="index">
                <td>{{ index + 1 }}</td>
                <td>
                  <div class="qi-pivot-source-select">
                    <AutoComplete
                      :value="field"
                      :options="fields"
                      :disabled="disabled"
                      placeholder="请选择分组字段"
                      @update:value="(value) => setGroup(index, value)"
                    />
                  </div>
                </td>
                <td>
                  <Button
                    type="link"
                    danger
                    :disabled="disabled"
                    @click="
                      set(
                        'groupFields',
                        groups.filter((_, position) => position !== index),
                      )
                    "
                    >
<ReferenceIcon name="delete" />删除
</Button>
                </td>
              </tr>
              <tr v-if="!groups.length">
                <td
                  colspan="3"
                  class="qi-reference-empty qi-pivot-empty qi-pivot-group-empty"
                >
                  <img
                    src="/assets/qdata-integration/node-empty-reference.png"
                    alt=""
                    aria-hidden="true"
                    draggable="false"
                  />
                  <p>暂无记录</p>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="qi-reference-section-header qi-reference-table-heading">
          <h3>新增字段</h3>
          <Button
            class="qi-reference-button"
            :disabled="disabled"
            @click="
              set('mappings', [...mappings, { keyValue: '', fieldName: '' }])
            "
            >
<ReferenceIcon name="plus" />新增
</Button>
        </div>
        <table class="qi-reference-fields-table">
          <thead>
            <tr>
              <th>序号</th>
              <th>目标字段名称</th>
              <th>数据字段</th>
              <th>Key</th>
              <th>聚合方式</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(mapping, index) in mappings" :key="index">
              <td>{{ index + 1 }}</td>
              <td>
                <Input
                  :value="mapping.fieldName"
                  :disabled="disabled"
                  @update:value="
                    (value) => setMapping(index, 'fieldName', value)
                  "
                />
              </td>
              <td>
                <AutoComplete
                  :value="config.valueField"
                  :options="fields"
                  :disabled="disabled"
                  @update:value="(value) => set('valueField', value)"
                />
              </td>
              <td>
                <Input
                  :value="mapping.keyValue"
                  :disabled="disabled"
                  @update:value="
                    (value) => setMapping(index, 'keyValue', value)
                  "
                />
              </td>
              <td>按确定顺序保留末值</td>
              <td>
                <Button
                  type="link"
                  danger
                  :disabled="disabled"
                  @click="
                    set(
                      'mappings',
                      mappings.filter((_, position) => position !== index),
                    )
                  "
                  >
删除
</Button>
              </td>
            </tr>
            <tr v-if="!mappings.length">
              <td colspan="6" class="qi-reference-empty">暂无记录</td>
            </tr>
          </tbody>
        </table>
        <p class="qi-reference-notice">
          每个Key生成一个目标字段，读取相同数据字段；同组同Key按确定顺序保留末值。
        </p>
      </template>
      <details class="qi-transform-advanced">
        <summary>完整行列配置</summary>
        <p class="qi-reference-notice">
          此节点保留原有协议。可将当前来源和顺序配置显式转换为支持独立Key、输出列及聚合的版本。
        </p>
        <Button :disabled="disabled" @click="upgrade">使用完整行列配置</Button>
      </details>
    </template>
    <details class="qi-transform-advanced">
      <summary>历史执行模式</summary>
      <div class="qi-reference-form-row">
        <label>转换模式</label><Select
          :value="config.mode ?? 'COL2ROW'"
          :disabled="disabled"
          :options="[
            { label: '宽表转长表（COL2ROW）', value: 'COL2ROW' },
            { label: '长表转宽表（ROW2COL）', value: 'ROW2COL' },
          ]"
          @update:value="(value) => set('mode', value)"
        />
      </div>
    </details>
    <NativeStreamingWindowEditor
      v-if="mode === 'REALTIME' && !unpivot"
      :config="config"
      :disabled="disabled"
    />
    <ReferenceFieldMergeDialog
      class="qi-pivot-merge-dialog"
      v-model:open="mergeOpen"
      :existing-count="groups.length"
      :incoming-count="fields.length"
      :disabled="disabled"
      @resolve="merge"
    />
  </div>
</template>
