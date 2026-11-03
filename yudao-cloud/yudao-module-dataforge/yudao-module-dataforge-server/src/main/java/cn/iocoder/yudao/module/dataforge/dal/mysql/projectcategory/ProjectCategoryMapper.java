package cn.iocoder.yudao.module.dataforge.dal.mysql.projectcategory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 只读取迁入展示名称，不建立第三套类目事实。 */
@Mapper
public interface ProjectCategoryMapper {

    record SourceCreator(Long id, String creatorName) {

    }

    record TaskCount(Long id, Long taskCount) {

    }

    record ChildCount(Long id, Long childCount) {

    }

    /**
     * 查询项目分类的集成创建人名称。
     *
     * @param ids 记录编号集合
     * @return 项目分类的集成创建人名称结果集合
     */
    @Select({"<script>", "SELECT id, source_creator_name AS creator_name FROM dh_dataforge_integration_category",
            "WHERE deleted = 0 AND id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>", "</script>"})
    List<SourceCreator> selectIntegrationCreatorNames(@Param("ids") List<Long> ids);

    /**
     * 查询项目分类的开发创建人名称。
     *
     * @param ids 记录编号集合
     * @return 项目分类的开发创建人名称结果集合
     */
    @Select({"<script>", "SELECT id, source_creator_name AS creator_name FROM dh_dataforge_dev_dir",
            "WHERE deleted = 0 AND id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>", "</script>"})
    List<SourceCreator> selectDevelopmentCreatorNames(@Param("ids") List<Long> ids);

    /**
     * 查询项目分类的集成任务数量。
     *
     * @param ids 记录编号集合
     * @return 项目分类的集成任务数量结果集合
     */
    @Select({"<script>", "SELECT category_id AS id, count(*) AS task_count FROM dh_dataforge_integration_task",
            "WHERE deleted = 0 AND category_id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "GROUP BY category_id", "</script>"})
    List<TaskCount> selectIntegrationTaskCounts(@Param("ids") List<Long> ids);

    /**
     * 查询项目分类的开发任务数量。
     *
     * @param ids 记录编号集合
     * @return 项目分类的开发任务数量结果集合
     */
    @Select({"<script>", "SELECT dir_id AS id, count(*) AS task_count FROM dh_dataforge_dev_task",
            "WHERE deleted = 0 AND dir_id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "GROUP BY dir_id", "</script>"})
    List<TaskCount> selectDevelopmentTaskCounts(@Param("ids") List<Long> ids);

    /**
     * 查询项目分类的集成子级数量。
     *
     * @param ids 记录编号集合
     * @return 项目分类的集成子级数量结果集合
     */
    @Select({"<script>", "SELECT parent_id AS id, count(*) AS child_count FROM dh_dataforge_integration_category",
            "WHERE deleted = 0 AND parent_id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "GROUP BY parent_id", "</script>"})
    List<ChildCount> selectIntegrationChildCounts(@Param("ids") List<Long> ids);

    /**
     * 查询项目分类的开发子级数量。
     *
     * @param ids 记录编号集合
     * @return 项目分类的开发子级数量结果集合
     */
    @Select({"<script>", "SELECT parent_id AS id, count(*) AS child_count FROM dh_dataforge_dev_dir",
            "WHERE deleted = 0 AND parent_id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "GROUP BY parent_id", "</script>"})
    List<ChildCount> selectDevelopmentChildCounts(@Param("ids") List<Long> ids);
}
