<script setup lang="ts">
import type { EditableLogicKind } from '../editor-state';

import type { ModelingModelApi } from '#/api/modeling/model';
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { computed, ref } from 'vue';

import { useAntdDesignTokens } from '@vben/hooks';

import {
  Button,
  Checkbox,
  ConfigProvider,
  Modal,
  Radio,
  RadioGroup,
  Select,
  Spin,
  TextArea,
} from 'antdv-next';

import { parseSqlFields } from '#/api/modeling/tablemodel';
import { useObjectRequest } from '#/hooks/use-object-request';

import emptyRecords from '../assets/empty-records.png';
import { parsedField } from '../field-import-state';

import '../modal.css';
import '../version.css';

const props = defineProps<{ kind: EditableLogicKind }>();

const emit = defineEmits<{
  import: [fields: ModelingModelApi.ModelField[], replace: boolean];
}>();

const { tokens } = useAntdDesignTokens();

const DIALECTS: { label: string; value: TableModelApi.SqlDialect }[] = [
  { label: 'Doris', value: 'DORIS' },
  { label: 'MySQL', value: 'MYSQL' },
  { label: 'PostgreSQL', value: 'POSTGRESQL' },
  { label: 'Oracle', value: 'ORACLE' },
];

const visible = ref(false);
const dialect = ref<TableModelApi.SqlDialect>('DORIS');
const sql = ref('');
const parsing = ref(false);
const result = ref<TableModelApi.SqlParseResult>();
const checked = ref(new Set<number>());
const replace = ref(false);
const guard = useObjectRequest(() => 'sql-parse');

const fields = computed(() => result.value?.fields ?? []);
const allChecked = computed(
  () => fields.value.length > 0 && checked.value.size === fields.value.length,
);

function open() {
  guard.begin();
  dialect.value = 'DORIS';
  sql.value = '';
  result.value = undefined;
  checked.value = new Set();
  replace.value = false;
  parsing.value = false;
  visible.value = true;
}

async function parse() {
  if (!sql.value.trim() || parsing.value) return;
  const current = guard.begin();
  parsing.value = true;
  result.value = undefined;
  try {
    // 解析失败的原因由全局请求错误提示展示，弹窗保留输入便于修改后重试。
    const parsed = await parseSqlFields({ dialect: dialect.value, sql: sql.value });
    if (!current()) return;
    result.value = parsed;
    checked.value = new Set(parsed.fields.map((_, index) => index));
  } finally {
    if (current()) parsing.value = false;
  }
}

function toggle(index: number, value: boolean) {
  const next = new Set(checked.value);
  if (value) next.add(index);
  else next.delete(index);
  checked.value = next;
}

function toggleAll(value: boolean) {
  checked.value = value ? new Set(fields.value.map((_, index) => index)) : new Set();
}

function describe(field: ModelingModelApi.ModelField) {
  if (field.dataPrecision !== undefined && field.dataPrecision !== null)
    return `${field.dataType}(${field.dataPrecision},${field.dataScale ?? 0})`;
  if (field.dataLength !== undefined && field.dataLength !== null)
    return `${field.dataType}(${field.dataLength})`;
  return field.dataType || '-';
}

function close() {
  guard.begin();
  visible.value = false;
}

function confirm() {
  const selected = fields.value
    .filter((_, index) => checked.value.has(index))
    .map((field) => parsedField(field, props.kind));
  if (selected.length === 0) return;
  emit('import', selected, replace.value);
  close();
}

defineExpose({ open });
</script>

<template>
  <ConfigProvider :theme="{ token: { borderRadius: tokens.borderRadius } }">
    <Modal
      v-model:open="visible"
      title="SQL解析"
      class="qd-logic-modal"
      :width="960"
      :footer="null"
      :style="{ top: '68px' }"
      destroy-on-hidden
      @cancel="close"
    >
      <Spin :spinning="parsing">
        <div class="qd-modal-scroll">
          <div class="qd-import-toolbar">
            <Select
              v-model:value="dialect"
              :options="DIALECTS"
              aria-label="建表语句方言"
            />
            <span class="qd-import-toolbar__hint">
              粘贴一条 CREATE TABLE 语句，可附带同一张表的 COMMENT ON 语句；只解析不执行
            </span>
          </div>
          <TextArea
            v-model:value="sql"
            class="qd-sql-editor"
            :rows="8"
            :maxlength="100000"
            placeholder="CREATE TABLE dw.fact_order (order_id BIGINT NOT NULL COMMENT '订单号', ...)"
          />
          <div class="qd-import-toolbar" style="margin-top: 12px">
            <Button type="primary" :disabled="!sql.trim()" @click="parse">
              解析
            </Button>
            <template v-if="result">
              <span>
                表 {{ result.tableName
                }}{{ result.tableComment ? `（${result.tableComment}）` : '' }}，共
                {{ fields.length }} 个字段
              </span>
              <RadioGroup v-model:value="replace">
                <Radio :value="false">追加到现有字段</Radio>
                <Radio :value="true">
                  {{ kind === 'application' ? '替换现有字段' : '替换主键以外的字段' }}
                </Radio>
              </RadioGroup>
            </template>
          </div>
          <div v-if="result" class="qd-import-table-wrap">
            <table class="qd-summary-table">
              <thead>
                <tr>
                  <th class="is-check">
                    <Checkbox
                      :checked="allChecked"
                      :indeterminate="!allChecked && checked.size > 0"
                      aria-label="全选"
                      @change="toggleAll($event.target.checked)"
                    />
                  </th>
                  <th>字段英文名称</th>
                  <th>字段中文名称</th>
                  <th>数据类型</th>
                  <th>主键</th>
                  <th>可为空</th>
                  <th>默认值</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(field, index) in fields" :key="index">
                  <td class="is-check">
                    <Checkbox
                      :checked="checked.has(index)"
                      :aria-label="`选择${field.fieldName}`"
                      @change="toggle(index, $event.target.checked)"
                    />
                  </td>
                  <td>{{ field.fieldName }}</td>
                  <td>{{ field.cnName }}</td>
                  <td>{{ describe(field) }}</td>
                  <td>{{ field.pkFlag ? '是' : '否' }}</td>
                  <td>{{ field.nullableFlag === false ? '否' : '是' }}</td>
                  <td>{{ field.defaultValue ?? '' }}</td>
                </tr>
              </tbody>
            </table>
            <div v-if="fields.length === 0" class="qd-summary-empty">
              <img :src="emptyRecords" alt="" />
              <p>未解析到字段</p>
            </div>
          </div>
        </div>
      </Spin>
      <footer class="qd-modal-footer">
        <Button @click="close">取消</Button><Button
          type="primary"
          :disabled="!result || checked.size === 0"
          @click="confirm"
        >
          确定引入
        </Button>
      </footer>
    </Modal>
  </ConfigProvider>
</template>
