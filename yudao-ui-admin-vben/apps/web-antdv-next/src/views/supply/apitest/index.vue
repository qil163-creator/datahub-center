<script setup lang="ts">
import type { RequestDraft } from './request';

import type { SupplyApiTestApi } from '#/api/supply/apitest';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  ref,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  InputNumber,
  message,
  Radio,
  RadioGroup,
  Select,
  Tag,
} from 'antdv-next';

import {
  getApiTestCatalog,
  getApiTestDetail,
  sendApiTest,
} from '#/api/supply/apitest';
import { parseResourceId } from '#/utils/resource-id';

import ApiTree from './components/api-tree.vue';
import BodyEditor from './components/body-editor.vue';
import KeyValueEditor from './components/key-value-editor.vue';
import ResponsePanel from './components/response-panel.vue';
import { buildSendRequest, createDraft, createRow } from './request';

defineOptions({ name: 'SupplyApiTest' });

interface TestTab {
  draft: RequestDraft;
  requestTab: string;
  loading: boolean;
  sending: boolean;
  error: string;
  response?: SupplyApiTestApi.Response;
  collapsed: boolean;
  responseRatio: number;
  rawBodies: Record<'json' | 'text' | 'xml', string>;
}
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canInvoke = computed(() => hasAccessByCodes(['supply:data-api:invoke']));
const tabs = ref<TestTab[]>([]);
const selectedId = ref('');
const current = computed(() =>
  tabs.value.find((tab) => tab.draft.id === selectedId.value),
);
const catalog = ref<SupplyApiTestApi.Catalog>();
const catalogLoading = ref(false);
const catalogError = ref('');
const treeCollapsed = ref(false);
const workspace = ref<HTMLElement>();
let active = true;
let epoch = 0;
let catalogController: AbortController | undefined;
const details = new Map<string, AbortController>();
const requests = new Map<string, AbortController>();
const methods = [
  'GET',
  'POST',
  'PUT',
  'PATCH',
  'DELETE',
  'HEAD',
  'OPTIONS',
].map((value) => ({ label: value, value }));
const requestTabs = [
  { key: 'params', label: 'Params' },
  { key: 'body', label: 'Body' },
  { key: 'headers', label: 'Headers' },
  { key: 'cookies', label: 'Cookies' },
  { key: 'auth', label: 'Auth' },
  { key: 'settings', label: '设置' },
];
const bodyTypes = [
  { value: 'none', label: 'none' },
  { value: 'form-data', label: 'form-data' },
  { value: 'x-www-form-urlencoded', label: 'x-www-form-urlencoded' },
  { value: 'json', label: 'JSON' },
  { value: 'xml', label: 'XML' },
  { value: 'text', label: 'Text' },
];
const authTypes = [
  { value: 'none', label: '无认证' },
  { value: 'bearer', label: 'Bearer Token' },
  { value: 'basic', label: 'Basic Auth' },
  { value: 'apikey', label: 'API Key（Header）' },
];

