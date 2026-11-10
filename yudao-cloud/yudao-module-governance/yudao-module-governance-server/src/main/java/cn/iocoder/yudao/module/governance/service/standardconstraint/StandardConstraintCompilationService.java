package cn.iocoder.yudao.module.governance.service.standardconstraint;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem.StandardCodeItemDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_CONSTRAINT_COMPILE_FAILED;

/** 解析有效代码集，再委托纯约束编译器处理。 */
@Service
@RequiredArgsConstructor
public class StandardConstraintCompilationService {

    private final StandardConstraintCompiler compiler;

    private final StandardCodeSetMapper codeSetMapper;

    private final StandardCodeItemMapper codeItemMapper;

    /** 在事务内按编号顺序锁定码表，与码项维护串行化。 */
    public void lockCodeSets(List<Integer> ids) {
        ids.stream().distinct().sorted().forEach(codeSetMapper::selectByIdForUpdate);
    }

    /**
     * 读取生效的标准代码集和代码项后编译标准数据元约束。
     */
    public StandardConstraintCompiler.Compilation compile(StandardElementDO element) {
        StandardCodeSetDO codeSet = element != null && element.getCodeSetId() != null
                ? codeSetMapper.selectById(element.getCodeSetId()) : null;
        List<StandardCodeItemDO> items = element != null && element.getCodeSetId() != null
                ? codeItemMapper.selectEnabledByCodeSetId(element.getCodeSetId()) : List.of();
        return compiler.compile(element, codeSet, items);
    }

    /** 在调用方修改生命周期状态之前，以稳定、结构化错误码按拒绝原则处理失败。 */
    public StandardConstraintCompiler.Compilation requireCompilable(StandardElementDO element) {
        StandardConstraintCompiler.Compilation result = compile(element);
        if (!result.compilable()) {
            String summary = result.errors().stream()
                    .map(item -> item.code() + ":" + item.message())
                    .reduce((left, right) -> left + "；" + right).orElse("UNKNOWN");
            throw exception(STANDARD_CONSTRAINT_COMPILE_FAILED, StrUtil.maxLength(summary, 1000));
        }
        return result;
    }

}
