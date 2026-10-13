const {test} = require('node:test');
const assert = require('node:assert/strict');
const {parseProof, screenshotOnly, successfulSource, gate, prepare, publish, gitBlobSha, previewPublicationPlan} =
  require('../scripts/ci/tui-previews.cjs');
const head = 'a'.repeat(40), parent = 'b'.repeat(40), base = 'c'.repeat(40), mergeSha = 'd'.repeat(40);
const proof = {pr: 71, run: 123, base};
const commit = {parents: [{sha: parent}], files: [{filename: 'assets/screenshots/30x100.png', status: 'modified'}],
  commit: {message: `Update TUI screenshots\n\nYAME-Screenshots: ${JSON.stringify(proof)}`}};
const run = {id: 123, path: '.github/workflows/ci.yml', event: 'pull_request', conclusion: 'success',
  head_sha: parent, head_repository: {full_name: 'skasti/yame'}, pull_requests: [{number: 71, base: {sha: base}}]};
const pr = {number: 71, state: 'open', merge_commit_sha: mergeSha, head: {sha: head, ref: 'feature/test', repo: {full_name: 'skasti/yame'}},
  base: {sha: base, repo: {full_name: 'skasti/yame'}}};
function environment({sourceRun = run, currentCommit = commit, currentPr = pr, jobs = [{name: 'build-and-test', conclusion: 'success'}], event = 'pull_request'} = {}) {
  const outputs = {};
  const github = {rest: {
    pulls: {get: async () => ({data: currentPr})}, repos: {getCommit: async () => ({data: currentCommit})},
    git: {getCommit: async () => ({data: {parents: [{sha: currentPr.base.sha}, {sha: currentPr.head.sha}]}})},
    actions: {getWorkflowRun: async () => ({data: sourceRun}), listJobsForWorkflowRun: 'jobs', listWorkflowRunArtifacts: 'artifacts'},
  }, paginate: async method => method === 'jobs' ? jobs : [{name: 'yame-tui-screenshots-v3', expired: false, size_in_bytes: 1000}]};
  const context = {repo: {owner: 'skasti', repo: 'yame'}, sha: event === 'pull_request' ? mergeSha : head, eventName: event,
    payload: {pull_request: {number: 71, head: {sha: head}}, inputs: {pr_number: '71'},
      workflow_run: sourceRun, repository: {default_branch: 'main'}}};
  const core = {setOutput: (key, value) => outputs[key] = value, info: () => {}};
  return {github, context, core, outputs};
}

