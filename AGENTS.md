# Repository workflow

For changes in this repository, follow this standard procedure:

1. Create a branch.
2. Implement the change.
3. Dispatch an independent review to an agent.
4. Fix any bugs found in review.
5. Push the branch and create a pull request.
6. Wait until CI is green.
7. Merge the pull request.
8. Observe the deployment pipeline.

# Kotlin style

- Avoid early returns; prefer expression-based control flow.
- Use functional programming techniques where they make the code clear.
- Guard conditions are fine with early returns
