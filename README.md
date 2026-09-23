# Hello ZIO TypeSafe AI / Jev

This Scala 3 application is an experimental benchmark for schema-safe AI tool orchestration with ZIO, Amazon Bedrock, TypeSafe AI's Jev controller, and MCP. It runs the same Jackson documentation-research task against the live [javadocs.dev](https://www.javadocs.dev/) MCP catalog through three designs: a direct LLM-agent baseline, an LLM with a nested Jev evidence tool, and top-level Jev orchestration with plan and no-plan modes.

The experiments compare controller behavior, filtering and fanout safety, MCP usage, model and Jev token consumption, and end-to-end latency. The implementation is provider-neutral below its Bedrock transport and benchmark-specific MCP wiring, so the orchestration patterns can be evaluated independently of the Javadocs use case.

## Run the experiments

1. [Create a Bedrock Bearer token](https://us-east-1.console.aws.amazon.com/bedrock/home?region=us-east-1#/api-keys/long-term/create).
1. Set Bedrock configuration:
   ```bash
   export AWS_BEARER_TOKEN_BEDROCK=YOUR_TOKEN
   export BEDROCK_MODEL_ID=us.anthropic.claude-sonnet-4-5-20250929-v1:0
   ```
1. Run the LLM Loop:
   ```bash
   ./sbt "run llm-loop"
   ```
1. [Create a TypeSafe API Key](https://console.typesafe.ai/keys)
1. Setup TypeSafe configuration:
   ```bash
   export TYPESAFE_API_KEY=YOUR_TYPESAFE_TOKEN
   ```
1. Run an experiment:
   ```bash
   ./sbt "run jev-tool jev-filters"
   ./sbt "run jev-tool llm-filters"
   ./sbt "run jev-loop plan"
   ./sbt "run jev-loop no-plan"
   ./sbt "run jev-loop plan-llm-filters"
   ./sbt "run jev-loop no-plan-llm-filters"
   ```


## Benchmark summary

The following is one live sample run on 2026-09-23 using the configured Bedrock model, Jev `1.13.0`, and the live javadocs.dev MCP catalog. All seven unique variants completed successfully and produced an answer covering the central polymorphic-validation API. Bare `jev-tool` and `jev-loop` aliases are omitted because they duplicate `jev-tool jev-filters` and `jev-loop plan` respectively.

| Variant | Controller turns | Main symbol filter | Physical MCP calls | LLM input | LLM output | Jev input | Wall time |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| `llm-loop` | 6 outer LLM | Direct LLM tool selection | 10 | 257,010 | 2,651 | 0 | 52,698 ms |
| `jev-tool jev-filters` | 5 outer LLM + 20 inner Jev | Jev `Ready 6/618`; 20 total filter requests across repeated nested runs | 20 | 126,905 | 1,775 | 253,090 | 53,366 ms |
| `jev-tool llm-filters` | 3 outer LLM + 11 inner Jev | LLM `Ready 10/618`; 4 total criteria calls | 15 | 98,555 | 1,814 | 35,368 | 45,634 ms |
| `jev-loop plan` | 7 Jev | 2 LLM criteria calls | 13 | 88,278 | 1,078 | 37,465 | 29,585 ms |
| `jev-loop no-plan` | 8 Jev | Jev `Ready 6/618`; 7 total filter requests | 10 | 38,753 | 710 | 152,942 | 22,122 ms |
| `jev-loop plan-llm-filters` | 7 Jev | 2 LLM criteria calls | 13 | 88,278 | 1,116 | 37,465 | 30,421 ms |
| `jev-loop no-plan-llm-filters` | 8 Jev | LLM `Ready 7/618`; 2 criteria calls | 11 | 68,034 | 930 | 59,548 | 27,650 ms |

“LLM input/output” includes every Bedrock model call in the arm: outer agent turns plus any internal extraction, predicate-synthesis, and summary calls. “Jev input” combines logical controller input with internal Jev field/variant-filter input; Jev output is retained in the raw metrics but omitted from this primary cost table. For nested `jev-tool`, each column sums all inner invocations. Jev and Bedrock may use different tokenizers and pricing, so the columns should be compared independently rather than added into a normalized cost. Wall time is end-to-end and includes model, Jev, MCP, and host execution; overlapping calls are not subtracted.

In this sample, runtime-checkpoint `jev-loop no-plan` was fastest and tied `llm-loop` for the fewest MCP calls. It also had the lowest LLM input/output usage, while complete Jev classification of 618 variants raised Jev input to 152,942. Switching that arm to LLM filter criteria increased LLM input from 38,753 to 68,034 but reduced Jev input to controller-only traffic of 59,548. The two plan rows are nearly identical because current plan execution synthesizes filters before runtime variants exist, so both presently use LLM criteria. Catalog-only `jev-tool jev-filters` incurred repeated outer delegations and repeated inner work, visible as both 126,905 LLM input and 253,090 Jev input; its LLM-filter counterpart reduced those columns to 98,555 and 35,368 respectively. That overhead motivates future resumable checkpoint handoff between nested invocations.

These are stochastic, network-dependent single samples, not statistically significant rankings. Use multiple samples and answer-quality judging in zio-evals before drawing release or architecture conclusions. Complete logs for this sample were captured under `/tmp/hello-zio-bedrock-benchmark/` on the machine that ran it.

## Nested Jev tool

`jev-tool` keeps Bedrock as the outer agentic loop but exposes only one synthetic capability. Each invocation passes the tool's schema-validated JSON input, the provider-neutral operation catalog, and the operation invoker into `GenericOrchestrator.runEvidence`. The inner TypeSafeAI loop has no knowledge of Javadocs, Maven, Jackson, MCP operation names, or Bedrock.

The inner loop offers only operations whose required arguments bind exactly from supplied input or prior typed outputs. It never enables extraction or summary capabilities. The default and explicit `jev-filters` modes use Jev field preflight, complete recall batches, consolidation, and complete host membership evaluation; a source above the configured Jev threshold fails visibly instead of invoking an LLM. The `llm-filters` mode changes only filter-criteria generation: Bedrock receives deterministic observed-variant evidence and returns one host-validated predicate, which Scala still evaluates against the complete source. When enough safe evidence exists, Jev selects `return_evidence`; filtered evidence replaces its unsafe raw producer, and the structured evidence is returned to the outer Bedrock loop for final answering.

If an operation returns a prerequisite under a schema name that cannot bind exactly to a later input, the inner loop does not guess or relabel it. The outer LLM can inspect that evidence and invoke the synthetic tool again with the newly known value. The synthetic tool input schema is generated at startup by merging parameter schemas from the provided MCP catalog; every field is optional, conflicting definitions use `anyOf`, and no prompt-specific or domain-specific fields are added.

## Generic plan and no-plan modes

Both generic modes use the same catalog, schema binding rules, host executor, evidence representation, final Bedrock summary, safety policy, retry limits, and result/metric type. The catalog contains every MCP operation plus three internal capabilities: `extract_tool_arguments`, `synthesize_filter`, and `summarize`. Names and bindings come from MCP schemas; there are no javadocs-specific operation names in the generic implementation.

In **plan** mode, Jev first selects a symbolic workflow segment and the host then executes it deterministically in dependency order/waves. In **no-plan** mode, options are regenerated from the actual checkpoint after every selection and the selected extraction, filter, direct/fanout MCP invocation, summary, or finish action executes in the loop handler before Jev's next decision. After a successful action, the host indexes only runtime-confirmed singleton arrays at fixed index zero: declared scalar/object fields and nested arrays become independently bindable values, while empty and multi-item arrays are not projected. Nested arrays can therefore be filtered against their own item schema without enabling array traversal in predicate paths. The next no-plan state contains compact operation metadata, required input names, output paths, available value names/paths, confirmation flags, and action metadata—not full operation/value schemas, runtime arrays, or the host cardinality guard. Confirmed actions are fingerprinted and removed from later options.

Execution is checkpointed. A failed MCP attempt retries only that call; successful earlier calls and successful sibling fanout items are retained and are not replayed. In no-plan mode, a terminal invocation failure is compacted into the next Jev checkpoint, the failed invocation fingerprint is retired, and no failed workflow step is committed, allowing Jev to choose another operation or summarize retained evidence. In **no-plan** mode, each Jev-selected filter or filter-recovery action performs exactly one synthesis/evaluation attempt. A `TooBroad`, `NoMatches`, or `NoProgress` outcome is retained as compact state and returned to Jev, which may choose refinement, replacement, another operation, or summarization on its next turn; downstream fanout remains unavailable until `Ready`. If Jev summarizes with a filter still unresolved, the host excludes that filter source's raw producer evidence so summarization cannot become an implicit semantic filter over the unsafe array. Once a filter is `Ready`, host-filtered evidence replaces its raw producer evidence. When that ReadyFiltered value has a compatible downstream fanout, summary remains unavailable until at least one such fanout has produced evidence; if no compatible fanout exists, the filtered evidence itself may be summarized. In **plan** mode, a recoverable filter outcome stops the dependent suffix while the host performs a bounded deterministic continuation from the retained complete set. `TooBroad` refinement evaluates a new predicate over the complete prior match set, so cumulative matches are a monotonic subset. `NoMatches` replacement evaluates over the retained parent source. `NoProgress` and repeated canonical predicates are rejected. Filter attempts and the run-wide recovery budget are independently bounded by `OrchestrationConfig.maxFilterAttempts` and `maxRecoveries`; both plan and no-plan share one total recovery allowance per run. Per-turn Jev transport retries are bounded by `jevTurnRetries`. The current plan-mode implementation uses deterministic checkpoint continuation rather than a planner-installed suffix replan.

## Filtering and fanout safety

`synthesize_filter` uses a Jev-first runtime router in no-plan mode. `OrchestrationConfig.jevFilterVariantThreshold` defaults to 255; this application's `plan` and `no-plan` experiments configure it to 1024, while both `*-llm-filters` variants set it to zero. The current plan executor still synthesizes filters before runtime variants are available, so its configured threshold is reserved for the runtime-aware plan filter continuation; `plan-llm-filters` makes today's LLM behavior explicit. Jev-filtered sources first use a field-relevance preflight over declared scalar paths. Up to 255 distinct canonical scalar variants are then classified in one recall request; when configured above 255, sources above 255 and at or below the threshold split complete recall into batches of at most 255 evaluated in parallel. When a multi-batch recall's preliminary positives fit in one request, a stricter consolidation pass evaluates each positive independently rather than ranking for one winner, retaining specifically related subject, member/subtype, contract, implementation, configuration, and result identities while rejecting broad or adjacent concepts. The host also retains an exact recalled enclosing identity when one of its nested identities survives consolidation; it never introduces a variant that failed preliminary recall. Sources above the threshold fall back to Bedrock predicate synthesis with deterministic prompt-linked plus stable-uniform observed-variant evidence. Setting the threshold to zero disables Jev variant classification. Jev-selected variants compile into a host-only exact membership criterion, and Scala still evaluates that criterion against every original source record. Plan mode continues to use LLM predicate synthesis because its symbolic workflow is completed before runtime variants exist; `plan-llm-filters` makes that behavior explicit.

The LLM fallback uses a forced `Tool.dynamic` structured output. Its request contains only the prompt, item schema, optional prior predicate, outcome diagnostics, any compact prior synthesis rejection, and bounded observed-variant evidence. It never contains the host fanout guard, the Jev routing threshold, or another host max/limit setting. Model output is parsed, validated against declared scalar item-schema paths, and canonically re-encoded before evaluation. Invalid AI-generated criteria consume exactly one filter attempt and one internal generation call; they are never retried inside a selected action. In no-plan mode the compact rejection is returned to Jev, which must explicitly select retry, refinement, or replacement. In plan mode the host deterministically continues from the equivalent retained checkpoint while attempt and run-wide recovery budgets remain; each continuation includes deterministic observed-variant evidence from the complete retained source used by the next predicate.

The predicate language is finite and non-recursive: one atom or one bounded `all`/`any` group of leaf predicates. Leaves support case-insensitive string `eq` and `in` (non-string scalars remain exact), case-insensitive `contains` and `startsWith`, `exists`, `isNull`, `gt`, `gte`, `lt`, and `lte`. Paths are bounded nested object-field segments resolving to declared scalar schemas. Array indexing/traversal, arbitrary JSONPath, regex, scripts, ranking, projection, and top-k are not representable.

Filtering always evaluates every source item and retains complete ordered `source` and `matches` vectors before classifying the outcome as `Ready` (nonempty and at most the host guard), `TooBroad`, `NoMatches`, or `NoProgress`. It never samples, calls `.take`, stops early on cardinality, or silently truncates. Generic fanout requires an explicit host authorization: either a `Ready` filter result or a declared `maxItems` bound at or below the host guard whose MCP output has passed local output-schema validation. Raw unsafe arrays fail before any downstream call. Fanout result schemas are `array<operation output>`. Final summarization is instructed to assert only facts explicit in retained evidence; identifiers alone cannot justify behavioral descriptions, inferred relationships, enum members, or safety guidance.

`OrchestrationConfig` owns `hostGuard`, `maxFilterAttempts`, `maxRecoveries`, `jevFilterVariantThreshold`, and `jevTurnRetries`; the host limits are never sent to Jev or the filter LLM.

## Metrics and observation

Both generic modes print the same JSON key set:

- `mode`; Jev logical controller `turns`, input/output tokens, and logical request time;
- Jev physical attempts/successes/failures/time from `ExchangeObserver`, including controller and internal variant-classification requests;
- internal Jev filter calls/input/output/time separately, so single and parallel batches are visible;
- internal LLM calls/input/output/time independently for extract, filter fallback, and summary;
- MCP physical calls/successes/failures, union-of-interval wall time, and summed call time;
- checkpoint recoveries and actual planner-installed replans (the current deterministic continuation implementation reports `replans = 0`), plus end-to-end total time.

Logical Jev time sums logical decision request elapsed times (including per-turn retry waits as measured by the loop); physical Jev time sums observed HTTP attempt durations. MCP physical metrics are recorded immediately around each raw MCP request: local input-schema rejection is not an MCP call, MCP transport errors and `isError` responses are physical failures, and local output normalization/schema validation is excluded from MCP duration. MCP wall time merges overlapping raw-call intervals while summed time adds every raw call. `recoveries` counts bounded in-place MCP retries and filter continuations; `replans` counts only a future planner invocation that installs a new suffix, so it remains zero in the current implementation. `totalTimeMs` is measured directly around the generic run. No synthetic “orchestration time” is derived by subtracting overlapping timers.

Every production Jev client in `jev-tool` and `jev-loop` uses `TypeSafeAI.Client.live >>> TypeSafeAI.Client.observed(...)`. Jev choice requests retain complete operation metadata, including operation descriptions and full input/output schemas with field descriptions, so selection and schema-declared producer/consumer relationships are available to the controller. Normal exchange logs summarize those schemas as operation descriptions plus named input/output fields, types, required flags, and field descriptions; runtime candidate/evidence payload arrays remain omitted. Response logs retain the selected answer, complete probability distribution, confidence, token usage, and physical-attempt latency.

For temporary wire-level diagnosis, exact lowercase `JEV_FULL_EXCHANGE_LOGS=true` restores TypeSafeAI's complete request-body and canonical response-body logger:

```bash
JEV_FULL_EXCHANGE_LOGS=true ./sbt "run jev-loop no-plan"
```

Neither logger captures HTTP headers, so bearer-token headers remain excluded. Full bodies can still contain sensitive application state and answers; compact mode should remain the normal setting. Generic plan mode also retains `Content`-only planning audit snapshots, while no-plan retains the corresponding runtime action audit. CLI output includes the mode's workflow and action/continuation trace.

## Tests

The default test run is deterministic and credential-free:

```bash
./sbt test
```

The suite covers catalog-derived synthetic inputs, exact-input evidence-only Jev delegation with zero inner LLM calls, visible Jev-capacity failure, predicate validation and full ordered evaluation, last-item matches, `TooBroad` refinement, `NoMatches` replacement, `NoProgress`, repeated predicates, independent filter/recovery exhaustion, request guard secrecy, raw fanout rejection, static-bound authorization and output violations, fanout output shape, plan-before-execute ordering, action execution between no-plan decisions, checkpoint no-replay, recovery-versus-replan accounting, physical MCP boundary counts, equivalent evidence/summaries, common metric keys/counts, and CLI defaults.

Without `-Dlocal`, the build resolves pinned releases `zio-bedrock` 0.1.0, `zio-http-mcp` 0.8.2, and `zio-typesafe-ai` 0.1.0. Add `-Dlocal` only when intentionally testing both local source checkouts.

## Repository boundaries

The provider-neutral `com.jamesward.zio_typesafe_ai.orchestration` package comes from `zio-typesafe-ai` 0.1.0, or from its source project when `-Dlocal` is enabled. This application retains only `McpCatalog`/`McpOperationInvoker`, `BedrockLlmTransport`, the Javadocs benchmark prompt and MCP endpoint, and CLI/outer-loop wiring. `jev-tool` invokes TypeSafeAI's generic evidence orchestrator and zio-bedrock's generic `Tool.dynamic`, `ToolInput`, and `dynamicLoop` APIs. No Javadocs assumptions or benchmark-specific limits are present in generic orchestration. `zio-typesafe-ai` has no zio-bedrock or zio-http-mcp dependency; `zio-evals` consumes its orchestration result types for judge-oriented checks and comparisons.
