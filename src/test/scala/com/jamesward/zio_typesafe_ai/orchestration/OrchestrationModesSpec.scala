package com.jamesward.zio_typesafe_ai.orchestration

import com.jamesward.zio_typesafe_ai.{AppTypeSafeAIMock, TypeSafeAI}
import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import com.jamesward.ziohttp.mcp.{CallToolResult, ToolDefinition, ToolName}
import com.jamesward.zio_typesafe_ai.orchestration.model.*
import com.jamesward.zio_typesafe_ai.orchestration.runtime.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object OrchestrationModesSpec extends ZIOSpecDefault:
  private val string = Json.Obj("type" -> Json.Str("string"))
  private val emptyInput = Json.Obj(
    "type" -> Json.Str("object"), "properties" -> Json.Obj(), "additionalProperties" -> Json.Bool(false),
  )
  private val output = Json.Obj(
    "type" -> Json.Str("object"), "properties" -> Json.Obj("value" -> string),
    "required" -> Json.Arr(Json.Str("value")), "additionalProperties" -> Json.Bool(false),
  )
  private val definition = ToolDefinition(ToolName("fetch"), Some("fetch generic evidence"), emptyInput, Some(output))

  private def filterAtom(op: String, value: Option[String] = None): Json.Obj = Json.Obj(
    "kind" -> Json.Str("atom"),
    "leaf" -> Json.Obj(Chunk.fromIterable(
      Vector("op" -> Json.Str(op), "path" -> Json.Arr(Json.Str("id"))) ++ value.map(text => "value" -> Json.Str(text))
    )),
  )

  private case class Run(result: OrchestrationResult, evidence: Json.Arr, calls: Int, requestBodies: Vector[Json])

  private def run(mode: OrchestrationMode): Task[Run] =
    for
      observed <- AppTypeSafeAIMock.choicesObserved("c000", "c000", "c000")
      calls <- Ref.make(0)
      evidence <- Ref.make(Json.Arr())
      llm <- InternalLlmInvoker.make(new LlmTransport:
        def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
        def summarize(prompt: String, values: Json.Arr) = evidence.set(values) *> ZIO.succeed(LlmResult("same final answer", 2, 1, 1))
      )
      operations <- McpOperationInvoker.make(Chunk(definition), new RawMcpInvoker:
        def call(operation: String, arguments: Json.Obj) =
          calls.updateAndGet(_ + 1).as(CallToolResult(structuredContent = Some(Json.Obj("value" -> Json.Str("evidence")))))
      )
      result <- McpOrchestrator.runMode(
        "fetch an answer", Chunk(definition),
        operations,
        llm, mode, OrchestrationConfig(jevFilterVariantThreshold = 0, maxOperations = 1, maxIterations = 3),
      ).provideLayer(observed.layer)
      captured <- evidence.get
      count <- calls.get
      bodies <- observed.requestBodies
    yield Run(result, captured, count, bodies)

  def spec = suite("comparable generic orchestration modes")(
    test("no-plan executes selected actions between Jev decisions and suppresses duplicates") {
      run(OrchestrationMode.NoPlan).map: observed =>
        val secondState = observed.requestBodies.lift(1).flatMap(_.asObject).flatMap(_.get("state"))
        assertTrue(
          observed.calls == 1,
          observed.result.actionTrace == Vector("invoke:fetch:direct", "summarize", "finish:s003-summary"),
          secondState.exists(_.toJson.contains("invoke:fetch:direct")),
          observed.result.finalText == "same final answer",
        )
    },
    test("no-plan uses Jev first for runtime variants at or below the configured threshold") {
      val item = Json.Obj(
        "type" -> Json.Str("object"), "properties" -> Json.Obj("id" -> string),
        "required" -> Json.Arr(Json.Str("id")), "additionalProperties" -> Json.Bool(false),
      )
      val enumerateOutput = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("items" -> Json.Obj("type" -> Json.Str("array"), "items" -> item)),
        "required" -> Json.Arr(Json.Str("items")), "additionalProperties" -> Json.Bool(false),
      )
      val inspectInput = Json.Obj(
        "type" -> Json.Str("object"), "properties" -> Json.Obj("id" -> string),
        "required" -> Json.Arr(Json.Str("id")), "additionalProperties" -> Json.Bool(false),
      )
      val definitions = Chunk(
        ToolDefinition(ToolName("enumerate"), Some("enumerate variants"), emptyInput, Some(enumerateOutput)),
        ToolDefinition(ToolName("inspect"), Some("inspect one variant"), inspectInput, Some(output)),
      )
      val source = Vector.tabulate(3)(index => Json.Obj("id" -> Json.Str(s"item-$index")))
      import AppTypeSafeAIMock.ScriptedResponse
      for
        observed <- AppTypeSafeAIMock.scriptedObserved(
          ScriptedResponse.Choice("c000"),
          ScriptedResponse.Choice("c000"),
          ScriptedResponse.Nouls(Vector(0.9)),
          ScriptedResponse.Nouls(Vector(0.1, 0.9, 0.1)),
          ScriptedResponse.Choice("c000"),
          ScriptedResponse.Choice("c000"),
          ScriptedResponse.Choice("c000"),
        )
        inspected <- Ref.make(Vector.empty[String])
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          override def synthesizeFilter(request: PredicateFilter.SynthesisRequest) =
            ZIO.dieMessage("LLM filter fallback must not run below the Jev threshold")
          def summarize(prompt: String, values: Json.Arr) = ZIO.succeed(LlmResult("Jev-filtered answer"))
        )
        operations <- McpOperationInvoker.make(definitions, new RawMcpInvoker:
          def call(operation: String, arguments: Json.Obj) = operation match
            case "enumerate" => ZIO.succeed(CallToolResult(structuredContent = Some(Json.Obj("items" -> Json.Arr(source*)))))
            case "inspect" =>
              val id = arguments.get("id").flatMap(_.asString).get
              inspected.update(_ :+ id).as(CallToolResult(structuredContent = Some(Json.Obj("value" -> Json.Str(s"detail:$id")))))
        )
        result <- McpOrchestrator.runMode(
          "inspect the relevant item", definitions, operations, llm, OrchestrationMode.NoPlan,
          OrchestrationConfig(maxOperations = 2, maxIterations = 5),
        ).provideLayer(observed.layer)
        calls <- inspected.get
        bodies <- observed.requestBodies
      yield assertTrue(
        calls == Vector("item-1"),
        result.finalText == "Jev-filtered answer",
        result.internalLlm.filterCount == 0,
        result.metrics.jevFilterCalls == 2,
        result.metrics.jevFilterInputTokens == 4,
        result.metrics.jevLogicalTurns == 5,
        result.workflow.steps.exists {
          case Step.Filter(_, _, _, _, Expr.Ref(_, _)) => true
          case _                                       => false
        },
        result.workflow.steps.exists {
          case Step.Construct(_, Expr.Literal(value)) => value.asObject.flatMap(_.get("_hostType")).flatMap(_.asString).contains("filter_variant_membership_v1")
          case _                                      => false
        },
        !result.workflow.steps.exists {
          case Step.Generate(_, Catalog.FilterName, _) => true
          case _                                       => false
        },
        result.actionTrace.exists(_.startsWith("filter-engine:jev:2:attempt=1")),
        bodies.exists(_.toJson.contains("\"filterMode\":\"field_relevance\"")),
        bodies.exists(_.toJson.contains("\"filterMode\":\"variant_recall\"")),
      )
    },
    test("evidence-only Jev loop uses exact input, Jev filtering, and no inner LLM") {
      val inputSchema = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("query" -> string),
        "required" -> Json.Arr(Json.Str("query")),
        "additionalProperties" -> Json.Bool(false),
      )
      val item = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("id" -> string),
        "required" -> Json.Arr(Json.Str("id")),
        "additionalProperties" -> Json.Bool(false),
      )
      val enumerateOutput = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("items" -> Json.Obj("type" -> Json.Str("array"), "items" -> item)),
        "required" -> Json.Arr(Json.Str("items")),
        "additionalProperties" -> Json.Bool(false),
      )
      val inspectInput = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("id" -> string),
        "required" -> Json.Arr(Json.Str("id")),
        "additionalProperties" -> Json.Bool(false),
      )
      val definitions = Chunk(
        ToolDefinition(ToolName("enumerate"), Some("enumerate records for a query"), inputSchema, Some(enumerateOutput)),
        ToolDefinition(ToolName("inspect"), Some("inspect one record"), inspectInput, Some(output)),
      )
      val source = Vector.tabulate(3)(index => Json.Obj("id" -> Json.Str(s"item-$index")))
      import AppTypeSafeAIMock.ScriptedResponse
      for
        observed <- AppTypeSafeAIMock.scriptedObserved(
          ScriptedResponse.Choice("c000"),
          ScriptedResponse.Choice("c000"),
          ScriptedResponse.Nouls(Vector(0.9)),
          ScriptedResponse.Nouls(Vector(0.1, 0.9, 0.1)),
          ScriptedResponse.Choice("c000"),
          ScriptedResponse.Choice("c000"),
        )
        calls <- Ref.make(Vector.empty[(String, Json.Obj)])
        operations <- McpOperationInvoker.make(definitions, new RawMcpInvoker:
          def call(operation: String, arguments: Json.Obj) =
            calls.update(_ :+ (operation -> arguments)) *> (operation match
              case "enumerate" => ZIO.succeed(CallToolResult(structuredContent = Some(Json.Obj("items" -> Json.Arr(source*)))))
              case "inspect" => ZIO.succeed(CallToolResult(structuredContent = Some(Json.Obj(
                "value" -> Json.Str(s"detail:${arguments.get("id").flatMap(_.asString).getOrElse("")}"),
              ))))
              case other => ZIO.fail(IllegalStateException(s"unexpected operation $other")))
        )
        catalog <- McpCatalog.fromDefinitions(definitions)
        result <- GenericOrchestrator.runEvidence(
          "inspect the relevant record",
          catalog,
          Json.Obj("query" -> Json.Str("target")),
          inputSchema,
          operations,
          OrchestrationConfig(jevFilterVariantThreshold = 10, maxOperations = 2, maxIterations = 4),
        ).provideLayer(observed.layer)
        invoked <- calls.get
      yield assertTrue(
        invoked.map(_._1) == Vector("enumerate", "inspect"),
        invoked.headOption.flatMap(_._2.get("query")).flatMap(_.asString).contains("target"),
        invoked.lift(1).flatMap(_._2.get("id")).flatMap(_.asString).contains("item-1"),
        result.evidence.elements.size == 2,
        result.evidence.toJson.contains("HostFiltered"),
        result.evidence.toJson.contains("detail:item-1"),
        result.metrics.jevLogicalTurns == 4,
        result.metrics.jevFilterCalls == 2,
        result.metrics.extractCalls == 0,
        result.metrics.filterCalls == 0,
        result.metrics.summaryCalls == 0,
        result.metrics.mcpPhysicalCalls == 2,
        result.actionTrace.lastOption.contains("return-evidence"),
        !result.workflow.steps.exists(_.isInstanceOf[Step.Generate]),
        result.workflow.result.isInstanceOf[Expr.Arr],
      )
    },
    test("evidence-only Jev loop fails visibly instead of using an LLM above capacity") {
      val inputSchema = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj(),
        "additionalProperties" -> Json.Bool(false),
      )
      val item = Json.Obj("type" -> Json.Str("object"), "properties" -> Json.Obj("id" -> string))
      val enumerateOutput = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("items" -> Json.Obj("type" -> Json.Str("array"), "items" -> item)),
      )
      val definitions = Chunk(ToolDefinition(
        ToolName("enumerate"), Some("enumerate records"), inputSchema, Some(enumerateOutput),
      ))
      for
        observed <- AppTypeSafeAIMock.scriptedObserved(
          AppTypeSafeAIMock.ScriptedResponse.Choice("c000"),
          AppTypeSafeAIMock.ScriptedResponse.Choice("c000"),
        )
        operations <- McpOperationInvoker.make(definitions, new RawMcpInvoker:
          def call(operation: String, arguments: Json.Obj) = ZIO.succeed(CallToolResult(
            structuredContent = Some(Json.Obj("items" -> Json.Arr(
              Json.Obj("id" -> Json.Str("a")),
              Json.Obj("id" -> Json.Str("b")),
            ))),
          ))
        )
        catalog <- McpCatalog.fromDefinitions(definitions)
        exit <- GenericOrchestrator.runEvidence(
          "return relevant records",
          catalog,
          Json.Obj(),
          inputSchema,
          operations,
          OrchestrationConfig(jevFilterVariantThreshold = 0, maxOperations = 1, maxIterations = 3),
        ).provideLayer(observed.layer).exit
      yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[JevFilterCapacityExceeded]),
      )
    },
    test("no-plan returns a failed MCP invocation to Jev and continues with another action") {
      val fail = ToolDefinition(ToolName("a_fail"), Some("always fails"), emptyInput, Some(output))
      val good = ToolDefinition(ToolName("b_good"), Some("returns evidence"), emptyInput, Some(output))
      for
        observed <- AppTypeSafeAIMock.choicesObserved("c000", "c000", "c000", "c000")
        calls <- Ref.make(Vector.empty[String])
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          def summarize(prompt: String, values: Json.Arr) = ZIO.succeed(LlmResult("recovered after MCP failure"))
        )
        operations <- McpOperationInvoker.make(Chunk(fail, good), new RawMcpInvoker:
          def call(operation: String, arguments: Json.Obj) =
            calls.update(_ :+ operation) *> (if operation == "a_fail" then ZIO.fail(IllegalStateException("synthetic failure"))
            else ZIO.succeed(CallToolResult(structuredContent = Some(Json.Obj("value" -> Json.Str("ok"))))))
        )
        result <- McpOrchestrator.runMode(
          "recover from one operation", Chunk(fail, good), operations, llm, OrchestrationMode.NoPlan,
          OrchestrationConfig(jevFilterVariantThreshold = 0, maxOperations = 2, maxRecoveries = 0, maxIterations = 4),
        ).provideLayer(observed.layer)
        invoked <- calls.get
        bodies <- observed.requestBodies
      yield assertTrue(
        invoked == Vector("a_fail", "b_good"),
        result.finalText == "recovered after MCP failure",
        result.actionTrace.contains("invoke-failed:a_fail:direct"),
        !result.workflow.steps.exists { case Step.Call(_, "a_fail", _) => true; case _ => false },
        result.workflow.steps.exists { case Step.Call(_, "b_good", _) => true; case _ => false },
        bodies.lift(1).exists(body => body.toJson.contains("\"lastActionFailure\"") && body.toJson.contains("synthetic failure")),
      )
    },
    test("plan and no-plan produce equivalent evidence and summary with identical metric keys") {
      for
        plan <- run(OrchestrationMode.Plan)
        noPlan <- run(OrchestrationMode.NoPlan)
      yield assertTrue(
        plan.calls == 1,
        noPlan.calls == 1,
        plan.evidence == noPlan.evidence,
        plan.result.finalText == noPlan.result.finalText,
        plan.result.metrics.toJson.fields.map(_._1).toSet == noPlan.result.metrics.toJson.fields.map(_._1).toSet,
        plan.result.metrics.jevLogicalTurns == 3,
        noPlan.result.metrics.jevLogicalTurns == 3,
        plan.result.metrics.mcpPhysicalCalls == 1,
        noPlan.result.metrics.mcpPhysicalCalls == 1,
        plan.result.metrics.summaryCalls == 1,
        noPlan.result.metrics.summaryCalls == 1,
      )
    },
    test("plan completes before execution and reports checkpoint retry without a false replan") {
      for
        events <- Ref.make(Vector.empty[String])
        attempts <- Ref.make(0)
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          def summarize(prompt: String, values: Json.Arr) = ZIO.succeed(LlmResult("recovered answer"))
        )
        operations <- McpOperationInvoker.make(Chunk(definition), new RawMcpInvoker:
          def call(operation: String, arguments: Json.Obj) = for
            attempt <- attempts.updateAndGet(_ + 1)
            _ <- events.update(_ :+ s"mcp:$attempt")
          yield if attempt == 1 then CallToolResult(isError = Some(true))
          else CallToolResult(structuredContent = Some(Json.Obj("value" -> Json.Str("evidence"))))
        )
        observer: LoopObserver[GenericPlanner.PlanningError, Workflow] = LoopObserver.make:
          case LoopObservation.Completion(_) => events.update(_ :+ "plan-complete")
          case _                             => ZIO.unit
        result <- McpOrchestrator.runMode(
          "fetch an answer", Chunk(definition), operations, llm, OrchestrationMode.Plan,
          OrchestrationConfig(jevFilterVariantThreshold = 0, maxOperations = 1, maxIterations = 3, maxRecoveries = 1),
          planningObserver = observer,
        ).provideLayer(AppTypeSafeAIMock.choices("c000", "c000", "c000"))
        seen <- events.get
      yield assertTrue(
        seen == Vector("plan-complete", "mcp:1", "mcp:2"),
        result.execution.metrics.recoveries == 1,
        result.execution.metrics.replans == 0,
        result.metrics.recoveries == 1,
        result.metrics.replans == 0,
        result.metrics.mcpPhysicalCalls == 2,
        result.metrics.mcpPhysicalFailures == 1,
        result.metrics.mcpPhysicalSuccesses == 1,
        result.actionTrace == Vector("checkpoint-continuation:1"),
      )
    },
    test("maxRecoveries is a run-wide bound in both plan and no-plan modes") {
      val tokenOutput = Json.Obj(
        "type" -> Json.Str("object"), "properties" -> Json.Obj("token" -> string),
        "required" -> Json.Arr(Json.Str("token")), "additionalProperties" -> Json.Bool(false),
      )
      val tokenInput = Json.Obj(
        "type" -> Json.Str("object"), "properties" -> Json.Obj("token" -> string),
        "required" -> Json.Arr(Json.Str("token")), "additionalProperties" -> Json.Bool(false),
      )
      val alpha = ToolDefinition(ToolName("alpha"), Some("produce a token"), emptyInput, Some(tokenOutput))
      val beta = ToolDefinition(ToolName("beta"), Some("consume a token"), tokenInput, Some(output))

      def boundedRun(mode: OrchestrationMode) = for
        attempts <- Ref.make(Map.empty[String, Int])
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          def summarize(prompt: String, values: Json.Arr) = ZIO.succeed(LlmResult("must not summarize"))
        )
        operations <- McpOperationInvoker.make(Chunk(alpha, beta), new RawMcpInvoker:
          def call(operation: String, arguments: Json.Obj) = for
            counts <- attempts.updateAndGet(current => current.updated(operation, current.getOrElse(operation, 0) + 1))
          yield if counts(operation) == 1 then CallToolResult(isError = Some(true))
          else if operation == "alpha" then CallToolResult(structuredContent = Some(Json.Obj("token" -> Json.Str("t"))))
          else CallToolResult(structuredContent = Some(Json.Obj("value" -> Json.Str("done"))))
        )
        result <- McpOrchestrator.runMode(
          "use both capabilities", Chunk(alpha, beta), operations, llm, mode,
          OrchestrationConfig(jevFilterVariantThreshold = 0, maxOperations = 2, maxIterations = 4, maxParallelism = 1, maxRecoveries = 1),
        ).provideLayer(AppTypeSafeAIMock.choices("c000", "c000", "c000", "c000")).either
        counts <- attempts.get
      yield result -> counts

      for
        plan <- boundedRun(OrchestrationMode.Plan)
        noPlan <- boundedRun(OrchestrationMode.NoPlan)
      yield assertTrue(
        plan._1.isLeft,
        noPlan._1.isRight,
        plan._2 == Map("alpha" -> 2, "beta" -> 1),
        noPlan._2 == Map("alpha" -> 2, "beta" -> 1),
        plan._2.values.sum == 3,
        noPlan._2.values.sum == 3,
        noPlan._1.toOption.exists(_.actionTrace.contains("invoke-failed:beta:direct")),
      )
    },
    test("checkpoint recovery retries only failed MCP work and does not replay confirmed fanout items") {
      val source = Json.Arr(Json.Obj("id" -> Json.Str("a")), Json.Obj("id" -> Json.Str("b")))
      val workflow = Workflow(Vector(
        Step.Call("confirmed", "first", Expr.Obj(Vector.empty)),
        Step.Construct("source", Expr.Literal(source)),
        Step.FanOut(
          "fan", Expr.Ref("source"), "detail", Expr.Obj(Vector("id" -> Expr.Item(List("id")))),
          FanOutAuthorization.StaticallyBound(2),
        ),
      ), Expr.Ref("fan"))
      for
        counts <- Ref.make(Map.empty[String, Int])
        report <- WorkflowRuntime.execute(workflow, Json.Obj(), new OperationInvoker:
          def call(operation: String, arguments: Json.Obj) =
            val key = if operation == "first" then "first" else arguments.get("id").flatMap(_.asString).get
            counts.updateAndGet(map => map.updated(key, map.getOrElse(key, 0) + 1)).flatMap: updated =>
              if key == "b" && updated(key) == 1 then ZIO.fail(RuntimeException("retry b"))
              else ZIO.succeed(Json.Obj("value" -> Json.Str(key)))
        , policy = ExecutionPolicy(fanOutLimit = Some(2), maxRecoveries = 1))
        seen <- counts.get
      yield assertTrue(
        seen == Map("first" -> 1, "a" -> 1, "b" -> 2),
        report.metrics.recoveries == 1,
        report.metrics.replans == 0,
        report.output.asArray.exists(_.size == 2),
      )
    },
    test("no-plan isolates a replacement filter checkpoint across two unsafe arrays") {
      def itemSchema(extraField: String): Json.Obj = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("id" -> string, "origin" -> string, extraField -> string),
        "required" -> Json.Arr(Json.Str("id"), Json.Str("origin"), Json.Str(extraField)),
        "additionalProperties" -> Json.Bool(false),
      )
      val aItem = itemSchema("aOnly")
      val bItem = itemSchema("bOnly")
      val enumerateOutput = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj(
          "aEntries" -> Json.Obj("type" -> Json.Str("array"), "items" -> aItem),
          "bEntries" -> Json.Obj("type" -> Json.Str("array"), "items" -> bItem),
        ),
        "required" -> Json.Arr(Json.Str("aEntries"), Json.Str("bEntries")),
        "additionalProperties" -> Json.Bool(false),
      )
      val inspectInput = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("id" -> string, "origin" -> string),
        "required" -> Json.Arr(Json.Str("id"), Json.Str("origin")),
        "additionalProperties" -> Json.Bool(false),
      )
      val enumerate = ToolDefinition(ToolName("enumerate_both"), Some("enumerate A and B records"), emptyInput, Some(enumerateOutput))
      val inspect = ToolDefinition(ToolName("inspect"), Some("inspect one record"), inspectInput, Some(output))
      val definitions = Chunk(enumerate, inspect)
      val aSource = Vector.tabulate(4)(index => Json.Obj(
        "id" -> Json.Str("target"), "origin" -> Json.Str("A"), "aOnly" -> Json.Str(s"a-$index"),
      ))
      val bSource = Vector.tabulate(5)(index => Json.Obj(
        "id" -> Json.Str(if index >= 3 then "target" else s"other-$index"),
        "origin" -> Json.Str("B"), "bOnly" -> Json.Str(s"b-$index"),
      ))

      for
        observed <- AppTypeSafeAIMock.choicesObserved("c000", "c000", "c001", "c000", "c002", "c002", "c000")
        filterCalls <- Ref.make(0)
        filterRequests <- Ref.make(Vector.empty[PredicateFilter.SynthesisRequest])
        inspectCalls <- Ref.make(Vector.empty[(String, String)])
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          override def synthesizeFilter(request: PredicateFilter.SynthesisRequest) =
            filterRequests.update(_ :+ request) *> filterCalls.updateAndGet(_ + 1).map:
              case 1 => LlmResult(filterAtom("eq", Some("target")))
              case 2 => LlmResult(filterAtom("any"))
              case 3 => LlmResult(filterAtom("eq", Some("target")))
              case other => throw IllegalStateException(s"unexpected filter synthesis $other")
          def summarize(prompt: String, values: Json.Arr) = ZIO.succeed(LlmResult("B-only answer"))
        )
        operations <- McpOperationInvoker.make(definitions, new RawMcpInvoker:
          def call(operation: String, arguments: Json.Obj) = operation match
            case "enumerate_both" => ZIO.succeed(CallToolResult(structuredContent = Some(Json.Obj(
              "aEntries" -> Json.Arr(aSource*), "bEntries" -> Json.Arr(bSource*),
            ))))
            case "inspect" =>
              val id = arguments.get("id").flatMap(_.asString).get
              val origin = arguments.get("origin").flatMap(_.asString).get
              inspectCalls.update(_ :+ (origin -> id)).as(CallToolResult(structuredContent = Some(Json.Obj("value" -> Json.Str(s"$origin:$id")))))
        )
        result <- McpOrchestrator.runMode(
          "inspect the target B record", definitions, operations, llm, OrchestrationMode.NoPlan,
          OrchestrationConfig(jevFilterVariantThreshold = 0, maxOperations = 2, hostGuard = 2, maxFilterAttempts = 2, maxRecoveries = 1, maxIterations = 7),
        ).provideLayer(observed.layer)
        bodies <- observed.requestBodies
        requests <- filterRequests.get
        generated <- filterCalls.get
        inspected <- inspectCalls.get
      yield
        val aPending = bodies(2).toJson
        val bRejected = bodies(3).toJson
        val bPending = bodies(3).asObject.flatMap(_.get("state")).flatMap(_.asObject)
          .flatMap(_.get("pendingFilterOutcome")).flatMap(_.asObject)
        val bRetry = bodies(3).asObject.flatMap(_.get("questions")).flatMap(_.asObject)
          .flatMap(_.get("next_action")).flatMap(_.asObject)
          .flatMap(_.get("criteria")).flatMap(_.asObject)
          .flatMap(_.get("c000")).flatMap(_.asObject)
        val bRecovered = bodies(4).toJson
        val allRequests = bodies.map(_.toJson).mkString("\n")
        val filterId = result.workflow.steps.collectFirst { case Step.Filter(id, _, _, _, _) => id }
        val filterActions = result.actionTrace.filter(entry => entry.startsWith("filter:") || entry.startsWith("filter-recovery:"))
        assertTrue(
          aPending.contains("\"classification\":\"TooBroad\"") && aPending.contains("\"sourceCount\":4") && aPending.contains("\"value\":\"target\""),
          bPending.flatMap(_.get("evaluation")).contains(Json.Null),
          bPending.flatMap(_.get("synthesisRejection")).flatMap(_.asObject).flatMap(_.get("kind")).flatMap(_.asString).contains("invalid_predicate"),
          bPending.flatMap(_.get("sourceCount")).contains(Json.Num(5)),
          bRetry.flatMap(_.get("transition")).flatMap(_.asString).contains("filter_recovery"),
          bRetry.flatMap(_.get("action")).flatMap(_.asString).contains("retry"),
          bRetry.flatMap(_.get("sourceCount")).contains(Json.Num(5)),
          bRetry.flatMap(_.get("nextAttempt")).contains(Json.Num(2)),
          bRetry.exists(!_.contains("priorPredicate")),
          bRetry.exists(!_.contains("outcomeDiagnostics")),
          !bRejected.contains("\"value\":\"target\""),
          requests.map(_.itemSchema) == Vector(aItem, bItem, bItem),
          requests(2).priorPredicate.isEmpty,
          requests(2).outcomeDiagnostics.isEmpty,
          requests(2).priorRejection.exists(_.kind == PredicateFilter.SynthesisRejectionKind.InvalidPredicate),
          bRecovered.contains("\"mode\":\"FanOut\""),
          inspected == Vector("B" -> "target", "B" -> "target"),
          filterId.flatMap(result.execution.values.get).flatMap(_.asArray).exists(_.toVector == Vector(bSource(3), bSource(4))),
          result.workflow.steps.count { case Step.Generate(_, Catalog.FilterName, _) => true; case _ => false } == 1,
          result.workflow.steps.exists { case Step.FanOut(_, _, "inspect", _, FanOutAuthorization.ReadyFiltered) => true; case _ => false },
          generated == 3,
          requests.size == 3,
          filterActions.size == 3,
          result.internalLlm.filterCount == 3,
          result.execution.metrics.recoveries == 1,
          result.execution.metrics.replans == 0,
          result.actionTrace.exists(_.contains("filter-rejection:invalid_predicate:attempt=1")),
          result.actionTrace.exists(_.contains("filter-outcome:Ready:2/5:attempt=2")),
          !allRequests.contains("hostGuard"),
          !allRequests.contains("fanOutLimit"),
          !allRequests.contains("parentSource"),
          !allRequests.contains("\"source\":["),
          !allRequests.contains("\"matches\":["),
        )
    },
    test("no-plan exposes one invalid synthesis, waits for explicit recovery, then enables fanout only after Ready") {
      val item = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("id" -> string),
        "required" -> Json.Arr(Json.Str("id")),
        "additionalProperties" -> Json.Bool(false),
      )
      val enumerateOutput = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("entries" -> Json.Obj("type" -> Json.Str("array"), "items" -> item)),
        "required" -> Json.Arr(Json.Str("entries")),
        "additionalProperties" -> Json.Bool(false),
      )
      val inspectInput = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("id" -> string),
        "required" -> Json.Arr(Json.Str("id")),
        "additionalProperties" -> Json.Bool(false),
      )
      val enumerate = ToolDefinition(ToolName("enumerate"), Some("enumerate records"), emptyInput, Some(enumerateOutput))
      val inspect = ToolDefinition(ToolName("inspect"), Some("inspect one record"), inspectInput, Some(output))
      val definitions = Chunk(enumerate, inspect)
      val source = Vector.tabulate(5)(index => Json.Obj("id" -> Json.Str(s"private-$index")))

      for
        observed <- AppTypeSafeAIMock.choicesObserved("c000", "c000", "c000", "c001", "c001", "c000")
        filterCalls <- Ref.make(0)
        inspectCalls <- Ref.make(Vector.empty[String])
        requests <- Ref.make(Vector.empty[PredicateFilter.SynthesisRequest])
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          override def synthesizeFilter(request: PredicateFilter.SynthesisRequest) =
            requests.update(_ :+ request) *> filterCalls.updateAndGet(_ + 1).map:
              case 1 => LlmResult(filterAtom("any"))
              case 2 => LlmResult(filterAtom("eq", Some("private-4")))
              case other => throw IllegalStateException(s"unexpected filter synthesis $other")
          def summarize(prompt: String, values: Json.Arr) = ZIO.succeed(LlmResult("explicit recovery answer"))
        )
        operations <- McpOperationInvoker.make(definitions, new RawMcpInvoker:
          def call(operation: String, arguments: Json.Obj) = operation match
            case "enumerate" => ZIO.succeed(CallToolResult(structuredContent = Some(Json.Obj("entries" -> Json.Arr(source*)))))
            case "inspect" =>
              val id = arguments.get("id").flatMap(_.asString).get
              inspectCalls.update(_ :+ id).as(CallToolResult(structuredContent = Some(Json.Obj("value" -> Json.Str(s"detail:$id")))))
        )
        result <- McpOrchestrator.runMode(
          "inspect one matching record", definitions, operations, llm, OrchestrationMode.NoPlan,
          OrchestrationConfig(jevFilterVariantThreshold = 0, maxOperations = 2, hostGuard = 2, maxFilterAttempts = 2, maxRecoveries = 1, maxIterations = 6),
        ).provideLayer(observed.layer)
        bodies <- observed.requestBodies
        generated <- filterCalls.get
        filterRequests <- requests.get
        inspected <- inspectCalls.get
      yield
        val rejectedRequest = bodies(2).toJson
        val readyRequest = bodies(3).toJson
        val preReady = bodies.take(3).map(_.toJson)
        val allRequests = bodies.map(_.toJson).mkString("\n")
        assertTrue(
          generated == 2,
          filterRequests.size == 2,
          filterRequests(1).priorPredicate.isEmpty,
          filterRequests(1).outcomeDiagnostics.isEmpty,
          filterRequests(1).priorRejection.exists(_.kind == PredicateFilter.SynthesisRejectionKind.InvalidPredicate),
          rejectedRequest.contains("\"evaluation\":null"),
          rejectedRequest.contains("\"synthesisRejection\":{\"kind\":\"invalid_predicate\""),
          rejectedRequest.contains("\"attemptsUsed\":1"),
          rejectedRequest.contains("\"transition\":\"filter_recovery\""),
          rejectedRequest.contains("\"action\":\"retry\""),
          rejectedRequest.contains("\"nextAttempt\":2"),
          preReady.forall(!_.contains("\"mode\":\"FanOut\"")),
          readyRequest.contains("\"mode\":\"FanOut\""),
          inspected == Vector("private-4"),
          result.internalLlm.filterCount == 2,
          result.execution.metrics.generativeCalls == 3,
          result.execution.metrics.recoveries == 1,
          result.execution.metrics.replans == 0,
          result.actionTrace.exists(_.startsWith("filter-rejection:invalid_predicate:attempt=1")),
          !result.actionTrace.exists(_.contains("Unsupported operator")),
          result.workflow.steps.exists { case Step.FanOut(_, _, "inspect", _, FanOutAuthorization.ReadyFiltered) => true; case _ => false },
          !allRequests.contains("hostGuard"),
          !allRequests.contains("fanOutLimit"),
          !allRequests.contains("parentSource"),
          !allRequests.contains("\"source\":["),
          !allRequests.contains("\"matches\":["),
          !allRequests.contains("private-4"),
        )
    },
    test("no-plan keeps replacements inside the refinement source after accepted NoMatches") {
      val item = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("id" -> string),
        "required" -> Json.Arr(Json.Str("id")),
        "additionalProperties" -> Json.Bool(false),
      )
      val entries = Json.Obj("type" -> Json.Str("array"), "items" -> item)
      val enumerateOutput = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("entries" -> entries),
        "required" -> Json.Arr(Json.Str("entries")),
        "additionalProperties" -> Json.Bool(false),
      )
      val inspectInput = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("id" -> string),
        "required" -> Json.Arr(Json.Str("id")),
        "additionalProperties" -> Json.Bool(false),
      )
      val enumerate = ToolDefinition(ToolName("enumerate"), Some("enumerate symbols"), emptyInput, Some(enumerateOutput))
      val inspect = ToolDefinition(ToolName("inspect"), Some("inspect one symbol"), inspectInput, Some(output))
      val definitions = Chunk(enumerate, inspect)
      val source = Vector.tabulate(9): index =>
        Json.Obj("id" -> Json.Str(if index < 4 then s"keep-$index" else s"outside-$index"))

      for
        observed <- AppTypeSafeAIMock.choicesObserved("c000", "c000", "c000", "c000", "c000", "c001", "c001", "c000")
        inspectCalls <- Ref.make(Vector.empty[String])
        filterRequests <- Ref.make(Vector.empty[PredicateFilter.SynthesisRequest])
        inspectionsAtFilter <- Ref.make(Vector.empty[Int])
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          override def synthesizeFilter(request: PredicateFilter.SynthesisRequest) = for
            calls <- inspectCalls.get
            _ <- inspectionsAtFilter.update(_ :+ calls.size)
            requests <- filterRequests.updateAndGet(_ :+ request)
          yield LlmResult(requests.size match
            case 1 => filterAtom("startsWith", Some("keep-"))
            case 2 => filterAtom("eq", Some("absent"))
            case 3 => filterAtom("eq", Some("outside-8"))
            case 4 => filterAtom("eq", Some("keep-3"))
            case other => throw IllegalStateException(s"unexpected filter synthesis $other")
          )
          def summarize(prompt: String, values: Json.Arr) = ZIO.succeed(LlmResult("recovered filtered answer"))
        )
        operations <- McpOperationInvoker.make(definitions, new RawMcpInvoker:
          def call(operation: String, arguments: Json.Obj) = operation match
            case "enumerate" => ZIO.succeed(CallToolResult(structuredContent = Some(Json.Obj("entries" -> Json.Arr(source*)))))
            case "inspect" =>
              val id = arguments.get("id").flatMap(_.asString).get
              inspectCalls.update(_ :+ id).as(CallToolResult(structuredContent = Some(Json.Obj("value" -> Json.Str(s"detail:$id")))))
        )
        result <- McpOrchestrator.runMode(
          "inspect the matching symbol", definitions, operations, llm, OrchestrationMode.NoPlan,
          OrchestrationConfig(jevFilterVariantThreshold = 0, maxOperations = 2, hostGuard = 2, maxFilterAttempts = 4, maxRecoveries = 3, maxIterations = 8),
        ).provideLayer(observed.layer)
        requests <- filterRequests.get
        callsWhenFiltering <- inspectionsAtFilter.get
        inspected <- inspectCalls.get
        bodies <- observed.requestBodies
      yield
        val tooBroadState = bodies.lift(2).flatMap(_.asObject).flatMap(_.get("state"))
        val refinementNoMatchesState = bodies.lift(3).flatMap(_.asObject).flatMap(_.get("state"))
        val outsideNoMatchesState = bodies.lift(4).flatMap(_.asObject).flatMap(_.get("state"))
        val readyRequest = bodies.lift(5).map(_.toJson).getOrElse("")
        val preReadyRequests = bodies.take(5).map(_.toJson)
        val allRequests = bodies.map(_.toJson).mkString("\n")
        val filterId = result.workflow.steps.collectFirst { case Step.Filter(id, _, _, _, _) => id }
        assertTrue(
          tooBroadState.exists(state => state.toJson.contains("\"classification\":\"TooBroad\"") && state.toJson.contains("\"sourceCount\":9") && state.toJson.contains("\"matchCount\":4") && state.toJson.contains("\"attemptsUsed\":1")),
          bodies(2).toJson.contains("\"transition\":\"filter_recovery\"") && bodies(2).toJson.contains("\"action\":\"refine\"") && bodies(2).toJson.contains("\"nextAttempt\":2"),
          refinementNoMatchesState.exists(state => state.toJson.contains("\"classification\":\"NoMatches\"") && state.toJson.contains("\"sourceCount\":4") && state.toJson.contains("\"matchCount\":0") && state.toJson.contains("\"attemptsUsed\":2")),
          bodies(3).toJson.contains("\"transition\":\"filter_recovery\"") && bodies(3).toJson.contains("\"action\":\"replace\"") && bodies(3).toJson.contains("\"nextAttempt\":3"),
          outsideNoMatchesState.exists(state => state.toJson.contains("\"classification\":\"NoMatches\"") && state.toJson.contains("\"sourceCount\":4") && state.toJson.contains("\"matchCount\":0") && state.toJson.contains("\"attemptsUsed\":3")),
          bodies(4).toJson.contains("\"transition\":\"filter_recovery\"") && bodies(4).toJson.contains("\"action\":\"replace\"") && bodies(4).toJson.contains("\"nextAttempt\":4"),
          preReadyRequests.forall(!_.contains("\"mode\":\"FanOut\"")),
          readyRequest.contains("\"mode\":\"FanOut\""),
          result.workflow.steps.exists { case Step.FanOut(_, _, "inspect", _, FanOutAuthorization.ReadyFiltered) => true; case _ => false },
          callsWhenFiltering == Vector(0, 0, 0, 0),
          inspected == Vector("keep-3"),
          requests.size == 4,
          requests.flatMap(_.outcomeDiagnostics.map(d => (d.classification, d.sourceCount, d.matchCount))) ==
            Vector(("TooBroad", 9, 4), ("NoMatches", 4, 0), ("NoMatches", 4, 0)),
          filterId.flatMap(result.execution.values.get).flatMap(_.asArray).exists(_.toVector == Vector(source(3))),
          result.workflow.steps.count { case Step.Generate(_, Catalog.FilterName, _) => true; case _ => false } == 1,
          result.workflow.steps.count(_.isInstanceOf[Step.Filter]) == 1,
          result.internalLlm.filterCount == 4,
          result.execution.metrics.recoveries == 3,
          result.execution.metrics.replans == 0,
          result.finalText == "recovered filtered answer",
          !allRequests.contains("hostGuard"),
          !allRequests.contains("fanOutLimit"),
          !allRequests.contains("keep-3"),
        )
    },
    test("no-plan independently exhausts filter-attempt and run-wide recovery candidate budgets") {
      val item = Json.Obj(
        "type" -> Json.Str("object"), "properties" -> Json.Obj("id" -> string),
        "required" -> Json.Arr(Json.Str("id")), "additionalProperties" -> Json.Bool(false),
      )
      val enumerateOutput = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("entries" -> Json.Obj("type" -> Json.Str("array"), "items" -> item)),
        "required" -> Json.Arr(Json.Str("entries")), "additionalProperties" -> Json.Bool(false),
      )
      val enumerate = ToolDefinition(ToolName("enumerate"), Some("enumerate symbols"), emptyInput, Some(enumerateOutput))
      val source = Vector.tabulate(9)(index => Json.Obj("id" -> Json.Str(s"budget-$index")))

      def budgetRun(maxFilterAttempts: Int, maxRecoveries: Int) = for
        observed <- AppTypeSafeAIMock.choicesObserved("c000", "c000", "c000", "c000", "c000")
        filterCalls <- Ref.make(0)
        summaryEvidence <- Ref.make(Json.Arr())
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          override def synthesizeFilter(request: PredicateFilter.SynthesisRequest) =
            filterCalls.updateAndGet(_ + 1).map:
              case 1 => LlmResult(filterAtom("exists"))
              case 2 => LlmResult(filterAtom("eq", Some("absent")))
              case other => throw IllegalStateException(s"unexpected filter synthesis $other")
          def summarize(prompt: String, values: Json.Arr) =
            summaryEvidence.set(values).as(LlmResult("budget exhausted safely"))
        )
        operations <- McpOperationInvoker.make(Chunk(enumerate), new RawMcpInvoker:
          def call(operation: String, arguments: Json.Obj) =
            ZIO.succeed(CallToolResult(structuredContent = Some(Json.Obj("entries" -> Json.Arr(source*)))))
        )
        result <- McpOrchestrator.runMode(
          "inspect a symbol", Chunk(enumerate), operations, llm, OrchestrationMode.NoPlan,
          OrchestrationConfig(jevFilterVariantThreshold = 0, maxOperations = 1, hostGuard = 2, maxFilterAttempts = maxFilterAttempts, maxRecoveries = maxRecoveries, maxIterations = 5),
        ).provideLayer(observed.layer)
        bodies <- observed.requestBodies
        calls <- filterCalls.get
        summarized <- summaryEvidence.get
      yield (result, bodies, calls, summarized)

      for
        attemptLimited <- budgetRun(maxFilterAttempts = 2, maxRecoveries = 5)
        recoveryLimited <- budgetRun(maxFilterAttempts = 5, maxRecoveries = 1)
      yield
        def exhausted(run: (OrchestrationResult, Vector[Json], Int, Json.Arr)): Boolean =
          val terminalOutcomeRequest = run._2(3).toJson
          terminalOutcomeRequest.contains("\"classification\":\"NoMatches\"") &&
            !terminalOutcomeRequest.contains("\"transition\":\"filter_recovery\"") &&
            run._1.finalText == "budget exhausted safely" &&
            run._1.execution.metrics.recoveries == 1 &&
            run._1.execution.metrics.replans == 0 &&
            run._1.planningTurns.size == 5 &&
            run._3 == 2 &&
            run._4.elements.isEmpty
        assertTrue(exhausted(attemptLimited), exhausted(recoveryLimited))
    },
    test("no-plan promotes singleton wrappers and filters the nested record array") {
      def obj(required: Vector[String] = Vector.empty, fields: (String, Json)*): Json.Obj = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj(fields*),
        "required" -> Json.Arr(required.map(Json.Str(_))*),
        "additionalProperties" -> Json.Bool(false),
      )
      def array(item: Json.Obj): Json.Obj = Json.Obj("type" -> Json.Str("array"), "items" -> item)

      val artifact = obj(Vector("groupId", "artifactId"), "groupId" -> string, "artifactId" -> string)
      val symbol = obj(Vector("fqn", "link"), "fqn" -> string, "link" -> string)
      val searchOutput = obj(Vector("results"), "results" -> array(artifact))
      val latestOutput = obj(Vector("result"), "result" -> string)
      val listOutput = obj(Vector("result"), "result" -> array(symbol))
      val detailOutput = obj(Vector("text"), "text" -> string)
      val search = ToolDefinition(ToolName("a_search"), Some("search generic artifacts"), obj(Vector("query"), "query" -> string), Some(searchOutput))
      val latest = ToolDefinition(ToolName("b_latest"), Some("resolve latest generic version"), obj(Vector("groupId", "artifactId"), "groupId" -> string, "artifactId" -> string), Some(latestOutput))
      val list = ToolDefinition(ToolName("c_list"), Some("list generic records"), obj(Vector("groupId", "artifactId", "version"), "groupId" -> string, "artifactId" -> string, "version" -> string), Some(listOutput))
      val detail = ToolDefinition(ToolName("d_detail"), Some("read one generic record"), obj(Vector("link"), "link" -> string), Some(detailOutput))
      val definitions = Chunk(search, latest, list, detail)
      val artifacts = Vector(
        Json.Obj("groupId" -> Json.Str("org.alpha"), "artifactId" -> Json.Str("alpha-data")),
        Json.Obj("groupId" -> Json.Str("org.target"), "artifactId" -> Json.Str("target-data")),
        Json.Obj("groupId" -> Json.Str("org.omega"), "artifactId" -> Json.Str("omega-data")),
      )
      val symbols = Vector(
        Json.Obj("fqn" -> Json.Str("sample.First"), "link" -> Json.Str("first.html")),
        Json.Obj("fqn" -> Json.Str("sample.Target"), "link" -> Json.Str("target.html")),
        Json.Obj("fqn" -> Json.Str("sample.Last"), "link" -> Json.Str("last.html")),
      )
      def predicateEq(path: String, value: String): Json.Obj = Json.Obj(
        "kind" -> Json.Str("atom"),
        "leaf" -> Json.Obj("op" -> Json.Str("eq"), "path" -> Json.Arr(Json.Str(path)), "value" -> Json.Str(value)),
      )

      for
        observed <- AppTypeSafeAIMock.choicesObserved("c000", "c000", "c000", "c001", "c000", "c001", "c001", "c000")
        calls <- Ref.make(Vector.empty[(String, Json.Obj)])
        filterRequests <- Ref.make(Vector.empty[PredicateFilter.SynthesisRequest])
        summaryEvidence <- Ref.make(Json.Arr())
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = request.targetTool match
            case "a_search" => ZIO.succeed(LlmResult(Json.Obj("query" -> Json.Str("generic lookup"))))
            case "c_list"   => ZIO.succeed(LlmResult(Json.Obj("version" -> Json.Str("9.9.9"))))
            case other      => ZIO.fail(IllegalStateException(s"unexpected extraction for $other: ${request.missingFields}"))
          override def synthesizeFilter(request: PredicateFilter.SynthesisRequest) =
            filterRequests.updateAndGet(_ :+ request).flatMap: seen =>
              if request.itemSchema == artifact then ZIO.succeed(LlmResult(predicateEq("artifactId", "target-data")))
              else if request.itemSchema == symbol then ZIO.succeed(LlmResult(predicateEq("fqn", "sample.Target")))
              else ZIO.fail(IllegalStateException(s"filter offered for unsafe wrapper schema: ${request.itemSchema.toJson}"))
          def summarize(prompt: String, values: Json.Arr) =
            summaryEvidence.set(values).as(LlmResult("nested singleton answer"))
        )
        operations <- McpOperationInvoker.make(definitions, new RawMcpInvoker:
          def call(operation: String, arguments: Json.Obj) =
            calls.update(_ :+ (operation -> arguments)) *> ZIO.succeed(operation match
              case "a_search" => CallToolResult(structuredContent = Some(Json.Obj("results" -> Json.Arr(artifacts*))))
              case "b_latest" => CallToolResult(structuredContent = Some(Json.Obj("result" -> Json.Str("9.9.9"))))
              case "c_list"   => CallToolResult(structuredContent = Some(Json.Obj("result" -> Json.Arr(symbols*))))
              case "d_detail" => CallToolResult(structuredContent = Some(Json.Obj("text" -> Json.Str(s"record:${arguments.get("link").flatMap(_.asString).get}"))))
            )
        )
        result <- McpOrchestrator.runMode(
          "find and inspect the target generic record", definitions, operations, llm, OrchestrationMode.NoPlan,
          OrchestrationConfig(jevFilterVariantThreshold = 0, maxOperations = 4, hostGuard = 2, maxIterations = 8),
        ).provideLayer(observed.layer)
        recorded <- calls.get
        requests <- filterRequests.get
        summarized <- summaryEvidence.get
        bodies <- observed.requestBodies
      yield
        val latestArgs = recorded.collectFirst { case ("b_latest", args) => args }
        val listArgs = recorded.collectFirst { case ("c_list", args) => args }
        val detailArgs = recorded.collect { case ("d_detail", args) => args }
        val latestStep = result.workflow.steps.collectFirst { case call @ Step.Call(_, "b_latest", _) => call }
        val listStep = result.workflow.steps.collectFirst { case fan @ Step.FanOut(_, _, "c_list", _, _) => fan }
        val symbolSynthesisId = result.workflow.steps.collectFirst {
          case Step.Filter(_, Expr.At(_, 0, List("result")), item, _, Expr.Ref(id, Nil)) if item == symbol => id
        }
        val symbolPredicatePath = symbolSynthesisId.flatMap(result.execution.values.get)
          .flatMap(_.asObject).flatMap(_.get("leaf")).flatMap(_.asObject).flatMap(_.get("path"))
          .flatMap(_.asArray).map(_.flatMap(_.asString).toVector)
        val requestText = bodies.map(_.toJson).mkString("\n")
        val summarizedText = summarized.toJson
        val postListRequest = bodies.lift(4).map(_.toJson).getOrElse("")
        assertTrue(
          result.finalText == "nested singleton answer",
          summarizedText.contains("sample.Target"),
          summarizedText.contains("record:target.html"),
          !summarizedText.contains("sample.First"),
          !summarizedText.contains("sample.Last"),
          requests.map(_.itemSchema) == Vector(artifact, symbol),
          requests.forall(_.evidence.exists(_.complete)),
          requests.map(_.evidence.map(_.variantCount)) == Vector(Some(3), Some(3)),
          requests.lastOption.exists(_.itemSchema == symbol),
          symbolPredicatePath.contains(Vector("fqn")),
          latestArgs.flatMap(_.get("groupId")).flatMap(_.asString).contains("org.target"),
          latestArgs.flatMap(_.get("artifactId")).flatMap(_.asString).contains("target-data"),
          listArgs.flatMap(_.get("groupId")).flatMap(_.asString).contains("org.target"),
          listArgs.flatMap(_.get("artifactId")).flatMap(_.asString).contains("target-data"),
          listArgs.flatMap(_.get("version")).flatMap(_.asString).contains("9.9.9"),
          detailArgs.flatMap(_.get("link").flatMap(_.asString)) == Vector("target.html"),
          latestStep.exists { case Step.Call(_, _, Expr.Obj(fields)) => fields.filter(entry => Set("groupId", "artifactId").contains(entry._1)).forall(_._2.isInstanceOf[Expr.At]); case _ => false },
          listStep.exists { case Step.FanOut(_, _, _, _, FanOutAuthorization.ReadyFiltered) => true; case _ => false },
          result.workflow.steps.exists { case Step.Filter(_, Expr.At(_, 0, List("result")), item, _, _) => item == symbol; case _ => false },
          result.workflow.steps.exists { case Step.FanOut(_, _, "d_detail", _, FanOutAuthorization.ReadyFiltered) => true; case _ => false },
          recorded.map(_._1) == Vector("a_search", "b_latest", "c_list", "d_detail"),
          result.metrics.mcpPhysicalCalls == 4,
          result.internalLlm.extractionCount == 2,
          result.internalLlm.filterCount == 2,
          postListRequest.contains("\"path\":\"/result\"") && postListRequest.contains("\"filterablePaths\":[\"/fqn\",\"/link\"]"),
          !postListRequest.contains("/result/fqn"),
          !requestText.contains("hostGuard"),
          !requestText.contains("fanOutLimit"),
          !requestText.contains("org.target"),
          !requestText.contains("9.9.9"),
          !requestText.contains("sample.Target"),
          !requestText.contains("target.html"),
        )
    },
    test("CLI parser defaults bare jev-loop to plan") {
      assertTrue(
        OrchestrationMode.parseCli(Chunk("jev-loop")) == Right(OrchestrationMode.Plan),
        OrchestrationMode.parseCli(Chunk("jev-loop", "plan")) == Right(OrchestrationMode.Plan),
        OrchestrationMode.parseCli(Chunk("jev-loop", "no-plan")) == Right(OrchestrationMode.NoPlan),
        OrchestrationMode.parseCli(Chunk("jev-loop", "plan-llm-filters")) == Right(OrchestrationMode.PlanLlmFilters),
        OrchestrationMode.parseCli(Chunk("jev-loop", "no-plan-llm-filters")) == Right(OrchestrationMode.NoPlanLlmFilters),
        OrchestrationMode.parseCli(Chunk("jev-loop", "other")).isLeft,
      )
    },
  )
