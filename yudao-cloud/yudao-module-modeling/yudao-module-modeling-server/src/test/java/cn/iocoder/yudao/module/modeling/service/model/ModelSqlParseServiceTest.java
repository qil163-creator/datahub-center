package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.SqlParseFieldsReqVO;
import org.junit.jupiter.api.Test;

import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.MODEL_SQL_PARSE_FAILED;
import static org.junit.jupiter.api.Assertions.*;

/** 字段设计 SQL 解析：解析结果到模型字段的映射与错误翻译。 */
class ModelSqlParseServiceTest {

    private final ModelSqlParseService service = new ModelSqlParseService();

    private static SqlParseFieldsReqVO request(String dialect, String sql) {
        SqlParseFieldsReqVO request = new SqlParseFieldsReqVO();
        request.setDialect(dialect);
        request.setSql(sql);
        return request;
    }

    @Test
    void mapsCommentsToChineseNamesAndKeepsStructure() {
        var result = service.parse(request("doris", "CREATE TABLE dw.fact_order (order_id BIGINT NOT NULL COMMENT '订单号',"
                + " amount DECIMAL(20,4), region VARCHAR(32)) UNIQUE KEY(order_id) DISTRIBUTED BY HASH(order_id)"));

        assertEquals("fact_order", result.getTableName());
        assertEquals(3, result.getFields().size());
        var id = result.getFields().get(0);
        assertEquals("order_id", id.getFieldName());
        assertEquals("订单号", id.getCnName());
        assertTrue(id.getPkFlag());
        assertFalse(id.getNullableFlag());
        assertNull(id.getFieldUid());
        var amount = result.getFields().get(1);
        assertEquals("amount", amount.getCnName(), "没有注释时中文名沿用字段名");
        assertEquals(20, amount.getDataPrecision());
        assertEquals(4, amount.getDataScale());
        assertEquals(32, result.getFields().get(2).getDataLength());
        assertEquals(3, result.getFields().get(2).getSort());
    }

    @Test
    void unknownDialectAndInvalidSqlAreBusinessErrors() {
        for (var request : new SqlParseFieldsReqVO[]{request("NOPE", "CREATE TABLE a (id int)"),
                request("SQLSERVER", "CREATE TABLE a (id int)"), request("MYSQL", "DROP TABLE a")}) {
            ServiceException error = assertThrows(ServiceException.class, () -> service.parse(request));
            assertEquals(MODEL_SQL_PARSE_FAILED.getCode(), error.getCode());
        }
    }
}
