package com.vertex.admin.web.security;

import com.vertex.admin.web.config.JwtUtil;
import com.vertex.common.core.config.TokenProperties;
import com.vertex.common.core.context.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

/**
 * JWT 认证过滤器：从请求头解析 Bearer token，校验后设置 SecurityContext 和 UserContext
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final TokenProperties tokenProperties;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String authHeader = request.getHeader(tokenProperties.getHeader());
        // 兼容 "Bearer " 或误传的 "Bearer"（无空格）+ token
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer")) {
            String token = authHeader.substring(6).trim();
            try {
                String username = jwtUtil.getUsernameFromToken(token);
                Long userId = jwtUtil.getUserIdFromToken(token);
                Integer accountType = jwtUtil.getAccountTypeFromToken(token);
                if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            username,
                            null,
                            new ArrayList<>()
                    );
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    // 设置用户上下文，用于数据权限控制
                    UserContext.set(userId, username, accountType);
                }
            } catch (Exception e) {
                // token 无效或过期，不设置 SecurityContext，后续会 401
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 请求结束后清理 ThreadLocal，防止线程池复用时数据污染
            UserContext.clear();
        }
    }
}
