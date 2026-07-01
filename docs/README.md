# Documentation

Reference guides for building the Scale Testing Platform. Read these before you start coding a new area.

| Document | What it covers |
|----------|----------------|
| [MVC Structure](./mvc-structure.md) | Recommended package layout, layer responsibilities, dependency rules |
| [Design Patterns](./design-patterns.md) | Patterns to implement, in order, mapped to this project |
| [Response Validation](./response-validation.md) | Size limits, suspicious content checks, safe `TestResponse` handling |
| [Failure Policies](./failure-policies.md) | When to stop vs continue, circuit breaker, failure-rate thresholds |

Start with **MVC Structure**, then pick patterns from **Design Patterns** as you need them. Read **Response Validation** and **Failure Policies** when you implement the service layer (Step 2).
