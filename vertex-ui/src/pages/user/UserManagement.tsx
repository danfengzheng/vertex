import { useState, useEffect } from 'react';
import { Table, Button, Space, message, Modal, Form, Input, Select, Tag, Popconfirm, Checkbox } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, LockOutlined, TeamOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { userApi, UserVO, UserQueryDTO, UserCreateDTO, UserUpdateDTO } from '../../api/user';
import { roleApi, RoleVO } from '../../api/role';
import { userRoleApi } from '../../api/userRole';
import type { ApiResponse, PageResult } from '../../types/api';

export const UserManagement = () => {
  const { t } = useTranslation();
  const [users, setUsers] = useState<UserVO[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingUser, setEditingUser] = useState<UserVO | null>(null);
  const [form] = Form.useForm();

  // ─── 分配角色弹窗 ────────────────────────────────
  const [roleModalVisible, setRoleModalVisible] = useState(false);
  const [assigningUser, setAssigningUser] = useState<UserVO | null>(null);
  const [allRoles, setAllRoles] = useState<RoleVO[]>([]);
  const [selectedRoleIds, setSelectedRoleIds] = useState<string[]>([]);
  const [rolesLoading, setRolesLoading] = useState(false);
  const [rolesSaving, setRolesSaving] = useState(false);

  const loadUsers = async () => {
    setLoading(true);
    try {
      const query: UserQueryDTO = {
        pageNum,
        pageSize,
      };
      const response: ApiResponse<PageResult<UserVO>> = await userApi.page(query);
      if (response.code === 200) {
        setUsers(response.data.records);
        setTotal(response.data.total);
      }
    } catch (error) {
      message.error(t('message.user.loadFailed'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadUsers();
  }, [pageNum, pageSize]);

  const handleAdd = () => {
    setEditingUser(null);
    form.resetFields();
    setModalVisible(true);
  };

  const handleEdit = (user: UserVO) => {
    setEditingUser(user);
    form.setFieldsValue(user);
    setModalVisible(true);
  };

  const handleDelete = (id: string) => {
    Modal.confirm({
      title: t('common.confirm'),
      content: t('message.user.deleteConfirm'),
      onOk: async () => {
        try {
          await userApi.delete(id);
          message.success(t('message.common.deleteSuccess'));
          loadUsers();
        } catch (error) {
          message.error(t('message.common.deleteFailed'));
        }
      },
    });
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editingUser) {
        const updateData: UserUpdateDTO = {
          id: editingUser.id,
          username: values.username,
          nickname: values.nickname,
          phone: values.phone,
          email: values.email,
          accountType: values.accountType,
        };
        if (values.password && values.password.trim()) {
          updateData.password = values.password.trim();
        }
        await userApi.update(updateData);
        message.success(t('message.common.updateSuccess'));
      } else {
        const createData: UserCreateDTO = values;
        await userApi.create(createData);
        message.success(t('message.common.createSuccess'));
      }
      setModalVisible(false);
      loadUsers();
    } catch (error) {
      console.error('Submit error:', error);
    }
  };

  const handleOpenRoleModal = async (user: UserVO) => {
    setAssigningUser(user);
    setRoleModalVisible(true);
    setRolesLoading(true);
    try {
      const [rolesRes, assignedRes] = await Promise.all([
        roleApi.listAll(),
        userRoleApi.getRoleIdsByUserId(user.id),
      ]);
      if (rolesRes.code === 200) setAllRoles(rolesRes.data);
      if (assignedRes.code === 200) setSelectedRoleIds(assignedRes.data);
    } catch {
      message.error('加载角色数据失败');
    } finally {
      setRolesLoading(false);
    }
  };

  const handleSaveRoles = async () => {
    if (!assigningUser) return;
    setRolesSaving(true);
    try {
      await userRoleApi.assignRolesToUser(assigningUser.id, selectedRoleIds);
      message.success('角色分配成功');
      setRoleModalVisible(false);
    } catch {
      message.error('保存失败');
    } finally {
      setRolesSaving(false);
    }
  };

  const handleUnfreeze = async (id: string) => {
    try {
      await userApi.unfreeze(id);
      message.success(t('message.user.unfreezeSuccess'));
      loadUsers();
    } catch (error) {
      message.error(t('message.common.requestFailed'));
    }
  };

  const columns = [
    {
      title: t('text.user.username'),
      dataIndex: 'username',
      key: 'username',
    },
    {
      title: t('text.user.nickname'),
      dataIndex: 'nickname',
      key: 'nickname',
    },
    {
      title: t('text.user.phone'),
      dataIndex: 'phone',
      key: 'phone',
    },
    {
      title: t('text.user.email'),
      dataIndex: 'email',
      key: 'email',
    },
    {
      title: t('text.user.accountType'),
      dataIndex: 'accountType',
      key: 'accountType',
      render: (type: number) => (type === 0 ? t('text.user.accountType.system') : t('text.user.accountType.agent')),
    },
    {
      title: t('common.status'),
      dataIndex: 'status',
      key: 'status',
      render: (status: number) => (status === 1 ? t('common.normal') : t('common.disabled')),
    },
    {
      title: t('text.user.locked'),
      dataIndex: 'locked',
      key: 'locked',
      width: 100,
      render: (locked: boolean) =>
        locked ? <Tag color="error" icon={<LockOutlined />}>{t('text.user.locked')}</Tag> : '-',
    },
    {
      title: t('common.operation'),
      key: 'action',
      width: 280,
      render: (_: any, record: UserVO) => (
        <Space>
          <Button type="link" icon={<TeamOutlined />} onClick={() => handleOpenRoleModal(record)}>
            分配角色
          </Button>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(record)}>
            {t('common.edit')}
          </Button>
          {record.locked && (
            <Popconfirm
              title={t('text.user.unfreeze') + '?'}
              onConfirm={() => handleUnfreeze(record.id)}
            >
              <Button type="link">{t('text.user.unfreeze')}</Button>
            </Popconfirm>
          )}
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
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>{t('text.user.title')}</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd}>
          {t('common.add')}
        </Button>
      </div>
      <Table
        columns={columns}
        dataSource={users}
        loading={loading}
        rowKey="id"
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (total) => t('common.total', { total }),
          onChange: (page, size) => {
            setPageNum(page);
            setPageSize(size);
          },
        }}
      />
      <Modal
        title={editingUser ? t('common.edit') : t('common.add')}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="username" label={t('text.user.username')} rules={[{ required: true }]}>
            <Input placeholder={t('placeholder.user.username')} disabled={!!editingUser} />
          </Form.Item>
          <Form.Item
            name="password"
            label={t('text.user.password')}
            rules={editingUser ? [] : [{ required: true, message: t('placeholder.user.password') }]}
          >
            <Input.Password
              placeholder={editingUser ? t('text.user.passwordOptional') : t('placeholder.user.password')}
            />
          </Form.Item>
          <Form.Item name="nickname" label={t('text.user.nickname')}>
            <Input placeholder={t('placeholder.user.nickname')} />
          </Form.Item>
          <Form.Item name="phone" label={t('text.user.phone')} rules={[{ required: true }]}>
            <Input placeholder={t('placeholder.user.phone')} />
          </Form.Item>
          <Form.Item name="email" label={t('text.user.email')}>
            <Input placeholder={t('placeholder.user.email')} />
          </Form.Item>
          <Form.Item name="accountType" label={t('text.user.accountType')}>
            <Select placeholder={t('placeholder.common.select')}>
              <Select.Option value={0}>{t('text.user.accountType.system')}</Select.Option>
              <Select.Option value={1}>{t('text.user.accountType.agent')}</Select.Option>
            </Select>
          </Form.Item>
        </Form>
      </Modal>
      {/* 分配角色弹窗 */}
      <Modal
        title={`分配角色 — ${assigningUser?.username ?? ''}`}
        open={roleModalVisible}
        onOk={handleSaveRoles}
        confirmLoading={rolesSaving}
        onCancel={() => setRoleModalVisible(false)}
      >
        {rolesLoading ? (
          <div style={{ textAlign: 'center', padding: '20px 0' }}>加载中...</div>
        ) : allRoles.length === 0 ? (
          <div style={{ color: '#999', textAlign: 'center', padding: '20px 0' }}>
            暂无可用角色，请先在角色管理中创建角色
          </div>
        ) : (
          <Checkbox.Group
            value={selectedRoleIds}
            onChange={(vals) => setSelectedRoleIds(vals as string[])}
            style={{ display: 'flex', flexDirection: 'column', gap: 8 }}
          >
            {allRoles.map(role => (
              <Checkbox key={role.id} value={role.id} disabled={role.status !== 1}>
                {role.name}
                {role.description && (
                  <span style={{ color: '#999', marginLeft: 6, fontSize: 12 }}>({role.description})</span>
                )}
                {role.status !== 1 && <span style={{ color: '#f5222d', marginLeft: 6, fontSize: 12 }}>已禁用</span>}
              </Checkbox>
            ))}
          </Checkbox.Group>
        )}
      </Modal>
    </div>
  );
};
