import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import { parse as parseTemplate } from '@vue/compiler-dom'

test('all document panels belong to their selected document row, not the page footer', () => {
  const { descriptor } = parse(readFileSync(new URL('../src/views/DocumentView.vue', import.meta.url), 'utf8'))
  const ast = parseTemplate(descriptor.template.content)
  const panels = []
  function visit(node, ancestors = []) {
    if (['DocumentIndexPanel', 'DocumentChunkPreview'].includes(node.tag)
        || (node.tag === 'section' && node.props.some(prop => prop.name === 'aria-label' && prop.value?.content === '文档正文预览')))
      panels.push({ node, ancestors })
    for (const child of node.children || []) visit(child, [...ancestors, node])
  }
  visit(ast)
  assert.equal(panels.length, 3)
  for (const panel of panels) {
    assert.ok(panel.ancestors.some(node => node.tag === 'li' && node.props.some(prop => prop.name === 'for')))
    assert.match(panel.node.props.find(prop => prop.name === 'if').exp.content, /=== item\.id && canRead/)
  }
})

test('sidebar does not offer the vector experiment entry', () => {
  const source = readFileSync(new URL('../src/components/AppSidebar.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(source, /向量实验|\/embeddings/)
})

test('chat shows failures once outside transcript, without repeated incomplete badges', () => {
  const source = readFileSync(new URL('../src/views/ChatView.vue', import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  assert.doesNotMatch(descriptor.template.content, /message\.incomplete|回复未完成/)
  assert.equal((descriptor.template.content.match(/v-if="error"/g) || []).length, 1)
  assert.match(source, /message\.role !== 'assistant' \|\| message\.content/)
})
