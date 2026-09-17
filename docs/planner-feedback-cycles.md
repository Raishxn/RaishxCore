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

## The conversion ring needed nothing

`cycle/conversion-ring` was declared a limitation on the assumption that a ring of
conversions is beyond the planner. It never was. The cycle guard already refuses a route
that reaches into a key currently being expanded, and the leaf-cost pass already leaves
keys on a cycle out of the cost map instead of failing outright. The ring was therefore a
safe decline that happened to be a capability the engine had all along and had simply
never claimed.

Turning it on cost no engine change: the planner returns the corpus minimum, one extra
unit of either entry key, with overhead `1.000`, and the independent replay funds the ring
and confirms the plan. AE2-VM solves the same shape with exact rational arithmetic; a
floating comparison there would produce a plan that cannot be executed. The guard here is
integral instead, so the question does not arise.

## What the two catalyst loops need on top

Both span several keys and both recycle one of them, so the self-feeding split is not
enough: it is the strongly connected component that has to be reasoned about, with one
member treated as the seed.

The naive model, "seed plus a fixed loss per turn", is **unsound**, and the corpus proves
it rather than an argument. `catalyst/lossy-feedback-loop` is `3 A -> 2 B`, `2 B -> 1 D +
2 A`: one turn nets `-1 A` and yields `1 D`. For eight turns that model demands
`3 + 8 = 11 A`, but the true minimum is `10`.

The difference is that the last turn never has to be replenished: it may spend its seed
and finish. What binds is the deepest point of the turn, not the total. Writing `c` for the
net consumed per turn, `d` for how much of the key must be in hand at the worst moment of a
turn, and `n` for turns:

```text
seed >= (n-1)·c + d
```

With `c = 1`, `d = 3`, `n = 8` this is `10`, and with `c = 0`, `d = 1` — the conservative
loop, which returns the seed in full — it is `1`, matching `catalyst/raw-feedback-loop`.
Self-growth is the same formula with `c` negative, which is why `1 A -> 2 A` needs a seed
of one and no more.

The `(n-1)` is not a rounding detail: charging the last turn makes the planner report
`11 A` where `10` suffices, and the corpus witness pins the minimum, so it would be caught.

## Measured, before implementing any of it

Declaring both families required without any engine change — the probe that establishes
what is actually missing — gives:

```text
catalyst/raw-feedback-loop/missing    missing={D=8}   overhead=8.000
catalyst/raw-feedback-loop/minimum    FALSE_NEGATIVE  reported shortage {D=7} on a feasible scenario
catalyst/lossy-feedback-loop/missing  missing={A=16}  overhead=8.000
catalyst/lossy-feedback-loop/minimum  FALSE_NEGATIVE  reported shortage {A=14} on a feasible scenario
```

No false positives: the planner never claims a plan it cannot execute, it only refuses
work it could do. It treats the recycled key as ordinary stock and reaches for the
byproduct — `D` — as if it had to come from outside, which is where the factor of eight
comes from. That is the failure to fix, and it is a safe one, which is why these families
stay declared limitations until the component arithmetic above is in.

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

The corpus moves `cycle/self-growth` and `cycle/conversion-ring` from declared limitations
to required — `required supported=45/45`, `limitation cases supported but not claimed=0/6`
— and the `MINIMAL_SHORTAGE_CASES` list gains the missing mode of each, so the seed
shortage is pinned. The refill path must complete, which is what proves a reported seed is
both necessary and sufficient.

The two catalyst loops stay declared limitations until the component arithmetic lands.
A declared limitation is a promise the opposite way: the gate asserts the planner must
**not** claim them, so neither the over-report above nor a premature activation can pass
unnoticed.
