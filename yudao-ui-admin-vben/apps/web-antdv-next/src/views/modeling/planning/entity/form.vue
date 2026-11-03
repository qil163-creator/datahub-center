<script setup lang="ts">
import type { BusinessEntityApi } from '#/api/modeling/business-entity';
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
  Select,
  TextArea,
} from 'antdv-next';

import {
  createBusinessEntity,
  getBusinessEntity,
  getBusinessEntityOptions,
  updateBusinessEntity,
} from '#/api/modeling/business-entity';
import { getSubjectDomainList } from '#/api/modeling/subjectdomain';
import { useObjectRequest } from '#/hooks/use-object-request';

import activityCardIcon from '../reference-assets/entity-activity-card.png';
import objectCardIcon from '../reference-assets/entity-object-card.png';
import { usePlanningOptions } from '../use-planning-options';
import {
  clearEntityRelations,
  ENTITY_SUBTYPES,
  entitySavePayload,
} from './state';

import '../planning.css';
import '../detail.css';

defineOptions({ name: 'BusinessEntityForm' });
const emit = defineEmits<{ success: [] }>();
const access = useAccessStore();
const identity = ref('closed');
const id = ref<BusinessEntityApi.Id>();
const revision = ref<BusinessEntityApi.Id>(0);
const saving = ref(false);
const loading = ref(false);
const loadFailed = ref(false);
const errors = ref<Record<string, string>>({});
const subjects = ref<ModelingSubjectDomainApi.SubjectDomain[]>([]);
const relations = ref<BusinessEntityApi.Options>({
  entities: [],
  unavailableProcessEventIds: [],
  unavailablePredecessorEntityIds: [],
});
const blank = (): BusinessEntityApi.SaveReq => ({
  dataDomainId: '',
  subjectDomainId: '',
  name: '',
  code: '',
  entityType: 'OBJECT',
  entitySubtype: 'NORMAL',
  description: '',
  associatedEntityIds: [],
  predecessorEntityIds: [],
  processEventIds: [],
});
const values = reactive(blank());
const {
  domains,
  userOptions,
  userPhone,
  loading: optionsLoading,
  failed: optionsFailed,
  reload: reloadUsers,
} = usePlanningOptions(true);
const request = useObjectRequest(
  () => `${identity.value}/${access.accessCodes.join(',')}`,
);
const boardName = computed(
  () =>
    domains.value.find(
      (item) => String(item.id) === String(values.dataDomainId),
    )?.name || String(values.dataDomainId),
);
const subjectOptions = computed(() =>
  subjects.value.map((item) => ({ label: item.name, value: String(item.id) })),
);
const subtypeOptions = computed(() =>
  (values.entityType === 'OBJECT'
    ? ['NORMAL', 'HIERARCHICAL']
    : ['EVENT', 'PROCESS']
  ).map((value) => ({
    value,
    label: ENTITY_SUBTYPES[value as BusinessEntityApi.EntitySubtype].label,
  })),
);
const inheritOptions = computed(() =>
  relations.value.entities
    .filter(
      (item) => String(item.subjectDomainId) === String(values.subjectDomainId),
    )
    .map((item) => ({ label: item.name, value: String(item.id) })),
);
const associatedOptions = computed(() =>
  relations.value.entities
    .filter((item) => String(item.id) !== String(values.inheritEntityId))
    .map((item) => ({ label: item.name, value: String(item.id) })),
);
const processOptions = computed(() =>
  relations.value.entities
    .filter((item) => item.entitySubtype === 'PROCESS')
    .map((item) => ({
      label: `${item.name}${relations.value.unavailablePredecessorEntityIds.map(String).includes(String(item.id)) ? '（已被其他流程引用）' : ''}`,
      value: String(item.id),
      disabled: relations.value.unavailablePredecessorEntityIds
        .map(String)
        .includes(String(item.id)),
    })),
);
const eventOptions = computed(() =>
  relations.value.entities
    .filter((item) => item.entitySubtype === 'EVENT')
    .map((item) => ({
      label: `${item.name}${relations.value.unavailableProcessEventIds.map(String).includes(String(item.id)) ? '（已被其他流程包含）' : ''}`,
      value: String(item.id),
      disabled: relations.value.unavailableProcessEventIds
        .map(String)
        .includes(String(item.id)),
    })),
);
function changeType(type: BusinessEntityApi.EntityType) {
  if (id.value !== undefined || values.entityType === type) return;
  values.entityType = type;
  values.entitySubtype = type === 'OBJECT' ? 'NORMAL' : 'EVENT';
  clearEntityRelations(values);
}
function changeSubject() {
  values.inheritEntityId = undefined;
}
function changeInheritance() {
  values.associatedEntityIds = values.associatedEntityIds.filter(
    (item) => String(item) !== String(values.inheritEntityId),
  );
}
function validate() {
  const result: Record<string, string> = {};
  if (!values.dataDomainId) result.dataDomainId = '请选择数据板块';
  if (!values.subjectDomainId) result.subjectDomainId = '请选择所属主题域';
  if (!values.name.trim()) result.name = '请输入实体名称';
  else if (values.name.length > 64) result.name = '实体名称最多64字符';
  if (!values.code.trim()) result.code = '请输入实体英文名称';
  else if (!/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(values.code))
    result.code = '英文名称须以字母开头，仅支持字母、数字和下划线，最多64字符';
  if (values.entitySubtype === 'PROCESS' && values.processEventIds.length < 2)
    result.processEventIds = '请选择至少2个流程事件';
  errors.value = result;
  return Object.keys(result).length === 0;
}
async function submit() {
  if (
    saving.value ||
    loading.value ||
    loadFailed.value ||
    optionsFailed.value ||
    !validate()
  )
    return;
  const current = request.begin();
  saving.value = true;
  try {
    const payload = entitySavePayload(values);
    if (id.value === undefined) await createBusinessEntity(payload);
    else
      await updateBusinessEntity({
        ...payload,
        id: id.value,
        expectedRevision: revision.value,
      });
    if (!current()) return;
    message.success('保存成功');
    emit('success');
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
    const data = modalApi.getData() as
      | undefined
      | {
          dataDomainId?: BusinessEntityApi.Id;
          id?: BusinessEntityApi.Id;
          subjectDomainId?: BusinessEntityApi.Id;
        };
    identity.value = open ? String(data?.id ?? 'new') : 'closed';
    const current = request.begin();
    Object.assign(values, blank());
    id.value = undefined;
    revision.value = 0;
    subjects.value = [];
    relations.value = {
      entities: [],
      unavailablePredecessorEntityIds: [],
      unavailableProcessEventIds: [],
    };
    errors.value = {};
    saving.value = false;
    loading.value = false;
    loadFailed.value = false;
    if (!open) return;
    loading.value = true;
    try {
      if (data?.id === undefined) {
        values.dataDomainId =
          data?.dataDomainId === undefined ? '' : String(data.dataDomainId);
        values.subjectDomainId =
          data?.subjectDomainId === undefined
            ? ''
            : String(data.subjectDomainId);
      } else {
        const item = await getBusinessEntity(data.id);
        if (!current()) return;
        id.value = item.id;
        revision.value = item.revision;
        Object.assign(values, entitySavePayload(item), {
          dataDomainId: String(item.dataDomainId),
          subjectDomainId: String(item.subjectDomainId),
          leaderUserId:
            item.leaderUserId === undefined
              ? undefined
              : String(item.leaderUserId),
          inheritEntityId:
            item.inheritEntityId === undefined
              ? undefined
              : String(item.inheritEntityId),
          associatedEntityIds: (item.associatedEntityIds ?? []).map(String),
          predecessorEntityIds: (item.predecessorEntityIds ?? []).map(String),
          processEventIds: (item.processEventIds ?? []).map(String),
        });
      }
      if (!values.dataDomainId) {
        loadFailed.value = true;
        return;
      }
      const [subjectList, choices] = await Promise.all([
        getSubjectDomainList({ dataDomainId: values.dataDomainId }),
        getBusinessEntityOptions(values.dataDomainId, id.value),
      ]);
      if (!current()) return;
      subjects.value = subjectList;
      relations.value = choices;
    } catch {
      if (current()) loadFailed.value = true;
    } finally {
      if (current()) loading.value = false;
    }
  },
});
watch(
  () =>
    `${access.accessToken}/${access.refreshToken}/${access.accessCodes.join(',')}`,
  () => modalApi.close(),
);
</script>

