<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 仅编辑设计器隔离草稿，保留既有节点执行协议。 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, onMounted, ref, watch } from 'vue';

import { Alert, AutoComplete, Button, Checkbox, Input, InputNumber, Select } from 'antdv-next';

import ReferenceCleanEditor from './reference-clean-editor.vue';
import ReferenceIcon from './reference-icon.vue';
import { referenceFieldTypes } from './reference-node-schema';
import ReferencePivotEditor from './reference-pivot-editor.vue';
import ReferenceSubflowEditor from './reference-subflow-editor.vue';

import './reference-node-editor.css';
import './reference-transform-layout.css';

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
  ? ['基本属性', 'A组件字段配置', 'B组件字段配置']
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
const joinOperators = [['EQ','='],['NE','≠'],['GT','>'],['GTE','≥'],['LT','<'],['LTE','≤']].map(([value,label]) => ({value,label}));
const joinTypes = [{label:'LEFT JOIN',value:'LEFT'},{label:'RIGHT JOIN',value:'RIGHT'},{label:'INNER JOIN',value:'INNER'}];
// 新建节点默认 LEFT JOIN；已有连接配置的历史节点仍按后端默认 INNER 展示。
const freshJoin = computed(() => props.toolCode==='multi-join' && !props.config.joinType && joinRows.value.length === 0 && !props.config.leftNodeNo && !props.config.leftFields);
const joinType = computed(() => props.config.joinType ?? (freshJoin.value ? 'LEFT' : 'INNER'));
const projectionKey = computed(() => step.value === 1 ? 'leftFields' : 'rightFields');
const leftNode = computed(() => props.upstreamNodes?.find((node) => node.nodeNo===props.config.leftNodeNo) ?? props.upstreamNodes?.[0]);
const rightNode = computed(() => props.upstreamNodes?.find((node) => node.nodeNo===props.config.rightNodeNo) ?? props.upstreamNodes?.[1]);
const upstreamOptions = computed(() => (props.upstreamNodes ?? []).map((node) => ({value:node.nodeNo,label:node.name})));
const projectionInputs = computed(() => (step.value === 1 ? leftNode.value : rightNode.value)?.fields ?? props.inputFields ?? []);
function chooseJoinSource(index: number,value: unknown) {
  if (props.disabled || !value) return;
  const nodes=props.upstreamNodes ?? [];
  const currentLeft=leftNode.value?.nodeNo; const currentRight=rightNode.value?.nodeNo;
  let left=index===0 ? String(value) : currentLeft;
  let right=index===1 ? String(value) : currentRight;
  // 仅两个上游时，选择 A 后 B 自动取另一个上游。
  if (nodes.length===2 && index===0) right=nodes.find((node) => node.nodeNo!==left)?.nodeNo;
  if (left && right && left!==right) { props.config.leftNodeNo=left; props.config.rightNodeNo=right; }
}
function addLegacyBranch(otherwise=false) { if (!props.disabled) props.config.branches=[...branches.value,{id:`branch_${Date.now()}_${branches.value.length}`,name:'新分支',type:otherwise?'ELSE':'IF',fieldName:'',operator:'EQ',value:'',targetNodeNo:''}]; }
function removeLegacyBranch(index:number) { if (!props.disabled) props.config.branches=branches.value.filter((_,position) => position!==index); }
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
  if (rows[index]) rows[index][key]=value;
  branch.conditions=rows;
}
function addCondition() { if (props.disabled) return; const branch=ensureBranch(); if (branch) branch.conditions=[...conditions.value.map((row) => ({...row})), {fieldName:'',operator:'EQ',value:''}]; }
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
const aliasCounts = computed(() => {
  const counts = new Map<string, number>();
  const rowsOf = (key: string, node?: { fields?: DataforgeEtlApi.FieldMeta[] }) => Array.isArray(props.config[key])
    ? props.config[key] as Record<string, any>[]
    : (node?.fields ?? []).filter((field) => field.name).map((field) => ({ name: field.name, alias: field.name }));
  for (const row of [...rowsOf('leftFields', leftNode.value), ...rowsOf('rightFields', rightNode.value)]) {
    const alias = String(row.alias || row.name || '').trim();
    if (alias) counts.set(alias, (counts.get(alias) ?? 0) + 1);
  }
  return counts;
});
const aliasConflict = (row: Record<string, any>) => (aliasCounts.value.get(String(row.alias || row.name || '').trim()) ?? 0) > 1;
const fieldDataType = (row: Record<string, any>) => row.type ?? projectionInputs.value.find((field) => field.name === row.name)?.dataType ?? '-';
const fieldComment = (row: Record<string, any>) => (projectionInputs.value.find((field) => field.name === row.name) as { comment?: string } | undefined)?.comment || '-';
const projectionFull = computed(() => projectionOptions.value.every((option) => projectionRows.value.some((row) => row.name === option.value)));
const projectionTitle = computed(() => (step.value === 1 ? leftNode.value : rightNode.value)?.name ?? '');
function branchSelected(name: string) { return !Array.isArray(activeBranch.value?.fields) || activeBranch.value?.fields.includes(name); }
function selectBranchField(name: string, selected: boolean) {
  if (props.disabled) return;
  const branch=ensureBranch(step.value===3); if (!branch) return;
  const current=Array.isArray(branch.fields) ? branch.fields : fields.value.map((field) => field.value);
  branch.fields=selected ? [...new Set([...current,name])] : current.filter((field: string) => field!==name);
}
function moveStep(value: number) { step.value=Math.max(0,Math.min(steps.value.length-1,value)); }
const stepNav = computed(() => ['multi-join','data-condition'].includes(props.toolCode)
  ? { last: steps.value.length - 1, next: () => moveStep(step.value + 1), prev: () => moveStep(step.value - 1), step: step.value }
  : undefined);
