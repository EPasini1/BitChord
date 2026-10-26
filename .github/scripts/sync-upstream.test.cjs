const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const { test } = require('node:test');
const { syncUpstream, SYNC_BRANCH } = require('./sync-upstream.cjs');

function git(cwd, ...args) {
  return execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

function bareGit(repo, ...args) {
  return git(repo, '--git-dir', repo, ...args);
}

function identity(repo) {
  git(repo, 'config', 'user.name', 'Sync test');
  git(repo, 'config', 'user.email', 'sync-test@example.invalid');
  git(repo, 'config', 'commit.gpgSign', 'false');
  git(repo, 'config', 'core.autocrlf', 'false');
}

function commit(repo, file, content) {
  writeFileSync(join(repo, file), content);
  git(repo, 'add', file);
  git(repo, 'commit', '-m', `Update ${file}`, '-m',
    'Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>');
}

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'bitchord-sync-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const upstream = join(root, 'upstream');
  const origin = join(root, 'fork.git');
  const work = join(root, 'work');
  mkdirSync(upstream);
  git(upstream, 'init', '--initial-branch=main');
  identity(upstream);
  commit(upstream, 'player.txt', 'Original player\n');
  git(root, 'clone', '--bare', upstream, origin);
  git(root, 'clone', '--config', 'core.autocrlf=false', origin, work);
  identity(work);
  commit(work, 'custom-ui.txt', 'BitChord red desktop UI\n');
  git(work, 'push', 'origin', 'main');
  assert.equal(git(work, 'status', '--porcelain'), '');
  const main = git(work, 'rev-parse', 'main');
  const options = { cwd: work, upstreamUrl: upstream, apply: true, push: true };
  return { root, upstream, origin, work, main, options };
}

test('an up-to-date fork does not create a synchronization branch', (t) => {
  const f = fixture(t);
  const result = syncUpstream(f.options);
  assert.equal(result.state, 'up-to-date');
  assert.equal(result.updated, false);
  assert.equal(git(f.work, 'branch', '--list', SYNC_BRANCH), '');
  assert.equal(git(f.work, 'rev-parse', 'main'), f.main);
});

test('merges upstream history while retaining the custom UI and leaving main untouched', (t) => {
  const f = fixture(t);
  commit(f.upstream, 'player.txt', 'Upstream playback fix\n');
  const result = syncUpstream(f.options);
  assert.equal(result.state, 'prepared');
  assert.equal(git(f.work, 'show', `${SYNC_BRANCH}:custom-ui.txt`), 'BitChord red desktop UI');
  assert.equal(git(f.work, 'show', `${SYNC_BRANCH}:player.txt`), 'Upstream playback fix');
  git(f.work, 'merge-base', '--is-ancestor', result.upstreamSha, result.headSha);
  assert.equal(bareGit(f.origin, 'rev-parse', SYNC_BRANCH), result.headSha);
  assert.equal(bareGit(f.origin, 'rev-parse', 'main'), f.main);
  assert.equal(git(f.work, 'branch', '--show-current'), 'main');
  assert.equal(git(f.work, 'status', '--porcelain'), '');
});

test('checking for updates never switches branches or pushes changes', (t) => {
  const f = fixture(t);
  commit(f.upstream, 'player.txt', 'Upstream playback fix\n');
  const result = syncUpstream({ ...f.options, apply: false, push: false });
  assert.equal(result.state, 'available');
  assert.equal(result.commitsBehind, 1);
  assert.equal(git(f.work, 'branch', '--list', SYNC_BRANCH), '');
  assert.equal(git(f.work, 'branch', '--show-current'), 'main');
  assert.equal(bareGit(f.origin, 'rev-parse', 'main'), f.main);
});

test('conflicts are reported and aborted without changing either main or publishing a branch', (t) => {
  const f = fixture(t);
  commit(f.work, 'player.txt', 'Custom player controls\n');
  git(f.work, 'push', 'origin', 'main');
  const customHead = git(f.work, 'rev-parse', 'HEAD');
  commit(f.upstream, 'player.txt', 'Upstream player redesign\n');
  assert.throws(() => syncUpstream(f.options), /resolve these conflicts manually[\s\S]*player\.txt/);
  assert.equal(git(f.work, 'branch', '--show-current'), 'main');
  assert.equal(git(f.work, 'rev-parse', 'main'), customHead);
  assert.equal(bareGit(f.origin, 'rev-parse', 'main'), customHead);
  assert.equal(git(f.work, 'status', '--porcelain'), '');
  assert.equal(readFileSync(join(f.work, 'player.txt'), 'utf8'), 'Custom player controls\n');
  assert.equal(bareGit(f.origin, 'branch', '--list', SYNC_BRANCH), '');
});

