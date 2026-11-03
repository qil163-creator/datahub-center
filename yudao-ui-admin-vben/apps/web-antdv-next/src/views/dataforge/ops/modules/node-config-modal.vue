<script setup lang="ts">
import type { PublicFlowNode } from '../model';

import { computed } from 'vue';

import { Button, Input, Modal, Switch, TextArea } from 'antdv-next';

const props = defineProps<{ node?: PublicFlowNode }>();
defineEmits<{ close: [] }>();
const type = computed(() => props.node?.type);
const typeNames: Record<string, string> = {
  '1': '表输入组件',
  '31': '转换组件',
  '91': '表输出组件',
};
const typeName = computed(
  () => typeNames[type.value || ''] || props.node?.label || '-',
);
function value(...names: string[]) {
  return (
    props.node?.publicConfig?.find((field) => names.includes(field.name))
      ?.value || '-'
  );
}
const readMode = computed(() => {
  const mode = value('读取方式', '读取模式');
  if (mode === '1') return '全量';
  return mode === '2' ? '增量' : mode;
});
const writeMode = computed(() => {
  const mode = value('写入方式', '写入模式');
  if (mode === '2') return '追加';
  return mode === '1' ? '覆盖' : mode;
});
const fields = computed<NonNullable<PublicFlowNode['publicFields']>>(
  () =>
    props.node?.publicFields ??
    value('字段')
      .split(/[，,]/u)
      .filter((name) => name !== '-')
      .map((name) => ({ name })),
);
const mappings = computed(
  () =>
    props.node?.publicMappings ??
    fields.value.map((field) => ({ source: field.name, target: field.name })),
);
</script>

<template>
  <Modal
    :open="!!node"
    :title="node?.label"
    width="1200px"
    wrap-class-name="qo-modal qo-node-modal"
    :style="{ top: '64px' }"
    :mask-closable="false"
    @cancel="$emit('close')"
  >
    <div class="qo-node-form">
      <label><span><i>*</i>节点名称</span><Input :value="node?.label" readonly /></label>
      <label><span>类型</span><Input :value="typeName" readonly /></label>
      <label class="qo-node-full"><span>描述</span><TextArea
          :value="value('描述')"
          readonly
          :rows="type === '91' ? 1 : 3"
      /></label>
      <template v-if="type === '1'">
        <label><span>连接方式</span><Input value="数据连接" readonly /></label>
        <div></div>
        <label><span><i>*</i>源数据库连接</span><Input :value="value('源数据库连接', '数据源名称')" readonly /></label>
        <label><span>数据连接类型</span><Input :value="value('数据源类型', '数据库类型')" readonly /></label>
        <label><span>数据连接实例</span><Input :value="value('数据库', '库名')" readonly /></label>
        <label><span><i>*</i>选择表/视图</span><Input :value="value('数据表', '表名')" readonly /></label>
        <label><span><i>*</i>读取模式</span><Input :value="readMode" readonly /></label>
        <div></div>
      </template>
      <template v-if="type === '91'">
        <label><span><i>*</i>目标数据连接</span><Input :value="value('目标数据连接', '数据源名称')" readonly /></label>
        <label><span>数据连接类型</span><Input :value="value('数据源类型', '数据库类型')" readonly /></label>
        <label><span>数据连接实例</span><Input :value="value('数据库', '库名')" readonly /></label>
        <label><span><i>*</i>选择表</span><Input :value="value('数据表', '表名')" readonly /></label>
      </template>
      <label class="qo-node-full"><span>where条件</span><TextArea
          :value="value('过滤条件', 'where条件')"
          readonly
          :rows="type === '31' ? 3 : 1"
      /></label>
    </div>
    <template v-if="type === '1'">
      <div class="qo-node-section">
        <h4>分批读取</h4>
        <label><i>*</i>启用分批读取<Switch
            :checked="['true', '1', '是'].includes(value('启用分批读取'))"
            disabled
            size="small"
        /></label>
      </div>
      <div class="qo-node-section"><h4>属性字段</h4></div>
      <div class="qo-node-table-scroll">
        <table class="qo-node-table">
          <thead>
            <tr>
              <th>序号</th>
              <th>英文名称</th>
              <th>中文名称</th>
              <th>字段类型</th>
              <th>是否主键</th>
              <th>字段长度</th>
              <th>小数精度</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(field, index) in fields"
              :key="`${field.name}:${index}`"
            >
              <td>{{ index + 1 }}</td>
              <td>{{ field.name }}</td>
              <td>{{ field.description || '-' }}</td>
              <td>{{ field.type || '-' }}</td>
              <td>
                <Switch :checked="!!field.primary" disabled size="small" />
              </td>
              <td>{{ field.length || '-' }}</td>
              <td>{{ field.scale || '-' }}</td>
            </tr>
            <tr v-if="!fields.length">
              <td colspan="7" class="qo-empty">暂无数据</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
    <template v-else-if="type === '31'">
      <div class="qo-node-section"><h4>规则设置</h4></div>
      <div class="qo-node-table-scroll qo-rule-table">
        <table class="qo-node-table">
          <thead>
            <tr>
              <th>序号</th>
              <th>清洗名称</th>
              <th>清洗字段</th>
              <th>清洗规则</th>
              <th>规则描述</th>
              <th>维度</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(rule, index) in node?.publicRules || []" :key="index">
              <td>{{ index + 1 }}</td>
              <td>{{ rule.name }}</td>
              <td>{{ rule.field }}</td>
              <td>{{ rule.rule }}</td>
              <td>{{ rule.description || '-' }}</td>
              <td>{{ rule.dimension || '-' }}</td>
            </tr>
            <tr v-if="!node?.publicRules?.length">
              <td colspan="6" class="qo-empty">暂无数据</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
    <template v-else-if="type === '91'">
      <div class="qo-node-section"><h4>字段映射</h4></div>
      <div class="qo-field-mapping">
        <div class="qo-mapping-head">
          <span>来源表字段：</span><span></span><span>目标字段：</span>
        </div>
        <div class="qo-mapping-head">
          <span><input type="checkbox" checked disabled />全选</span><span></span><span><input type="checkbox" checked disabled />全选</span>
        </div>
        <div
          v-for="(mapping, index) in mappings"
          :key="index"
          class="qo-mapping-row"
        >
          <span><input type="checkbox" checked disabled />{{
              mapping.source
            }}</span><b aria-hidden="true"></b><span><input type="checkbox" checked disabled />{{
              mapping.target
            }}</span>
        </div>
      </div>
      <div class="qo-node-section"><h4>输出配置</h4></div>
      <div class="qo-node-form">
        <label class="qo-node-full"><span>前置SQL</span><TextArea :value="value('前置SQL')" readonly :rows="2" /></label><label class="qo-node-full"><span>后置SQL</span><TextArea :value="value('后置SQL')" readonly :rows="2" /></label><label><span>写入模式</span><Input :value="writeMode" readonly /></label><label><span>批量条数</span><Input :value="value('批次大小', '批量条数')" readonly /></label>
      </div>
    </template>
    <div v-else class="qo-node-form">
      <label
        v-for="field in node?.publicConfig || []"
        :key="field.name"
        class="qo-node-full"
        ><span>{{ field.name }}</span><TextArea
          :value="field.value"
          readonly
          :auto-size="{ minRows: 1, maxRows: 8 }"
      /></label>
    </div>
    <template #footer><Button @click="$emit('close')">关闭</Button></template>
  </Modal>
</template>
