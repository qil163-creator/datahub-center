<script setup lang="ts">
import type { ModelingSubjectDomainApi } from '#/api/modeling/subjectdomain';

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
  TextArea,
} from 'antdv-next';

import {
  createSubjectDomain,
  getSubjectDomain,
  getSubjectDomainList,
  updateSubjectDomain,
} from '#/api/modeling/subjectdomain';
import { useObjectRequest } from '#/hooks/use-object-request';

import defaultIcon from '../../planning/reference-assets/domain-icon.png';
import { parentOptions } from '../../planning/state';
import { usePlanningOptions } from '../../planning/use-planning-options';

import '../../planning/planning.css';

defineOptions({ name: 'ModelingSubjectDomainForm' });
const emit = defineEmits<{ success: [] }>();
const access = useAccessStore();
const identity = ref('closed');
const saving = ref(false);
const loading = ref(false);
const loadFailed = ref(false);
const errors = ref<Record<string, string>>({});
const subjects = ref<ModelingSubjectDomainApi.SubjectDomain[]>([]);
const blank = (): ModelingSubjectDomainApi.SubjectDomain => ({
  id: undefined,
  dataDomainName: undefined,
  name: '',
  code: '',
  abbreviation: '',
  dataDomainId: undefined,
  parentId: undefined,
  leaderUserId: undefined,
  leaderPhone: '',
  status: 1,
  description: '',
});
const values = reactive(blank());
const {
  domains,
  domainOptions,
  userOptions,
  userPhone,
  loading: optionsLoading,
  failed: optionsFailed,
} = usePlanningOptions(true);
const request = useObjectRequest(() => identity.value);
const boardIcon = (id?: number | string) =>
  domains.value.find((item) => String(item.id) === String(id))?.icon ||
  defaultIcon;
