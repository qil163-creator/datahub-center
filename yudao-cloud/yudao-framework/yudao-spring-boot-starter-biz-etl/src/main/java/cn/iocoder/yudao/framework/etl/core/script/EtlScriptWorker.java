package cn.iocoder.yudao.framework.etl.core.script;

import cn.hutool.json.JSONUtil;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

import javax.script.ScriptEngine;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 固定入口。绝不启动 Spring、业务服务、凭据或父应用上下文。 */
public final class EtlScriptWorker {

    private static boolean workerProcess;

    /**
     * 返回工作进程进程。
     */
    static boolean isWorkerProcess() {
        return workerProcess;
    }

    /**
     * 初始化 {@code EtlScriptWorker}。
     */
    private EtlScriptWorker() {
    }

    /**
     * 源码、数据行、编译器详情或不受控堆栈信息不得进入进程标准错误输出。
     */
    public static void main(String[] ignored) {
        workerProcess = true;
        try {
            run(new FileInputStream(FileDescriptor.in), new FileOutputStream(FileDescriptor.out));
        } catch (Throwable failure) {
            // 源码、数据行、编译器详情或不受控堆栈信息不得进入进程标准错误输出。
            System.exit(71);
        }
    }

    /**
     * 按工作进程协议处理当前任务并记录运行结果。
     */
    private static void run(InputStream input, OutputStream output) throws Exception {
        var init = EtlScriptProtocol.readFrame(input, 1024 * 1024 + 512);
        if (init.readUnsignedByte() != EtlScriptProtocol.INIT || init.readInt() != 1) {
            throw new IOException("Unsupported worker protocol");
        }
        String language = EtlScriptProtocol.readText(init, 16);
        String source = EtlScriptProtocol.readText(init, 1024 * 1024);
        int batchBytes = init.readInt(), resultBytes = init.readInt(), batchRows = init.readInt(), resultRows = init.readInt();
        int logBytes = init.readInt();
        if (batchBytes < 1024 || batchBytes > 8 * 1024 * 1024 || resultBytes < 1024 || resultBytes > 8 * 1024 * 1024
                || batchRows < 1 || batchRows > 10_000 || resultRows < 1 || resultRows > 10_000
                || logBytes < 1 || logBytes > 64 * 1024) {
            throw new IOException("Invalid worker limits");
        }
        boolean httpEnabled = init.available() > 0 && init.readBoolean();
        EtlScriptProtocol.requireEnd(init);
        if (httpEnabled) EtlScriptWorkerHttp.install(input, output);
        var discardedLogs = new BoundedLogSink(logBytes);
        var capture = new PrintStream(discardedLogs, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        EtlJavaScript javaScript = null;
        tech.qiantong.qdata.common.core.base.EtlBase qdataScript = null;
        ScriptEngine jsScript = null;
        try {
            if ("CHECK_JAVA".equals(language)) {
                List<String> errors = EtlJavaCompiler.check(source);
                EtlScriptProtocol.writeFrame(output, EtlScriptProtocol.encode(resultBytes, out -> {
                    out.writeByte(EtlScriptProtocol.CHECK_ERRORS);
                    out.writeInt(errors.size());
                    for (String error : errors) {
                        EtlScriptProtocol.writeText(out, error, 256);
                    }
                }));
                return;
            } else if ("JAVA".equals(language)) {
                javaScript = EtlJavaCompiler.compileScript(source);
            } else if ("QDATA_JAVA".equals(language) || "QDATA_TOKEN".equals(language)) {
                qdataScript = EtlJavaCompiler.compileQdataScript(source);
            } else if ("JS".equals(language)) {
                jsScript = new NashornScriptEngineFactory().getScriptEngine("--language=es6");
                jsScript.getContext().setWriter(new PrintWriter(capture));
                jsScript.getContext().setErrorWriter(new PrintWriter(capture));
                jsScript.eval(source);
                if (!Boolean.TRUE.equals(jsScript.eval("typeof execute === 'function'"))) {
                    throw new IllegalStateException("Missing execute function");
                }
            } else {
                throw new IllegalArgumentException("Unsupported worker language");
            }
            if (discardedLogs.exceeded()) {
                throw new IOException("Script log limit exceeded");
            }
            writeOkay(output, resultBytes, List.of(), resultRows);
        } catch (Throwable failure) {
            writeFailure(output, resultBytes, "INITIALIZATION_FAILED");
            return;
        }

        while (true) {
            var request = EtlScriptProtocol.readFrame(input, batchBytes);
            int operation = request.readUnsignedByte();
            if (operation == EtlScriptProtocol.STOP) {
                EtlScriptProtocol.requireEnd(request);
                writeOkay(output, resultBytes, List.of(), resultRows);
                return;
            }
            if (operation != EtlScriptProtocol.BATCH) {
                throw new IOException("Unsupported worker operation");
            }
            List<Map<String, Object>> rows = EtlScriptProtocol.readRows(request, batchRows, batchBytes);
            EtlScriptProtocol.requireEnd(request);
            try {
                List<Map<String, Object>> result;
                if (qdataScript != null) {
                    result = executeQdata(qdataScript, rows, "QDATA_TOKEN".equals(language), resultRows);
                } else if (javaScript != null) {
                    result = javaScript.execute(rows);
                } else {
                    jsScript.put("__rowsJson", JSONUtil.toJsonStr(rows));
                    Object json = jsScript.eval("JSON.stringify(execute(JSON.parse(__rowsJson)) || [])");
                    result = parseJsResult(json, resultRows, resultBytes);
                }
                if (discardedLogs.exceeded()) {
                    writeFailure(output, resultBytes, "LOG_LIMIT_EXCEEDED");
                    return;
                }
                writeOkay(output, resultBytes, result, resultRows);
            } catch (Throwable failure) {
                writeFailure(output, resultBytes, "EXECUTION_FAILED");
                return;
            }
        }
    }

    /** 不通过 JSON 字符串中转，保留 Long、Decimal、日期以及原站可增删行的批次语义。 */
    private static List<Map<String, Object>> executeQdata(tech.qiantong.qdata.common.core.base.EtlBase script,
            List<Map<String, Object>> rows, boolean token, int maximumRows) throws Exception {
        var data = new com.alibaba.fastjson2.JSONObject();
        var batch = new com.alibaba.fastjson2.JSONArray();
        for (var row : rows) batch.add(new com.alibaba.fastjson2.JSONObject(row));
        data.put("data", batch);
        String status = script.execute(data, null);
        if (token) {
            if (status == null || status.isBlank() || status.equals("0") || status.length() > 8192 || status.codePoints().anyMatch(Character::isISOControl)) throw new IllegalStateException("认证脚本没有返回有效结果");
            return List.of(Map.of("__token", status));
        }
        if (!"1".equals(status)) throw new IllegalStateException("脚本请求终止任务或返回了无效状态");
        Object result = data.get("data");
        if (!(result instanceof List<?> list) || list.size() > maximumRows) throw new IllegalStateException("脚本输出行超限或结构无效");
        var output = new ArrayList<Map<String, Object>>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) throw new IllegalStateException("脚本输出必须是对象行");
            var converted = new LinkedHashMap<String, Object>();
            for (var entry : row.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalStateException("脚本输出字段名无效");
                converted.put(key, entry.getValue());
            }
            output.add(converted);
        }
        return output;
    }

    /**
     * 解析JavaScript结果。
     */
    private static List<Map<String, Object>> parseJsResult(Object json, int maximumRows,
            int maximumBytes) throws IOException {
        if (json == null) {
            return List.of();
        }
        if (!(json instanceof String text) || text.length() > maximumBytes
                || text.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IOException("Script result limit exceeded");
        }
        var array = JSONUtil.parseArray(text);
        if (array.size() > maximumRows) {
            throw new IOException("Script result row limit exceeded");
        }
        var rows = new ArrayList<Map<String, Object>>(array.size());
        for (Object item : array) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IOException("Script must return object rows");
            }
            var row = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IOException("Script field name must be a string");
                }
                row.put(key, entry.getValue());
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 写入成功状态。
     */
    private static void writeOkay(OutputStream output, int maximumBytes, List<Map<String, Object>> rows,
            int maximumRows)
            throws IOException {
        EtlScriptProtocol.writeFrame(output, EtlScriptProtocol.encode(maximumBytes, out -> {
            out.writeByte(EtlScriptProtocol.OK);
            EtlScriptProtocol.writeRows(out, rows, maximumRows, maximumBytes);
        }));
    }

    /**
     * 写入失败信息。
     */
    private static void writeFailure(OutputStream output, int maximumBytes, String code) throws IOException {
        EtlScriptProtocol.writeFrame(output, EtlScriptProtocol.encode(maximumBytes, out -> {
            out.writeByte(EtlScriptProtocol.FAILED);
            EtlScriptProtocol.writeText(out, code, 64);
        }));
    }

    /** 丢弃日志数据，不将其返回审计或界面；节点总量仍受限。 */
    private static final class BoundedLogSink extends OutputStream {

        private final long maximum;

        private long count;

        /**
         * 初始化 {@code BoundedLogSink}。
         */
        private BoundedLogSink(long maximum) {
            this.maximum = maximum;
        }

        /**
         * 检查当前计数是否超过允许的最大值。
         */
        private synchronized boolean exceeded() {
            return count > maximum;
        }

        /**
         * 累计被丢弃日志的字节数，用于检查日志输出预算。
         */
        @Override
        public synchronized void write(int value) {
            count = Math.min(maximum + 1, count + 1);
        }

        /**
         * 累计被丢弃日志的字节数，用于检查日志输出预算。
         */
        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            count = Math.min(maximum + 1, count + length);
        }
    }
}
