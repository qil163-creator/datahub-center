<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 仅编辑设计器隔离草稿，保留既有节点执行协议。 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, ref, watch } from 'vue';

import { Alert, AutoComplete, Button, Input, InputNumber, Select } from 'antdv-next';

import ReferenceCleanEditor from './reference-clean-editor.vue';
import ReferenceIcon from './reference-icon.vue';
import { referenceFieldTypes } from './reference-node-schema';
import ReferencePivotEditor from './reference-pivot-editor.vue';
import ReferenceSubflowEditor from './reference-subflow-editor.vue';

import './reference-node-editor.css';
import './reference-transform-layout.css';
import './condition-flow-reference.css';

const props = defineProps<{
  componentCode?: string;
  config: Record<string, any>;
  disabled?: boolean;
  downstreamNodes?: { targetId: string; targetName: string }[];
  engine?: string;
  inputFields?: (DataforgeEtlApi.FieldMeta & { comment?: string; isPrimaryKey?: boolean })[];
  mode?: string;
  toolCode: string;
  upstreamNodes?: { fields?: DataforgeEtlApi.FieldMeta[]; name: string; nodeNo: string; }[];
}>();
const specialEditor=ref<{openRulePicker?: () => void; validate?: () => boolean}>();
const step = ref(0);
const errors = ref<string[]>([]);
const steps = computed(() => props.toolCode === 'multi-join'
  ? ['基本信息', 'A组件字段配置', 'B组件字段配置']
  : ['基本信息', '分发条件配置', 'True组件字段配置', 'False组件字段配置']);
