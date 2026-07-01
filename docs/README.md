# Documentation

Reference guides for building the Scale Testing Platform. Read these before you start coding a new area.

| Document | What it covers |
|----------|----------------|
| [MVC Structure](./mvc-structure.md) | Recommended package layout, layer responsibilities, dependency rules |
| [Design Patterns](./design-patterns.md) | Patterns to implement, in order, mapped to this project |
| [Response Validation](./response-validation.md) | Size limits, suspicious content checks, safe `TestResponse` handling |
| [Failure Policies](./failure-policies.md) | When to stop vs continue, circuit breaker, failure-rate thresholds |
| [Enterprise Scale](./enterprise-scale.md) | Loom idioms, pacing, Claim Check resolution, pinning, live metrics |

**Suggested reading order**

1. **MVC Structure** — where code lives  
2. **Design Patterns** — what to implement when  
3. **Enterprise Scale** — how the engine should behave at real throughput (read before / during Step 2)  
4. **Response Validation** & **Failure Policies** — harden the service layer  
