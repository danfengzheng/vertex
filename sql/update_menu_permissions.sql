-- 补全 sys_menu.permission 字段
-- 仅更新 type=1 的叶子菜单（页面菜单），目录节点无需权限码

UPDATE sys_menu SET permission = 'system:user'      WHERE id = 11;
UPDATE sys_menu SET permission = 'system:menu'      WHERE id = 12;
UPDATE sys_menu SET permission = 'system:role'      WHERE id = 13;

UPDATE sys_menu SET permission = 'quote:source'     WHERE id = 21;
UPDATE sys_menu SET permission = 'quote:kline'      WHERE id = 22;

UPDATE sys_menu SET permission = 'strategy:config'  WHERE id = 31;
UPDATE sys_menu SET permission = 'strategy:signal'  WHERE id = 32;

UPDATE sys_menu SET permission = 'trade:account'    WHERE id = 41;
UPDATE sys_menu SET permission = 'trade:order'      WHERE id = 42;
UPDATE sys_menu SET permission = 'trade:position'   WHERE id = 43;
UPDATE sys_menu SET permission = 'trade:pnl'        WHERE id = 44;
UPDATE sys_menu SET permission = 'trade:symbol'     WHERE id = 45;

UPDATE sys_menu SET permission = 'chain:token'      WHERE id = 51;
UPDATE sys_menu SET permission = 'chain:alert'      WHERE id = 52;
