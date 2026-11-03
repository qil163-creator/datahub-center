package cn.iocoder.yudao.module.dataforge.api.database;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryReqDTO;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 受限字段授权由提供方在执行前强制：三个约束字段缺一不可，越权引用在连接数据库之前就被拒绝。
 */
class DatabaseQueryScopeTest {

    private DatabaseApiImpl api;

    private static AccessDatabaseResolver.ConnectionInfo connection(String schema) {
        AccessDatabaseResolver.ConnectionInfo info = new AccessDatabaseResolver.ConnectionInfo();
        info.setDatabaseId(3L);
        info.setProductType(ProductTypeEnum.POSTGRESQL);
        info.setSchema(schema);
        return info;
    }

    private static DatabaseQueryReqDTO request(String sql, String table, List<String> allowed) {
        DatabaseQueryReqDTO dto = new DatabaseQueryReqDTO();
        dto.setDatabaseId(3L);
        dto.setSql(sql);
        dto.setExpectedTable(table);
        dto.setAllowedColumns(allowed);
        return dto;
    }

    private String restrict(AccessDatabaseResolver.ConnectionInfo info, DatabaseQueryReqDTO dto) {
        return (String) ReflectionTestUtils.invokeMethod(api, "restrictToAllowedColumns", info, dto);
    }

    @BeforeEach
    void setUp() {
        api = new DatabaseApiImpl();
    }

    @Test
    void wildcardIsExpandedToAllowedColumns() {
        String sql = restrict(connection("public"),
                request("select * from public.citizen", "citizen", List.of("id", "phone")));
        assertThat(sql).doesNotContain("*").contains("\"id\"").contains("\"phone\"");
    }

    @Test
    void unapprovedFilterOrOrderIsRejected() {
        assertThatThrownBy(() -> restrict(connection("public"),
                request("select id from public.citizen where salary > 1", "citizen", List.of("id"))))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> restrict(connection("public"),
                request("select id from public.citizen order by phone", "citizen", List.of("id"))))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void partialConstraintsCannotDowngradeToUnrestricted() {
        DatabaseQueryReqDTO onlyTable = request("select * from public.citizen", "citizen", null);
        assertThatThrownBy(() -> restrict(connection("public"), onlyTable))
                .isInstanceOf(ServiceException.class);
        DatabaseQueryReqDTO onlyColumns = request("select * from public.citizen", null, List.of("id"));
        assertThatThrownBy(() -> restrict(connection("public"), onlyColumns))
                .isInstanceOf(ServiceException.class);
        DatabaseQueryReqDTO emptyColumns = request("select * from public.citizen", "citizen", List.of());
        assertThatThrownBy(() -> restrict(connection("public"), emptyColumns))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void schemaFallsBackToTheDatasourceConfigurationAndFailsWhenUnknown() {
        assertThat(restrict(connection("public"), request("select id from citizen", "citizen", List.of("id"))))
                .contains("\"public\".\"citizen\"");
        assertThatThrownBy(() -> restrict(connection(" "),
                request("select id from citizen", "citizen", List.of("id"))))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void anotherTableOrSchemaIsRejected() {
        assertThatThrownBy(() -> restrict(connection("public"),
                request("select id from other.citizen", "citizen", List.of("id"))))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> restrict(connection("public"),
                request("select id from public.resident", "citizen", List.of("id"))))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void unscopedRequestKeepsTheExistingNormalizedBehavior() {
        DatabaseQueryReqDTO plain = new DatabaseQueryReqDTO();
        plain.setDatabaseId(3L);
        plain.setSql("select id from public.citizen");
        assertThat(restrict(connection("public"), plain)).containsIgnoringCase("select");
    }

    @Test
    void qualifiedPlanWireContractPreservesPhysicalNamesAndSql() throws Exception {
        var dto = new cn.iocoder.yudao.module.dataforge.api.database.dto.RestrictedSelectRespDTO();
        dto.setSchemaName("Gov.Schema");
        dto.setTableName("Citizen\". One");
        dto.setNormalizedSql("SELECT * FROM \"Gov.Schema\".\"Citizen\"\". One\"");
        var jackson2 = new com.fasterxml.jackson.databind.ObjectMapper();
        var jackson3 = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(jackson2.readValue(jackson2.writeValueAsBytes(dto), dto.getClass())).isEqualTo(dto);
        assertThat(jackson3.readValue(jackson3.writeValueAsBytes(dto), dto.getClass())).isEqualTo(dto);
    }

    @Test
    void metadataWireContractsPreservePhysicalNamesAndEmptyProjection() throws Exception {
        var table = new cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryTableRespDTO();
        table.setSchemaName("Gov.Schema");
        table.setTableName("Citizen\". One");
        table.setTableReference("\"Gov.Schema\".\"Citizen\"\". One\"");
        table.setNormalizedSql("SELECT * FROM " + table.getTableReference());
        var columns = new cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryColumnsReqDTO();
        columns.setDatabaseId(4L);
        columns.setSchemaName(table.getSchemaName());
        columns.setTableName(table.getTableName());
        columns.setColumns(List.of(" Id.\" ", "Phone"));
        var jackson2 = new com.fasterxml.jackson.databind.ObjectMapper();
        var jackson3 = tools.jackson.databind.json.JsonMapper.builder().build();
        for (Object dto : List.of(table, columns)) {
            assertThat(jackson2.readValue(jackson2.writeValueAsBytes(dto), dto.getClass())).isEqualTo(dto);
            assertThat(jackson3.readValue(jackson3.writeValueAsBytes(dto), dto.getClass())).isEqualTo(dto);
        }
        columns.setColumns(List.of());
        assertThat(jackson2.readValue(jackson2.writeValueAsBytes(columns), columns.getClass()).getColumns()).isEmpty();
        assertThat(jackson3.readValue(jackson3.writeValueAsBytes(columns), columns.getClass()).getColumns()).isEmpty();
    }
}
