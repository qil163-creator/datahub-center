<script setup lang="ts">
import type { FormInstance } from 'antdv-next';

import type { QualityTaskApi } from '#/api/governance/quality/task';

import { computed, onMounted, reactive, ref } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { formatDateTime } from '@vben/utils';

import {
  Button,
  Descriptions,
  DescriptionsItem,
  Form,
  FormItem,
  Input,
  InputNumber,
  message,
  Modal,
  RadioGroup,
  Select,
  Spin,
  TabPane,
  Tabs,
  TextArea,
} from 'antdv-next';

import { getCategoryDataQualityList } from '#/api/governance/categorydataquality';
import {
  createQualityTask,
  getQualityTask,
  getQualityTaskRuleCatalog,
  suggestQualityTaskRules,
  updateQualityTask,
} from '#/api/governance/quality/task';
import { getSimpleUserList } from '#/api/system/user';
import { CronTab } from '#/components/cron-tab';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import {
  dimensionLabel,
  dimensionOptions,
  emptyImage,
  newTask,
  statusOptions,
  strategyOptions,
  taskPayload,
} from '../data';
import ObjectEditor from './object-editor.vue';
import RuleEditor from './rule-editor.vue';
const props = defineProps<{ id?: string; readonly?: boolean }>();
const emit = defineEmits<{ back: []; saved: [] }>();
const { hasAccessByCodes } = useAccess();
const model = ref<QualityTaskApi.Task>(newTask());
const formRef = ref<FormInstance>();
const objectEditor = ref<InstanceType<typeof ObjectEditor>>();
const ruleEditor = ref<InstanceType<typeof RuleEditor>>();
const step = ref(0);
const tab = ref('objects');
const loading = ref(false);
const failed = ref(false);
const saving = ref(false);
const suggesting = ref(false);
const categories = ref<{ label: string; value: string }[]>([]);
const users = ref<{ label: string; value: QualityTaskApi.Id }[]>([]);
const catalog = ref<QualityTaskApi.CatalogRule[]>([]);
const filter = reactive({
  name: '',
  dim: undefined as string | undefined,
  status: undefined as number | undefined,
});
const requests = useObjectRequest(
  () => props.id,
  () => {
    model.value = newTask();
    void load();
  },
);
const suggestRequests = useObjectRequest(
  () => JSON.stringify(model.value.objects),
  () => {
    suggesting.value = false;
  },
);
const saveRequests = useObjectRequest(
  () => props.id,
  () => {
    saving.value = false;
  },
);
const objects = computed(() => model.value.objects ?? []);
const evaluations = computed(() => model.value.evaluations ?? []);
const visibleEvaluations = computed(() =>
  evaluations.value
    .map((row, index) => ({ row, index }))
    .filter(
      ({ row }) =>
        (!filter.name || row.evaluationName.includes(filter.name)) &&
        (!filter.dim || row.qualityDim === filter.dim) &&
        (filter.status === undefined || row.status === filter.status),
    ),
);
function ruleName(row: QualityTaskApi.Evaluation) {
  return (
    catalog.value.find((entry) => entry.ruleCode === row.ruleCode)?.ruleName ??
    row.ruleCode
  );
}
async function load() {
  const current = requests.begin();
  loading.value = true;
  failed.value = false;
  step.value = 0;
  try {
    const [detail, categoryRows, userRows, rules] = await Promise.all([
      props.id ? getQualityTask(props.id) : Promise.resolve(newTask()),
      getCategoryDataQualityList({}),
      getSimpleUserList(),
      getQualityTaskRuleCatalog(),
    ]);
    if (!current()) return;
    model.value = detail;
    model.value.objects ??= [];
    model.value.evaluations ??= [];
    categories.value = categoryRows.map((row) => ({
      label: row.categoryName ?? row.categoryCode ?? String(row.id),
      value: row.categoryCode ?? String(row.id),
    }));
    users.value = userRows
      .filter((row) => row.id !== undefined)
      .map((row) => ({ label: row.nickname, value: row.id! }));
    catalog.value = rules;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
async function next() {
  if (step.value === 0) {
    await formRef.value?.validate();
    if (
      model.value.scheduleType === 'TIMER' &&
      !model.value.cronExpression?.trim()
    ) {
      message.warning('请配置调度周期');
      return;
    }
  }
  if (step.value === 1 && objects.value.length === 0) {
    message.warning('请添加稽查对象');
    return;
  }
  ++step.value;
}
function saveObject(value: QualityTaskApi.Object) {
  const index = objects.value.findIndex(
    (row) => row.clientKey === value.clientKey,
  );
  if (index === -1) objects.value.push(value);
  else objects.value.splice(index, 1, value);
}
function removeObject(value: QualityTaskApi.Object) {
  const references = evaluations.value.filter(
    (row) => row.config.referenceObjectKey === value.clientKey,
  );
  if (references.length > 0) {
    message.warning(
      `该对象被评测规则“${references.map((row) => row.evaluationName).join('、')}”引用，请先删除或调整引用规则。`,
    );
    return;
  }
  Modal.confirm({
    title: '确认删除稽查对象？',
    content: '该对象关联的评测规则也将一并移除。',
    onOk() {
      model.value.objects = objects.value.filter(
        (row) => row.clientKey !== value.clientKey,
      );
      model.value.evaluations = evaluations.value.filter(
        (row) => row.objectKey !== value.clientKey,
      );
    },
  });
}
function saveEvaluation(value: QualityTaskApi.Evaluation, index?: number) {
  if (index === undefined) evaluations.value.push(value);
  else evaluations.value.splice(index, 1, value);
}
async function suggest() {
  const current = suggestRequests.begin();
  suggesting.value = true;
  try {
    const result = await suggestQualityTaskRules(objects.value);
    if (current()) {
      const existing = new Set(
        evaluations.value.map(
          (row) =>
            `${row.objectKey}/${row.ruleCode}/${row.columnNames.join(',')}`,
        ),
      );
      const fresh = result.filter(
        (row) =>
          !existing.has(
            `${row.objectKey}/${row.ruleCode}/${row.columnNames.join(',')}`,
          ),
      );
      evaluations.value.push(...fresh);
      message.success(
        fresh.length > 0
          ? `已获取 ${fresh.length} 条稽查规则`
          : '没有新的适用稽查规则',
      );
    }
  } finally {
    if (current()) suggesting.value = false;
  }
}
async function save() {
  if (saving.value) return;
  if (evaluations.value.length === 0) {
    message.warning('请至少添加一条稽查规则');
    return;
  }
  saving.value = true;
  const current = saveRequests.begin();
  try {
    const payload = taskPayload(model.value);
    await (props.id
      ? updateQualityTask({ ...payload, id: props.id })
      : createQualityTask(payload));
    if (current()) {
      message.success('保存成功');
      emit('saved');
    }
  } finally {
    if (current()) saving.value = false;
  }
}
onMounted(load);
</script>
<template>
  <Spin v-if="loading" class="q-loading" />
  <template v-else-if="failed"><DetailBackButton v-if="readonly" @click="emit('back')" /></template>
  <template v-else>
    <section v-if="readonly" class="q-panel q-detail-heading">
      <div class="q-detail-title">
        <span class="q-id">{{ model.id }}</span>
        <h2>{{ model.taskName }}</h2>
        <span
          class="q-tag"
          :class="{ 'q-tag-success': model.taskStatus === 0 }"
          >{{ model.taskStatus === 0 ? '上线' : '下线' }}</span><DetailBackButton @click="emit('back')" />
      </div>
      <Descriptions bordered size="small" :column="{ xs: 1, sm: 2, xl: 3 }">
        <DescriptionsItem label="所属类目">
          {{
            categories.find((row) => row.value === model.taskCategory)?.label ??
            model.taskCategory ??
            '-'
          }}
</DescriptionsItem><DescriptionsItem label="执行策略">
          {{
            strategyOptions.find((row) => row.value === model.executeStrategy)
              ?.label ?? model.executeStrategy
          }}
</DescriptionsItem><DescriptionsItem label="调度状态">
          {{ model.taskStatus === 0 ? '上线' : '下线' }}
</DescriptionsItem><DescriptionsItem label="描述" :span="3">
          {{ model.description || '-' }}
</DescriptionsItem><DescriptionsItem label="创建人">
          {{ model.creator || '-' }}
</DescriptionsItem><DescriptionsItem label="创建时间">
          {{
            model.createTime ? formatDateTime(model.createTime) : '-'
          }}
</DescriptionsItem><DescriptionsItem label="更新时间">
          {{ model.updateTime ? formatDateTime(model.updateTime) : '-' }}
        </DescriptionsItem>
      </Descriptions>
    </section>
    <section v-else class="q-steps">
      <div
        v-for="(title, index) in ['基础信息', '稽查对象信息', '稽查规则']"
        :key="title"
        :class="{ active: step === index, complete: step > index }"
      >
        <span>{{ index + 1 }}</span>{{ title }}
      </div>
    </section>
    <section class="q-panel q-task-content">
      <Tabs v-if="readonly" v-model:active-key="tab">
        <TabPane key="objects" tab="稽查对象" /><TabPane
          key="rules"
          tab="稽查规则"
        /><TabPane key="details" tab="详细信息" />
      </Tabs>
      <template v-if="!readonly && step === 0">
        <h3 class="q-section">基础信息</h3>
        <Form
          ref="formRef"
          :model="model"
          :label-col="{ style: { width: '150px' } }"
          class="q-task-form"
        >
          <FormItem
            label="所属类目"
            name="taskCategory"
            :rules="[{ required: true, message: '请选择所属类目' }]"
          >
            <Select
              v-model:value="model.taskCategory"
              :options="categories"
              placeholder="请选择所属类目"
            />
          </FormItem>
          <FormItem
            label="任务名称"
            name="taskName"
            :rules="[{ required: true, message: '请输入任务名称' }]"
          >
            <Input
              v-model:value="model.taskName"
              :maxlength="128"
              placeholder="请输入任务名称"
            />
          </FormItem>
          <FormItem label="执行策略">
            <Select
              v-model:value="model.executeStrategy"
              :options="strategyOptions"
            />
          </FormItem>
          <FormItem label="负责人">
            <Select
              v-model:value="model.ownerUserId"
              :options="users"
              show-search
              option-filter-prop="label"
              allow-clear
              placeholder="请选择负责人"
            />
          </FormItem>
          <FormItem label="调度周期">
            <div class="q-schedule">
              <Select
                v-model:value="model.scheduleType"
                :options="[
                  { label: '手动执行', value: 'MANUAL' },
                  { label: '定时执行', value: 'TIMER' },
                ]"
              /><CronTab
                v-if="model.scheduleType === 'TIMER'"
                v-model="model.cronExpression"
                week-mode="quartz"
              />
            </div>
          </FormItem>
          <FormItem label="任务状态">
            <RadioGroup
              v-model:value="model.taskStatus"
              :options="statusOptions"
            />
          </FormItem>
          <FormItem label="描述" class="q-form-full">
            <TextArea
              v-model:value="model.description"
              :rows="3"
              :maxlength="256"
              show-count
              placeholder="请输入描述"
            />
          </FormItem>
        </Form>
        <h3 class="q-section">属性配置</h3>
        <Form
          :model="model"
          :label-col="{ style: { width: '150px' } }"
          class="q-task-form"
        >
          <FormItem label="任务优先级">
            <Select
              v-model:value="model.priority"
              :options="[
                { label: '高', value: 'HIGH' },
                { label: '中', value: 'MID' },
                { label: '低', value: 'LOW' },
              ]"
            />
          </FormItem>
          <FormItem label="Worker 分组">
            <Input v-model:value="model.workerGroup" placeholder="default" />
          </FormItem>
          <FormItem label="失败重试次数">
            <InputNumber
              v-model:value="model.retryCount"
              :min="0"
              :max="10"
              :precision="0"
            />
          </FormItem>
          <FormItem label="失败重试间隔(分钟)">
            <InputNumber
              v-model:value="model.retryInterval"
              :min="0"
              :precision="0"
            />
          </FormItem>
          <FormItem label="延时执行时间(分钟)">
            <InputNumber
              v-model:value="model.delayExecTime"
              :min="0"
              :precision="0"
            />
          </FormItem>
          <FormItem label="机构标识字段">
            <Input v-model:value="model.orgField" placeholder="选填" />
          </FormItem>
          <FormItem label="通用分组字段">
            <Input v-model:value="model.groupField" placeholder="选填" />
          </FormItem>
        </Form>
      </template>
      <template v-if="readonly ? tab === 'objects' : step === 1">
        <h3 v-if="!readonly" class="q-section">稽查对象信息</h3>
        <div class="q-toolbar">
          <Button v-if="!readonly" type="primary" @click="objectEditor?.open()">
            ＋ 新增
          </Button>
        </div>
        <div class="q-table-scroll">
          <table class="q-table quality-task-table">
            <thead>
              <tr>
                <th>序号</th>
                <th>名称</th>
                <th>连接名称</th>
                <th>模式</th>
                <th>表名称</th>
                <th class="quality-task-actions">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in objects" :key="row.clientKey">
                <td>{{ index + 1 }}</td>
                <td>{{ row.objectName }}</td>
                <td>{{ row.datasourceConnName ?? row.datasourceConnId }}</td>
                <td>{{ row.datasourceInstance || '-' }}</td>
                <td>{{ row.tableName }}</td>
                <td class="quality-task-actions">
                  <Button
                    v-if="!readonly"
                    type="link"
                    @click="objectEditor?.open(row)"
                  >
                    <template #icon>
