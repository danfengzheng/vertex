package com.vertex.service.quote.watchdog;

import com.vertex.framework.socket.exchange.ExchangeConfig;
import com.vertex.framework.socket.exchange.ExchangeType;
import com.vertex.framework.socket.exchange.ExchangeWebSocketClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link WsConnectionWatchdog} 单元测试。
 * <p>
 * 模拟 2026-09-03 线上事故场景 —— 验证看门狗在"pipeline 事件全失效 + reconnect 不自愈"时
 * 依然能强制重连 + 告警。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WsConnectionWatchdogTest {

    @Mock ApplicationContext appCtx;
    @Mock TelegramAlerter alerter;
    @Mock ExchangeWebSocketClient client;

    WsWatchdogProperties props;
    WsConnectionWatchdog watchdog;

    @BeforeEach
    void setUp() {
        props = new WsWatchdogProperties();
        props.setEnabled(true);
        props.setCheckIntervalSec(30);
        props.setNoMessageWarnSec(90);
        props.setNoMessageAlertSec(180);
        props.setNoMessageSecondaryAlertSec(600);
        props.setAlertRateLimitSec(300);

        watchdog = new WsConnectionWatchdog(props, appCtx, alerter);

        // 默认给 client 一个 exchange 配置，便于生成 label
        ExchangeConfig cfg = ExchangeConfig.builder()
                .exchangeType(ExchangeType.BINANCE)
                .wsUrl("wss://mock")
                .build();
        when(client.getExchangeConfig()).thenReturn(cfg);

        Map<String, ExchangeWebSocketClient> beans = new HashMap<>();
        beans.put("binanceWs", client);
        when(appCtx.getBeansOfType(ExchangeWebSocketClient.class)).thenReturn(beans);
    }

    @Test
    @DisplayName("正常场景：静默 < 90s → 什么都不做")
    void healthy_noAction() throws Exception {
        when(client.getMillisSinceLastMessage()).thenReturn(30_000L);   // 30s

        watchdog.tick();

        verify(client, never()).forceReconnect();
        verifyNoInteractions(alerter);
    }

    @Test
    @DisplayName("静默 90-180s → 强制重连，不告警")
    void warnThreshold_reconnectOnly() throws Exception {
        when(client.getMillisSinceLastMessage()).thenReturn(100_000L);  // 100s > 90s < 180s

        watchdog.tick();

        verify(client, times(1)).forceReconnect();
        verifyNoInteractions(alerter);
    }

    @Test
    @DisplayName("静默 >= 180s < 600s → 首次告警（级别 1）+ 继续重连")
    void alertThreshold_level1Alert() throws Exception {
        when(client.getMillisSinceLastMessage()).thenReturn(200_000L);  // 200s

        watchdog.tick();

        verify(client, times(1)).forceReconnect();
        ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
        verify(alerter, times(1)).alert(msgCap.capture());
        String msg = msgCap.getValue();
        assert msg.contains("WS 无数据告警") : "expected level-1 subject, got: " + msg;
        assert msg.contains("200s") : "expected silent time in msg, got: " + msg;
    }

    @Test
    @DisplayName("静默 >= 600s → 升级告警（级别 2 严重）")
    void secondaryThreshold_level2Alert() throws Exception {
        when(client.getMillisSinceLastMessage()).thenReturn(700_000L);  // 700s

        watchdog.tick();

        verify(client, times(1)).forceReconnect();
        ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
        verify(alerter, times(1)).alert(msgCap.capture());
        assert msgCap.getValue().contains("严重") : "expected severity marker";
    }

    @Test
    @DisplayName("去抖：同级别连续两次 tick 只发一次告警")
    void rateLimit_sameLevelSuppressed() throws Exception {
        when(client.getMillisSinceLastMessage()).thenReturn(200_000L);

        watchdog.tick();
        watchdog.tick();   // 30 秒后再触发（模拟下一个 tick），props.alertRateLimit=300s 内

        verify(alerter, times(1)).alert(anyString());   // 只 1 次
        verify(client, atLeast(2)).forceReconnect();     // 但每次都尝试重连
    }

    @Test
    @DisplayName("级别升级（1→2）即使在 rate limit 内也再发一次")
    void levelUpgrade_reAlertBypassRateLimit() throws Exception {
        // 第一次：level 1
        when(client.getMillisSinceLastMessage()).thenReturn(200_000L);
        watchdog.tick();

        // 第二次立即：level 2（升级）
        when(client.getMillisSinceLastMessage()).thenReturn(700_000L);
        watchdog.tick();

        verify(alerter, times(2)).alert(anyString());
    }

    @Test
    @DisplayName("恢复告警：从告警状态回到正常 → 发送 ✅ 恢复消息")
    void recovery_sendsRecoveryAlert() throws Exception {
        // 先告警
        when(client.getMillisSinceLastMessage()).thenReturn(200_000L);
        watchdog.tick();
        // 恢复
        when(client.getMillisSinceLastMessage()).thenReturn(10_000L);   // 10s，正常
        watchdog.tick();

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(alerter, times(2)).alert(cap.capture());
        String recoveryMsg = cap.getAllValues().get(1);
        assert recoveryMsg.contains("已恢复") : "expected recovery marker, got: " + recoveryMsg;
    }

    @Test
    @DisplayName("client 未初始化（silentMs=-1）→ 跳过")
    void notInitialized_skipped() throws Exception {
        when(client.getMillisSinceLastMessage()).thenReturn(-1L);

        watchdog.tick();

        verify(client, never()).forceReconnect();
        verifyNoInteractions(alerter);
    }

    @Test
    @DisplayName("forceReconnect 抛异常不影响下一个 client 检查")
    void forceReconnectException_doesNotBlockOthers() throws Exception {
        // 加第二个 client
        ExchangeWebSocketClient client2 = org.mockito.Mockito.mock(ExchangeWebSocketClient.class);
        ExchangeConfig cfg2 = ExchangeConfig.builder()
                .exchangeType(ExchangeType.OKX).wsUrl("wss://mock2").build();
        when(client2.getExchangeConfig()).thenReturn(cfg2);
        when(client2.getMillisSinceLastMessage()).thenReturn(120_000L);

        Map<String, ExchangeWebSocketClient> beans = new HashMap<>();
        beans.put("binanceWs", client);
        beans.put("okxWs", client2);
        when(appCtx.getBeansOfType(ExchangeWebSocketClient.class)).thenReturn(beans);

        when(client.getMillisSinceLastMessage()).thenReturn(120_000L);
        org.mockito.Mockito.doThrow(new RuntimeException("mock error"))
                .when(client).forceReconnect();

        watchdog.tick();

        // 尽管 client 抛异常，client2 依然被处理
        verify(client2, times(1)).forceReconnect();
    }

    @Test
    @DisplayName("多个 client 独立追踪：一个告警不影响另一个")
    void multipleClients_independentTracking() throws Exception {
        ExchangeWebSocketClient healthy = org.mockito.Mockito.mock(ExchangeWebSocketClient.class);
        ExchangeConfig cfgH = ExchangeConfig.builder().exchangeType(ExchangeType.OKX).build();
        when(healthy.getExchangeConfig()).thenReturn(cfgH);
        when(healthy.getMillisSinceLastMessage()).thenReturn(10_000L);

        Map<String, ExchangeWebSocketClient> beans = new HashMap<>();
        beans.put("binanceWs", client);
        beans.put("okxWs", healthy);
        when(appCtx.getBeansOfType(ExchangeWebSocketClient.class)).thenReturn(beans);

        when(client.getMillisSinceLastMessage()).thenReturn(200_000L);

        watchdog.tick();

        verify(client, times(1)).forceReconnect();
        verify(healthy, never()).forceReconnect();
        verify(alerter, times(1)).alert(anyString());
    }

    @Test
    @DisplayName("无 client bean → 什么都不做")
    void noClients_noAction() {
        when(appCtx.getBeansOfType(ExchangeWebSocketClient.class)).thenReturn(new HashMap<>());

        watchdog.tick();

        verifyNoInteractions(alerter);
    }
}
