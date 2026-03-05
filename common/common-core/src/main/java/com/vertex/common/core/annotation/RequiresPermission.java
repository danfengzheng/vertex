package com.vertex.common.core.annotation;

import java.lang.annotation.*;

/**
 * 接口权限校验注解
 * <p>
 * 标注在 Controller 方法上，AOP 切面会校验当前用户是否拥有指定权限码。
 * 管理员（accountType=0）自动跳过校验。
 *
 * <pre>
 *   {@literal @}RequiresPermission("trade:account")
 *   public Result<Long> create(...) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPermission {

    /**
     * 所需权限码，格式：{domain}:{resource}，如 "system:user"、"trade:account"
     */
    String value();
}
