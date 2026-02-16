import { useState, useEffect, useCallback } from 'react';
import { Table, Button, Space, message, Tag, Modal, Form, Input, Select, Popconfirm } from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  ApiOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { exchangeAccountApi, ExchangeAccountVO } from '../../api/trading';

export const ExchangeAccountManagement = () => {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [accounts, setAccounts] = useState<ExchangeAccountVO[]>([]);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form] = Form.useForm();
  const [testingId, setTestingId] = useState<string | null>(null);

  const fetchAccounts = useCallback(async () => {
    setLoading(true);
    try {
      const res = await exchangeAccountApi.list();
      setAccounts(res.data || []);
    } catch (e) {
      // error handled by interceptor
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchAccounts();
  }, [fetchAccounts]);

  const handleCreate = () => {
    setEditingId(null);
    form.resetFields();
    form.setFieldsValue({ exchange: 'binance' });
    setModalOpen(true);
  };

  const handleEdit = (record: ExchangeAccountVO) => {
    setEditingId(record.id);
    form.resetFields();
    form.setFieldsValue({ name: record.name, exchange: record.exchange });
    setModalOpen(true);
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editingId) {
        await exchangeAccountApi.update({ id: editingId, ...values });
        message.success(t('message.common.updateSuccess'));
      } else {
        await exchangeAccountApi.create(values);
        message.success(t('message.common.createSuccess'));
      }
      setModalOpen(false);
      fetchAccounts();
    } catch (e) {
      // validation error
    }
  };

  const handleDelete = async (id: string) => {
    await exchangeAccountApi.delete(id);
    message.success(t('message.common.deleteSuccess'));
    fetchAccounts();
  };

  const handleTestConnection = async (id: string) => {
    setTestingId(id);
    try {
      const res = await exchangeAccountApi.testConnection(id);
      if (res.data) {
        message.success(t('text.trading.testConnection') + ' - OK');
      } else {
        message.error(t('text.trading.testConnection') + ' - Failed');
      }
    } catch (e) {
      message.error(t('text.trading.testConnection') + ' - Error');
    } finally {
      setTestingId(null);
    }
  };

  const columns = [
    {
      title: t('text.trading.accountName'),
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: t('text.quote.exchange'),
      dataIndex: 'exchange',
      key: 'exchange',
      render: (val: string) => <Tag color="blue">{val?.toUpperCase()}</Tag>,
    },
    {
      title: t('text.trading.status'),
      dataIndex: 'status',
      key: 'status',
      render: (val: number) =>
        val === 1 ? (
          <Tag icon={<CheckCircleOutlined />} color="success">{t('common.enabled')}</Tag>
        ) : (
          <Tag icon={<CloseCircleOutlined />} color="error">{t('common.disabled')}</Tag>
        ),
    },
    {
      title: t('common.createTime'),
      dataIndex: 'createTime',
      key: 'createTime',
    },
    {
      title: t('common.operation'),
      key: 'action',
      render: (_: any, record: ExchangeAccountVO) => (
        <Space>
          <Button
            type="link"
            icon={<ApiOutlined />}
            loading={testingId === record.id}
            onClick={() => handleTestConnection(record.id)}
          >
            {t('text.trading.testConnection')}
          </Button>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(record)}>
            {t('common.edit')}
          </Button>
          <Popconfirm title={t('message.trading.deleteConfirm')} onConfirm={() => handleDelete(record.id)}>
            <Button type="link" danger icon={<DeleteOutlined />}>
              {t('common.delete')}
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
          {t('common.add')}
        </Button>
      </div>
      <Table
        columns={columns}
        dataSource={accounts}
        loading={loading}
        rowKey="id"
        pagination={false}
      />
      <Modal
        title={editingId ? t('common.edit') : t('common.add')}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label={t('text.trading.accountName')} rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="exchange" label={t('text.quote.exchange')}>
            <Select options={[{ value: 'binance', label: 'Binance' }]} />
          </Form.Item>
          <Form.Item
            name="apiKey"
            label={t('text.trading.apiKey')}
            rules={editingId ? [] : [{ required: true }]}
          >
            <Input.Password placeholder={editingId ? t('text.trading.apiKey') + ' (leave empty to keep)' : ''} />
          </Form.Item>
          <Form.Item
            name="apiSecret"
            label={t('text.trading.apiSecret')}
            rules={editingId ? [] : [{ required: true }]}
          >
            <Input.Password placeholder={editingId ? t('text.trading.apiSecret') + ' (leave empty to keep)' : ''} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};
