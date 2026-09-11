package com.vertex.service.mm.binance;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.vertex.service.mm.config.MmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 币安服务器时间同步 —— 修正本地时钟偏差避免 -1021 timestamp 拒单。
 * <p>
 * 启动时首次同步 + 每 15min 刷新。所有签名请求用 {@link #now()} 代替 {@link System#currentTimeMillis()}。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceTimeSync {

    private final MmProperties properties;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
            r -> { Thread t = new Thread(r, "mm-time-sync"); t.setDaemon(true); return t; });

    /** serverTime - localTime，符号：+ 表示服务器比本地快 */
    private final AtomicLong offsetMs = new AtomicLong(0);

    @PostConstruct
    void init() {
        scheduler.schedule(this::sync, 2, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::sync, 15, 15, TimeUnit.MINUTES);
    }

    /** 用于所有签名请求：now = localTime + offset ≈ serverTime */
    public long now() {
        return System.currentTimeMillis() + offsetMs.get();
    }

    public long offset() { return offsetMs.get(); }

    private void sync() {
        try {
            Request req = new Request.Builder()
                    .url(properties.getBinanceUsdcmBaseUrl() + "/fapi/v1/time")
                    .get().build();
            long t0 = System.currentTimeMillis();
            try (Response resp = http.newCall(req).execute()) {
                long t1 = System.currentTimeMillis();
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("[TimeSync] HTTP {} — keep old offset {} ms", resp.code(), offsetMs.get());
                    return;
                }
                JSONObject j = JSON.parseObject(resp.body().string());
                long serverTime = j.getLongValue("serverTime");
                // 用往返时间的中点作为本地采样时间
                long localMid = (t0 + t1) / 2;
                long newOffset = serverTime - localMid;
                long oldOffset = offsetMs.getAndSet(newOffset);
                if (Math.abs(newOffset - oldOffset) > 500) {
                    log.warn("[TimeSync] offset changed {} → {} ms (rtt={}ms)",
                            oldOffset, newOffset, t1 - t0);
                } else {
                    log.debug("[TimeSync] offset={} ms rtt={}ms", newOffset, t1 - t0);
                }
            }
        } catch (Exception ex) {
            log.warn("[TimeSync] sync failed: {}", ex.getMessage());
        }
    }
}
