package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.framework.etl.core.file.FileSchemaReader;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationFileSchemaModels.*;
import cn.iocoder.yudao.module.infra.api.file.ManagedFileApi;
import cn.iocoder.yudao.module.infra.api.file.dto.UserFileContentRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.Locale;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.INTEGRATION_CONFIG_INVALID;

/** 业务层只负责上传归属和请求约束；文件格式与采样复用ETL组件。 */
@Service
public class IntegrationFileSchemaService {
    @Resource private ManagedFileApi files;

    public Response parse(Request request) {
        Long actorId = getLoginUserId();
        if (actorId == null || actorId <= 0) throw invalid("请登录后解析自己的上传文件");
        if (request.getFileId() == null || request.getFileId() <= 0
                || request.getFormat() == null || !java.util.Set.of("CSV", "EXCEL", "JSONL").contains(request.getFormat())
                || request.getStartRow() == null || request.getStartRow() < 1 || request.getStartRow() > 10000
                || request.getStartColumn() == null || request.getStartColumn() < 1 || request.getStartColumn() > 256
                || request.getSheetName() != null && (request.getSheetName().length() > 128
                    || request.getSheetName().codePoints().anyMatch(Character::isISOControl)))
            throw invalid("文件解析参数无效");
        UserFileContentRespDTO upload;
        try { upload = files.readUserUpload(request.getFileId(), actorId).getCheckedData(); }
        catch (RuntimeException unavailable) { throw invalid("文件不可读取，请使用自己上传且不超过50MiB的文件"); }
        if (upload == null || upload.getContent() == null || upload.getSize() == null
                || upload.getSize() <= 0 || upload.getSize() > FileSchemaReader.LIMITS.maxBytes()
                || upload.getContent().length != upload.getSize()) throw invalid("文件大小或内容校验失败");
        String name = upload.getName() == null ? "" : upload.getName().toLowerCase(Locale.ROOT);
        String extension = name.substring(name.lastIndexOf('.') + 1);
        try {
            FileSchemaReader.Result result;
            if ("CSV".equals(request.getFormat())) {
                if (!"csv".equals(extension)) throw invalid("请选择csv文件");
                result = FileSchemaReader.csv(upload.getContent());
            } else if ("EXCEL".equals(request.getFormat())) {
                if (!java.util.Set.of("xls", "xlsx").contains(extension)) throw invalid("请选择xls或xlsx文件");
                result = FileSchemaReader.excel(upload.getContent(), extension, request.getStartRow(), request.getStartColumn(), request.getSheetName());
            } else {
                if (!java.util.Set.of("jsonl", "ndjson").contains(extension)) throw invalid("请选择jsonl或ndjson文件");
                result = FileSchemaReader.jsonl(upload.getContent());
            }
            return new Response(result.fields().stream().map(field -> new SchemaField(field.name(), field.dataType(),
                    field.length(), field.precision(), field.scale(), field.path())).toList(), result.warnings(), true);
        } catch (IllegalArgumentException validation) { throw invalid(validation.getMessage()); }
        catch (cn.iocoder.yudao.framework.common.exception.ServiceException validation) { throw validation; }
        catch (IOException | RuntimeException malformed) { throw invalid("文件格式无效或超过解析限额"); }
    }

    private static cn.iocoder.yudao.framework.common.exception.ServiceException invalid(String message) {
        return exception(INTEGRATION_CONFIG_INVALID, message);
    }
}
