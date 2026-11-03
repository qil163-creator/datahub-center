<script setup lang="ts">
import type { ModelingDataDomainApi } from '#/api/modeling/datadomain';

import { computed, reactive, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import {
  Button,
  Form,
  FormItem,
  Input,
  message,
  Radio,
  RadioGroup,
  Select,
  Switch,
  TextArea,
} from 'antdv-next';

import { uploadFileWithInfo } from '#/api/infra/file';
import {
  createDataDomain,
  getDataDomain,
  updateDataDomain,
} from '#/api/modeling/datadomain';
import { useObjectRequest } from '#/hooks/use-object-request';

import defaultIcon from '../../planning/reference-assets/domain-icon.png';
import {
  defaultNamingRules,
  namingPreview,
  RULE_LABELS,
} from '../../planning/state';
import { usePlanningOptions } from '../../planning/use-planning-options';

import '../../planning/planning.css';

defineOptions({ name: 'ModelingDataDomainForm' });
const emit = defineEmits<{ success: [] }>();
const access = useAccessStore();
const step = ref(0);
const saving = ref(false);
const loading = ref(false);
const uploading = ref(false);
const loadFailed = ref(false);
const identity = ref('closed');
const fileInput = ref<HTMLInputElement>();
const errors = ref<Record<string, string>>({});
const showRuleErrors = ref(false);
const blank = (): ModelingDataDomainApi.SaveReq => ({
  id: undefined,
  name: '',
  code: '',
  leaderUserId: '',
  leaderPhone: '',
  status: 1,
  description: '',
  icon: '',
  businessOwnerIds: [],
  dataOwnerIds: [],
  namingRules: defaultNamingRules(),
});
const values = reactive(blank());
const {
  userOptions,
  userPhone,
  loading: optionsLoading,
  failed: optionsFailed,
  reload,
} = usePlanningOptions();
const request = useObjectRequest(() => identity.value);
const title = computed(() =>
  values.id === undefined ? '新增数据板块' : '修改数据板块',
);
const rules = computed(() => values.namingRules ?? []);
function reset() {
  Object.assign(values, blank());
  step.value = 0;
  showRuleErrors.value = false;
  errors.value = {};
  loading.value = false;
  saving.value = false;
  uploading.value = false;
  loadFailed.value = false;
}
function validate() {
  const result: Record<string, string> = {};
  if (!values.name.trim()) result.name = '请输入板块名称';
  else if (!/^[\w\u4E00-\u9FA5\s.\-()（）@~]+$/.test(values.name))
    result.name = '名称仅允许中文、字母、数字、空格及 . - _ ( ) @ ~';
  if (!values.code.trim()) result.code = '请输入板块英文名';
  else if (!/^[A-Za-z0-9_]{1,64}$/.test(values.code))
    result.code = '请输入不超过64字符的字母、数字或下划线';
  if (!values.leaderUserId) result.leaderUserId = '请选择板块架构师';
  if ((values.businessOwnerIds?.length ?? 0) > 3)
    result.businessOwnerIds = '业务负责人最多选择3人';
  if ((values.dataOwnerIds?.length ?? 0) > 3)
    result.dataOwnerIds = '数据负责人最多选择3人';
  errors.value = result;
  return Object.keys(result).length === 0;
}
function suffixError(
  rule: ModelingDataDomainApi.NamingRule,
  field: 'daySuffix' | 'hourSuffix' | 'minuteSuffix',
) {
  return rule[field].length <= 512 ? '' : '最多512字符';
}
async function submit() {
  if (
    saving.value ||
    loading.value ||
    uploading.value ||
    loadFailed.value ||
    optionsFailed.value
  )
    return;
  if (!validate()) {
    step.value = 0;
    return;
  }
  if (step.value === 0) {
    step.value = 1;
    return;
  }
  showRuleErrors.value = true;
  if (
    rules.value.some(
      (rule) =>
        suffixError(rule, 'daySuffix') ||
        suffixError(rule, 'hourSuffix') ||
        suffixError(rule, 'minuteSuffix'),
    )
  )
    return;
  const current = request.begin();
  saving.value = true;
  try {
    const payload: ModelingDataDomainApi.SaveReq = {
      ...values,
      name: values.name.trim(),
      code: values.code.trim(),
      namingRules: rules.value.map((rule) => ({ ...rule })),
    };
    await (values.id === undefined
      ? createDataDomain(payload)
      : updateDataDomain(payload));
    if (!current()) return;
    emit('success');
    message.success('保存成功');
    await modalApi.close();
  } finally {
    if (current()) saving.value = false;
  }
}
async function uploadIcon(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  if (
    !['image/jpeg', 'image/png'].includes(file.type) ||
    file.size > 5 * 1024 * 1024
  ) {
    message.warning('请上传不超过5MB的 png/jpg/jpeg 图片');
    return;
  }
  const current = request.begin();
  uploading.value = true;
  try {
    const result = await uploadFileWithInfo({
      file,
      directory: 'modeling/data-domain',
    });
    if (current()) values.icon = result.url;
  } finally {
    if (current()) uploading.value = false;
  }
}
const [Modal, modalApi] = useVbenModal({
  fullscreenButton: false,
  showConfirmButton: false,
  showCancelButton: false,
  async onOpenChange(open) {
    identity.value = open
      ? String(
          (modalApi.getData() as undefined | { id?: number | string })?.id ??
            'new',
        )
      : 'closed';
    const current = request.begin();
    reset();
    if (!open) return;
    const data = modalApi.getData() as undefined | { id?: number | string };
    if (data?.id === undefined) return;
    loading.value = true;
    try {
      const detail = await getDataDomain(data.id);
      if (!current()) return;
      Object.assign(values, detail, {
        leaderUserId: String(detail.leaderUserId ?? ''),
        businessOwnerIds: detail.businessOwnerIds?.map(String) ?? [],
        dataOwnerIds: detail.dataOwnerIds?.map(String) ?? [],
        namingRules: detail.namingRules?.length
          ? detail.namingRules.map((rule) => ({ ...rule }))
          : defaultNamingRules(),
      });
    } catch {
      if (current()) loadFailed.value = true;
    } finally {
      if (current()) loading.value = false;
    }
  },
});
watch(
  () => [values.leaderUserId, optionsLoading.value],
  () => {
    if (!optionsLoading.value)
      values.leaderPhone = userPhone(values.leaderUserId);
  },
);
watch(
  () => `${access.accessToken}/${access.refreshToken}`,
  () => modalApi.close(),
);
</script>

<template>
  <Modal
    header-class="planning-modal__header"
    :title="title"
    class="planning-modal"
    :class="[step === 0 ? 'w-[666px]' : 'w-[838px]']"
    :loading="loading"
  >
    <div class="planning-modal__body">
      <div class="planning-steps">
        <div
          class="planning-step"
          :class="{ active: step === 0, done: step === 1 }"
        >
          ① 配置板块定义
        </div>
        <div class="planning-step" :class="{ active: step === 1 }">
          ② 配置命名规则
        </div>
      </div>
      <div v-if="loadFailed" class="planning-error">
        加载数据板块失败，请关闭后重试。
      </div>
      <div v-if="optionsFailed" class="planning-error">
        人员选项加载失败。<Button type="link" @click="reload">重试</Button>
      </div>
      <template v-if="step === 0">
        <div class="planning-section-title">基础信息</div>
        <Form
          :colon="false"
          :model="values"
          :label-col="{ style: { width: '116px' } }"
          :disabled="saving || loading"
        >
          <FormItem
            label="板块名称"
            required
            :validate-status="errors.name ? 'error' : undefined"
            :help="errors.name"
          >
            <Input
              v-model:value="values.name"
              :maxlength="32"
              placeholder="请输入板块名称，仅允许输入中文、字母、数字及部分特殊字符以及空格"
            />
          </FormItem>
          <FormItem
            label="板块英文名"
            required
            :validate-status="errors.code ? 'error' : undefined"
            :help="errors.code"
          >
            <Input
              v-model:value="values.code"
              :maxlength="64"
              placeholder="请输入英文名称，至多64字符，允许字母、数字或下划线"
            />
          </FormItem>
          <FormItem label="图标">
            <button
              type="button"
              class="planning-upload"
              :disabled="uploading || saving"
              aria-label="上传数据板块图标"
              @click="fileInput?.click()"
            >
              <img :src="values.icon || defaultIcon" alt="数据板块图标" />
            </button>
            <input
              ref="fileInput"
              type="file"
              accept="image/png,image/jpeg"
              hidden
              @change="uploadIcon"
            />
            <template #extra>
              请上传大小不超过 <span style="color: #ef7777">5MB</span> 格式为
              <span style="color: #ef7777">png/jpg/jpeg</span>
              的文件<br />用于设置数据板块的展示图标，便于在板块列表、模型及相关关系中快速识别不同板块。<span
                v-if="uploading"
                >上传中…</span>
            </template>
          </FormItem>
          <FormItem
            label="板块架构师"
            required
            :validate-status="errors.leaderUserId ? 'error' : undefined"
            :help="errors.leaderUserId"
          >
            <Select
              v-model:value="values.leaderUserId"
              :options="userOptions"
              :loading="optionsLoading"
              show-search
              option-filter-prop="label"
              placeholder="请选择板块架构师"
            />
          </FormItem>
          <FormItem label="联系方式">
            <Input
              v-model:value="values.leaderPhone"
              disabled
              placeholder="请输入联系方式"
            />
          </FormItem>
          <FormItem
            label="业务负责人"
            :validate-status="errors.businessOwnerIds ? 'error' : undefined"
            :help="errors.businessOwnerIds"
            extra="负责板块数据的稳定性，非必填，最多3人。"
          >
            <Select
              v-model:value="values.businessOwnerIds"
              :options="userOptions"
              :loading="optionsLoading"
              mode="multiple"
              :max-count="3"
              :max-tag-count="3"
              option-filter-prop="label"
              placeholder="请选择业务负责人"
            />
          </FormItem>
          <FormItem
            label="数据负责人"
            :validate-status="errors.dataOwnerIds ? 'error' : undefined"
            :help="errors.dataOwnerIds"
            extra="负责板块数据质量，非必填，最多3人。"
          >
            <Select
              v-model:value="values.dataOwnerIds"
              :options="userOptions"
              :loading="optionsLoading"
              mode="multiple"
              :max-count="3"
              :max-tag-count="3"
              option-filter-prop="label"
              placeholder="请选择数据负责人"
            />
          </FormItem>
          <FormItem label="状态">
            <RadioGroup v-model:value="values.status">
              <Radio :value="1">禁用</Radio><Radio :value="0">启用</Radio>
            </RadioGroup>
          </FormItem>
          <FormItem label="描述">
            <TextArea
              v-model:value="values.description"
              :rows="4"
              :maxlength="256"
              show-count
              placeholder="请输入数据板块的业务边界与说明"
            />
          </FormItem>
        </Form>
      </template>
      <template v-else>
        <div class="planning-form-hint">
          <IconifyIcon
            icon="ant-design:info-circle-filled"
          />命名规则用于生成推荐表名<br /><span
            style="display: block; padding-left: 20px"
            >用于生成逻辑表的推荐名称。系统将根据表类型、时间粒度及主题域缩写等规则自动拼接表名，生成后可修改；开启主题域缩写后，事实类逻辑表将在表前缀后增加所属主题域缩写。</span>
        </div>
        <div class="planning-rule-scroll">
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
              <tr v-for="rule in rules" :key="rule.tableType">
                <td>{{ RULE_LABELS[rule.tableType] }}</td>
                <td><Input v-model:value="rule.prefix" disabled /></td>
                <td>
                  <Input
                    v-model:value="rule.daySuffix"
                    :status="
                      showRuleErrors && suffixError(rule, 'daySuffix')
                        ? 'error'
                        : undefined
                    "
                    :disabled="rule.tableType === 'ODS'"
                    placeholder="-"
                    :maxlength="512"
                  />
                  <div
                    v-if="showRuleErrors && suffixError(rule, 'daySuffix')"
                    class="planning-field-error"
                  >
                    {{ suffixError(rule, 'daySuffix') }}
                  </div>
                </td>
                <td>
                  <Input
                    v-model:value="rule.hourSuffix"
                    :status="
                      showRuleErrors && suffixError(rule, 'hourSuffix')
                        ? 'error'
                        : undefined
                    "
                    :disabled="rule.tableType === 'ODS'"
                    placeholder="-"
                    :maxlength="512"
                  />
                  <div
                    v-if="showRuleErrors && suffixError(rule, 'hourSuffix')"
                    class="planning-field-error"
                  >
                    {{ suffixError(rule, 'hourSuffix') }}
                  </div>
                </td>
                <td>
                  <Input
                    v-model:value="rule.minuteSuffix"
                    :status="
                      showRuleErrors && suffixError(rule, 'minuteSuffix')
                        ? 'error'
                        : undefined
                    "
                    :disabled="rule.tableType === 'ODS'"
                    placeholder="-"
                    :maxlength="512"
                  />
                  <div
                    v-if="showRuleErrors && suffixError(rule, 'minuteSuffix')"
                    class="planning-field-error"
                  >
                    {{ suffixError(rule, 'minuteSuffix') }}
                  </div>
                </td>
                <td>
                  <Switch
                    v-if="rule.tableType.startsWith('FACT')"
                    v-model:checked="rule.includeSubjectAbbreviation"
                    size="small"
                  /><span v-else>-</span>
                </td>
                <td :title="namingPreview(rule, values.code)">
                  {{ namingPreview(rule, values.code) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>
    <template #footer>
      <div class="planning-modal__footer">
        <Button :disabled="saving" @click="modalApi.close()">取消</Button><Button v-if="step === 1" :disabled="saving" @click="step = 0">
          上一步
</Button><Button
          type="primary"
          :loading="saving"
          :disabled="loading || uploading || loadFailed || optionsFailed"
          @click="submit"
        >
          {{ step === 0 ? '下一步' : '确认' }}
        </Button>
      </div>
    </template>
  </Modal>
</template>
