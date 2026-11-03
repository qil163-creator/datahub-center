package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DataAccessResponseMetadataTest {

    private final DataAccessServiceImpl service = new DataAccessServiceImpl();
    private final DataAccessMapper mapper = mock(DataAccessMapper.class);
    private final AdminUserApi userApi = mock(AdminUserApi.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "dataAccessMapper", mapper);
        ReflectionTestUtils.setField(service, "adminUserApi", userApi);
    }

    @Test
    void pageResolvesCreatorsAndReleaseUsersInOneBatchWithoutLosingLargeIds() {
        long creatorId = 9007199254740993L;
        var first = definition(1L, String.valueOf(creatorId));
        first.setReleaseUserId(7L);
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 13, 9, 30);
        first.setCreateTime(createdAt);
        var second = definition(2L, String.valueOf(creatorId));
        second.setReleaseUserId(creatorId);
        var request = new DataAccessPageReqVO();
        when(mapper.selectPage(request)).thenReturn(new PageResult<>(List.of(first, second), 2L));
        when(userApi.getUserMap(anyCollection())).thenReturn(Map.of(
                creatorId, user(creatorId, "创建人"), 7L, user(7L, "发布人")));

        var page = service.getDataAccessPage(request);

        assertEquals(2L, page.getTotal());
        assertEquals("创建人", page.getList().getFirst().getCreatorName());
        assertEquals("发布人", page.getList().getFirst().getReleaseUserName());
        assertEquals("创建人", page.getList().get(1).getCreatorName());
        assertEquals("创建人", page.getList().get(1).getReleaseUserName());
        assertEquals(createdAt, page.getList().getFirst().getCreateTime());
        verify(userApi).getUserMap(Set.of(creatorId, 7L));
        verifyNoMoreInteractions(userApi);
    }

    @Test
    void invalidAuditCreatorsLeaveNamesEmptyWithoutUserLookup() {
        var definitions = new ArrayList<DataAccessDO>();
        definitions.add(definition(1L, null));
        for (String creator : List.of("", " ", "system", "9223372036854775808", "1.5")) {
            definitions.add(definition((long) definitions.size() + 1, creator));
        }
        var request = new DataAccessPageReqVO();
        when(mapper.selectPage(request)).thenReturn(new PageResult<>(definitions, (long) definitions.size()));

        var page = service.getDataAccessPage(request);

        assertEquals(definitions.size(), page.getList().size());
        assertTrue(page.getList().stream().allMatch(row -> row.getCreatorName() == null));
        verifyNoInteractions(userApi);
    }

    @Test
    void detailLeavesDeletedCreatorEmptyAndPreservesCreationTime() {
        var definition = definition(1L, "8");
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 12, 8, 0);
        definition.setCreateTime(createdAt);
        when(mapper.selectById(1L)).thenReturn(definition);
        when(userApi.getUserMap(Set.of(8L))).thenReturn(Map.of());

        var response = service.getDataAccess(1L);

        assertNull(response.getCreatorName());
        assertEquals(createdAt, response.getCreateTime());
        verify(userApi).getUserMap(Set.of(8L));
    }

    private static DataAccessDO definition(Long id, String creator) {
        var definition = DataAccessDO.builder().id(id).engineType(2).build();
        definition.setCreator(creator);
        return definition;
    }

    private static AdminUserRespDTO user(Long id, String nickname) {
        var user = new AdminUserRespDTO();
        user.setId(id);
        user.setNickname(nickname);
        return user;
    }
}
