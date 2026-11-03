package cn.iocoder.yudao.module.asset.service.dataquery;

import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryFileRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryFileSaveReqVO;

import java.util.List;

/**
 * 数据查询文件目录：目录平台内可见；脚本可公开或仅创建人可见。
 */
public interface DataQueryFileService {

    /**
     * 创建目录或 SQL 脚本。
     */
    Long createFile(DataQueryFileSaveReqVO reqVO);

    /**
     * 更新目录或 SQL 脚本。
     */
    void updateFile(DataQueryFileSaveReqVO reqVO);

    /**
     * 删除目录或 SQL 脚本。
     */
    void deleteFile(Long id);

    /**
     * 获得目录或 SQL 脚本详情。
     */
    DataQueryFileRespVO getFile(Long id);

    /**
     * 获得目录树扁平列表，不含 SQL 正文。
     */
    List<DataQueryFileRespVO> getFileList();

}
