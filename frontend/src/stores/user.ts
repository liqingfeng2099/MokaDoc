import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getCurrentUser } from '@/api/yonghuguanli'

export const useUserStore = defineStore('user', () => {
  const currentUser = ref<API.UserVO | null>(null)
  const isLogin = ref(false)

  function setUser(user: API.UserVO) {
    currentUser.value = user
    isLogin.value = true
  }

  function logout() {
    currentUser.value = null
    isLogin.value = false
  }

  async function fetchCurrentUser() {
    try {
      const res = await getCurrentUser()
      if (res.data.code === 0 && res.data.data) {
        setUser(res.data.data)
      } else {
        logout()
      }
    } catch {
      logout()
    }
  }

  return { currentUser, isLogin, setUser, logout, fetchCurrentUser }
})
