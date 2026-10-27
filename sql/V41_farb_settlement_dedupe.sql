-- ============================================================================
-- V41: 资金费套利 - 结算记账幂等
--   1) 清理历史重复：同 (position_id, settlement_time) 只保留 id 最小的一条
--   2) 加唯一索引，从 DB 层保证幂等
-- ============================================================================

-- 1) 清理重复记录（保留 id 最小的那条）
DELETE s1
FROM farb_settlement_log s1
INNER JOIN farb_settlement_log s2
    ON s1.position_id = s2.position_id
   AND s1.settlement_time = s2.settlement_time
   AND s1.id > s2.id;

-- 2) 唯一索引（防止未来重复）
ALTER TABLE farb_settlement_log
    ADD UNIQUE KEY uk_position_settle (position_id, settlement_time);

-- 3) 用去重后的真实值修正 pair 上的 actualFundingPnl / settlementCount
UPDATE farb_position p
LEFT JOIN (
    SELECT position_id,
           COUNT(*)              AS cnt,
           SUM(funding_amount)   AS sum_amount,
           MAX(settlement_time)  AS max_settle
    FROM farb_settlement_log
    GROUP BY position_id
) agg ON p.id = agg.position_id
SET p.actual_funding_pnl   = COALESCE(agg.sum_amount, 0),
    p.settlement_count     = COALESCE(agg.cnt, 0),
    p.last_settlement_time = agg.max_settle
WHERE agg.position_id IS NOT NULL;

