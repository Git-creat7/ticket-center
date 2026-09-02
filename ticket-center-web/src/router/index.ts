import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(to, _from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, behavior: 'smooth' }
    return { top: 0 }
  },
  routes: [
    { path: '/', redirect: '/discover' },
    {
      path: '/discover',
      name: 'discover',
      component: () => import('../pages/DiscoverPage.vue'),
    },
    {
      path: '/events/:id',
      name: 'event-detail',
      component: () => import('../pages/EventDetailPage.vue'),
    },
    {
      path: '/reviews/:id',
      name: 'review-detail',
      component: () => import('../pages/ReviewDetailPage.vue'),
    },
    {
      path: '/reviews/new',
      name: 'review-create',
      component: () => import('../pages/ReviewCreatePage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/people/:id',
      name: 'person',
      component: () => import('../pages/PersonPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/following',
      name: 'following',
      component: () => import('../pages/FollowingPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/orders',
      name: 'orders',
      component: () => import('../pages/OrdersPage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/me',
      name: 'me',
      component: () => import('../pages/ProfilePage.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('../pages/LoginPage.vue'),
      meta: { bare: true },
    },
    { path: '/:pathMatch(.*)*', redirect: '/discover' },
  ],
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth && !localStorage.getItem('ticket-center-token')) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && localStorage.getItem('ticket-center-token')) {
    return { name: 'discover' }
  }
  return true
})

export default router
