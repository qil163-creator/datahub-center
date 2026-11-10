<script lang="ts" setup>
import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';
import type { GovernanceMasterDistributeApi } from '#/api/governance/masterdistribute';

import { computed, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import {
  Alert,
  Button,
  Checkbox,
  Input,
  InputNumber,
  message,
  RadioGroup,
  Select,
  TextArea,
  TreeSelect,
} from 'antdv-next';

import { getDatabaseSimpleList } from '#/api/dataforge/database';
import {
  createMasterDistribute,
  getMasterDistribute,
  getPushModel,
  getPushModelList,
  updateMasterDistribute,
} from '#/api/governance/masterdistribute';
import { getMasterPushCategoryTree } from '#/api/governance/masterdistribute/category';
import { CronTab } from '#/components/cron-tab';

import { clearReactiveObject } from '../../common/form-layout';
import { useMasterScope } from '../../mastercommon/use-master-scope';
import { buildCategoryOptions } from '../category-model';
import { DISTRIBUTE_TYPE_OPTIONS, TASK_TYPE_OPTIONS } from '../data';
import {
  defaultApiConfig,
  PUSH_MODE_OPTIONS,
  PUSH_SCOPE_OPTIONS,
  validateMapping,
} from '../push-config';
import MappingEditor from './mapping-editor.vue';

defineOptions({ name: 'GovernanceMasterDistributeForm' });
const emit = defineEmits<{ success: [] }>();
const { hasAccessByCodes } = useAccess();
const canEnable = computed(() =>
  hasAccessByCodes(['governance:master-distribute:release']),
);
const model = reactive<GovernanceMasterDistributeApi.Distribute>({});
const dbConfig = reactive<GovernanceMasterDistributeApi.DbConfig>({});
const apiConfig = reactive<GovernanceMasterDistributeApi.ApiConfig>({});
const mqConfig = reactive<GovernanceMasterDistributeApi.MqConfig>({});
const apiHeaders = ref<GovernanceMasterDistributeApi.KeyValue[]>([]);
const apiParams = ref<GovernanceMasterDistributeApi.KeyValue[]>([]);
const columns = ref<GovernanceMasterDataApi.Column[]>([]);
const models = ref<{ label: string; value: number | string }[]>([]);
const databases = ref<{ label: string; value: number | string }[]>([]);
const categoryOptions = ref<ReturnType<typeof buildCategoryOptions>>([]);
const opened = ref(false);
const advanced = ref(false);
const failed = ref(false);
const fieldsLoading = ref(false);
const fieldsFailed = ref(false);
const validation = ref('');
const MQ_TYPE_OPTIONS = [
  { label: 'Kafka', value: 'kafka' },
  { label: 'RabbitMQ', value: 'rabbitmq' },
];
const DB_FETCH_OPTIONS = [1000, 2000, 3000, 5000].map((value) => ({
  label: String(value),
  value,
}));
const MSG_FETCH_OPTIONS = [10, 50, 100, 200, 500].map((value) => ({
  label: String(value),
  value,
}));
const scope = useMasterScope(() => {
  resetAll();
  void modalApi.close();
});
const fieldOptions = computed(() =>
  columns.value
    .filter((row) => row.name)
    .map((row) => ({
      label: `${row.commentStr || row.name} (${row.name})`,
      value: row.name!,
    })),
);
function resetAll() {
  clearReactiveObject(model);
  clearReactiveObject(apiConfig);
  Object.assign(model, {
    distributeType: 2,
    taskType: 1,
    status: canEnable.value ? 1 : 0,
    cron: '0 0 2 * * ?',
  });
  Object.assign(apiConfig, defaultApiConfig());
  Object.assign(dbConfig, {
    databaseId: undefined,
    targetTableName: undefined,
    targetDrop: false,
    syncExist: true,
    onlyCreate: false,
    changeDataSync: true,
    fetchSize: 1000,
  });
  Object.assign(mqConfig, {
    mqType: 'kafka',
    kafka: {},
    rabbit: { port: 5672, virtualHost: '/' },
    fetchSize: 100,
  });
  apiHeaders.value = [];
  apiParams.value = [];
  columns.value = [];
  models.value = [];
  databases.value = [];
  categoryOptions.value = [];
  advanced.value = false;
  failed.value = false;
  fieldsLoading.value = false;
  fieldsFailed.value = false;
  validation.value = '';
}
function fillFrom(data: GovernanceMasterDistributeApi.Distribute) {
  Object.assign(model, data, { config: undefined });
  if (data.config?.db) Object.assign(dbConfig, data.config.db);
  if (data.config?.api) {
    Object.assign(
      apiConfig,
      { pushScope: 'ALL', pushMode: 'FULL', authType: 'NONE' },
      data.config.api,
    );
    apiHeaders.value = data.config.api.headers ?? [];
    apiParams.value = data.config.api.params ?? [];
  }
  if (data.config?.mq)
    Object.assign(mqConfig, data.config.mq, {
      kafka: data.config.mq.kafka ?? {},
      rabbit: data.config.mq.rabbit ?? { port: 5672, virtualHost: '/' },
    });
  advanced.value = data.distributeType !== 2 || data.taskType === 2;
}
async function loadFields(id: number | string | undefined) {
  const current = scope.begin('fields');
  columns.value = [];
  fieldsFailed.value = false;
  fieldsLoading.value = !!id;
  if (!id || !opened.value) return;
  try {
    const result = await getPushModel(id);
    if (current()) columns.value = result.columns ?? [];
  } catch {
    if (current()) fieldsFailed.value = true;
  } finally {
    if (current()) fieldsLoading.value = false;
  }
}
watch(
  () => model.masterModelId,
  (id) => {
    void loadFields(id);
  },
);
watch(
  () => model.distributeType,
  async (type) => {
    if (type !== 1 || !opened.value || databases.value.length > 0) return;
    const current = scope.begin('databases');
    try {
      const values = await getDatabaseSimpleList();
      if (current())
        databases.value = values.map((item) => ({
          label: item.name || String(item.id),
          value: item.id,
        }));
    } catch {
      if (current()) validation.value = '目标数据源加载失败，请关闭后重试';
    }
  },
);
function validate() {
  if (!model.name?.trim()) return '请输入下游系统';
  if (!model.masterModelId) return '请选择主数据对象';
  if (model.taskType === 2 && !model.cron?.trim()) return '请配置 Cron 表达式';
  if (model.distributeType === 1 && !dbConfig.databaseId)
    return '请选择目标数据源';
  if (model.distributeType === 2) {
    if (model.status === 1 && !canEnable.value)
      return '修改已启用的推送配置需要启停权限';
    if (!apiConfig.url?.trim()) return '请输入下游接收地址';
    if (!/^https?:\/\//iu.test(apiConfig.url.trim()))
      return '下游接收地址仅支持 http/https 协议';
    if (fieldsLoading.value) return '请等待模型字段加载完成';
    if (fieldsFailed.value) return '模型字段加载失败，请重新选择主数据对象';
    if (
      apiConfig.authType === 'TOKEN' &&
      !apiConfig.token?.trim() &&
      !apiConfig.tokenConfigured
    )
      return '请输入 Token';
    if (apiConfig.pushScope === 'ENABLED' && !apiConfig.statusField)
      return '请选择用于识别启用数据的状态字段';
    if (apiConfig.pushScope === 'ENABLED' && !apiConfig.enabledValue?.trim())
      return '请输入启用值';
    if (
      apiConfig.pushMode === 'INCREMENTAL' &&
      !columns.value.some((row) => row.pk === 1)
    )
      return '增量推送需要模型主键，请选择全量推送';
    return validateMapping(apiConfig.mappings ?? []);
  }
  if (model.distributeType === 3) {
    if (
      mqConfig.mqType === 'kafka' &&
      (!mqConfig.kafka?.bootstrapServers || !mqConfig.kafka?.topic)
    )
      return '请填写 Kafka 服务地址与 Topic';
    if (
      mqConfig.mqType === 'rabbitmq' &&
      (!mqConfig.rabbit?.host ||
        !mqConfig.rabbit?.port ||
        !mqConfig.rabbit?.exchange ||
        !mqConfig.rabbit?.routingKey)
    )
      return '请填写 RabbitMQ 主机、端口、交换机与路由键';
  }
  return undefined;
}
function buildPayload(): GovernanceMasterDistributeApi.Distribute {
  const config: GovernanceMasterDistributeApi.DistributeConfig = {};
  if (model.distributeType === 1) config.db = { ...dbConfig };
  if (model.distributeType === 2)
    config.api = {
      ...apiConfig,
      headers: apiHeaders.value.filter((row) => row.key?.trim()),
      params: apiParams.value.filter((row) => row.key?.trim()),
    };
  if (model.distributeType === 3)
    config.mq = {
      mqType: mqConfig.mqType,
      fetchSize: mqConfig.fetchSize,
      kafka: mqConfig.mqType === 'kafka' ? mqConfig.kafka : undefined,
      rabbit: mqConfig.mqType === 'rabbitmq' ? mqConfig.rabbit : undefined,
    };
  return {
    ...model,
    categoryId: model.categoryId ?? null,
    status: model.distributeType === 2 ? model.status : undefined,
    cron: model.taskType === 2 ? model.cron : undefined,
    config,
  };
}
const [ModalComponent, modalApi] = useVbenModal({
  async onConfirm() {
    validation.value = validate() ?? '';
    if (validation.value || failed.value) return;
    const current = scope.begin('save');
    modalApi.lock();
    try {
      const payload = buildPayload();
      await (model.id
        ? updateMasterDistribute(payload)
        : createMasterDistribute(payload));
      if (!current()) return;
      message.success('主数据推送配置已保存');
      emit('success');
      await modalApi.close();
    } finally {
      if (current()) modalApi.unlock();
    }
  },
  async onOpenChange(isOpen) {
    scope.invalidate();
    opened.value = isOpen;
    resetAll();
    modalApi.unlock();
    if (!isOpen) return;
    const current = scope.begin('open');
    const row = modalApi.getData() as
      | GovernanceMasterDistributeApi.Distribute
      | undefined;
    modalApi.lock();
    try {
      const [values, categories] = await Promise.all([
        getPushModelList(),
        getMasterPushCategoryTree(),
      ]);
      if (!current()) return;
      categoryOptions.value = buildCategoryOptions(categories);
      models.value = values.map((item) => ({
        label: item.tableCn || item.tableName || String(item.id),
        value: item.id!,
      }));
      if (row?.id !== undefined) {
        const detail = await getMasterDistribute(row.id);
        if (current()) {
          fillFrom(detail);
          model.categoryId = detail.categoryId === null || detail.categoryId === undefined ? undefined : String(detail.categoryId);
        }
      } else if (row?.categoryId !== null && row?.categoryId !== undefined) {
        model.categoryId = String(row.categoryId);
      }
    } catch {
      if (current()) failed.value = true;
    } finally {
      if (current()) modalApi.unlock();
    }
  },
});
</script>

<template>
  <ModalComponent
    :title="model.id ? '配置主数据推送' : '新增主数据推送'"
    class="metadata-modal qdata-master-modal"
    header-class="metadata-modal__header"
    content-class="metadata-modal__body"
    footer-class="metadata-modal__footer"
  >
    <Alert v-if="validation" type="error" :message="validation" class="mb-4" />
    <div class="metadata-section">基础配置</div>
    <div class="metadata-form-grid">
      <div class="metadata-form-item">
        <label>推送类目</label><TreeSelect
          v-model:value="model.categoryId"
          :tree-data="categoryOptions"
          placeholder="请选择推送类目"
          allow-clear
          tree-default-expand-all
        />
      </div>
      <div class="metadata-form-item metadata-form-item--required">
        <label>下游系统</label><Input v-model:value="model.name" placeholder="请输入下游系统" />
      </div>
      <div class="metadata-form-item metadata-form-item--required">
        <label>主数据对象</label><Select
          v-model:value="model.masterModelId"
          :options="models"
          placeholder="请选择主数据对象"
          show-search
          option-filter-prop="label"
        />
      </div>
      <template v-if="model.distributeType === 2">
        <div class="metadata-form-item">
          <label>推送范围</label><Select
            v-model:value="apiConfig.pushScope"
            :options="PUSH_SCOPE_OPTIONS"
          />
        </div>
        <div class="metadata-form-item">
          <label>推送模式</label><Select
            v-model:value="apiConfig.pushMode"
            :options="PUSH_MODE_OPTIONS"
          />
        </div>
        <div class="metadata-form-item">
          <label>状态</label><RadioGroup
            v-model:value="model.status"
            :disabled="!canEnable"
            :options="[
              { label: '启用', value: 1 },
              { label: '禁用', value: 0 },
            ]"
          />
        </div>
      </template>
    </div>
    <template v-if="model.distributeType === 2">
      <div class="metadata-section">REST 接收配置</div>
      <div class="metadata-form-grid">
        <div
          class="metadata-form-item metadata-form-item--wide metadata-form-item--required"
        >
          <label>下游接收地址</label><Input
            v-model:value="apiConfig.url"
            placeholder="请输入下游接收地址"
            :status="validation === '请输入下游接收地址' ? 'error' : undefined"
          />
        </div>
        <div class="metadata-form-item metadata-form-item--required">
          <label>请求方式</label><Select
            v-model:value="apiConfig.method"
            :options="[
              { label: 'POST', value: 'POST' },
              { label: 'PUT', value: 'PUT' },
            ]"
          />
        </div>
        <div class="metadata-form-item">
          <label>认证方式</label><Select
            v-model:value="apiConfig.authType"
            :options="[
              { label: 'Token', value: 'TOKEN' },
              { label: '无认证', value: 'NONE' },
            ]"
          />
        </div>
        <div class="metadata-form-item">
          <label>超时时间(秒)</label><InputNumber
            v-model:value="apiConfig.timeoutSeconds"
            :min="1"
            :max="300"
          />
        </div>
        <div v-if="apiConfig.authType === 'TOKEN'" class="metadata-form-item">
          <label>Token</label><Input.Password
            v-model:value="apiConfig.token"
            :placeholder="
              apiConfig.tokenConfigured
                ? '已配置，留空保留原 Token'
                : '请输入 Token'
            "
            autocomplete="new-password"
          />
        </div>
      </div>
      <div class="metadata-section">重试机制配置</div>
      <div class="metadata-form-grid">
        <div class="metadata-form-item">
          <label>失败自动重试</label><Select
            :value="apiConfig.retryEnabled ? 1 : 0"
            :options="[
              { label: '是', value: 1 },
              { label: '否', value: 0 },
            ]"
            @update:value="(value) => (apiConfig.retryEnabled = value === 1)"
          />
        </div>
        <div class="metadata-form-item">
          <label>失败重试次数</label><InputNumber
            v-model:value="apiConfig.retryCount"
            :disabled="!apiConfig.retryEnabled"
            :min="0"
            :max="10"
          />
        </div>
        <div class="metadata-form-item">
          <label>重试间隔(分钟)</label><InputNumber
            v-model:value="apiConfig.retryIntervalMinutes"
            :disabled="!apiConfig.retryEnabled"
            :min="1"
            :max="1440"
          />
        </div>
        <div class="metadata-form-item">
          <label>超出次数处理</label><Select
            v-model:value="apiConfig.exhaustedAction"
            :disabled="!apiConfig.retryEnabled"
            :options="[
              { label: '停止推送', value: 'STOP' },
              { label: '人工处理', value: 'MANUAL' },
            ]"
          />
        </div>
        <div class="metadata-form-item metadata-form-item--wide">
          <label>描述</label><TextArea
            v-model:value="model.description"
            :rows="4"
            :maxlength="256"
            show-count
            placeholder="请输入描述"
          />
        </div>
      </div>
      <template v-if="apiConfig.pushScope === 'ENABLED'">
        <div class="metadata-section">启用数据规则</div>
        <div class="metadata-form-grid">
          <div class="metadata-form-item metadata-form-item--required">
            <label>状态字段</label><Select
              v-model:value="apiConfig.statusField"
              :options="fieldOptions"
              :loading="fieldsLoading"
              placeholder="请选择状态字段"
            />
          </div>
          <div class="metadata-form-item metadata-form-item--required">
            <label>启用值</label><Input
              v-model:value="apiConfig.enabledValue"
              placeholder="请输入启用数据对应的字段值"
            />
          </div>
        </div>
      </template>
      <div class="metadata-section">字段映射</div>
      <MappingEditor
        v-model="apiConfig.mappings"
        :columns="columns"
        :disabled="fieldsLoading"
      />
    </template>
    <div class="push-advanced-toggle">
      <Button type="link" size="small" @click="advanced = !advanced">
        <template #icon>
