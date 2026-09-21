export const MAX_DOCUMENT_BYTES = 1024 * 1024
export function validateDocument(file) {
  if (!file) return '请先选择一个文件。'
  if (!/\.(txt|md)$/i.test(file.name)) return '请选择 .txt 或 .md 文件。'
  if (file.size === 0) return '不能上传空文件。'
  if (file.size > MAX_DOCUMENT_BYTES) return '文件不能超过 1 MB。'
  return ''
}
export function formatFileSize(size) {
  return size < 1024 ? `${size} B` : `${(size / 1024).toFixed(1)} KB`
}
