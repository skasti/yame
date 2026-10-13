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

const FONT_FACES = [
  'DejaVu Sans Mono:style=Book',
  'DejaVu Sans Mono:style=Bold',
  'DejaVu Sans Mono:style=Oblique',
  'DejaVu Sans Mono:style=Bold Oblique',
];

function fontHash(resolve = pattern => {
  const output = execFileSync('fc-match', ['-f', '%{family}\\n%{style}\\n%{file}', pattern],
    {encoding: 'utf8', timeout: 5000}).trimEnd().split('\n');
  if (output.length !== 3 || !output[2]) throw new Error('Unknown font face');
  return {family: output[0], style: output[1], bytes: fs.readFileSync(output[2])};
}) {
  try {
    // Font rendering can select separate bold/italic faces for ANSI styles.
    const faces = FONT_FACES.map(pattern => {
      const font = resolve(pattern);
      if (!font?.bytes || !font.family || !font.style) throw new Error('Unknown font face');
      return [pattern, font.family, font.style, sha256(font.bytes)].join('\\0');
    });
    return sha256(Buffer.from(faces.join('\\n')));
  } catch {
    // Without verified faces, never reuse a PNG from another machine.
    return null;
  }
}

function dependencyHash(root, installedTree = () => execFileSync('npm', ['ls', '--all', '--json'],
  {cwd: root, encoding: 'utf8', timeout: 20000, maxBuffer: 8 * 1024 * 1024})) {
  try {
    const tree = JSON.parse(installedTree());
    const packages = [];
    const visit = (node, path, isRoot = false) => {
      // This project's package.json intentionally has no version field.
      if (!node || (!isRoot && typeof node.version !== 'string')) {
        throw new Error('Unresolved dependency');
      }
      packages.push([path, node.name || '', node.version || '(root)'].join('\\0'));
      for (const [name, child] of Object.entries(node.dependencies || {}).sort(
        ([a], [b]) => a.localeCompare(b))) {
        visit(child, path + '/' + name);
      }
    };
    if (!tree.dependencies || !Object.keys(tree.dependencies).length) return null;
    visit(tree, 'root', true);
    const manifest = fs.readFileSync(require('node:path').join(root, 'package.json'));
    return sha256(Buffer.concat([manifest, Buffer.from('\\n' + packages.join('\\n'))]));
  } catch {
    // A missing/invalid npm tree cannot establish whether renderer output changed.
    return null;
  }
}

function rendererIdentity(root) {
  return {
    schema: 2,
    rendererSha256: sha256(fs.readFileSync(__filename)),
    dependenciesSha256: dependencyHash(root),
    fontSha256: fontHash(),
    nodeMajor: Number(process.versions.node.split('.')[0]),
    terminalOptions,
  };
}

function canReuseFrame(snapshot, previousSnapshot, rendererIdentity, previousMetadata,
  pngName, pngBytes, force = false) {
  if (force || !pngBytes || rendererIdentity.fontSha256 === null ||
      rendererIdentity.dependenciesSha256 === null ||
      !previousMetadata || typeof previousMetadata !== 'object') return false;
  const {pngSha256, ...previousIdentity} = previousMetadata;
  return snapshot === previousSnapshot &&
    JSON.stringify(rendererIdentity) === JSON.stringify(previousIdentity) &&
    pngSha256 && pngSha256[pngName] === sha256(pngBytes);
}

async function generate({root = process.cwd()} = {}) {
  let renderer = null;
  const outputDirectory = path.join(root, 'build/reports/tui-screenshots');
  const inputDirectory = path.join(root, 'build/reports/tui-frames');
  const baselineDirectory = path.join(root, 'assets/screenshots');
  fs.mkdirSync(outputDirectory, {recursive: true});
  const config = rendererIdentity(root);
  const previousConfigFile = path.join(baselineDirectory, 'render-info.json');
  let previousConfig = null;
  if (fs.existsSync(previousConfigFile)) {
    try {
      previousConfig = JSON.parse(fs.readFileSync(previousConfigFile, 'utf8'));
    } catch { /* Invalid or older cache metadata requires rendering. */ }
  }
  const outputPngHashes = {};
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
    const pngName = name + '.png';
    const previous = path.join(baselineDirectory, pngName);
    const previousPng = fs.existsSync(previous) ? fs.readFileSync(previous) : null;
    if (canReuseFrame(snapshot, previousSnapshot, config, previousConfig,
      pngName, previousPng, process.env.YAME_FORCE_TUI_RENDER === '1')) {
      outputPngHashes[pngName] = sha256(previousPng);
      fs.writeFileSync(path.join(outputDirectory, pngName), previousPng);
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
    const decode = buffer => PNG.sync.read(buffer);
    const decoded = decode(image);
    if (decoded.width !== cols * CELL_WIDTH || decoded.height !== rows * CELL_HEIGHT) {
      throw new Error(`Wrong preview dimensions: ${name}`);
    }
    // A mismatched digest forces rendering. Only preserve an older PNG when its
    // *actual decoded pixels* match the freshly rendered output.
    if (previousPng) {
      try {
        if (samePixels(previousPng, image, decode)) image = previousPng;
      } catch { /* Corrupt baseline PNG: use freshly rendered pixels. */ }
    }
    outputPngHashes[pngName] = sha256(image);
    fs.writeFileSync(path.join(outputDirectory, pngName), image);
  }
  fs.writeFileSync(path.join(outputDirectory, 'render-info.json'),
    JSON.stringify({...config, pngSha256: outputPngHashes}, null, 2) + '\n');
}

if (require.main === module) {
  generate().catch(error => {console.error(error); process.exitCode = 1;});
}

module.exports = {sizes, filenames, framesToRows, frameSnapshot, canReuseFrame, fontHash, dependencyHash, samePixels, generate, CELL_WIDTH, CELL_HEIGHT};
