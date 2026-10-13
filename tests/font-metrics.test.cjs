const {test} = require('node:test');
const assert = require('node:assert/strict');
const {measureCellSize} = require('../scripts/ci/font-metrics.cjs');

function fakeFixedPitchFont({upm = 1000, advance = 600, ascender = 1050,
  descender = -250, lineGap = 100, fixedPitch = 1} = {}) {
  const font = Buffer.alloc(200);
  font.writeUInt32BE(0x00010000, 0);
  font.writeUInt16BE(4, 4);
  const entries = [
    ['head', 76, 54], ['hhea', 132, 36], ['hmtx', 168, 4], ['post', 172, 28],
  ];
  entries.forEach(([tag, offset, length], index) => {
    const pos = 12 + index * 16;
    font.write(tag, pos, 4, 'ascii');
    font.writeUInt32BE(offset, pos + 8);
    font.writeUInt32BE(length, pos + 12);
  });
  font.writeUInt16BE(upm, 76 + 18);
  font.writeInt16BE(ascender, 132 + 4);
  font.writeInt16BE(descender, 132 + 6);
  font.writeInt16BE(lineGap, 132 + 8);
  font.writeUInt16BE(advance, 168);
  font.writeUInt32BE(fixedPitch, 172 + 12);
  return font;
}

test('font metrics produce rounded-up character width and line height', () => {
  const measurements = measureCellSize(fakeFixedPitchFont(), 14);
  assert.deepEqual(measurements, {
    cellWidth: 9, cellHeight: 20,
    metrics: {unitsPerEm: 1000, advanceWidth: 600,
      ascender: 1050, descender: -250, lineGap: 100},
  });
  assert.equal(measureCellSize(fakeFixedPitchFont(), 16).cellWidth, 10);
  assert.equal(measureCellSize(fakeFixedPitchFont(), 16).cellHeight, 23);
});

test('invalid or non-monospace font metrics fail closed', () => {
  assert.throws(() => measureCellSize(Buffer.from('bad'), 14), /Invalid font/);
  assert.throws(() => measureCellSize(fakeFixedPitchFont({fixedPitch: 0}), 14), /fixed-pitch/);
  assert.throws(() => measureCellSize(fakeFixedPitchFont({upm: 0}), 14), /fixed-pitch/);
  assert.throws(() => measureCellSize(fakeFixedPitchFont(), 0), /Invalid font/);
  const font = fakeFixedPitchFont();
  font.writeUInt32BE(5000, 12 + 8);
  assert.throws(() => measureCellSize(font, 14), /Out-of-bounds/);
});
