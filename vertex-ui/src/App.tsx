import { BrowserRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import enUS from 'antd/locale/en_US';
import { useTranslation } from 'react-i18next';
import { AppRouter } from './router';

function App() {
  const { i18n } = useTranslation();
  
  // 根据当前语言设置 Ant Design 的语言包
  const antdLocale = i18n.language === 'zh-CN' ? zhCN : enUS;

  return (
    <ConfigProvider locale={antdLocale}>
      <BrowserRouter>
        <AppRouter />
      </BrowserRouter>
    </ConfigProvider>
  );
}

export default App;
