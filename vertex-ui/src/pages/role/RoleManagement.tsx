import { useState, useEffect } from 'react';
import { Table, Button, Space, message, Modal, Form, Input, Select, Drawer, Tree, Tag, Popconfirm, Spin } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, MenuOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { roleApi, RoleVO, RoleCreateDTO, RoleUpdateDTO, RoleQueryDTO } from '../../api/role';
import { roleMenuApi } from '../../api/roleMenu';
import { menuApi, MenuVO } from '../../api/menu';
import type { ApiResponse, PageResult } from '../../types/api';
import type { DataNode } from 'antd/es/tree';

/** 将 MenuVO[] 树转为 Ant Design Tree DataNode[] */
const menuToTreeData = (menus: MenuVO[]): DataNode[] =>
  menus.map(m => ({
    key: m.id,
    title: m.name,
    children: m.children && m.children.length > 0 ? menuToTreeData(m.children) : undefined,
  }));


export const RoleManagement = () => {
  const { t } = useTranslation();

  // ─── 角色列表 ─────────────────────────────────────
  const [roles, setRoles] = useState<RoleVO[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  // ─── 创建/编辑角色弹窗 ──────────────────────────
  const [modalVisible, setModalVisible] = useState(false);
  const [editingRole, setEditingRole] = useState<RoleVO | null>(null);
  const [form] = Form.useForm();

  // ─── 分配菜单抽屉 ────────────────────────────────
  const [drawerVisible, setDrawerVisible] = useState(false);
  const [assigningRole, setAssigningRole] = useState<RoleVO | null>(null);
  const [allMenuTree, setAllMenuTree] = useState<MenuVO[]>([]);
  const [checkedMenuIds, setCheckedMenuIds] = useState<string[]>([]);
  const [menuLoading, setMenuLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const loadRoles = async () => {
    setLoading(true);
    try {
      const query: RoleQueryDTO = { pageNum, pageSize };
      const res: ApiResponse<PageResult<RoleVO>> = await roleApi.page(query);
      if (res.code === 200) {
        setRoles(res.data.records);
        setTotal(res.data.total);
      }
    } catch {
      message.error('加载角色失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadRoles();
  }, [pageNum, pageSize]);

  // ─── 角色 CRUD ───────────────────────────────────

  const handleAdd = () => {
    setEditingRole(null);
    form.resetFields();
    setModalVisible(true);
  };

  const handleEdit = (role: RoleVO) => {
    setEditingRole(role);
    form.setFieldsValue(role);
    setModalVisible(true);
  };

  const handleDelete = (id: string) => {
    Modal.confirm({
      title: t('common.confirm'),
      content: '确认删除该角色？',
      onOk: async () => {
        try {
          await roleApi.delete(id);
          message.success(t('message.common.deleteSuccess'));
          loadRoles();
        } catch {
          message.error(t('message.common.deleteFailed'));
        }
      },
    });
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editingRole) {
        const dto: RoleUpdateDTO = { id: editingRole.id, ...values };
        await roleApi.update(dto);
        message.success(t('message.common.updateSuccess'));
      } else {
        const dto: RoleCreateDTO = values;
        await roleApi.create(dto);
        message.success(t('message.common.createSuccess'));
      }
      setModalVisible(false);
      loadRoles();
    } catch (e) {
      console.error(e);
    }
  };

  // ─── 分配菜单 ────────────────────────────────────

  const handleOpenMenuDrawer = async (role: RoleVO) => {
    setAssigningRole(role);
    setDrawerVisible(true);
    setMenuLoading(true);
    try {
      // 加载全量菜单树
      const menuRes = await menuApi.listTree();
      if (menuRes.code === 200) setAllMenuTree(menuRes.data);

      // 加载角色已分配的菜单 ID
      const assignedRes = await roleMenuApi.getMenuIdsByRoleId(role.id);
      if (assignedRes.code === 200) setCheckedMenuIds(assignedRes.data.map(String));
    } catch {
      message.error('加载菜单数据失败');
    } finally {
      setMenuLoading(false);
    }
  };

  const handleSaveMenus = async () => {
    if (!assigningRole) return;
    setSaving(true);
    try {
      await roleMenuApi.assignMenusToRole(assigningRole.id, checkedMenuIds);
      message.success('菜单权限保存成功');
      setDrawerVisible(false);
    } catch {
      message.error('保存失败');
    } finally {
      setSaving(false);
    }
  };

  // ─── 表格列 ──────────────────────────────────────

  const columns = [
    { title: '角色名称', dataIndex: 'name', key: 'name' },
    { title: '角色编码', dataIndex: 'code', key: 'code' },
    { title: '描述', dataIndex: 'description', key: 'description' },
    {
      title: t('common.status'),
      dataIndex: 'status',
      key: 'status',
      render: (s: number) => s === 1
        ? <Tag color="success">{t('common.enabled')}</Tag>
        : <Tag color="default">{t('common.disabled')}</Tag>,
    },
    {
      title: t('common.operation'),
      key: 'action',
      width: 260,
      render: (_: any, record: RoleVO) => (
        <Space>
          <Button type="link" icon={<MenuOutlined />} onClick={() => handleOpenMenuDrawer(record)}>
            分配菜单
          </Button>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(record)}>
            {t('common.edit')}
          </Button>
          <Popconfirm title="确认删除？" onConfirm={() => handleDelete(record.id)}>
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
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>{t('text.role.title')}</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd}>
          {t('common.add')}
        </Button>
      </div>

      <Table
        columns={columns}
        dataSource={roles}
        loading={loading}
        rowKey="id"
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (total) => t('common.total', { total }),
          onChange: (page, size) => { setPageNum(page); setPageSize(size); },
        }}
      />

      {/* 创建/编辑角色弹窗 */}
      <Modal
        title={editingRole ? t('common.edit') : t('common.add')}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="角色名称" rules={[{ required: true, message: '请输入角色名称' }]}>
            <Input placeholder="如：策略交易员" />
          </Form.Item>
          <Form.Item name="code" label="角色编码" rules={[{ required: true, message: '请输入角色编码' }]}>
            <Input placeholder="如：trader（唯一标识）" disabled={!!editingRole} />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input placeholder="角色描述（可选）" />
          </Form.Item>
          <Form.Item name="status" label={t('common.status')} initialValue={1}>
            <Select>
              <Select.Option value={1}>{t('common.enabled')}</Select.Option>
              <Select.Option value={0}>{t('common.disabled')}</Select.Option>
            </Select>
          </Form.Item>
        </Form>
      </Modal>

      {/* 分配菜单抽屉 */}
      <Drawer
        title={`分配菜单 — ${assigningRole?.name ?? ''}`}
        open={drawerVisible}
        onClose={() => setDrawerVisible(false)}
        width={400}
        extra={
          <Space>
            <Button onClick={() => setDrawerVisible(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSaveMenus}>
              保存
            </Button>
          </Space>
        }
      >
        <Spin spinning={menuLoading}>
          <Tree
            checkable
            treeData={menuToTreeData(allMenuTree)}
            checkedKeys={checkedMenuIds}
            onCheck={(checked) => {
              const ids = Array.isArray(checked)
                ? (checked as string[])
                : (checked.checked as string[]);
              setCheckedMenuIds(ids);
            }}
            defaultExpandAll
          />
          {allMenuTree.length === 0 && !menuLoading && (
            <div style={{ color: '#999', textAlign: 'center', padding: '40px 0' }}>
              暂无菜单数据，请先在菜单管理中添加菜单
            </div>
          )}
        </Spin>
      </Drawer>
    </div>
  );
};
