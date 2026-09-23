package com.jamesward.zio_typesafe_ai.orchestration

import com.jamesward.zio_typesafe_ai.{AppTypeSafeAIMock, TypeSafeAI}
import com.jamesward.ziohttp.mcp.{CallToolResult, ToolContent, ToolDefinition, ToolName}
import com.jamesward.zio_typesafe_ai.orchestration.model.*
import zio.*
import zio.json.ast.Json
import zio.test.*

object GenericOrchestratorSpec extends ZIOSpecDefault:
  private val string = Json.Obj("type" -> Json.Str("string"))
  private def obj(required: Vector[String] = Vector.empty, fields: (String, Json)*): Json.Obj = Json.Obj(
    "type" -> Json.Str("object"),
    "properties" -> Json.Obj(fields*),
    "required" -> Json.Arr(required.map(Json.Str(_))*),
    "additionalProperties" -> Json.Bool(false),
  )
  private def definition(name: String, input: Json.Obj, output: Json.Obj): ToolDefinition =
    ToolDefinition(ToolName(name), Some(s"generic $name capability"), input, Some(output))

  private val bootstrapDef = definition("bootstrap", obj(Vector("query"), "query" -> string), obj(Vector("sessionId"), "sessionId" -> string))
  private val list = definition("enumerate", obj(Vector("sessionId"), "sessionId" -> string), obj(Vector("entries"),
    "entries" -> Json.Obj("type" -> Json.Str("array"), "items" -> obj(Vector("entryId"), "entryId" -> string))))
  private val detail = definition("inspect", obj(Vector("sessionId", "entryId"), "sessionId" -> string, "entryId" -> string), obj(Vector("text"), "text" -> string))

  private def extractionArguments(schema: Json.Obj): Json.Obj = Json.Obj(
    "prompt" -> Json.Str("prompt only"),
    "targetTool" -> Json.Str("bootstrap"),
    "targetDescription" -> Json.Str("generic capability"),
    "targetInputSchema" -> obj(Vector("query"), "query" -> string),
    "outputSchema" -> schema,
    "knownArguments" -> Json.Obj(),
    "priorContext" -> Json.Arr(),
    "missingFields" -> Json.Arr(Json.Str("query")),
  )

  def spec = suite("generic orchestration runtime")(
    test("internal extraction validates fake model output against runtime schema") {
      val projected = obj(Vector("query"), "query" -> string)
      for
        invoker <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj("query" -> Json.Num(7))))
          def summarize(prompt: String, evidence: Json.Arr) = ZIO.succeed(LlmResult("unused"))
        )
        result <- invoker.generate(Catalog.ExtractName, extractionArguments(projected)).either
      yield assertTrue(result.isLeft, result.left.toOption.exists(_.getMessage.contains("/query")))
    },
    test("internal summarize receives all evidence and wraps final text") {
      for
        observed <- Ref.make(0)
        invoker <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          def summarize(prompt: String, evidence: Json.Arr) =
            observed.set(evidence.elements.size) *> ZIO.succeed(LlmResult(s"summary:$prompt", 3, 2, 1))
        )
        output <- invoker.generate(Catalog.SummarizeName, Json.Obj(
          "prompt" -> Json.Str("request"),
          "evidence" -> Json.Arr(Json.Obj("a" -> Json.Num(1)), Json.Obj("b" -> Json.Num(2))),
        ))
        count <- observed.get
        metrics <- invoker.metrics
      yield assertTrue(
        output.asObject.flatMap(_.get("summary")).flatMap(_.asString).contains("summary:request"),
        count == 2, metrics.summaryCount == 1, metrics.summaryInputTokens == 3,
      )
    },
    test("MCP adapter rejects invalid full arguments before invocation") {
      for
        calls <- Ref.make(0)
        raw = new RawMcpInvoker:
          def call(operation: String, arguments: Json.Obj) = calls.updateAndGet(_ + 1).as(CallToolResult())
        adapter <- McpOperationInvoker.make(Chunk(bootstrapDef), raw)
        result <- adapter.call("bootstrap", Json.Obj()).either
        count <- calls.get
        physical <- adapter.mcpPhysicalMetrics
      yield assertTrue(
        result.isLeft,
        count == 0,
        physical.calls == 0,
        physical.failures == 0,
        result.left.toOption.exists(_.getMessage.contains("/query")),
      )
    },
    test("MCP normalization preserves objects and wraps scalar, parseable text, and plain text") {
      for
        objectValue <- McpOperationInvoker.normalize("x", CallToolResult(structuredContent = Some(Json.Obj("id" -> Json.Str("1")))))
        scalarValue <- McpOperationInvoker.normalize("x", CallToolResult(structuredContent = Some(Json.Arr(Json.Num(1)))))
        parsedValue <- McpOperationInvoker.normalize("x", CallToolResult(content = Chunk(ToolContent.text("{\"ok\":true}"))))
        plainValue <- McpOperationInvoker.normalize("x", CallToolResult(content = Chunk(ToolContent.text("plain"))))
        failed <- McpOperationInvoker.normalize("x", CallToolResult(content = Chunk(ToolContent.text("bad")), isError = Some(true))).either
      yield assertTrue(
        objectValue.asObject.flatMap(_.get("id")).flatMap(_.asString).contains("1"),
        scalarValue.asObject.flatMap(_.get("result")).flatMap(_.asArray).nonEmpty,
        parsedValue.asObject.flatMap(_.get("ok")).flatMap(_.asBoolean).contains(true),
        plainValue.asObject.flatMap(_.get("result")).flatMap(_.asString).contains("plain"),
        failed.isLeft,
      )
    },
    test("scripted prompt-only plan executes extraction, exact chain, fan-out, summarize, and finish") {
      val definitions = Chunk(bootstrapDef, list, detail)
      for
        catalog <- McpCatalog.fromDefinitions(definitions)
        workflow <- GenericPlanner.planScripted(
          "research all matching entries",
          catalog,
          (state, options) =>
            val selected =
              if state.summaryStep.nonEmpty then options.collectFirst { case c if c.transition.isInstanceOf[Transition.Finish] => c }
              else if !state.operationCounts.contains("bootstrap") then options.collectFirst {
                case c @ Candidate(_, _, Transition.Invoke(plan), _) if plan.operation.name == "bootstrap" && plan.mode == InvocationMode.Direct => c
              }
              else if !state.operationCounts.contains("enumerate") then options.collectFirst {
                case c @ Candidate(_, _, Transition.Invoke(plan), _) if plan.operation.name == "enumerate" && plan.mode == InvocationMode.Direct => c
              }
              else if !state.operationCounts.contains("inspect") && !state.available.exists(_.fanOutSafety == FanOutSafety.ReadyFiltered) then options.collectFirst {
                case c @ Candidate(_, _, Transition.ApplyFilter(_), _) => c
              }
              else if !state.operationCounts.contains("inspect") then options.collectFirst {
                case c @ Candidate(_, _, Transition.Invoke(plan), _) if plan.operation.name == "inspect" && plan.mode == InvocationMode.FanOut => c
              }
              else options.collectFirst { case c if c.transition.isInstanceOf[Transition.AddSummary] => c }
            ZIO.fromOption(selected.map(_.id)).orElseFail(IllegalStateException("script had no matching host candidate")),
          OrchestrationConfig(maxOperations = 3, maxIterations = 7),
        )
        calls <- Ref.make(Vector.empty[(String, Json.Obj)])
        raw = new RawMcpInvoker:
          def call(operation: String, arguments: Json.Obj): Task[CallToolResult] =
            calls.update(_ :+ (operation -> arguments)) *> ZIO.succeed(operation match
              case "bootstrap" => CallToolResult(structuredContent = Some(Json.Obj("sessionId" -> Json.Str("s-1"))))
              case "enumerate" => CallToolResult(structuredContent = Some(Json.Obj("entries" -> Json.Arr(
                Json.Obj("entryId" -> Json.Str("a")), Json.Obj("entryId" -> Json.Str("b")),
              ))))
              case "inspect" => CallToolResult(structuredContent = Some(Json.Obj("text" -> Json.Str(s"detail:${arguments.get("entryId").flatMap(_.asString).get}"))))
            )
        operations <- McpOperationInvoker.make(definitions, raw)
        summaryEvidence <- Ref.make(0)
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest): Task[LlmResult[Json.Obj]] =
            ZIO.succeed(LlmResult(Json.Obj("query" -> Json.Str("from prompt")), 4, 1, 2))
          override def synthesizeFilter(request: PredicateFilter.SynthesisRequest): Task[LlmResult[Json.Obj]] =
            ZIO.succeed(LlmResult(Json.Obj(
              "kind" -> Json.Str("atom"),
              "leaf" -> Json.Obj("op" -> Json.Str("exists"), "path" -> Json.Arr(Json.Str("entryId"))),
            ), 2, 1, 1))
          def summarize(prompt: String, evidence: Json.Arr): Task[LlmResult[String]] =
            summaryEvidence.set(evidence.elements.size) *> ZIO.succeed(LlmResult("final generic answer", 6, 3, 2))
        )
        executed <- GenericOrchestrator.execute(
          "research all matching entries", workflow, operations, llm,
          OrchestrationConfig(maxOperations = 3, maxIterations = 7),
        )
        recorded <- calls.get
        evidenceCount <- summaryEvidence.get
      yield
        val enumerateArgs = recorded.collectFirst { case ("enumerate", args) => args }
        val inspectArgs = recorded.collect { case ("inspect", args) => args }
        assertTrue(
          workflow.steps.count { case Step.Generate(_, Catalog.ExtractName, _) => true; case _ => false } == 1,
          workflow.steps.count { case Step.Generate(_, Catalog.SummarizeName, _) => true; case _ => false } == 1,
          executed._3 == "final generic answer",
          executed._1.metrics.operationCalls == 4,
          executed._2.extractionCount == 1,
          executed._2.summaryCount == 1,
          evidenceCount == 3,
          enumerateArgs.flatMap(_.get("sessionId")).flatMap(_.asString).contains("s-1"),
          inspectArgs.map(_.get("entryId").flatMap(_.asString)).toSet == Set(Some("a"), Some("b")),
          inspectArgs.forall(_.get("sessionId").flatMap(_.asString).contains("s-1")),
        )
    },
    test("audited Jev planning propagates snapshots without changing existing result fields") {
      val operations = new _root_.com.jamesward.zio_typesafe_ai.orchestration.runtime.OperationInvoker:
        def call(operation: String, arguments: Json.Obj): Task[Json] =
          ZIO.succeed(Json.Obj("sessionId" -> Json.Str("s-audit")))

      for
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest): Task[LlmResult[Json.Obj]] =
            ZIO.succeed(LlmResult(Json.Obj("query" -> Json.Str("from prompt"))))
          def summarize(prompt: String, evidence: Json.Arr): Task[LlmResult[String]] =
            ZIO.succeed(LlmResult("audited answer"))
        )
        catalog <- McpCatalog.fromDefinitions(Chunk(bootstrapDef))
        result <- GenericOrchestrator.run(
          "audit this plan",
          catalog,
          operations,
          llm,
          OrchestrationConfig(maxOperations = 1, maxIterations = 3),
          TypeSafeAI.LoopObserver.none,
        ).provideLayer(AppTypeSafeAIMock.choices("c000", "c000", "c000"))
      yield assertTrue(
        result.finalText == "audited answer",
        result.planningTurns.size == 3,
        result.planningAudit.map(_.iteration) == List(1, 2, 3),
        result.planningAudit.flatMap(_.options.map(_.id)) == List("c000", "c000", "c000"),
        result.workflow.steps.nonEmpty,
      )
    },
  )
