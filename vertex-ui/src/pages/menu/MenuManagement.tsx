import { useState, useEffect } from 'react';
import { Table, Button, Space, message, Modal, Form, Input, InputNumber, Select } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { menuApi, MenuVO, MenuCreateDTO, MenuUpdateDTO } from '../../api/menu';
import type { ApiResponse } from '../../types/api';

export const MenuManagement = () => {
  const { t } = useTranslation();
  const [menus, setMenus] = useState<MenuVO[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingMenu, setEditingMenu] = useState<MenuVO | null>(null);
  const [form] = Form.useForm();

  const loadMenus = async () => {
    setLoading(true);
    try {
      const response: ApiResponse<MenuVO[]> = await menuApi.listTree();
      if (response.code === 200) {
        setMenus(response.data);
      }
    } catch (error) {
      message.error(t('message.menu.loadFailed'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadMenus();
  }, []);

  const handleAdd = () => {
    setEditingMenu(null);
    form.resetFields();
    setModalVisible(true);
  };

  const handleEdit = (menu: MenuVO) => {
    setEditingMenu(menu);
    form.setFieldsValue(menu);
    setModalVisible(true);
  };

  const handleDelete = (id: number) => {
    Modal.confirm({
      title: t('common.confirm'),
      content: t('message.menu.deleteConfirm'),
      onOk: async () => {
        try {
          await menuApi.delete(id);
          message.success(t('message.common.deleteSuccess'));
          loadMenus();
        } catch (error) {
          message.error(t('message.common.deleteFailed'));
        }
      },
    });
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editingMenu) {
        const updateData: MenuUpdateDTO = {
          id: editingMenu.id,
          ...values,
        };
        await menuApi.update(updateData);
        message.success(t('message.common.updateSuccess'));
      } else {
        const createData: MenuCreateDTO = values;
        await menuApi.create(createData);
        message.success(t('message.common.createSuccess'));
      }
      setModalVisible(false);
      loadMenus();
    } catch (error) {
      console.error('Submit error:', error);
    }
  };

  const columns = [
    {
      title: t('text.menu.name'),
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: t('text.menu.i18nKey'),
      dataIndex: 'i18nKey',
      key: 'i18nKey',
    },
    {
      title: t('text.menu.path'),
      dataIndex: 'path',
      key: 'path',
    },
    {
      title: t('text.menu.type'),
      dataIndex: 'type',
      key: 'type',
      render: (type: number) => {
        const types = [t('text.menu.type.directory'), t('text.menu.type.menu'), t('text.menu.type.button')];
        return types[type] || type;
      },
    },
    {
      title: t('common.status'),
      dataIndex: 'status',
      key: 'status',
      render: (status: number) => (status === 1 ? t('common.enabled') : t('common.disabled')),
    },
    {
      title: t('common.operation'),
      key: 'action',
      render: (_: any, record: MenuVO) => (
        <Space>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(record)}>
            {t('common.edit')}
          </Button>
          <Button type="link" danger icon={<DeleteOutlined />} onClick={() => handleDelete(record.id)}>
            {t('common.delete')}
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>{t('text.menu.title')}</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd}>
          {t('common.add')}
        </Button>
      </div>
      <Table
        columns={columns}
        dataSource={menus}
        loading={loading}
        rowKey="id"
        pagination={false}
      />
      <Modal
        title={editingMenu ? t('common.edit') : t('common.add')}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        width={600}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="parentId" label={t('text.menu.parentId')}>
            <Select placeholder={t('placeholder.menu.parentId')} allowClear>
              {menus.map(m => (
                <Select.Option key={m.id} value={m.id}>
                  {m.name}
                </Select.Option>
              ))}
            </Select>
          </Form.Item>
          <Form.Item name="name" label={t('text.menu.name')} rules={[{ required: true }]}>
            <Input placeholder={t('placeholder.menu.name')} />
          </Form.Item>
          <Form.Item name="i18nKey" label={t('text.menu.i18nKey')}>
            <Input placeholder={t('placeholder.menu.i18nKey')} />
          </Form.Item>
          <Form.Item name="path" label={t('text.menu.path')}>
            <Input placeholder={t('placeholder.menu.path')} />
          </Form.Item>
          <Form.Item name="component" label={t('text.menu.component')}>
            <Input placeholder={t('placeholder.menu.component')} />
          </Form.Item>
          <Form.Item name="icon" label={t('text.menu.icon')}>
            <Input placeholder={t('placeholder.menu.icon')} />
          </Form.Item>
          <Form.Item name="type" label={t('text.menu.type')} rules={[{ required: true }]}>
            <Select placeholder={t('placeholder.common.select')}>
              <Select.Option value={0}>{t('text.menu.type.directory')}</Select.Option>
              <Select.Option value={1}>{t('text.menu.type.menu')}</Select.Option>
              <Select.Option value={2}>{t('text.menu.type.button')}</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item name="permission" label={t('text.menu.permission')}>
            <Input placeholder={t('placeholder.menu.permission')} />
          </Form.Item>
          <Form.Item name="sort" label={t('text.menu.sort')}>
            <InputNumber min={0} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};
