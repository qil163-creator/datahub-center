package cn.iocoder.yudao.module.governance.dal.mysql.doc;

import cn.iocoder.yudao.module.governance.dal.dataobject.doc.StandardDocDO;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydatameta.CategoryDatametaMapper;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardCatalogService;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardElementServiceImpl;
import cn.iocoder.yudao.module.governance.support.StandardPostgreSqlIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class StandardDocMapperPostgreSqlTest extends StandardPostgreSqlIntegrationTestBase {

    @Autowired
    private StandardDocMapper mapper;
    @Autowired
    private StandardElementMapper elements;
    @Autowired
    private StandardCodeSetMapper codeSets;
    @Autowired
    private CategoryDatametaMapper categories;

    @Test
    void elementUpdateWaitingForExistingBindingDoesNotHoldParentCategoryLock() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO dh_governance_category_datameta(id,category_name) VALUES (2,'共享类目')");
            statement.execute("UPDATE dh_governance_standard_element SET category_id=2,eng_name='element100',lifecycle_status='DRAFT',standard_type='LOCAL' WHERE id=100");
        }
        var service = new StandardElementServiceImpl();
        ReflectionTestUtils.setField(service, "catalogService", mock(StandardCatalogService.class));
        ReflectionTestUtils.setField(service, "standardElementMapper", elements);
        ReflectionTestUtils.setField(service, "categoryDatametaMapper", categories);
        ReflectionTestUtils.setField(service, "standardDocMapper", mapper);
        var request = new StandardElementSaveReqVO();
        request.setId(100);
        request.setRevision(0L);
        request.setCategoryId(2L);
        request.setDocumentId(10L);
        request.setStandardType("LOCAL");
        request.setName("有效引用一");
        request.setEngName("element100");
        request.setStatus((short) 1);
        request.setSort(0);
        var executor = Executors.newSingleThreadExecutor();
        try (var binding = dataSource.getConnection(); var statement = binding.createStatement()) {
            binding.setAutoCommit(false);
            // 模拟码表绑定事务先持有数据元，再通过引用触发器读取父类目。
            statement.executeQuery("SELECT id FROM dh_governance_standard_element WHERE id=100 FOR UPDATE").close();
            var update = executor.submit(() -> new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                    .executeWithoutResult(status -> service.updateStandardElement(request)));
            try {
                assertThat(awaitElementLockWait()).as("更新事务已经阻塞在同一数据元行锁").isTrue();
                statement.execute("SET LOCAL lock_timeout='300ms'");
                statement.executeQuery("SELECT id FROM dh_governance_category_datameta WHERE id=2 FOR UPDATE").close();
                binding.commit();
                update.get(5, TimeUnit.SECONDS);
            } finally {
                binding.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private boolean awaitElementLockWait() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname=current_database() "
                         + "AND wait_event_type='Lock' AND query LIKE '%dh_governance_standard_element%' AND query LIKE '%FOR UPDATE%')")) {
                result.next();
                if (result.getBoolean(1)) return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    @Test
    void pagesOnlyReturnLiveChildrenOfTheSelectedDocument() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO dh_governance_standard_element(id,name,document_id) VALUES (103,'其他标准数据元',99)");
            statement.execute("INSERT INTO dh_governance_standard_code_set(id,name,type,document_id,deleted) VALUES "
                    + "(1,'关联一','ONE',10,0),(2,'关联二','TWO',10,0),(3,'其他标准','OTHER',99,0),(4,'已删除','DELETED',10,1)");
        }
        var elementQuery = new StandardElementPageReqVO();
        elementQuery.setDocumentId(10L);
        elementQuery.setPageSize(1);
        var elementPage = elements.selectPage(elementQuery);
        assertThat(elementPage.getTotal()).isEqualTo(2L);
        assertThat(elementPage.getList()).hasSize(1).allSatisfy(row -> assertThat(row.getDocumentId()).isEqualTo(10L));
        elementQuery.setDocumentId(99L);
        assertThat(elements.selectPage(elementQuery).getTotal()).isEqualTo(1L);
        var codeSetQuery = new StandardCodeSetPageReqVO();
        codeSetQuery.setDocumentId(10L);
        codeSetQuery.setPageSize(1);
        var codeSetPage = codeSets.selectPage(codeSetQuery);
        assertThat(codeSetPage.getTotal()).isEqualTo(2L);
        assertThat(codeSetPage.getList()).hasSize(1).allSatisfy(row -> assertThat(row.getDocumentId()).isEqualTo(10L));
        assertThat(mapper.selectCodeSetReferenceCount(10L)).isEqualTo(2L);
    }

    @Test
    void documentCatalogFieldsRoundTripAndCategoryFilterExcludesOtherRecords() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("UPDATE dh_governance_standard_doc SET category_id=7, version_no='2026-A', abolish_date='2028-03-01' WHERE id=10");
            statement.execute("INSERT INTO dh_governance_standard_doc(id,name_cn,category_id) VALUES (12,'其他类目',8)");
        }
        var query = new StandardDocPageReqVO();
        query.setCategoryIds(List.of(7L));
        var result = mapper.selectPage(query);
        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getList().getFirst().getCategoryId()).isEqualTo(7L);
        assertThat(result.getList().getFirst().getVersionNo()).isEqualTo("2026-A");
        assertThat(result.getList().getFirst().getAbolishDate()).isEqualTo(LocalDate.of(2028, 3, 1));
        mapper.updateById(StandardDocDO.builder().id(10L).categoryId(7L).build());
        var cleared = mapper.selectById(10L);
        assertThat(cleared.getVersionNo()).isNull();
        assertThat(cleared.getAbolishDate()).isNull();
    }

    @Test
    void shouldOnlyAllowUnmistakableTestDatabasesForExternalReset() {
        assertThat(StandardPostgreSqlIntegrationTestBase.isUnmistakableTestDatabaseName("data_standard_java_test"))
                .isTrue();
        assertThat(StandardPostgreSqlIntegrationTestBase.isUnmistakableTestDatabaseName("datahub"))
                .isFalse();
        assertThat(StandardPostgreSqlIntegrationTestBase.isUnmistakableTestDatabaseName("production_test"))
                .isFalse();
    }

    @Test
    @Transactional
    void shouldLockRealDocumentAndCountAllActiveReferences() throws Exception {
        StandardDocDO locked = mapper.selectByIdForUpdate(10L);

        assertThat(locked.getNameCn()).isEqualTo("现行标准");
        assertThat(mapper.selectElementReferenceCount(10L)).isEqualTo(2L);
        assertThat(mapper.selectByIdForUpdate(11L)).isNull();

        try (Connection independentConnection = dataSource.getConnection()) {
            try (PreparedStatement searchPath = independentConnection.prepareStatement(
                    "SET search_path TO \"datahub-center\"")) {
                searchPath.execute();
            }
            try (PreparedStatement lockTimeout = independentConnection.prepareStatement("SET lock_timeout = '100ms'")) {
                lockTimeout.execute();
            }
            assertThatThrownBy(() -> lockSameDocument(independentConnection))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("lock timeout");
        }
    }

    private static void lockSameDocument(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM dh_governance_standard_doc WHERE id = 10 FOR UPDATE")) {
            statement.executeQuery();
        }
    }
}
