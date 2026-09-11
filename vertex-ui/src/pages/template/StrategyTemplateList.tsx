import { useEffect, useState } from 'react';
import { Card, Table, Button, Tag, Space, Popconfirm, message, Modal, Descriptions, Alert } from 'antd';
import { PlusOutlined, RocketOutlined, ExperimentOutlined, EyeOutlined, DeleteOutlined, SwapOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { templateApi, StrategyTemplateVO } from '../../api/template';

/**
 * 策略模板列表页。
 * <p>
 * - 内置模板不可删除；点"从此模板创建策略"进入表单
 * - 用户自建模板可编辑/删除
 * - 点"A/B 对比"跳去对比页
 * </p>
 */
export const StrategyTemplateList = () => {
  const { t } = useTranslation();
  const nav = useNavigate();
  const [data, setData] = useState<StrategyTemplateVO[]>([]);
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<StrategyTemplateVO | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const res = await templateApi.list();
      if (res.code === 200 && res.data) setData(res.data);
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { load(); }, []);

  const onDelete = async (id: string) => {
    try {
      await templateApi.delete(id);
      message.success(t('common.deleteSuccess', { defaultValue: '已删除' }));
      load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'delete failed');
    }
  };

  const columns = [
    {
      title: 'Code',
      dataIndex: 'code',
      key: 'code',
      render: (c: string, r: StrategyTemplateVO) => (
        <Space>
          <span>{c}</span>
          {r.isBuiltin === 1 && <Tag color="gold">{t('text.strategy.templateBuiltin')}</Tag>}
        </Space>
      ),
    },
    { title: t('text.strategy.templateName'), dataIndex: 'name', key: 'name' },
    { title: t('text.strategy.templateCategory'), dataIndex: 'category', key: 'category',
      render: (c: string) => c ? <Tag>{c}</Tag> : '-' },
    { title: t('text.strategy.templateStatus'), dataIndex: 'status', key: 'status',
      render: (s: string) => {
        const color = s === 'PUBLISHED' ? 'success' : s === 'DRAFT' ? 'default' : 'warning';
        return <Tag color={color}>{s}</Tag>;
      } },
    { title: 'v', dataIndex: 'version', key: 'version', width: 60 },
    { title: t('text.strategy.templateRisk'), dataIndex: 'riskProfile', key: 'riskProfile',
      render: (r: string) => r ? <Tag color={r === 'HIGH' ? 'error' : r === 'LOW' ? 'success' : 'processing'}>{r}</Tag> : '-' },
    {
      title: t('common.operation', { defaultValue: '操作' }),
      key: 'action',
      width: 380,
      render: (_: unknown, r: StrategyTemplateVO) => (
        <Space>
          <Button type="link" icon={<EyeOutlined />} onClick={() => setDetail(r)}>
            {t('common.detail', { defaultValue: '详情' })}
          </Button>
          <Button type="link" icon={<RocketOutlined />}
                  onClick={() => nav(`/strategy/template/${r.id}/create`)}>
            {t('text.strategy.templateCreateStrategy')}
          </Button>
          {r.isBuiltin !== 1 && (
            <Popconfirm title={t('text.strategy.templateDeleteConfirm')} onConfirm={() => onDelete(r.id)}>
              <Button type="link" danger icon={<DeleteOutlined />} />
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>
          {t('text.strategy.templateTitle')}
        </h2>
        <Space>
          <Button icon={<SwapOutlined />} onClick={() => nav('/strategy/template/ab')}>
            {t('text.strategy.templateAbCompare')}
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => nav('/strategy/template/new')}>
            {t('text.strategy.templateNew')}
          </Button>
        </Space>
      </div>

      <Card>
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
               message={t('text.strategy.templateHint')} />
        <Table
          rowKey="id"
          loading={loading}
          dataSource={data}
          columns={columns as never}
          pagination={false}
        />
      </Card>

      <Modal
        open={!!detail}
        title={detail?.name}
        onCancel={() => setDetail(null)}
        onOk={() => setDetail(null)}
        width={800}
        okText={t('common.close', { defaultValue: '关闭' })}
        cancelButtonProps={{ style: { display: 'none' } }}
      >
        {detail && (
          <>
            <Descriptions bordered column={2} size="small">
              <Descriptions.Item label="Code">{detail.code}</Descriptions.Item>
              <Descriptions.Item label="Version">{detail.version}</Descriptions.Item>
              <Descriptions.Item label={t('text.strategy.templateCategory')}>{detail.category ?? '-'}</Descriptions.Item>
              <Descriptions.Item label={t('text.strategy.templateRisk')}>{detail.riskProfile ?? '-'}</Descriptions.Item>
              <Descriptions.Item label={t('text.strategy.templateMarketFit')} span={2}>
                {(detail.marketFit ?? []).join(', ') || '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('text.strategy.templateIntervalFit')} span={2}>
                {(detail.intervalFit ?? []).join(', ') || '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('text.strategy.templateDescription')} span={2}>
                {detail.description ?? '-'}
              </Descriptions.Item>
            </Descriptions>

            <h4 style={{ marginTop: 16 }}>{t('text.strategy.templateExposedParams')}</h4>
            <Table
              size="small"
              pagination={false}
              rowKey="userKey"
              dataSource={detail.exposedParams ?? []}
              columns={[
                { title: 'userKey', dataIndex: 'userKey', key: 'userKey' },
                { title: 'label', dataIndex: 'label', key: 'label' },
                { title: 'type', dataIndex: 'type', key: 'type' },
                { title: 'default', dataIndex: 'default', key: 'default',
                  render: (v: unknown) => v == null ? '-' : String(v) },
                { title: 'range', key: 'range',
                  render: (_: unknown, r: { min?: number; max?: number; options?: unknown[] }) =>
                    r.options ? `enum(${r.options.length})` : (r.min != null || r.max != null) ? `${r.min ?? '-'} ~ ${r.max ?? '-'}` : '-' },
                { title: 'templatePath', dataIndex: 'templatePath', key: 'templatePath' },
              ]}
            />

            <h4 style={{ marginTop: 16 }}>
              <ExperimentOutlined /> template_json
            </h4>
            <pre style={{ background: '#fafafa', padding: 8, maxHeight: 300, overflow: 'auto', fontSize: 12 }}>
              {JSON.stringify(detail.template, null, 2)}
            </pre>
          </>
        )}
      </Modal>
    </div>
  );
};
