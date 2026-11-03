package cn.iocoder.yudao.module.system.api.user;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.system.api.user.dto.*;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.user.UserPageReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.dal.dataobject.dept.DeptDO;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import cn.iocoder.yudao.module.system.service.dept.DeptService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class AdminUserOptionApiTest {
 @Test void optionProjectionUsesTrueAccountAndMobileFiltersWithActiveStateAndNoCredentials()throws Exception{
  var service=mock(AdminUserService.class);var departments=mock(DeptService.class);var api=new AdminUserOptionApiImpl();
  var servlet=new org.springframework.mock.web.MockHttpServletRequest();servlet.setSecure(true);
  var certificate=mock(java.security.cert.X509Certificate.class);when(certificate.getEncoded()).thenReturn("fixture-admin-option".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  servlet.setAttribute(cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE,new java.security.cert.X509Certificate[]{certificate});
  String pin=cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver.fingerprint(certificate);
  new cn.iocoder.yudao.module.system.framework.security.core.AdminUserOptionRpcFilter(()->java.util.Map.of("governance",java.util.Set.of(pin))).doFilter(servlet,new org.springframework.mock.web.MockHttpServletResponse(),(a,b)->{});
  ReflectionTestUtils.setField(api,"servletRequest",servlet);
  ReflectionTestUtils.setField(api,"userService",service);ReflectionTestUtils.setField(api,"deptService",departments);
  var person=new AdminUserDO();person.setId(9007199254740993L);person.setUsername("local_operator");person.setNickname("本地姓名");person.setMobile("13800000000");person.setDeptId(103L);person.setPassword("private-test-password-sentinel");
  var dept=new DeptDO();dept.setId(103L);dept.setName("测试部门");
  when(service.getUserPage(any())).thenReturn(new PageResult<>(List.of(person),1L));when(departments.getDept(103L)).thenReturn(dept);
  var req=new AdminUserOptionPageReqDTO();req.setPageNo(2);req.setPageSize(20);req.setUsername("local");req.setMobile("138");
  var result=api.getUserOptionPage(req).getCheckedData();var capture=ArgumentCaptor.forClass(UserPageReqVO.class);verify(service).getUserPage(capture.capture());
  assertThat(capture.getValue().getUsername()).isEqualTo("local");assertThat(capture.getValue().getMobile()).isEqualTo("138");assertThat(capture.getValue().getStatus()).isZero();assertThat(capture.getValue().getPageNo()).isEqualTo(2);
  var row=result.getList().getFirst();assertThat(row.getUsername()).isEqualTo("local_operator");assertThat(row.getNickname()).isEqualTo("本地姓名");assertThat(row.getDeptName()).isEqualTo("测试部门");
  assertThat(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(row)).doesNotContain("private-test-password-sentinel","password");
  var method=AdminUserOptionApiImpl.class.getMethod("getUserOptionPage",AdminUserOptionPageReqDTO.class);
  assertThat(method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value()).contains("governance:whitelist:query");
 }
}
