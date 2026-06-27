import { useEffect, useState } from 'react';
import { Card, Row, Col, Tag, Statistic, Button, Space, Alert, Spin, Descriptions } from 'antd';
import { ReloadOutlined, RobotOutlined, MonitorOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { aiApi, AiStatus } from '../../api/ai';

/**
 * AI 运行状态 / 配置菜单。
 * <p>
 * 调用 GET /admin/ai/status，展示 provider / model / 是否启用 / 缓存状态
 * 以及线程池堆积 (queueSize / activeCount / completedTaskCount / rejectedTaskCount)。
 * 不修改任何配置，纯只读视图；改配置仍需要走 yaml + 重启服务。
 * </p>
 */
export const AiStatusPage = () => {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState<AiStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  const fetchStatus = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await aiApi.status();
      setStatus(res.data ?? null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'unknown error');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchStatus();
    // 队列堆积是动态值，每 5s 自动刷新一次
    const timer = setInterval(fetchStatus, 5000);
    return () => clearInterval(timer);
  }, []);

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Space>
          <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>
            <MonitorOutlined /> {t('text.ai.statusTitle')}
          </h2>
        </Space>
        <Button icon={<ReloadOutlined />} onClick={fetchStatus} loading={loading}>
          {t('common.refresh', { defaultValue: 'Refresh' })}
        </Button>
      </div>

      {error && <Alert type="error" message={error} style={{ marginBottom: 12 }} />}

      <Spin spinning={loading && !status}>
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={12}>
            <Card size="small" title={<><RobotOutlined /> {t('text.ai.providerConfig')}</>}>
              <Descriptions size="small" column={1} bordered>
                <Descriptions.Item label={t('text.ai.aiEnabled')}>
                  {status?.aiEnabled
                    ? <Tag color="success">ON</Tag>
                    : <Tag color="default">OFF</Tag>}
                </Descriptions.Item>
                <Descriptions.Item label={t('text.ai.cacheEnabled')}>
                  {status?.cacheEnabled
                    ? <Tag color="success">ON</Tag>
                    : <Tag color="default">OFF</Tag>}
                </Descriptions.Item>
                <Descriptions.Item label={t('text.ai.providerLabel')}>
                  {status?.provider
                    ? <Tag color="blue">{status.provider}</Tag>
                    : <span style={{ color: '#999' }}>-</span>}
                </Descriptions.Item>
                <Descriptions.Item label={t('text.ai.modelLabel')}>
                  {status?.model
                    ? <Tag>{status.model}</Tag>
                    : <span style={{ color: '#999' }}>-</span>}
                </Descriptions.Item>
              </Descriptions>
              <Alert
                style={{ marginTop: 12 }}
                type="info"
                showIcon
                message={t('text.ai.configHint')}
              />
            </Card>
          </Col>

          <Col xs={24} lg={12}>
            <Card size="small" title={t('text.ai.queueStatus')}>
              <Row gutter={16}>
                <Col span={12}>
                  <Statistic
                    title={t('text.ai.workerThreads')}
                    value={status?.workerThreads ?? 0}
                  />
                </Col>
                <Col span={12}>
                  <Statistic
                    title={t('text.ai.queueCapacity')}
                    value={status?.queueCapacity ?? 0}
                  />
                </Col>
                <Col span={12} style={{ marginTop: 16 }}>
                  <Statistic
                    title={t('text.ai.queueSize')}
                    value={status?.queueSize ?? 0}
                    valueStyle={{ color: (status?.queueSize ?? 0) > 0 ? '#fa8c16' : undefined }}
                  />
                </Col>
                <Col span={12} style={{ marginTop: 16 }}>
                  <Statistic
                    title={t('text.ai.activeCount')}
                    value={status?.activeCount ?? 0}
                    valueStyle={{ color: (status?.activeCount ?? 0) > 0 ? '#1677ff' : undefined }}
                  />
                </Col>
                <Col span={12} style={{ marginTop: 16 }}>
                  <Statistic
                    title={t('text.ai.completedTaskCount')}
                    value={status?.completedTaskCount ?? 0}
                  />
                </Col>
                <Col span={12} style={{ marginTop: 16 }}>
                  <Statistic
                    title={t('text.ai.rejectedTaskCount')}
                    value={status?.rejectedTaskCount ?? 0}
                    valueStyle={{ color: (status?.rejectedTaskCount ?? 0) > 0 ? '#ff4d4f' : undefined }}
                  />
                </Col>
              </Row>
            </Card>
          </Col>
        </Row>

        <Alert
          style={{ marginTop: 16 }}
          type="warning"
          showIcon
          message={t('text.ai.menuHint')}
        />
      </Spin>
    </div>
  );
};
