import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './i18n' // 初始化多语言
import 'antd/dist/reset.css' // Ant Design 样式
import './index.css'
import App from './App.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
