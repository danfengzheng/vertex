import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';

dayjs.extend(utc);

/**
 * 交易系统统一使用 UTC+8（Asia/Shanghai / CST）显示时间，
 * 不依赖运行环境的系统时区，避免服务器时区（如 CET/UTC）导致显示错误。
 */
const CST_OFFSET_HOURS = 8;

/**
 * LocalDateTime 字符串（后端带 Z 后缀，如 2026-02-19T22:00:01Z）→ UTC+8 展示。
 * 适用于 createTime、updateTime、closedAt 等 LocalDateTime 字段。
 */
export function formatServerTime(val: string | null | undefined): string {
  if (val == null || val === '') return '-';
  const s = typeof val === 'string' && !/Z|[+-]\d{2}:?\d{2}$/.test(val) ? val + 'Z' : val;
  return dayjs.utc(s).add(CST_OFFSET_HOURS, 'hour').format('YYYY-MM-DD HH:mm:ss');
}

/**
 * UTC 毫秒时间戳（数字或字符串）→ UTC+8 展示。
 * 适用于 openTime、signalTime、entryTime、exitTime 等以 long 毫秒存储的字段。
 *
 * @param val  毫秒时间戳，数字或字符串形式
 * @param fmt  dayjs 格式字符串，默认 'YYYY-MM-DD HH:mm:ss'
 */
export function formatTimestamp(
  val: number | string | null | undefined,
  fmt = 'YYYY-MM-DD HH:mm:ss',
): string {
  if (val == null) return '-';
  const ts = typeof val === 'string' ? Number(val) : val;
  if (!Number.isFinite(ts) || ts === 0) return '-';
  return dayjs.utc(ts).add(CST_OFFSET_HOURS, 'hour').format(fmt);
}
