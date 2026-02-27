import { useState, useCallback, useEffect } from 'react';
import {
  Table, Button, Space, Tag, Switch, Modal, Form,
  Input, InputNumber, Select, Popconfirm, message, Tooltip,
} from 'antd';
import {
  PlusOutlined, EditOutlined, DeleteOutlined,
  CheckCircleOutlined, StopOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  alertRuleApi,
  AlertRuleVO,
  AlertRuleCreateDTO,
  AlertRuleUpdateDTO,
} from '../../api/chain';
import type { TablePaginationConfig } from 'antd';

const { Option } = Select;

// ─── 主组件 ──────────────────────────────────────

export const AlertConfig = () => {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [rules, setRules] = useState<AlertRuleVO[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingRule, setEditingRule] = useState<AlertRuleVO | null>(null);
  const [form] = Form.useForm();

  const fetchRules = useCallback(async () => {
    setLoading(true);
    try {
      const res = await alertRuleApi.page({ pageNum, pageSize });
      setRules(res.data?.records || []);
      setTotal(res.data?.total || 0);
    } catch {
      // handled by interceptor
    } finally {
      setLoading(false);
    }
  }, [pageNum, pageSize]);

  useEffect(() => { fetchRules(); }, [fetchRules]);

  // ─── 开/关规则 ──────────────────────────────────────
  const handleToggle = async (record: AlertRuleVO) => {
    try {
      if (record.enabled === 1) {
        await alertRuleApi.disable(record.id);
        message.success(t('message.chain.disableSuccess'));
      } else {
        await alertRuleApi.enable(record.id);
        message.success(t('message.chain.enableSuccess'));
      }
      fetchRules();
    } catch {
      // handled
    }
  };

  // ─── 删除 ────────────────────────────────────────
  const handleDelete = async (id: string) => {
    try {
      await alertRuleApi.delete(id);
      message.success(t('common.delete') + ' OK');
      fetchRules();
    } catch {
      // handled
    }
  };

  // ─── 打开新增/编辑弹窗 ──────────────────────────────
  const handleAdd = () => {
    setEditingRule(null);
    form.resetFields();
    form.setFieldsValue({
      chain: 'ALL',
      minScore: 60,
      notifyChannels: '["telegram"]',
    });
    setModalVisible(true);
  };

  const handleEdit = (record: AlertRuleVO) => {
    setEditingRule(record);
    form.setFieldsValue({
      name: record.name,
      chain: record.chain,
      minScore: record.minScore,
      minMarketCapUsd: record.minMarketCapUsd ? Number(record.minMarketCapUsd) : undefined,
      minLiquidityUsd: record.minLiquidityUsd ? Number(record.minLiquidityUsd) : undefined,
      minHolderCount: record.minHolderCount,
      maxTop10HolderPct: record.maxTop10HolderPct ? Number(record.maxTop10HolderPct) : undefined,
      notifyChannels: record.notifyChannels,
      requireLiquidityLocked: record.requireLiquidityLocked,
      description: record.description,
    });
    setModalVisible(true);
  };

  // ─── 表单提交 ────────────────────────────────────
  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editingRule) {
        const dto: AlertRuleUpdateDTO = { ...values, id: editingRule.id };
        await alertRuleApi.update(dto);
        message.success(t('message.common.updateSuccess'));
      } else {
        const dto: AlertRuleCreateDTO = values;
        await alertRuleApi.create(dto);
        message.success(t('message.common.createSuccess'));
      }
      setModalVisible(false);
      fetchRules();
    } catch {
      // validation or http error
    }
  };

  const handleTableChange = (pagination: TablePaginationConfig) => {
    setPageNum(pagination.current || 1);
    setPageSize(pagination.pageSize || 20);
  };

  // ─── 表格列 ──────────────────────────────────────
  const columns = [
    {
      title: t('text.chain.alertRuleName'),
      dataIndex: 'name',
      key: 'name',
      width: 160,
      render: (v: string, record: AlertRuleVO) => (
        <Space direction="vertical" size={0}>
          <span style={{ fontWeight: 600 }}>{v}</span>
          {record.description && (
            <span style={{ fontSize: 12, color: '#8c8c8c' }}>{record.description}</span>
          )}
        </Space>
      ),
    },
    {
      title: t('text.chain.chain'),
      dataIndex: 'chain',
      key: 'chain',
      width: 80,
      render: (v: string) => (
        <Tag color={v === 'BNB' ? 'gold' : v === 'SOL' ? 'purple' : 'default'}>{v}</Tag>
      ),
    },
    {
      title: t('text.chain.minScore'),
      dataIndex: 'minScore',
      key: 'minScore',
      width: 90,
      render: (v: number) => <Tag color="blue">≥ {v}</Tag>,
    },
    {
      title: t('text.chain.conditions'),
      key: 'conditions',
      width: 200,
      render: (_: unknown, record: AlertRuleVO) => (
        <Space direction="vertical" size={0} style={{ fontSize: 12 }}>
          {record.minLiquidityUsd && (
            <span>流动性 ≥ ${Number(record.minLiquidityUsd).toLocaleString()}</span>
          )}
          {record.minMarketCapUsd && (
            <span>市值 ≥ ${Number(record.minMarketCapUsd).toLocaleString()}</span>
          )}
          {record.minHolderCount && <span>持有者 ≥ {record.minHolderCount}</span>}
          {record.maxTop10HolderPct && <span>Top10集中 ≤ {record.maxTop10HolderPct}%</span>}
          {record.requireLiquidityLocked === 1 && <Tag color="green" style={{ fontSize: 11 }}>需锁定LP</Tag>}
        </Space>
      ),
    },
    {
      title: t('text.chain.notifyChannels'),
      dataIndex: 'notifyChannels',
      key: 'notifyChannels',
      width: 110,
      render: (v: string) => {
        try {
          const channels: string[] = JSON.parse(v || '[]');
          return (
            <Space>
              {channels.map(c => (
                <Tag key={c} color="geekblue">{c}</Tag>
              ))}
            </Space>
          );
        } catch { return v || '-'; }
      },
    },
    {
      title: t('common.status'),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 80,
      render: (v: number, record: AlertRuleVO) => (
        <Switch
          checked={v === 1}
          onChange={() => handleToggle(record)}
          checkedChildren={<CheckCircleOutlined />}
          unCheckedChildren={<StopOutlined />}
        />
      ),
    },
    {
      title: t('common.operation'),
      key: 'action',
      width: 110,
      fixed: 'right' as const,
      render: (_: unknown, record: AlertRuleVO) => (
        <Space>
          <Tooltip title={t('common.edit')}>
            <Button type="link" size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)} />
          </Tooltip>
          <Popconfirm
            title={t('message.chain.deleteConfirm')}
            onConfirm={() => handleDelete(record.id)}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Tooltip title={t('common.delete')}>
              <Button type="link" size="small" danger icon={<DeleteOutlined />} />
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      {/* 工具栏 */}
      <div style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd}>
          {t('common.add')} {t('text.chain.alertRuleTitle')}
        </Button>
      </div>

      {/* 规则表格 */}
      <Table
        rowKey="id"
        loading={loading}
        dataSource={rules}
        columns={columns}
        scroll={{ x: 900 }}
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (t_) => `共 ${t_} 条`,
        }}
        onChange={handleTableChange}
        size="middle"
      />

      {/* 新增/编辑弹窗 */}
      <Modal
        title={editingRule ? t('common.edit') + ' ' + t('text.chain.alertRuleTitle') : t('common.add') + ' ' + t('text.chain.alertRuleTitle')}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
        width={560}
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item name="name" label={t('text.chain.alertRuleName')} rules={[{ required: true }]}>
            <Input placeholder="如：高分新币告警" />
          </Form.Item>
          <Form.Item name="chain" label={t('text.chain.chain')}>
            <Select>
              <Option value="ALL">全链（ALL）</Option>
              <Option value="BNB">BNB Chain</Option>
              <Option value="SOL">Solana</Option>
            </Select>
          </Form.Item>
          <Form.Item name="minScore" label={t('text.chain.minScore')} rules={[{ required: true }]}>
            <InputNumber min={0} max={100} style={{ width: '100%' }} addonAfter="分" />
          </Form.Item>
          <Form.Item name="minLiquidityUsd" label="最低流动性（USD）">
            <InputNumber min={0} style={{ width: '100%' }} placeholder="如：5000" addonBefore="$" />
          </Form.Item>
          <Form.Item name="minMarketCapUsd" label="最低市值（USD）">
            <InputNumber min={0} style={{ width: '100%' }} placeholder="如：100000" addonBefore="$" />
          </Form.Item>
          <Form.Item name="minHolderCount" label="最低持有者数">
            <InputNumber min={0} style={{ width: '100%' }} placeholder="如：100" />
          </Form.Item>
          <Form.Item name="maxTop10HolderPct" label="Top10持有者集中度上限（%）">
            <InputNumber min={0} max={100} style={{ width: '100%' }} placeholder="如：50" addonAfter="%" />
          </Form.Item>
          <Form.Item name="requireLiquidityLocked" label="是否要求流动性锁定">
            <Select allowClear placeholder="不限">
              <Option value={1}>要求锁定</Option>
              <Option value={0}>不要求</Option>
            </Select>
          </Form.Item>
          <Form.Item name="notifyChannels" label={t('text.chain.notifyChannels')}>
            <Input placeholder='["telegram"]' />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="规则说明（可选）" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};
