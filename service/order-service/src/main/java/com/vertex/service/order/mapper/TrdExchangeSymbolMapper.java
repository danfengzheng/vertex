package com.vertex.service.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.trading.TrdExchangeSymbol;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 交易所币对映射 Mapper
 */
@Mapper
public interface TrdExchangeSymbolMapper extends BaseMapper<TrdExchangeSymbol> {

    /**
     * 查询指定交易所 + 市场类型的全量记录，包含软删除记录（绕过 @TableLogic 自动过滤）。
     * 用于同步时检测已删除记录，避免重新 INSERT 时命中 uk_exchange_symbol_type 唯一键冲突。
     */
    @Select("SELECT * FROM trd_exchange_symbol WHERE exchange = #{exchange} AND market_type = #{marketType}")
    List<TrdExchangeSymbol> selectAllByExchangeAndType(@Param("exchange") String exchange,
                                                       @Param("marketType") String marketType);

    /**
     * 更新并恢复（同时将 deleted 置回 0），绕过 @TableLogic 为 updateById 自动追加的 WHERE deleted=0。
     * 用于同步时统一更新所有记录（含已软删除记录），确保恢复数据完整性。
     */
    @Update("UPDATE trd_exchange_symbol " +
            "SET exchange_symbol = #{es.exchangeSymbol}, " +
            "    status          = #{es.status}, " +
            "    lot_size        = #{es.lotSize}, " +
            "    tick_size       = #{es.tickSize}, " +
            "    min_notional    = #{es.minNotional}, " +
            "    sync_time       = #{es.syncTime}, " +
            "    deleted         = 0, " +
            "    update_time     = NOW() " +
            "WHERE id = #{es.id}")
    int updateAndRestoreById(@Param("es") TrdExchangeSymbol es);
}