const fields = computed(() => (props.inputFields ?? []).filter((field) => field.name).map((field) => ({ label: field.name!, value: field.name! })));
const targets = computed(() => (props.downstreamNodes ?? []).map((node) => ({ label: node.targetName, value: node.targetId })));
const joinRows = computed<Record<string, any>[]>(() => Array.isArray(props.config.joinConditions) ? props.config.joinConditions : []);
const branches = computed<Record<string, any>[]>(() => Array.isArray(props.config.branches) ? props.config.branches : []);
const trueBranch = computed(() => branches.value.find((branch) => branch.type !== 'ELSE'));
const falseBranch = computed(() => branches.value.find((branch) => branch.type === 'ELSE'));
const activeBranch = computed(() => step.value === 3 ? falseBranch.value : trueBranch.value);
const conditions = computed<Record<string, any>[]>(() => {
  const branch = trueBranch.value;
  if (!branch) return [];
  if (Array.isArray(branch.conditions)) return branch.conditions;
  return branch.fieldName ? [{ fieldName: branch.fieldName, operator: branch.operator, value: branch.value }] : [];
});
const operators = [ ['EQ', '等于'], ['NE', '不等于'], ['GT', '大于'], ['GTE', '大于等于'], ['LT', '小于'], ['LTE', '小于等于'], ['CONTAINS', '包含'], ['NOT_CONTAINS', '不包含'], ['IS_NULL', '为空'], ['NOT_NULL', '不为空'] ].map(([value,label]) => ({value,label}));
const joinOperators = operators.slice(0,6);
// 参考界面的判断条件只提供这六项；历史配置中的其它运算符保留显示但不可再选。
const conditionOperatorValues = ['EQ','NE','IS_NULL','NOT_NULL','CONTAINS','NOT_CONTAINS'];
function conditionOperatorOptions(current: unknown) {
  const base = conditionOperatorValues.map((value) => operators.find((item) => item.value === value)!);
  const legacy = operators.find((item) => item.value === current && !conditionOperatorValues.includes(String(item.value)));
  return legacy ? [...base, { label: `${legacy.label}（历史配置）`, value: legacy.value, disabled: true }] : base;
}
const valuelessOperators = ['IS_NULL','NOT_NULL'];
const conditionFieldOptions = computed(() => (props.inputFields ?? []).filter((field) => field.name).map((field) => ({ value: field.name!, label: field.comment ? `${field.comment} (${field.name})` : field.name! })));
function hasValue(condition: Record<string, any>) { return condition.value !== undefined && condition.value !== null && String(condition.value) !== ''; }
const valueEditing = ref(-1);
const valueDraft = ref('');
function startValue(index: number) {
  if (props.disabled) return;
  valueDraft.value = String(conditions.value[index]?.value ?? '');
  valueEditing.value = index;
}
function commitValue(index: number) {
  if (valueEditing.value !== index) return;
  valueEditing.value = -1;
  setCondition(index, 'value', valueDraft.value.trim());
}
// 字段长度/小数精度排序只改变展示顺序，行操作仍按真实下标写回配置。
type MetaKey = 'length' | 'scale';
const metaSort = ref<{ direction: -1 | 1; key: MetaKey }>();
function toggleSort(key: MetaKey) {
  if (metaSort.value?.key !== key) metaSort.value = { key, direction: 1 };
  else metaSort.value = metaSort.value.direction === 1 ? { key, direction: -1 } : undefined;
}
function sortState(key: MetaKey) { return metaSort.value?.key !== key ? 'none' : (metaSort.value.direction === 1 ? 'ascending' : 'descending'); }
function sortDirection(key: MetaKey) { return metaSort.value?.key === key ? metaSort.value.direction : 0; }
function metaNumber(value: unknown) { return value === undefined || value === null || !/^\d+$/u.test(String(value)) ? undefined : Number(value); }
function sortByMeta<T>(entries: T[], meta: (entry: T) => Record<string, any> | undefined) {
  const sort = metaSort.value;
  if (!sort) return entries;
  return entries.toSorted((left, right) => {
    const a = metaNumber(meta(left)?.[sort.key]); const b = metaNumber(meta(right)?.[sort.key]);
    if (a === undefined) return b === undefined ? 0 : 1;
    if (b === undefined) return -1;
    return (a - b) * sort.direction;
  });
}
const inputEntries = computed(() => sortByMeta((props.inputFields ?? []).map((field, index) => ({ field, index })), (entry) => entry.field));
const branchNames = computed<string[]>(() => Array.isArray(activeBranch.value?.fields) ? activeBranch.value!.fields : fields.value.map((field) => field.value));
const branchEntries = computed(() => sortByMeta(branchNames.value.map((name, index) => ({ name, index, meta: props.inputFields?.find((field) => field.name === name) })), (entry) => entry.meta));
const canAddBranchField = computed(() => fields.value.some((field) => !branchNames.value.includes(field.value)));
function branchFieldOptions(name: string) { return fields.value.map((field) => ({ ...field, disabled: field.value !== name && branchNames.value.includes(field.value) })); }
function writeBranchFields(next: string[]) { if (props.disabled) return; const branch = ensureBranch(step.value === 3); if (branch) branch.fields = next; }
function setBranchField(index: number, name: unknown) { const next = [...branchNames.value]; next[index] = String(name ?? ''); writeBranchFields(next); }
function removeBranchField(index: number) { writeBranchFields(branchNames.value.filter((_, position) => position !== index)); }
function addBranchField() { const next = fields.value.find((field) => !branchNames.value.includes(field.value)); if (next) writeBranchFields([...branchNames.value, next.value]); }
const projectionKey = computed(() => step.value === 1 ? 'leftFields' : 'rightFields');
const leftNode = computed(() => props.upstreamNodes?.find((node) => node.nodeNo===props.config.leftNodeNo) ?? props.upstreamNodes?.[0]);
const rightNode = computed(() => props.upstreamNodes?.find((node) => node.nodeNo===props.config.rightNodeNo) ?? props.upstreamNodes?.[1]);
const upstreamOptions = computed(() => (props.upstreamNodes ?? []).map((node) => ({value:node.nodeNo,label:node.name})));
const projectionInputs = computed(() => (step.value === 1 ? leftNode.value : rightNode.value)?.fields ?? props.inputFields ?? []);
function chooseJoinSource(index: number,value: unknown) {
  if (props.disabled || !value) return;
  const currentLeft=leftNode.value?.nodeNo; const currentRight=rightNode.value?.nodeNo;
  const left=index===0 ? String(value) : currentLeft;
  const right=index===1 ? String(value) : currentRight;
  if (left && right && left!==right) { props.config.leftNodeNo=left; props.config.rightNodeNo=right; }
}
const projectionRows = computed<Record<string, any>[]>(() => Array.isArray(props.config[projectionKey.value])
  ? props.config[projectionKey.value]
  : projectionInputs.value.filter((field) => field.name).map((field) => ({ name: field.name, alias: field.name, type: field.dataType, length: field.length, precision: field.scale })));
