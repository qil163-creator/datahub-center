package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketCategoryDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketListingDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCategoryMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketListingMapper;
import cn.iocoder.yudao.module.asset.service.common.AssetUserNameResolver;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jakarta.validation.Validation;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketCategoryServiceImplTest {
    private final MarketCategoryServiceImpl service = new MarketCategoryServiceImpl();
    private final MarketCategoryMapper mapper = mock(MarketCategoryMapper.class);
    private final MarketListingMapper listings = mock(MarketListingMapper.class);

    @BeforeAll
    static void initializeMetadata() {
        for (var type : List.of(MarketCategoryDO.class, MarketListingDO.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }

    @BeforeEach
    void injectDependencies() {
        ReflectionTestUtils.setField(service, "categoryMapper", mapper);
        ReflectionTestUtils.setField(service, "listingMapper", listings);
        ReflectionTestUtils.setField(service, "userNameResolver", mock(AssetUserNameResolver.class));
    }

    @Test
    void createTrimsNameAndDoesNotAcceptSubmittedIdentifier() {
        var req = request(); req.setId(99L); req.setName(" 集市目录 ");
        service.create(req);
        var captured = ArgumentCaptor.forClass(MarketCategoryDO.class);
        verify(mapper).lockTree(); verify(mapper).insert(captured.capture());
        assertThat(captured.getValue().getId()).isNull();
        assertThat(captured.getValue().getName()).isEqualTo("集市目录");
        assertThat(captured.getValue().getParentId()).isZero();
    }

    @Test
    void movingUnderOwnDescendantCannotIntroduceCycle() {
        var req = request(); req.setId(1L); req.setParentId(3L);
        when(mapper.selectForUpdate(1L)).thenReturn(category(1, 0));
        when(mapper.selectById(3L)).thenReturn(category(3, 2));
        when(mapper.selectById(2L)).thenReturn(category(2, 1));
        assertError(() -> service.update(req), MARKET_CATEGORY_PARENT_INVALID.getCode());
        verify(mapper, never()).updateById(any(MarketCategoryDO.class));
    }

    @Test
    void missingParentAndDuplicateSiblingNameAreDomainFailures() {
        var req = request(); req.setParentId(15L);
        assertError(() -> service.create(req), MARKET_CATEGORY_PARENT_INVALID.getCode());
        req.setParentId(0L);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(category(2, 0));
        assertError(() -> service.create(req), MARKET_CATEGORY_NAME_DUPLICATE.getCode());
        verify(mapper, never()).insert(any(MarketCategoryDO.class));
    }

    @Test
    void batchDeleteIsAtomicAndRejectsRemainingChildrenBeforeDeleting() {
        when(mapper.selectForUpdate(1L)).thenReturn(category(1, 0));
        when(mapper.selectForUpdate(2L)).thenReturn(category(2, 1));
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertError(() -> service.delete(List.of(2L, 1L)), MARKET_CATEGORY_HAS_CHILDREN.getCode());
        var order = inOrder(mapper); order.verify(mapper).lockTree();
        order.verify(mapper).selectForUpdate(1L); order.verify(mapper).selectForUpdate(2L);
        verify(mapper, never()).deleteByIds(anyCollection());
        verifyNoInteractions(listings);
    }

    @Test
    void categoryStillReferencedByListingCannotBeDeleted() {
        when(mapper.selectForUpdate(1L)).thenReturn(category(1, 0));
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(listings.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertError(() -> service.delete(List.of(1L)), MARKET_CATEGORY_IN_USE.getCode());
        verify(mapper, never()).deleteByIds(anyCollection());
    }

    @Test
    void publisherLocksAncestorsInSameOrderAsBatchDeleteAndRejectsDisabledAncestor() {
        var parent = category(1, 0); parent.setStatus((short)1);
        var child = category(2, 1);
        when(mapper.selectById(1L)).thenReturn(parent); when(mapper.selectById(2L)).thenReturn(child);
        when(mapper.selectForPublish(1L)).thenReturn(parent); when(mapper.selectForPublish(2L)).thenReturn(child);
        assertError(() -> service.requireEnabledForPublish(2L), MARKET_CATEGORY_DISABLED.getCode());
        var order = inOrder(mapper); order.verify(mapper).selectForPublish(1L); order.verify(mapper).selectForPublish(2L);
    }

    @Test
    void parentChangedBeforePublishLockCannotEscapeLockedAncestorSet() {
        var before = category(2, 1); var after = category(2, 3); var parent = category(1, 0);
        when(mapper.selectById(2L)).thenReturn(before); when(mapper.selectById(1L)).thenReturn(parent);
        when(mapper.selectForPublish(1L)).thenReturn(parent); when(mapper.selectForPublish(2L)).thenReturn(after);
        assertError(() -> service.requireEnabledForPublish(2L), MARKET_CATEGORY_PARENT_INVALID.getCode());
    }

    @Test
    void formBoundariesMatchReferenceWithoutNewCodeField() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator(); var req = request();
            req.setName("类".repeat(50)); req.setDescription("述".repeat(256));
            assertThat(validator.validate(req)).isEmpty();
            req.setName("类".repeat(51)); req.setDescription("述".repeat(257));
            assertThat(validator.validate(req)).extracting(error -> error.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("name", "description");
            assertThat(MarketCategorySaveReqVO.class.getDeclaredFields()).extracting(java.lang.reflect.Field::getName)
                    .doesNotContain("code", "categoryId", "tenantId");
        }
    }

    @Test
    void enabledOptionsExcludeOrphansAndDisabledAncestorBranchesWithoutUserLookup() {
        var disabled = category(1, 0); disabled.setStatus((short)1);
        var child = category(2, 1); var healthy = category(3, 0); var orphan = category(4, 99);
        when(mapper.selectCategoryList(any())).thenReturn(List.of(disabled, child, healthy, orphan));
        assertThat(service.getEnabledList()).extracting(MarketCategoryRespVO::getId).containsExactly(3L);
    }

    private static MarketCategorySaveReqVO request() {
        var req = new MarketCategorySaveReqVO(); req.setParentId(0L); req.setName("集市目录");
        req.setStatus((short)0); req.setSort(0); return req;
    }

    private static MarketCategoryDO category(long id, long parentId) {
        var item = new MarketCategoryDO(); item.setId(id); item.setParentId(parentId); item.setStatus((short)0); return item;
    }

    private static void assertError(Runnable operation, int code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
