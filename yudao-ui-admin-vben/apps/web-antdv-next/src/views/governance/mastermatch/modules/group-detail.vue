<script setup lang="ts">
import type { MergeDraft } from '../data';

import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';
import type { GovernanceMasterMatchApi } from '#/api/governance/mastermatch';

import { computed, ref } from 'vue';

import {
  Alert,
  Button,
  Checkbox,
  message,
  Modal,
  Radio,
  Spin,
  Tag,
  TextArea,
} from 'antdv-next';

import {
  getMatchGroup,
  ignoreMatchGroup,
  mergeMatchGroup,
} from '#/api/governance/mastermatch';

import {
  chooseSurvivor,
  displayValue,
  goldenValues,
  groupStatus,
  initialDraft,
  keyLabel,
  mergeIssue,
  mergeRequest,
  toggleParticipant,
} from '../data';

const props = defineProps<{
  canIgnore: boolean;
  canMerge: boolean;
  model: GovernanceMasterDataApi.Model;
}>();
const emit = defineEmits<{ changed: [] }>();

const open = ref(false);
const loading = ref(false);
const submitting = ref(false);
const group = ref<GovernanceMasterMatchApi.Group>();
const draft = ref<MergeDraft>();
const ignoring = ref(false);
const remark = ref('');
/** 同一次打开内重试沿用同一请求标识，服务端据此幂等 */
let requestId = '';
let sequence = 0;

const columns = computed(() => props.model.columns ?? []);
const editable = computed(
  () => group.value?.status === 'PENDING' && props.canMerge,
);
const golden = computed(() =>
  group.value && draft.value
    ? goldenValues(group.value, draft.value, columns.value)
    : {},
);
const issue = computed(() =>
  group.value && draft.value ? mergeIssue(group.value, draft.value) : undefined,
);

async function show(id: GovernanceMasterMatchApi.Id) {
  const current = ++sequence;
  open.value = true;
  loading.value = true;
  group.value = undefined;
  draft.value = undefined;
  ignoring.value = false;
  remark.value = '';
  requestId = crypto.randomUUID();
  try {
    const detail = await getMatchGroup(id);
    if (current !== sequence) return;
    group.value = detail;
    draft.value = initialDraft(detail, columns.value);
  } catch {
    if (current === sequence) open.value = false;
  } finally {
    if (current === sequence) loading.value = false;
  }
}

function pick(field: string, index: number) {
  if (!editable.value || !draft.value) return;
  const column = columns.value.find((item) => item.name === field);
  if (column?.pk === 1 || !draft.value.participating[index]) return;
  draft.value.sources[field] = index;
}

function sourceOf(field: string) {
  const column = columns.value.find((item) => item.name === field);
  return column?.pk === 1
    ? draft.value?.survivor
    : (draft.value?.sources[field] ?? draft.value?.survivor);
}

/** 与保留记录取值不同的单元格高亮，便于发现冲突字段 */
function differs(field: string, index: number) {
  const rows = group.value?.members;
  if (!rows || !draft.value) return false;
  return (
    displayValue(rows[index]?.row[field]) !==
    displayValue(rows[draft.value.survivor]?.row[field])
  );
}

async function submit() {
  if (!group.value || !draft.value || issue.value) return;
  submitting.value = true;
  try {
    const result = await mergeMatchGroup(
      mergeRequest(group.value, draft.value, columns.value, requestId),
    );
    if (result.status === 'APPLIED') message.success('合并已生效，黄金记录已更新');
    else if (result.status === 'PENDING' || result.status === 'WAIT_START')
      message.success('合并已提交审批，审批通过后生效');
    else if (result.status === 'UNKNOWN')
      message.warning('合并结果待核查，请到主数据审批中处理');
    else if (result.status === 'REJECTED')
      message.error(result.message || '合并未生效，请刷新后重试');
    else message.info('合并已受理，请查看主数据审批');
    open.value = false;
    emit('changed');
  } finally {
    submitting.value = false;
  }
}

async function ignore() {
  if (!group.value) return;
  submitting.value = true;
  try {
    await ignoreMatchGroup(group.value.id, remark.value.trim() || undefined);
    message.success('已忽略，再次匹配时不会重复列出这组记录');
    open.value = false;
    emit('changed');
  } finally {
    submitting.value = false;
  }
}

defineExpose({ open: show });
</script>

