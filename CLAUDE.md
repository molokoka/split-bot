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
