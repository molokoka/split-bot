# Worktree structure

This repo uses a bare-repo hub layout:

```
split/
├── .bare/    # bare repo — all history/branches/refs, no working files
├── .git       # pointer file: "gitdir: ./.bare"
├── main/      # worktree for main branch — actual working copy
└── <name>/    # other worktrees, siblings of main/
```

Base directory: `~/workspace/split`. Create every worktree as a direct child of it, a sibling of `main/`:

    git worktree add ~/workspace/split/<name> <branch>

Never nest a worktree inside another worktree.

Note: Claude Code's `EnterWorktree` tool does not yet support this layout — it creates `.claude/worktrees/<name>/` nested inside whichever worktree it's invoked from, rather than a hub-level sibling. This is a known gap, not a misconfiguration; use the command above directly instead of relying on `EnterWorktree` when this layout matters.
