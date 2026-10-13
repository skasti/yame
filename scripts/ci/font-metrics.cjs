// Derive terminal cell dimensions from the installed TrueType font.
// The Nerd Font Mono variant has fixed-width glyph advances. Using the font's
// hhea line metrics avoids guessing a line height for each new typeface.
function measureCellSize(font, sizePx) {
  if (!Buffer.isBuffer(font) || font.length < 12 ||
      !Number.isFinite(sizePx) || sizePx <= 0 || sizePx > 72) {
    throw new Error('Invalid font metrics input');
  }
  const tableCount = font.readUInt16BE(4);
  if (tableCount < 1 || tableCount > 128 || 12 + tableCount * 16 > font.length) {
    throw new Error('Invalid TTF table directory');
  }
  const tables = new Map();
  for (let index = 0; index < tableCount; index++) {
    const pos = 12 + index * 16;
    const tag = font.toString('ascii', pos, pos + 4);
    const offset = font.readUInt32BE(pos + 8);
    const length = font.readUInt32BE(pos + 12);
    if (offset > font.length || length > font.length - offset) {
      throw new Error('Out-of-bounds TTF table: ' + tag);
    }
    tables.set(tag, {offset, length});
  }
  const required = (tag, length) => {
    const table = tables.get(tag);
    if (!table || table.length < length) throw new Error('Missing TTF metrics table: ' + tag);
    return table.offset;
  };
  const head = required('head', 20);
  const hhea = required('hhea', 36);
  const hmtx = required('hmtx', 4);
  const post = required('post', 16);
  const unitsPerEm = font.readUInt16BE(head + 18);
  const advanceWidth = font.readUInt16BE(hmtx);
  const ascender = font.readInt16BE(hhea + 4);
  const descender = font.readInt16BE(hhea + 6);
  const lineGap = font.readInt16BE(hhea + 8);
  const fixedPitch = font.readUInt32BE(post + 12);
  if (!unitsPerEm || !advanceWidth || !fixedPitch ||
      ascender <= descender || ascender - descender + lineGap <= 0) {
    throw new Error('Not a valid fixed-pitch font');
  }
  return {
    cellWidth: Math.ceil(advanceWidth * sizePx / unitsPerEm),
    cellHeight: Math.ceil((ascender - descender + lineGap) * sizePx / unitsPerEm),
    metrics: {unitsPerEm, advanceWidth, ascender, descender, lineGap},
  };
}

module.exports = {measureCellSize};