function addTab(draft = createDraft()) {
  const tab: TestTab = {
    draft,
    requestTab: 'params',
    loading: false,
    sending: false,
    error: '',
    collapsed: false,
    responseRatio: 0.35,
    rawBodies: { json: '', text: '', xml: '' },
  };
  tabs.value.push(tab);
  selectedId.value = draft.id;
  return tabs.value.at(-1)!;
}
function closeTab(id: string) {
  details.get(id)?.abort();
  details.delete(id);
  requests.get(id)?.abort();
  requests.delete(id);
  const index = tabs.value.findIndex((tab) => tab.draft.id === id);
  tabs.value = tabs.value.filter((tab) => tab.draft.id !== id);
  if (selectedId.value === id)
    selectedId.value = tabs.value[Math.max(0, index - 1)]?.draft.id ?? '';
  if (tabs.value.length === 0 && active && canInvoke.value) addTab();
}
function clearWorkspace() {
  epoch++;
  catalogController?.abort();
  catalogController = undefined;
  for (const controller of [...details.values(), ...requests.values()])
    controller.abort();
  details.clear();
  requests.clear();
  tabs.value = [];
  selectedId.value = '';
  catalog.value = undefined;
  catalogLoading.value = false;
  catalogError.value = '';
}
async function loadCatalog() {
  if (!active || !canInvoke.value) return;
  catalogController?.abort();
  const controller = new AbortController();
  catalogController = controller;
  const generation = epoch;
  catalogLoading.value = true;
  catalogError.value = '';
  try {
    const result = await getApiTestCatalog(controller.signal);
    if (
      controller.signal.aborted ||
      generation !== epoch ||
      !active ||
      !canInvoke.value
    )
      return;
    catalog.value = result;
  } catch {
    if (!controller.signal.aborted && generation === epoch)
      catalogError.value = 'API 目录加载失败，请重试';
  } finally {
    if (catalogController === controller && generation === epoch) {
      catalogLoading.value = false;
      catalogController = undefined;
    }
  }
}
async function selectApi(rawId: string) {
  const apiId = parseResourceId(rawId);
  if (!apiId || !active || !canInvoke.value) return;
  const existing = tabs.value.find((tab) => String(tab.draft.apiId) === apiId);
  if (existing) {
    selectedId.value = existing.draft.id;
    return;
  }
  const item = catalog.value?.apis.find((api) => String(api.id) === apiId);
  const tab = addTab(
    createDraft({
      mode: 'REGISTERED',
      apiId,
      title: item?.name ?? 'API 测试',
      method: item?.method ?? 'GET',
    }),
  );
  const id = tab.draft.id;
  const controller = new AbortController();
  details.set(id, controller);
  tab.loading = true;
  const generation = epoch;
  try {
    const detail = await getApiTestDetail(apiId, controller.signal);
    if (
      controller.signal.aborted ||
      generation !== epoch ||
      !tabs.value.includes(tab)
    )
      return;
    if (parseResourceId(detail.id) !== apiId)
      throw new Error('API 定义身份不一致');
    tab.draft.title = detail.name;
    tab.draft.method = detail.method;
    tab.draft.url = detail.publicUrl;
    tab.draft.query = (detail.requestParams ?? []).map((param) =>
      createRow(
        param.name ?? '',
        param.defaultValue ?? param.exampleValue ?? '',
        param.paramType,
      ),
    );
    if (
      detail.configType === 'ETL_BATCH' &&
      !tab.draft.query.some((row) => row.name === 'afterRow')
    )
      tab.draft.query.push(createRow('afterRow', '0', 'INTEGER'));
    if (detail.resultFormat === 'PAGE') {
      for (const [name, value] of [
        ['pageNo', '1'],
        ['pageSize', '10'],
      ])
        if (!tab.draft.query.some((row) => row.name === name))
          tab.draft.query.push(createRow(name, value));
    }
    if (tab.draft.query.length === 0) tab.draft.query.push(createRow());
    if (detail.authType === 'APPKEY')
      tab.draft.auth = { ...tab.draft.auth, type: 'apikey', key: 'X-AppKey' };
  } catch {
    if (
      !controller.signal.aborted &&
      generation === epoch &&
      tabs.value.includes(tab)
    )
      tab.error = 'API 定义加载失败，请关闭当前标签后重新选择';
  } finally {
    if (details.get(id) === controller && generation === epoch) {
      details.delete(id);
      tab.loading = false;
    }
  }
}
function cancel(tab: TestTab) {
  const id = tab.draft.id;
  requests.get(id)?.abort();
  requests.delete(id);
  tab.sending = false;
  tab.error = '已取消请求，停止接收本次结果';
}
async function send() {
  const tab = current.value;
  if (!tab || tab.sending || tab.loading || !active || !canInvoke.value) return;
  if (tab.draft.mode === 'REGISTERED' && !tab.draft.url) {
    message.warning('请等待 API 定义加载完成');
    return;
  }
  let payload: SupplyApiTestApi.SendRequest;
  try {
    payload = buildSendRequest(tab.draft);
  } catch (error) {
    message.warning((error as Error).message);
    return;
  }
  const id = tab.draft.id;
  const controller = new AbortController();
  const generation = epoch;
  requests.set(id, controller);
  tab.sending = true;
  tab.error = '';
  tab.response = undefined;
  tab.collapsed = false;
  try {
    const result = await sendApiTest(payload, controller.signal);
    if (
      controller.signal.aborted ||
      requests.get(id) !== controller ||
      generation !== epoch ||
      !tabs.value.includes(tab) ||
      !active ||
      !canInvoke.value
    )
      return;
    tab.response = result;
  } catch {
    if (
      !controller.signal.aborted &&
      requests.get(id) === controller &&
      generation === epoch &&
      tabs.value.includes(tab)
    )
      tab.error = '请求执行失败，请检查目标地址和请求配置后重试';
  } finally {
    if (requests.get(id) === controller && generation === epoch) {
      requests.delete(id);
      tab.sending = false;
    }
  }
}
function resizeResponse(event: PointerEvent) {
  const tab = current.value;
  const element = event.currentTarget as HTMLElement;
  if (!tab || tab.collapsed) return;
  element.setPointerCapture(event.pointerId);
  const move = (pointer: PointerEvent) => {
    const rect = workspace.value?.getBoundingClientRect();
    if (rect)
      tab.responseRatio = Math.max(
        0.2,
        Math.min(0.72, (rect.bottom - pointer.clientY) / rect.height),
      );
  };
  const stop = () => {
    element.removeEventListener('pointermove', move);
    element.removeEventListener('pointerup', stop);
    element.removeEventListener('pointercancel', stop);
  };
  element.addEventListener('pointermove', move);
  element.addEventListener('pointerup', stop);
  element.addEventListener('pointercancel', stop);
}
function setBodyType(type: RequestDraft['bodyType']) {
  const tab = current.value;
  if (!tab) return;
  const previous = tab.draft.bodyType;
  if (previous === 'json' || previous === 'xml' || previous === 'text')
    tab.rawBodies[previous] = tab.draft.rawBody;
  tab.draft.bodyType = type;
  if (type === 'json' || type === 'xml' || type === 'text')
    tab.draft.rawBody = tab.rawBodies[type];
}
function bodyLanguage(type: RequestDraft['bodyType']): 'json' | 'text' | 'xml' {
  return type === 'json' || type === 'xml' ? type : 'text';
}
function resetSession() {
  clearWorkspace();
  if (active && canInvoke.value) {
    addTab();
    void loadCatalog();
  }
}
watch(
  () => [
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
    canInvoke.value,
  ],
  resetSession,
  { immediate: true, flush: 'sync' },
);
onDeactivated(() => {
  active = false;
  clearWorkspace();
});
onActivated(() => {
  if (!active) {
    active = true;
    resetSession();
  }
});
onBeforeUnmount(() => {
  active = false;
  clearWorkspace();
});
</script>

