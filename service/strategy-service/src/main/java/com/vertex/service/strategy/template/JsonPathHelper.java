package com.vertex.service.strategy.template;

import java.util.List;
import java.util.Map;

/**
 * 简易 JSONPath 助手 —— 支持点分路径 + 数组下标：
 * <pre>
 *   "positionRatio"                     顶层字段
 *   "stopLoss.params.buffer"            嵌套对象
 *   "indicators[2].weight"              数组下标
 * </pre>
 * 只支持 read/write 到 Map&lt;String,Object&gt; + List&lt;Object&gt; 结构。
 * 不支持 wildcard、slice、filter —— 保持简单。
 */
public final class JsonPathHelper {

    private JsonPathHelper() {}

    /**
     * 按 path 定位并写入 value。中间节点必须存在（不会自动创建）。
     * 找不到路径 → 抛 IllegalArgumentException。
     */
    @SuppressWarnings({"unchecked","rawtypes"})
    public static void set(Object root, String path, Object value) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("empty path");
        }
        String[] segments = splitSegments(path);
        Object cur = root;
        for (int i = 0; i < segments.length - 1; i++) {
            cur = descend(cur, segments[i], path);
        }
        String last = segments[segments.length - 1];
        // 数组下标结尾 or 对象 key 结尾
        int bracket = last.indexOf('[');
        if (bracket >= 0) {
            String key = last.substring(0, bracket);
            int idx = parseIdx(last.substring(bracket));
            Object arr = key.isEmpty() ? cur : ((Map) cur).get(key);
            ((List) arr).set(idx, value);
        } else {
            ((Map) cur).put(last, value);
        }
    }

    /** 只读定位。找不到返回 null，不抛错。 */
    @SuppressWarnings({"unchecked","rawtypes"})
    public static Object get(Object root, String path) {
        try {
            String[] segments = splitSegments(path);
            Object cur = root;
            for (String seg : segments) cur = descend(cur, seg, path);
            return cur;
        } catch (Exception ex) {
            return null;
        }
    }

    /** path 是否可解析（用于 validate 阶段） */
    public static boolean exists(Object root, String path) {
        return get(root, path) != null;
    }

    // ─── 内部 ─────────────────────────────────────────

    @SuppressWarnings({"unchecked","rawtypes"})
    private static Object descend(Object cur, String seg, String fullPath) {
        int bracket = seg.indexOf('[');
        String key;
        Integer idx = null;
        if (bracket >= 0) {
            key = seg.substring(0, bracket);
            idx = parseIdx(seg.substring(bracket));
        } else {
            key = seg;
        }
        Object next = cur;
        if (!key.isEmpty()) {
            if (!(cur instanceof Map)) {
                throw new IllegalArgumentException("path '" + fullPath + "' expected object at segment '" + seg + "'");
            }
            next = ((Map) cur).get(key);
        }
        if (idx != null) {
            if (!(next instanceof List)) {
                throw new IllegalArgumentException("path '" + fullPath + "' expected array at segment '" + seg + "'");
            }
            List list = (List) next;
            if (idx < 0 || idx >= list.size()) {
                throw new IllegalArgumentException("path '" + fullPath + "' index " + idx + " out of range");
            }
            next = list.get(idx);
        }
        return next;
    }

    private static int parseIdx(String bracketExpr) {
        // 支持 "[0]" 一层；不支持 "[0][1]"
        if (!bracketExpr.startsWith("[") || !bracketExpr.endsWith("]")) {
            throw new IllegalArgumentException("invalid bracket expr: " + bracketExpr);
        }
        try {
            return Integer.parseInt(bracketExpr.substring(1, bracketExpr.length() - 1));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid array index: " + bracketExpr);
        }
    }

    private static String[] splitSegments(String path) {
        // "a.b[2].c" → ["a", "b[2]", "c"]
        return path.split("\\.");
    }
}
