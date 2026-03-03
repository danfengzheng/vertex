package com.vertex.admin.web.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.vertex.common.core.context.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * MyBatis-Plus 自动填充处理器
 * <p>
 * 配合 {@link com.vertex.common.core.base.BaseEntity} 中的
 * {@code @TableField(fill = FieldFill.INSERT)} 和
 * {@code @TableField(fill = FieldFill.INSERT_UPDATE)} 注解，
 * 在 insert / update 时自动填充 createTime、updateTime、createBy、updateBy 字段。
 * 统一使用 UTC 存储，前端按浏览器时区展示。
 * </p>
 */
@Slf4j
@Component
public class MyBatisMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
        Long userId = UserContext.getUserId();
        if (userId != null) {
            this.strictInsertFill(metaObject, "createBy", Long.class, userId);
            this.strictInsertFill(metaObject, "updateBy", Long.class, userId);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.setFieldValByName("updateTime", LocalDateTime.now(ZoneOffset.UTC), metaObject);
        Long userId = UserContext.getUserId();
        if (userId != null) {
            this.setFieldValByName("updateBy", userId, metaObject);
        }
    }
}
