package cn.iocoder.yudao.framework.etl.core.script;

import java.io.*;
import java.util.*;

/** 检查点只保存引用、位点、明确窗口及有界数据；不使用 Java 对象反序列化。 */
public final class EtlNativeScriptState {
    public static final int MAX_BATCH_BYTES = 8 * 1024 * 1024;
    private static final int MAX_STATE_BYTES = MAX_BATCH_BYTES + 4096;
    private EtlNativeScriptState() { }

    public record Snapshot(String scope, String partition, String run, String session, long nextSequence,
                           long timerDeadline, List<Map<String, Object>> rows) {
        public Snapshot {
            if (scope == null || !scope.matches("[a-f0-9]{64}") || partition == null || !partition.matches("[A-Za-z0-9_.-]{1,128}")
                    || run == null || !run.matches("[a-f0-9-]{36}") || session == null || !(session.isEmpty() || session.matches("[a-f0-9-]{36}"))
                    || nextSequence < 0 || timerDeadline < 0 || rows == null || rows.size() > 10000
                    || session.isEmpty() && nextSequence != 0 || rows.isEmpty() && timerDeadline != 0) throw invalid();
            rows = rows.stream().map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
        }
    }

    public static int rowBytes(Map<String, Object> row) {
        try { return EtlScriptProtocol.encode(MAX_BATCH_BYTES, out -> EtlScriptProtocol.writeRows(out, List.of(row), 1, MAX_BATCH_BYTES)).length - Integer.BYTES; }
        catch (IOException | RuntimeException failure) { throw invalid(); }
    }
    public static int batchBytes(List<Map<String, Object>> rows) {
        try { return EtlScriptProtocol.encode(MAX_BATCH_BYTES, out -> EtlScriptProtocol.writeRows(out, rows, 10000, MAX_BATCH_BYTES)).length; }
        catch (IOException | RuntimeException failure) { throw invalid(); }
    }
    public static byte[] encode(Snapshot state) {
        batchBytes(state.rows());
        try {
            return EtlScriptProtocol.encode(MAX_STATE_BYTES, out -> {
                out.writeInt(1);
                for (String value : List.of(state.scope(), state.partition(), state.run(), state.session())) EtlScriptProtocol.writeText(out, value, 128);
                out.writeLong(state.nextSequence()); out.writeLong(state.timerDeadline());
                EtlScriptProtocol.writeRows(out, state.rows(), 10000, MAX_BATCH_BYTES);
            });
        } catch (IOException failure) { throw invalid(); }
    }
    public static Snapshot decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_STATE_BYTES) throw invalid();
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 1) throw invalid();
            String scope = EtlScriptProtocol.readText(in, 128), partition = EtlScriptProtocol.readText(in, 128), run = EtlScriptProtocol.readText(in, 128), session = EtlScriptProtocol.readText(in, 128);
            var state = new Snapshot(scope, partition, run, session, in.readLong(), in.readLong(), EtlScriptProtocol.readRows(in, 10000, MAX_BATCH_BYTES));
            EtlScriptProtocol.requireEnd(in); batchBytes(state.rows()); return state;
        } catch (IOException | RuntimeException failure) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("脚本检查点或批次数据无效，或超过明确的字节/行数预算"); }
}
