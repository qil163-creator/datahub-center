package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class EtlCompilerUiFixtures {
    private EtlCompilerUiFixtures() { }
    static JSONObject configs() throws IOException {
        for (Path root = Path.of("").toAbsolutePath(); root != null; root = root.getParent()) {
            Path fixture = root.resolve("deploy/dolphinscheduler-local/verification/components-20261102-ui-default-payloads.json");
            if (Files.isRegularFile(fixture)) return JSONUtil.parseObj(Files.readString(fixture)).getJSONObject("configs");
        }
        throw new IOException("未找到实际编辑器配置验收样例");
    }
}
