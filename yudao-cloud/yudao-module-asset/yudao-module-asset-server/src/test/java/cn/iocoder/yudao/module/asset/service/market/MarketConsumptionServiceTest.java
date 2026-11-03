package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.supply.api.app.SupplyQuotaApi;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.*;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO.Quota;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketConsumptionServiceTest {
    SupplyQuotaApi api; MarketConsumptionService service; MarketQuerySnapshot snapshot;
    final Receipt reserved = new Receipt("PORTAL:7:4","request-0001","RESERVED","private-token",3,null,List.of());
    @BeforeEach void setup() {
        api=mock(SupplyQuotaApi.class); service=new MarketConsumptionService(); ReflectionTestUtils.setField(service,"quotaApi",api);
        var http=new MockHttpServletRequest(); http.addHeader("X-Consumption-Key","request-0001");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
        var source=new Source("asset","asset:apply:1",new Quota("ROWS",5),List.of("name"));
        var grant=new MarketQuerySnapshot.Grant(1,1,1,"{}",null,null,null,source);
        snapshot=new MarketQuerySnapshot(7,1,4,List.of(grant),MarketFieldScope.parse("name"),9L,"public","citizen",LocalDateTime.now());
    }
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }
    Receipt committed() { return new Receipt("PORTAL:7:4","request-0001","COMMITTED",null,3,2,List.of()); }
    @Test void reservesTrustedScopeBeforeReadAndCommitsActualRowsAfterAuthorization() {
        when(api.reserve(any())).thenReturn(CommonResult.success(reserved)); when(api.settle(any())).thenReturn(CommonResult.success(committed()));
        var rows=MarketConsumptionService.read(service,snapshot,Map.of("query","sensitive-not-ledger"),100, limit -> {
            assertThat(limit).isEqualTo(3); verify(api).reserve(argThat(r -> r.actorId()==7 && r.consumerKey().equals("PORTAL:7:4")
                    && r.requestKey().equals("request-0001") && r.requestHash().matches("[0-9a-f]{64}")));
            verify(api,never()).settle(any()); return List.of("one","two");
        }, List::size);
        assertThat(rows).hasSize(2); verify(api).settle(new Settle("PORTAL:7:4","request-0001","private-token",2,false));
    }
    @Test void revokedDuringReadReleasesReservationAndDoesNotReturnRows() {
        when(api.reserve(any())).thenReturn(CommonResult.success(reserved)); when(api.settle(any())).thenReturn(CommonResult.success(reserved));
        assertThatThrownBy(() -> MarketConsumptionService.read(service,snapshot,Map.of(),100, limit -> {
            throw new IllegalStateException("authorization changed");
        }, ignored -> 0)).hasMessage("authorization changed");
        verify(api).settle(new Settle("PORTAL:7:4","request-0001","private-token",null,true));
    }
    @Test void lostCommitReplyQueriesOriginalReceiptWithoutReadingAgain() {
        when(api.reserve(any())).thenReturn(CommonResult.success(reserved));
        when(api.settle(any())).thenThrow(new IllegalStateException("transport lost"));
        when(api.receipt("PORTAL:7:4","request-0001",7)).thenReturn(CommonResult.success(committed()));
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        var result=MarketConsumptionService.read(service,snapshot,Map.of(),10,limit -> { reads.incrementAndGet(); return List.of(1,2); },List::size);
        assertThat(result).containsExactly(1,2); assertThat(reads).hasValue(1);
        verify(api,times(1)).settle(any());
    }
    @Test void unknownReceiptNeverReturnsQueriedRows() {
        when(api.reserve(any())).thenReturn(CommonResult.success(reserved)); when(api.settle(any())).thenThrow(new IllegalStateException("transport lost"));
        when(api.receipt(anyString(),anyString(),anyLong())).thenReturn(CommonResult.success(reserved));
        assertThatThrownBy(() -> MarketConsumptionService.read(service,snapshot,Map.of(),10,limit -> List.of(1,2),List::size)).hasMessageContaining("查看消费回执");
    }
    @Test void unlimitedPathNeedsNoQuotaRpcAndKeepsLimit() {
        List<Integer> result = MarketConsumptionService.read(null,null,Map.of(),20,limit -> List.of(limit),List::size);
        assertThat(result).containsExactly(20);
        verifyNoInteractions(api);
    }
}
