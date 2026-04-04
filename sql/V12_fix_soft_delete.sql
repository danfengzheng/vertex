-- V12: 修复软删除与唯一键冲突
--
-- 问题根因：
-- 1. stg_strategy.uk_name(name, deleted)
--    场景：create → delete(deleted=1) → create(同名) → delete(同名)
--    第二次删除时两行的 (name, deleted=1) 组合相同，触发 DUPLICATE KEY ERROR。
--
-- 2. trd_exchange_symbol.uk_exchange_symbol_type(exchange, symbol, market_type)
--    trd_symbol.uk_symbol(symbol)
--    场景：记录被软删除后再次同步，@TableLogic 过滤使代码误判为新记录，
--    执行 INSERT 时命中唯一键冲突。（已通过代码层 restore 修复，此处只处理历史数据）
--
-- 修复：对 stg_strategy 中已删除记录追加 _del_{id} 后缀，释放名称占用。

UPDATE stg_strategy
SET name = CONCAT(name, '_del_', id)
WHERE deleted = 1
  AND name NOT LIKE '%\_del\_%';   -- 幂等：避免重复执行时二次追加后缀