function validate() {
  if (props.toolCode==='row-column') return specialEditor.value?.validate?.() ?? true;
  const issues: string[]=[];
  if (props.toolCode==='multi-join') {
    if (joinRows.value.length === 0 || joinRows.value.some((row) => !row.leftField?.trim() || !row.rightField?.trim())) issues.push('请填写完整连接条件');
    for (const key of ['leftFields','rightFields']) if (Array.isArray(props.config[key]) && props.config[key].some((field: Record<string,any>) => !field.name?.trim())) issues.push('请填写投影来源字段');
    if (Array.isArray(props.config.leftFields) && Array.isArray(props.config.rightFields) && props.config.leftFields.length === 0 && props.config.rightFields.length === 0) issues.push('请至少保留一个输出字段');
  } else if (props.toolCode==='data-condition') {
    for (const branch of branches.value) {
      if (!branch.targetNodeNo) issues.push('请选择分支下游组件');
      if (Array.isArray(branch.fields) && (branch.fields.length === 0 || new Set(branch.fields).size!==branch.fields.length)) issues.push('每个分支需选择不重复的输出字段');
    }
  }
  errors.value=issues;
  return issues.length === 0;
}
watch(() => [props.config,props.toolCode], () => { step.value=0; errors.value=[]; });
const canGetRules=computed(() => props.toolCode==='data-clean');
function openRulePicker() { specialEditor.value?.openRulePicker?.(); }
onMounted(() => { if (!props.disabled && freshJoin.value) props.config.joinType = 'LEFT'; });
defineExpose({validate,canGetRules,openRulePicker,stepNav});
</script>

