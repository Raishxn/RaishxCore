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

## What the two catalyst loops needed on top

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

## The primitive that carries the formula

`(n-1)·c + d` is `(d - c) + n·c`, which is a catalyst whose working stock is `d - c` and which
loses `c` per firing. That is expressible as a reusable input of `d - c` plus a consumed input
of `c` — except that both are the same key, and `CraftingPattern` refused to let a key be
consumed and reusable at once. A caller had to pick one and silently lose the other: the
catalyst half promised a batch that eats the working stock it was just approved against, and
the consumed half demanded a whole working stock per firing.

A decaying catalyst is now a first-class declaration, keeping both entries through compilation
rather than merging them into one. The presence check also had to change, because it runs
before the draws and would otherwise read the full stock and wave through a batch that spends
the very stock it approved; it now demands the working stock and the batch's decay together.

None of this was enough on its own — the component still has to be found and turned into such
a pattern — but it is the piece the arithmetic was missing, and it is useful on its own for any
recipe that genuinely consumes part of its catalyst.

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

## How the component is recognised and priced

`FeedbackCyclePlanner` walks from the target's recipe along the single route into each of its inputs
until a pattern repeats, and declines anything with a choice or a fork on the way. A single-pattern
loop is left alone, because the planner already funds self-feeding growth a seed at a time.

A loop has as many rotations as it has patterns, and they are not equally usable. Priming the wrong
key asks for material the request never had, which reads as a shortage on a feasible scenario, so the
rotation the inventory already covers wins, then the smallest shortage, then the fewest kinds of seed.
For the conservative loop with one `A` and eight `C` in stock, priming `A` is feasible and priming `D`
is not, and the two are otherwise identical, so nothing but the inventory can tell them apart.

One turn is then priced by walking it in that order. A pattern draws its inputs before it produces
anything, so the deepest point of a turn is the running consumption less everything produced before
it, not the balance at the end. Self-growth falls out as the one-pattern case of the same walk, and
the conversion ring needs none of it.

The macro is a decaying catalyst: working stock present, decay consumed per firing. The plan is
rewritten back to the real patterns turn by turn rather than grouped, because grouping lets a reader
or a machine ask for the whole batch of the first pattern before the pattern that returns the catalyst
has run — the very thing the loop makes impossible. A request whose expansion would be enormous is
declined instead, since a plan that cannot be written down cannot be executed.

The declared numbers have to be restated in the schedule's terms, because the macro and the real
schedule agree in total but not line by line. The first turn takes the whole working stock out of the
inventory and every later turn replaces one turn of decay, so the loop withdraws
`working + (turns-1)·decay` — and only what the inventory actually holds can be declared as taken,
with the rest carried by the reported shortage. That distinction was found by the oracle, which
rejected a plan that extracted ten units from a stock of eight.

## Gates

The corpus moves every family this document describes from a declared limitation to a required
capability: self-growth and the conversion ring first, then both catalyst loops, then the chance
route. `required supported=81/81 confirmed defects=0 limitation cases supported but not claimed=0/0`,
with zero false positives and zero false negatives across all three material modes, over 81 cases in
27 groups.

The `MINIMAL_SHORTAGE_CASES` list gains the missing mode of each family as it lands, so the frontier
is pinned rather than merely present. The refill path must complete, which is what proves a reported
seed or shortage is both necessary and sufficient.

Both catalyst loops are optimal on the missing mode — `overhead=1.000`, the exact witness — where the
naive model would have reported eleven units against ten, and that difference is exactly what the
witness assertion catches.

With nothing declared, the refusal machinery is driven by a planner stub that declines every case
rather than by a family that happens to be missing: a capability that is never refused is a refusal
path that is never tested, and leaving it to a family that happens to be missing is how it rots.

RaishxCore is now optimal on all 27 missing-mode cases, worse on none. ThunderboltV2 is optimal on
19 and worse on seven, among them eight times over on `cycle/self-growth`, and it does not answer the
chance-output family at all. Claim level across the corpus is 54 complete and 27 shortage for
RaishxCore, against 39 and 39 with three unanswered for the reference.
