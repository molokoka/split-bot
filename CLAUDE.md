# Worktree structure

This repo uses a bare-repo hub layout:

```
split/
├── .bare/    # bare repo — all history/branches/refs, no working files
├── .git       # pointer file: "gitdir: ./.bare"
├── main/      # worktree for main branch — actual working copy
└── <name>/    # other worktrees, siblings of main/
```

Create new worktrees as siblings at this level (`git worktree add ../<name> <branch>` from inside `main/`), not nested inside another worktree.

Note: Claude Code's `EnterWorktree` tool does not yet support this layout — it creates `.claude/worktrees/<name>/` nested inside whichever worktree it's invoked from (e.g. `split/main/.claude/worktrees/<name>/`) rather than a hub-level sibling. This is a known gap, not a misconfiguration.

## Reusing an agent's worktree

Worktrees created by an agent via `EnterWorktree` aren't agent-exclusive — when you want to work on the same branch, reuse that worktree instead of creating a new one or running `git checkout <branch>` elsewhere. Git refuses the latter: a branch can only be checked out in one worktree at a time. Just `cd` into the existing worktree path (or point your editor/GUI tool at it) and work there directly.

Current example: `/Users/swietique/workspace/split/.bare/.claude/worktrees/split-exact-amounts` is the worktree for branch `worktree-split-exact-amounts` (the exact-amount `/split` feature). An agent created and had been using it; keep working there for that branch rather than spinning up a duplicate.
