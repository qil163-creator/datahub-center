<script setup lang="ts">
import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';
import type { GovernanceMasterManageApi } from '#/api/governance/mastermanage';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import { Alert, Button, Input, message, Select } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  getMasterModel,
  getMasterModelSimpleList,
} from '#/api/governance/masterdata';
import {
  createMasterCodeRule,
  getMasterCodeRule,
  previewMasterCodeRule,
  updateMasterCodeRule,
} from '#/api/governance/mastermanage';

import { useMasterScope } from '../../mastercommon/use-master-scope';
import {
  CODE_SEGMENT_TYPE_OPTIONS as typeOptions,
  useFormSchema,
} from '../data';

const emit = defineEmits<{ success: [] }>();
type SegmentRow = GovernanceMasterManageApi.Segment & { rowKey: string };
const editId = ref<GovernanceMasterManageApi.Id>();
const models = ref<{ label: string; value: number | string }[]>([]);
const fields = ref<GovernanceMasterDataApi.Column[]>([]);
const segments = ref<SegmentRow[]>([]);
const example = ref('');
const previewFailed = ref(false);
const loadFailed = ref(false);
const shown = ref(false);
let nextKey = 0;
let timer: ReturnType<typeof setTimeout> | undefined;
const scope = useMasterScope(() => {
  void drawerApi.close();
});
const title = computed(() =>
  editId.value === undefined ? '新增编码规则' : '修改编码规则',
);
const dateOptions = ['yyyyMMdd', 'yyyyMM', 'yyyy', 'yyMMdd'].map((value) => ({
  label: value,
  value,
}));
const lengthOptions = Array.from({ length: 10 }, (_, index) => ({
  label: `${index + 1}位`,
  value: String(index + 1),
}));
const fieldOptions = computed(() =>
  fields.value.map((row) => ({
    label: `${row.commentStr || row.name}（${row.name}）`,
    value: row.name,
  })),
);
const [Form, formApi] = useVbenForm({
  commonConfig: {
    componentProps: { class: 'w-full' },
    formItemClass: 'col-span-1',
  },
  layout: 'vertical',
  wrapperClass: 'grid-cols-1 sm:grid-cols-2',
  showDefaultActions: false,
  schema: useFormSchema(
    () => models.value,
    () => fields.value,
  ),
  handleValuesChange: async (values, changed) => {
    if (!shown.value) return;
    if (changed.includes('modelId')) {
      fields.value = [];
      await loadFields(values.modelId);
    }
    queuePreview();
  },
});
async function loadFields(id?: GovernanceMasterDataApi.Id) {
  const current = scope.begin('fields');
  if (id === undefined) return;
  const model = await getMasterModel(id);
  if (current()) fields.value = model.columns ?? [];
}
function addSegment(value?: GovernanceMasterManageApi.Segment): SegmentRow {
  return {
    segmentType: 'CONSTANT',
    segmentValue: '',
    sepChar: '',
    ...value,
    rowKey: `segment-${++nextKey}`,
  };
}
function changeType(row: SegmentRow) {
  row.segmentValue = '';
  if (row.segmentType === 'DATE') row.segmentValue = 'yyyyMMdd';
  if (row.segmentType === 'SEQUENCE') row.segmentValue = '4';
}
function move(index: number, offset: number) {
  const target = index + offset;
  if (target < 0 || target >= segments.value.length) return;
  const [row] = segments.value.splice(index, 1);
  segments.value.splice(target, 0, row!);
}
async function payload() {
  return {
    ...(await formApi.getValues()),
    id: editId.value,
    segments: segments.value.map(({ rowKey: _key, ...row }) => row),
  } as GovernanceMasterManageApi.CodeRule;
}
function queuePreview() {
  if (timer) clearTimeout(timer);
  scope.begin('preview');
  if (!shown.value) return;
  timer = setTimeout(() => {
    void preview();
  }, 350);
}
async function preview() {
  if (!shown.value) return;
  const current = scope.begin('preview');
  const data = await payload();
  if (!current()) return;
  if (
    !data.modelId ||
    !data.code ||
    !data.name ||
    !data.fieldName ||
    data.segments.length === 0 ||
    data.segments.some((row) => !row.segmentValue)
  ) {
    if (current()) example.value = '';
    return;
  }
  try {
    const result = await previewMasterCodeRule(data);
    if (current()) {
      example.value = result;
      previewFailed.value = false;
    }
  } catch {
    if (current()) {
      example.value = '';
      previewFailed.value = true;
    }
  }
}
watch(segments, queuePreview, { deep: true });
onBeforeUnmount(() => {
  if (timer) clearTimeout(timer);
});
const [Drawer, drawerApi] = useVbenDrawer({
  placement: 'right',
  confirmText: '保存',
  cancelText: '关闭',
  async onOpenChange(open) {
    shown.value = false;
    scope.invalidate();
    const current = scope.begin('detail');
    if (timer) clearTimeout(timer);
    drawerApi.unlock();
    await formApi.reset();
    if (!current()) return;
    editId.value = undefined;
    fields.value = [];
    models.value = [];
    segments.value = [];
    example.value = '';
    loadFailed.value = false;
    previewFailed.value = false;
    if (!open) return;
    const data = drawerApi.getData() as { id?: GovernanceMasterManageApi.Id };
    drawerApi.lock();
    try {
      const [modelList, rule] = await Promise.all([
        getMasterModelSimpleList(),
        data?.id === undefined
          ? Promise.resolve(undefined)
          : getMasterCodeRule(data.id),
      ]);
      if (!current()) return;
      models.value = modelList.map((row) => ({
        label: row.tableCn || row.tableName || String(row.id),
        value: row.id!,
      }));
      editId.value = rule?.id;
      segments.value = (
        rule?.segments ?? [
          { segmentType: 'CONSTANT', segmentValue: '', sepChar: '-' },
          { segmentType: 'SEQUENCE', segmentValue: '4', sepChar: '' },
        ]
      ).map((segment) => addSegment(segment));
      await formApi.setValues(rule ?? { status: 1 });
      if (!current()) return;
      if (rule) await loadFields(rule.modelId);
      if (!current()) return;
      shown.value = true;
      queuePreview();
    } catch {
      if (current()) loadFailed.value = true;
    } finally {
      if (current()) drawerApi.unlock();
    }
  },
  async onConfirm() {
    if (!shown.value || loadFailed.value) return;
    const current = scope.begin('save');
    const validation = await formApi.validate();
    if (!validation.valid || !current()) return;
    const data = await payload();
    if (!current()) return;
    if (
      data.segments.filter((row) => row.segmentType === 'SEQUENCE').length !== 1
    ) {
      message.warning('编码规则必须包含且只能包含一段流水号');
      return;
    }
    if (data.segments.some((row) => !row.segmentValue.trim())) {
      message.warning('请填写每个编码段的内容');
      return;
    }
    drawerApi.lock();
    try {
      await (editId.value === undefined
        ? createMasterCodeRule(data)
        : updateMasterCodeRule(data));
      if (!current()) return;
      message.success('编码规则已保存');
      emit('success');
      await drawerApi.close();
    } finally {
      if (current()) drawerApi.unlock();
    }
  },
});
</script>

