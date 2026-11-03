package cn.iocoder.yudao.module.dataforge.service.fileresource;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.file.core.remote.RemoteFileClient;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseDiagnosisRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.FILE_RESOURCE_DISABLED;

/** 文件连接诊断，写入探测仅操作本次随机创建的临时文件。 */
@Component
public class FileResourceDiagnosis {

    @Resource
    private FileResourceConnectionResolver connections;

    public DatabaseDiagnosisRespVO diagnose(DatabaseDO db, DatabaseTypeDO type, String password,
                                            boolean includeWriteChecks) {
        long start = System.nanoTime();
        DatabaseDiagnosisRespVO result = new DatabaseDiagnosisRespVO();
        result.setDiagnosisId(UUID.randomUUID().toString());
        result.setCheckedAt(LocalDateTime.now());
        boolean connected = false;
        List<DatabaseDiagnosisRespVO.Item> writes = skippedWrites(includeWriteChecks
                ? "连接或根目录未通过检查，未执行写入探测" : "本次未授权写入检查");
        try (RemoteFileClient client = connections.open(db, type, password)) {
            if (!client.stat("/").directory()) throw new IOException("根目录无效");
            client.list("/");
            connected = true;
            if (includeWriteChecks) writes = checkWrites(client);
        } catch (ServiceException failure) {
            if (FILE_RESOURCE_DISABLED.getCode().equals(failure.getCode())) throw failure;
        } catch (Exception ignored) { /* 不回显外部服务的地址、凭据或异常。 */ }

        var read = item("FILE_ROOT_READ", "连接与根目录", "连接认证、配置根目录与目录读取",
                connected ? "PASS" : "FAIL",
                connected ? "连接及根目录读取成功" : "连接或根目录读取失败，请检查配置、服务状态和权限",
                connected ? "仅验证连接和根目录读取，不代表写入权限" : "可能影响文件管理对根目录的访问");
        var groups = List.of(group("FILE_CONNECTION", "文件连接", "连接与根目录读取", List.of(read)),
                group("WRITE", "文件写入权限", "仅验证本次随机临时文件的操作权限", writes));
        result.setGroups(groups);
        result.setConnectionSuccessful(connected);
        result.setNormalCount(groups.stream().mapToInt(DatabaseDiagnosisRespVO.Group::getNormalCount).sum());
        result.setWarningCount(groups.stream().mapToInt(DatabaseDiagnosisRespVO.Group::getWarningCount).sum());
        result.setErrorCount(groups.stream().mapToInt(DatabaseDiagnosisRespVO.Group::getErrorCount).sum());
        result.setSkippedCount(groups.stream().mapToInt(DatabaseDiagnosisRespVO.Group::getSkippedCount).sum());
        result.setTotalCount(groups.stream().mapToInt(DatabaseDiagnosisRespVO.Group::getTotalCount).sum());
        result.setOverallStatus(status(result.getNormalCount(), result.getWarningCount(), result.getErrorCount()));
        result.setDurationMs((System.nanoTime() - start) / 1_000_000);
        return result;
    }

    private static List<DatabaseDiagnosisRespVO.Item> checkWrites(RemoteFileClient client) {
        var items = skippedWrites("前置检查未通过，未执行本项");
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        String original = "/.datahub-diagnosis-" + token;
        String renamed = original + "-renamed";
        byte[] first = (token + ":create").getBytes(StandardCharsets.UTF_8);
        byte[] second = (token + ":overwrite").getBytes(StandardCharsets.UTF_8);
        int stage = 0;
        try {
            requireAbsent(client, original);
            requireAbsent(client, renamed);
            client.upload(original, new ByteArrayInputStream(first), first.length, false);
            requireContent(client, original, first);
            pass(items.get(stage++));
            client.upload(original, new ByteArrayInputStream(second), second.length, true);
            requireContent(client, original, second);
            pass(items.get(stage++));
            client.rename(original, renamed);
            requireAbsent(client, original);
            requireContent(client, renamed, second);
            pass(items.get(stage++));
            client.delete(renamed);
            requireAbsent(client, renamed);
            pass(items.get(stage));
        } catch (Exception ignored) {
            var failed = items.get(stage);
            failed.setStatus("FAIL");
            failed.setReasonCode(null);
            failed.setMessage("操作或结果校验失败，已尝试清理，可能存在探测残留，请检查连接根目录");
            failed.setImpact("本项权限未通过验证，远程临时文件或备份可能需要人工清理");
            cleanupOwned(client, original, first, second);
            cleanupOwned(client, renamed, first, second);
        }
        return items;
    }