<template>
  <Page auto-content-height>
    <Alert
      v-if="!canInvoke"
      message="没有接口测试权限"
      type="warning"
      show-icon
    />
    <div
      v-else
      class="api-workbench"
      :class="{ 'api-workbench--collapsed': treeCollapsed }"
    >
      <Card
        v-show="!treeCollapsed"
        class="api-workbench__catalog"
        :styles="{
          body: {
            display: 'flex',
            flexDirection: 'column',
            height: '100%',
            overflow: 'hidden',
            padding: '12px',
          },
        }"
      >
        <ApiTree
          :catalog="catalog"
          :loading="catalogLoading"
          :selected-api-id="
            current?.draft.mode === 'REGISTERED'
              ? String(current.draft.apiId)
              : undefined
          "
          @select="selectApi"
          @refresh="loadCatalog"
        /><Alert
          v-if="catalogError"
          :message="catalogError"
          type="error"
          show-icon
        /><Button v-if="catalogError" type="link" @click="loadCatalog">
          <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
          重试
        </Button>
      </Card>
      <button
        class="api-workbench__collapse"
        :aria-label="treeCollapsed ? '展开 API 目录' : '收起 API 目录'"
        @click="treeCollapsed = !treeCollapsed"
      >
        <IconifyIcon
          :icon="treeCollapsed ? 'lucide:chevron-right' : 'lucide:chevron-left'"
        />
      </button>
      <section ref="workspace" class="api-workbench__workspace">
        <div class="api-workbench__request-card">
        <div
          class="api-workbench__requests"
          role="tablist"
          aria-label="测试请求"
        >
          <div
            v-for="tab in tabs"
            :key="tab.draft.id"
            class="api-workbench__request"
            :class="{ active: selectedId === tab.draft.id }"
          >
            <button
              role="tab"
              :aria-selected="selectedId === tab.draft.id"
              @click="selectedId = tab.draft.id"
            >
              <span
                class="api-workbench__method"
                :class="`method-${tab.draft.method.toLowerCase()}`"
                >{{ tab.draft.method }}</span><span class="api-workbench__title">{{
                tab.draft.title
              }}</span>
