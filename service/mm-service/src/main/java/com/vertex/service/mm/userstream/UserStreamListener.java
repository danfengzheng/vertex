package com.vertex.service.mm.userstream;

import com.vertex.service.mm.userstream.event.AccountUpdateEvent;
import com.vertex.service.mm.userstream.event.OrderTradeUpdateEvent;

/**
 * 私有流事件监听接口。
 * <p>
 * mm-service 内部各组件（GridEngine、DeltaMonitor、MarginMonitor 等）实现此接口，
 * 注册到 {@link UserDataStreamManager}，收到相应事件时按 event.symbol 或全局分发。
 * </p>
 */
public interface UserStreamListener {

    /** 订单成交/状态变化 */
    default void onOrderUpdate(OrderTradeUpdateEvent event) {}

    /** 账户余额/持仓变化 */
    default void onAccountUpdate(AccountUpdateEvent event) {}

    /** MARGIN_CALL —— 强平预警 */
    default void onMarginCall(String rawPayload) {}

    /** listenKey 过期通知 —— UserDataStreamManager 会自动重连，业务层可选择性对账 */
    default void onListenKeyExpired() {}

    /** WS 重连成功后 —— 业务层应做全量对账 */
    default void onReconnected() {}
}
