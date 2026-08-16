import { createRouter, createWebHistory } from 'vue-router'
import ServerList from '../views/ServerList.vue'

const routes = [
  { path: '/', name: 'ServerList', component: ServerList }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