<IconifyIcon
            :icon="advanced ? 'lucide:chevron-up' : 'lucide:chevron-down'"
        />
</template>
        {{ advanced ? '收起高级配置' : '高级配置' }}
      </Button>
    </div>
    <div
      v-if="advanced"
      class="distribute-form governance-form-item-horizontal"
    >
      <div class="metadata-section">高级配置</div>
      <div class="metadata-form-grid">
        <div class="metadata-form-item">
          <label>推送类型</label><Select
            v-model:value="model.distributeType"
            :options="DISTRIBUTE_TYPE_OPTIONS"
          />
        </div>
        <div class="metadata-form-item">
          <label>调度类型</label><Select
            v-model:value="model.taskType"
            :options="TASK_TYPE_OPTIONS"
          />
        </div>
        <div
          v-if="model.taskType === 2"
          class="metadata-form-item metadata-form-item--wide"
        >
          <label>Cron 表达式</label><CronTab v-model="model.cron" />
        </div>
        <div v-if="model.distributeType === 2" class="metadata-form-item">
          <label>单次记录数</label><Select
            v-model:value="apiConfig.fetchSize"
            :options="MSG_FETCH_OPTIONS"
          />
        </div>
        <div
          v-if="model.distributeType !== 2"
          class="metadata-form-item metadata-form-item--wide"
        >
          <label>描述</label><TextArea
            v-model:value="model.description"
            :rows="3"
            :maxlength="256"
            show-count
          />
        </div>
      </div>
      <template v-if="model.distributeType === 2">
        <div class="metadata-form-grid">
          <div class="metadata-form-item metadata-form-item--wide">
            <label>请求头</label>
            <div>
              <div
                v-for="(header, index) in apiHeaders"
                :key="index"
                class="kv-row"
              >
                <Input
                  v-model:value="header.key"
                  placeholder="Header 名"
                /><Input.Password
                  v-model:value="header.value"
                  placeholder="Header 值，留空保留已配置值"
                /><Button
                  type="link"
                  danger
                  @click="apiHeaders.splice(index, 1)"
                >
                  <template #icon>
