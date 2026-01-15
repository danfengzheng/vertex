import { useTranslation } from 'react-i18next';

/**
 * 语言切换 Hook
 */
export const useLanguage = () => {
  const { i18n } = useTranslation();

  const changeLanguage = (lng: string) => {
    i18n.changeLanguage(lng);
    localStorage.setItem('language', lng);
  };

  const currentLanguage = i18n.language;

  return {
    currentLanguage,
    changeLanguage,
    languages: [
      { code: 'zh-CN', label: '中文' },
      { code: 'en-US', label: 'English' },
    ],
  };
};
