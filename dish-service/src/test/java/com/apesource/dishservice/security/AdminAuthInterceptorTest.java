package com.apesource.dishservice.security;

import com.apesource.dishservice.mapper.UserRoleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理员鉴权的单元测试。
 *
 * <p>刻意写成纯 Mockito 测试、不启动 Spring 容器：
 * 拦截器的判定逻辑不该依赖 Nacos、MySQL、Redis 任何一个 ——
 * 一旦写成 {@code @SpringBootTest}，这个测试就会因为"本地没起注册中心"而跑不起来，
 * 于是没人会去跑它，权限回归也就没人守了。
 *
 * <p>覆盖的场景对应论文里"接口权限测试"那张表：
 * 管理员放行 / 普通用户 403 / 无身份 401 / 非法身份 401 /
 * 管理前缀路径即使漏了注解也必须拦住 / 普通接口不查库。
 */
class AdminAuthInterceptorTest {

    /** 参照用的假 Controller，只为了造出 HandlerMethod */
    static class DummyController {

        @AdminOnly("测试用管理接口")
        public void adminEndpoint() {
        }

        public void normalEndpoint() {
        }
    }

    private UserRoleMapper userRoleMapper;
    private AdminAuthInterceptor interceptor;
    private HttpServletResponse response;
    private StringWriter responseBody;

    @BeforeEach
    void setUp() throws Exception {
        userRoleMapper = mock(UserRoleMapper.class);
        interceptor = new AdminAuthInterceptor(userRoleMapper, new ObjectMapper());

        responseBody = new StringWriter();
        response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    private HttpServletRequest request(String uri, String userIdHeader) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("X-User-Id")).thenReturn(userIdHeader);
        return request;
    }

    private HandlerMethod handler(String methodName) throws Exception {
        return new HandlerMethod(new DummyController(), DummyController.class.getMethod(methodName));
    }

    @Test
    @DisplayName("管理员访问管理接口：放行")
    void adminPasses() throws Exception {
        when(userRoleMapper.selectRoleById(1001L)).thenReturn(1);

        boolean allowed = interceptor.preHandle(
                request("/ai/es/reindex", "1001"), response, handler("adminEndpoint"));

        assertThat(allowed).isTrue();
        assertThat(responseBody.toString()).isEmpty();
    }

    @Test
    @DisplayName("普通用户访问管理接口：403 且带明确提示")
    void normalUserForbidden() throws Exception {
        when(userRoleMapper.selectRoleById(2002L)).thenReturn(0);

        boolean allowed = interceptor.preHandle(
                request("/ai/es/reindex", "2002"), response, handler("adminEndpoint"));

        assertThat(allowed).isFalse();
        verify(response).setStatus(403);
        assertThat(responseBody.toString()).contains("需要管理员权限");
    }

    @Test
    @DisplayName("查不到用户（角色为 null）：同样拒绝，不能当成普通用户放行")
    void unknownUserForbidden() throws Exception {
        when(userRoleMapper.selectRoleById(anyLong())).thenReturn(null);

        boolean allowed = interceptor.preHandle(
                request("/ai/es/reindex", "9999"), response, handler("adminEndpoint"));

        assertThat(allowed).isFalse();
        verify(response).setStatus(403);
    }

    @Test
    @DisplayName("没带 X-User-Id：401，而不是 403")
    void missingIdentityUnauthorized() throws Exception {
        boolean allowed = interceptor.preHandle(
                request("/ai/es/reindex", null), response, handler("adminEndpoint"));

        assertThat(allowed).isFalse();
        verify(response).setStatus(401);
        verify(userRoleMapper, never()).selectRoleById(anyLong());
    }

    @Test
    @DisplayName("X-User-Id 不是数字：401，且不查库")
    void malformedIdentityUnauthorized() throws Exception {
        boolean allowed = interceptor.preHandle(
                request("/ai/es/reindex", "abc"), response, handler("adminEndpoint"));

        assertThat(allowed).isFalse();
        verify(response).setStatus(401);
        verify(userRoleMapper, never()).selectRoleById(anyLong());
    }

    @Test
    @DisplayName("管理前缀路径即使漏了 @AdminOnly：仍然按管理接口拦住")
    void adminPrefixWithoutAnnotationStillGuarded() throws Exception {
        // 这是本拦截器最重要的一条兜底：新增管理接口忘了加注解，
        // 不能因此变成"谁都能调"。这里用普通方法 + 管理前缀来模拟那种情况。
        when(userRoleMapper.selectRoleById(2002L)).thenReturn(0);

        boolean allowed = interceptor.preHandle(
                request("/ai/admin/dish/123/unpublish", "2002"), response, handler("normalEndpoint"));

        assertThat(allowed).isFalse();
        verify(response).setStatus(403);
    }

    @Test
    @DisplayName("普通接口：直接放行，且完全不查角色（不产生多余查询）")
    void normalEndpointBypassesRoleLookup() throws Exception {
        boolean allowed = interceptor.preHandle(
                request("/ai/dish/order/123/publish", "2002"), response, handler("normalEndpoint"));

        assertThat(allowed).isTrue();
        verify(userRoleMapper, never()).selectRoleById(anyLong());
    }

    @Test
    @DisplayName("非 Controller 的 handler（静态资源等）：放行")
    void nonHandlerMethodPasses() throws Exception {
        boolean allowed = interceptor.preHandle(
                request("/ai/es/reindex", null), response, new Object());

        assertThat(allowed).isTrue();
    }
}
