<script lang="ts" setup>
import type { PropType } from 'vue';

import type { WeekMode } from './cron-semantics';
import type { CronItem, CronValue, ShortcutsType } from './types';

import { computed, nextTick, reactive, ref, useAttrs, watch } from 'vue';

import { formatDateTime, getCurrentTimezone } from '@vben/utils';

import {
  Button,
  Input,
  Modal,
  Radio,
  RadioGroup,
  Select,
  TabPane,
  Tabs,
} from 'antdv-next';

import CronNumber from './cron-number.vue';
import { getCronPreview } from './cron-preview';
import {
  createCronData,
  createCronValue,
  normalizeWeekField,
  parseYearInterval,
  sundayShortcut,
  weekExpression,
} from './cron-semantics';

defineOptions({ name: 'Crontab', inheritAttrs: false });
const props = defineProps({
  modelValue: { type: String, default: '* * * * * ?' },
  shortcuts: { type: Array as PropType<ShortcutsType[]>, default: () => [] },
  weekMode: { type: String as PropType<WeekMode>, default: 'quartz' },
  modalClass: { type: String, default: undefined },
  modalWidth: { type: [Number, String], default: 1120 },
});
const emit = defineEmits<{ 'update:modelValue': [value: string] }>();
const attrs = useAttrs();
const disabled = computed(
  () => attrs.disabled === true || attrs.disabled === '',
);
const defaultValue = ref('');
const dialogVisible = ref(false);
const activeTab = ref('second');
const select = ref<null | string>(null);
const sourceError = ref('');
let hydrating = false;
const cronValue = reactive<CronValue>(createCronValue(props.weekMode));
const data = computed(() => createCronData(props.weekMode));
const fields = [
  { key: 'second', label: '秒', unit: '秒', min: 0, max: 59 },
  { key: 'minute', label: '分钟', unit: '分钟', min: 0, max: 59 },
  { key: 'hour', label: '小时', unit: '小时', min: 0, max: 23 },
  { key: 'day', label: '日', unit: '天', min: 1, max: 31 },
  { key: 'month', label: '月', unit: '月', min: 1, max: 12 },
  { key: 'week', label: '周', unit: '天', min: 0, max: 7 },
  { key: 'year', label: '年', unit: '年', min: 1970, max: 2099 },
] as const;

function fieldExpression(item: CronItem) {
  switch (item.type) {
    case '-1': {
      return '';
    }
    case '1': {
      return `${item.range.start ?? ''}-${item.range.end ?? ''}`;
    }
    case '2': {
      return `${item.loop.start ?? ''}/${item.loop.end ?? ''}`;
    }
    case '3': {
      return item.appoint.join(',');
    }
    case '4': {
      return 'L';
    }
    case '5': {
      return '?';
    }
    case '7': {
      return item.appoint[0] || '';
    }
    default: {
      return '*';
    }
  }
}
const expressionFields = computed(() =>
  fields.map((field) => ({
    ...field,
    value:
      field.key === 'week'
        ? weekExpression(cronValue.week)
        : fieldExpression(cronValue[field.key]),
  })),
);
const expression = computed(() =>
  expressionFields.value
    .map((field) => field.value)
    .join(' ')
    .trim(),
);
const preview = computed(() => {
  if (!dialogVisible.value) return { times: [] as Date[], error: '' };
  try {
    if (sourceError.value) throw new Error(sourceError.value);
    const emptyField = expressionFields.value.find((field) => {
      const item = cronValue[field.key];
      return (
        (!field.value && (field.key !== 'year' || item.type !== '-1')) ||
        (item.type === '3' && item.appoint.length === 0) ||
        (item.type === '7' && !item.appoint[0]?.trim())
      );
    });
    if (emptyField) throw new Error(`请填写${emptyField.label}字段。`);
    return {
      times: getCronPreview(
        expression.value,
        props.weekMode,
        undefined,
        getCurrentTimezone(),
      ),
      error: '',
    };
  } catch (error) {
    return {
      times: [] as Date[],
      error: error instanceof Error ? error.message : '请检查 Cron 表达式。',
    };
  }
});
watch(
  () => props.modelValue,
  (value) => {
    defaultValue.value = value;
    if (value !== select.value && select.value !== 'custom')
      select.value = null;
  },
  { immediate: true },
);
watch(
  () => cronValue.week.type,
  (value) => {
    if (!hydrating && value !== '5') cronValue.day.type = '5';
  },
  { flush: 'sync' },
);
watch(
  () => cronValue.day.type,
  (value) => {
    if (!hydrating && value !== '5') cronValue.week.type = '5';
  },
  { flush: 'sync' },
);

