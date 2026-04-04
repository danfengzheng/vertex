package com.vertex.service.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.trading.TrdSymbol;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 平台通用币对 Mapper
 */
@Mapper
public interface TrdSymbolMapper extends BaseMapper<TrdSymbol> {

    /**
     * 按 symbol 查询软删除记录（绕过 @TableLogic 自动过滤）。
     * 用于同步时检测同名已删除记录，恢复而非重新插入，避免 uk_symbol 唯一键冲突。
     */
    @Select("SELECT * FROM trd_symbol WHERE symbol = #{symbol} AND deleted = 1 LIMIT 1")
    TrdSymbol selectDeletedBySymbol(String symbol);

    /**
     * 按主键恢复软删除记录（将 deleted 置回 0，绕过 @TableLogic 的 WHERE deleted=0 限制）。
     */
    @Update("UPDATE trd_symbol SET deleted = 0, update_time = NOW() WHERE id = #{id}")
    int restoreById(Long id);
}
