package cn.iocoder.yudao.framework.seatunnel;

import cn.hutool.http.ContentType;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * SeaTunnel REST 接口。
 *
 * @author zrx
 */
public class SeatunnelRestApi {

    private static final int TIMEOUT_MILLIS = 30_000;

    public static String SERVER_URL = "http://localhost:8080";

    /**
     * 获取全部任务。
     */
    public static JSONArray getAllJobs() {
        try (HttpResponse response = HttpRequest.get(SERVER_URL + RestConstant.RUNNING_JOBS_URL)
                .timeout(TIMEOUT_MILLIS)
                .execute()) {
            String body = checkResponse(response);
            return parseArray(body);
        }
    }

    /**
     * 根据任务 ID 获取任务信息。
     */
    public static JSONObject getJobInfo(String jobId) {
        try (HttpResponse response = HttpRequest.get(
                        SERVER_URL + RestConstant.RUNNING_JOB_URL + "/" + jobId)
                .timeout(TIMEOUT_MILLIS)
                .execute()) {
            String body = checkResponse(response);
            return parseObject(body);
        }
    }

    /**
     * 获取系统监控信息。
     */
    public static JSONArray getSystemMonitor() {
        try (HttpResponse response = HttpRequest.get(
                        SERVER_URL + RestConstant.SYSTEM_MONITORING_INFORMATION)
                .timeout(TIMEOUT_MILLIS)
                .execute()) {
            String body = checkResponse(response);
            if (body != null && body.trim().startsWith("[")) {
                return parseArray(body);
            }
            JSONArray array = new JSONArray();
            array.add(parseObject(body));
            return array;
        }
    }

    /**
     * 提交作业。
     */
    public static JSONObject submitJob(String jobBody, String format) {
        return submitJob(jobBody, format, null);
    }

    /**
     * 使用稳定作业名称提交，便于提交响应丢失后查询并核对远端结果。
     */
    public static JSONObject submitJob(String jobBody, String format, String jobName) {
        String url = SERVER_URL + RestConstant.SUBMIT_JOB_URL;
        if (format != null && !format.trim().isEmpty()) {
            url = url + "?format=" + URLEncoder.encode(format.trim(), StandardCharsets.UTF_8);
        }
        if (jobName != null && !jobName.isBlank()) {
            url += (url.contains("?") ? "&" : "?") + "jobName="
                    + URLEncoder.encode(jobName, StandardCharsets.UTF_8);
        }
        try (HttpResponse response = HttpRequest.post(url)
                .timeout(TIMEOUT_MILLIS)
                .contentType(ContentType.JSON.getValue())
                .body(jobBody)
                .execute()) {
            return parseObject(checkResponse(response));
        }
    }

    /**
     * 停止作业。
     */
    public static JSONObject stopJob(String jobJson) {
        try (HttpResponse response = HttpRequest.post(SERVER_URL + RestConstant.STOP_JOB_URL)
                .timeout(TIMEOUT_MILLIS)
                .contentType(ContentType.JSON.getValue())
                .body(jobJson)
                .execute()) {
            return parseObject(checkResponse(response));
        }
    }

    /**
     * 检查响应。
     */
    private static String checkResponse(HttpResponse response) {
        String body = response.body();
        if (response.getStatus() != 200) {
            // 远端错误响应可能回显完整作业配置和凭据，只保留状态码。
            throw new IllegalStateException("SeaTunnel REST 请求失败，HTTP " + response.getStatus());
        }
        return body;
    }

    /**
     * 解析对象。
     */
    private static JSONObject parseObject(String body) {
        try {
            return JSONUtil.parseObj(body);
        } catch (RuntimeException ex) {
            // 非 JSON 响应也可能回显凭据，不传播底层解析异常。
            throw new IllegalStateException("SeaTunnel REST 响应格式错误");
        }
    }

    /**
     * 解析数组。
     */
    private static JSONArray parseArray(String body) {
        try {
            return JSONUtil.parseArray(body);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("SeaTunnel REST 响应格式错误");
        }
    }

}