function parseField(item: CronItem, value: string, key: keyof CronValue) {
  if (!value && key === 'year') item.type = '-1';
  else if (value === '*') item.type = '0';
  else if (value === '?' && (key === 'day' || key === 'week')) item.type = '5';
  else if (value === 'L' && key === 'day') item.type = '4';
  else if (/^\d+-\d+$/.test(value)) {
    item.type = '1';
    const [start, end] = value.split('-');
    item.range =
      key === 'week'
        ? { start, end }
        : { start: Number(start), end: Number(end) };
  } else if (/^(?:\d+|\*)\/\d+$/.test(value) && key !== 'week') {
    item.type = '2';
    const [start, end] = value.split('/');
    const field = fields.find((field) => field.key === key)!;
    item.loop =
      key === 'year'
        ? parseYearInterval(value)
        : {
            start: start === '*' ? field.min : Number(start),
            end: Number(end),
          };
  } else if (/^\d+#\d+$/.test(value) && key === 'week') {
    item.type = '2';
    const [weekday, occurrence] = value.split('#');
    item.loop = { start: Number(occurrence), end: weekday };
  } else if (/^\d+L$/.test(value) && key === 'week') {
    item.type = '4';
    item.last = value.slice(0, -1);
  } else if (/^\d+\/\d+$/.test(value) && key === 'week') {
    item.type = '6';
    const [start, end] = value.split('/');
    item.loop = { start, end: Number(end) };
  } else if (/^\d+(?:,\d+)*$/.test(value)) {
    item.type = '3';
    item.appoint = value.split(',');
  } else {
    // 复杂表达式保留原文，避免打开后悄悄改变已有调度规则。
    item.type = '7';
    item.appoint = [value];
  }
}
function open() {
  hydrating = true;
  try {
    Object.assign(cronValue, createCronValue(props.weekMode));
    const parts = (props.modelValue || '0 * * * * ?').trim().split(/\s+/);
    sourceError.value =
      parts.length === 6 || parts.length === 7
        ? ''
        : 'Cron 表达式需要包含 6 或 7 个字段，请重置后重新配置。';
    fields.forEach((field, index) => {
      const value = parts[index] || '';
      parseField(
        cronValue[field.key],
        field.key === 'week'
          ? normalizeWeekField(value, props.weekMode)
          : value,
        field.key,
      );
    });
  } finally {
    hydrating = false;
  }
  activeTab.value = 'second';
  dialogVisible.value = true;
}
function reset() {
  sourceError.value = '';
  Object.assign(cronValue, createCronValue(props.weekMode));
  cronValue.second.type = '3';
  cronValue.second.appoint = ['0'];
  activeTab.value = 'second';
}
async function handleShortcut(value: string) {
  select.value = value;
  if (value === 'custom') {
    open();
    await nextTick();
    select.value = null;
  } else {
    defaultValue.value = value;
    emit('update:modelValue', value);
  }
}
function submit() {
  if (preview.value.error) return;
  defaultValue.value = expression.value;
  emit('update:modelValue', expression.value);
  dialogVisible.value = false;
}
</script>

