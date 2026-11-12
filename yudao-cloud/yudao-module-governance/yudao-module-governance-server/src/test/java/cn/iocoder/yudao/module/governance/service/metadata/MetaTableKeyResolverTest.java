package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaDbMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.service.metadata.MetaTableKeyResolver.TableKey;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 匹配规则在内存中执行，Mapper 返回全部夹具；SQL 过滤（大小写不敏感候选、删除状态）由 PostgreSQL 用例覆盖。
 */
class MetaTableKeyResolverTest {

    private final List<MetaDbDO> dbs = new ArrayList<>();
    private final List<MetaTableDO> tables = new ArrayList<>();
    private final List<MetaColumnDO> columns = new ArrayList<>();
    private MetaTableKeyResolver resolver;

    @BeforeAll
    static void initLambdaCache() {
        for (Class<?> type : List.of(MetaDbDO.class, MetaTableDO.class, MetaColumnDO.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var dbMapper = mock(MetaDbMapper.class);
        var tableMapper = mock(MetaTableMapper.class);
        var columnMapper = mock(MetaColumnMapper.class);
        when(dbMapper.selectList(any(Wrapper.class))).thenAnswer(call -> new ArrayList<>(dbs));
        when(tableMapper.selectList(any(Wrapper.class))).thenAnswer(call -> new ArrayList<>(tables));
        when(columnMapper.selectList(any(Wrapper.class))).thenAnswer(call -> new ArrayList<>(columns));
        resolver = new MetaTableKeyResolver();
        ReflectionTestUtils.setField(resolver, "dbMapper", dbMapper);
        ReflectionTestUtils.setField(resolver, "tableMapper", tableMapper);
        ReflectionTestUtils.setField(resolver, "columnMapper", columnMapper);

        db(10, 1, "ods");
        db(11, 1, "dwd");
        db(12, 1, "Mixed");
        db(20, 2, "ods");
        table(100, 10, "orders", "订单");
        table(101, 11, "orders", "订单明细");
        table(102, 10, "Customer", "客户");
        table(103, 12, "Users", "用户");
        table(104, 12, "USERS", "用户副本");
        table(105, 11, "Only_Dwd", "仅 DWD");
        table(200, 20, "orders", "其他数据源订单");
    }

    @Test
    void exactMatchWinsAndIsScopedToDatasourceAndSchema() {
        assertThat(match(1, "ods", "orders")).isEqualTo(100L);
        assertThat(match(1, "dwd", "orders")).isEqualTo(101L);
        assertThat(match(2, "ods", "orders")).isEqualTo(200L);
        assertThat(match(3, "ods", "orders")).isNull();
        assertThat(match(1, "Mixed", "Users")).isEqualTo(103L);
        assertThat(match(1, "Mixed", "USERS")).isEqualTo(104L);
    }

    @Test
    void caseInsensitiveFallbackRequiresAUniqueCandidate() {
        assertThat(match(1, "ODS", "CUSTOMER")).isEqualTo(102L);
        assertThat(match(1, "mixed", "users")).as("两个仅大小写不同的表，忽略大小写后不唯一").isNull();
        assertThat(match(1, "ods", "missing")).isNull();
    }

    @Test
    void emptySchemaOnlyMatchesTableNamesUniqueWithinTheDatasource() {
        assertThat(match(1, null, "orders")).as("ods 与 dwd 都有 orders").isNull();
        assertThat(match(1, "", "Customer")).isEqualTo(102L);
        assertThat(match(1, " ", "only_dwd")).isEqualTo(105L);
        assertThat(match(2, null, "orders")).isEqualTo(200L);
    }

    @Test
    void exactAmbiguityAcrossDatabasesWithSameSchemaIsAMiss() {
        MetaDbDO sameSchema = db(13, 1, "ods");
        sameSchema.setDbName("another_catalog");
        table(106, 13, "orders", "同 schema 另一库");
        assertThat(match(1, "ods", "orders")).isNull();
    }

    @Test
    void columnNamesKeepOrdinalOrderPerTable() {
        column(1, 100, "id", 1);
        column(2, 100, "amount", 2);
        column(3, 101, "order_id", 1);
        var names = resolver.loadColumnNames(List.of(100L, 101L));
        assertThat(names.get(100L)).containsExactly("id", "amount");
        assertThat(names.get(101L)).containsExactly("order_id");
        assertThat(resolver.loadColumnNames(List.of())).isEmpty();
    }

    @Test
    void invalidKeysAreIgnored() {
        assertThat(resolver.resolve(java.util.Arrays.asList(null, new TableKey(null, "ods", "orders"),
                new TableKey(1L, "ods", " ")))).isEmpty();
    }

    private Long match(long datasourceId, String schema, String table) {
        TableKey key = new TableKey(datasourceId, schema, table);
        var match = resolver.resolve(List.of(key)).get(key);
        return match == null ? null : match.tableId();
    }

    private MetaDbDO db(long id, long datasourceId, String schema) {
        MetaDbDO db = MetaDbDO.builder().id(id).datasourceId(datasourceId).dbName(schema).schemaName(schema)
                .objectStatus(MetaDbDO.OBJECT_STATUS_ACTIVE).build();
        dbs.add(db);
        return db;
    }

    private void table(long id, long dbId, String name, String businessName) {
        MetaTableDO table = new MetaTableDO();
        table.setId(id);
        table.setDbId(dbId);
        table.setTableName(name);
        table.setBusinessName(businessName);
        table.setObjectStatus(MetaDbDO.OBJECT_STATUS_ACTIVE);
        tables.add(table);
    }

    private void column(long id, long tableId, String name, int ordinal) {
        MetaColumnDO column = new MetaColumnDO();
        column.setId(id);
        column.setTableId(tableId);
        column.setColumnName(name);
        column.setOrdinalPosition(ordinal);
        columns.add(column);
    }

}