</button><button
              :aria-label="`关闭 ${tab.draft.title}`"
              @click="closeTab(tab.draft.id)"
            >
              <IconifyIcon icon="lucide:x" />
            </button>
          </div>
          <Button
            title="新建测试"
            aria-label="新建测试"
            type="text"
            @click="addTab()"
          >
            <IconifyIcon icon="lucide:plus" />
          </Button>
        </div>
          <div v-if="current" class="api-workbench__request-pane">
            <div class="api-workbench__address">
              <Select
                v-model:value="current.draft.method"
                :options="methods"
                class="api-workbench__method-select"
                aria-label="请求方式"
                :disabled="current.loading || current.sending"
              /><Input
                v-model:value="current.draft.url"
                class="api-workbench__url-input"
                :readonly="current.draft.mode === 'REGISTERED'"
                :disabled="current.loading || current.sending"
                placeholder="请输入 http:// 或 https:// 开头的接口地址"
                aria-label="接口地址"
              /><Tag class="api-workbench__source">
                {{
                  current.draft.mode === 'REGISTERED' ? '项目 API' : '外部 URL'
                }}
</Tag><Button v-if="current.sending" danger @click="cancel(current)">
                <template #icon>
<IconifyIcon icon="lucide:circle-stop" />
</template>
                取消
</Button><Button
                v-else
                type="primary"
                :disabled="current.loading"
                @click="send"
              >
                <template #icon><IconifyIcon icon="lucide:send" /></template>
                发送
              </Button>
            </div>
            <nav class="api-workbench__request-tabs" aria-label="请求配置">
              <button
                v-for="item in requestTabs"
                :key="item.key"
                :class="{ active: current.requestTab === item.key }"
                @click="current.requestTab = item.key"
              >
                {{ item.label }}
              </button>
            </nav>
            <div
              class="api-workbench__configuration"
              :inert="current.loading || current.sending"
            >
              <div v-if="current.loading" class="api-workbench__loading">
                正在加载 API 定义…
              </div>
              <KeyValueEditor
                v-else-if="current.requestTab === 'params'"
                v-model:items="current.draft.query"
                show-type
              />
              <template v-else-if="current.requestTab === 'body'">
                <div class="api-workbench__body-types">
                  <button
                    v-for="item in bodyTypes"
                    :key="item.value"
                    :class="{ active: current.draft.bodyType === item.value }"
                    @click="setBodyType(item.value as RequestDraft['bodyType'])"
                  >
                    {{ item.label }}
                  </button>
                </div>
                <div
                  v-if="current.draft.bodyType === 'none'"
                  class="api-workbench__no-body"
                >
                  <Empty description="该请求没有 Body" />
                </div>
                <KeyValueEditor
                  v-else-if="current.draft.bodyType === 'form-data'"
                  v-model:items="current.draft.formData"
                /><KeyValueEditor
                  v-else-if="current.draft.bodyType === 'x-www-form-urlencoded'"
                  v-model:items="current.draft.urlencoded"
                /><BodyEditor
                  v-else
                  v-model:value="current.draft.rawBody"
                  :language="bodyLanguage(current.draft.bodyType)"
                />
              </template>
              <KeyValueEditor
                v-else-if="current.requestTab === 'headers'"
                v-model:items="current.draft.headers"
                name-placeholder="请输入 Header 名称"
                value-placeholder="请输入 Header 值"
              />
              <KeyValueEditor
                v-else-if="current.requestTab === 'cookies'"
                v-model:items="current.draft.cookies"
                name-placeholder="请输入 Cookie 名称"
                value-placeholder="请输入 Cookie 值"
              />
              <div
                v-else-if="current.requestTab === 'auth'"
                class="api-workbench__form"
              >
                <label :for="`${current.draft.id}-auth-type`">认证类型</label><Select
                  v-model:value="current.draft.auth.type"
                  :id="`${current.draft.id}-auth-type`"
                  :options="authTypes"
                  aria-label="认证类型"
                /><template v-if="current.draft.auth.type === 'bearer'">
                  <label>Token</label><Input
                    v-model:value="current.draft.auth.token"
                    type="password"
                    placeholder="请输入 Token"
                    aria-label="Token"
                    autocomplete="off"
                  />