test('proof rejects malformed data', () => {
  assert.equal(parseProof('YAME-Screenshots: nope'), null);
  assert.equal(parseProof('YAME-Screenshots: {}'), null);
  assert.deepEqual(parseProof(commit.commit.message), proof);
});
test('only allowlisted generated TUI files and single parent are accepted', () => {
  assert.equal(screenshotOnly(commit), true);
  assert.equal(screenshotOnly({...commit, files: [
    {filename: 'assets/screenshots/30x100.ansi.txt', status: 'added'},
    {filename: 'assets/screenshots/render-info.json', status: 'added'},
  ]}), true);
  for (const files of [[], [{filename: 'src/main.kt', status: 'modified'}],
    [{filename: 'assets/screenshots/30x100.png', status: 'renamed'}],
    [{filename: 'assets/screenshots/arbitrary.sh', status: 'added'}]]) {
    assert.equal(screenshotOnly({...commit, files}), false);
  }
  assert.equal(screenshotOnly({...commit, parents: [{sha: parent}, {sha: base}]}), false);
});
test('source requires successful PR CI on exact parent and base', () => {
  assert.equal(successfulSource(run, 71, parent, base), true);
  assert.equal(successfulSource({...run, path: '.github/workflows/ci.yml@main'}, 71, parent, base), true);
  for (const patch of [{conclusion: 'failure'}, {conclusion: 'cancelled'}, {event: 'workflow_dispatch'},
    {path: '.github/workflows/other.yml'}, {head_sha: head}, {pull_requests: []}]) {
    assert.equal(successfulSource({...run, ...patch}, 71, parent, base), false);
  }
  assert.equal(successfulSource(run, 71, parent, head), false);
});
test('verified screenshot successor skips heavy build on PR', async () => {
  for (const event of ['pull_request']) {
    const env = environment({event}); await gate(env);
    assert.equal(env.outputs.reuse, 'true');
    assert.equal(env.outputs.checkout_ref, mergeSha);
  }
});
test('failed parent, changed base, code changes or failed build never reuse green status', async () => {
  for (const options of [
    {sourceRun: {...run, conclusion: 'failure'}},
    {currentPr: {...pr, base: {...pr.base, sha: head}}},
    {currentCommit: {...commit, files: [...commit.files, {filename: 'src/main.kt', status: 'modified'}]}},
    {jobs: [{name: 'build-and-test', conclusion: 'failure'}]},
    {currentCommit: {...commit, commit: {message: 'ordinary screenshot edit'}}},
    {sourceRun: {...run, event: 'workflow_dispatch'}},
  ]) {
    const env = environment(options); await gate(env); assert.equal(env.outputs.reuse, 'false');
  }
});
test('stale PR cannot validate a different head', async () => {
  const env = environment({currentPr: {...pr, head: {...pr.head, sha: parent}}});
  await assert.rejects(gate(env), /head changed/);
});
test('publisher refuses forks, failed runs, dispatch runs and stale heads', async () => {
  for (const sourceRun of [{...run, conclusion: 'failure'}, {...run, event: 'workflow_dispatch'},
    {...run, head_repository: {full_name: 'attacker/yame'}}, run]) {
    const env = environment({sourceRun}); await prepare(env); assert.equal(env.outputs.publish, 'false');
  }
});

test('publisher delays Dependabot only while the base lacks diffable snapshots', async () => {
  const env = environment({
    sourceRun: {...run, head_sha: head},
    currentPr: {...pr, user: {login: 'dependabot[bot]'}},
  });
  env.github.paginate = async () => { throw new Error('Unexpected artifact lookup'); };
  env.github.rest.git.getCommit = async () => ({data: {tree: {sha: 'base-tree'}}});
  env.github.rest.git.getTree = async () => ({data: {truncated: false, tree: []}});
  await prepare(env);
  assert.equal(env.outputs.publish, 'false');
  assert.equal(env.outputs.pr_number, undefined);
});

test('publisher allows Dependabot when base contains complete snapshots', async () => {
  const env = environment({
    sourceRun: {...run, head_sha: head},
    currentPr: {...pr, user: {login: 'dependabot[bot]'}},
  });
  const names = ['30x100', '30x100-proxy', '30x100-dns', '30x100-transfers',
    '45x135', '45x135-proxy', '45x135-dns', '45x135-transfers',
    '50x150', '50x150-proxy'];
  const snapshotPaths = [...names.map(n => n + '.png'), ...names.map(n => n + '.ansi.txt'), 'render-info.json'];
  env.github.rest.git.getCommit = async () => ({data: {tree: {sha: 'base-tree'}}});
  env.github.rest.git.getTree = async () => ({data: {truncated: false, tree: snapshotPaths.map(name => ({
    path: 'assets/screenshots/' + name, sha: 'a'.repeat(40), type: 'blob', mode: '100644',
  }))}});
  await prepare(env);
  assert.equal(env.outputs.publish, 'true');
  assert.equal(env.outputs.pr_number, 71);
});

test('publisher permits live same-repo PR with bounded artifact', async () => {
  const env = environment({sourceRun: {...run, head_sha: head}}); await prepare(env);
  assert.equal(env.outputs.publish, 'true'); assert.equal(env.outputs.pr_number, 71);
});
test('stale publication returns before reading artifacts or writing git objects', async () => {
  const env = environment(); await publish(env, 71, '/does/not/exist');
});