test('tracked and untracked local changes are never overwritten', (t) => {
  const f = fixture(t);
  writeFileSync(join(f.work, 'custom-ui.txt'), 'Unsaved custom UI\n');
  writeFileSync(join(f.work, 'new-ui.txt'), 'New local screen\n');
  assert.throws(() => syncUpstream(f.options), /Save or commit local changes/);
  assert.equal(readFileSync(join(f.work, 'custom-ui.txt'), 'utf8'), 'Unsaved custom UI\n');
  assert.equal(readFileSync(join(f.work, 'new-ui.txt'), 'utf8'), 'New local screen\n');
  assert.equal(git(f.work, 'remote'), 'origin');
});

test('successive syncs incorporate newer fork and upstream commits without force-pushing', (t) => {
  const f = fixture(t);
  commit(f.upstream, 'player.txt', 'First upstream fix\n');
  const first = syncUpstream(f.options);
  commit(f.work, 'custom-ui.txt', 'Improved red desktop UI\n');
  git(f.work, 'push', 'origin', 'main');
  commit(f.upstream, 'player.txt', 'Second upstream fix\n');
  const second = syncUpstream(f.options);
  bareGit(f.origin, 'merge-base', '--is-ancestor', first.headSha, second.headSha);
  assert.equal(git(f.work, 'show', `${SYNC_BRANCH}:custom-ui.txt`), 'Improved red desktop UI');
  assert.equal(git(f.work, 'show', `${SYNC_BRANCH}:player.txt`), 'Second upstream fix');
  assert.equal(git(f.work, 'branch', '--show-current'), 'main');
});

test('a fresh runner can update the existing remote synchronization branch', (t) => {
  const f = fixture(t);
  commit(f.upstream, 'player.txt', 'First upstream fix\n');
  const first = syncUpstream(f.options);
  const runner = join(f.root, 'second-runner');
  git(f.root, 'clone', '--config', 'core.autocrlf=false', f.origin, runner);
  identity(runner);
  commit(f.upstream, 'player.txt', 'Second upstream fix\n');
  const second = syncUpstream({ ...f.options, cwd: runner });
  bareGit(f.origin, 'merge-base', '--is-ancestor', first.headSha, second.headSha);
  assert.equal(git(runner, 'branch', '--show-current'), 'main');
});

test('after the PR is merged, another run reports up-to-date', (t) => {
  const f = fixture(t);
  commit(f.upstream, 'player.txt', 'Upstream fix\n');
  syncUpstream(f.options);
  git(f.work, 'merge', '--ff-only', SYNC_BRANCH);
  git(f.work, 'push', 'origin', 'main');
  assert.equal(syncUpstream(f.options).state, 'up-to-date');
});

test('an unexpected upstream remote is not silently replaced', (t) => {
  const f = fixture(t);
  git(f.work, 'remote', 'add', 'upstream', f.origin);
  assert.throws(() => syncUpstream(f.options), /upstream remote points elsewhere/);
  assert.equal(git(f.work, 'remote', 'get-url', 'upstream'), f.origin);
});

test('unpublished custom commits are not omitted from a synchronization PR', (t) => {
  const f = fixture(t);
  commit(f.work, 'custom-ui.txt', 'Unpublished custom UI\n');
  commit(f.upstream, 'player.txt', 'New upstream player\n');
  const localHead = git(f.work, 'rev-parse', 'HEAD');
  assert.throws(() => syncUpstream(f.options), /Run from the published fork main/);
  assert.equal(git(f.work, 'rev-parse', 'HEAD'), localHead);
  assert.equal(bareGit(f.origin, 'rev-parse', 'main'), f.main);
  assert.equal(git(f.work, 'branch', '--list', SYNC_BRANCH), '');
});

test('detached checkouts are restored after preparing the synchronization branch', (t) => {
  const f = fixture(t);
  git(f.work, 'switch', '--detach');
  commit(f.upstream, 'player.txt', 'New upstream player\n');
  assert.equal(syncUpstream(f.options).state, 'prepared');
  assert.equal(git(f.work, 'branch', '--show-current'), '');
  assert.equal(git(f.work, 'rev-parse', 'HEAD'), f.main);
});

test('network or remote failures are errors rather than successful no-update results', (t) => {
  const f = fixture(t);
  assert.throws(
    () => syncUpstream({ ...f.options, upstreamUrl: join(f.root, 'missing-repository') }),
    /fetch[\s\S]*failed/,
  );
  assert.equal(git(f.work, 'branch', '--show-current'), 'main');
  assert.equal(git(f.work, 'status', '--porcelain'), '');
});

test('publishing requires explicit apply mode', (t) => {
  const f = fixture(t);
  assert.throws(() => syncUpstream({ ...f.options, apply: false }), /--push requires --apply/);
});
