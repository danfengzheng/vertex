package com.vertex.service.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.api.trading.IExchangeAccountService;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.crypto.AesGcmCryptoService;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.dto.trading.ExchangeAccountCreateDTO;
import com.vertex.model.dto.trading.ExchangeAccountUpdateDTO;
import com.vertex.model.entity.trading.ExchangeAccount;
import com.vertex.model.vo.trading.AssetBalanceVO;
import com.vertex.model.vo.trading.ExchangeAccountVO;
import com.vertex.common.core.context.UserContext;
import com.vertex.service.order.client.BinanceTradeClient;
import com.vertex.service.order.mapper.ExchangeAccountMapper;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 交易所账户服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeAccountServiceImpl implements IExchangeAccountService {

    private final ExchangeAccountMapper accountMapper;
    private final AesGcmCryptoService cryptoService;
    private final BinanceTradeClient binanceTradeClient;

    @Override
    public Long create(ExchangeAccountCreateDTO dto) {
        // 检查名称唯一
        LambdaQueryWrapper<ExchangeAccount> wrapper = new LambdaQueryWrapper<ExchangeAccount>()
                .eq(ExchangeAccount::getName, dto.getName())
                .eq(ExchangeAccount::getDeleted, 0);
        if (accountMapper.selectCount(wrapper) > 0) {
            throw new BizException(GlobalError.ACCOUNT_NAME_EXISTS);
        }

        ExchangeAccount account = new ExchangeAccount();
        account.setName(dto.getName());
        account.setExchange(dto.getExchange());
        account.setApiKey(cryptoService.encrypt(dto.getApiKey()));
        account.setApiSecret(cryptoService.encrypt(dto.getApiSecret()));
        account.setStatus(1);

        accountMapper.insert(account);
        return account.getId();
    }

    @Override
    public void update(ExchangeAccountUpdateDTO dto) {
        ExchangeAccount account = accountMapper.selectById(dto.getId());
        if (account == null) {
            throw new BizException(GlobalError.ACCOUNT_NOT_FOUND);
        }
        if (!UserContext.isAdmin() && !Objects.equals(account.getCreateBy(), UserContext.getUserId())) {
            throw new BizException(GlobalError.FORBIDDEN);
        }

        if (dto.getName() != null) {
            // 检查名称唯一（排除自身）
            LambdaQueryWrapper<ExchangeAccount> wrapper = new LambdaQueryWrapper<ExchangeAccount>()
                    .eq(ExchangeAccount::getName, dto.getName())
                    .ne(ExchangeAccount::getId, dto.getId())
                    .eq(ExchangeAccount::getDeleted, 0);
            if (accountMapper.selectCount(wrapper) > 0) {
                throw new BizException(GlobalError.ACCOUNT_NAME_EXISTS);
            }
            account.setName(dto.getName());
        }

        if (dto.getApiKey() != null && !dto.getApiKey().isBlank()) {
            account.setApiKey(cryptoService.encrypt(dto.getApiKey()));
        }
        if (dto.getApiSecret() != null && !dto.getApiSecret().isBlank()) {
            account.setApiSecret(cryptoService.encrypt(dto.getApiSecret()));
        }

        accountMapper.updateById(account);
    }

    @Override
    public void delete(Long id) {
        ExchangeAccount account = accountMapper.selectById(id);
        if (account == null) {
            throw new BizException(GlobalError.ACCOUNT_NOT_FOUND);
        }
        if (!UserContext.isAdmin() && !Objects.equals(account.getCreateBy(), UserContext.getUserId())) {
            throw new BizException(GlobalError.FORBIDDEN);
        }
        accountMapper.deleteById(id);
    }

    @Override
    public ExchangeAccountVO getById(Long id) {
        ExchangeAccount account = accountMapper.selectById(id);
        if (account == null) {
            throw new BizException(GlobalError.ACCOUNT_NOT_FOUND);
        }
        if (!UserContext.isAdmin() && !Objects.equals(account.getCreateBy(), UserContext.getUserId())) {
            throw new BizException(GlobalError.FORBIDDEN);
        }
        return toVO(account);
    }

    @Override
    public List<ExchangeAccountVO> list() {
        LambdaQueryWrapper<ExchangeAccount> wrapper = new LambdaQueryWrapper<ExchangeAccount>()
                .eq(ExchangeAccount::getDeleted, 0);

        if (!UserContext.isAdmin()) {
            wrapper.eq(ExchangeAccount::getCreateBy, UserContext.getUserId());
        }

        wrapper.orderByDesc(ExchangeAccount::getCreateTime);
        return accountMapper.selectList(wrapper).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    public boolean testConnection(Long id) {
        ExchangeAccount account = accountMapper.selectById(id);
        if (account == null) {
            throw new BizException(GlobalError.ACCOUNT_NOT_FOUND);
        }
        if (!UserContext.isAdmin() && !Objects.equals(account.getCreateBy(), UserContext.getUserId())) {
            throw new BizException(GlobalError.FORBIDDEN);
        }
        if (account.getStatus() == 0) {
            throw new BizException(GlobalError.ACCOUNT_DISABLED);
        }

        String apiKey = cryptoService.decrypt(account.getApiKey());
        String apiSecret = cryptoService.decrypt(account.getApiSecret());
        return binanceTradeClient.testConnection(apiKey, apiSecret);
    }

    /**
     * 获取解密后的 API 凭据（仅供内部服务调用）
     * 注意：此方法由 TradeExecutionService 等内部服务调用，不做数据权限校验
     */
    public String[] getDecryptedCredentials(Long accountId) {
        ExchangeAccount account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new BizException(GlobalError.ACCOUNT_NOT_FOUND);
        }
        if (account.getStatus() == 0) {
            throw new BizException(GlobalError.ACCOUNT_DISABLED);
        }
        return new String[]{
                cryptoService.decrypt(account.getApiKey()),
                cryptoService.decrypt(account.getApiSecret())
        };
    }

    /**
     * 获取解密后的 API 凭据（面向用户请求，含数据权限校验）
     */
    private String[] getDecryptedCredentialsWithPermissionCheck(Long accountId) {
        ExchangeAccount account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new BizException(GlobalError.ACCOUNT_NOT_FOUND);
        }
        if (!UserContext.isAdmin() && !Objects.equals(account.getCreateBy(), UserContext.getUserId())) {
            throw new BizException(GlobalError.FORBIDDEN);
        }
        if (account.getStatus() == 0) {
            throw new BizException(GlobalError.ACCOUNT_DISABLED);
        }
        return new String[]{
                cryptoService.decrypt(account.getApiKey()),
                cryptoService.decrypt(account.getApiSecret())
        };
    }

    /**
     * 查询账户全量资产余额，返回所有 free+locked > 0 的资产
     */
    @Override
    public List<AssetBalanceVO> getBalance(Long id) {
        String[] credentials = getDecryptedCredentialsWithPermissionCheck(id);
        JSONObject account = binanceTradeClient.getAccount(credentials[0], credentials[1]);
        JSONArray balances = account.getJSONArray("balances");
        List<AssetBalanceVO> result = new java.util.ArrayList<>();
        if (balances != null) {
            for (int i = 0; i < balances.size(); i++) {
                JSONObject b = balances.getJSONObject(i);
                BigDecimal free   = b.getBigDecimal("free");
                BigDecimal locked = b.getBigDecimal("locked");
                if (free == null) free = BigDecimal.ZERO;
                if (locked == null) locked = BigDecimal.ZERO;
                // 只返回持有资产
                if (free.compareTo(BigDecimal.ZERO) > 0 || locked.compareTo(BigDecimal.ZERO) > 0) {
                    result.add(AssetBalanceVO.builder()
                            .asset(b.getString("asset"))
                            .free(free)
                            .locked(locked)
                            .total(free.add(locked))
                            .build());
                }
            }
        }
        // 按 total 降序排列，USDT 等主资产排前
        result.sort((a, b2) -> b2.getTotal().compareTo(a.getTotal()));
        return result;
    }

    /**
     * 查询指定账户中某资产的可用余额
     *
     * @param accountId 交易所账户ID
     * @param asset     资产名称（如 "USDT"）
     * @return 可用余额，查询失败返回 null
     */
    public BigDecimal getAvailableBalance(Long accountId, String asset) {
        try {
            String[] credentials = getDecryptedCredentials(accountId);
            JSONObject account = binanceTradeClient.getAccount(credentials[0], credentials[1]);
            JSONArray balances = account.getJSONArray("balances");
            if (balances != null) {
                for (int i = 0; i < balances.size(); i++) {
                    JSONObject balance = balances.getJSONObject(i);
                    if (asset.equalsIgnoreCase(balance.getString("asset"))) {
                        return balance.getBigDecimal("free");
                    }
                }
            }
            return BigDecimal.ZERO;
        } catch (Exception e) {
            log.warn("Failed to query balance for account {}, asset {}: {}",
                    accountId, asset, e.getMessage());
            return null;
        }
    }

    private ExchangeAccountVO toVO(ExchangeAccount account) {
        return ExchangeAccountVO.builder()
                .id(account.getId())
                .name(account.getName())
                .exchange(account.getExchange())
                .status(account.getStatus())
                .createTime(account.getCreateTime())
                .updateTime(account.getUpdateTime())
                .build();
    }
}
