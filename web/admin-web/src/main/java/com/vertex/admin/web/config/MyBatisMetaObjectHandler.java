package com.vertex.admin.web.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 自动填充处理器
 * <p>
 * 配合 {@link com.vertex.common.core.base.BaseEntity} 中的
 * {@code @TableField(fill = FieldFill.INSERT)} 和
 * {@code @TableField(fill = FieldFill.INSERT_UPDATE)} 注解，
 * 在 insert / update 时自动填充 createTime、updateTime 等字段。
 * </p>
 */
@Slf4j
@Component
public class MyBatisMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 使用 setFieldValByName 强制更新，即使字段已有值也覆盖为最新时间
        this.setFieldValByName("updateTime", LocalDateTime.now(), metaObject);
    }
}