</template><template v-else-if="current.draft.auth.type === 'basic'">
                  <label>用户名</label><Input
                    v-model:value="current.draft.auth.username"
                    placeholder="请输入用户名"
                    aria-label="认证用户名"
                    autocomplete="off"
                  /><label>密码</label><Input
                    v-model:value="current.draft.auth.password"
                    type="password"
                    placeholder="请输入密码"
                    aria-label="认证密码"
                    autocomplete="off"
                  />
</template><template v-else-if="current.draft.auth.type === 'apikey'">
                  <label>Header 名称</label><Input
                    v-model:value="current.draft.auth.key"
                    aria-label="认证 Header 名称"
                  /><label>Header 值</label><Input
                    v-model:value="current.draft.auth.value"
                    type="password"
                    placeholder="请输入 Header 值"
                    aria-label="认证 Header 值"
                    autocomplete="off"
                  />
                </template>
              </div>
              <div v-else class="api-workbench__form">
                <label>超时时间</label>
                <div class="api-workbench__timeout">
                  <InputNumber
                    v-model:value="current.draft.timeoutSeconds"
                    :min="1"
                    :max="120"
                    :precision="0"
                    aria-label="超时时间"
                  /><span>秒</span>
                </div>
                <label>跟随重定向</label><RadioGroup v-model:value="current.draft.followRedirects">
                  <Radio :value="true">启用</Radio><Radio :value="false">禁用</Radio>
                </RadioGroup>
              </div>
            </div>
          </div>
        </div>
          <div
            v-if="current"
            class="api-workbench__response"
            :class="{ 'api-workbench__response--collapsed': current.collapsed }"
            :style="
              current.collapsed
                ? undefined
                : { height: `${current.responseRatio * 100}%` }
            "
          >
            <div
              class="api-workbench__response-title"
              role="separator"
              aria-label="调整返回响应区域高度"
              aria-orientation="horizontal"
              @pointerdown="resizeResponse"
            >
              <span>返回响应</span><Button
                size="small"
                type="text"
                :aria-label="
                  current.collapsed ? '展开返回响应' : '收起返回响应'
                "
                @pointerdown.stop
                @click="current.collapsed = !current.collapsed"
              >
                <IconifyIcon
                  :icon="current.collapsed ? 'lucide:plus' : 'lucide:minus'"
                />
              </Button>
            </div>
            <ResponsePanel
              v-show="!current.collapsed"
              :key="current.draft.id"
              :response="current.response"
              :sending="current.sending"
              :error="current.error"
            />
          </div>
      </section>
    </div>
  </Page>
</template>

<style scoped>
.api-workbench {
  position: relative;
  display: flex;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  font-size: 14px;
}

.api-workbench__catalog {
  flex-shrink: 0;
  width: 16.6667%;
  min-width: 0;
  min-height: 0;
  margin-right: 12px;
}

.api-workbench__catalog :deep(.ant-card-body) > :first-child {
  flex: 1;
  min-height: 0;
}

.api-workbench__collapse {
  position: absolute;
  top: 48%;
  left: calc(16.6667% - 2px);
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 40px;
  color: hsl(var(--muted-foreground));
  background: hsl(var(--muted));
  border-radius: 3px;
}

.api-workbench--collapsed .api-workbench__collapse {
  left: 0;
}