<template>
  <div class="qi-reference-editor qi-transform-reference qi-reference-flow" :data-tool-code="toolCode" :data-component-code="componentCode">
    <Alert v-if="errors.length" type="error" :message="errors.join('；')" />
    <template v-if="['multi-join','data-condition'].includes(toolCode)">
      <nav class="qi-reference-steps" aria-label="配置步骤">
        <button v-for="(label,index) in steps" :key="label" type="button" :aria-current="step === index ? 'step' : undefined" @click="moveStep(index)"><span class="qi-reference-step-number">{{ index + 1 }}</span>{{ label }}</button>
      </nav>
      <template v-if="step === 0">
        <slot name="basics"></slot>
        <template v-if="toolCode === 'multi-join'">
          <div class="qi-reference-form-row"><label class="qi-id-required">Join模式</label><Select :value="joinType" :disabled="disabled" :options="joinTypes" @update:value="(value) => set('joinType',value)" /></div>
          <div v-for="(label,index) in ['A组件','B组件']" :key="label" class="qi-reference-form-row"><label class="qi-id-required">{{ label }}</label><Select :value="index === 0 ? leftNode?.nodeNo : rightNode?.nodeNo" :options="upstreamOptions" :disabled="disabled || upstreamOptions.length < 2 || (index === 1 && upstreamOptions.length <= 2)" :placeholder="`请选择${label}`" @update:value="(value) => chooseJoinSource(index,value)" /></div>
          <Button type="primary" :disabled="disabled" @click="addJoin"><ReferenceIcon name="plus" />添加条件</Button>
          <p class="qi-reference-notice">点击 “+ 添加条件” 可新增A组件与B组件字段的关联条件，用于更精准地定义两表数据的连接规则</p>
          <table class="qi-reference-fields-table qi-join-conditions"><thead><tr><th>A组件字段</th><th>运算符</th><th>B组件字段</th><th>操作</th></tr></thead><tbody><tr v-for="(condition,index) in joinRows" :key="index"><td><AutoComplete :value="condition.leftField" :options="leftNode?.fields?.map((field) => ({value:field.name,label:field.name})) ?? fields" :disabled="disabled" placeholder="请选择A组件字段" @update:value="(value) => update(condition,'leftField',value)" /></td><td><Select :value="condition.operator ?? 'EQ'" :options="joinOperators" :disabled="disabled" @update:value="(value) => update(condition,'operator',value)" /></td><td><AutoComplete :value="condition.rightField" :options="rightNode?.fields?.map((field) => ({value:field.name,label:field.name})) ?? fields" :disabled="disabled" placeholder="请选择B组件字段" @update:value="(value) => update(condition,'rightField',value)" /></td><td><Button type="link" danger :disabled="disabled" @click="removeJoin(index)"><ReferenceIcon name="delete" />删除</Button></td></tr><tr v-if="!joinRows.length"><td colspan="4" class="qi-reference-empty">暂无记录</td></tr></tbody></table>
        </template>
        <template v-else>
          <div class="qi-reference-form-row"><label>True组件</label><Select :value="trueBranch?.targetNodeNo" :options="targets" :disabled="disabled || !targets.length" placeholder="请选择True组件" @update:value="(value) => setTarget(false,value)" /></div>
          <div class="qi-reference-form-row"><label>False组件</label><Select :value="falseBranch?.targetNodeNo" :options="targets" :disabled="disabled || !targets.length" placeholder="请选择False组件" @update:value="(value) => setTarget(true,value)" /></div>
          <div class="qi-reference-section-header qi-reference-table-heading"><h3>输入字段</h3></div>
          <table class="qi-reference-fields-table"><thead><tr><th>序号</th><th>英文名称</th><th>中文名称</th><th>字段类型</th><th>是否主键</th><th>字段长度</th><th>小数精度</th></tr></thead><tbody><tr v-for="(field,index) in inputFields" :key="field.name"><td>{{ index + 1 }}</td><td>{{ field.name }}</td><td>{{ field.comment ?? '-' }}</td><td>{{ field.dataType }}</td><td>{{ field.isPrimaryKey ? '是' : '否' }}</td><td>{{ field.length ?? '-' }}</td><td>{{ field.scale ?? '-' }}</td></tr><tr v-if="!inputFields?.length"><td colspan="7" class="qi-reference-empty">暂无记录</td></tr></tbody></table>
          <p class="qi-reference-notice">根据配置的条件对上游输入数据进行判断，并将数据分发至不同的下游步骤。</p>
        </template>
      </template>
      <template v-else-if="toolCode === 'multi-join'">
        <div class="qi-join-projection-heading"><Button :disabled="disabled || projectionFull" class="qi-reference-button qi-script-add-button" :auto-insert-space="false" @click="addProjection"><ReferenceIcon name="plus" />新增</Button><span>{{ projectionTitle }}</span></div>
        <div class="qi-reference-fields-scroll"><table class="qi-reference-fields-table qi-join-projection"><thead><tr><th>序号</th><th>字段名称</th><th>字段别名</th><th>数据类型</th><th>字段说明</th><th>操作</th></tr></thead><tbody><tr v-for="(field,index) in projectionRows" :key="index"><td>{{ index + 1 }}</td><td><Select :value="field.name || undefined" :options="projectionOptions" :disabled="disabled" placeholder="请选择字段名称" show-search @update:value="(value) => setProjection(index,'name',value)" /></td><td><Input :value="field.alias" :class="{'is-conflict':aliasConflict(field)}" :disabled="disabled" @update:value="(value) => setProjection(index,'alias',value)" /></td><td>{{ fieldDataType(field) }}</td><td>{{ fieldComment(field) }}</td><td><Button type="link" danger :disabled="disabled" @click="removeProjection(index)"><ReferenceIcon name="delete" />删除</Button></td></tr><tr v-if="!projectionRows.length"><td colspan="6" class="qi-reference-empty">暂无记录</td></tr></tbody></table></div>
        <p class="qi-reference-notice">Join连接后，{{ step === 1 ? 'A' : 'B' }}组件数据仅保留已选字段，未配置的字段不会出现在合并结果中。</p>
        <details class="qi-transform-advanced"><summary>输出字段类型与精度</summary><table class="qi-reference-fields-table"><thead><tr><th>字段名称</th><th>字段类型</th><th>字段长度</th><th>小数精度</th></tr></thead><tbody><tr v-for="(field,index) in projectionRows" :key="index"><td>{{ field.name }}</td><td><Select :value="field.type" :options="referenceFieldTypes" allow-clear :disabled="disabled" @update:value="(value) => setProjection(index,'type',value)" /></td><td><Input :value="field.length" :disabled="disabled" @update:value="(value) => setProjection(index,'length',value)" /></td><td><InputNumber :value="field.precision" :min="0" :precision="0" :disabled="disabled" @update:value="(value) => setProjection(index,'precision',value)" /></td></tr></tbody></table></details>
      </template>
      <template v-else-if="step === 1">
        <Alert type="info" message="条件说明" description="根据输入字段配置判断条件；满足条件的数据进入True组件，不满足的数据进入False组件。" />
        <div class="qi-reference-form-row"><label>条件关系</label><div class="qi-reference-radio-options"><label v-for="choice in [{label:'满足全部条件',value:'AND'},{label:'满足任一条件',value:'OR'}]" :key="choice.value"><input type="radio" name="qi-condition-relation" :checked="(trueBranch?.conditionRelation ?? 'AND') === choice.value" :disabled="disabled" @change="setRelation(choice.value)" />{{ choice.label }}</label></div></div>
        <Button type="primary" :disabled="disabled" @click="addCondition"><ReferenceIcon name="plus" />添加条件</Button>
        <table class="qi-reference-fields-table"><thead><tr><th>序号</th><th>字段名称</th><th>判断条件</th><th>比较值</th><th>逻辑</th><th>操作</th></tr></thead><tbody><tr v-for="(condition,index) in conditions" :key="index"><td>{{ index + 1 }}</td><td><AutoComplete :value="condition.fieldName" :options="fields" :disabled="disabled" @update:value="(value) => setCondition(index,'fieldName',value)" /></td><td><Select :value="condition.operator" :options="operators" :disabled="disabled" @update:value="(value) => setCondition(index,'operator',value)" /></td><td><Input v-if="!['IS_NULL','NOT_NULL'].includes(condition.operator)" :value="condition.value" :disabled="disabled" @update:value="(value) => setCondition(index,'value',value)" /></td><td>{{ trueBranch?.conditionRelation === 'OR' ? '或' : '且' }}</td><td><Button type="link" danger :disabled="disabled" @click="removeCondition(index)">删除</Button></td></tr><tr v-if="!conditions.length"><td colspan="6" class="qi-reference-empty">暂无记录</td></tr></tbody></table>
        <p class="qi-reference-notice">条件判断结果为True时，数据流向True组件；结果为False时，数据流向False组件。</p>
      </template>
      <template v-else>
        <div class="qi-reference-section-header qi-reference-table-heading"><h3>{{ step === 2 ? 'True' : 'False' }}组件字段配置</h3></div>
        <table class="qi-reference-fields-table"><thead><tr><th>选择</th><th>序号</th><th>字段名称</th><th>字段类型</th><th>字段长度</th><th>小数精度</th></tr></thead><tbody><tr v-for="(field,index) in inputFields" :key="field.name"><td><Checkbox :checked="branchSelected(field.name!)" :disabled="disabled" :aria-label="`输出字段${field.name}`" @update:checked="(value) => selectBranchField(field.name!,value)" /></td><td>{{ index + 1 }}</td><td>{{ field.name }}</td><td>{{ field.dataType }}</td><td>{{ field.length ?? '-' }}</td><td>{{ field.scale ?? '-' }}</td></tr><tr v-if="!inputFields?.length"><td colspan="6" class="qi-reference-empty">暂无记录</td></tr></tbody></table>
      </template>
      <details v-if="toolCode === 'multi-join'" class="qi-transform-advanced"><summary>连接关系与执行预算</summary><div class="qi-reference-form-row"><label>条件关系</label><div class="qi-reference-radio-options"><label v-for="choice in [{label:'满足全部条件',value:'AND'},{label:'满足任一条件',value:'OR'}]" :key="choice.value"><input type="radio" name="qi-join-relation" :checked="(config.conditionRelation ?? 'AND') === choice.value" :disabled="disabled" @change="set('conditionRelation',choice.value)" />{{ choice.label }}</label></div></div><div class="qi-reference-form-row"><label>最大输出行数</label><InputNumber :value="config.maxOutputRows" :disabled="disabled" :min="1" :max="10000000" @update:value="(value) => set('maxOutputRows',value)" /></div></details>
      <details v-else class="qi-transform-advanced"><summary>分支兼容配置</summary><p class="qi-reference-notice">历史多分支继续按原顺序匹配；新增True/False配置不会移除其他分支。</p><div class="qi-reference-file-actions"><Button :disabled="disabled" @click="addLegacyBranch()">添加条件分支</Button><Button :disabled="disabled" @click="addLegacyBranch(true)">添加其余情况</Button></div><section v-for="(branch,index) in branches" :key="branch.id"><div class="qi-reference-form-grid"><div class="qi-reference-form-row"><label>分支名称</label><Input :value="branch.name" :disabled="disabled" @update:value="(value) => update(branch,'name',value)" /></div><div class="qi-reference-form-row"><label>分支类型</label><Select :value="branch.type" :disabled="disabled" :options="[{label:'条件分支',value:'IF'},{label:'其余情况',value:'ELSE'}]" @update:value="(value) => update(branch,'type',value)" /></div></div><div class="qi-reference-form-row"><label>流向下游</label><Select :value="branch.targetNodeNo" :options="targets" :disabled="disabled" @update:value="(value) => update(branch,'targetNodeNo',value)" /></div><div v-if="branch.type !== 'ELSE' && !Array.isArray(branch.conditions)" class="qi-reference-form-grid"><AutoComplete :value="branch.fieldName" :options="fields" :disabled="disabled" placeholder="字段名称" @update:value="(value) => update(branch,'fieldName',value)" /><Select :value="branch.operator" :options="operators" :disabled="disabled" @update:value="(value) => update(branch,'operator',value)" /><Input :value="branch.value" :disabled="disabled" placeholder="比较值" @update:value="(value) => update(branch,'value',value)" /></div><Button type="link" danger :disabled="disabled" @click="removeLegacyBranch(index)">删除分支</Button></section><div class="qi-reference-form-row"><label>未命中任何分支时</label><Select :value="config.unmatchedPolicy ?? 'DROP'" :disabled="disabled" :options="[{label:'丢弃',value:'DROP'},{label:'报错',value:'ERROR'}]" @update:value="(value) => set('unmatchedPolicy',value)" /></div></details>
    </template>
    <ReferenceCleanEditor ref="specialEditor" v-else-if="toolCode === 'data-clean'" :config="config" :input-fields="inputFields" :disabled="disabled" />
    <ReferencePivotEditor ref="specialEditor" v-else-if="toolCode === 'row-column'" :config="config" :component-code="componentCode" :input-fields="inputFields" :mode="mode" :disabled="disabled" />
    <ReferenceSubflowEditor v-else-if="toolCode === 'sub-pipeline'" :config="config" :disabled="disabled"><template #description><slot name="description"></slot></template></ReferenceSubflowEditor>
  </div>
</template>
