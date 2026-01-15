import { useState, useEffect } from 'react';
import { Table, Button, Space, message, Modal, Form, Input } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

export const RoleManagement = () => {
  const { t } = useTranslation();
  
  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>{t('text.role.title')}</h2>
      </div>
      <div style={{ padding: '40px 0', textAlign: 'center', color: '#999' }}>
        <p>角色管理页面开发中...</p>
      </div>
    </div>
  );
};
