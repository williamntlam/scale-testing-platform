# Documentation

Reference guides for the Scale Testing Platform. Docs distinguish **what is implemented** from **what is planned**.

| Document | What it covers |
|----------|----------------|
| [MVC Structure](./mvc-structure.md) | Actual package layout (`services/`), layer responsibilities, live API |
| [Design Patterns](./design-patterns.md) | Patterns mapped to this project — done vs remaining |
| [Response Validation](./response-validation.md) | Size limits (done) and suspicious-content checks (planned) |
| [Failure Policies](./failure-policies.md) | When to stop vs continue — currently always run-to-completion |
| [Enterprise Scale](./enterprise-scale.md) | Loom idioms, pacing, Claim Check, pinning, live metrics |

**Suggested reading order**

1. **MVC Structure** — where the code lives today  
2. **Design Patterns** — what is done vs what comes next  
3. **Enterprise Scale** — how the engine should behave at real throughput  
4. **Response Validation** & **Failure Policies** — hardening remaining service-layer edges  
