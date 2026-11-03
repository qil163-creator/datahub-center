package cn.iocoder.yudao.module.infra.service.file;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.infra.controller.admin.file.vo.file.FileCreateReqVO;
import cn.iocoder.yudao.module.infra.controller.admin.file.vo.file.FilePageReqVO;
import cn.iocoder.yudao.module.infra.controller.admin.file.vo.file.FilePresignedUrlRespVO;
import cn.iocoder.yudao.module.infra.dal.dataobject.file.FileDO;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 文件 Service 接口
 *
 * @author 芋道源码
 */
public interface FileService {
    cn.iocoder.yudao.module.infra.api.file.dto.ManagedFileRespDTO createManaged(String owner,
            cn.iocoder.yudao.module.infra.api.file.dto.ManagedFileCreateReqDTO request) throws Exception;
    cn.iocoder.yudao.module.infra.api.file.dto.ManagedFileRespDTO getManaged(String owner, String operationKey) throws Exception;
    byte[] readManaged(String owner, Long fileId, String operationKey) throws Exception;
    cn.iocoder.yudao.module.infra.api.file.dto.UserFileContentRespDTO readUserUpload(Long fileId, Long actorId) throws Exception;
    void deleteManaged(String owner, Long fileId, String operationKey) throws Exception;
    record ManagedCleanupResult(int attempted, int succeeded, int failed) { }
    ManagedCleanupResult cleanupManagedFiles(int pageSize, int maxPages);

    /**
     * 获得文件分页
     *
     * @param pageReqVO 分页查询
     * @return 文件分页
     */
    PageResult<FileDO> getFilePage(FilePageReqVO pageReqVO);

    /**
     * 保存文件，并返回文件的访问路径
     *
     * @param content   文件内容
     * @param name      文件名称，允许空
     * @param directory 目录，允许空
     * @param type      文件的 MIME 类型，允许空
     * @return 文件路径
     */
    String createFile(@NotEmpty(message = "文件内容不能为空") byte[] content,
                      String name, String directory, String type);

    /**
     * 生成文件预签名地址信息，用于上传
     *
     * @param name      文件名
     * @param directory 目录
     * @return 预签名地址信息
     */
    FilePresignedUrlRespVO presignPutUrl(@NotEmpty(message = "文件名不能为空") String name,
                                         String directory);
    /**
     * 生成文件预签名地址信息，用于读取
     *
     * @param url 完整的文件访问地址
     * @param expirationSeconds 访问有效期，单位秒
     * @return 文件预签名地址
     */
    String presignGetUrl(String url, Integer expirationSeconds);

    /**
     * 创建文件
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createFile(FileCreateReqVO createReqVO);
    FileDO getFile(Long id);

    /**
     * 删除文件
     *
     * @param id 编号
     */
    void deleteFile(Long id) throws Exception;

    /**
     * 批量删除文件
     *
     * @param ids 编号列表
     */
    void deleteFileList(List<Long> ids) throws Exception;

    /**
     * 获得文件内容
     *
     * @param configId 配置编号
     * @param path     文件路径
     * @return 文件内容
     */
    byte[] getFileContent(Long configId, String path) throws Exception;

    /**
     * 获得文件
     *
     * @param configId 配置编号
     * @param path     文件路径
     * @return 文件
     */
    FileDO getFileByConfigIdAndPath(Long configId, String path);

    /**
     * 保存文件，并返回文件信息
     *
     * @param content   文件内容
     * @param name      文件名称，允许空
     * @param directory 目录，允许空
     * @param type      文件的 MIME 类型，允许空
     * @return 文件信息
     */
    FileDO createFileWithInfo(@NotEmpty(message = "文件内容不能为空") byte[] content,
                              String name, String directory, String type);

    /**
     * 生成详情上传链路的文件预签名地址信息。该链路始终使用唯一对象路径。
     *
     * @param name      文件名
     * @param directory 目录
     * @return 预签名地址信息
     */
    FilePresignedUrlRespVO presignPutUrlWithInfo(@NotEmpty(message = "文件名不能为空") String name,
                                                 String directory);

    /**
     * 根据文件编号生成可访问 URL。使用文件记录所属配置的客户端；不支持签名的客户端返回持久化 URL。
     *
     * @param id 文件编号
     * @param expirationSeconds 访问有效期，单位秒
     * @return 文件可访问 URL
     */
    String getFileAccessUrl(Long id, Integer expirationSeconds);

}
