package com.vertex.service.mm.grid.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vertex.model.entity.trading.ExchangeAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Component;

/**
 * 从 trd_exchange_account 表拉 API key / secret。
 * <p>
 * mm-service 不引入 order-service 的复杂账户逻辑，只做最小 credentials 查询。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountCredentialResolver {

    private final ExchangeAccountMapper mapper;

    /** 结果 —— 明文 credentials，只在内存传递，不落库不打印 */
    public record Credentials(String apiKey, String apiSecret) {}

    /** Paper 模式占位凭证 —— PaperOrderClient 不会真的用它签名，只是让下游不 NPE */
    private static final Credentials PAPER_STUB = new Credentials("PAPER_STUB_KEY", "PAPER_STUB_SECRET");

    public Credentials resolve(long accountId) {
        ExchangeAccount acc = mapper.selectById(accountId);
        if (acc == null) {
            throw new IllegalArgumentException("account not found: " + accountId
                    + " (请先在'交易账户'页面录入 API 凭证，然后填写正确的 accountId)");
        }
        if (acc.getApiKey() == null || acc.getApiSecret() == null) {
            throw new IllegalStateException("account " + accountId + " has no api credentials");
        }
        return new Credentials(acc.getApiKey(), acc.getApiSecret());
    }

    /**
     * PAPER 模式安全 resolve —— 若账户不存在或无凭证，返回占位而非抛异常。
     * <p>
     * PAPER 走 PaperOrderClient 全程不签名，凭证只用来避免 null 传递；
     * 允许用户在没配任何真实账户的情况下就能跑模拟研究。
     * </p>
     */
    public Credentials resolveOrPaper(long accountId, boolean isPaper) {
        if (!isPaper) return resolve(accountId);
        try {
            ExchangeAccount acc = mapper.selectById(accountId);
            if (acc != null && acc.getApiKey() != null && acc.getApiSecret() != null) {
                return new Credentials(acc.getApiKey(), acc.getApiSecret());
            }
        } catch (Exception ignored) { /* fall through */ }
        log.info("[AccountResolver] PAPER strategy accountId={} using stub credentials", accountId);
        return PAPER_STUB;
    }

    /** 最小 mapper —— 只用于 mm-service 内部读取 */
    @Mapper
    public interface ExchangeAccountMapper extends BaseMapper<ExchangeAccount> {}
}
