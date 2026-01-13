package com.vertex.common.core.constant;

/**
 * 通用常量
 */
public interface CommonConstant {

    /** 成功标记 */
    Integer SUCCESS = 200;

    /** 失败标记 */
    Integer FAIL = 500;

    /** UTF-8 字符集 */
    String UTF8 = "UTF-8";

    /** 默认页码 */
    Integer DEFAULT_PAGE_NUM = 1;

    /** 默认每页条数 */
    Integer DEFAULT_PAGE_SIZE = 10;

    /** 最大每页条数 */
    Integer MAX_PAGE_SIZE = 100;

    /** Token前缀 */
    String TOKEN_PREFIX = "Bearer ";

    /** Token请求头 */
    String TOKEN_HEADER = "Authorization";
}
