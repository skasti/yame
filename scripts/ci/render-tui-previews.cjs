// Render trusted JVM-produced Mordant ANSI fixtures using an actual terminal emulator.
// Runs only in read-only PR CI; the privileged screenshot publisher never decodes PNGs.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {execFileSync} = require('node:child_process');

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


// Store ANSI as one JSON-escaped terminal row per line. Raw ESC bytes are not
// reliably displayed as a textual diff by GitHub, while this is UTF-8 text.
function frameSnapshot(rows) {
  return rows.map(row => JSON.stringify(row)).join('\n') + '\n';
}

function sha256(bytes) {
  return crypto.createHash('sha256').update(bytes).digest('hex');
}

const terminalOptions = {
  scale: 1,
  fontFamily: "'DejaVu Sans Mono', monospace",
  fontSize: 14,
  cellWidth: CELL_WIDTH,
  cellHeight: CELL_HEIGHT,
  theme: {background: '#101318', foreground: '#dce0e6', cursor: '#101318'},
};

function fontHash() {
  try {
    const file = execFileSync('fc-match', ['-f', '%{file}', 'DejaVu Sans Mono'],
      {encoding: 'utf8', timeout: 5000}).trim();
    return file ? sha256(fs.readFileSync(file)) : null;
  } catch {
    // Without a known font, do not trust a renderer cache from another machine.
    return null;
  }
}

function rendererIdentity(root) {
  return {
    schema: 1,
    rendererSha256: sha256(fs.readFileSync(__filename)),
    dependenciesSha256: sha256(fs.readFileSync(path.join(root, 'package.json'))),
    fontSha256: fontHash(),
    nodeMajor: Number(process.versions.node.split('.')[0]),
    terminalOptions,
  };
}

function canReuseFrame(snapshot, previousSnapshot, renderConfig, previousRenderConfig,
  hasPng, force = false) {
  return !force && hasPng && renderConfig.fontSha256 !== null &&
    renderConfig === previousRenderConfig && snapshot === previousSnapshot;
}

async function generate({root = process.cwd()} = {}) {
  let renderer = null;
  const outputDirectory = path.join(root, 'build/reports/tui-screenshots');
  const inputDirectory = path.join(root, 'build/reports/tui-frames');
  const baselineDirectory = path.join(root, 'assets/screenshots');
  fs.mkdirSync(outputDirectory, {recursive: true});
  const config = rendererIdentity(root);
  const configContents = JSON.stringify(config, null, 2) + '\n';
  const previousConfigFile = path.join(baselineDirectory, 'render-info.json');
  const previousConfig = fs.existsSync(previousConfigFile)
    ? fs.readFileSync(previousConfigFile, 'utf8') : null;
  fs.writeFileSync(path.join(outputDirectory, 'render-info.json'), configContents);
  for (const name of filenames) {
    const match = /^(\d+)x(\d+)/.exec(name);
    const rows = Number(match[1]), cols = Number(match[2]);
    const ansi = fs.readFileSync(path.join(inputDirectory, name + '.ansi'), 'utf8');
    const frameRows = framesToRows(ansi, rows);
    const snapshot = frameSnapshot(frameRows);
    fs.writeFileSync(path.join(outputDirectory, name + '.ansi.txt'), snapshot);
    const previousSnapshotFile = path.join(baselineDirectory, name + '.ansi.txt');
    const previousSnapshot = fs.existsSync(previousSnapshotFile)
      ? fs.readFileSync(previousSnapshotFile, 'utf8') : null;
    const previous = path.join(baselineDirectory, name + '.png');
    if (canReuseFrame(snapshot, previousSnapshot, configContents, previousConfig,
      fs.existsSync(previous), process.env.YAME_FORCE_TUI_RENDER === '1')) {
      fs.copyFileSync(previous, path.join(outputDirectory, name + '.png'));
      continue;
    }
    if (!renderer) {
      const [{createTerminal}, {createXtermBackend}] = await Promise.all([
        import('@termless/core'), import('@termless/xtermjs'),
      ]);
      renderer = {createTerminal, createXtermBackend, PNG: require('pngjs').PNG};
    }
    const {createTerminal, createXtermBackend, PNG} = renderer;
    const term = createTerminal({backend: createXtermBackend(), cols, rows});
    let image;
    try {
      // Address every line explicitly. A full-width line must not wrap the
      // next line or accidentally scroll away the first row.
      term.feed('\x1b[?25l\x1b[2J');
      for (let i = 0; i < frameRows.length; i++) {
        term.feed(`\x1b[${i + 1};1H${frameRows[i]}`);
      }
      image = Buffer.from(await term.screenshotPng(terminalOptions));
    } finally {
      await term.close();
    }
    if (image.length > PNG_LIMIT) throw new Error(`Preview exceeds 1 MiB: ${name}`);
    const decoded = decode(image);
    if (decoded.width !== cols * CELL_WIDTH || decoded.height !== rows * CELL_HEIGHT) {
      throw new Error(`Wrong preview dimensions: ${name}`);
    }
    const decode = buffer => PNG.sync.read(buffer);
    if (fs.existsSync(previous) && samePixels(fs.readFileSync(previous), image, decode)) {
      image = fs.readFileSync(previous);
    }
    fs.writeFileSync(path.join(outputDirectory, name + '.png'), image);
  }
}

if (require.main === module) {
  generate().catch(error => {console.error(error); process.exitCode = 1;});
}

module.exports = {sizes, filenames, framesToRows, frameSnapshot, canReuseFrame, samePixels, generate, CELL_WIDTH, CELL_HEIGHT};
