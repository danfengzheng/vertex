package com.vertex.service.farb.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.trading.ExchangeAccount;
import com.vertex.model.entity.trading.MarketType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Component;

/**
 * 从 trd_exchange_account 拉 API key/secret，对齐 mm-service.AccountCredentialResolver。
 * <p>
 * Funding arb 需要**两个账户**（spot + perp），分别 resolve。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FarbCredentialResolver {

    private final ExchangeAccountMapper mapper;

    public record Credentials(String apiKey, String apiSecret, MarketType marketType) {}

    private static final Credentials PAPER_SPOT = new Credentials("PAPER_STUB", "PAPER_STUB", MarketType.SPOT);
    private static final Credentials PAPER_PERP = new Credentials("PAPER_STUB", "PAPER_STUB", MarketType.USDM);

    /**
     * 拉现货账户 + 校验 marketType=SPOT。
     */
    public Credentials resolveSpot(long accountId) {
        return resolveTyped(accountId, MarketType.SPOT);
    }

    /**
     * 拉期货账户 + 校验 marketType=USDM。
     */
    public Credentials resolvePerp(long accountId) {
        return resolveTyped(accountId, MarketType.USDM);
    }

    /**
     * PAPER 兜底：不存在或无凭证时返回 stub，不抛异常。
     */
    public Credentials resolveSpotOrPaper(long accountId, boolean isPaper) {
        if (!isPaper) return resolveSpot(accountId);
        try {
            ExchangeAccount acc = mapper.selectById(accountId);
            if (acc != null && acc.getApiKey() != null && acc.getApiSecret() != null) {
                return new Credentials(acc.getApiKey(), acc.getApiSecret(), acc.getMarketType());
            }
        } catch (Exception ignored) { /* fall through */ }
        return PAPER_SPOT;
    }

    public Credentials resolvePerpOrPaper(long accountId, boolean isPaper) {
        if (!isPaper) return resolvePerp(accountId);
        try {
            ExchangeAccount acc = mapper.selectById(accountId);
            if (acc != null && acc.getApiKey() != null && acc.getApiSecret() != null) {
                return new Credentials(acc.getApiKey(), acc.getApiSecret(), acc.getMarketType());
            }
        } catch (Exception ignored) { /* fall through */ }
        return PAPER_PERP;
    }

    // ─── 内部 ────────────────────────────────────────────────────────────

    private Credentials resolveTyped(long accountId, MarketType expected) {
        ExchangeAccount acc = mapper.selectById(accountId);
        if (acc == null) {
            throw new IllegalArgumentException("farb account not found: " + accountId);
        }
        if (acc.getApiKey() == null || acc.getApiSecret() == null) {
            throw new IllegalStateException("farb account " + accountId + " has no api credentials");
        }
        if (acc.getMarketType() != expected) {
            throw new IllegalStateException(String.format(
                    "farb account %d marketType=%s but expected %s (请检查策略绑定的账户 marketType)",
                    accountId, acc.getMarketType(), expected));
        }
        return new Credentials(acc.getApiKey(), acc.getApiSecret(), acc.getMarketType());
    }

    /** 最小 mapper —— 只 farb 内部读取 */
    @Mapper
    public interface ExchangeAccountMapper extends BaseMapper<ExchangeAccount> {
        default LambdaQueryWrapper<ExchangeAccount> qw() {
            return new LambdaQueryWrapper<>();
        }
    }
}