const parentChoices = computed(() =>
  parentOptions(subjects.value, values.dataDomainId, values.id),
);
const title = computed(() =>
  values.id === undefined ? '新增主题域' : '修改主题域',
);
const domainChoices = computed(() =>
  values.dataDomainId !== undefined &&
  !domainOptions.value.some(
    (item) => item.value === String(values.dataDomainId),
  )
    ? [
        {
          label: values.dataDomainName ?? String(values.dataDomainId),
          value: String(values.dataDomainId),
        },
        ...domainOptions.value,
      ]
    : domainOptions.value,
);
async function submit() {
  if (saving.value || loading.value || loadFailed.value || optionsFailed.value)
    return;
  const result: Record<string, string> = {};
  if (!values.dataDomainId) result.dataDomainId = '请选择数据板块';
  if (!values.name?.trim()) result.name = '请输入主题域名称';
  if (!/^[A-Za-z0-9_]{1,64}$/.test(values.code ?? ''))
    result.code = '请输入不超过64字符的字母、数字或下划线';
  if (!/^[A-Za-z0-9_]{1,15}$/.test(values.abbreviation ?? ''))
    result.abbreviation = '请输入不超过15字符的字母、数字或下划线';
  if (!values.leaderUserId) result.leaderUserId = '请选择负责人';
  errors.value = result;
  if (Object.keys(result).length > 0) return;
  const current = request.begin();
  saving.value = true;
  try {
    const data = {
      ...values,
      name: values.name?.trim(),
      parentId: values.parentId ?? 0,
    };
    await (values.id === undefined
      ? createSubjectDomain(data)
      : updateSubjectDomain(data));
    if (!current()) return;
    emit('success');
    message.success('保存成功');
    await modalApi.close();
  } finally {
    if (current()) saving.value = false;
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
    Object.assign(values, blank());
    subjects.value = [];
    errors.value = {};
    saving.value = false;
    loading.value = false;
    loadFailed.value = false;
    if (!open) return;
    const data = (modalApi.getData() ??
      {}) as ModelingSubjectDomainApi.SubjectDomain;
    loading.value = true;
    try {
      const [detail, list] = await Promise.all([
        data.id === undefined
          ? Promise.resolve(data)
          : getSubjectDomain(data.id),
        getSubjectDomainList({}),
      ]);
      if (!current()) return;
      subjects.value = list;
      Object.assign(values, detail, {
        dataDomainId:
          detail.dataDomainId === undefined
            ? undefined
            : String(detail.dataDomainId),
        parentId:
          detail.parentId && String(detail.parentId) !== '0'
            ? String(detail.parentId)
            : undefined,
        leaderUserId:
          detail.leaderUserId === undefined
            ? undefined
            : String(detail.leaderUserId),
      });
    } catch {
      if (current()) loadFailed.value = true;
    } finally {
      if (current()) loading.value = false;
    }
  },
});
function changeDomain() {
  values.parentId = undefined;
}
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
    :title="title"
    header-class="planning-modal__header"
    class="planning-modal w-[598px]"
    :loading="loading"
  >
    <div class="planning-modal__body">
      <div class="planning-form-hint">
        <IconifyIcon
          icon="ant-design:info-circle-filled"
        />主题域用于按业务主题组织和管理数据模型，例如客户、商品、交易等。同一主题下的相关维度、事实及汇总模型可统一归类管理，并支持建立多级主题域。
      </div>
      <Form
        :colon="false"
        :model="values"
        :label-col="{ style: { width: '86px' } }"
        :disabled="loading || saving"
      >
        <FormItem
          label="数据板块"
          required
          :validate-status="errors.dataDomainId ? 'error' : undefined"
          :help="errors.dataDomainId"
          extra="当前主题域所属的数据板块，创建后用于限定主题域及其模型的业务归属范围。"
        >
          <Select
            v-model:value="values.dataDomainId"
            :disabled="values.id !== undefined"
            :options="domainChoices"
            :loading="optionsLoading"
            show-search
            option-filter-prop="label"
            placeholder="请选择数据板块"
            @change="changeDomain"
          >
            <template #labelRender="{ label, value }">
              <span class="planning-board-option is-selected"><img :src="boardIcon(value)" alt="" />{{ label }}</span>
            </template>
            <template #optionRender="{ option }">
              <span class="planning-board-option"><img :src="boardIcon(option.value)" alt="" />{{
                  option.label
                }}</span>
            </template>
          </Select>
        </FormItem>
        <FormItem label="上级主题域">
          <Select
            v-model:value="values.parentId"
            :options="parentChoices"
            allow-clear
            show-search
            option-filter-prop="label"
            placeholder="请选择上级主题域"
          />
        </FormItem>
        <FormItem
          label="主题域名称"
          required
          :validate-status="errors.name ? 'error' : undefined"
          :help="errors.name"
        >
          <Input
            v-model:value="values.name"
            :maxlength="64"
            placeholder="请输入主题域名称"
          />
        </FormItem>
        <FormItem
          label="主题域编码"
          required
          :validate-status="errors.code ? 'error' : undefined"
          :help="errors.code"
        >
          <Input
            v-model:value="values.code"
            :maxlength="64"
            placeholder="请输入主题域编码，允许字母、数字或下划线"
          />
        </FormItem>
        <FormItem
          label="英文缩写"
          required
          :validate-status="errors.abbreviation ? 'error' : undefined"
          :help="errors.abbreviation"
        >
          <Input
            v-model:value="values.abbreviation"
            :maxlength="15"
            placeholder="请输入主题域英文缩写"
          />
        </FormItem>
        <FormItem
          label="负责人"
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
            placeholder="请选择负责人"
          />
        </FormItem>
        <FormItem label="联系方式">
          <Input
            v-model:value="values.leaderPhone"
            disabled
            placeholder="请输入联系方式"
          />
        </FormItem>
        <FormItem label="状态" required>
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
            placeholder="请输入主题域描述，建议填写主题域覆盖的业务范围、主要数据内容及与其他主题域的边界。"
          />
        </FormItem>
      </Form>
    </div>
    <template #footer>
      <div class="planning-modal__footer">
        <Button :disabled="saving" @click="modalApi.close()">取消</Button><Button
          type="primary"
          :loading="saving"
          :disabled="loading || loadFailed || optionsFailed"
          @click="submit"
        >
          确认
        </Button>
      </div>
    </template>
  </Modal>
</template>