<template>
  <Drawer :title="title" class="w-[680px] max-w-full" content-class="p-5">
    <Alert
      v-if="loadFailed"
      type="error"
      message="编码规则加载失败，请重新打开。"
      show-icon
      class="mb-4"
    />
    <Form />
    <div class="bg-muted/40 mb-5 rounded-md border border-dashed p-4">
      <div class="mb-2 flex items-center gap-2 text-sm text-muted-foreground">
        <IconifyIcon icon="lucide:scan-line" class="size-4" />
        编码示例
      </div>
      <div class="break-all font-mono text-lg font-semibold">
        {{ example || '填写规则后自动预览' }}
      </div>
    </div>
    <Alert
      v-if="previewFailed"
      class="mb-3"
      type="warning"
      show-icon
      message="编码预览失败，请检查规则配置。"
    />
    <div class="mb-3 flex items-center gap-2">
      <strong>编码段配置</strong>
      <span
        class="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground"
      >
        {{ segments.length }} 段
      </span>
    </div>
    <div
      v-for="(row, index) in segments"
      :key="row.rowKey"
      class="mb-3 rounded-md border bg-card p-4"
    >
      <div class="mb-3 flex items-center justify-between gap-2">
        <span class="text-sm font-medium">第 {{ index + 1 }} 段</span>
        <div class="flex items-center gap-1">
          <Button
            type="text"
            size="small"
            aria-label="上移编码段"
            :disabled="index === 0"
            @click="move(index, -1)"
          >
            <IconifyIcon icon="lucide:arrow-up" />
          </Button>
          <Button
            type="text"
            size="small"
            aria-label="下移编码段"
            :disabled="index === segments.length - 1"
            @click="move(index, 1)"
          >
            <IconifyIcon icon="lucide:arrow-down" />
          </Button>
          <Button
            type="text"
            size="small"
            danger
            aria-label="删除编码段"
            @click="segments.splice(index, 1)"
          >
            <IconifyIcon icon="lucide:trash-2" />
          </Button>
        </div>
      </div>
      <div
        class="grid grid-cols-1 gap-3 sm:grid-cols-[148px_minmax(0,1fr)_96px]"
      >
        <div class="min-w-0">
          <label :for="`${row.rowKey}-type`" class="mb-2 block text-sm">
            段类型
          </label>
          <Select
            :id="`${row.rowKey}-type`"
            v-model:value="row.segmentType"
            :options="typeOptions"
            class="w-full"
            @change="changeType(row)"
          />
        </div>
        <div class="min-w-0">
          <label :for="`${row.rowKey}-value`" class="mb-2 block text-sm">
            段值
          </label>
          <Select
            v-if="row.segmentType === 'DATE'"
            :id="`${row.rowKey}-value`"
            v-model:value="row.segmentValue"
            :options="dateOptions"
            class="w-full"
          />
          <Select
            v-else-if="row.segmentType === 'SEQUENCE'"
            :id="`${row.rowKey}-value`"
            v-model:value="row.segmentValue"
            :options="lengthOptions"
            class="w-full"
          />
          <Select
            v-else-if="row.segmentType === 'ATTRIBUTE'"
            :id="`${row.rowKey}-value`"
            v-model:value="row.segmentValue"
            :options="fieldOptions"
            class="w-full"
            placeholder="请选择属性"
          />
          <Input
            v-else
            :id="`${row.rowKey}-value`"
            v-model:value="row.segmentValue"
            :maxlength="100"
            class="w-full"
            placeholder="请输入固定值"
          />
        </div>
        <div class="min-w-0">
          <label :for="`${row.rowKey}-separator`" class="mb-2 block text-sm">
            分隔符
          </label>
          <Input
            :id="`${row.rowKey}-separator`"
            v-model:value="row.sepChar"
            :maxlength="8"
            class="w-full"
            placeholder="可选，如 - 或 _"
          />
        </div>
      </div>
    </div>
    <Button
      type="dashed"
      class="w-full"
      :disabled="segments.length >= 12"
      @click="segments.push(addSegment())"
    >
      <template #icon><IconifyIcon icon="lucide:plus" /></template>
      添加编码段
    </Button>
    <p class="mt-3 text-xs text-muted-foreground">
      流水号在新增数据时生成；预览不会占用流水号。启用后，同模型的其他编码规则将停用。
    </p>
  </Drawer>
</template>