<template>
  <Modal
    :title="id === undefined ? '新增业务实体' : '修改业务实体'"
    class="planning-modal planning-entity-modal"
    header-class="planning-modal__header"
    :loading="loading"
  >
    <div class="planning-modal__body">
      <div class="planning-form-hint">
        <IconifyIcon
          icon="ant-design:info-circle-filled"
        />业务实体用于描述业务中需要统一管理的对象或活动，例如客户、商品、订单、支付等。通过定义实体类型及实体间的继承、关联关系，可形成统一的业务对象体系。
      </div>
      <div v-if="loadFailed" class="planning-error">
        实体或关联选项加载失败，请关闭后重试。
      </div>
      <div v-if="optionsFailed" class="planning-error">
        人员选项加载失败。<Button type="link" @click="reloadUsers">重试</Button>
      </div>
      <div class="planning-section-title">基础信息</div>
      <Form
        :colon="false"
        :model="values"
        :label-col="{ style: { width: '96px' } }"
        :disabled="loading || saving || loadFailed"
      >
        <FormItem
          label="数据板块"
          required
          :validate-status="errors.dataDomainId ? 'error' : undefined"
          :help="errors.dataDomainId"
        >
          <Input :value="boardName" disabled />
        </FormItem>
        <FormItem
          label="所属主题域"
          required
          :validate-status="errors.subjectDomainId ? 'error' : undefined"
          :help="errors.subjectDomainId"
        >
          <Select
            v-model:value="values.subjectDomainId"
            :options="subjectOptions"
            show-search
            option-filter-prop="label"
            placeholder="请选择所属主题域"
            @change="changeSubject"
          />
        </FormItem>
        <FormItem
          label="实体名称"
          required
          :validate-status="errors.name ? 'error' : undefined"
          :help="errors.name"
        >
          <Input
            v-model:value="values.name"
            :maxlength="64"
            placeholder="请输入实体名称，支持中文、英文字母、数字、下划线和短横线"
          />
        </FormItem>
        <FormItem
          label="实体英文名称"
          required
          :validate-status="errors.code ? 'error' : undefined"
          :help="errors.code"
        >
          <Input
            v-model:value="values.code"
            :maxlength="64"
            placeholder="请输入实体英文名称，仅支持英文字母、数字和下划线"
          />
        </FormItem>
        <FormItem label="实体类型" required>
          <div class="planning-entity-type-cards">
            <button
              type="button"
              :disabled="id !== undefined || saving"
              :class="{ active: values.entityType === 'OBJECT' }"
              @click="changeType('OBJECT')"
            >
              <img :src="objectCardIcon" alt="" /><span><strong>业务对象</strong><small>业务中可独立识别和管理的人、事、物，如客户、商品、门店。</small></span><i v-if="values.entityType === 'OBJECT'"></i>
