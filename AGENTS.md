# AGENTS.md

## Project

DropFlow is a local-first file transfer application for macOS and Android.

The project prioritizes:

- Simplicity
- Reliability
- Maintainability
- Small, focused changes

Always prefer simple and predictable solutions over clever or complex ones.

---

## Workflow

Before starting any task:

1. Read only the documentation relevant to the current task.
2. Create a new feature branch.
3. Explain the implementation plan.
4. Wait for approval before making changes.

Each branch must implement exactly one feature.

Never combine multiple unrelated features into one branch.

---

## Branch Naming

Use:

feature/<feature-name>

Examples:

feature/desktop-scaffold

feature/device-discovery

feature/file-transfer

---

## During Planning

Allowed:

- Read project files
- Inspect repository structure
- Run read-only Git commands (`git status`, `git log`, `git diff`)
- Ask clarification questions

Do NOT:

- Create files
- Modify files
- Install packages
- Scaffold projects
- Switch branches
- Commit changes

Implementation begins only after explicit approval.

---

## During Implementation

Only modify files required for the assigned task.

Never:

- Refactor unrelated code
- Change project architecture
- Rename project structure
- Modify unrelated documentation

Follow PROJECT_STRUCTURE.md.

---

## Documentation

Update documentation only if the task changes project behavior or architecture.

---

## Commands

Package Manager:

pnpm

Desktop Development:

pnpm tauri dev

Desktop Build:

pnpm tauri build

Type Check:

pnpm tsc --noEmit

---

## Completion Checklist

A task is complete only if:

- Project builds successfully
- No syntax errors
- No unrelated files changed
- Documentation updated if required
- Ready to merge into main