package cn.iocoder.yudao.module.infra.dal.mysql.file;

import cn.iocoder.yudao.module.infra.dal.dataobject.file.FileContentDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface FileContentMapper extends BaseMapper<FileContentDO> {

    default FileContentDO selectLatestBounded(Long configId, String path, long maxBytes) {
        var scope = new LambdaQueryWrapper<FileContentDO>().eq(FileContentDO::getConfigId, configId)
                .eq(FileContentDO::getPath, path);
        if (selectCount(scope.clone().apply("OCTET_LENGTH(content) > {0}", maxBytes)) > 0)
            throw new IllegalArgumentException("文件内容超过读取预算");
        return selectOne(scope.apply("OCTET_LENGTH(content) <= {0}", maxBytes).orderByDesc(FileContentDO::getId).last("LIMIT 1"));
    }

    default void deleteByConfigIdAndPath(Long configId, String path) {
        this.delete(new LambdaQueryWrapper<FileContentDO>()
                .eq(FileContentDO::getConfigId, configId)
                .eq(FileContentDO::getPath, path));
    }

    default List<FileContentDO> selectListByConfigIdAndPath(Long configId, String path) {
        return selectList(new LambdaQueryWrapper<FileContentDO>()
                .eq(FileContentDO::getConfigId, configId)
                .eq(FileContentDO::getPath, path));
    }

}
