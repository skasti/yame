// Loaded by github-script. Publication runs only from the default branch via workflow_run.
const fs = require('node:fs');
const path = require('node:path');
const marker = '<!-- yame-tui-previews -->';
const filenames = [
  '30x100.png', '30x100-proxy.png', '30x100-dns.png', '30x100-transfers.png',
  '80x200.png', '80x200-proxy.png', '80x200-dns.png', '80x200-transfers.png',
  '100x300.png', '100x300-proxy.png',
];

function parseProof(message) {
  const line = message.split('\n').find(line => line.startsWith('YAME-Screenshots: '));
  if (!line) return null;
  try {
    const proof = JSON.parse(line.slice('YAME-Screenshots: '.length));
    return Number.isSafeInteger(proof.run) && proof.run > 0 &&
      /^[a-f0-9]{40}$/.test(proof.base) && Number.isSafeInteger(proof.pr) ? proof : null;
  } catch { return null; }
}

function screenshotOnly(commit) {
  return commit.parents.length === 1 && commit.files?.length > 0 &&
    commit.files.length <= filenames.length && commit.files.every(file =>
      ['added', 'modified'].includes(file.status) &&
      filenames.some(name => file.filename === `assets/screenshots/${name}`));
}

function successfulSource(run, pr, parent, base) {
  return typeof run.path === 'string' && run.path.split('@', 1)[0] === '.github/workflows/ci.yml' && run.event === 'pull_request' &&
    run.conclusion === 'success' && run.head_sha === parent &&
    run.pull_requests.some(p => p.number === pr && p.base.sha === base);
}

async function verifySuccessor(github, repo, number, sha, base, requireArm64 = false) {
  let reuse = false;
  const {data: commit} = await github.rest.repos.getCommit({...repo, ref: sha});
  const proof = parseProof(commit.commit.message);
  if (proof && proof.pr === number && proof.base === base && screenshotOnly(commit)) {
    const {data: run} = await github.rest.actions.getWorkflowRun({...repo, run_id: proof.run});
    if (successfulSource(run, number, commit.parents[0].sha, base)) {
      const jobs = await github.paginate(github.rest.actions.listJobsForWorkflowRun, {...repo, run_id: proof.run});
      reuse = jobs.some(job => job.name === 'build-and-test' && job.conclusion === 'success');
      if (reuse && requireArm64) {
        const runs = await github.paginate(github.rest.actions.listWorkflowRuns, {...repo,
          workflow_id: 'arm64.yml', head_sha: commit.parents[0].sha, event: 'pull_request'});
        reuse = runs.some(r => r.conclusion === 'success' && r.pull_requests.some(p =>
          p.number === number && p.base.sha === base));
      }
    }
  }
  return reuse;
}

async function gate({github, context, core}, {requireArm64 = false} = {}) {
  const repo = context.repo;
  const number = context.payload.pull_request.number;
  if (!Number.isSafeInteger(number) || number <= 0) throw new Error('Invalid PR number');
  const {data: pr} = await github.rest.pulls.get({...repo, pull_number: number});
  const expectedHead = context.payload.pull_request.head.sha;
  if (pr.state !== 'open' || pr.head.sha !== expectedHead) throw new Error('PR head changed; rerun CI');
  core.setOutput('pr_number', number);
  core.setOutput('head_sha', pr.head.sha);
  core.setOutput('base_sha', pr.base.sha);
  const mergeSha = context.sha;
  if (!mergeSha) throw new Error('Merge commit not ready; rerun CI');
  const {data: merge} = await github.rest.git.getCommit({...repo, commit_sha: mergeSha});
  if (merge.parents.length !== 2 || merge.parents[0].sha !== pr.base.sha || merge.parents[1].sha !== pr.head.sha) {
    throw new Error('Merge head/base changed; rerun CI');
  }
  core.setOutput('checkout_ref', mergeSha);
  const reuse = await verifySuccessor(github, repo, number, pr.head.sha, pr.base.sha, requireArm64);
  core.setOutput('reuse', String(reuse));
  core.info(reuse ? 'Verified screenshot-only successor of successful CI; no rebuild needed.' : 'Full build required.');
}

async function prepare({github, context, core}) {
  const run = context.payload.workflow_run;
  core.setOutput('publish', 'false');
  if (run.event !== 'pull_request' || run.conclusion !== 'success' ||
      run.head_repository.full_name !== `${context.repo.owner}/${context.repo.repo}`) return;
  // Resolve live PR data rather than trusting artifact-supplied PR numbers or refs.
  for (const candidate of run.pull_requests) {
    const {data: pr} = await github.rest.pulls.get({...context.repo, pull_number: candidate.number});
    if (pr.state !== 'open' || pr.head.sha !== run.head_sha || pr.base.sha !== candidate.base.sha ||
        pr.head.repo.full_name !== pr.base.repo.full_name || pr.head.ref === context.payload.repository.default_branch) continue;
    const artifacts = await github.paginate(github.rest.actions.listWorkflowRunArtifacts,
      {...context.repo, run_id: run.id});
    const artifact = artifacts.find(a => a.name === 'yame-tui-screenshots' && !a.expired);
    if (!artifact || artifact.size_in_bytes > 5 * 1024 * 1024) return;
    core.setOutput('publish', 'true');
    core.setOutput('pr_number', pr.number);
    return;
  }
}