<template>
  <Input
    v-model:value="defaultValue"
    class="input-with-select"
    v-bind="$attrs"
    @update:value="emit('update:modelValue', $event)"
  >
    <template #addonAfter>
      <slot name="trigger" :open="open" :disabled="disabled">
      <Select
        :value="select"
        :disabled="disabled || undefined"
        placeholder="生成器"
        class="w-36"
        :options="[
          { label: '每分钟', value: '0 * * * * ?' },
          { label: '每小时', value: '0 0 * * * ?' },
          { label: '每天零点', value: '0 0 0 * * ?' },
          { label: '每月一号零点', value: '0 0 0 1 * ?' },
          { label: '每月最后一天零点', value: '0 0 0 L * ?' },
          { label: '每周星期日零点', value: sundayShortcut(weekMode) },
          ...shortcuts.map((item) => ({ label: item.text, value: item.value })),
          { label: '自定义', value: 'custom' },
        ]"
        @change="handleShortcut"
      />
      </slot>
    </template>
  </Input>
  <Modal
    v-model:open="dialogVisible"
    :width="modalWidth"
    :root-class="modalClass"
    :style="{ top: '4vh', maxWidth: 'calc(100vw - 32px)' }"
    :styles="{ body: { paddingTop: '20px' } }"
    destroy-on-hidden
    title="Cron表达式生成器"
  >
    <div class="cron-generator">
      <Tabs
        v-model:active-key="activeTab"
        class="cron-tabs"
        :tab-bar-gutter="0"
      >
        <TabPane v-for="field in fields" :key="field.key" :tab="field.label">
          <RadioGroup
            v-model:value="cronValue[field.key].type"
            class="cron-rules"
          >
            <div v-if="field.key === 'year'" class="cron-rule">
              <Radio value="-1">不指定年份</Radio>
            </div>
            <div class="cron-rule">
              <Radio value="0">
                {{ field.label }}，允许的通配符 [ , - * / ]
              </Radio>
            </div>
            <div
              v-if="field.key === 'day' || field.key === 'week'"
              class="cron-rule"
            >
              <Radio value="5">不指定</Radio>
            </div>
            <div class="cron-rule">
              <Radio value="1">周期从</Radio>
              <template v-if="field.key === 'week'">
                <Select
                  v-model:value="cronValue.week.range.start"
                  class="cron-week-select"
                  :options="data.week"
                  :disabled="cronValue.week.type !== '1'"
                  aria-label="周范围开始"
                />
                <span>−</span>
                <Select
                  v-model:value="cronValue.week.range.end"
                  class="cron-week-select"
                  :options="data.week"
                  :disabled="cronValue.week.type !== '1'"
                  aria-label="周范围结束"
                />
              </template>
              <template v-else>
                <CronNumber
                  v-model="cronValue[field.key].range.start"
                  :label="`${field.label}范围开始`"
                  :min="field.min"
                  :max="field.max"
                  :disabled="cronValue[field.key].type !== '1'"
                />
                <span>−</span>
                <CronNumber
                  v-model="cronValue[field.key].range.end"
                  :label="`${field.label}范围结束`"
                  :min="field.min"
                  :max="field.max"
                  :disabled="cronValue[field.key].type !== '1'"
                />
                <span>{{ field.unit }}</span>
              </template>
            </div>
            <div v-if="field.key !== 'week'" class="cron-rule">
              <Radio value="2">从</Radio>
              <CronNumber
                v-model="cronValue[field.key].loop.start"
                :label="`${field.label}间隔开始`"
                :min="field.min"
                :max="field.max"
                :disabled="cronValue[field.key].type !== '2'"
              />
              <span>{{ field.key === 'day' ? '号' : field.unit }}开始，每</span>
              <CronNumber
                v-model="cronValue[field.key].loop.end"
                :label="`${field.label}间隔步长`"
                :min="1"
                :max="field.key === 'year' ? 130 : field.max"
                :disabled="cronValue[field.key].type !== '2'"
              />
              <span>{{ field.unit }}执行一次</span>
            </div>
            <template v-else>
              <div class="cron-rule">
                <Radio value="2">每月第</Radio>
                <CronNumber
                  v-model="cronValue.week.loop.start"
                  label="每月第几周"
                  :min="1"
                  :max="5"
                  :disabled="cronValue.week.type !== '2'"
                />
                <span>周的</span>
                <Select
                  v-model:value="cronValue.week.loop.end"
                  class="cron-week-select"
                  :options="data.week"
                  :disabled="cronValue.week.type !== '2'"
                  aria-label="第几周的星期"
                />
                <span>执行一次</span>
              </div>
              <div
                v-if="weekMode === 'spring' || cronValue.week.type === '6'"
                class="cron-rule"
              >
                <Radio value="6">从</Radio>
                <Select
                  v-model:value="cronValue.week.loop.start"
                  class="cron-week-select"
                  :options="data.week"
                  :disabled="cronValue.week.type !== '6'"
                  aria-label="周间隔开始"
                />
                <span>开始，每</span>
                <CronNumber
                  v-model="cronValue.week.loop.end"
                  label="周间隔步长"
                  :min="1"
                  :max="7"
                  :disabled="cronValue.week.type !== '6'"
                />
                <span>天执行一次</span>
              </div>
            </template>
            <div class="cron-rule">
              <Radio value="3">指定</Radio>
              <Select
                v-model:value="cronValue[field.key].appoint"
                class="cron-appoint"
                mode="multiple"
                :disabled="cronValue[field.key].type !== '3'"
                :aria-label="`指定${field.label}`"
                :options="
                  field.key === 'week'
                    ? data.week
                    : data[field.key].map((item) => ({
                        label: item,
                        value: String(item),
                      }))
                "
              />
            </div>
            <div v-if="field.key === 'day'" class="cron-rule">
              <Radio value="4">本月最后一天</Radio>
            </div>
            <div v-if="field.key === 'week'" class="cron-rule">
              <Radio value="4">本月最后一个</Radio>
              <Select
                v-model:value="cronValue.week.last"
                class="cron-week-select"
                :options="data.week"
                :disabled="cronValue.week.type !== '4'"
                aria-label="本月最后一个星期"
              />
            </div>
            <div v-if="cronValue[field.key].type === '7'" class="cron-rule">
              <Radio value="7">表达式</Radio>
              <Input
                v-model:value="cronValue[field.key].appoint[0]"
                class="cron-appoint"
                :aria-label="`${field.label}表达式`"
              />
            </div>
          </RadioGroup>
        </TabPane>
      </Tabs>
      <fieldset class="cron-expression">
        <legend>时间表达式</legend>
        <div class="cron-expression-grid">
          <label v-for="field in expressionFields" :key="field.key">
            <span>{{ field.label }}</span>
            <Input
              :value="field.value"
              :aria-label="`${field.label}字段`"
              readonly
            />
          </label>
          <label class="cron-expression-full">
            <span>Cron 表达式</span>
            <Input :value="expression" aria-label="Cron 表达式" readonly />
          </label>
        </div>
      </fieldset>
      <section class="cron-preview-panel" aria-live="polite">
        <p v-if="preview.error" class="cron-error" role="alert">
          {{ preview.error }}
        </p>
        <slot
          v-else-if="dialogVisible"
          name="preview"
          :expression="expression"
          :compact="true"
        >
          <h3>最近5次运行时间</h3>
          <p class="cron-timezone">
            按 {{ getCurrentTimezone() }} 时区预览，实际执行以调度配置为准。
          </p>
          <ul v-if="preview.times.length" class="cron-next-times">
            <li v-for="time in preview.times" :key="time.getTime()">
              {{ formatDateTime(time) }}
            </li>
          </ul>
          <p v-else>当前表达式没有后续运行时间，请检查日期和年份范围。</p>
        </slot>
      </section>
    </div>
    <template #footer>
      <div class="cron-footer">
        <Button class="cron-reset" @click="reset">重置</Button>
        <Button @click="dialogVisible = false">关闭</Button>
        <Button type="primary" :disabled="!!preview.error" @click="submit">
          确定
        </Button>
      </div>
    </template>
  </Modal>
