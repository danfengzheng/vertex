import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';

dayjs.extend(utc);

/**
 * 后端统一存 UTC，接口返回 ISO 8601 带 Z（如 2026-02-19T22:00:01Z）。
 * 按 UTC 解析后转为浏览器本地时区展示。
 */
export function formatServerTime(val: string | null | undefined): string {
  if (val == null || val === '') return '-';
  const s = typeof val === 'string' && !/Z|[+-]\d{2}:?\d{2}$/.test(val) ? val + 'Z' : val;
  return dayjs.utc(s).local().format('YYYY-MM-DD HH:mm:ss');
}