test('mismatched immutable merge parents fail the gate', async () => {
  const env = environment();
  env.github.rest.git.getCommit = async () => ({data: {parents: [{sha: head}, {sha: head}]}});
  await assert.rejects(gate(env), /Merge head\/base changed/);
});
test('ARM64 cannot skip a failed or missing parent ARM64 run', async () => {
  for (const conclusion of ['failure', 'success']) {
    const env = environment();
    env.github.rest.actions.listWorkflowRuns = 'arm64';
    const paginate = env.github.paginate;
    env.github.paginate = async (method, args) => method === 'arm64'
      ? [{conclusion, pull_requests: [{number: 71, base: {sha: base}}]}] : paginate(method, args);
    await gate(env, {requireArm64: true});
    assert.equal(env.outputs.reuse, String(conclusion === 'success'));
  }
});
test('PNG validation rejects arbitrary bytes and incorrect terminal dimensions', () => {
  const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
  const {validatePng} = require('../scripts/ci/tui-previews.cjs');
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yame-png-'));
  try {
    const file = path.join(directory, 'preview.png');
    fs.writeFileSync(file, 'not a PNG');
    assert.throws(() => validatePng(file, '30x100.png'), /Invalid screenshot/);
    const bytes = Buffer.alloc(33);
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]).copy(bytes);
    bytes.write('IHDR', 12); bytes.writeUInt32BE(900, 16); bytes.writeUInt32BE(540, 20);
    fs.writeFileSync(file, bytes);
    assert.equal(validatePng(file, '30x100.png').length, 33);
    assert.throws(() => validatePng(file, '45x135.png'), /Invalid screenshot/);
  } finally { fs.rmSync(directory, {recursive: true, force: true}); }
});

