// Render trusted JVM-produced Mordant ANSI fixtures using an actual terminal emulator.
// Runs only in read-only PR CI; the privileged screenshot publisher never decodes PNGs.
const fs = require('node:fs');
const path = require('node:path');

const sizes = [[30, 100], [45, 135], [50, 150]];
const filenames = sizes.flatMap(([rows, cols]) => [
  `${rows}x${cols}`,
  ...((cols < 150 ? ['proxy', 'dns', 'transfers'] : ['proxy']).map(panel => `${rows}x${cols}-${panel}`)),
]);
const CELL_WIDTH = 9;
const CELL_HEIGHT = 18;
const PNG_LIMIT = 1024 * 1024;

function framesToRows(input, expectedRows) {
  const rows = input.split('\n');
  if (rows.length !== expectedRows || rows.some(row => row.includes('\r'))) {
    throw new Error(`Expected ${expectedRows} LF-separated terminal rows`);
  }
  return rows;
}

// Decode RGBA, not PNG chunks: equivalent pixels with different compression/metadata
// should reuse the checked-in bytes exactly rather than churn screenshot commits.
function samePixels(previousBytes, currentBytes, decode) {
  const a = decode(previousBytes);
  const b = decode(currentBytes);
  return a.width === b.width && a.height === b.height && a.data.equals(b.data);
}

async function generate({root = process.cwd()} = {}) {
  const {createTerminal} = await import('@termless/core');
  const {createXtermBackend} = await import('@termless/xtermjs');
  const {PNG} = require('pngjs');
  const outputDirectory = path.join(root, 'build/reports/tui-screenshots');
  const inputDirectory = path.join(root, 'build/reports/tui-frames');
  const baselineDirectory = path.join(root, 'assets/screenshots');
  fs.mkdirSync(outputDirectory, {recursive: true});
  const decode = buffer => PNG.sync.read(buffer);
  for (const name of filenames) {
    const match = /^(\d+)x(\d+)/.exec(name);
    const rows = Number(match[1]), cols = Number(match[2]);
    const ansi = fs.readFileSync(path.join(inputDirectory, name + '.ansi'), 'utf8');
    const frameRows = framesToRows(ansi, rows);
    const term = createTerminal({backend: createXtermBackend(), cols, rows});
    let image;
    try {
      // Address every line explicitly. A full-width line must not wrap the
      // next line or accidentally scroll away the first row.
      term.feed('\x1b[?25l\x1b[2J');
      for (let i = 0; i < frameRows.length; i++) {
        term.feed(`\x1b[${i + 1};1H${frameRows[i]}`);
      }
      image = Buffer.from(await term.screenshotPng({
        scale: 1,
        fontFamily: "'DejaVu Sans Mono', monospace",
        fontSize: 14,
        cellWidth: CELL_WIDTH,
        cellHeight: CELL_HEIGHT,
        theme: {
          background: '#101318',
          foreground: '#dce0e6',
          cursor: '#101318',
        },
      }));
    } finally {
      await term.close();
    }
    if (image.length > PNG_LIMIT) throw new Error(`Preview exceeds 1 MiB: ${name}`);
    const decoded = decode(image);
    if (decoded.width !== cols * CELL_WIDTH || decoded.height !== rows * CELL_HEIGHT) {
      throw new Error(`Wrong preview dimensions: ${name}`);
    }
    const previous = path.join(baselineDirectory, name + '.png');
    if (fs.existsSync(previous) && samePixels(fs.readFileSync(previous), image, decode)) {
      image = fs.readFileSync(previous);
    }
    fs.writeFileSync(path.join(outputDirectory, name + '.png'), image);
  }
}

if (require.main === module) {
  generate().catch(error => {console.error(error); process.exitCode = 1;});
}

module.exports = {sizes, filenames, framesToRows, samePixels, generate, CELL_WIDTH, CELL_HEIGHT};
