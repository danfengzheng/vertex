package com.vertex.service.quote.notify;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.quote.KLineInterval;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * K 线通知门控
 * <p>
 * WebSocket 重连后、REST 缺口数据补充完成前，对指定 (exchange:symbol:interval) 的
 * 通知进行暂存，待 REST 补充完成后按序排空，防止策略在数据不完整时被触发。
 *
 * <h3>版本号（generation）设计</h3>
 * 每次 {@link #gate} 调用均递增版本号并创建全新缓冲队列。
 * {@link #releaseAndDrain} 携带版本号，只有版本匹配时才真正释放。
 * 快速重连时（第一次 backfill 尚未完成又发生第二次重连），第一次 backfill 调用
 * releaseAndDrain(v=1)，因版本不匹配（当前=2）而静默跳过，门控由第二次 backfill 正常释放。
 *
 * <h3>线程安全策略</h3>
 * 所有门控状态（gatedKeys、bufferMap、versionMap）的<b>复合读写</b>均在
 * {@code synchronized(this)} 保护下执行，消除 TOCTOU 竞态：
 * <ul>
 *   <li>{@link #tryBuffer}：check-then-act（contains + get + addLast）原子化</li>
 *   <li>{@link #releaseAndDrain}：版本校验 + 门控移除原子化，dispatch 在锁外执行
 *       （避免慢速策略执行长期占锁）</li>
 *   <li>{@link #gate}：version bump + buffer 创建 + key 激活原子化</li>
 * </ul>
 * 底层使用普通 HashMap/HashSet（均在 synchronized 内访问，无需 Concurrent 实现）。
 */
@Slf4j
@Component
public class KLineNotifyGate {

    /** key → 当前缓冲器（每次 gate 创建全新实例，丢弃旧缓冲） */
    private final Map<String, Deque<KLine>> bufferMap = new HashMap<>();

    /** key → 当前门控版本号（单调递增），用于辨别过期的 releaseAndDrain 调用 */
    private final Map<String, Long> versionMap = new HashMap<>();

    /** 当前处于门控状态的 key 集合（key = exchange:symbol:interval.code） */
    private final Set<String> gatedKeys = new HashSet<>();

    // ─────────────────────────── 公共 API ───────────────────────────

    /**
     * 对指定 (exchange, symbol, interval) 开启门控，返回本次门控的版本号。
     * <p>
     * <b>每次调用都会创建全新的缓冲队列</b>，并递增版本号使旧 backfill 任务的
     * releaseAndDrain 调用自动失效（快速重连保护）。
     *
     * @return 本次门控版本号，调用方需保存并在 {@link #releaseAndDrain} 时传入
     */
    public synchronized long gate(String exchange, String symbol, KLineInterval interval) {
        String key = key(exchange, symbol, interval);
        long newVersion = versionMap.merge(key, 1L, Long::sum);
        bufferMap.put(key, new ArrayDeque<>());
        gatedKeys.add(key);
        log.debug("[NotifyGate] Gated {} version={}", key, newVersion);
        return newVersion;
    }

    /**
     * 若 key 处于门控状态，将 kline 原子性地放入缓冲队列并返回 {@code true}；
     * 否则返回 {@code false}（调用方应正常分发）。
     * <p>
     * check-then-act 在 synchronized 保护下执行，不存在 TOCTOU 竞态：
     * 若 releaseAndDrain 恰好在 contains 检查与 addLast 之间执行，
     * bufferMap.get 会返回 null → 返回 false → 调用方正常分发，不丢 kline。
     */
    public synchronized boolean tryBuffer(KLine kline) {
        if (kline == null) {
            return false;
        }
        String key = key(kline.getExchange(), kline.getSymbol(), kline.getInterval());
        if (!gatedKeys.contains(key)) {
            return false;
        }
        Deque<KLine> buf = bufferMap.get(key);
        if (buf == null) {
            // 极罕见：gate 激活到 bufferMap.put 之间的窗口（理论上不存在，因两步在同一 synchronized 块）
            // 防御性保留，返回 false → 调用方正常分发
            return false;
        }
        buf.addLast(kline);
        return true;
    }

    /**
     * 解除门控（仅当 {@code version} 与当前版本号匹配时），并按序分发：
     * <ol>
     *   <li>先发 {@code restLatestBar}（REST 补充的最新已收盘 bar，触发策略以完整数据重算）</li>
     *   <li>再逐条排空门控期间缓冲的 WS bar（追补实时数据）</li>
     * </ol>
     * 版本校验与门控移除在 synchronized 块内原子完成；
     * dispatch 在锁外执行，避免策略引擎的耗时操作长期阻塞 {@link #tryBuffer}。
     *
     * @param version       由 {@link #gate} 返回的版本号
     * @param restLatestBar REST 补充数据中最新的已收盘 bar；可为 {@code null}（仅排空缓冲）
     * @param dispatcher    实际分发函数，通常为 {@code notifier::notifyKLine}
     */
    public void releaseAndDrain(String exchange, String symbol, KLineInterval interval,
                                long version, KLine restLatestBar,
                                Consumer<KLine> dispatcher) {
        String key = key(exchange, symbol, interval);

        // ── 在锁内原子完成版本校验 + 门控移除 ──────────────────────────────────
        Deque<KLine> buf;
        synchronized (this) {
            Long currentVersion = versionMap.get(key);
            if (currentVersion == null || currentVersion != version) {
                log.warn("[NotifyGate] Stale releaseAndDrain skipped for {} (expected={}, current={})",
                        key, version, currentVersion);
                return;
            }
            // 先移除门控，后取走缓冲：
            // 移除门控后新到的 WS 通知直接分发，不再进入即将被丢弃的旧缓冲
            gatedKeys.remove(key);
            buf = bufferMap.remove(key);
        }

        // ── 锁外执行 dispatch（策略引擎可能耗时，不应持锁）────────────────────
        // 1. 推送 REST 补充数据的最新 bar → 触发策略以完整数据重新计算信号
        if (restLatestBar != null) {
            try {
                log.debug("[NotifyGate] Dispatching REST latest bar for {}: openTime={}",
                        key, restLatestBar.getOpenTime());
                dispatcher.accept(restLatestBar);
            } catch (Exception e) {
                log.error("[NotifyGate] Error dispatching REST bar for {}: {}", key, e.getMessage(), e);
            }
        }

        // 2. 排空门控期间缓冲的 WS bar（保持 WS 数据连续性）
        if (buf != null && !buf.isEmpty()) {
            log.debug("[NotifyGate] Draining {} buffered WS klines for {}", buf.size(), key);
            KLine k;
            while ((k = buf.pollFirst()) != null) {
                try {
                    dispatcher.accept(k);
                } catch (Exception e) {
                    log.error("[NotifyGate] Error draining kline for {}: {}", key, e.getMessage(), e);
                }
            }
        }

        log.info("[NotifyGate] Released gate for {} version={}", key, version);
    }

    /**
     * 强制解除门控并丢弃缓冲（用于 REST 回填出错时的兜底）。
     * <p>
     * 同时递增版本号，使任何正在进行中的 backfill 的 releaseAndDrain 调用因版本不匹配而失效。
     */
    public synchronized void forceRelease(String exchange, String symbol, KLineInterval interval) {
        String key = key(exchange, symbol, interval);
        // 递增版本，使 in-flight 的 releaseAndDrain 失效
        versionMap.merge(key, 1L, Long::sum);
        gatedKeys.remove(key);
        Deque<KLine> dropped = bufferMap.remove(key);
        int size = dropped != null ? dropped.size() : 0;
        log.warn("[NotifyGate] Force-released gate for {} (discarded {} buffered klines)", key, size);
    }

    public synchronized boolean isGated(String exchange, String symbol, KLineInterval interval) {
        return gatedKeys.contains(key(exchange, symbol, interval));
    }

    // ─────────────────────────── 内部工具 ───────────────────────────

    private static String key(String exchange, String symbol, KLineInterval interval) {
        return exchange + ":" + symbol + ":" + interval.getCode();
    }
}