<IconifyIcon icon="lucide:edit" />
</template>
                    修改
</Button><Button type="link" @click="objectEditor?.open(row, true)">
                    <template #icon><IconifyIcon icon="lucide:eye" /></template>
                    详情
</Button><Button
                    v-if="!readonly"
                    type="link"
                    danger
                    @click="removeObject(row)"
                  >
                    <template #icon>
<IconifyIcon icon="lucide:trash-2" />
</template>
                    删除
                  </Button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="!objects.length" class="q-empty">
          <img :src="emptyImage" alt="" />暂无记录
        </div>
      </template>
      <template v-if="readonly ? tab === 'rules' : step === 2">
        <h3 v-if="!readonly" class="q-section">稽查规则信息</h3>
        <div class="q-filter q-filter-inline">
          <label>规则名称<Input
              v-model:value="filter.name"
              placeholder="请输入规则名称"
              allow-clear
/></label><label>质量维度<Select
              v-model:value="filter.dim"
              :options="dimensionOptions"
              allow-clear
              placeholder="请选择质量维度"
/></label><label>状态<Select
              v-model:value="filter.status"
              :options="statusOptions"
              allow-clear
              placeholder="请选择状态"
          /></label>
        </div>
        <div v-if="!readonly" class="q-toolbar">
          <Button type="primary" @click="ruleEditor?.open()">＋ 新增</Button><Button class="q-warning" :loading="suggesting" @click="suggest">
            <template #icon><IconifyIcon icon="lucide:sparkles" /></template>
            获取稽查规则
          </Button>
        </div>
        <div class="q-table-scroll">
          <table class="q-table quality-task-table">
            <thead>
              <tr>
                <th>序号</th>
                <th>评测名称</th>
                <th>评测对象</th>
                <th>评测字段</th>
                <th>稽查规则</th>
                <th>规则描述</th>
                <th>质量维度</th>
                <th>状态</th>
                <th class="quality-task-actions">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="{ row, index } in visibleEvaluations"
                :key="`${row.id ?? index}/${row.objectKey}`"
              >
                <td>{{ index + 1 }}</td>
                <td>{{ row.evaluationName }}</td>
                <td>
                  {{
                    objects.find((object) => object.clientKey === row.objectKey)
                      ?.objectName
                  }}
                </td>
                <td>{{ row.columnNames.join('、') }}</td>
                <td>{{ ruleName(row) }}</td>
                <td>{{ row.description || '-' }}</td>
                <td>
                  <span class="q-tag">{{
                    dimensionLabel(row.qualityDim)
                  }}</span>
                </td>
                <td>{{ row.status === 0 ? '上线' : '下线' }}</td>
                <td class="quality-task-actions">
                  <Button
                    v-if="!readonly"
                    type="link"
                    @click="ruleEditor?.open(row, index)"
                  >
                    <template #icon>
