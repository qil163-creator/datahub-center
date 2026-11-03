package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaImportRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaTableRegisterReqVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 数据治理-元数据导入与手工登记 Service
 *
 * 政务无直连场景的两条旁路通道：Excel 数据字典导入（origin=导入）与手工登记（origin=手工），
 * 均转换为统一采集模型后进入与直连采集同一套 diff 引擎，版本、快照、变更链路完全一致；
 * 旁路通道不做删除识别（只增改，报送口径内字段消失按删除留痕）
 *
 * @author Li
 */
public interface MetaImportService {

    /**
     * Excel 数据字典导入（单 Sheet：每行一个字段，表级信息取该表首行）
     */
    MetaImportRespVO importDict(Long datasourceId, Long sourceSystemId, String sourceSystemName,
            MultipartFile file) throws Exception;

    /**
     * 手工登记单张表
     *
     * @return 表元数据编号
     */
    Long registerTable(MetaTableRegisterReqVO reqVO);

    /** 只在事务外解析来源。返回值不包含密码、连接串。 */
    RegistrationSource prepareRegistration(Long datasourceId);

    /** 使用已校验来源在调用方本地事务内登记；不得再次请求来源服务。 */
    Long registerTablePrepared(MetaTableRegisterReqVO reqVO, RegistrationSource source);

    record RegistrationSource(Long datasourceId, String datasourceName, String dbType) {

    }

}
