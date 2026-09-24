import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import { parse as parseTemplate } from '@vue/compiler-dom'

test('index panel belongs to the selected document row, not the page footer', () => {
  const { descriptor } = parse(readFileSync(new URL('../src/views/DocumentView.vue', import.meta.url), 'utf8'))
  const ast = parseTemplate(descriptor.template.content)
  const panels = []
  function visit(node, ancestors = []) {
    if (node.tag === 'DocumentIndexPanel') panels.push({ node, ancestors })
    for (const child of node.children || []) visit(child, [...ancestors, node])
  }
  visit(ast)
  assert.equal(panels.length, 1)
  assert.ok(panels[0].ancestors.some(node => node.tag === 'li' && node.props.some(prop => prop.name === 'for')))
  assert.match(panels[0].node.props.find(prop => prop.name === 'if').exp.content, /indexDocument\?\.id === item\.id/)
})

test('chat shows failures once outside transcript, without repeated incomplete badges', () => {
  const source = readFileSync(new URL('../src/views/ChatView.vue', import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  assert.doesNotMatch(descriptor.template.content, /message\.incomplete|回复未完成/)
  assert.equal((descriptor.template.content.match(/v-if="error"/g) || []).length, 1)
  assert.match(source, /message\.role !== 'assistant' \|\| message\.content/)
})