<IconifyIcon icon="lucide:edit" />
</template>
                    修改
</Button><Button
                    type="link"
                    @click="ruleEditor?.open(row, index, true)"
                  >
                    <template #icon><IconifyIcon icon="lucide:eye" /></template>
                    {{ readonly ? '查看' : '详情' }}
</Button><Button
                    v-if="!readonly"
                    type="link"
                    danger
                    @click="evaluations.splice(index, 1)"
                  >
                    <template #icon>
<IconifyIcon icon="lucide:trash-2" />
</template>
                    删除
                  </Button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="!visibleEvaluations.length" class="q-empty">
          <img :src="emptyImage" alt="" />暂无记录
        </div>
      </template>
      <Descriptions
        v-if="readonly && tab === 'details'"
        bordered
        :column="{ xs: 1, sm: 2, xl: 3 }"
        size="small"
      >
        <DescriptionsItem label="任务优先级">
          {{
            { HIGH: '高', MID: '中', LOW: '低' }[model.priority ?? 'MID'] ??
            model.priority
          }}
</DescriptionsItem><DescriptionsItem label="Worker 分组">
          {{ model.workerGroup || 'default' }}
</DescriptionsItem><DescriptionsItem label="负责人">
          {{
            model.ownerUserName ||
            users.find((row) => String(row.value) === String(model.ownerUserId))
              ?.label ||
            '-'
          }}
