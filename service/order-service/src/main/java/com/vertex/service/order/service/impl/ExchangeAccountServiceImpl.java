package com.vertex.service.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vertex.api.trading.IExchangeAccountService;
import com.vertex.common.core.GlobalError;
import com.vertex.common.core.crypto.AesGcmCryptoService;
import com.vertex.common.core.exception.BizException;
import com.vertex.model.dto.trading.ExchangeAccountCreateDTO;
import com.vertex.model.dto.trading.ExchangeAccountUpdateDTO;
import com.vertex.model.entity.trading.ExchangeAccount;
import com.vertex.model.vo.trading.ExchangeAccountVO;
import com.vertex.service.order.client.BinanceTradeClient;
import com.vertex.service.order.mapper.ExchangeAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
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
        accountMapper.deleteById(id);
    }

    @Override
    public ExchangeAccountVO getById(Long id) {
        ExchangeAccount account = accountMapper.selectById(id);
        if (account == null) {
            throw new BizException(GlobalError.ACCOUNT_NOT_FOUND);
        }
        return toVO(account);
    }

    @Override
    public List<ExchangeAccountVO> list() {
        LambdaQueryWrapper<ExchangeAccount> wrapper = new LambdaQueryWrapper<ExchangeAccount>()
                .eq(ExchangeAccount::getDeleted, 0)
                .orderByDesc(ExchangeAccount::getCreateTime);
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
        if (account.getStatus() == 0) {
            throw new BizException(GlobalError.ACCOUNT_DISABLED);
        }

        String apiKey = cryptoService.decrypt(account.getApiKey());
        String apiSecret = cryptoService.decrypt(account.getApiSecret());
        return binanceTradeClient.testConnection(apiKey, apiSecret);
    }

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