    private static void requireAbsent(RemoteFileClient client, String path) throws IOException {
        if (client.list("/").stream().anyMatch(entry -> path.equals(entry.path()))) {
            throw new IOException("探测目标已存在");
        }
        try {
            client.stat(path);
        } catch (FileNotFoundException absent) {
            return;
        }
        throw new IOException("探测目标已存在");
    }

    private static void requireContent(RemoteFileClient client, String path, byte[] expected) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        client.download(path, output, 1024);
        if (!Arrays.equals(expected, output.toByteArray())) throw new IOException("探测内容不一致");
    }

    private static void cleanupOwned(RemoteFileClient client, String path, byte[] first, byte[] second) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            client.download(path, output, 1024);
            byte[] actual = output.toByteArray();
            if (Arrays.equals(actual, first) || Arrays.equals(actual, second)) client.delete(path);
        } catch (Exception ignored) { /* 结果不确定时保留文件并提示人工检查。 */ }
    }

    private static List<DatabaseDiagnosisRespVO.Item> skippedWrites(String reason) {
        List<DatabaseDiagnosisRespVO.Item> items = new ArrayList<>();
        for (String[] spec : List.of(
                new String[]{"FILE_CREATE", "上传", "创建随机临时文件并读回"},
                new String[]{"FILE_OVERWRITE", "覆盖", "覆盖临时文件并读回"},
                new String[]{"FILE_RENAME", "重命名", "重命名临时文件并验证"},
                new String[]{"FILE_DELETE", "删除", "删除临时文件并确认不存在"})) {
            var item = item(spec[0], spec[1], spec[2], "SKIPPED", reason, "本项权限尚未验证");
            item.setReasonCode("UNVERIFIED");
            items.add(item);
        }
        return items;
    }

    private static void pass(DatabaseDiagnosisRespVO.Item item) {
        item.setStatus("PASS");
        item.setReasonCode(null);
        item.setMessage("临时文件操作及结果验证通过");
        item.setImpact("仅表示当前连接根目录内的本项操作通过");
    }

    private static DatabaseDiagnosisRespVO.Item item(String code, String name, String content,
                                                     String status, String message, String impact) {
        var item = new DatabaseDiagnosisRespVO.Item();
        item.setCheckCode(code); item.setCheckName(name); item.setCheckContent(content);
        item.setStatus(status); item.setMessage(message); item.setImpact(impact);
        return item;
    }

    private static DatabaseDiagnosisRespVO.Group group(String code, String name, String description,
                                                        List<DatabaseDiagnosisRespVO.Item> items) {
        var group = new DatabaseDiagnosisRespVO.Group();
        group.setGroupCode(code); group.setGroupName(name); group.setDescription(description); group.setItems(items);
        group.setNormalCount(count(items, "PASS")); group.setWarningCount(count(items, "WARN"));
        group.setErrorCount(count(items, "FAIL")); group.setSkippedCount(count(items, "SKIPPED"));
        group.setTotalCount(items.size());
        group.setStatus(status(group.getNormalCount(), group.getWarningCount(), group.getErrorCount()));
        return group;
    }

    private static int count(List<DatabaseDiagnosisRespVO.Item> items, String state) {
        return (int) items.stream().filter(item -> state.equals(item.getStatus())).count();
    }

    private static String status(int passed, int warnings, int failed) {
        return failed > 0 ? "FAIL" : warnings > 0 ? "WARN" : passed > 0 ? "PASS" : "SKIPPED";
    }
}