for (const scenario of ['success', 'base-changed', 'closed', 'head-moved', 'get-failed', 'verification-failed', 'status-failed', 'push-timeout', 'list-comments-failed', 'create-comment-failed', 'update-comment-failed', 'unchanged-comment-failed', 'create-comment-timeout']) {
test(`publication recovers safely: ${scenario}`, async () => {
  const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yame-publish-'));
  const sourceRun = {...run, head_sha: head, html_url: 'https://github.com/skasti/yame/actions/runs/123'};
  const env = environment({sourceRun});
  const successor = 'e'.repeat(40);
  const calls = [];
  let moved = false;
  let failureActive = true;
  let changedBase = false;
  let wasClosed = false;
  let commentExists = false;
  try {
    const names = ['30x100', '30x100-proxy', '30x100-dns', '30x100-transfers',
      '45x135', '45x135-proxy', '45x135-dns', '45x135-transfers', '50x150', '50x150-proxy'];
    const hashes = {};
    const {createHash} = require('node:crypto');
    for (const name of names) {
      const [rows, columns] = name.match(/^(\d+)x(\d+)/).slice(1).map(Number);
      const png = Buffer.alloc(33); Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]).copy(png);
      png.write('IHDR', 12); png.writeUInt32BE(columns * 9, 16); png.writeUInt32BE(rows * 18, 20);
      fs.writeFileSync(path.join(directory, `${name}.png`), png);
      hashes[`${name}.png`] = createHash('sha256').update(png).digest('hex');
      fs.writeFileSync(path.join(directory, `${name}.ansi.txt`),
        Array(rows).fill(JSON.stringify('synthetic frame')).join('\n') + '\n');
    }
    fs.writeFileSync(path.join(directory, 'render-info.json'), JSON.stringify({
      schema: 2, rendererSha256: 'a'.repeat(64), dependenciesSha256: 'b'.repeat(64),
      fontSha256: 'c'.repeat(64), nodeMajor: 24, terminalOptions: {scale: 1},
      pngSha256: hashes,
    }, null, 2) + '\n');
    fs.writeFileSync(path.join(directory, 'source.json'), JSON.stringify({run: 123, pr: 71, head, base, merge: mergeSha}));
    env.github.rest.pulls.get = async () => {
      if (moved && failureActive && scenario === 'get-failed') throw new Error('Get failed');
      const current = moved ? {...pr, head: {...pr.head, sha: failureActive && scenario === 'head-moved' ? mergeSha : successor}} : pr;
      if (moved && failureActive && scenario === 'base-changed') changedBase = true;
      if (moved && failureActive && scenario === 'closed') wasClosed = true;
      return {data: {...current, base: changedBase ? {...pr.base, sha: mergeSha} : pr.base,
        state: wasClosed ? 'closed' : pr.state}};
    };
    env.github.rest.git = {
      getCommit: async ({commit_sha}) => ({data: commit_sha === mergeSha
        ? {parents: [{sha: base}, {sha: head}]} : {tree: {sha: 'old-tree'}}}),
      getTree: async () => ({data: {truncated: false, tree: []}}),
      createBlob: async ({content}) => ({data: {sha: gitBlobSha(Buffer.from(content, 'base64'))}}),
      createTree: async args => { calls.push(['tree', args]); return {data: {sha: scenario === 'unchanged-comment-failed' ? 'old-tree' : 'new-tree'}}; },
      createCommit: async args => { calls.push(['commit', args]); return {data: {sha: successor}}; },
      updateRef: async args => { calls.push(['push', args]); moved = true; if (failureActive && scenario === 'push-timeout') throw new Error('Push timed out'); },
    };
    env.github.rest.repos.getCommit = async () => ({data: {...commit, parents: [{sha: head}],
      ...(failureActive && scenario === 'verification-failed' ? {files: []} : {})}});
    env.github.rest.repos.createCommitStatus = async args => {
      calls.push(['status', args]);
      if (failureActive && scenario === 'status-failed') throw new Error('Status failed');
    };
    const comment = async args => {
      calls.push(['comment', args]);
      if (failureActive && scenario === 'create-comment-timeout') {
        commentExists = true;
        throw new Error('Comment created, but response timed out');
      }
      if (failureActive && ['create-comment-failed', 'update-comment-failed', 'unchanged-comment-failed'].includes(scenario)) {
        throw new Error('Comment failed');
      }
    };
    env.github.rest.issues = {listComments: 'comments', createComment: comment, updateComment: comment};
    const paginate = env.github.paginate;
    env.github.paginate = async (method, args) => {
      if (method !== 'comments') return paginate(method, args);
      if (failureActive && scenario === 'list-comments-failed') throw new Error('List comments failed');
      return commentExists || scenario === 'update-comment-failed'
        ? [{id: 456, user: {login: 'github-actions[bot]'}, body: '<!-- yame-tui-previews -->'}] : [];
    };
    const rollback = async (_, args) => {calls.push(['rollback', args]); moved = false;};
    if (!['success', 'head-moved'].includes(scenario)) {
      await assert.rejects(publish(env, 71, directory, {rollback}));
      if (scenario === 'unchanged-comment-failed') assert.ok(!calls.some(c => c[0] === 'rollback'));
      else assert.deepEqual(calls.at(-1), ['rollback', {branch: pr.head.ref, sha: successor, parent: head}]);
      const commentFailure = ['list-comments-failed', 'create-comment-failed', 'update-comment-failed', 'create-comment-timeout'].includes(scenario);
      if (scenario !== 'status-failed' && !commentFailure) assert.ok(!calls.some(c => c[0] === 'status'));
      // Restoration makes retry possible after a transient error.
      await prepare(env);
      if (changedBase || wasClosed) {
        assert.equal(env.outputs.publish, 'false');
        return;
      }
      assert.equal(env.outputs.publish, 'true');
      failureActive = false;
      await publish(env, 71, directory, {rollback});
      assert.equal(calls.at(-1)[0], 'comment');
      if (scenario === 'create-comment-timeout') assert.equal(calls.at(-1)[1].comment_id, 456);
      return;
    }
    await publish(env, 71, directory, {rollback});
    if (scenario === 'head-moved') {
      assert.deepEqual(calls.map(c => c[0]), ['tree', 'commit', 'push']);
      return;
    }
    assert.deepEqual(calls.map(c => c[0]), ['tree', 'commit', 'push', 'status', 'comment']);
    assert.equal(calls[0][1].tree.length, 21);
    assert.ok(calls[0][1].tree.every(t => t.path.startsWith('assets/screenshots/') &&
      /\.(?:png|ansi\.txt)$|\/render-info\.json$/.test(t.path)));
    assert.deepEqual(calls[1][1].parents, [head]);
    assert.equal(calls[2][1].force, false);
    assert.equal(calls[3][1].sha, successor);
    assert.equal(calls[3][1].context, 'build-and-test');
    assert.equal(calls[3][1].state, 'success');
    assert.ok(calls[4][1].body.includes(`/${successor}/assets/screenshots/30x100.png`));
    assert.equal((calls[4][1].body.match(/!\[/g) || []).length, 10);
    assert.ok(calls[4][1].body.includes('30x100.ansi.txt'));
    assert.ok(!calls[4][1].body.includes('<details>'));
  } finally { fs.rmSync(directory, {recursive: true, force: true}); }
});
}

