package com.vertex.service.order.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.api.trading.IExchangeAccountService;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.context.UserContext;
import com.vertex.common.core.crypto.AesGcmCryptoService;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.dto.trading.ExchangeAccountCreateDTO;
import com.vertex.model.dto.trading.ExchangeAccountUpdateDTO;
import com.vertex.model.entity.trading.ExchangeAccount;
import com.vertex.model.entity.trading.MarketType;
import com.vertex.model.vo.trading.AssetBalanceVO;
import com.vertex.model.vo.trading.ExchangeAccountVO;
import com.vertex.service.order.client.BinanceFuturesClient;
import com.vertex.service.order.client.BinanceTradeClient;
import com.vertex.service.order.mapper.ExchangeAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 交易所账户服务实现
 * <p>
 * 支持现货（SPOT）和合约（USDM / COINM）账户。
 * 余额查询、连通性测试等操作根据 marketType 路由到不同的客户端。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeAccountServiceImpl implements IExchangeAccountService {

    private final ExchangeAccountMapper accountMapper;
    private final AesGcmCryptoService cryptoService;
    private final BinanceTradeClient binanceTradeClient;
    private final BinanceFuturesClient binanceFuturesClient;

    // ─── 增删改查 ────────────────────────────────────────────

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
        account.setMarketType(dto.getMarketType() != null ? dto.getMarketType() : MarketType.SPOT);
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
        if (dto.getMarketType() != null) {
            account.setMarketType(dto.getMarketType());
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

    // ─── 连通性测试 ──────────────────────────────────────────

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

        String apiKey    = cryptoService.decrypt(account.getApiKey());
        String apiSecret = cryptoService.decrypt(account.getApiSecret());

        MarketType marketType = account.getMarketType();
        if (marketType != null && marketType.isFutures()) {
            return binanceFuturesClient.testConnection(apiKey, apiSecret, marketType);
        }
        return binanceTradeClient.testConnection(apiKey, apiSecret);
    }

    // ─── 余额查询 ─────────────────────────────────────────────

    @Override
    public List<AssetBalanceVO> getBalance(Long id) {
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

        String apiKey    = cryptoService.decrypt(account.getApiKey());
        String apiSecret = cryptoService.decrypt(account.getApiSecret());

        MarketType marketType = account.getMarketType();
        if (marketType != null && marketType.isFutures()) {
            return getFuturesBalance(apiKey, apiSecret, marketType);
        }
        return getSpotBalance(apiKey, apiSecret);
    }

    private List<AssetBalanceVO> getSpotBalance(String apiKey, String apiSecret) {
        JSONObject accountData = binanceTradeClient.getAccount(apiKey, apiSecret);
        JSONArray balances = accountData.getJSONArray("balances");
        List<AssetBalanceVO> result = new ArrayList<>();
        if (balances != null) {
            for (int i = 0; i < balances.size(); i++) {
                JSONObject b = balances.getJSONObject(i);
                BigDecimal free   = b.getBigDecimal("free");
                BigDecimal locked = b.getBigDecimal("locked");
                if (free == null)   free   = BigDecimal.ZERO;
                if (locked == null) locked = BigDecimal.ZERO;
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
        result.sort((a, b) -> b.getTotal().compareTo(a.getTotal()));
        return result;
    }

    private List<AssetBalanceVO> getFuturesBalance(String apiKey, String apiSecret, MarketType marketType) {
        JSONObject accountData = binanceFuturesClient.getAccount(apiKey, apiSecret, marketType);
        // USDM/COINM 合约账户余额字段：assets 数组
        JSONArray assets = accountData.getJSONArray("assets");
        List<AssetBalanceVO> result = new ArrayList<>();
        if (assets != null) {
            for (int i = 0; i < assets.size(); i++) {
                JSONObject a = assets.getJSONObject(i);
                BigDecimal available = a.getBigDecimal("availableBalance");
                BigDecimal wallet    = a.getBigDecimal("walletBalance");
                if (available == null) available = BigDecimal.ZERO;
                if (wallet == null)    wallet    = BigDecimal.ZERO;
                if (wallet.compareTo(BigDecimal.ZERO) > 0) {
                    result.add(AssetBalanceVO.builder()
                            .asset(a.getString("asset"))
                            .free(available)
                            .locked(wallet.subtract(available))
                            .total(wallet)
                            .build());
                }
            }
        }
        result.sort((a, b) -> b.getTotal().compareTo(a.getTotal()));
        return result;
    }

    // ─── 内部服务调用（无数据权限校验）──────────────────────

    /**
     * 获取解密后的 API 凭据（仅供内部服务调用）
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
     * 获取账户实体（仅供内部服务调用，含状态校验）
     */
    public ExchangeAccount getAccountEntity(Long accountId) {
        ExchangeAccount account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new BizException(GlobalError.ACCOUNT_NOT_FOUND);
        }
        if (account.getStatus() == 0) {
            throw new BizException(GlobalError.ACCOUNT_DISABLED);
        }
        return account;
    }

    /**
     * 查询指定账户中某资产的可用余额（内部调用）
     * <p>
     * 根据账户 marketType 自动路由：SPOT → balances[].free；USDM/COINM → assets[].availableBalance
     *
     * @param accountId 交易所账户 ID
     * @param asset     资产名称（如 "USDT"）
     * @return 可用余额，查询失败返回 null
     */
    public BigDecimal getAvailableBalance(Long accountId, String asset) {
        try {
            ExchangeAccount account = accountMapper.selectById(accountId);
            if (account == null || account.getStatus() == 0) return null;

            String apiKey    = cryptoService.decrypt(account.getApiKey());
            String apiSecret = cryptoService.decrypt(account.getApiSecret());

            MarketType marketType = account.getMarketType();
            if (marketType != null && marketType.isFutures()) {
                return getFuturesAvailableBalance(apiKey, apiSecret, marketType, asset);
            }
            return getSpotAvailableBalance(apiKey, apiSecret, asset);
        } catch (Exception e) {
            log.warn("Failed to query balance for account {}, asset {}: {}",
                    accountId, asset, e.getMessage());
            return null;
        }
    }

    private BigDecimal getSpotAvailableBalance(String apiKey, String apiSecret, String asset) {
        JSONObject accountData = binanceTradeClient.getAccount(apiKey, apiSecret);
        JSONArray balances = accountData.getJSONArray("balances");
        if (balances != null) {
            for (int i = 0; i < balances.size(); i++) {
                JSONObject b = balances.getJSONObject(i);
                if (asset.equalsIgnoreCase(b.getString("asset"))) {
                    return b.getBigDecimal("free");
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal getFuturesAvailableBalance(String apiKey, String apiSecret,
                                                   MarketType marketType, String asset) {
        JSONObject accountData = binanceFuturesClient.getAccount(apiKey, apiSecret, marketType);
        JSONArray assets = accountData.getJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.size(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (asset.equalsIgnoreCase(a.getString("asset"))) {
                    return a.getBigDecimal("availableBalance");
                }
            }
        }
        return BigDecimal.ZERO;
    }

    // ─── VO 转换 ──────────────────────────────────────────────

    private ExchangeAccountVO toVO(ExchangeAccount account) {
        return ExchangeAccountVO.builder()
                .id(account.getId())
                .name(account.getName())
                .exchange(account.getExchange())
                .marketType(account.getMarketType())
                .status(account.getStatus())
                .createTime(account.getCreateTime())
                .updateTime(account.getUpdateTime())
                .build();
    }
}
