# 5. Scoring Rubric and Reporting

Every row in [file 3](03-functional-coverage-matrix.md) and every scenario in
[file 4](04-edge-cases-and-adversarial-tests.md) needs a result recorded using the vocabulary below —
not a free-text guess at how it went. Consistent vocabulary is what makes 200+ individual test rows
into a single coherent report instead of 200 slightly-differently-worded paragraphs.

## 5.1 Result vocabulary

| Result | Meaning | When to use it |
|---|---|---|
| **PASS** | Observed behavior matched the documented/expected behavior exactly. | You ran it, against the real cluster, and it did what the docs said. |
| **FAIL** | Observed behavior contradicted the documented/expected behavior. | The docs (or a prior fix's changelog claim) were wrong, or a regression exists. Always attach a severity (5.2) and a minimal reproduction. |
| **PARTIAL** | The core behavior worked, but some documented detail didn't (wrong message text, wrong log level, correct outcome via a different mechanism than documented, etc.). | Don't round a partial success up to PASS or down to FAIL — record exactly what diverged. |
| **BLOCKED** | Could not be executed at all — infrastructure problem, missing prerequisite, or a prior step's failure made this one impossible to reach. | Always name the blocker (e.g. "blocked by P10 install failure", "blocked — no two-DC cluster built, see file 1 §1.2.3"). A blocked test is not evidence of anything about Kandra — don't imply it is. |
| **N/A** | Genuinely not applicable given a documented, deliberate scope decision (e.g. skipping the optional two-DC cluster and explicitly marking every two-DC-only row N/A rather than BLOCKED, if that was a conscious, stated decision up front rather than something that just didn't get done). | Use sparingly — most rows in this plan are applicable if you built the prerequisites in file 1. If you're tempted to mark something N/A, double check it isn't actually BLOCKED. |

## 5.2 Severity (for FAIL and PARTIAL results only)

| Severity | Definition | Examples from this plan |
|---|---|---|
| **Critical** | Silent data loss, silent data corruption, or a security/auth bypass. | Concurrent `update()` without `@Version` silently losing writes is *documented*, not a Critical finding — but concurrent `update()` **with** `@Version` allowing more than one writer through (4.4) would be Critical. Two migration runners both executing a non-idempotent `up()` (4.5) despite the claimed lock would be Critical. |
| **High** | Incorrect behavior with no data loss, but a real functional break a consumer would hit in normal usage — wrong exception type breaking `catch` blocks, a documented fix not actually working, a feature throwing when it shouldn't. | `findActive()` not actually excluding a soft-deleted row within the marker-column window (4.3) would be High. `deleteById` still not respecting `@SoftDelete` despite ISS-015 claiming a fix (3.5 D4) would be High. |
| **Medium** | Behavior technically works but diverges from documentation in a way that would confuse or mislead a consumer, without breaking correctness outright. | Wrong log level, a config knob genuinely inert when docs imply otherwise beyond what's already documented, an error message that's misleading but not wrong. |
| **Low** | Cosmetic, or a known/accepted limitation working exactly as documented (not a real "finding" so much as a confirmation). | `PoolConfig.localRequestsPerConnection` confirmed inert (P7) — expected, not a discovery, but still worth recording as confirmed rather than assumed. |

## 5.3 What a finding write-up must contain

Every FAIL and PARTIAL, no exceptions:

```markdown
### [Row ID] Short title

**Severity:** Critical / High / Medium / Low
**Expected (per docs):** <quote or close paraphrase of the documented claim>
**Observed:** <what actually happened>
**Repro:**
- Entity/config code (or a link to the exact file:line in the sample app)
- Exact CQL generated (from `debug.logQueries`), if relevant
- Exact exception type + message + stack trace, if one occurred
- Cluster state at the time (node count up, RF, consistency level in effect)
**Cluster/environment:** ScyllaDB version, node count, single/multi-DC, auth on/off
```

A finding without a repro is not a finding — it's an anecdote. If you can't reproduce it a second
time, say so explicitly ("observed once, could not reproduce on retry") rather than omitting the
attempt.

## 5.4 Aggregate summary (produce this once, at the end)

```markdown
## Kandra 0.4.2 Real-World Test Plan — Summary

**Environment:** <cluster topology, auth on/off, dates run>
**Coverage:** <N/total rows from file 3> functional rows scored, <M/total scenarios from file 4> edge cases attempted.

| Result | Count |
|---|---|
| PASS | |
| FAIL | |
| PARTIAL | |
| BLOCKED | |
| N/A | |

### Findings by severity
| Severity | Count | Row IDs |
|---|---|---|
| Critical | | |
| High | | |
| Medium | | |
| Low | | |

### Confirmed-fixed (0.4.1/0.4.2 changes that held up under real testing)
- List each `docs/issues/ISS-0NN` whose corresponding rows all PASSed.

### Regressed or unconfirmed (0.4.1/0.4.2 changes that did NOT hold up)
- List each `docs/issues/ISS-0NN` with any FAIL/PARTIAL among its rows, with the specific row IDs.

### Blocking findings (stopped further testing entirely)
- e.g. artifact not resolvable from Maven Central (file 1 §1.3), cluster wouldn't converge, etc.

### Top 5 findings by severity, one line each
1. ...
```

## 5.5 What NOT to do while scoring

- **Don't "fix" Kandra while testing it.** If you notice a real bug, record it as a finding per 5.3.
  Do not patch the published library, do not vendor a local workaround into the sample app and call
  the row PASS because your workaround made it behave — that would score the workaround, not Kandra.
  The one exception: if a scenario is explicitly framed in file 4 as "confirm you can reproduce the
  documented broken behavior" (several are, deliberately, to prove measurement methodology), a clean
  reproduction of the *documented* broken behavior is itself the correct PASS outcome for that
  specific meta-check — read each scenario's "expected" wording carefully before deciding.
- **Don't upgrade a PARTIAL to PASS because the difference "doesn't matter."** Let severity (5.2)
  carry that judgment explicitly (a Low-severity PARTIAL is a fully honest report; a silently-rounded
  PASS is not).
- **Don't mark something N/A to avoid doing the work.** N/A is for genuine, stated scope decisions
  made *before* attempting the row, not a way to skip something hard after the fact. If you skipped
  the two-DC cluster because of time, that's BLOCKED with a reason, not N/A.
- **Don't average or discard BLOCKED rows out of the coverage percentage silently.** Report them as
  their own bucket (5.4) — a report that only shows "95% pass rate" while quietly excluding 40%
  BLOCKED rows from the denominator is misleading.

## 5.6 Definition of a complete run

Per the root [README](README.md#definition-of-100-real-functionality-for-this-plan): every row in
file 3 and every scenario in file 4 has a recorded result (including BLOCKED/N/A, which still count
as "attempted and recorded"), and the aggregate summary (5.4) has been produced. A run that stops
partway with unscored rows remaining is incomplete — say so explicitly rather than presenting a
partial run as final.