</button><button
              type="button"
              :disabled="id !== undefined || saving"
              :class="{ active: values.entityType === 'ACTIVITY' }"
              @click="changeType('ACTIVITY')"
            >
              <img :src="activityCardIcon" alt="" /><span><strong>业务活动</strong><small>业务过程中发生的行为或事件，如下单、支付、退款。</small></span><i v-if="values.entityType === 'ACTIVITY'"></i>
            </button>
          </div>
        </FormItem>
        <FormItem
          label="对象类型"
          required
          :extra="ENTITY_SUBTYPES[values.entitySubtype].description"
        >
          <Select
            v-model:value="values.entitySubtype"
            :disabled="id !== undefined"
            :options="subtypeOptions"
            @change="clearEntityRelations(values)"
          />
        </FormItem>
        <FormItem label="负责人">
          <Select
            v-model:value="values.leaderUserId"
            :options="userOptions"
            :loading="optionsLoading"
            allow-clear
            show-search
            option-filter-prop="label"
            placeholder="请选择负责人"
          />
        </FormItem>
        <FormItem label="联系方式">
          <Input
            :value="userPhone(values.leaderUserId)"
            disabled
            placeholder="请输入联系方式"
          />
        </FormItem>
        <FormItem label="描述">
          <TextArea
            v-model:value="values.description"
            :rows="4"
            :maxlength="256"
            show-count
            placeholder="请填写描述，建议说明实体的业务含义、覆盖范围、核心特征及典型使用场景"
          />
        </FormItem>
        <template v-if="values.entitySubtype !== 'HIERARCHICAL'">
          <div class="planning-section-title planning-entity-relations-title">
            关系配置
          </div>
          <FormItem
            v-if="values.entitySubtype === 'NORMAL'"
            label="继承自实体"
            extra="继承父对象的基础属性，同时可增加自身特有属性。"
          >
            <Select
              v-model:value="values.inheritEntityId"
              :options="inheritOptions"
              allow-clear
              show-search
              option-filter-prop="label"
              placeholder="请选择继承自实体"
              @change="changeInheritance"
            />
          </FormItem>
          <FormItem
            label="关联实体"
            :extra="
              values.entityType === 'OBJECT'
                ? '用于描述对象之间的业务关系，例如：客户关联地址、订单关联客户。'
                : '选择与当前业务活动相关的业务对象，用于描述活动涉及的主体或业务对象关系。例如：订单关联客户、商品。'
            "
          >
            <Select
              v-model:value="values.associatedEntityIds"
              :options="associatedOptions"
              mode="multiple"
              :max-tag-count="3"
              :max-count="100"
              option-filter-prop="label"
              placeholder="请选择关联实体"
            />
          </FormItem>
          <template v-if="values.entitySubtype === 'PROCESS'">
            <FormItem
              label="前序业务流程"
              extra="选择当前业务活动所属的业务流程，用于明确该活动在业务流程中的位置。例如：订单创建属于订单处理流程。"
            >
              <Select
                v-model:value="values.predecessorEntityIds"
                :options="processOptions"
                mode="multiple"
                :max-tag-count="3"
                :max-count="100"
                option-filter-prop="label"
                placeholder="请选择前序业务流程"
              />
            </FormItem>
            <FormItem
              label="流程事件"
              required
              :validate-status="errors.processEventIds ? 'error' : undefined"
              :help="errors.processEventIds"
              extra="选择当前业务流程中的相关事件，用于描述该活动触发或产生的业务变化。例如：订单提交、支付完成等。"
            >
              <Select
                v-model:value="values.processEventIds"
                :options="eventOptions"
                mode="multiple"
                :max-tag-count="3"
                :max-count="100"
                option-filter-prop="label"
                placeholder="请选择流程事件（至少2个）"
              />
            </FormItem>
          </template>
        </template>
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
