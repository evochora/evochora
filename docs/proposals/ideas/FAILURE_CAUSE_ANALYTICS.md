# Failure Cause Analytics

**Status: IDEA — not decided. No design decisions made yet; open questions at the end.**

## Problem

When organisms fail instructions in a live run, the analytics show **that** they fail, but not
**at what**. Verified state:

- `Organism.instructionFailed()` records a failure flag and a free-text reason string
  ("Invalid register ID: …", "not a unit vector", "No matching label found for hash …",
  "Max skips exceeded", stack underflow/overflow, division by zero, type mismatches, …).
- `InstructionUsagePlugin` already aggregates the failure *rate* (`failure_count` column,
  rendered as a secondary axis), but no analytics plugin aggregates the failure *reason*.
  The information is produced every tick and discarded.

Why this matters for the fitness-landscape work: the failure-cause distribution is the live
counterpart of the [MUTATIONAL_ROBUSTNESS_ASSAY](MUTATIONAL_ROBUSTNESS_ASSAY.md). The assay measures
the landscape around the primordial under controlled conditions; the cause histogram shows what the
*evolved population* actually fails at in a real run — i.e. which cliff is currently under selection.
Example: a high share of "not a unit vector" failures would be the live signature of the defect
described in [DATA_MUTATION_SIGN_FIX](../DATA_MUTATION_SIGN_FIX.md); its disappearance after the fix
would be the confirmation.

## Idea

Aggregate a histogram over failure-cause **categories** per tick bucket (following the bucket
aggregation pattern of `InstructionUsagePlugin`), either as an extension of that plugin or as a
separate small plugin built on the same pattern.

Raw reason strings are unsuitable as histogram keys: they contain variable parts (concrete register
IDs, hashes, coordinates), so a stable categorization is needed.

## Suggested approach (not decided)

Categorize at the source: a failure-cause enum alongside (not replacing) the free-text string in
`instructionFailed`. Arguments:

- The category is the stable information; the free string remains as debug detail.
- The hotpath impact is favorable — the failure path currently builds a concatenated string in every
  case; an enum field costs nothing, and string construction could even become lazy or optional later.
- Analytics then reads a typed field instead of parsing text.

The alternative — parsing the existing reason strings inside the analytics plugin — would leave the
runtime untouched, but is fragile against any wording change and couples analytics to log text.

## Open questions

1. Enum at the source vs. string parsing in analytics (see above — suggested, not decided).
2. Category granularity: one category per distinct failure site, or coarser groups
   (operand errors / control-flow errors / stack errors / environment errors / stall)?
3. Extension of `InstructionUsagePlugin` vs. separate plugin.
4. Serialization impact: does the enum need to appear in `OrganismRuntimeView` / the pipeline DTOs,
   or is per-tick aggregation inside the simulation process sufficient?
5. Should the histogram also be broken down by instruction family (which family fails at what),
   or is the global distribution enough for a first version?
