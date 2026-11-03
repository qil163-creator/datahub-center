package cn.iocoder.yudao.framework.etl.core.script;

import java.io.*;
import java.util.*;

/** broker 和可信原生 launcher 共用的有界二进制协议；数据继续复用原有无损行 codec。 */
final class EtlScriptBrokerProtocol {
    static final int VERSION = 1, OPEN = 1, BATCH = 2, CLOSE = 3, CANCEL = 4, ACK = 5, HTTP = 6;
    static final int MAX_BYTES = 17 * 1024 * 1024, MAX_ROWS = 10_000;
    private EtlScriptBrokerProtocol() { }

    record Claim(String binding, String sourceHash, String epoch, String task, String workerGroup, String engine,
                 String run, String partition) {
        Claim {
            if (binding == null || !binding.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}") || sourceHash == null || !sourceHash.matches("[a-f0-9]{64}")
                    || epoch == null || !epoch.matches("[a-f0-9-]{36}") || task == null || !task.matches("[A-Za-z0-9_.-]{1,128}")
                    || workerGroup == null || !workerGroup.matches("[A-Za-z0-9_.-]{1,100}") || !Set.of("SPARK", "FLINK").contains(engine)
                    || run == null || !run.matches("[A-Za-z0-9_.-]{1,128}") || partition == null || !partition.matches("[A-Za-z0-9_.-]{1,128}"))
                throw new IllegalArgumentException("脚本运行实例声明无效");
        }
        void write(DataOutputStream output) throws IOException {
            for (String value : List.of(binding, sourceHash, epoch, task, workerGroup, engine, run, partition)) EtlScriptProtocol.writeText(output, value, 128);
        }
        static Claim read(DataInputStream input) throws IOException {
            return new Claim(EtlScriptProtocol.readText(input, 128), EtlScriptProtocol.readText(input, 128), EtlScriptProtocol.readText(input, 128), EtlScriptProtocol.readText(input, 128),
                    EtlScriptProtocol.readText(input, 128), EtlScriptProtocol.readText(input, 128), EtlScriptProtocol.readText(input, 128), EtlScriptProtocol.readText(input, 128));
        }
    }
    static byte[] request(int operation, EtlScriptProtocol.Encoder body) throws IOException {
        return EtlScriptProtocol.encode(MAX_BYTES, output -> { output.writeInt(VERSION); output.writeByte(operation); body.write(output); });
    }
    static byte[] okay(EtlScriptProtocol.Encoder body) throws IOException {
        return EtlScriptProtocol.encode(MAX_BYTES, output -> { output.writeInt(VERSION); output.writeByte(EtlScriptProtocol.OK); body.write(output); });
    }
    static byte[] failed() {
        try { return EtlScriptProtocol.encode(128, output -> { output.writeInt(VERSION); output.writeByte(EtlScriptProtocol.FAILED); EtlScriptProtocol.writeText(output, "SCRIPT_REQUEST_DENIED_OR_FAILED", 64); }); }
        catch (IOException impossible) { throw new IllegalStateException("脚本协议不可用"); }
    }
}
