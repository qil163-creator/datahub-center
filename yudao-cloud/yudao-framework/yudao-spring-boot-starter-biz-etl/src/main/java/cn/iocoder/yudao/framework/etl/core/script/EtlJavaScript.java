package cn.iocoder.yudao.framework.etl.core.script;

import java.util.List;
import java.util.Map;

/**
 * Java 脚本节点的用户类契约：脚本代码需实现本接口
 *
 * @author Li
 */
public interface EtlJavaScript {

    /**
     * 按批处理数据行（可增删改行与字段），返回值作为节点输出
     *
     * @param rows 上游数据行批次
     * @return 处理后的数据行
     */
    List<Map<String, Object>> execute(List<Map<String, Object>> rows) throws Exception;

}
