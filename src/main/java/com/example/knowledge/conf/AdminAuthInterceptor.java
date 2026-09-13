package com.example.knowledge.conf;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 管理接口鉴权拦截器：配置了 app.rag.auth-token 时，
 * 要求请求头 X-Admin-Token 与其一致，否则拒绝。
 */
@Slf4j
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final String HEADER = "X-Admin-Token";

    private final RagProperties props;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String token = props.getAuthToken();
        if (!StringUtils.hasText(token)) {
            log.warn("未配置 app.rag.auth-token，管理接口 [{}] 处于无鉴权开发模式", request.getRequestURI());
            return true;
        }
        if (token.equals(request.getHeader(HEADER))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"缺少或错误的 X-Admin-Token\"}");
        return false;
    }
}
