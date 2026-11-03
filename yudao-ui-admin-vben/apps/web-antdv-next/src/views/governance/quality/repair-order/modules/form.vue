<script lang="ts" setup>
import type { QualityCheckObjectApi } from '#/api/governance/quality/checkobject';
import type { QualityRepairOrderApi } from '#/api/governance/quality/repair';

import { onDeactivated, reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import {
  Button,
  Form,
  FormItem,
  Input,
  message,
  Select,
  TextArea,
} from 'antdv-next';

import { getCheckObjectSimpleList } from '#/api/governance/quality/checkobject';
import {
  createRepairOrder,
  previewRepairSql,
} from '#/api/governance/quality/repair';
import { useObjectRequest } from '#/hooks/use-object-request';

import {
  clearReactiveObject,
  GOVERNANCE_ANTD_FORM_LABEL_COL,
  GOVERNANCE_ANTD_FORM_WRAPPER_COL,
  GOVERNANCE_FORM_MODAL_CLASS,
} from '../../../common/form-layout';

const emit = defineEmits<{ saved: [] }>();

const formRef = ref();

const formData = reactive<QualityRepairOrderApi.RepairOrder>({});
const checkObjects = ref<QualityCheckObjectApi.CheckObject[]>([]);
const previewSql = ref('');
const opened = ref(false);
const opening = useObjectRequest(() => '', close);
const preview = useObjectRequest(
  () => `${opened.value}/${JSON.stringify(formData)}`,
  () => {
    previewSql.value = '';
  },
);
const submission = useObjectRequest(() => opened.value);

function close() {
  opening.begin();
  preview.begin();
  submission.begin();
  opened.value = false;
  checkObjects.value = [];
  previewSql.value = '';
  void modalApi.close();
}

const rules = {
  orderTitle: [{ required: true, message: '请输入工单标题' }],
  repairType: [{ required: true, message: '请选择修复类型' }],
  objectId: [{ required: true, message: '请选择检查对象' }],
};

const REPAIR_TYPE_OPTIONS = [
  { label: '填充默认值', value: 'FILL_DEFAULT' },
  { label: '删除异常记录', value: 'DELETE_RECORD' },
  { label: '格式修正', value: 'FORMAT_FIX' },
  { label: '去重清洗', value: 'DEDUP' },
  { label: '标准化映射', value: 'STANDARD_MAP' },
  { label: '自定义SQL', value: 'CUSTOM_SQL' },
];

const CONFIG_PLACEHOLDERS: Record<string, string> = {
  FILL_DEFAULT: '{"defaultValue": "默认值", "includeBlank": true}',
  DELETE_RECORD:
    '{"relation": "AND", "conditions": [{"field": "age", "operator": ">", "value": "150"}]}',
  FORMAT_FIX: '{"find": "查找内容", "replace": "替换为"}',
  DEDUP:
    '{"groupFields": "id_card,name", "pkField": "id", "keepStrategy": "FIRST"}',
  STANDARD_MAP:
    '{"mappings": [{"from": "男性", "to": "男"}, {"from": "M", "to": "男"}]}',
  CUSTOM_SQL: '{"sql": "UPDATE table_name SET column = value WHERE condition"}',
};

async function open(prefill?: Partial<QualityRepairOrderApi.RepairOrder>) {
  close();
  clearReactiveObject(formData as Record<string, unknown>);
  formData.orderTitle = `修复 - ${new Date().toISOString().slice(0, 10)}`;
  Object.assign(formData, prefill);
  previewSql.value = '';
  opened.value = true;
  const current = opening.begin();
  modalApi.unlock();
  modalApi.open();
  const objects = await getCheckObjectSimpleList();
  if (!current()) return;
  checkObjects.value = objects;
  // 从质量报告「发起修复」进入时预填检查对象/表/字段
  if (prefill && !formData.tableName && formData.objectId) {
    handleObjectChange(formData.objectId);
  }
}

function handleObjectChange(value: any) {
  const object = checkObjects.value.find(
    (item) => String(item.id) === String(value),
  );
  formData.tableName = object?.tableName;
}

async function handlePreview() {
  if (!formData.repairType || !formData.tableName) {
    message.warning('请先选择修复类型和检查对象');
    return;
  }
  const current = preview.begin();
  const value = await previewRepairSql({ ...formData });
  if (current()) previewSql.value = value;
}

async function handleOk() {
  const current = submission.begin();
  await formRef.value?.validate();
  if (!current()) return;
  modalApi.lock();
  try {
    await createRepairOrder({ ...formData });
    if (!current()) return;
    message.success('创建成功，待审批通过后可执行');
    await modalApi.close();
    emit('saved');
  } finally {
    if (current()) modalApi.unlock();
  }
}

const [Modal, modalApi] = useVbenModal({
  onOpenChange(isOpen) {
    if (!isOpen && opened.value) close();
  },
  async onConfirm() {
    await handleOk();
  },
});

onDeactivated(close);
defineExpose({ open, close });
</script>

<template>
  <Modal :class="GOVERNANCE_FORM_MODAL_CLASS" title="新增修复工单">
    <Form
      ref="formRef"
      class="mx-4"
      layout="horizontal"
      :label-col="GOVERNANCE_ANTD_FORM_LABEL_COL"
      :model="formData"
      :rules="rules"
      :wrapper-col="GOVERNANCE_ANTD_FORM_WRAPPER_COL"
    >
      <FormItem label="工单标题" name="orderTitle">
        <Input
          v-model:value="formData.orderTitle"
          placeholder="请输入工单标题"
        />
      </FormItem>
      <FormItem label="检查对象" name="objectId">
        <Select
          v-model:value="formData.objectId"
          :options="
            checkObjects.map((obj) => ({
              label: `${obj.objectName}（${obj.tableName}）`,
              value: obj.id,
            }))
          "
          placeholder="请选择检查对象（决定修复目标库表）"
          show-search
          @change="handleObjectChange"
        />
      </FormItem>
      <FormItem label="修复类型" name="repairType">
        <Select
          v-model:value="formData.repairType"
          :options="REPAIR_TYPE_OPTIONS"
          placeholder="请选择修复类型"
        />
      </FormItem>
      <FormItem label="目标字段" name="targetField">
        <Input
          v-model:value="formData.targetField"
          placeholder="修复的目标字段（去重/自定义SQL可留空）"
        />
      </FormItem>
      <FormItem label="修复配置" name="repairConfig">
        <TextArea
          v-model:value="formData.repairConfig"
          :placeholder="
            CONFIG_PLACEHOLDERS[formData.repairType ?? ''] ??
            'JSON 配置，选择修复类型后显示示例'
          "
          :rows="4"
        />
      </FormItem>
      <FormItem :wrapper-col="{ offset: 5, span: 18 }">
        <Button size="small" @click="handlePreview"><template #icon><IconifyIcon icon="lucide:code" /></template>生成SQL预览</Button>
        <div class="text-muted-foreground mt-1 text-xs">
          禁止 DROP/TRUNCATE/ALTER/CREATE 等 DDL 语句
        </div>
        <pre
          v-if="previewSql"
          class="mt-2 max-h-32 overflow-auto whitespace-pre-wrap rounded bg-[hsl(var(--accent))] p-2 text-xs"
          >{{ previewSql }}</pre>
      </FormItem>
      <FormItem label="修复说明" name="repairDesc">
        <TextArea
          v-model:value="formData.repairDesc"
          :rows="2"
          placeholder="请输入修复说明"
        />
      </FormItem>
    </Form>
  </Modal>
</template>