for (const workflow of ['ci.yml', 'arm64.yml']) {
  test(`${workflow} loads only the base gate and fails closed before it exists`, async () => {
    const fs = require('node:fs'), vm = require('node:vm');
    const yaml = fs.readFileSync(require('node:path').join(__dirname, '../.github/workflows', workflow), 'utf8');
    const gateJob = yaml.split('  preview-gate:')[1].split(/\n  [a-z]/)[0];
    assert.ok(gateJob.includes('ref: ${{ github.event.pull_request.base.sha || github.sha }}'));
    assert.ok(gateJob.includes('path: trusted-ci'));
    const script = gateJob.split('          script: |\n')[1].split('\n').map(line => line.slice(12)).join('\n');
    for (const exists of [false, true]) {
      const env = environment();
      let trustedCalls = 0;
      const requireTrusted = name => {
        if (name === 'node:fs') return {existsSync: p => {assert.equal(p, './trusted-ci/scripts/ci/tui-previews.cjs'); return exists;}};
        assert.equal(name, './trusted-ci/scripts/ci/tui-previews.cjs');
        return {gate: async (received, options) => {
          trustedCalls++;
          assert.equal(Boolean(options?.requireArm64), workflow === 'arm64.yml');
          received.core.setOutput('reuse', 'false');
        }};
      };
      await vm.runInNewContext(`(async () => {${script}\n})()`, {...env, require: requireTrusted});
      assert.equal(env.outputs.reuse, 'false');
      assert.equal(trustedCalls, Number(exists));
      if (!exists) {
        assert.equal(env.outputs.checkout_ref, mergeSha);
        assert.equal(env.outputs.head_sha, head);
        assert.equal(env.outputs.base_sha, base);
      }
    }
  });
}

test('oversized provenance and PNG are rejected before reading their bytes', async () => {
  const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
  const {validatePng} = require('../scripts/ci/tui-previews.cjs');
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yame-bounds-'));
  const originalRead = fs.readFileSync;
  try {
    fs.writeFileSync(path.join(directory, 'source.json'), ' '.repeat(4097));
    const png = path.join(directory, '30x100.png');
    fs.writeFileSync(png, Buffer.alloc(1024 * 1024 + 1));
    fs.readFileSync = () => {throw new Error('Unbounded read attempted');};
    const env = environment({sourceRun: {...run, head_sha: head}});
    await assert.rejects(publish(env, 71, directory), /bounded regular artifact/);
    assert.throws(() => validatePng(png, '30x100.png'), /bounded regular artifact/);
  } finally {
    fs.readFileSync = originalRead;
    fs.rmSync(directory, {recursive: true, force: true});
  }
});

