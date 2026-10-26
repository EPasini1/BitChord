const { spawnSync } = require('node:child_process');
const { appendFileSync } = require('node:fs');

const UPSTREAM_URL = 'https://github.com/kushagrasinghx/BitChord.git';
const SYNC_BRANCH = 'automation/sync-upstream-main';
const CO_AUTHOR = 'Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>';

function runGit(cwd, args) {
  const result = spawnSync('git', args, {
    cwd,
    encoding: 'utf8',
    windowsHide: true,
    env: { ...process.env, GIT_TERMINAL_PROMPT: '0' },
  });
  if (result.error) throw result.error;
  return result;
}

function git(cwd, ...args) {
  const result = runGit(cwd, args);
  if (result.status !== 0) {
    throw new Error(`git ${args.join(' ')} failed: ${result.stderr.trim() || result.stdout.trim()}`);
  }
  return result.stdout.trim();
}

function hasRef(cwd, ref) {
  const result = runGit(cwd, ['show-ref', '--verify', '--quiet', ref]);
  if (result.status === 0) return true;
  if (result.status === 1) return false;
  throw new Error(`Could not inspect Git reference ${ref}: ${result.stderr.trim()}`);
}

function merge(cwd, ref) {
  const result = runGit(cwd, [
    '-c', 'user.name=github-actions[bot]',
    '-c', 'user.email=41898282+github-actions[bot]@users.noreply.github.com',
    '-c', 'commit.gpgSign=false',
    'merge', '--no-ff', '-m', `Merge ${ref} into the customized fork\n\n${CO_AUTHOR}`, ref,
  ]);
  if (result.status !== 0) {
    const conflicts = git(cwd, 'diff', '--name-only', '--diff-filter=U');
    throw new Error(
      conflicts
        ? `Synchronization stopped: resolve these conflicts manually before retrying:\n${conflicts}`
        : `Could not merge ${ref}: ${result.stderr.trim() || result.stdout.trim()}`,
    );
  }
}

function syncUpstream({ cwd = process.cwd(), upstreamUrl = UPSTREAM_URL, apply = false, push = false } = {}) {
  if (push && !apply) throw new Error('--push requires --apply.');
  if (git(cwd, 'status', '--porcelain')) {
    throw new Error('Save or commit local changes before syncing. No files or branches have been changed.');
  }
  const remotes = git(cwd, 'remote').split(/\r?\n/);
  if (!remotes.includes('origin')) throw new Error('The fork must be configured as the origin remote.');
  if (remotes.includes('upstream')) {
    const configured = git(cwd, 'remote', 'get-url', 'upstream');
    if (configured.replace(/\.git$/, '') !== upstreamUrl.replace(/\.git$/, '')) {
      throw new Error('The existing upstream remote points elsewhere. It has not been overwritten.');
    }
  } else {
    git(cwd, 'remote', 'add', 'upstream', upstreamUrl);
  }
  git(cwd, 'fetch', '--no-tags', 'origin', 'refs/heads/main:refs/remotes/origin/main');
  git(cwd, 'fetch', '--no-tags', 'upstream', 'refs/heads/main:refs/remotes/upstream/main');
  if (git(cwd, 'rev-parse', 'HEAD') !== git(cwd, 'rev-parse', 'origin/main')) {
    throw new Error(
      'Run from the published fork main. Local commits or an outdated checkout must be integrated first; nothing was merged or pushed.',
    );
  }
  const upstreamSha = git(cwd, 'rev-parse', 'upstream/main');
  const behind = Number(git(cwd, 'rev-list', '--count', 'origin/main..upstream/main'));
  const ancestor = runGit(cwd, ['merge-base', '--is-ancestor', 'upstream/main', 'origin/main']);
  if (ancestor.status === 0) return { state: 'up-to-date', updated: false, upstreamSha };
  if (ancestor.status !== 1) throw new Error(`Could not compare histories: ${ancestor.stderr.trim()}`);
  if (!apply) return { state: 'available', updated: false, upstreamSha, commitsBehind: behind };

  const originalHead = git(cwd, 'rev-parse', 'HEAD');
  const originalBranch = git(cwd, 'branch', '--show-current');
  const remoteBranch = runGit(cwd, ['ls-remote', '--exit-code', '--heads', 'origin', `refs/heads/${SYNC_BRANCH}`]);
  const remoteExists = remoteBranch.status === 0;
  if (!remoteExists && remoteBranch.status !== 2) {
    throw new Error(`Could not inspect the synchronization branch: ${remoteBranch.stderr.trim()}`);
  }
  if (remoteExists) {
    git(cwd, 'fetch', '--no-tags', 'origin', `refs/heads/${SYNC_BRANCH}:refs/remotes/origin/${SYNC_BRANCH}`);
  }
  try {
    if (hasRef(cwd, `refs/heads/${SYNC_BRANCH}`)) {
      git(cwd, 'switch', SYNC_BRANCH);
      if (remoteExists) merge(cwd, `origin/${SYNC_BRANCH}`);
    } else {
      git(cwd, 'switch', '--create', SYNC_BRANCH, remoteExists ? `origin/${SYNC_BRANCH}` : 'origin/main');
    }
    merge(cwd, 'origin/main');
    merge(cwd, 'upstream/main');
    const headSha = git(cwd, 'rev-parse', 'HEAD');
    if (push) git(cwd, 'push', 'origin', `HEAD:refs/heads/${SYNC_BRANCH}`);
    return { state: 'prepared', updated: true, branch: SYNC_BRANCH, headSha, upstreamSha, commitsBehind: behind };
  } finally {
    const merging = runGit(cwd, ['rev-parse', '--verify', '--quiet', 'MERGE_HEAD']);
    if (merging.status === 0) git(cwd, 'merge', '--abort');
    else if (merging.status !== 1) throw new Error(`Could not inspect merge state: ${merging.stderr.trim()}`);
    if (originalBranch) git(cwd, 'switch', originalBranch);
    else git(cwd, 'switch', '--detach', originalHead);
  }
}

if (require.main === module) {
  const flags = process.argv.slice(2);
  for (const flag of flags) {
    if (!['--check', '--apply', '--push'].includes(flag)) throw new Error(`Unknown option: ${flag}`);
  }
  if (flags.includes('--check') && flags.includes('--apply')) {
    throw new Error('Choose either --check or --apply.');
  }
  const result = syncUpstream({ apply: flags.includes('--apply'), push: flags.includes('--push') });
  console.log(JSON.stringify(result, null, 2));
  if (process.env.GITHUB_OUTPUT) {
    appendFileSync(process.env.GITHUB_OUTPUT, [
      `updated=${result.updated}`,
      `branch=${result.branch || ''}`,
      `head_sha=${result.headSha || ''}`,
      `upstream_sha=${result.upstreamSha}`,
      '',
    ].join('\n'));
  }
}

module.exports = { syncUpstream, SYNC_BRANCH };
