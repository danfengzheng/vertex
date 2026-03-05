package com.vertex.admin.web.aspect;

import com.vertex.api.permission.IPermissionService;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.annotation.RequiresPermission;
import com.vertex.common.core.context.UserContext;
import com.vertex.common.core.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/**
 * 接口权限校验切面
 * <p>
 * 拦截所有标注了 {@link RequiresPermission} 的 Controller 方法：
 * <ol>
 *   <li>管理员（accountType=0）直接放行</li>
 *   <li>未登录用户返回 401</li>
 *   <li>无权限用户返回 403</li>
 * </ol>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class PermissionAspect {

    private final IPermissionService permissionService;

    @Before("@annotation(rp)")
    public void checkPermission(RequiresPermission rp) {
        // 管理员跳过权限校验
        if (UserContext.isAdmin()) {
            return;
        }

        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(GlobalError.UNAUTHORIZED);
        }

        String required = rp.value();
        if (!permissionService.getPermissionCodes(userId).contains(required)) {
            log.warn("[Permission] userId={} 无权访问，需要权限: {}", userId, required);
            throw new BizException(GlobalError.FORBIDDEN);
        }
    }
}
