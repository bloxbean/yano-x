import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import test from 'node:test';
import { IMPORTED_DOCS, contentPathForRoute, repoPath } from './repo-sources.mjs';

test('binding learning chapters have canonical sources and distinct site routes', async () => {
  const chapters = Object.entries(IMPORTED_DOCS)
    .filter(([source]) => source.startsWith('docs/appchain/bindings/'));
  assert.equal(chapters.length, 6);
  assert.equal(new Set(chapters.map(([, route]) => route)).size, 6);
  assert.equal(contentPathForRoute('/bindings/'), 'bindings/index.md');
  for (const [source, route] of chapters) {
    assert.ok(route.startsWith('/bindings/'));
    const text = await fs.readFile(repoPath(source), 'utf8');
    assert.match(text, /^# /);
    assert.ok(text.length > 1000, `${source} must be a chapter, not a link stub`);
  }
});

test('first-workflow fixtures stay aligned with the packaged CLI reference exercise', async () => {
  const tutorial = await fs.readFile(repoPath('docs/appchain/bindings/01-first-workflow.md'), 'utf8');
  const reference = await fs.readFile(repoPath('docs/appchain/DECLARATIVE_BINDINGS_CLI.md'), 'utf8');
  const blocks = (text, language) => [...text.matchAll(
    new RegExp('```' + language + '\\n([\\s\\S]*?)```', 'g'),
  )].map((match) => match[1].trim());
  for (const language of ['yaml', 'json']) {
    const tutorialBlocks = blocks(tutorial, language);
    assert.equal(tutorialBlocks.length, language === 'yaml' ? 1 : 2);
    const referenceBlocks = blocks(reference, language);
    for (const block of tutorialBlocks) {
      assert.ok(referenceBlocks.includes(block), `tutorial ${language} fixture must match the reference`);
    }
  }
});
