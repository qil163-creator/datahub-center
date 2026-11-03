<script setup lang="ts">
import type { ModelingDataDomainApi } from '#/api/modeling/datadomain';

import { computed, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import { Button, Input, message, Switch } from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import { updateDataDomainNamingRules } from '#/api/modeling/datadomain';
import { useObjectRequest } from '#/hooks/use-object-request';

import { namingPreview, RULE_LABELS } from './state';

const props = defineProps<{
  domain: ModelingDataDomainApi.DataDomain;
  subjectAbbreviation?: string;
}>();
const emit = defineEmits<{ refresh: [] }>();
const { hasAccessByCodes } = useAccess();
const drafts = ref<ModelingDataDomainApi.NamingRule[]>([]);
const saving = ref(false);
const open = ref(false);
const guard = useObjectRequest(
  () => `${props.domain.id}/${open.value}`,
  () => {
    saving.value = false;
    drafts.value = [];
  },
);
const rows = computed(() => props.domain.namingRules ?? []);
const [Grid, gridApi] = useVbenVxeGrid<ModelingDataDomainApi.NamingRule>({
  gridOptions: {
    data: [],
    border: false,
    round: false,
    stripe: true,
    showOverflow: true,
    rowConfig: { keyField: 'tableType' },
    columnConfig: { resizable: false },
    pagerConfig: { enabled: false },
    toolbarConfig: {
      custom: true,
      refresh: true,
      zoom: false,
      refreshOptions: {
        queryMethod: () => {
          emit('refresh');
          return Promise.resolve();
        },
      },
    },
    columns: [
      {
        field: 'tableType',
        title: '逻辑表名',
        minWidth: 180,
        formatter: ({
          cellValue,
        }: {
          cellValue: ModelingDataDomainApi.NamingRule['tableType'];
        }) => RULE_LABELS[cellValue],
      },
      { field: 'prefix', title: '表前缀', minWidth: 170 },
      {
        field: 'daySuffix',
        title: '天（T+1）',
        minWidth: 160,
        formatter: ({ cellValue }: { cellValue: string }) => cellValue || '-',
      },
      {
        field: 'hourSuffix',
        title: '小时（T+h）',
        minWidth: 160,
        formatter: ({ cellValue }: { cellValue: string }) => cellValue || '-',
      },
      {
        field: 'minuteSuffix',
        title: '分钟（T+h）',
        minWidth: 160,
        formatter: ({ cellValue }: { cellValue: string }) => cellValue || '-',
      },
      {
        field: 'includeSubjectAbbreviation',
        title: '主题域缩写',
        minWidth: 160,
        slots: { default: 'abbreviation' },
      },
      {
        title: '推荐表名预览',
        minWidth: 380,
        align: 'left',
        slots: { default: 'preview' },
      },
    ],
  },
});
watch(rows, (data) => gridApi.setGridOptions({ data }), { immediate: true });
const [Modal, modalApi] = useVbenModal({
  title: '修改命名规则',
  fullscreenButton: false,
  showConfirmButton: false,
  showCancelButton: false,
  onOpenChange(value) {
    open.value = value;
    drafts.value = value ? rows.value.map((row) => ({ ...row })) : [];
  },
});
const preview = (rule: ModelingDataDomainApi.NamingRule) =>
  namingPreview(rule, props.domain.code, props.subjectAbbreviation);
async function save() {
  if (saving.value || props.domain.id === undefined) return;
  const current = guard.begin();
  saving.value = true;
  try {
    await updateDataDomainNamingRules(props.domain.id, drafts.value);
    if (!current()) return;
    message.success('命名规则保存成功');
    emit('refresh');
    await modalApi.close();
  } finally {
    if (current()) saving.value = false;
  }
}
</script>

<template>
  <div class="planning-rule-section">
    <div class="planning-table planning-rules-list">
<Grid>
      <template #toolbar-actions><Button v-if="hasAccessByCodes(['modeling:data-domain:update'])" class="planning-query" @click="modalApi.open()"><IconifyIcon icon="lucide:square-pen" />修改</Button></template>
      <template #abbreviation="{ row }"><span v-if="row.tableType.startsWith('FACT')" class="planning-status-label" :class="{ disabled: !row.includeSubjectAbbreviation }">{{ row.includeSubjectAbbreviation ? '是' : '否' }}</span><span v-else>-</span></template>
      <template #preview="{ row }"><span :title="preview(row)">{{ preview(row) }}</span></template>
    </Grid>
</div>
    <p class="planning-rule-note">
      用于生成逻辑表的推荐名称。系统将根据表类型、时间粒度及主题域缩写等规则自动拼接表名，生成后可修改；开启主题域缩写后，事实类逻辑表将在表前缀后增加所属主题域缩写。
    </p>
    <Modal
      class="planning-modal planning-naming-modal"
      header-class="planning-modal__header"
    >
      <div class="planning-modal__body planning-rule-scroll">
        <table class="planning-rules">
          <colgroup>
            <col style="width: 104px" />
            <col style="width: 110px" />
            <col style="width: 92px" />
            <col style="width: 92px" />
            <col style="width: 92px" />
            <col style="width: 100px" />
            <col />
          </colgroup>
          <thead>
            <tr>
              <th>逻辑表名</th>
              <th>表前缀</th>
              <th>天（T+1）</th>
              <th>小时（T+h）</th>
              <th>分钟（T+h）</th>
              <th>主题域缩写</th>
              <th>推荐表名预览</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="rule in drafts" :key="rule.tableType">
              <td>{{ RULE_LABELS[rule.tableType] }}</td>
              <td><Input :value="rule.prefix" disabled /></td>
              <td
                v-for="key in [
                  'daySuffix',
                  'hourSuffix',
                  'minuteSuffix',
                ] as const"
                :key="key"
              >
                <Input
                  v-model:value="rule[key]"
                  :disabled="saving || ['ODS', 'ADS'].includes(rule.tableType)"
                  :maxlength="512"
                  placeholder="-"
                />
              </td>
              <td>
                <Switch
                  v-if="rule.tableType.startsWith('FACT')"
                  v-model:checked="rule.includeSubjectAbbreviation"
                  :disabled="saving"
                  size="small"
                /><span v-else>-</span>
              </td>
              <td :title="preview(rule)">{{ preview(rule) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <template #footer>
<div class="planning-modal__footer">
          <Button :disabled="saving" @click="modalApi.close()">取消</Button><Button type="primary" :loading="saving" @click="save">确定</Button>
        </div>
</template>
    </Modal>
  </div>
</template>