test('publisher downloads the exact source artifact with a bounded ZIP before extraction', async () => {
  const fs = require('node:fs'), vm = require('node:vm');
  const yaml = fs.readFileSync(require('node:path').join(__dirname, '../.github/workflows/tui-previews.yml'), 'utf8');
  const step = yaml.split('      - name: Download bounded preview ZIP')[1].split('      - name:')[0];
  const script = step.split('          script: |\n')[1].trimEnd().split('\n').map(line => line.slice(12)).join('\n');
  for (const length of [100, 5 * 1024 * 1024 + 1]) {
    const env = environment();
    let writes = 0;
    env.github.rest.actions.downloadArtifact = async args => {
      assert.equal(args.artifact_id, 456);
      assert.equal(args.archive_format, 'zip');
      return {data: new ArrayBuffer(length)};
    };
    env.github.paginate = async (method, args) => {
      assert.equal(args.run_id, 123);
      return [{id: 456, name: 'yame-tui-screenshots-v3', expired: false, size_in_bytes: 100}];
    };
    const requireSafe = name => name === 'node:fs' ? {writeFileSync: () => writes++} : require(name);
    const execution = vm.runInNewContext(`(async () => {${script}\n})()`, {
      ...env, require: requireSafe, Buffer, process: {env: {RUNNER_TEMP: '/tmp/test'}},
    });
    if (length > 5 * 1024 * 1024) await assert.rejects(execution, /ZIP exceeds limit/);
    else await execution;
    assert.equal(writes, Number(length === 100));
  }
});