</DescriptionsItem><DescriptionsItem label="失败重试次数">
          {{ model.retryCount ?? 0 }}
</DescriptionsItem><DescriptionsItem label="失败重试间隔(分钟)">
          {{ model.retryInterval ?? 0 }}
</DescriptionsItem><DescriptionsItem label="延时执行时间(分钟)">
          {{ model.delayExecTime ?? 0 }}
</DescriptionsItem><DescriptionsItem label="调度周期">
          {{ model.cronExpression || '手动执行' }}
</DescriptionsItem><DescriptionsItem label="上次执行时间">
          {{
            model.lastRunTime ? formatDateTime(model.lastRunTime) : '-'
          }}
</DescriptionsItem><DescriptionsItem label="机构/分组字段">
          {{ model.orgField || '-' }} /
          {{ model.groupField || '-' }}
        </DescriptionsItem>
      </Descriptions>
      <div v-if="!readonly" class="q-page-footer">
        <Button class="q-query" @click="emit('back')">返回列表</Button><Button v-if="step > 0" @click="step--">上一步</Button><Button v-if="step < 2" type="primary" @click="next">下一步</Button><Button
          v-else
          type="primary"
          :loading="saving"
          :disabled="
            !hasAccessByCodes([
              props.id
                ? 'governance:quality-task:update'
                : 'governance:quality-task:create',
            ])
          "
          @click="save"
        >
          确定并退出
        </Button>
      </div>
    </section>
    <ObjectEditor ref="objectEditor" @save="saveObject" /><RuleEditor
      ref="ruleEditor"
      :objects="objects"
      :catalog="catalog"
      @save="saveEvaluation"
    />
  </template>
</template>

<style scoped>
.quality-task-table {
  min-width: 1100px;
}

.quality-task-actions {
  position: sticky;
  right: 0;
  z-index: 1;
  width: 280px;
  min-width: 280px;
  white-space: nowrap;
  background: #fff;
}

th.quality-task-actions {
  background: #f1f1f5;
}

tr:nth-child(even) .quality-task-actions {
  background: #fafafb;
}
</style>
