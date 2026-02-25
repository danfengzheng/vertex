package com.vertex.api.trading;

import com.vertex.model.dto.trading.ExchangeAccountCreateDTO;
import com.vertex.model.dto.trading.ExchangeAccountUpdateDTO;
import com.vertex.model.vo.trading.AssetBalanceVO;
import com.vertex.model.vo.trading.ExchangeAccountVO;

import java.util.List;

/**
 * 交易所账户服务接口
 */
public interface IExchangeAccountService {

    Long create(ExchangeAccountCreateDTO dto);

    void update(ExchangeAccountUpdateDTO dto);

    void delete(Long id);

    ExchangeAccountVO getById(Long id);

    List<ExchangeAccountVO> list();

    boolean testConnection(Long id);

    /**
     * 查询账户全量资产余额（仅返回 free+locked > 0 的资产）
     */
    List<AssetBalanceVO> getBalance(Long id);
}
