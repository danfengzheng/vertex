import { Select } from 'antd';
import { useLanguage } from '../hooks/useLanguage';

/**
 * 语言切换组件
 */
export const LanguageSwitcher = () => {
  const { currentLanguage, changeLanguage, languages } = useLanguage();

  return (
    <Select
      value={currentLanguage}
      onChange={changeLanguage}
      style={{ width: 120 }}
    >
      {languages.map((lang) => (
        <Select.Option key={lang.code} value={lang.code}>
          {lang.label}
        </Select.Option>
      ))}
    </Select>
  );
};