function readBoundedFile(file, limit) {
  const stat = fs.lstatSync(file);
  if (!stat.isFile() || stat.size > limit) throw new Error('Expected bounded regular artifact file');
  return fs.readFileSync(file);
}

function validatePng(file, name) {
  const data = readBoundedFile(file, 1024 * 1024);
  const [rows, columns] = name.match(/^(\d+)x(\d+)/).slice(1).map(Number);
  if (data.length < 33 || data.length > 1024 * 1024 ||
      !data.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])) ||
      data.toString('ascii', 12, 16) !== 'IHDR' ||
      data.readUInt32BE(16) !== columns * 9 || data.readUInt32BE(20) !== rows * 18) {
    throw new Error(`Invalid screenshot: ${name}`);
  }
  return data;
}

async function publish({github, context, core}, number, directory) {
  const repo = context.repo;
  const run = context.payload.workflow_run;
  const {data: pr} = await github.rest.pulls.get({...repo, pull_number: Number(number)});
  const candidate = run.pull_requests.find(p => p.number === pr.number);
  if (!candidate || pr.state !== 'open' || pr.head.sha !== run.head_sha || pr.base.sha !== candidate.base.sha ||
      pr.head.repo.full_name !== `${repo.owner}/${repo.repo}` || pr.head.ref === context.payload.repository.default_branch) {
    core.info('PR moved; discarding stale previews.'); return;
  }
  const provenance = JSON.parse(readBoundedFile(path.join(directory, 'source.json'), 4096).toString('utf8'));
  if (provenance.run !== run.id || provenance.head !== run.head_sha || provenance.base !== pr.base.sha ||
      provenance.pr !== pr.number || !/^[a-f0-9]{40}$/.test(provenance.merge)) {
    throw new Error('Preview provenance does not match source CI and live PR');
  }
  const {data: testedMerge} = await github.rest.git.getCommit({...repo, commit_sha: provenance.merge});
  if (testedMerge.parents.length !== 2 || testedMerge.parents[0].sha !== provenance.base ||
      testedMerge.parents[1].sha !== run.head_sha) throw new Error('Invalid tested merge parents');
  const {data: source} = await github.rest.git.getCommit({...repo, commit_sha: run.head_sha});
  const tree = [];
  for (const name of filenames) {
    const data = validatePng(path.join(directory, name), name);
    const {data: blob} = await github.rest.git.createBlob({...repo, content: data.toString('base64'), encoding: 'base64'});
    tree.push({path: `assets/screenshots/${name}`, mode: '100644', type: 'blob', sha: blob.sha});
  }
  const {data: outputTree} = await github.rest.git.createTree({...repo, base_tree: source.tree.sha, tree});
  let sha = run.head_sha;
  if (outputTree.sha !== source.tree.sha) {
    const proof = {pr: pr.number, run: run.id, base: pr.base.sha};
    const {data: commit} = await github.rest.git.createCommit({...repo,
      message: `Update TUI screenshots\n\nYAME-Screenshots: ${JSON.stringify(proof)}`,
      tree: outputTree.sha, parents: [run.head_sha],
      author: {name: 'github-actions[bot]', email: '41898282+github-actions[bot]@users.noreply.github.com'},
    });
    // No force: a concurrent developer push makes this fail rather than overwrite code.
    await github.rest.git.updateRef({...repo, ref: `heads/${pr.head.ref}`, sha: commit.sha, force: false});
    sha = commit.sha;
    // workflow_dispatch job checks do not satisfy PR rulesets. Report the proven
    // successor as a commit status instead; no new build or write-enabled PR job.
    const {data: latest} = await github.rest.pulls.get({...repo, pull_number: pr.number});
    if (latest.head.sha !== sha || latest.base.sha !== pr.base.sha || latest.state !== 'open') {
      core.info('PR moved after publication; not reporting inherited success.'); return;
    }
    if (!await verifySuccessor(github, repo, pr.number, sha, pr.base.sha)) {
      throw new Error('Cannot prove screenshot-only successor; not reporting success');
    }
    await github.rest.repos.createCommitStatus({...repo, sha, state: 'success', context: 'build-and-test',
      description: 'Only generated PNGs changed; parent CI passed against the same base.', target_url: run.html_url});
  }
  const url = name => `https://raw.githubusercontent.com/${repo.owner}/${repo.repo}/${sha}/assets/screenshots/${name}`;
  const body = `${marker}\n### YAME terminal previews\n\n` +
    `Rendered from code commit \`${run.head_sha.slice(0, 8)}\`; [CI run](${run.html_url}). ` +
    `Fixed font/colors, production dashboard renderer, synthetic data. Sizes are rows × columns.\n\n` +
    filenames.map(name => `#### ${name.replace('.png', '')}\n\n![${name}](${url(name)})`).join('\n\n');
  const comments = await github.paginate(github.rest.issues.listComments, {...repo, issue_number: pr.number});
  const existing = comments.find(c => ['github-actions[bot]', 'chatgpt-codex-connector[bot]'].includes(c.user.login) && c.body.startsWith(marker));
  if (existing) await github.rest.issues.updateComment({...repo, comment_id: existing.id, body});
  else await github.rest.issues.createComment({...repo, issue_number: pr.number, body});
}

module.exports = {gate, prepare, publish, parseProof, screenshotOnly, successfulSource, validatePng, verifySuccessor};
