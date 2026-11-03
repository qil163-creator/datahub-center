package cn.iocoder.yudao.framework.dolphinscheduler.api;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerException;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinSchedulerHttp;
import cn.iocoder.yudao.framework.dolphinscheduler.model.TaskLogPage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务日志接口，对应 OpenAPI tag：log related operation。
 */
public class LogApi {

    public static final int MAX_PAGE_LINES = 1000;

    public static final int MAX_PAGE_BYTES = 128 * 1024;

    private final DolphinSchedulerHttp http;

    /**
     * 初始化 {@code LogApi}。
     */
    public LogApi(DolphinSchedulerHttp http) {
        this.http = http;
    }

    /**
     * downloadTaskLog
     * <p>下载任务实例日志</p>
     * <p>{@code GET /log/download-log}</p>
     * @param taskInstanceId 必填，任务实例编号
     * @return DolphinScheduler 统一 {@code Result}
     */
    public byte[] downloadTaskLog(Integer taskInstanceId) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (taskInstanceId != null) {
            params.put("taskInstanceId", taskInstanceId);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/log/download-log", pathVars);
        return http.download("GET", path, params);
    }

    /**
     * queryLog
     * <p>查询任务实例日志</p>
     * <p>{@code GET /log/detail}</p>
     * @param taskInstanceId 必填，任务实例编号
     * @param skipLineNum 必填，跳过的行数
     * @param limit 必填，数量上限
     * @return DolphinScheduler 统一 {@code Result}
     */
    public JSONObject queryLog(Integer taskInstanceId, Integer skipLineNum, Integer limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (taskInstanceId != null) {
            params.put("taskInstanceId", taskInstanceId);
        }
        if (skipLineNum != null) {
            params.put("skipLineNum", skipLineNum);
        }
        if (limit != null) {
            params.put("limit", limit);
        }
        Map<String, Object> pathVars = new LinkedHashMap<>();
        String path = DolphinSchedulerHttp.fillPath("/log/detail", pathVars);
        return http.get(path, params);
    }

    /**
     * Reads a bounded page with a physical-file cursor. DS 3.4.2 includes a synthetic
     * host/path header in its first-page lineNum and may stop early at its byte bound;
     * neither lineNum nor a short page is a reliable cursor/end-of-log indicator.
     * The raw API remains available for callers needing the original response.
     * Authorization and sensitive-content redaction belong to the caller.
     */
    public TaskLogPage queryLogPage(Integer taskInstanceId, Integer skipLineNum, Integer limit) {
        if (taskInstanceId == null || taskInstanceId <= 0 || skipLineNum == null || skipLineNum < 0
                || limit == null || limit < 1 || limit > MAX_PAGE_LINES
                || skipLineNum > Integer.MAX_VALUE - limit) {
            throw new IllegalArgumentException("Invalid task log page parameters");
        }
        JSONObject response = queryLog(taskInstanceId, skipLineNum, limit);
        if (response == null || response.getInt("code", -1) != DolphinSchedulerHttp.SUCCESS_CODE
                || Boolean.FALSE.equals(response.getBool("success")) || Boolean.TRUE.equals(
                response.getBool("failed"))) {
            throw new DolphinSchedulerException("DolphinScheduler task log request failed");
        }
        Object rawData = response.get("data");
        if (!(rawData instanceof JSONObject data) || !(data.get("message") instanceof String message)) {
            throw new DolphinSchedulerException("DolphinScheduler task log response has no log content");
        }
        StringBuilder content = new StringBuilder();
        int readLines = 0;
        int bytes = 0;
        boolean truncated = false;
        try (BufferedReader reader = new BufferedReader(new StringReader(message))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first && skipLineNum == 0 && line.startsWith("[LOG-PATH]: ")
                        && line.contains(", [HOST]: ")) {
                    first = false;
                    continue;
                }
                first = false;
                if (readLines == limit) {
                    truncated = true;
                    break;
                }
                int separatorBytes = readLines == 0 ? 0 : 1;
                int remaining = MAX_PAGE_BYTES - bytes - separatorBytes;
                if (remaining <= 0) {
                    truncated = true;
                    break;
                }
                if (separatorBytes != 0) {
                    content.append('\n');
                }
                String bounded = utf8Prefix(line, remaining);
                content.append(bounded);
                bytes += separatorBytes + bounded.getBytes(StandardCharsets.UTF_8).length;
                readLines++;
                if (bounded.length() != line.length()) {
                    // A physical line cannot be resumed with DS's line cursor. Consume
                    // this line and report clipping, without skipping following lines.
                    truncated = true;
                    break;
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read in-memory task log", ex);
        }
        return new TaskLogPage(content.toString(), readLines, skipLineNum + readLines,
                readLines > 0, truncated);
    }

    /**
     * 按 UTF-8 字节预算截取文本前缀，保留完整字符边界。
     */
    private static String utf8Prefix(String value, int maximumBytes) {
        int end = 0;
        int bytes = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int width = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            if (bytes + width > maximumBytes) {
                break;
            }
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }

}
