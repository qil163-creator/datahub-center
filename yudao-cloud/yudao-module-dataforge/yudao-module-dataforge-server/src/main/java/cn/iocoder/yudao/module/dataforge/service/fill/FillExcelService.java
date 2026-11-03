package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillExcelImportConfirmReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillExcelImportPreviewRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationApplyRespVO;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * 在线填报-Excel 导入导出 Service 接口
 *
 * 模板与导出按工作项绑定的不可变模板版本生成（非当前草稿字段配置）；
 * 导入采用两段式：预检不落库，确认后经草稿操作通道原子应用并广播协同
 *
 * @author Li
 */
public interface FillExcelService {

    /**
     * 下载 Excel 填报模板（含必填标记与字典下拉，仅输入列）
     */
    void downloadTemplate(Long workItemId, HttpServletResponse response);

    /**
     * 导入预检：解析 + 逐行逐字段校验，不写草稿
     */
    FillExcelImportPreviewRespVO previewImport(Long workItemId, MultipartFile file);

    /**
     * 导入确认：将预检行经草稿操作通道原子写入（追加或整体替换）
     *
     * @return 应用结果（新 serverVersion、行数）
     */
    FillOperationApplyRespVO confirmImport(Long workItemId, FillExcelImportConfirmReqVO reqVO);

    /**
     * 导出当前草稿数据为 Excel
     */
    void exportDraft(Long workItemId, HttpServletResponse response);

}
