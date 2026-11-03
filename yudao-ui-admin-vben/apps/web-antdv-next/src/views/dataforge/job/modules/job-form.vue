<script setup lang="ts">
import type { JobApi as Api } from '#/api/dataforge/job';

import { computed, reactive, ref, watch } from 'vue';

import {
  Alert,
  Button,
  Input,
  message,
  Modal,
  Radio,
  RadioGroup,
  Select,
  Spin,
  TextArea,
  TreeSelect,
} from 'antdv-next';

import {
  createJob,
  getJob,
  getJobCategories,
  getJobOwners,
  previewJobCron,
  updateJob,
  updateJobStatus,
} from '#/api/dataforge/job';
import { CronTab } from '#/components/cron-tab';
import { useObjectRequest } from '#/hooks/use-object-request';

import { jobCategoryTree, jobSave, jobStrategyHelp, strategyOptions } from '../model';

const props = defineProps<{
  initialJob?: Api.Job;
  jobId?: Api.Id;
  open: boolean;
}>();
const emit = defineEmits<{
  configure: [id: Api.Id];
  success: [job: Api.Job];
  'update:open': [value: boolean];
}>();
const form = reactive<Api.SaveReq>({
  categoryId: '',
  name: '',
  strategy: 'PARALLEL',
  cronExpression: '0 0 0 * * ?',
  description: '',
  flowJson: '{"nodes":[],"edges":[]}',
});
const categories = ref<Api.Category[]>([]);
const owners = ref<Api.Owner[]>([]);
const status = ref(0);
const initialStatus = ref(0);
const locked = computed(() => initialStatus.value === 1 && status.value === 1);
const busy = ref(false);
const loading = ref(false);
const failed = ref(false);
const validation = ref('');
const request = useObjectRequest(
  () => `${props.open}/${props.jobId ?? props.initialJob?.id ?? 'new'}`,
);
const tree = computed(() => jobCategoryTree(categories.value));
const ownerOptions = computed(() =>
  owners.value.map((row) => ({ value: String(row.id), label: row.name })),
);
watch(
  () => [props.open, props.jobId, props.initialJob],
  async () => {
    if (!props.open) return;
    const current = request.begin();
    busy.value = false;
    loading.value = true;
    failed.value = false;
    validation.value = '';
    try {
      const [cats, people, job] = await Promise.all([
        getJobCategories(),
        getJobOwners(),
        props.jobId ? getJob(props.jobId) : Promise.resolve(props.initialJob),
      ]);
      if (!current()) return;
      categories.value = cats;
      owners.value = people;
      Object.assign(
        form,
        {
              id: undefined,
              revision: undefined,
              ownerId: undefined,
              ownerPhone: '',
              categoryId: '',
              name: '',
              strategy: 'PARALLEL',
              cronExpression: '0 0 0 * * ?',
              description: '',
              flowJson: '{"nodes":[],"edges":[]}',
            },
        job ? jobSave(job) : {},
      );
      if (job) {
        form.categoryId = String(job.categoryId);
        form.ownerId =
          job.ownerId === undefined ? undefined : String(job.ownerId);
      }
      status.value = job?.status ?? 0;
      initialStatus.value = status.value;
    } catch {
      if (current()) failed.value = true;
    } finally {
      if (current()) loading.value = false;
    }
  },
  { immediate: true },
);
function ownerChanged(value: unknown) {
  form.ownerId =
    typeof value === 'string' || typeof value === 'number' ? value : undefined;
  form.ownerPhone =
    owners.value.find((item) => String(item.id) === String(value))
      ?.contactNumber ?? '';
}
async function save(configure = false) {
  if (busy.value || locked.value) return;
  if (!form.categoryId || !form.name.trim() || !form.ownerId || (form.id === undefined && !form.cronExpression?.trim())) {
    validation.value = '请填写作业类目、作业名称、责任人和调度周期';
    return;
  }
  const current = request.begin();
  busy.value = true;
  validation.value = '';
  try {
    if (form.cronExpression) await previewJobCron(form.cronExpression);
    if (!current()) return;
    let id = form.id;
    if (id !== undefined && initialStatus.value === 1 && status.value === 0) {
      await updateJobStatus({id, revision: form.revision!, status: 0});
      if (!current()) return;
      const offline = await getJob(id);
      if (!current()) return;
      form.revision = offline.revision;
      initialStatus.value = 0;
    }
    if (id === undefined)
      id = await createJob({ ...form, name: form.name.trim() });
    else await updateJob({ ...form, name: form.name.trim() });
    if (!current()) return;
    let saved = await getJob(id);
    if (!current()) return;
    form.id = saved.id;
    form.revision = saved.revision;
    if (status.value !== saved.status) {
      await updateJobStatus({
        id,
        revision: saved.revision,
        status: status.value,
      });
      saved = await getJob(id);
    }
    if (!current()) return;
    message.success('作业保存成功');
    emit('success', saved);
    emit('update:open', false);
    if (configure) emit('configure', id);
  } finally {
    if (current()) busy.value = false;
  }
}
</script>

