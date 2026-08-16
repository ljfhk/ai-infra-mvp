import axios from 'axios'

const BASE = '/api/inspection'

export function scanServer(data) {
  return axios.post(BASE + '/scan', data)
}

export function getHistory(ip) {
  return axios.get(BASE + '/history', { params: ip ? { ip } : {} })
}

export function healthCheck() {
  return axios.get(BASE + '/health')
}

export function getStats() {
  return axios.get(BASE + '/stats')
}
