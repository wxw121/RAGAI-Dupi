import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { ToastProvider } from '@/components/Toast'
import { KbListPage } from '@/pages/KbListPage'
import { KbDetailPage } from '@/pages/KbDetailPage'

export default function App() {
  return (
    <ToastProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<KbListPage />} />
          <Route path="/kb/:kbId" element={<KbDetailPage />} />
        </Routes>
      </BrowserRouter>
    </ToastProvider>
  )
}
