import axios from 'axios'

const BASE = '/api/inference'

export function getInferenceStatus(baseUrl) {
  return axios.get(BASE + '/status', { params: baseUrl ? { baseUrl } : {} })
}

export function getInferenceModels(baseUrl) {
  return axios.get(BASE + '/models', { params: baseUrl ? { baseUrl } : {} })
}

export function getInferenceMetrics(baseUrl) {
  return axios.get(BASE + '/metrics', { params: baseUrl ? { baseUrl } : {} })
}

export function restartInference() {
  return axios.post(BASE + '/restart')
}
