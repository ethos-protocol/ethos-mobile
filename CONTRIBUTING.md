# Contributing to Ethos Mobile

Thanks for helping improve Ethos Mobile! Please follow these guidelines so your contribution can be reviewed and merged quickly.

## Getting Started

1. Fork the repository and clone your fork.
2. Install dependencies: `[npm install / yarn / flutter pub get]`
3. Create a branch from `main`:
```bash
   git checkout -b feat/short-description
```
   Branch prefixes: `feat/`, `fix/`, `docs/`, `refactor/`, `test/`, `chore/`.

## Code Style Guidelines

- Follow the existing project structure and naming conventions.
- Use `[ESLint + Prettier / dart format]`. Run `[npm run lint / flutter analyze]` before committing.
- Use `[TypeScript / Dart]` types; avoid `any` / `dynamic` where possible.
- Components, classes: `PascalCase`. Variables, functions: `camelCase`. Files: `[convention]`.
- Keep functions small and single-purpose; comment the "why," not the "what."
- Do not commit secrets, API keys, or private keys. Use environment variables.
- Remove unused code, `console.log` / `print` statements, and commented-out blocks.

## Commit Message Conventions

We use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<optional scope>): <short summary>
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`.

Examples:
- `feat(wallet): add biometric unlock`
- `fix(auth): handle expired session token`
- `docs: update setup instructions`

Rules: use the imperative mood ("add," not "added"), keep the summary under 72 characters, and reference issues in the body (e.g., `Closes #123`).

## Pull Request Process

1. Make sure your branch is up to date with `main`.
2. Run linting and tests locally; both must pass.
3. Open a PR against `main` and fill in the PR template.
4. Link the related issue using `Closes #<issue-number>`.
5. Keep PRs focused: one feature or fix per PR.
6. Include screenshots or screen recordings for UI changes.
7. Respond to review feedback promptly. Maintainers merge once at least [1] approval is received and checks pass.

## Testing Requirements

- All new features and bug fixes must include tests.
- Run the full test suite before pushing: `[npm test / flutter test]`
- Do not decrease existing test coverage.
- For UI changes, test on both iOS and Android (emulator or device).
- Describe how you tested in your PR description.

## Code Review Checklist

**For authors (before requesting review):**
- [ ] Code builds and runs without errors or warnings
- [ ] Lint and tests pass locally
- [ ] New tests added for new behavior
- [ ] No secrets, debug logs, or dead code
- [ ] Documentation updated if needed
- [ ] Commit messages follow conventions

**For reviewers:**
- [ ] Changes match the issue's scope and intent
- [ ] Code is readable and consistent with project style
- [ ] Edge cases and error handling are covered
- [ ] No security or performance concerns
- [ ] Tests are meaningful and sufficient
- [ ] UI changes verified (screenshots or run locally)

## Reporting Issues

Search existing issues first. When opening a new one, include steps to reproduce, expected vs. actual behavior, and device/OS details.

## Code of Conduct

Be respectful and constructive. Harassment or discrimination of any kind is not tolerated.