<template>
  <Modal
    :open="open"
    :title="jobId || initialJob ? '修改任务配置' : '新增作业'"
    :width="800"
    :style="{ top: '60px' }"
    wrap-class-name="qj-form-modal"
    :mask-closable="false"
    :closable="!busy"
    @cancel="!busy && emit('update:open', false)"
  >
    <Spin :spinning="loading">
      <Alert
        v-if="failed"
        type="error"
        message="作业配置加载失败，请关闭后重试"
        show-icon
      />
      <Alert v-if="validation" type="error" :message="validation" show-icon />
      <form class="qj-form" @submit.prevent="save()">
        <label><span><em>*</em>作业类目</span><TreeSelect
            v-model:value="form.categoryId"
            :tree-data="tree"
            tree-default-expand-all
            placeholder="请选择作业类目"
            aria-label="作业类目"
            :disabled="locked"
        /></label>
        <label><span><em>*</em>作业名称</span><Input
            v-model:value="form.name"
            :maxlength="128"
            placeholder="建议格式：业务域_源到目标_指标名_全量或增量_周期"
            aria-label="作业名称"
            :disabled="locked"
        /></label>
        <label><span><em>*</em>责任人</span><Select
            :value="form.ownerId"
            :options="ownerOptions"
            placeholder="请选择责任人"
            aria-label="责任人"
            :disabled="locked"
            show-search
            option-filter-prop="label"
            @change="ownerChanged"
        /></label>
        <label><span>联系电话</span><Input
            v-model:value="form.ownerPhone"
            :maxlength="32"
            placeholder="请输入联系电话"
            aria-label="联系电话"
            disabled
        /></label>
        <label><span><em>*</em>执行策略</span>
          <div>
            <Select
              v-model:value="form.strategy"
              :options="strategyOptions"
              aria-label="执行策略"
              :disabled="locked"
            /><small class="qj-strategy-help">{{ jobStrategyHelp[form.strategy] }}人工执行与周期调度使用相同策略。</small>
          </div></label>
        <label><span><em>*</em>调度周期</span>
          <div>
            <CronTab
              v-model="form.cronExpression"
              week-mode="quartz"
              aria-label="调度周期"
              :disabled="locked"
            ><template #trigger="{ open: openGenerator, disabled }"><Button :disabled="disabled" @click="openGenerator">配置</Button></template></CronTab><small>定义任务自动触发的时间和频率</small>
          </div></label>
        <label><span><em>*</em>任务状态</span><RadioGroup v-model:value="status" aria-label="任务状态"><Radio :value="0">未上线</Radio><Radio :value="1">已上线</Radio></RadioGroup></label>
        <label><span>描述</span><TextArea
            v-model:value="form.description"
            :maxlength="256"
            :rows="4"
            show-count
            aria-label="描述"
            :disabled="locked"
        /></label>
      </form>
    </Spin>
    <template #footer>
<Button :disabled="busy" @click="emit('update:open', false)">
{{
        jobId || initialJob ? '关闭' : '取消'
      }}
</Button><Button
        :loading="busy"
        :disabled="loading || failed || locked"
        :type="jobId || initialJob ? 'primary' : 'default'"
        @click="save()"
        >
{{ jobId || initialJob ? '保存' : '仅保存' }}
</Button><Button
        v-if="!jobId && !initialJob"
        type="primary"
        :loading="busy"
        :disabled="loading || failed"
        @click="save(true)"
        >
保存并配置流程
</Button>
</template>
  </Modal>
</template>

<style>
.qj-form-modal .ant-modal-container {
  padding: 0;
  border-radius: 2px;
}

.qj-form-modal .ant-modal-header {
  height: 42px;
  padding: 9px 20px;
  margin: 0;
  background: #f8f8f8;
  border-bottom: 1px solid #ebeef5;
}

.qj-form-modal .ant-modal-close {
  inset-inline-end: 12px;
  top: 10px;
  width: 24px;
  height: 24px;
}

.qj-form-modal .ant-modal-body {
  padding: 20px 40px;
}

.qj-form-modal .ant-modal-footer {
  padding: 15px 20px;
  margin: 0;
  border-top: 1px solid #ebeef5;
}

.qj-form {
  display: grid;
  gap: 16px;
  padding: 0;
  font-size: 14px;
}

.qj-form > label {
  display: grid;
  grid-template-columns: 108px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
  min-height: 32px;
}

.qj-form > label:has(small) {
  margin-bottom: 10px;
}

.qj-form > label:has(.qj-strategy-help) {
  margin-bottom: 0;
}

.qj-form > label > div {
  position: relative;
}

.qj-form > label > span:first-child {
  padding-top: 6px;
  text-align: right;
}

.qj-form em {
  margin-right: 4px;
  font-style: normal;
  color: #f56c6c;
}

.qj-form small {
  position: absolute;
  top: 100%;
  display: block;
  margin-top: 0;
  font-size: 12px;
  line-height: 16px;
  color: #999;
}

.qj-form .qj-strategy-help {
  position: static;
}

.qj-form .ant-radio-group {
  padding-top: 4px;
}

.qj-form-modal .ant-input-group-addon {
  padding: 0;
}

.qj-form-modal .ant-input-group-addon .ant-btn {
  height: 30px;
  border: 0;
  border-radius: 0;
}

.qj-form .ant-select {
  width: 100%;
}

@media (width <= 600px) {
  .qj-form-modal .ant-modal-body {
    padding: 20px;
  }

  .qj-form {
    padding-inline: 0;
  }

  .qj-form > label {
    grid-template-columns: 1fr;
    gap: 5px;
  }

  .qj-form > label > span:first-child {
    text-align: left;
  }
}
</style>
