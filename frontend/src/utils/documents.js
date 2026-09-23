export const MAX_DOCUMENT_BYTES = 5 * 1024 * 1024
export function validateDocument(file) {
  if (!file) return '请先选择一个文件。'
  if (!/\.(txt|md|pdf|docx|csv|tsv|json|html|htm|rtf)$/i.test(file.name)) return '支持 TXT、Markdown、PDF、DOCX、CSV、TSV、JSON、HTML 和 RTF。'
  if (file.size === 0) return '不能上传空文件。'
  if (file.size > MAX_DOCUMENT_BYTES) return '文件不能超过 5 MB。'
  return ''
}
export function formatFileSize(size) {
  return size < 1024 ? `${size} B` : `${(size / 1024).toFixed(1)} KB`
}
