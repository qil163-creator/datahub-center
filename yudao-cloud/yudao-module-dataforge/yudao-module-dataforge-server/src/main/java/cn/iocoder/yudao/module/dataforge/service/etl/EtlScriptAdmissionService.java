package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.ETL_PIPELINE_GRAPH_INVALID;

/** 负责 DataForge 准入与安全错误映射；进程执行由框架运行器负责。 */
@Service
public class EtlScriptAdmissionService {

    @Resource
    private EtlScriptRunner scriptRunner;

    /**
     * 校验可用。
     *
     * @param nodes 节点
     */
    public void requireAvailable(List<EtlNodeDef> nodes) {
        if (nodes.stream().noneMatch(node -> "java-script".equals(node.getToolCode())
                || "js-script".equals(node.getToolCode()))) {
            return;
        }
        try {
            scriptRunner.ensureAvailable();
        } catch (IllegalStateException | IllegalArgumentException | SecurityException unavailable) {
            throw exception(ETL_PIPELINE_GRAPH_INVALID,
                    "Java/JavaScript 子进程执行器尚未就绪，请联系管理员配置后再发布或运行");
        }
    }
}
