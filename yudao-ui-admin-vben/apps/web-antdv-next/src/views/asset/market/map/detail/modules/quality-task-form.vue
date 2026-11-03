<script lang="ts" setup>
import type { DataAssetApi } from '#/api/asset/dataasset';
import type { AssetQualityTaskApi } from '#/api/asset/qualitytask';
import type { QualityTaskApi } from '#/api/governance/quality/task';

import { computed, h, onBeforeUnmount, ref, toRaw, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Alert,
  Button,
  Form,
  FormItem,
  Input,
  message,
  Table,
  Tag,
} from 'antdv-next';

import {
  createAssetQualityTask,
  updateAssetQualityTask,
} from '#/api/asset/qualitytask';
import {
  getQualityTaskRuleCatalog,
  suggestQualityTaskRules,
} from '#/api/governance/quality/task';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { createLatestWins, parseResourceId } from '#/utils/resource-id';
import { dimensionLabel } from '#/views/governance/quality/report/report-presentation';
import RuleEditor from '#/views/governance/quality/task/modules/rule-editor.vue';

import '#/views/governance/quality/task/qdata-quality.css';

defineOptions({ name: 'AssetQualityTaskForm' });
const emit = defineEmits<{ success: [] }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const owner = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const allowed = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes(['asset:quality-task:manage']),
);
const catalogAllowed = computed(() =>
  hasAccessByCodes([
    'governance:quality-task:query',
    'governance:quality-task:create',
    'governance:quality-task:update',
  ]),
);
const asset = ref<DataAssetApi.DataAsset>();
const task = ref<AssetQualityTaskApi.QualityTask>();
const taskName = ref('');
const evaluations = ref<QualityTaskApi.Evaluation[]>([]);
const catalog = ref<QualityTaskApi.CatalogRule[]>([]);
const loading = ref(false);
const failed = ref(false);
const saving = ref(false);
const editor = ref<InstanceType<typeof RuleEditor>>();
const guard = createLatestWins();
let opened = false;
const objects = computed<QualityTaskApi.Object[]>(() => {
  const current = asset.value;
  if (!current?.datasourceId || !current.tableName) return [];
  return [
    {
      clientKey: 'asset',
      objectName: current.assetName || current.tableName,
      datasourceConnId: current.datasourceId,
      datasourceConnName: current.datasourceName,
      datasourceType: current.datasourceType,
      tableName: current.tableName,
      tableId: current.metaTableId,
    },
  ];
});
function reset() {
  guard.invalidate();
  editor.value?.close();
  asset.value = undefined;
  task.value = undefined;
  evaluations.value = [];
  catalog.value = [];
  taskName.value = '';
  loading.value = false;
  saving.value = false;
  failed.value = false;
  modalApi.unlock();
}
async function recommend() {
  if (
    !opened ||
    !allowed.value ||
    !catalogAllowed.value ||
    objects.value.length === 0 ||
    loading.value
  )
    return;
  const ticket = guard.next();
  loading.value = true;
  try {
    const value = await suggestQualityTaskRules(objects.value);
    if (!ticket.isCurrent() || !opened) return;
    const keys = new Set(
      evaluations.value.map(
        (row) => `${row.ruleCode}/${row.columnNames.join(',')}`,
      ),
    );
    evaluations.value.push(
      ...value.filter(
        (row) => !keys.has(`${row.ruleCode}/${row.columnNames.join(',')}`),
      ),
    );
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
function saveEvaluation(value: QualityTaskApi.Evaluation, index?: number) {
  if (!opened || !allowed.value) return;
  const row = { ...value, objectKey: 'asset' };
  if (index === undefined) evaluations.value.push(row);
  else evaluations.value.splice(index, 1, row);
}
const [Modal, modalApi] = useVbenModal({
  fullscreenButton: false,
  closeOnClickModal: false,
  async onOpenChange(value) {
    opened = value;
    reset();
    if (!value || !allowed.value) return;
    const data = modalApi.getData() as {
      asset: DataAssetApi.DataAsset;
      task?: AssetQualityTaskApi.QualityTask;
    };
    if (!parseResourceId(data?.asset?.id)) return;
    asset.value = data.asset;
    task.value = data.task;
    taskName.value =
      data.task?.taskName ||
      `${data.asset.assetName || data.asset.tableName}_质量评估`;
    evaluations.value = structuredClone(
      toRaw(data.task?.evaluations ?? []),
    ).map((row) => ({ ...row, objectKey: 'asset' }));
    if (!catalogAllowed.value) return;
    const ticket = guard.next();
    loading.value = true;
    try {
      const result = await getQualityTaskRuleCatalog();
      if (ticket.isCurrent() && opened) catalog.value = result;
    } catch {
      if (ticket.isCurrent()) failed.value = true;
    } finally {
      if (ticket.isCurrent()) loading.value = false;
    }
  },
  async onConfirm() {
    if (!opened || !allowed.value || !asset.value?.id || saving.value) return;
    if (objects.value.length === 0) {
      message.warning('当前资产未挂接可执行的数据连接');
      return;
    }
    if (!taskName.value.trim()) {
      message.warning('请输入任务名称');
      return;
    }
    if (evaluations.value.length === 0) {
      message.warning('请至少添加一条评测规则');
      return;
    }
    const payload: AssetQualityTaskApi.QualityTask = {
      id: task.value?.id,
      assetId: asset.value.id,
      taskName: taskName.value.trim(),
      scheduleType: task.value?.scheduleType ?? 0,
      cronExpression: task.value?.cronExpression,
      enabled: task.value?.enabled ?? 0,
      remark: task.value?.remark,
      rules: [],
      evaluations: structuredClone(toRaw(evaluations.value)).map(
        ({ id: _id, objectKey: _objectKey, ...value }) => value,
      ),
    };
    const ticket = guard.next();
    saving.value = true;
    modalApi.lock();
    try {
      await (payload.id === undefined
        ? createAssetQualityTask(payload)
        : updateAssetQualityTask(payload));
      if (!ticket.isCurrent() || !opened) return;
      message.success('保存成功');
      emit('success');
      await modalApi.close();
    } catch {
      if (ticket.isCurrent() && opened) {
        emit('success');
        if (payload.id === undefined) {
          message.warning('任务定义可能已保存，请从原任务编辑或执行重试。');
          await modalApi.close();
        }
      }
    } finally {
      if (ticket.isCurrent()) {
        saving.value = false;
        modalApi.unlock();
      }
    }
  },
});
watch(
  owner,
  () => {
    opened = false;
    reset();
    modalApi.setState({ isOpen: false });
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  opened = false;
  guard.invalidate();
});
const columns = [
  { title: '编号', key: 'index', width: 70 },
  {
    title: '评测名称',
    dataIndex: 'evaluationName',
    key: 'evaluationName',
    width: 190,
  },
  { title: '评测字段', key: 'fields', width: 150 },
  { title: '稽查规则', key: 'rule', width: 190 },
  {
    title: '规则描述',
    dataIndex: 'description',
    key: 'description',
    ellipsis: false,
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
  { title: '质量维度', key: 'dimension', width: 110 },
  { title: '状态', key: 'status', width: 80 },
  { title: '操作', key: 'actions', width: 180 },
];
</script>

<template>
  <Modal
    :centered="false"
    confirm-text="确定"
    cancel-text="取消"
    class="!top-[60px] !w-[1200px] !max-w-[calc(100vw-32px)] !rounded-[var(--radius)]"
    :title="task ? '修改资产质量任务' : '新增资产质量任务'"
    :confirm-disabled="!allowed || saving"
  >
    <div class="quality-task-editor">
      <RuleEditor
        ref="editor"
        :catalog="catalog"
        :objects="objects"
        :allow-execution="hasAccessByCodes(['governance:quality-task:query'])"
        @save="saveEvaluation"
      />
      <Alert
        v-if="failed"
        type="error"
        message="稽查规则读取失败，请关闭后重试。"
        class="mb-3"
      />
      <Alert
        v-else-if="!catalogAllowed"
        type="info"
        message="当前账号未获质量规则查询权限。"
        class="mb-3"
      />
      <Form class="quality-task-editor__name" :colon="false">
        <FormItem label="任务名称" required>
          <Input v-model:value="taskName" :maxlength="200" />
        </FormItem>
      </Form>
      <div class="quality-task-editor__toolbar">
        <Button
          type="primary"
          :disabled="!catalog.length || !objects.length"
          @click="editor?.open()"
        >
          <template #icon><IconifyIcon icon="lucide:plus" /></template>新增
        </Button>
        <Button
          class="quality-task-editor__recommend"
          :disabled="!catalog.length || !objects.length"
          :loading="loading"
          @click="recommend"
        >
          <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template
          >获取稽查规则
        </Button>
      </div>
      <Table
        :columns="columns"
        :data-source="evaluations"
        :loading="loading"
        :pagination="false"
        :scroll="{ x: 1100, y: 420 }"
        :row-key="
          (row: QualityTaskApi.Evaluation) =>
            `${row.ruleCode}/${row.evaluationName}`
        "
        size="small"
      >
        <template #bodyCell="{ column, record, index }">
          <template v-if="column.key === 'index'">{{ index + 1 }}</template>
          <template v-else-if="column.key === 'fields'">
            {{
              (record as QualityTaskApi.Evaluation).columnNames.join(', ') ||
              '整表'
            }}
          </template>
          <template v-else-if="column.key === 'rule'">
            {{
              catalog.find(
                (item) =>
                  item.ruleCode ===
                  (record as QualityTaskApi.Evaluation).ruleCode,
              )?.ruleName || (record as QualityTaskApi.Evaluation).ruleCode
            }}
          </template>
          <template v-else-if="column.key === 'dimension'">
            <Tag :bordered="false" color="blue">
              {{
                dimensionLabel((record as QualityTaskApi.Evaluation).qualityDim)
              }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'status'">
            {{
              (record as QualityTaskApi.Evaluation).status === 0
                ? '上线'
                : '下线'
            }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <Button
              type="link"
              size="small"
              @click="
                editor?.open(record as QualityTaskApi.Evaluation, index, true)
              "
            >
              查看
            </Button>
            <Button
              type="link"
              size="small"
              @click="editor?.open(record as QualityTaskApi.Evaluation, index)"
            >
              修改
            </Button>
            <Button
              type="link"
              danger
              size="small"
              @click="evaluations.splice(index, 1)"
            >
              删除
            </Button>
          </template>
        </template>
      </Table>
    </div>
  </Modal>
</template>

<style scoped>
.quality-task-editor {
  min-height: 500px;
  padding: 4px 16px;
}

.quality-task-editor__name {
  max-width: 600px;
}

.quality-task-editor__toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.quality-task-editor__recommend {
  color: hsl(var(--primary-foreground));
  background: #dda53f;
  border-color: #dda53f;
}
</style>

<style scoped src="../../../../table-font.css"></style>