test('actual Git rollback lease restores only the expected head and rejects a concurrent push', async () => {
  const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
  const {execFileSync, execFile} = require('node:child_process');
  const execute = require('node:util').promisify(execFile);
  const {rollbackPreview} = require('../scripts/ci/tui-previews.cjs');
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yame-lease-'));
  const remote = path.join(directory, 'remote.git'), writer = path.join(directory, 'writer');
  const previousToken = process.env.PREVIEW_PUSH_TOKEN;
  const git = args => execFileSync('git', args, {cwd: writer, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']}).trim();
  try {
    fs.mkdirSync(writer);
    git(['init', '--bare', remote]); git(['init']);
    const commit = () => {
      git(['-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '--allow-empty', '-m', 'Test']);
      return git(['rev-parse', 'HEAD']);
    };
    const source = commit(), screenshot = commit(), newer = commit();
    const ref = 'refs/heads/feature/test';
    git(['push', remote, `${screenshot}:${ref}`]);
    // Start with an unrelated shallow checkout, as the publisher does.
    const checkout = path.join(directory, 'checkout'); fs.mkdirSync(checkout);
    execFileSync('git', ['init', checkout], {stdio: 'ignore'});
    process.env.PREVIEW_PUSH_TOKEN = 'test-only-token';
    const runGit = async (command, args, options) => {
      assert.equal(command, 'git');
      assert.ok(!args.join(' ').includes('test-only-token'));
      return execute(command, args.map(arg => arg === 'https://github.com/skasti/yame.git' ? remote : arg), {...options, cwd: checkout});
    };
    const env = environment(), state = {branch: 'feature/test', sha: screenshot, parent: source};
    await rollbackPreview(env, state, runGit);
    assert.equal(git(['--git-dir', remote, 'rev-parse', ref]), source);
    git(['push', remote, `${newer}:${ref}`]);
    await assert.rejects(rollbackPreview(env, state, runGit), /rollback refused or failed/);
    assert.equal(git(['--git-dir', remote, 'rev-parse', ref]), newer);
  } finally {
    if (previousToken === undefined) delete process.env.PREVIEW_PUSH_TOKEN;
    else process.env.PREVIEW_PUSH_TOKEN = previousToken;
    fs.rmSync(directory, {recursive: true, force: true});
  }
});

test('publisher validates canonical, bounded ANSI rows and renderer metadata', () => {
  const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
  const {validateFrame, validateRendererInfo} = require('../scripts/ci/tui-previews.cjs');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'yame-ansi-'));
  const {createHash} = require('node:crypto');
  const digest = data => createHash('sha256').update(data).digest('hex');
  const pngNames = ['30x100.png', '30x100-proxy.png', '30x100-dns.png', '30x100-transfers.png',
    '45x135.png', '45x135-proxy.png', '45x135-dns.png', '45x135-transfers.png',
    '50x150.png', '50x150-proxy.png'];
  try {
    const frame = path.join(dir, '30x100.ansi.txt');
    const rows = Array(30).fill(JSON.stringify('\x1b[31mTUI\x1b[0m')).join('\n') + '\n';
    fs.writeFileSync(frame, rows);
    assert.equal(validateFrame(frame, '30x100.ansi.txt').toString(), rows);
    fs.writeFileSync(frame, rows.replace('\\u001b', '\x1b'));
    assert.throws(() => validateFrame(frame, '30x100.ansi.txt'), /Invalid ANSI/);
    fs.writeFileSync(frame, rows.slice(0, -1));
    assert.throws(() => validateFrame(frame, '30x100.ansi.txt'), /Invalid ANSI/);
    fs.writeFileSync(frame, rows + JSON.stringify('extra') + '\n');
    assert.throws(() => validateFrame(frame, '30x100.ansi.txt'), /Invalid ANSI/);

    const info = path.join(dir, 'render-info.json');
    const valid = {schema: 2, rendererSha256: 'a'.repeat(64),
      dependenciesSha256: 'b'.repeat(64), fontSha256: null,
      nodeMajor: 24, terminalOptions: {fontSize: 14},
      pngSha256: Object.fromEntries(pngNames.map(name => [name, digest(name)]))};
    fs.writeFileSync(info, JSON.stringify(valid, null, 2) + '\n');
    assert.equal(validateRendererInfo(info).toString(), JSON.stringify(valid, null, 2) + '\n');
    fs.writeFileSync(info, JSON.stringify({...valid, rendererSha256: 'invalid'}, null, 2) + '\n');
    assert.throws(() => validateRendererInfo(info), /Invalid renderer identity/);
    fs.writeFileSync(info, JSON.stringify({...valid, pngSha256: {'30x100.png': digest('missing')}}, null, 2) + '\n');
    assert.throws(() => validateRendererInfo(info), /Invalid renderer identity/);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('publisher rejects altered PNG bytes against renderer metadata before creating blobs', async () => {
  const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
  const {createHash} = require('node:crypto');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'yame-unsafe-preview-'));
  const names = ['30x100', '30x100-proxy', '30x100-dns', '30x100-transfers',
    '45x135', '45x135-proxy', '45x135-dns', '45x135-transfers',
    '50x150', '50x150-proxy'];
  const sha256 = data => createHash('sha256').update(data).digest('hex');
  try {
    const hashes = {};
    for (const name of names) {
      const [rows, columns] = name.match(/^(\d+)x(\d+)/).slice(1).map(Number);
      const png = Buffer.alloc(33);
      Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]).copy(png);
      png.write('IHDR', 12); png.writeUInt32BE(columns * 9, 16);
      png.writeUInt32BE(rows * 18, 20);
      fs.writeFileSync(path.join(dir, name + '.png'), png);
      hashes[name + '.png'] = sha256(png);
      fs.writeFileSync(path.join(dir, name + '.ansi.txt'),
        Array(rows).fill(JSON.stringify('frame')).join('\n') + '\n');
    }
    fs.writeFileSync(path.join(dir, 'render-info.json'), JSON.stringify({
      schema: 2, rendererSha256: 'a'.repeat(64), dependenciesSha256: 'b'.repeat(64),
      fontSha256: 'c'.repeat(64), nodeMajor: 24,
      terminalOptions: {scale: 1}, pngSha256: hashes,
    }, null, 2) + '\n');
    fs.writeFileSync(path.join(dir, 'source.json'),
      JSON.stringify({run: 123, pr: 71, head, base, merge: mergeSha}));
    // Keep PNG signature/size valid but tamper with the byte contents.
    const target = path.join(dir, '30x100.png');
    const bytes = fs.readFileSync(target);
    bytes[32] ^= 1;
    fs.writeFileSync(target, bytes);
    const env = environment({sourceRun: {...run, head_sha: head}});
    let blobs = 0;
    env.github.rest.git.getCommit = async ({commit_sha}) => ({data:
      commit_sha === mergeSha ? {parents: [{sha: base}, {sha: head}]} : {tree: {sha: 'old-tree'}}});
    env.github.rest.git.getTree = async () => ({data: {truncated: false, tree: []}});
    env.github.rest.git.createBlob = async () => { blobs++; throw new Error('Unexpected blob'); };
    await assert.rejects(publish(env, 71, dir), /PNG digest does not match/);
    assert.equal(blobs, 0);
  } finally { fs.rmSync(dir, {recursive: true, force: true}); }
});

test('CI uses the immutable PR base checkout for preview cache inputs', () => {
  const fs = require('node:fs'), path = require('node:path');
  const ci = fs.readFileSync(path.join(__dirname, '../.github/workflows/ci.yml'), 'utf8');
  const renderer = fs.readFileSync(path.join(__dirname, '../scripts/ci/render-tui-previews.cjs'), 'utf8');
  assert.ok(ci.includes('- name: Check out immutable screenshot baseline'));
  assert.ok(ci.includes('ref: ' + '$' + '{{ needs.preview-gate.outputs.base_sha }}'));
  assert.ok(ci.includes('path: trusted-preview-base'));
  assert.ok(ci.includes('YAME_TUI_BASELINE_DIR: ' + '$' + '{{ github.workspace }}/trusted-preview-base/assets/screenshots'));
  assert.ok(renderer.includes('process.env.YAME_TUI_BASELINE_DIR'));
});

test('preview comparison ignores renderer metadata unless PNG or ANSI bytes change', () => {
  const names = ['30x100.png', '30x100.ansi.txt', 'render-info.json'];
  const before = [Buffer.from('image'), Buffer.from('ANSI'), Buffer.from('old metadata')];
  const files = names.map((name, i) => ({name, data: before[i]}));
  // Full list is needed to mark a baseline as complete. Use the production
  // allowlist, with mock bytes for every other permitted file.
  const {filenames} = require('../scripts/ci/render-tui-previews.cjs');
  const all = [...filenames.map(name => name + '.png'), ...filenames.map(name => name + '.ansi.txt'),
    'render-info.json'];
  const prior = new Map(all.map(name => [name, gitBlobSha(files.find(x => x.name === name)?.data || Buffer.from('same'))]));
  const artifact = all.map(name => ({
    name, data: files.find(x => x.name === name)?.data || Buffer.from('same'),
  }));
  artifact.find(x => x.name === 'render-info.json').data = Buffer.from('new metadata');
  const unchanged = previewPublicationPlan(artifact, prior);
  assert.equal(unchanged.complete, true);
  assert.equal(unchanged.changed, false);
  assert.equal(unchanged.entries.find(x => x.name === 'render-info.json').sha,
    gitBlobSha(Buffer.from('old metadata')));
  assert.ok(unchanged.entries.every(x => x.data === null));

  const changedAnsi = artifact.map(x => x.name === '30x100.ansi.txt'
    ? {...x, data: Buffer.from('different ANSI')} : x);
  assert.equal(previewPublicationPlan(changedAnsi, prior).changed, true);
  const changedImage = artifact.map(x => x.name === '30x100.png'
    ? {...x, data: Buffer.from('new pixels')} : x);
  assert.equal(previewPublicationPlan(changedImage, prior).changed, true);
  const missing = new Map(prior);
  missing.delete('30x100.ansi.txt');
  assert.equal(previewPublicationPlan(artifact, missing).complete, false);
  assert.equal(previewPublicationPlan(artifact, missing).changed, true);
});

test('base preview lookup refuses truncated trees', async () => {
  const {basePreviews} = require('../scripts/ci/tui-previews.cjs');
  const github = {rest: {git: {
    getCommit: async () => ({data: {tree: {sha: 'base'}}}),
    getTree: async () => ({data: {truncated: true, tree: []}}),
  }}};
  await assert.rejects(basePreviews(github, {owner: 'skasti', repo: 'yame'}, base), /Truncated/);
});
