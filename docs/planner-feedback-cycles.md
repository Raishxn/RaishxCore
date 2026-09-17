# Feedback and conversion cycles

Design for the last four declared limitations: `cycle/conversion-ring`,
`cycle/self-growth`, `catalyst/raw-feedback-loop` and `catalyst/lossy-feedback-loop`.
Twelve of the fifty-one corpus cases, and the only ones the model still refuses.

Report of origin: `docs/planner-superiority-roadmap.md`, phase R2.5.

## Why the planner refuses them today

In `candidates`, an input that is currently being expanded and is short of stock marks
the route as a cycle and drops it:

```java
if (state.active.contains(input.key()) && available.compareTo(needed) < 0) cycle = true;
if (input.key().equals(key)) cycle = true;
```

The second line refuses every self-feeding pattern outright. The first refuses any
route that re-enters a key the plan is already inside, which is what
`A→2B→(C)→E+D→A` does. There is no cycle analysis behind either refusal: the route is
simply discarded, and the whole request falls through to AE2.

## Self-feeding growth

`cycle/self-growth` is the narrowest case and the one worth doing first: a single
pattern that consumes and produces the same key with a surplus, `A1 → A2`.

### Arithmetic

Let a run consume `c`, produce `p`, with `net = p − c > 0`. The plan has already taken
`s` units from stock by the time the route is evaluated, so the demand still to satisfy
is `required = R − s`. Because each run adds `net`, the loop reaches the request in

```text
runs = ceil(required / net)
```

The seed is not subtracted a second time: the caller's `consume` already took it.

Checked against the corpus, whose requests are `A = 8` with `A1 → A2`:

| mode | stock | required | runs | missing |
|---|---:|---:|---:|---|
| MINIMUM | 1 | 7 | 7 | none |
| MISSING | 0 | 8 | 8 | `{A=1}` |
| UNBOUNDED | 10^12 | 0 | — | none, the stock covers it |

### The seed shortfall is not the whole request

With no stock the loop cannot start, so the shortage is one seed, not the eight the
request asks for: one seed is enough to reach any amount. Reporting the request would
be wrong, and running the loop while reporting nothing is the "feasible without a
seed" false positive that AE2-VM had to fix in its own v1.10.3.

The planner reports `c − s` as missing and still schedules the runs. The replay is what
holds this honest: with the shortage injected it funds the first firing and every later
one from the loop's own output, and without the injection it fails with
`unfunded input A`.

### The balance contract needs the supplied seed to be modelled

This is the part that is easy to get wrong. The corpus declares

```text
expected = stock + produced + missing
observed = remaining + demand + request
```

For a missing input that is fully consumed the two sides cancel, which is why every
other family balances. A growth seed is **not** fully consumed: supplying it leaves it
multiplied in the inventory. With `missing = 1`, `produced = 16`, `demand = 8` and
`request = 8`, the contract requires `remaining = 1`, but a planner that never received
the seed computes zero.

So when the seed is short, the planner must model the reported shortage as available —
add the shortfall to `crafted` as well as to `missing`. Then the self-feed draws it, the
run count is unchanged, and the residue is the surviving seed. This is the same stance
the refill contract takes for every other family: a reported shortage is assumed to be
supplied when accounting.

### The self-feeding input must be claimed after the pattern runs

`expand` wraps every input into the head of the task chain, so inputs are drawn before
the pattern executes. For a self-feeding input that is backwards: the material is
produced by the very pattern that consumes it. The chain has to become

```text
[other inputs] -> [pattern task] -> [self-feeding input task] -> [continuation]
```

Otherwise the self-feed is evaluated first, finds nothing, re-enters the same key, and
the cycle guard refuses the plan — which is exactly what happens today.

The chain is built in one pass by splitting the inputs into those claimed outside the
pattern and those claimed after it, then assembling `outside → pattern → feeding → next`.

## What the other three need on top

- **`catalyst/raw-feedback-loop`** (conservative feedback): the cycle spans several
  keys, so the split above is not enough. It needs the strongly connected component,
  a cycle iteration derived from it, and one member treated as the seed. The balance and
  seed reasoning are the same as for self-growth, applied to the component instead of a
  single pattern.
- **`catalyst/lossy-feedback-loop`**: the cycle loses a fixed amount per turn, so a
  single seed does not suffice. The shortage is `seed + losses × turns` and has to be
  reported as such.
- **`cycle/conversion-ring`**: pure conversion with no gain, `1 A = 9 B = 81 C`. Nothing
  is created, so feasibility needs the ring's exchange rates compared against external
  demand. AE2-VM solves this with exact rational arithmetic (`computeConversionRingMissing`)
  because a floating comparison here produces a plan that cannot be executed.

## Oracle support

`isReplayable` already accepts a self-feeding pattern, but that is not enough: the
ordered replay cannot fund one.

A replay step draws **all** of its inputs before producing any output, computed as
`input.amount × step.runs`. For every other pattern that is correct, because the whole
batch really is on hand before the pattern runs. For a self-feeding one it is not: the
plan schedules a single step of seven runs, so the replay demands seven units up front,
finds one seed, and reports `unfunded input A`.

This was found by implementing the planner half alone. The planner's arithmetic was
right — it scheduled the seven runs and balanced — and `cycle/self-growth/minimum`
still failed replay with `unfunded input A at a-grows` and a residue of six. Both halves
are needed and neither is useful alone.

Funding is inherently progressive: each run pays for the next. The replay has to model
it that way, but not by iterating, or a request of a billion would become a billion
steps. The closed form:

```text
c, p   per-run consumed and produced of the key
n      runs in the step
seed   max(0, c − crafted)     what crafted alone cannot cover
```

The step is fundable when `crafted + consumable + injected >= c`. The external draw is
exactly `seed`, taken from `consumable` and then `injected` the way other steps do it,
and after the step `crafted += n·p − n·c + seed`. A run-by-run simulation produces the
same pools, without the loop.

## Gates

The corpus moves `cycle/self-growth` from declared limitation to required, the
`MINIMAL_SHORTAGE_CASES` list gains its missing mode so the seed shortage is pinned,
and the refill path must complete — which is what proves the reported seed is both
necessary and sufficient.
