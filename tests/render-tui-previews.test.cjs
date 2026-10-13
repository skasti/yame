const {test} = require('node:test');
const assert = require('node:assert/strict');
const {
  filenames, framesToRows, frameSnapshot, canReuseFrame, fontHash, dependencyHash,
  samePixels, FONT_FAMILY, FONT_SIZE,
} = require('../scripts/ci/render-tui-previews.cjs');

test('renders exactly the ten approved compact and wide fixtures', () => {
  assert.deepEqual(filenames, [
    '30x100', '30x100-proxy', '30x100-dns', '30x100-transfers',
    '45x135', '45x135-proxy', '45x135-dns', '45x135-transfers',
    '50x150', '50x150-proxy',
  ]);
  assert.equal(FONT_FAMILY, 'JetBrainsMono Nerd Font Mono');
  assert.equal(FONT_SIZE, 14);
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

test('reuse verifies ANSI input, renderer identity, and the checked-in PNG digest', () => {
  const {createHash} = require('node:crypto');
  const hash = bytes => createHash('sha256').update(bytes).digest('hex');
  const config = {schema: 2, fontSha256: 'a'.repeat(64),
    dependenciesSha256: 'c'.repeat(64), rendererSha256: 'b'.repeat(64)};
  const png = Buffer.from('rendered PNG placeholder');
  const previous = {...config, pngSha256: {'30x100.png': hash(png)}};
  const reuse = (snapshot, old, metadata, image, force = false) =>
    canReuseFrame(snapshot, old, config, metadata, '30x100.png', image, force);
  assert.equal(reuse('frame', 'frame', previous, png), true);
  assert.equal(reuse('changed', 'frame', previous, png), false);
  assert.equal(reuse('frame', 'frame', previous, Buffer.from('replaced PNG')), false);
  assert.equal(reuse('frame', 'frame', {...previous, pngSha256: {}}, png), false);
  assert.equal(reuse('frame', 'frame', {...previous, rendererSha256: 'c'.repeat(64)}, png), false);
  assert.equal(reuse('frame', 'frame', previous, null), false);
  assert.equal(reuse('frame', 'frame', previous, png, true), false);
  assert.equal(reuse('frame', 'frame', null, png), false);
  const unknownFont = {...config, fontSha256: null};
  assert.equal(canReuseFrame('frame', 'frame', unknownFont,
    {...unknownFont, pngSha256: previous.pngSha256}, '30x100.png', png), false);
  const unknownDependencies = {...config, dependenciesSha256: null};
  assert.equal(canReuseFrame('frame', 'frame', unknownDependencies,
    {...unknownDependencies, pngSha256: previous.pngSha256}, '30x100.png', png), false);
});

test('font fingerprint includes bold and oblique faces as well as regular', () => {
  const faces = {
    Regular: Buffer.from('regular'),
    Bold: Buffer.from('bold'),
    Italic: Buffer.from('italic'),
    'Bold Italic': Buffer.from('bold italic'),
  };
  const resolve = pattern => {
    const style = pattern.split('style=')[1];
    return {family: FONT_FAMILY, style, bytes: faces[style]};
  };
  const original = fontHash(resolve);
  assert.match(original, /^[a-f0-9]{64}$/);
  for (const style of Object.keys(faces)) {
    const originalBytes = faces[style];
    faces[style] = Buffer.from('replacement');
    assert.notEqual(fontHash(resolve), original, style);
    faces[style] = originalBytes;
  }
  assert.equal(fontHash(() => null), null);
  assert.equal(fontHash(() => ({family: 'DejaVu Sans Mono', style: 'Regular',
    bytes: Buffer.from('fallback')})), null);
});

test('dependency fingerprint tracks transitive versions independent of JSON map order', () => {
  const root = require('node:path').resolve(__dirname, '..');
  const tree = {
    name: 'yame-tui-preview-tools',
    dependencies: {
      '@termless/xtermjs': {version: '0.10.2', dependencies: {inner: {version: '1.2.3'}}},
      '@termless/core': {version: '0.10.2'},
    },
  };
  const hash = data => dependencyHash(root, () => JSON.stringify(data));
  const original = hash(tree);
  assert.match(original, /^[a-f0-9]{64}$/);
  assert.equal(original, hash({...tree, dependencies: {
    '@termless/core': tree.dependencies['@termless/core'],
    '@termless/xtermjs': tree.dependencies['@termless/xtermjs'],
  }}));
  assert.notEqual(original, hash({...tree, dependencies: {
    ...tree.dependencies,
    '@termless/xtermjs': {version: '0.10.2', dependencies: {inner: {version: '1.2.4'}}},
  }}));
  assert.equal(hash({name: 'root', dependencies: {broken: {}}}), null);
  assert.equal(hash({name: 'root', dependencies: {}}), null);
  assert.equal(dependencyHash(root, () => {throw new Error('npm unavailable');}), null);
});
