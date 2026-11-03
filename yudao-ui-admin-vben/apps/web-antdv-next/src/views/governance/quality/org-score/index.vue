<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { QualityOrgScoreApi } from '#/api/governance/quality/orgscore';
import type { QualityRuleSetApi } from '#/api/governance/quality/ruleset';

import { onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Alert,
  Form,
  FormItem,
  Input,
  message,
  Modal,
  Progress,
  Select,
} from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  calculateOrgScore,
  getOrgScorePage,
} from '#/api/governance/quality/orgscore';
import { getRuleSetSimpleList } from '#/api/governance/quality/ruleset';

defineOptions({ name: 'GovernanceQualityOrgScore' });

const ruleSets = ref<QualityRuleSetApi.RuleSet[]>([]);
const calcVisible = ref(false);
const calcSaving = ref(false);
const calcForm = reactive<{
  periodEnd?: string;
  periodStart?: string;
  ruleSetId?: number;
}>({});

function handleRefresh() {
  gridApi.query();
}

function openCalculate() {
  calcVisible.value = true;
}

async function handleCalculate() {
  if (!calcForm.ruleSetId || !calcForm.periodStart || !calcForm.periodEnd) {
    message.warning('请选择规则集并填写周期范围');
    return;
  }
  calcSaving.value = true;
  try {
    const count = await calculateOrgScore(
      calcForm.ruleSetId,
      calcForm.periodStart,
      calcForm.periodEnd,
    );
    message.success(`计算完成，生成 ${count} 条评分记录`);
    calcVisible.value = false;
    handleRefresh();
  } finally {
    calcSaving.value = false;
  }
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: [
      {
        fieldName: 'ruleSetId',
        label: '规则集',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: [],
          placeholder: '请选择规则集',
        },
      },
      {
        fieldName: 'orgName',
        label: '机构名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入机构名称' },
      },
    ],
  },
  gridOptions: {
    showOverflow: false,
    columns: [
      { field: 'regionRank', title: '排名', width: 70 },
      { field: 'orgName', title: '机构名称', minWidth: 160, align: 'left' },
      { field: 'orgCode', title: '机构编码', width: 110 },
      { field: 'ruleSetName', title: '规则集', width: 150 },
      {
        field: 'scoreRate',
        title: '得分率',
        width: 180,
        slots: { default: 'scoreRate' },
      },
      {
        field: 'passRuleCount',
        title: '达标规则数/总规则数',
        width: 160,
        slots: { default: 'ruleCount' },
      },
      {
        field: 'periodStart',
        title: '周期',
        width: 160,
        slots: { default: 'period' },
      },
      {
        field: 'createTime',
        title: '计算时间',
        width: 165,
        formatter: 'formatDateTime',
      },
    ],
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getOrgScorePage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<QualityOrgScoreApi.OrgScore>,
});

onMounted(async () => {
  ruleSets.value = await getRuleSetSimpleList();
  await gridApi.formApi.updateSchema([
    {
      fieldName: 'ruleSetId',
      componentProps: {
        allowClear: true,
        options: ruleSets.value.map((set) => ({
          label: set.setName,
          value: set.id,
        })),
        placeholder: '请选择规则集',
      },
    },
  ]);
});
</script>

<template>
  <Page auto-content-height>
    <div class="flex h-full min-h-0 flex-col gap-3">
      <Alert
        message="评分计算依赖于已执行的质量检测结果记录，请确保在选择的日期范围内，该规则集下的检测任务已执行完成。"
        show-icon
        type="info"
      />
      <div class="min-h-0 flex-1 overflow-hidden">
        <Grid table-title="质量评分">
          <template #toolbar-tools>
            <TableAction
              :actions="[
                {
                  label: '计算评分',
                  type: 'primary',
                  icon: ACTION_ICON.ADD,
                  auth: ['governance:quality-org-score:create'],
                  onClick: openCalculate,
                },
              ]"
            />
          </template>
          <template #scoreRate="{ row }">
            <Progress
              :percent="Number(row.scoreRate ?? 0)"
              :status="Number(row.scoreRate ?? 0) < 60 ? 'exception' : 'normal'"
              size="small"
            />
          </template>
          <template #ruleCount="{ row }">
            {{ row.passRuleCount }} / {{ row.totalRuleCount }}
          </template>
          <template #period="{ row }">
            {{ row.periodStart }} ~ {{ row.periodEnd }}
          </template>
        </Grid>
      </div>
    </div>

    <Modal
      v-model:open="calcVisible"
      :confirm-loading="calcSaving"
      :width="800"
      title="计算评分"
      @ok="handleCalculate"
    >
      <Form
        :label-col="{ style: { width: '130px' } }"
        :wrapper-col="{ style: { flex: '1 1 0' } }"
      >
        <FormItem label="规则集" required>
          <Select
            v-model:value="calcForm.ruleSetId"
            :options="
              ruleSets.map((set) => ({ label: set.setName, value: set.id }))
            "
            placeholder="请选择规则集"
          />
        </FormItem>
        <FormItem label="开始周期" required>
          <Input v-model:value="calcForm.periodStart" placeholder="如 202609" />
        </FormItem>
        <FormItem label="结束周期" required>
          <Input v-model:value="calcForm.periodEnd" placeholder="如 202609" />
        </FormItem>
      </Form>
    </Modal>
  </Page>
</template>
