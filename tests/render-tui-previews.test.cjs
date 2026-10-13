const {test} = require('node:test');
const assert = require('node:assert/strict');
const {
  filenames, framesToRows, frameSnapshot, canReuseFrame, samePixels, CELL_WIDTH, CELL_HEIGHT,
} = require('../scripts/ci/render-tui-previews.cjs');

test('renders exactly the ten approved compact and wide fixtures', () => {
  assert.deepEqual(filenames, [
    '30x100', '30x100-proxy', '30x100-dns', '30x100-transfers',
    '45x135', '45x135-proxy', '45x135-dns', '45x135-transfers',
    '50x150', '50x150-proxy',
  ]);
  assert.equal(CELL_WIDTH, 9);
  assert.equal(CELL_HEIGHT, 18);
});

test('ANSI rows retain styles while malformed frame shapes are rejected', () => {
  const frame = '\x1b[31mRED\x1b[0m\nsecond';
  assert.deepEqual(framesToRows(frame, 2), ['\x1b[31mRED\x1b[0m', 'second']);
  assert.throws(() => framesToRows(frame, 3), /Expected 3/);
  assert.throws(() => framesToRows('first\r\nsecond', 2), /LF-separated/);
});

test('pixel comparison ignores encoded bytes but detects actual visual differences', () => {
  const pixels = {
    a: {width: 1, height: 1, data: Buffer.from([255, 0, 0, 255])},
    b: {width: 1, height: 1, data: Buffer.from([255, 0, 0, 255])},
    c: {width: 1, height: 1, data: Buffer.from([0, 0, 255, 255])},
    d: {width: 2, height: 1, data: Buffer.from([255, 0, 0, 255, 0, 0, 0, 0])},
  };
  const decode = encoded => pixels[encoded.toString('utf8')];
  assert.ok(samePixels(Buffer.from('a'), Buffer.from('b'), decode));
  assert.equal(samePixels(Buffer.from('a'), Buffer.from('c'), decode), false);
  assert.equal(samePixels(Buffer.from('a'), Buffer.from('d'), decode), false);
});

test('ANSI snapshots escape control characters into reviewable UTF-8 rows', () => {
  const rows = ['\x1b[31mHello\x1b[0m', 'Windows\\folder', 'norsk æøå'];
  const snapshot = frameSnapshot(rows);
  assert.equal(snapshot.split('\n').length, 4);
  assert.ok(snapshot.includes('\\u001b[31m'));
  assert.ok(!snapshot.includes('\x1b'));
  assert.deepEqual(snapshot.trimEnd().split('\n').map(JSON.parse), rows);
});

test('reuse requires identical snapshot, fingerprinted font and full renderer identity', () => {
  const config = {schema: 1, fontSha256: 'a'.repeat(64)};
  const prior = JSON.stringify(config, null, 2) + '\n';
  assert.equal(canReuseFrame('frame', 'frame', config, prior, true), true);
  assert.equal(canReuseFrame('changed', 'frame', config, prior, true), false);
  assert.equal(canReuseFrame('frame', 'frame', config, 'other', true), false);
  assert.equal(canReuseFrame('frame', 'frame', config, prior, false), false);
  assert.equal(canReuseFrame('frame', 'frame', config, prior, true, true), false);
  assert.equal(canReuseFrame('frame', 'frame', config, null, true), false);
  const unknownFont = {...config, fontSha256: null};
  assert.equal(canReuseFrame('frame', 'frame', unknownFont,
    JSON.stringify(unknownFont, null, 2) + '\n', true), false);
});
