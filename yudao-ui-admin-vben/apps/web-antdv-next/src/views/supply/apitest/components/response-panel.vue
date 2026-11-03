<script setup lang="ts">
import type { SupplyApiTestApi } from '#/api/supply/apitest';

import { computed, ref } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Alert, Button, Empty, message, Tag } from 'antdv-next';

import { formatJson } from '../request';

const props = defineProps<{
  error?: string;
  response?: SupplyApiTestApi.Response;
  sending: boolean;
}>();
const tab = ref('body');
const pretty = ref(true);
const wrap = ref(true);
const displayedBody = computed(() => {
  const result = props.response;
  if (!result) return '';
  // oxlint-disable-next-line unicorn/text-encoding-identifier-case -- 保持后端固定枚举。
  if (pretty.value && result.bodyEncoding === 'UTF8') {
    try {
      return formatJson(result.body);
    } catch {
      /* 非 JSON 原样展示。 */
    }
  }
  return result.body;
});
const cookies = computed(
  () =>
    props.response?.headers.filter(
      (header) => header.name.toLowerCase() === 'set-cookie',
    ) ?? [],
);
async function copyBody() {
  try {
    await navigator.clipboard.writeText(props.response?.body ?? '');
    message.success('已复制响应内容');
  } catch {
    message.warning('复制失败，请选择响应内容后手动复制');
  }
}
</script>

<template>
  <div class="response-panel">
    <div v-if="sending" class="response-panel__empty">
      <IconifyIcon
        icon="lucide:loader-circle"
        class="animate-spin text-primary size-8"
      /><span>请求发送中…</span>
    </div>
    <Alert v-else-if="error" :message="error" type="error" show-icon />
    <template v-else-if="response">
      <div class="response-panel__meta">
        <Tag :color="response.statusCode < 400 ? 'success' : 'error'">
          {{ response.statusCode }} {{ response.statusText }}
</Tag><span>{{ response.durationMs }} ms</span><span>{{ response.bodyBytes }} B</span><span class="response-panel__url" :title="response.url">{{
          response.url
        }}</span>
      </div>
      <Alert
        v-if="response.truncated"
        message="响应内容超过大小限制，已截断展示"
        type="warning"
        show-icon
        class="mb-2"
      />
      <Alert
        v-if="response.bodyEncoding === 'BASE64'"
        message="响应为二进制内容，以下按 Base64 展示"
        type="info"
        show-icon
        class="mb-2"
      />
      <div class="response-panel__toolbar">
        <div class="response-panel__tabs">
          <button
            v-for="item in [
              { key: 'body', label: 'Body' },
              { key: 'headers', label: `Headers (${response.headers.length})` },
              { key: 'cookies', label: `Cookies (${cookies.length})` },
            ]"
            :key="item.key"
            :class="{ active: tab === item.key }"
            @click="tab = item.key"
          >
            {{ item.label }}
          </button>
        </div>
        <template v-if="tab === 'body'">
          <Button size="small" type="text" @click="pretty = !pretty">
            <template #icon>
<IconifyIcon
                :icon="pretty ? 'lucide:code-xml' : 'lucide:braces'"
            />
</template>
            {{ pretty ? '原始内容' : '格式化' }}
</Button><Button size="small" type="text" @click="wrap = !wrap">
            <template #icon><IconifyIcon icon="lucide:wrap-text" /></template>
            {{ wrap ? '关闭换行' : '自动换行' }}
</Button><Button size="small" type="text" @click="copyBody">
            <template #icon><IconifyIcon icon="lucide:copy" /></template>
            复制
          </Button>
        </template>
      </div>
      <pre
        v-if="tab === 'body'"
        class="response-panel__body"
        :class="{ 'response-panel__body--wrap': wrap }"
        >{{ displayedBody || '响应体为空' }}</pre>
      <div v-else class="response-panel__headers">
        <div
          v-for="(header, index) in tab === 'headers'
            ? response.headers
            : cookies"
          :key="index"
          class="response-panel__header"
        >
          <strong>{{ header.name }}</strong><span>{{ header.value }}</span>
        </div>
        <Empty
          v-if="tab === 'cookies' && !cookies.length"
          description="没有响应 Cookie"
        />
      </div>
      <details
        v-if="response.redirectChain.length"
        class="response-panel__redirects"
      >
        <summary>重定向记录（{{ response.redirectChain.length }}）</summary>
        <div v-for="(redirect, index) in response.redirectChain" :key="index">
          {{ redirect.statusCode }} {{ redirect.url }}
        </div>
      </details>
    </template>
    <div v-else class="response-panel__empty">
      <Empty description="暂无响应结果" /><span>点击“发送”获取返回结果</span>
    </div>
  </div>
</template>

<style scoped>
.response-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  padding: 12px 14px;
  overflow: auto;
}

.response-panel__empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  justify-content: center;
  min-height: 140px;
  color: hsl(var(--muted-foreground));
}

.response-panel__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: center;
  margin-bottom: 10px;
  font-size: 12px;
}

.response-panel__url {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.response-panel__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  border-bottom: 1px solid hsl(var(--border));
}

.response-panel__tabs {
  display: flex;
  flex: 1;
  gap: 20px;
}

.response-panel__tabs button {
  padding: 8px 0;
  background: transparent;
  border-bottom: 2px solid transparent;
}

.response-panel__tabs button.active {
  color: hsl(var(--primary));
  border-bottom-color: hsl(var(--primary));
}

.response-panel__body {
  flex: 1;
  min-height: 100px;
  padding: 12px 4px;
  margin: 0;
  overflow: auto;
  font-size: 12px;
  line-height: 1.7;
}

.response-panel__body--wrap {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.response-panel__headers {
  flex: 1;
  overflow: auto;
}

.response-panel__header {
  display: grid;
  grid-template-columns: minmax(100px, 1fr) 4fr;
  gap: 16px;
  padding: 10px 4px;
  overflow-wrap: anywhere;
  border-bottom: 1px solid hsl(var(--border));
}

.response-panel__redirects {
  padding-top: 8px;
  font-size: 12px;
  overflow-wrap: anywhere;
}
</style>
