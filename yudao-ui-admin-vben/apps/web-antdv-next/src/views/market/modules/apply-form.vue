<script lang="ts" setup>
import type { MarketApi } from '#/api/asset/market';

import { reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import {
  Button,
  DatePicker,
  Input,
  InputNumber,
  message,
  Select,
  TextArea,
} from 'antdv-next';

import {
  createMarketApply,
  getMarketAvailableApis,
  getMarketAvailableApps,
  resubmitMarketApply,
  submitMarketApply,
  updateMarketApply,
} from '#/api/asset/market';

defineOptions({ name: 'MarketApplyForm' });

const emit = defineEmits<{ (e: 'success'): void }>();

const model = reactive<MarketApi.Apply>({});
const apps = ref<{ label: string; value: MarketApi.Id }[]>([]);
const apis = ref<{ label: string; value: MarketApi.Id }[]>([]);
const savingDraft = ref(false);

function resetModel() {
  Object.assign(model, {
    id: undefined,
    applyNo: undefined,
    title: undefined,
    assetId: undefined,
    assetName: undefined,
    assetCode: undefined,
    assetType: undefined,
    apiId: undefined,
    appId: undefined,
    demandId: undefined,
    demandNo: undefined,
    applyDeptId: undefined,
    applyDeptName: undefined,
    applyUserId: undefined,
    applyUserName: undefined,
    phone: undefined,
    email: undefined,
    purpose: undefined,
    fieldScope: undefined,
    applyNote: undefined,
    applyStartTime: undefined,
    applyEndTime: undefined,
    applyUseType: 0,
    applyUseTimes: undefined,
    status: undefined,
    ifAuth: undefined,
    effective: undefined,
    usable: undefined,
    approvalResult: undefined,
    techProvisionStatus: undefined,
    authStatus: undefined,
    revision: undefined,
    dutyMatter: undefined,
    legalBasis: undefined,
    supplementNote: undefined,
    receivedTime: undefined,
    replyDeadline: undefined,
    deliveryDeadline: undefined,
    deliveryStatus: undefined,
    sourceGrantId: undefined,
    firstUsedTime: undefined,
    paused: undefined,
    checkUserId: undefined,
    checkUserName: undefined,
    checkTime: undefined,
    checkNote: undefined,
    remark: undefined,
    createTime: undefined,
    processes: undefined,
    submit: true,
    datasourceId: undefined,
    tableName: undefined,
    defaultCheckerUserId: undefined,
    defaultCheckerName: undefined,
    ownerReviewer: undefined,
  });
}

const [Modal, modalApi] = useVbenModal({
  title: '申请共享',
  onConfirm: () => submit(true),
  onOpenChange(isOpen) {
    if (!isOpen) {
      resetModel();
      return;
    }
    const data = (modalApi.getData() ?? {}) as MarketApi.Apply;
    resetModel();
    Object.assign(model, {
      applyUseType: 0,
      submit: true,
      title: data.assetName ? `申请使用 ${data.assetName}` : '',
      ...data,
    });
    loadOptions();
  },
});

async function loadOptions() {
  try {
    const [myApps, apiList] = await Promise.all([
      getMarketAvailableApps(),
      model.assetId
        ? getMarketAvailableApis(model.assetId)
        : Promise.resolve([]),
    ]);
    apps.value = (myApps ?? []).map((item) => ({
      label: item.name ?? `应用#${item.id}`,
      value: item.id!,
    }));
    apis.value = (apiList ?? []).map((item) => ({
      label: item.name ?? `API#${item.apiId}`,
      value: item.apiId!,
    }));
  } catch {
    apps.value = [];
    apis.value = [];
  }
}

async function submit(asPending: boolean) {
  if (!model.title?.trim()) {
    message.warning('请填写申请标题');
    return;
  }
  if (!model.assetId) {
    message.warning('缺少资产');
    return;
  }
  if ((model.apiId && !model.appId) || (!model.apiId && model.appId)) {
    message.warning('开通 API 时需要同时选择应用和 API');
    return;
  }
  if (asPending) {
    if (
      model.applyUseType &&
      (!Number.isInteger(model.applyUseTimes) ||
        (model.applyUseTimes ?? 0) <= 0)
    ) {
      message.warning('请填写大于零的整数消费额度');
      return;
    }
    modalApi.lock();
  } else {
    savingDraft.value = true;
  }
  try {
    const payload: MarketApi.Apply = {
      ...model,
      applyUseTimes: model.applyUseType ? model.applyUseTimes : 0,
      submit: asPending,
    };
    if (model.id && (model.status === 0 || model.status === 8)) {
      // 所见版本随编辑提交；编辑成功后服务端版本确定为所见版本 + 1，提交/重提以此核对，
      // 期间另一窗口的修改会返回版本冲突而不是提交未看过的内容。缺少版本的旧数据不带版本。
      const seen = model.revision;
      await updateMarketApply({ ...payload, expectedRevision: seen });
      const edited = seen === undefined || seen === null ? undefined : seen + 1;
      if (asPending && model.status === 0) {
        await submitMarketApply(model.id, edited);
      }
      if (asPending && model.status === 8) {
        await resubmitMarketApply(model.id, edited);
      }
    } else {
      await createMarketApply(payload);
    }
    message.success(asPending ? '已提交申请，请等待审批' : '已保存草稿');
    emit('success');
    modalApi.close();
  } finally {
    if (asPending) {
      modalApi.unlock();
    } else {
      savingDraft.value = false;
    }
  }
}
</script>

<template>
  <Modal class="w-[800px]">
    <div class="governance-modal-form governance-form-item-horizontal">
      <div class="form-item">
        <label class="form-item__label form-item__label--required">申请标题</label>
        <Input v-model:value="model.title" placeholder="请输入申请标题" />
      </div>
      <div class="form-item">
        <label class="form-item__label">履职事项</label>
        <Input
          v-model:value="model.dutyMatter"
          placeholder="本部门履职事项/场景"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label">使用依据</label>
        <Input
          v-model:value="model.legalBasis"
          placeholder="法规、职责或文件依据"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label">使用用途</label>
        <TextArea
          v-model:value="model.purpose"
          :rows="2"
          placeholder="说明申请用途"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label">字段范围</label>
        <Input
          v-model:value="model.fieldScope"
          placeholder="可申请字段中文名或标识，逗号分隔；留空表示该资源全部已批准字段"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label">申请说明</label>
        <TextArea v-model:value="model.applyNote" :rows="2" />
      </div>
      <div class="form-item__row">
        <div class="form-item">
          <label class="form-item__label">开始时间</label>
          <DatePicker
            v-model:value="model.applyStartTime"
            class="w-full"
            format="YYYY-MM-DD HH:mm:ss"
            show-time
            value-format="x"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label">结束时间</label>
          <DatePicker
            v-model:value="model.applyEndTime"
            class="w-full"
            format="YYYY-MM-DD HH:mm:ss"
            show-time
            value-format="x"
          />
        </div>
      </div>
      <div class="form-item__row">
        <div class="form-item">
          <label class="form-item__label">联系电话</label>
          <Input v-model:value="model.phone" />
        </div>
        <div class="form-item">
          <label class="form-item__label">邮箱</label>
          <Input v-model:value="model.email" />
        </div>
      </div>
      <div class="form-item">
        <label class="form-item__label">开通 API</label>
        <div class="flex min-w-0 flex-1 flex-col gap-2">
          <div class="grid grid-cols-2 gap-3">
            <Select
              v-model:value="model.appId"
              allow-clear
              :options="apps"
              placeholder="选择本部门可代表的应用"
            />
            <Select
              v-model:value="model.apiId"
              allow-clear
              :options="apis"
              placeholder="选择已绑定到该资源的 API"
            />
          </div>
          <div class="text-muted-foreground text-xs">
            应用请先在数据服务「应用管理」创建。未绑定 API
            的资源不能申请接口开通。门户与 API 使用同一批准来源的额度。
          </div>
        </div>
      </div>
      <div
        v-if="model.status === 8 && model.supplementNote"
        class="text-xs text-orange-600"
      >
        补正要求：{{ model.supplementNote }}
      </div>
      <div class="form-item">
        <label class="form-item__label">消费额度</label>
        <div class="flex flex-1 flex-col gap-2">
          <Select
            v-model:value="model.applyUseType"
            :options="[
              { label: '不限制', value: 0 },
              { label: '成功调用次数', value: 1 },
              { label: '实际返回行数', value: 2 },
            ]"
          />
          <InputNumber
            v-if="model.applyUseType"
            v-model:value="model.applyUseTimes"
            :min="1"
            :max="2147483647"
            :precision="0"
            placeholder="本申请累计额度"
          />
          <span class="text-muted-foreground text-xs">预览、查询与接口累计计量；失败不扣，暂停恢复不重置。额度用尽需重新申请。</span>
        </div>
      </div>
      <Button :loading="savingDraft" @click="submit(false)">
        {{ model.status === 8 ? '保存补正' : '保存草稿' }}
      </Button>
    </div>
  </Modal>
</template>

<style lang="scss" scoped>
@import '../../governance/common/custom-modal-form.css';
</style>
