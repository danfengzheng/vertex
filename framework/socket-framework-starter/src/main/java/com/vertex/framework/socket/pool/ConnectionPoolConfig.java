package com.vertex.framework.socket.pool;

import lombok.Builder;
import lombok.Data;

/**
 * 连接池配置
 */
@Data
@Builder
public class ConnectionPoolConfig {

    /** 最大连接数 */
    @Builder.Default
    private int maxTotal = 20;

    /** 最大空闲连接数 */
    @Builder.Default
    private int maxIdle = 10;

    /** 最小空闲连接数 */
    @Builder.Default
    private int minIdle = 2;

    /** 获取连接最大等待时间（毫秒） */
    @Builder.Default
    private long maxWaitMs = 5000;

    /** 空闲连接回收间隔（毫秒） */
    @Builder.Default
    private long evictionRunIntervalMs = 30000;

    /** 连接空闲多久被回收（毫秒） */
    @Builder.Default
    private long minEvictableIdleTimeMs = 60000;

    /** 借出时是否检测有效性 */
    @Builder.Default
    private boolean testOnBorrow = true;

    /** 归还时是否检测有效性 */
    @Builder.Default
    private boolean testOnReturn = false;
}