</template>

<style scoped>
.cron-generator {
  display: flex;
  flex-direction: column;
  gap: 28px;
  color: hsl(var(--foreground));
}

.cron-tabs {
  border: 1px solid hsl(var(--border));
}

.cron-tabs :deep(.ant-tabs-nav) {
  margin: 0;
  background: hsl(var(--muted) / 55%);
}

.cron-tabs :deep(.ant-tabs-tab) {
  min-width: 72px;
  padding: 14px 24px;
  font-size: 15px;
  border-right: 1px solid transparent;
  border-left: 1px solid transparent;
}

.cron-tabs :deep(.ant-tabs-tab-active) {
  background: hsl(var(--background));
  border-color: hsl(var(--border));
}

.cron-tabs :deep(.ant-tabs-ink-bar) {
  display: none;
}

.cron-rules {
  display: flex;
  flex-direction: column;
  gap: 22px;
  min-height: 244px;
  padding: 24px 20px 28px;
  font-size: 14px;
}

.cron-rule {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  min-height: 36px;
  line-height: 1.6;
}

.cron-rule :deep(.ant-radio-wrapper) {
  margin-right: 0;
  font-size: 14px;
}

.cron-rule :deep(.ant-radio-wrapper-checked) {
  color: hsl(var(--primary));
}

:deep(.cron-appoint) {
  width: 360px;
  max-width: 100%;
}

:deep(.cron-week-select) {
  width: 146px;
}

.cron-expression {
  min-width: 0;
  padding: 14px;
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

.cron-expression legend {
  float: none;
  width: auto;
  padding: 0 34px;
  margin: 0 auto;
  font-size: 15px;
  text-align: center;
}

.cron-expression-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(48px, 1fr)) minmax(260px, 6fr);
  gap: 4px;
}

.cron-expression-grid label {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  text-align: center;
}

.cron-expression-grid :deep(.ant-input) {
  padding: 8px 6px;
  text-align: center;
  border-radius: var(--radius);
}

.cron-preview-panel {
  min-height: 202px;
  padding: 22px 16px;
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

.cron-preview-panel h3 {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 500;
}

.cron-timezone {
  margin: 0 0 10px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.cron-next-times {
  padding-left: 24px;
  margin: 0;
  font-variant-numeric: tabular-nums;
  line-height: 2;
  list-style: disc;
}

.cron-error {
  color: hsl(var(--destructive));
}

.cron-footer {
  display: flex;
  gap: 12px;
  justify-content: center;
  padding-top: 12px;
}

.cron-footer :deep(.ant-btn) {
  min-width: 72px;
}

.cron-reset {
  color: #d97706;
  background: #fffbeb;
  border-color: #fcd34d;
}

@media (max-width: 760px) {
  .cron-rules {
    gap: 16px;
    padding: 20px 12px;
  }

  .cron-tabs :deep(.ant-tabs-tab) {
    min-width: 48px;
    padding: 12px 16px;
  }

  .cron-expression-grid {
    grid-template-columns: repeat(7, minmax(0, 1fr));
  }

  .cron-expression-full {
    grid-column: 1 / -1;
    margin-top: 8px;
  }
}
</style>
