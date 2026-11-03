<script lang="ts" setup>
import type { QualityBriefApi } from '#/api/governance/quality/brief';
import type { QualityRuleSetApi } from '#/api/governance/quality/ruleset';

import { reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import {
  Button,
  Card,
  Form,
  FormItem,
  Input,
  InputNumber,
  message,
  Select,
  TextArea,
} from 'antdv-next';

import {
  createBriefTemplate,
  getBriefTemplate,
  updateBriefTemplate,
} from '#/api/governance/quality/brief';
import { getRuleSetSimpleList } from '#/api/governance/quality/ruleset';

import {
  clearReactiveObject,
  GOVERNANCE_ANTD_FORM_LABEL_COL,
  GOVERNANCE_ANTD_FORM_WRAPPER_COL,
  GOVERNANCE_FORM_MODAL_CLASS,
} from '../../../common/form-layout';

const emit = defineEmits<{ saved: [] }>();

const formRef = ref();
const editId = ref<number>();

const formData = reactive<QualityBriefApi.Template>({});
const paragraphs = ref<QualityBriefApi.TemplateParagraph[]>([]);
const ruleSets = ref<QualityRuleSetApi.RuleSet[]>([]);

const rules = {
  templateName: [{ required: true, message: '请输入模板名称' }],
};

function briefVar(name: string) {
  return `\${${name}}`;
}

const VARIABLES = [
  [briefVar('totalOrg'), '参评机构总数'],
  [briefVar('passRate'), '总体通过率'],
  [briefVar('paixuOrg'), '达标机构数（≥90%）'],
  [briefVar('lowScoreOrgList'), '低于60%的机构列表'],
  [briefVar('lowScoreOrgCount'), '低于60%的机构数'],
  [briefVar('bottomList'), '排名末位机构'],
  [briefVar('cycleLabel'), '评估周期描述'],
  [briefVar('trend'), '与上期对比趋势'],
];

async function open(id?: number) {
  editId.value = id;
  clearReactiveObject(formData as Record<string, unknown>);
  paragraphs.value = [];
  formData.status = 0;
  formData.assessCycle = 'MONTH';
  formData.assessDim = '按机构';
  modalApi.open();
  ruleSets.value = await getRuleSetSimpleList();
  if (id) {
    const detail = await getBriefTemplate(id);
    Object.assign(formData, detail);
    paragraphs.value = detail.paragraphs ?? [];
  }
}

function addParagraph(type: string) {
  paragraphs.value.push({
    paraType: type,
    sortNo: paragraphs.value.length + 1,
    title: '',
    contentText: '',
    columns:
      type === 'TABLE'
        ? [
            {
              fieldName: 'orgName',
              colTitle: '机构名称',
              dataField: 'orgName',
              sortNo: 1,
            },
            {
              fieldName: 'scoreRate',
              colTitle: '得分率(%)',
              dataField: 'scoreRate',
              sortNo: 2,
            },
            {
              fieldName: 'regionRank',
              colTitle: '排名',
              dataField: 'regionRank',
              sortNo: 3,
            },
          ]
        : [],
  });
}

function removeParagraph(index: number) {
  paragraphs.value.splice(index, 1);
}

function addColumn(paragraph: QualityBriefApi.TemplateParagraph) {
  paragraph.columns = paragraph.columns ?? [];
  paragraph.columns.push({
    fieldName: '',
    colTitle: '',
    dataField: '',
    sortNo: paragraph.columns.length + 1,
  });
}

async function handleOk() {
  await formRef.value?.validate();
  modalApi.lock();
  try {
    const payload = { ...formData, paragraphs: paragraphs.value };
    await (editId.value
      ? updateBriefTemplate({ ...payload, id: editId.value })
      : createBriefTemplate(payload));
    message.success(editId.value ? '修改成功' : '新增成功');
    await modalApi.close();
    emit('saved');
  } finally {
    modalApi.unlock();
  }
}

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    await handleOk();
  },
});

defineExpose({ open });
</script>