<IconifyIcon icon="lucide:trash-2" />
</template>
                  删除
                </Button>
              </div>
              <Button size="small" @click="apiHeaders.push({})">
                <template #icon><IconifyIcon icon="lucide:plus" /></template>
                添加请求头
              </Button>
            </div>
          </div>
          <div class="metadata-form-item metadata-form-item--wide">
            <label>查询参数</label>
            <div>
              <div
                v-for="(param, index) in apiParams"
                :key="index"
                class="kv-row"
              >
                <Input v-model:value="param.key" placeholder="参数名" /><Input
                  v-model:value="param.value"
                  placeholder="参数值，留空保留已配置值"
                /><Button
                  type="link"
                  danger
                  @click="apiParams.splice(index, 1)"
                >
                  <template #icon>
<IconifyIcon icon="lucide:trash-2" />
</template>
                  删除
                </Button>
              </div>
              <Button size="small" @click="apiParams.push({})">
                <template #icon><IconifyIcon icon="lucide:plus" /></template>
                添加查询参数
              </Button>
            </div>
          </div>
        </div>
      </template>
      <!-- 数据库派发 -->
      <template v-if="model.distributeType === 1">
        <div class="distribute-form__section">数据库派发配置</div>
        <div class="distribute-form__grid">
          <div class="form-item">
            <label class="form-item__label form-item__label--required">
              目标数据源
            </label>
            <Select
              v-model:value="dbConfig.databaseId"
              :options="databases"
              allow-clear
              option-filter-prop="label"
              placeholder="数据推送到该数据源"
              show-search
            />
          </div>
          <div class="form-item">
            <label class="form-item__label">目标表名</label>
            <Input
              v-model:value="dbConfig.targetTableName"
              allow-clear
              placeholder="留空表示与模型物理表同名"
            />
          </div>
          <div class="form-item">
            <label class="form-item__label">批次大小</label>
            <Select
              v-model:value="dbConfig.fetchSize"
              :options="DB_FETCH_OPTIONS"
            />
          </div>
          <div class="form-item">
            <label class="form-item__label">同步选项</label>
            <div class="flex flex-wrap gap-x-4 gap-y-1">
              <Checkbox
                :checked="dbConfig.targetDrop"
                @update:checked="(v: boolean) => (dbConfig.targetDrop = v)"
              >
                同步前删除重建目标表
              </Checkbox>
              <Checkbox
                :checked="dbConfig.syncExist"
                @update:checked="(v: boolean) => (dbConfig.syncExist = v)"
              >
                目标表已存在时继续同步
              </Checkbox>
              <Checkbox
                :checked="dbConfig.onlyCreate"
                @update:checked="(v: boolean) => (dbConfig.onlyCreate = v)"
              >
                仅创建表结构
              </Checkbox>
              <Checkbox
                :checked="dbConfig.changeDataSync"
                @update:checked="(v: boolean) => (dbConfig.changeDataSync = v)"
              >
                按主键变化量同步
              </Checkbox>
            </div>
          </div>
        </div>
      </template>

      <!-- MQ 派发 -->
      <template v-if="model.distributeType === 3">
        <div class="distribute-form__section">消息队列派发配置</div>
        <div class="distribute-form__grid">
          <div class="form-item">
            <label class="form-item__label form-item__label--required">
              队列类型
            </label>
            <RadioGroup
              v-model:value="mqConfig.mqType"
              :options="MQ_TYPE_OPTIONS"
              option-type="button"
            />
          </div>
          <div class="form-item">
            <label class="form-item__label">单条消息记录数</label>
            <Select
              v-model:value="mqConfig.fetchSize"
              :options="MSG_FETCH_OPTIONS"
            />
            <div class="form-item__tip">消息体为 JSON 数组字符串</div>
          </div>
          <template v-if="mqConfig.mqType === 'kafka'">
            <div class="form-item">
              <label class="form-item__label form-item__label--required">
                Kafka 服务地址
              </label>
              <Input
                v-model:value="mqConfig.kafka!.bootstrapServers"
                allow-clear
                placeholder="127.0.0.1:9092，多个逗号分隔"
              />
            </div>
            <div class="form-item">
              <label class="form-item__label form-item__label--required">
                Topic
              </label>
              <Input
                v-model:value="mqConfig.kafka!.topic"
                allow-clear
                placeholder="master-person"
              />
            </div>
          </template>
          <template v-else>
            <div class="form-item">
              <label class="form-item__label form-item__label--required">
                主机
              </label>
              <Input
                v-model:value="mqConfig.rabbit!.host"
                allow-clear
                placeholder="127.0.0.1"
              />
            </div>
            <div class="form-item">
              <label class="form-item__label form-item__label--required">
                端口
              </label>
              <InputNumber
                v-model:value="mqConfig.rabbit!.port"
                :max="65535"
                :min="1"
                class="w-full"
              />
            </div>
            <div class="form-item">
              <label class="form-item__label">用户名</label>
              <Input v-model:value="mqConfig.rabbit!.username" allow-clear />
            </div>
            <div class="form-item">
              <label class="form-item__label">密码</label>
              <Input.Password
                v-model:value="mqConfig.rabbit!.password"
                allow-clear
              />
            </div>
            <div class="form-item">
              <label class="form-item__label">虚拟主机</label>
              <Input
                v-model:value="mqConfig.rabbit!.virtualHost"
                allow-clear
                placeholder="/"
              />
            </div>
            <div class="form-item">
              <label class="form-item__label form-item__label--required">
                交换机
              </label>
              <Input
                v-model:value="mqConfig.rabbit!.exchange"
                allow-clear
                placeholder="master.exchange"
              />
            </div>
            <div class="form-item">
              <label class="form-item__label form-item__label--required">
                路由键
              </label>
              <Input
                v-model:value="mqConfig.rabbit!.routingKey"
                allow-clear
                placeholder="person"
              />
            </div>
          </template>
        </div>
      </template>
    </div>
  </ModalComponent>
</template>
<style scoped>
@import '../../common/custom-modal-form.css';

.distribute-form__section {
  padding-left: 10px;
  margin: 16px 0;
  border-left: 4px solid #526fff;
}

.distribute-form__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 40px;
}

.form-item--span2 {
  grid-column: 1 / -1;
}

.kv-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.push-advanced-toggle {
  margin-top: 16px;
  text-align: right;
}

@media (max-width: 900px) {
  .distribute-form__grid {
    grid-template-columns: 1fr;
  }
}
</style>