const projectionOptions = computed(() => projectionInputs.value.filter((field) => field.name).map((field) => ({ value: field.name!, label: field.name! })));
function set(key: string, value: unknown) { if (!props.disabled) props.config[key] = value; }
function update(row: Record<string, any>, key: string, value: unknown) { if (!props.disabled) row[key] = value; }
function ensureBranch(otherwise = false) {
  const existing = otherwise ? falseBranch.value : trueBranch.value;
  if (existing) return existing;
  if (props.disabled) return;
  const branch: Record<string,any> = { id: otherwise ? 'false' : 'true', name: otherwise ? 'False' : 'True', type: otherwise ? 'ELSE' : 'IF', targetNodeNo: '' };
  props.config.branches = [...branches.value, branch];
  return branch;
}
function setTarget(otherwise: boolean, value: unknown) { const branch=ensureBranch(otherwise); if (branch && !props.disabled) branch.targetNodeNo=value; }
function setCondition(index: number, key: string, value: unknown) {
  if (props.disabled) return;
  const branch=ensureBranch(); if (!branch) return;
  const rows=conditions.value.map((row) => ({...row}));
  if (rows[index]) {
    rows[index][key]=value;
    if (key==='operator' && valuelessOperators.includes(String(value))) rows[index].value='';
  }
  branch.conditions=rows;
}
function addCondition() { if (props.disabled) return; const branch=ensureBranch(); if (branch) branch.conditions=[...conditions.value.map((row) => ({...row})), {fieldName:'',operator:'',value:''}]; }
function removeCondition(index: number) { if (!props.disabled && trueBranch.value) trueBranch.value.conditions=conditions.value.filter((_,position) => position!==index).map((row) => ({...row})); }
function setRelation(value: string) { const branch=ensureBranch(); if (branch && !props.disabled) branch.conditionRelation=value; }
function addJoin() { if (!props.disabled) props.config.joinConditions=[...joinRows.value,{leftField:'',operator:'EQ',rightField:''}]; }
function removeJoin(index: number) { if (!props.disabled) props.config.joinConditions=joinRows.value.filter((_,position) => position!==index); }
function setProjection(index: number, key: string, value: unknown) {
  if (props.disabled) return;
  const rows=projectionRows.value.map((row) => ({...row}));
  if (rows[index]) rows[index][key]=value;
  props.config[projectionKey.value]=rows;
}
function removeProjection(index: number) { if (!props.disabled) props.config[projectionKey.value]=projectionRows.value.filter((_,position) => position!==index).map((row) => ({...row})); }
function addProjection() { if (!props.disabled) props.config[projectionKey.value]=[...projectionRows.value.map((row) => ({...row})), {name:'',alias:''}]; }
function moveStep(value: number) { step.value=Math.max(0,Math.min(steps.value.length-1,value)); metaSort.value=undefined; valueEditing.value=-1; }
const stepNav = computed(() => ['multi-join','data-condition'].includes(props.toolCode) ? { last: steps.value.length-1, next: () => moveStep(step.value+1), prev: () => moveStep(step.value-1), step: step.value } : undefined);
function validate() {
  if (props.toolCode==='row-column') return specialEditor.value?.validate?.() ?? true;
  const issues: string[]=[];
  if (props.toolCode==='multi-join') {
    if (joinRows.value.length === 0 || joinRows.value.some((row) => !row.leftField?.trim() || !row.rightField?.trim())) issues.push('请填写完整连接条件');
    for (const key of ['leftFields','rightFields']) if (Array.isArray(props.config[key]) && props.config[key].some((field: Record<string,any>) => !field.name?.trim())) issues.push('请填写投影来源字段');
    if (Array.isArray(props.config.leftFields) && Array.isArray(props.config.rightFields) && props.config.leftFields.length === 0 && props.config.rightFields.length === 0) issues.push('请至少保留一个输出字段');
  } else if (props.toolCode==='data-condition') {
    const incomplete = conditions.value.some((condition) => !condition.fieldName?.trim() || !condition.operator);
    if (incomplete) issues.push('请完善分发条件的字段名称和判断条件');
    else if (conditions.value.some((condition) => !valuelessOperators.includes(condition.operator) && !hasValue(condition))) issues.push('请填写分发条件的比较值');
    for (const branch of branches.value) {
      if (!branch.targetNodeNo) issues.push('请选择分支下游组件');
      if (Array.isArray(branch.fields) && (branch.fields.length === 0 || new Set(branch.fields).size!==branch.fields.length)) issues.push('每个分支需选择不重复的输出字段');
    }
  }
  errors.value=issues;
  return issues.length === 0;
}
watch(() => [props.config,props.toolCode], () => { step.value=0; errors.value=[]; metaSort.value=undefined; valueEditing.value=-1; });
const canGetRules=computed(() => props.toolCode==='data-clean');
function openRulePicker() { specialEditor.value?.openRulePicker?.(); }
defineExpose({validate,canGetRules,openRulePicker,stepNav});
</script>

