package com.vertex.common.core.utils;

/**
 * IdGenerator
 *
 * @author eth
 * @version 1.0
 * @description
 * @date 2026/1/13 23:43
 */

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;

/**
 * ID生成器
 */
public class IdGenerator {

    private static final Snowflake snowflake = IdUtil.getSnowflake(1, 1);

    /**
     * 生成雪花ID
     */
    public static Long generateId() {
        return snowflake.nextId();
    }

    /**
     * 生成UUID
     */
    public static String generateUuid() {
        return IdUtil.simpleUUID();
    }
}
