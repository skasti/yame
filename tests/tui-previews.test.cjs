const {test} = require('node:test');
const assert = require('node:assert/strict');
const {parseProof, screenshotOnly, successfulSource, gate, prepare, publish} = require('../scripts/ci/tui-previews.cjs');
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
  }, paginate: async method => method === 'jobs' ? jobs : [{name: 'yame-tui-screenshots', expired: false, size_in_bytes: 1000}]};
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
test('only exact generated PNG paths and single parent are accepted', () => {
  assert.equal(screenshotOnly(commit), true);
  for (const files of [[], [{filename: 'src/main.kt', status: 'modified'}],
    [{filename: 'assets/screenshots/30x100.png', status: 'renamed'}],
    [{filename: 'assets/screenshots/arbitrary.sh', status: 'added'}]]) {
    assert.equal(screenshotOnly({...commit, files}), false);
  }
  assert.equal(screenshotOnly({...commit, parents: [{sha: parent}, {sha: base}]}), false);
});
test('source requires successful PR CI on exact parent and base', () => {
  assert.equal(successfulSource(run, 71, parent, base), true);
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
    assert.throws(() => validatePng(file, '80x200.png'), /Invalid screenshot/);
  } finally { fs.rmSync(directory, {recursive: true, force: true}); }
});

test('publication writes only PNGs and reports inherited status after verifying successor', async () => {
  const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yame-publish-'));
  const sourceRun = {...run, head_sha: head, html_url: 'https://github.com/skasti/yame/actions/runs/123'};
  const env = environment({sourceRun});
  const successor = 'e'.repeat(40);
  const calls = [];
  let moved = false;
  try {
    const names = ['30x100', '30x100-proxy', '30x100-dns', '30x100-transfers',
      '80x200', '80x200-proxy', '80x200-dns', '80x200-transfers', '100x300', '100x300-proxy'];
    for (const name of names) {
      const [rows, columns] = name.match(/^(\d+)x(\d+)/).slice(1).map(Number);
      const png = Buffer.alloc(33); Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]).copy(png);
      png.write('IHDR', 12); png.writeUInt32BE(columns * 9, 16); png.writeUInt32BE(rows * 18, 20);
      fs.writeFileSync(path.join(directory, `${name}.png`), png);
    }
    fs.writeFileSync(path.join(directory, 'source.json'), JSON.stringify({run: 123, pr: 71, head, base, merge: mergeSha}));
    env.github.rest.pulls.get = async () => ({data: moved ? {...pr, head: {...pr.head, sha: successor}} : pr});
    env.github.rest.git = {
      getCommit: async ({commit_sha}) => ({data: commit_sha === mergeSha
        ? {parents: [{sha: base}, {sha: head}]} : {tree: {sha: 'old-tree'}}}),
      createBlob: async () => ({data: {sha: 'blob'}}),
      createTree: async args => { calls.push(['tree', args]); return {data: {sha: 'new-tree'}}; },
      createCommit: async args => { calls.push(['commit', args]); return {data: {sha: successor}}; },
      updateRef: async args => { calls.push(['push', args]); moved = true; },
    };
    env.github.rest.repos.getCommit = async () => ({data: {...commit, parents: [{sha: head}]}});
    env.github.rest.repos.createCommitStatus = async args => calls.push(['status', args]);
    env.github.rest.issues = {listComments: 'comments', createComment: async args => calls.push(['comment', args])};
    const paginate = env.github.paginate;
    env.github.paginate = async (method, args) => method === 'comments' ? [] : paginate(method, args);
    await publish(env, 71, directory);
    assert.deepEqual(calls.map(c => c[0]), ['tree', 'commit', 'push', 'status', 'comment']);
    assert.equal(calls[0][1].tree.length, 10);
    assert.ok(calls[0][1].tree.every(t => t.path.startsWith('assets/screenshots/') && t.path.endsWith('.png')));
    assert.deepEqual(calls[1][1].parents, [head]);
    assert.equal(calls[2][1].force, false);
    assert.equal(calls[3][1].sha, successor);
    assert.equal(calls[3][1].context, 'build-and-test');
    assert.equal(calls[3][1].state, 'success');
    assert.ok(calls[4][1].body.includes(`/${successor}/assets/screenshots/30x100.png`));
    assert.equal((calls[4][1].body.match(/!\[/g) || []).length, 10);
    assert.ok(!calls[4][1].body.includes('<details>'));
  } finally { fs.rmSync(directory, {recursive: true, force: true}); }
});
