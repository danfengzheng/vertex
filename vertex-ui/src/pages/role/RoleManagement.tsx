import { useState, useEffect } from 'react';
import { Table, Button, Space, message, Modal, Form, Input, Select, Drawer, Tree, Tag, Popconfirm, Spin } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, MenuOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { roleApi, RoleVO, RoleCreateDTO, RoleUpdateDTO, RoleQueryDTO } from '../../api/role';
import { roleMenuApi } from '../../api/roleMenu';
import { menuApi, MenuVO } from '../../api/menu';
import type { ApiResponse, PageResult } from '../../types/api';
import type { DataNode } from 'antd/es/tree';

/** 获取菜单显示名称，优先使用i18n翻译 */
const getDisplayName = (menu: MenuVO, t: (key: string) => string): string => {
  if (menu.i18nKey) {
    const translated = t(menu.i18nKey);
    if (translated !== menu.i18nKey) return translated;
  }
  return menu.name;
};

/** 将 MenuVO[] 树转为 Ant Design Tree DataNode[] */
const menuToTreeData = (menus: MenuVO[], t: (key: string) => string): DataNode[] =>
  menus.map(m => ({
    key: m.id,
    title: getDisplayName(m, t),
    children: m.children && m.children.length > 0 ? menuToTreeData(m.children, t) : undefined,
  }));

/** 构建菜单ID和父ID的映射关系 */
const buildMenuParentMap = (menus: MenuVO[], parentId: string | null = null): Map<string, string | null> => {
  const map = new Map<string, string | null>();
  menus.forEach(menu => {
    map.set(String(menu.id), parentId);
    if (menu.children && menu.children.length > 0) {
      const childMap = buildMenuParentMap(menu.children, String(menu.id));
      childMap.forEach((value, key) => {
        map.set(key, value);
      });
    }
  });
  return map;
};

/** 获取菜单的所有祖先ID（包括所有父菜单）*/
const getAncestorIds = (menuId: string, parentMap: Map<string, string | null>): string[] => {
  const ancestors: string[] = [];
  let currentId: string | null = menuId;
  
  while (currentId !== null) {
    const parentId = parentMap.get(currentId);
    if (parentId !== null && parentId !== undefined) {
      ancestors.push(parentId);
      currentId = parentId;
    } else {
      break;
    }
  }
  
  return ancestors;
};


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
  const [menuParentMap, setMenuParentMap] = useState<Map<string, string | null>>(new Map());

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
      if (menuRes.code === 200) {
        setAllMenuTree(menuRes.data);
        // 构建菜单父级关系映射
        const parentMap = buildMenuParentMap(menuRes.data);
        setMenuParentMap(parentMap);
      }

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
            checkStrictly
            treeData={menuToTreeData(allMenuTree, t)}
            checkedKeys={checkedMenuIds}
            onCheck={(checked) => {
              // checkStrictly 下只传回真正勾选的 key，再补全祖先 ID 用于提交和展示
              const rawChecked = Array.isArray(checked)
                ? (checked as (string | number)[])
                : ((checked as { checked: (string | number)[] }).checked ?? []);
              const ids = rawChecked.map(String);

              const selectedMenuIds = new Set(ids);
              ids.forEach(menuId => {
                const ancestorIds = getAncestorIds(menuId, menuParentMap);
                ancestorIds.forEach(ancestorId => selectedMenuIds.add(ancestorId));
              });

              setCheckedMenuIds(Array.from(selectedMenuIds));
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