.api-workbench__workspace {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.api-workbench__request-card {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

.api-workbench__requests {
  display: flex;
  flex-shrink: 0;
  gap: 4px;
  align-items: center;
  min-height: 46px;
  padding: 0 12px;
  overflow-x: auto;
  border-bottom: 1px solid hsl(var(--border));
}

.api-workbench__request {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  width: 160px;
  min-width: 0;
  border-bottom: 2px solid transparent;
}

.api-workbench__request.active {
  color: hsl(var(--primary));
  border-bottom-color: hsl(var(--primary));
}

.api-workbench__request button {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 8px;
}

.api-workbench__request button:first-child {
  flex: 1;
  min-width: 0;
}

.api-workbench__title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.api-workbench__method {
  font-size: 11px;
  font-weight: 600;
}

.method-get {
  color: #52a536;
}

.method-post {
  color: #d99000;
}

.method-put,
.method-head,
.method-options {
  color: #4870ee;
}

.method-patch {
  color: #995bc0;
}

.method-delete {
  color: #e75959;
}

.api-workbench__request-pane {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 140px;
  overflow: hidden;
}

.api-workbench__address {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
  align-items: center;
  padding: 12px 14px 8px;
}

.api-workbench__url-input {
  flex: 1;
  min-width: 0;
}

.api-workbench__method-select {
  flex-shrink: 0;
  width: 105px;
}

.api-workbench__source {
  flex-shrink: 0;
  margin: 0;
}

.api-workbench__request-tabs {
  display: flex;
  flex-shrink: 0;
  gap: 24px;
  padding: 0 14px;
  overflow-x: auto;
  border-bottom: 1px solid hsl(var(--border));
}

.api-workbench__request-tabs button {
  padding: 10px 0;
  border-bottom: 2px solid transparent;
}

.api-workbench__request-tabs button.active {
  color: hsl(var(--primary));
  border-bottom-color: hsl(var(--primary));
}

.api-workbench__configuration {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  padding: 12px 14px;
  overflow: auto;
}

.api-workbench__configuration[inert] {
  opacity: 0.65;
}

.api-workbench__body-types {
  display: flex;
  flex-shrink: 0;
  flex-wrap: wrap;
  gap: 8px;
  padding-bottom: 12px;
}

.api-workbench__body-types button {
  padding: 4px 10px;
  border-radius: 3px;
}

.api-workbench__body-types button.active {
  color: hsl(var(--primary-foreground));
  background: hsl(var(--primary));
}

.api-workbench__no-body {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  min-height: 180px;
  border: 1px solid hsl(var(--border));
}

.api-workbench__form {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  gap: 16px 12px;
  align-items: center;
  width: min(100%, 680px);
  padding: 4px 0;
}

.api-workbench__form > label {
  text-align: right;
}

.api-workbench__timeout {
  display: flex;
  gap: 8px;
  align-items: center;
}

.api-workbench__loading {
  padding: 20px;
  color: hsl(var(--muted-foreground));
}

.api-workbench__response {
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  min-height: 180px;
  overflow: hidden;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

.api-workbench__response--collapsed {
  height: 44px;
  min-height: 44px;
}

.api-workbench__response-title {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  height: 42px;
  padding: 0 14px;
  touch-action: none;
  cursor: row-resize;
  border-bottom: 1px solid hsl(var(--border));
}

.api-workbench__response-title > span {
  font-size: 14px;
  font-weight: 600;
}

.api-workbench__response > :last-child {
  flex: 1;
  min-height: 0;
}

@media (width <= 900px) {
  .api-workbench__catalog {
    width: 210px;
    min-width: 210px;
  }

  .api-workbench__collapse {
    left: 208px;
  }

  .api-workbench__source {
    display: none;
  }
}

@media (width <= 640px) {
  .api-workbench {
    flex-direction: column;
    height: auto;
    min-height: 900px;
  }

  .api-workbench__catalog {
    width: 100%;
    height: 190px;
    min-height: 190px;
    margin: 0 0 10px;
  }

  .api-workbench__collapse {
    top: 174px;
    right: 4px;
    left: auto;
    width: 24px;
    height: 24px;
  }

  .api-workbench--collapsed .api-workbench__collapse {
    top: 0;
    left: auto;
  }

  .api-workbench__workspace {
    flex: none;
    height: 700px;
  }

  .api-workbench__address {
    flex-wrap: wrap;
    gap: 6px;
    padding: 10px;
  }

  .api-workbench__method-select {
    width: 86px;
  }

  .api-workbench__url-input {
    flex: none;
    width: calc(100% - 92px);
  }

  .api-workbench__address > :last-child {
    margin-left: auto;
  }

  .api-workbench__request-tabs {
    gap: 22px;
    padding: 0 10px;
  }

  .api-workbench__configuration {
    padding: 10px;
  }

  .api-workbench__form {
    grid-template-columns: 82px minmax(0, 1fr);
  }
}
</style>