<template>
  <Modal
    v-model:open="open"
    title="疑似重复记录"
    width="min(1200px, 94vw)"
    :footer="null"
    destroy-on-close
  >
    <Spin v-if="loading" class="block py-10 text-center" />
    <template v-else-if="group && draft">
      <div class="match-detail__head">
        <Tag :color="groupStatus(group.status).color">
          {{ groupStatus(group.status).label }}
        </Tag>
        <span>{{ group.memberCount }} 条记录，最高相似度 {{ group.maxScore }}</span>
        <span v-if="group.changeId" class="match-detail__muted">
          合并申请 #{{ group.changeId }}
          <template v-if="group.changeStatus === 'REJECTED'">已驳回</template>
          <template v-else-if="group.changeStatus === 'CANCELLED'">
            已取消
          </template>
        </span>
      </div>

      <div v-if="group.pairs?.length" class="match-detail__pairs">
        <div v-for="(pair, index) in group.pairs" :key="index">
          记录 {{ pair.left + 1 }} ↔ 记录 {{ pair.right + 1 }}：
          <b>{{ pair.score }}</b>
          <span class="match-detail__muted">
            （{{
              Object.entries(pair.fields)
                .map(([name, score]) => `${name} ${score}`)
                .join('，')
            }}）
          </span>
        </div>
      </div>

      <Alert
        v-if="group.members.some((member) => member.exists === false)"
        type="warning"
        show-icon
        class="mb-3"
        message="部分记录在匹配后已被修改或删除，不能参与合并；显示的是匹配时的内容。"
      />
      <Alert
        v-if="editable"
        type="info"
        show-icon
        class="mb-3"
        :message="
          model.approvalEnabled
            ? '提交后按模型审批配置进入主数据审批，审批通过后：保留记录更新为黄金记录，其余参与合并的记录删除。'
            : '提交后立即生效：保留记录更新为黄金记录，其余参与合并的记录删除。'
        "
        description="点击单元格可改变该字段取值来源；主键始终取保留记录。与保留记录不同的值以底色标出。"
      />

      <div class="match-detail__scroll">
        <table class="match-detail__matrix">
          <thead>
            <tr>
              <th>字段</th>
              <th
                v-for="(member, index) in group.members"
                :key="member.key"
                :class="{ 'is-survivor': draft.survivor === index }"
              >
                <div class="match-detail__member">
                  <span>记录 {{ index + 1 }} · {{ keyLabel(member.key) }}</span>
                  <Tag v-if="member.exists === false" color="default">
                    已不存在
                  </Tag>
                </div>
                <div v-if="editable && member.exists !== false">
                  <Radio
                    :checked="draft.survivor === index"
                    @change="chooseSurvivor(draft, index, columns)"
                  >
                    保留
                  </Radio>
                  <Checkbox
                    :checked="draft.participating[index]"
                    :disabled="draft.survivor === index"
                    @change="
                      (event) =>
                        toggleParticipant(draft!, index, event.target.checked)
                    "
                  >
                    参与合并
                  </Checkbox>
                </div>
              </th>
              <th class="is-golden">黄金记录</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="column in columns" :key="column.name">
              <th>
                {{ column.commentStr || column.name }}
                <Tag v-if="column.pk === 1" color="blue">主键</Tag>
              </th>
              <td
                v-for="(member, index) in group.members"
                :key="member.key"
                :class="{
                  'is-picked': sourceOf(column.name!) === index,
                  'is-diff': differs(column.name!, index),
                  'is-off': !draft.participating[index],
                  'is-clickable':
                    editable && column.pk !== 1 && draft.participating[index],
                }"
                @click="pick(column.name!, index)"
              >
                {{ displayValue(member.row[column.name!]) }}
              </td>
              <td class="is-golden">
                {{ displayValue(golden[column.name!]) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="ignoring" class="match-detail__ignore">
        <TextArea
          v-model:value="remark"
          :maxlength="500"
          :rows="2"
          placeholder="说明为什么不是重复（可选）"
        />
      </div>
      <div class="match-detail__footer">
        <span v-if="editable && issue" class="match-detail__issue">
          {{ issue }}
        </span>
        <Button @click="open = false">关闭</Button>
        <template v-if="group.status === 'PENDING'">
          <Button
            v-if="canIgnore && !ignoring"
            :disabled="submitting"
            @click="ignoring = true"
          >
            不是重复
          </Button>
          <Button
            v-if="canIgnore && ignoring"
            danger
            :loading="submitting"
            @click="ignore"
          >
            确认忽略
          </Button>
          <Button
            v-if="editable"
            type="primary"
            :loading="submitting"
            :disabled="!!issue"
            @click="submit"
          >
            {{ model.approvalEnabled ? '提交合并审批' : '确认合并' }}
          </Button>
        </template>
      </div>
    </template>
  </Modal>
</template>

<style scoped>
.match-detail__head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}

.match-detail__muted {
  font-size: 12px;
  color: var(--ant-color-text-tertiary, #8c8c8c);
}

.match-detail__pairs {
  padding: 8px 12px;
  margin-bottom: 12px;
  font-size: 13px;
  background: var(--ant-color-fill-quaternary, #fafafa);
  border-radius: 6px;
}

.match-detail__scroll {
  max-height: 56vh;
  overflow: auto;
}

.match-detail__matrix {
  width: 100%;
  border-collapse: collapse;
}

.match-detail__matrix th,
.match-detail__matrix td {
  min-width: 140px;
  padding: 6px 10px;
  text-align: left;
  vertical-align: top;
  border: 1px solid var(--ant-color-border-secondary, #f0f0f0);
}

.match-detail__matrix thead th {
  position: sticky;
  top: 0;
  z-index: 1;
  font-weight: 500;
  background: var(--ant-color-bg-container, #fff);
}

.match-detail__matrix tbody th {
  font-weight: 500;
  white-space: nowrap;
}

.match-detail__member {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 4px;
}

.match-detail__matrix .is-survivor {
  box-shadow: inset 0 3px 0 var(--ant-color-primary, #1677ff);
}

.match-detail__matrix td.is-diff {
  background: var(--ant-color-warning-bg, #fffbe6);
}

.match-detail__matrix td.is-picked {
  font-weight: 600;
  outline: 2px solid var(--ant-color-primary, #1677ff);
  outline-offset: -2px;
}

.match-detail__matrix td.is-off {
  color: var(--ant-color-text-quaternary, #bfbfbf);
}

.match-detail__matrix td.is-clickable {
  cursor: pointer;
}

.match-detail__matrix .is-golden {
  background: var(--ant-color-success-bg, #f6ffed);
}

.match-detail__ignore {
  margin-top: 12px;
}

.match-detail__footer {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  margin-top: 16px;
}

.match-detail__issue {
  margin-right: auto;
  color: var(--ant-color-error, #ff4d4f);
}
</style>
