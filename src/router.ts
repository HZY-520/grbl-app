import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/home' },
  {
    path: '/home',
    name: 'home',
    component: () => import('./ui/views/HomeView.vue'),
    meta: { tab: 'home', title: '激光雕刻' }
  },
  {
    path: '/convert',
    name: 'convert',
    component: () => import('./ui/views/ConvertHubView.vue'),
    meta: { tab: 'convert', title: '图案生成' }
  },
  {
    path: '/convert/image',
    name: 'convert-image',
    component: () => import('./ui/views/ImageConvertView.vue'),
    meta: { title: '图片转雕刻' }
  },
  {
    path: '/convert/text',
    name: 'convert-text',
    component: () => import('./ui/views/TextConvertView.vue'),
    meta: { title: '文字转雕刻' }
  },
  {
    path: '/convert/svg',
    name: 'convert-svg',
    component: () => import('./ui/views/SvgConvertView.vue'),
    meta: { title: 'SVG 转雕刻' }
  },
  {
    path: '/file',
    name: 'file',
    component: () => import('./ui/views/FileView.vue'),
    meta: { tab: 'file', title: '雕刻文件' }
  },
  {
    path: '/preview',
    name: 'preview',
    component: () => import('./ui/views/PreviewView.vue'),
    meta: { title: '路径预览' }
  },
  {
    path: '/jog',
    name: 'jog',
    component: () => import('./ui/views/JogView.vue'),
    meta: { tab: 'jog', title: '运动控制' }
  },
  {
    path: '/connect',
    name: 'connect',
    component: () => import('./ui/views/ConnectView.vue'),
    meta: { title: '连接设备' }
  },
  {
    path: '/config',
    name: 'config',
    component: () => import('./ui/views/ConfigView.vue'),
    meta: { title: '机器参数' }
  },
  {
    path: '/terminal',
    name: 'terminal',
    component: () => import('./ui/views/TerminalView.vue'),
    meta: { title: '串口终端' }
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('./ui/views/SettingsView.vue'),
    meta: { tab: 'settings', title: '设置' }
  },
  {
    path: '/about',
    name: 'about',
    component: () => import('./ui/views/AboutView.vue'),
    meta: { title: '关于' }
  }
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes
})