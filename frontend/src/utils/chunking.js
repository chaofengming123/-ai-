export function validateChunkOptions(size, overlap) {
  if (!Number.isInteger(size) || size < 200 || size > 2000 || !Number.isInteger(overlap) || overlap < 0 || overlap > 200 || overlap * 2 >= size)
    return '块大小需为 200–2000 字符；重叠需为 0–200 字符，且小于块大小的一半。'
  return ''
}
