package com.vertex.model.query;


import com.vertex.common.core.constant.CommonConstant;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 分页查询基类
 */
@Data
public class PageQuery {

    @Min(value = 1, message = "页码最小为1")
    private Integer pageNum = CommonConstant.DEFAULT_PAGE_NUM;

    @Min(value = 1, message = "每页条数最小为1")
    @Max(value = 100, message = "每页条数最大为100")
    private Integer pageSize = CommonConstant.DEFAULT_PAGE_SIZE;
}