<template>
  <Modal
    :class="GOVERNANCE_FORM_MODAL_CLASS"
    :title="editId ? '配置简报模板' : '新增简报模板'"
  >
    <Form
      ref="formRef"
      class="mx-4"
      layout="horizontal"
      :label-col="GOVERNANCE_ANTD_FORM_LABEL_COL"
      :model="formData"
      :rules="rules"
      :wrapper-col="GOVERNANCE_ANTD_FORM_WRAPPER_COL"
    >
      <FormItem label="模板名称" name="templateName">
        <Input
          v-model:value="formData.templateName"
          placeholder="请输入模板名称"
        />
      </FormItem>
      <FormItem label="模板编码" name="templateCode">
        <Input
          v-model:value="formData.templateCode"
          placeholder="请输入模板编码（选填）"
        />
      </FormItem>
      <FormItem label="评估周期" name="assessCycle">
        <Select
          v-model:value="formData.assessCycle"
          :options="[
            { label: '日度', value: 'DAY' },
            { label: '周度', value: 'WEEK' },
            { label: '月度', value: 'MONTH' },
          ]"
        />
      </FormItem>
      <FormItem label="评估维度" name="assessDim">
        <Input v-model:value="formData.assessDim" placeholder="如：按机构" />
      </FormItem>
      <FormItem label="状态" name="status">
        <Select
          v-model:value="formData.status"
          :options="[
            { label: '启用', value: 0 },
            { label: '停用', value: 1 },
          ]"
        />
      </FormItem>
    </Form>

    <div class="mb-2 flex items-center justify-between">
      <span class="font-semibold">段落编排</span>
      <div class="flex gap-2">
        <Button size="small" @click="addParagraph('TEXT')">+ 正文段落</Button>
        <Button size="small" @click="addParagraph('TABLE')">+ 附表段落</Button>
      </div>
    </div>
    <div class="text-muted-foreground mb-2 text-xs">
      可用变量：
      <span v-for="[variable, label] in VARIABLES" :key="variable" class="mr-2">
        <code>{{ variable }}</code>（{{ label }}）
      </span>
    </div>

    <div class="flex max-h-96 flex-col gap-3 overflow-auto">
      <Card
        v-for="(paragraph, index) in paragraphs"
        :key="index"
        :title="`${paragraph.paraType === 'TABLE' ? '附表' : '正文'}段落 ${index + 1}`"
        size="small"
      >
        <template #extra>
          <Button
            danger
            size="small"
            type="link"
            @click="removeParagraph(index)"
          >
            <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
            移除
          </Button>
        </template>
        <div class="mb-2 flex items-center gap-2">
          <span class="w-16 text-right text-xs">标题</span>
          <Input
            v-model:value="paragraph.title"
            placeholder="段落标题"
            size="small"
          />
          <span class="w-16 text-right text-xs">排序</span>
          <InputNumber v-model:value="paragraph.sortNo" :min="0" size="small" />
        </div>
        <div class="mb-2 flex items-center gap-2">
          <span class="w-16 text-right text-xs">规则集</span>
          <Select
            v-model:value="paragraph.ruleSetId"
            :options="
              ruleSets.map((set) => ({ label: set.setName, value: set.id }))
            "
            allow-clear
            class="flex-1"
            placeholder="关联规则集（决定数据来源，可空=全部）"
            size="small"
          />
        </div>
        <template v-if="paragraph.paraType === 'TABLE'">
          <div class="mb-1 flex items-center justify-between">
            <span class="text-xs font-semibold">列配置（数据来源：评分数据）</span>
            <Button size="small" type="link" @click="addColumn(paragraph)">
              + 添加列
            </Button>
          </div>
          <div
            v-for="(column, columnIndex) in paragraph.columns"
            :key="columnIndex"
            class="mb-1 flex items-center gap-2"
          >
            <Input
              v-model:value="column.colTitle"
              placeholder="列标题"
              size="small"
            />
            <Select
              v-model:value="column.dataField"
              :options="[
                { label: '机构名称 orgName', value: 'orgName' },
                { label: '机构编码 orgCode', value: 'orgCode' },
                { label: '得分率 scoreRate', value: 'scoreRate' },
                { label: '达标规则数 passRuleCount', value: 'passRuleCount' },
                { label: '总规则数 totalRuleCount', value: 'totalRuleCount' },
                { label: '排名 regionRank', value: 'regionRank' },
              ]"
              class="w-52"
              placeholder="数据字段"
              size="small"
              @change="(value: any) => (column.fieldName = String(value))"
            />
            <Button
              danger
              size="small"
              type="link"
              @click="paragraph.columns?.splice(columnIndex, 1)"
            >
              <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
              删
            </Button>
          </div>
        </template>
        <template v-else>
          <TextArea
            v-model:value="paragraph.contentText"
            :rows="3"
            placeholder="正文内容模板，支持变量插值，如：本期共 ${totalOrg} 家机构参评，总体通过率 ${passRate}，${trend}。"
          />
        </template>
      </Card>
      <div
        v-if="paragraphs.length === 0"
        class="text-muted-foreground py-6 text-center text-sm"
      >
        暂无段落，点击上方按钮添加正文/附表段落
      </div>
    </div>
  </Modal>
</template>
