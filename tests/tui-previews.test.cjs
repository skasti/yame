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
test('verified screenshot successor skips heavy build on PR and dispatch', async () => {
  for (const event of ['pull_request', 'workflow_dispatch']) {
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