<template>
  <div class="qi-reference-editor qi-transform-reference qi-reference-flow" :data-tool-code="toolCode" :data-component-code="componentCode">
    <Alert v-if="errors.length" type="error" :message="errors.join('；')" />
    <template v-if="['multi-join','data-condition'].includes(toolCode)">
      <nav class="qi-reference-steps" aria-label="配置步骤">
        <button v-for="(label,index) in steps" :key="label" type="button" :aria-current="step === index ? 'step' : undefined" :data-state="step === index ? 'current' : (index < step ? 'done' : 'todo')" @click="moveStep(index)"><span class="qi-reference-step-number">{{ index + 1 }}</span>{{ label }}</button>
      </nav>
      <template v-if="step === 0">
        <slot name="basics"></slot>
        <template v-if="toolCode === 'multi-join'">
          <div class="qi-reference-form-row"><label>Join模式</label><Select :value="config.joinType ?? 'INNER'" :disabled="disabled" :options="[{label:'INNER JOIN',value:'INNER'},{label:'LEFT JOIN',value:'LEFT'}]" @update:value="(value) => set('joinType',value)" /></div>
          <div v-for="(label,index) in ['A组件','B组件']" :key="label" class="qi-reference-form-row"><label>{{ label }}</label><Select :value="index === 0 ? leftNode?.nodeNo : rightNode?.nodeNo" :options="upstreamOptions" :disabled="disabled || upstreamOptions.length < 2" :placeholder="`请选择${label}`" @update:value="(value) => chooseJoinSource(index,value)" /></div>
          <Button type="primary" :disabled="disabled" @click="addJoin"><ReferenceIcon name="plus" />添加条件 </Button>
          <p class="qi-reference-notice"> 配置A组件与B组件字段的关联条件。A/B按照画布上游连接顺序确定。 </p>
          <table class="qi-reference-fields-table"><thead><tr><th>A组件字段</th><th>运算符</th><th>B组件字段</th><th>操作</th></tr></thead><tbody><tr v-for="(condition,index) in joinRows" :key="index"><td><AutoComplete :value="condition.leftField" :options="leftNode?.fields?.map((field) => ({value:field.name,label:field.name})) ?? fields" :disabled="disabled" @update:value="(value) => update(condition,'leftField',value)" /></td><td><Select :value="condition.operator ?? 'EQ'" :options="joinOperators" :disabled="disabled" @update:value="(value) => update(condition,'operator',value)" /></td><td><AutoComplete :value="condition.rightField" :options="rightNode?.fields?.map((field) => ({value:field.name,label:field.name})) ?? fields" :disabled="disabled" @update:value="(value) => update(condition,'rightField',value)" /></td><td><Button type="link" danger :disabled="disabled" @click="removeJoin(index)"> 删除 </Button></td></tr><tr v-if="!joinRows.length"><td colspan="4" class="qi-reference-empty">暂无记录</td></tr></tbody></table>
        </template>
        <template v-else>
          <div class="qi-reference-form-row"><label>True组件</label><Select :value="trueBranch?.targetNodeNo" :options="targets" :disabled="disabled || !targets.length" placeholder="请选择True组件" @update:value="(value) => setTarget(false,value)" /></div>
          <div class="qi-reference-form-row"><label>False组件</label><Select :value="falseBranch?.targetNodeNo" :options="targets" :disabled="disabled || !targets.length" placeholder="请选择False组件" @update:value="(value) => setTarget(true,value)" /></div>
          <div class="qi-reference-section-header qi-reference-table-heading"><h3>输入字段</h3></div>
          <div class="qi-reference-fields-scroll qi-flow-input-scroll"><table class="qi-reference-fields-table qi-flow-field-table"><thead><tr><th>序号</th><th>英文名称</th><th>中文名称</th><th>字段类型</th><th>是否主键</th><th :aria-sort="sortState('length')"><button type="button" class="qi-flow-sort" aria-label="按字段长度排序" @click="toggleSort('length')">字段长度<span class="qi-sort-caret" :data-sort="sortDirection('length')" aria-hidden="true"></span></button></th><th :aria-sort="sortState('scale')"><button type="button" class="qi-flow-sort" aria-label="按小数精度排序" @click="toggleSort('scale')">小数精度<span class="qi-sort-caret" :data-sort="sortDirection('scale')" aria-hidden="true"></span></button></th></tr></thead><tbody><tr v-for="({ field }, position) in inputEntries" :key="field.name"><td>{{ position + 1 }}</td><td>{{ field.name }}</td><td>{{ field.comment ?? '-' }}</td><td>{{ field.dataType }}</td><td><span class="qi-flow-tag">{{ field.isPrimaryKey ? '是' : '否' }}</span></td><td>{{ field.length ?? '-' }}</td><td>{{ field.scale ?? '-' }}</td></tr><tr v-if="!inputFields?.length"><td colspan="7" class="qi-reference-empty">暂无记录</td></tr></tbody></table></div>
          <p class="qi-reference-notice"> 根据配置的条件对上游输入数据进行判断，并将数据分发至不同的下游步骤。 </p>
        </template>
      </template>
      <template v-else-if="toolCode === 'multi-join'">
        <div class="qi-reference-section-header qi-reference-table-heading"><h3>{{ step === 1 ? 'A' : 'B' }}组件字段配置</h3><Button :disabled="disabled" class="qi-reference-button" @click="addProjection"><ReferenceIcon name="plus" />新增 </Button></div>
        <div class="qi-reference-fields-scroll"><table class="qi-reference-fields-table"><thead><tr><th>序号</th><th>字段名称</th><th>字段别名</th><th>字段类型</th><th>字段长度</th><th>小数精度</th><th>操作</th></tr></thead><tbody><tr v-for="(field,index) in projectionRows" :key="index"><td>{{ index + 1 }}</td><td><AutoComplete :value="field.name" :options="projectionOptions" :disabled="disabled" @update:value="(value) => setProjection(index,'name',value)" /></td><td><Input :value="field.alias" :disabled="disabled" @update:value="(value) => setProjection(index,'alias',value)" /></td><td><Select :value="field.type" :options="referenceFieldTypes" allow-clear :disabled="disabled" @update:value="(value) => setProjection(index,'type',value)" /></td><td><Input :value="field.length" :disabled="disabled" @update:value="(value) => setProjection(index,'length',value)" /></td><td><InputNumber :value="field.precision" :min="0" :precision="0" :disabled="disabled" @update:value="(value) => setProjection(index,'precision',value)" /></td><td><Button type="link" danger :disabled="disabled" @click="removeProjection(index)"> 删除 </Button></td></tr><tr v-if="!projectionRows.length"><td colspan="7" class="qi-reference-empty">暂无记录</td></tr></tbody></table></div>
      </template>
      <template v-else-if="step === 1">
        <div class="qi-flow-condition-note" role="note"><p>条件说明</p><p>根据输入字段配置判断条件，系统会逐条判断上游数据。</p><p>满足条件的数据进入 True 组件，不满足条件的数据进入 False 组件。</p></div>
        <div class="qi-flow-relation"><span>条件关系</span><div class="qi-reference-radio-options"><label v-for="choice in [{label:'满足全部条件',value:'AND'},{label:'满足任一条件',value:'OR'}]" :key="choice.value"><input type="radio" name="qi-condition-relation" :checked="(trueBranch?.conditionRelation ?? 'AND') === choice.value" :disabled="disabled" @change="setRelation(choice.value)" />{{ choice.label }}</label></div></div>
        <div class="qi-flow-add-row"><Button type="primary" :disabled="disabled" @click="addCondition"><ReferenceIcon name="plus" />添加条件 </Button></div>
        <div class="qi-reference-fields-scroll"><table class="qi-reference-fields-table qi-flow-field-table"><thead><tr><th>序号</th><th>字段名称</th><th>判断条件</th><th>比较值</th><th>逻辑</th><th>操作</th></tr></thead><tbody><tr v-for="(condition,index) in conditions" :key="index"><td>{{ index + 1 }}</td><td><Select :value="condition.fieldName || undefined" :options="conditionFieldOptions" :disabled="disabled" show-search option-filter-prop="label" placeholder="请选择字段" :aria-label="`第${index + 1}行字段名称`" @update:value="(value) => setCondition(index,'fieldName',value ?? '')" /></td><td><Select :value="condition.operator || undefined" :options="conditionOperatorOptions(condition.operator)" :disabled="disabled" placeholder="请选择判断条件" :aria-label="`第${index + 1}行判断条件`" @update:value="(value) => setCondition(index,'operator',value ?? '')" /></td><td><div v-if="!valuelessOperators.includes(condition.operator)" class="qi-flow-value-cell"><Input v-if="valueEditing === index" class="qi-flow-value-input" :value="valueDraft" autofocus placeholder="请输入比较值" :aria-label="`第${index + 1}行比较值`" @update:value="(value) => valueDraft = String(value ?? '')" @press-enter="commitValue(index)" @blur="commitValue(index)" /><span v-else-if="hasValue(condition)" class="qi-flow-value-tag"><span role="button" tabindex="0" @click="startValue(index)">{{ condition.value }}</span><button type="button" :disabled="disabled" aria-label="清除比较值" @click="setCondition(index,'value','')">×</button></span><button v-else type="button" class="qi-flow-value-add" :disabled="disabled" aria-label="添加比较值" @click="startValue(index)">+</button></div></td><td>{{ trueBranch?.conditionRelation === 'OR' ? '或' : '且' }}</td><td><Button type="link" danger :disabled="disabled" @click="removeCondition(index)"><ReferenceIcon name="delete" />删除 </Button></td></tr><tr v-if="!conditions.length"><td colspan="6" class="qi-reference-empty">暂无记录</td></tr></tbody></table></div>
        <p class="qi-reference-notice"> 条件判断结果为True时，数据流向True组件；结果为False时，数据流向False组件。 </p>
      </template>
      <template v-else>
        <div class="qi-flow-add-row"><Button class="qi-reference-button" :disabled="disabled || !canAddBranchField" @click="addBranchField"><ReferenceIcon name="plus" />添加字段 </Button></div>
        <table class="qi-reference-fields-table qi-flow-field-table"><thead><tr><th>序号</th><th>英文名称</th><th>中文名称</th><th>字段类型</th><th>是否主键</th><th :aria-sort="sortState('length')"><button type="button" class="qi-flow-sort" aria-label="按字段长度排序" @click="toggleSort('length')">字段长度<span class="qi-sort-caret" :data-sort="sortDirection('length')" aria-hidden="true"></span></button></th><th :aria-sort="sortState('scale')"><button type="button" class="qi-flow-sort" aria-label="按小数精度排序" @click="toggleSort('scale')">小数精度<span class="qi-sort-caret" :data-sort="sortDirection('scale')" aria-hidden="true"></span></button></th><th>操作</th></tr></thead><tbody><tr v-for="(entry,position) in branchEntries" :key="entry.index"><td>{{ position + 1 }}</td><td><Select :value="entry.name || undefined" :options="branchFieldOptions(entry.name)" :disabled="disabled" show-search placeholder="请选择字段" :aria-label="`第${position + 1}行英文名称`" @update:value="(value) => setBranchField(entry.index,value)" /></td><td>{{ entry.meta?.comment ?? '-' }}</td><td>{{ entry.meta?.dataType ?? '-' }}</td><td><span class="qi-flow-tag">{{ entry.meta?.isPrimaryKey ? '是' : '否' }}</span></td><td>{{ entry.meta?.length ?? '-' }}</td><td>{{ entry.meta?.scale ?? '-' }}</td><td><Button type="link" danger :disabled="disabled" @click="removeBranchField(entry.index)"><ReferenceIcon name="delete" />删除 </Button></td></tr><tr v-if="!branchEntries.length"><td colspan="8" class="qi-reference-empty">暂无记录</td></tr></tbody></table>
        <p class="qi-reference-notice"> 条件判断结果为{{ step === 2 ? 'True' : 'False' }} 时，流转至该组件。 </p>
      </template>
    </template>
    <ReferenceCleanEditor ref="specialEditor" v-else-if="toolCode === 'data-clean'" :config="config" :input-fields="inputFields" :disabled="disabled" />
    <ReferencePivotEditor ref="specialEditor" v-else-if="toolCode === 'row-column'" :config="config" :component-code="componentCode" :input-fields="inputFields" :mode="mode" :disabled="disabled" />
    <ReferenceSubflowEditor v-else-if="toolCode === 'sub-pipeline'" :config="config" :disabled="disabled"><template #description><slot name="description"></slot></template></ReferenceSubflowEditor>
  </div>
</template>
