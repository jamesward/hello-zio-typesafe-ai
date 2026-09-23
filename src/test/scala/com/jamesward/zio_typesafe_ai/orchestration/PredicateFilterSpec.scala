package com.jamesward.zio_typesafe_ai.orchestration
import com.jamesward.ziohttp.mcp.{CallToolResult, ToolDefinition, ToolName}

import com.jamesward.zio_typesafe_ai.orchestration.PredicateFilter.*
import com.jamesward.zio_typesafe_ai.orchestration.model.*
import com.jamesward.zio_typesafe_ai.orchestration.runtime.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object PredicateFilterSpec extends ZIOSpecDefault:
  private val string = Json.Obj("type" -> Json.Str("string"))
  private val number = Json.Obj("type" -> Json.Str("number"))
  private val itemSchema = Json.Obj(
    "type" -> Json.Str("object"),
    "properties" -> Json.Obj(
      "id" -> string,
      "meta" -> Json.Obj("type" -> Json.Str("object"), "properties" -> Json.Obj("score" -> number, "tag" -> string)),
    ),
  )
  private def atom(op: String, path: String, value: Option[Json] = None): Json.Obj = Json.Obj(
    Chunk.fromIterable(Vector("kind" -> Json.Str("atom"), "leaf" -> Json.Obj(Chunk.fromIterable(
      Vector("op" -> Json.Str(op), "path" -> Json.Arr(path.split('.').toVector.map(Json.Str(_))*)) ++ value.map("value" -> _)
    ))))
  )
  private val items = Vector.tabulate(7)(i => Json.Obj(
    "id" -> Json.Str(s"id-$i"),
    "meta" -> Json.Obj("score" -> Json.Num(i), "tag" -> Json.Str(if i % 2 == 0 then "even" else "odd")),
  ))

  private def filterWorkflow(initial: Json.Obj): Workflow = Workflow(
    Vector(
      Step.Construct("source", Expr.Literal(Json.Arr(items*))),
      Step.Generate("predicate", Catalog.FilterName, Expr.Obj(Vector(
        "prompt" -> Expr.Literal(Json.Str("find the last item")),
        "itemSchema" -> Expr.Literal(itemSchema),
      ))),
      Step.Filter("filtered", Expr.Ref("source"), itemSchema, "find the last item", Expr.Ref("predicate")),
    ),
    Expr.Ref("filtered"),
  )

  def spec = suite("schema-safe non-truncating filtering")(
    test("indexes only declared scalar object paths and never traverses arrays") {
      val symbolSchema = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("fqn" -> string, "link" -> string),
      )
      val wrapperSchema = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj(
          "result" -> Json.Obj("type" -> Json.Str("array"), "items" -> symbolSchema),
        ),
      )
      val invalidTraversal = atom("eq", "result.fqn", Some(Json.Str("example.Symbol")))
      assertTrue(
        filterablePaths(wrapperSchema).isEmpty,
        filterablePaths(symbolSchema) == Vector(Vector("fqn"), Vector("link")),
        parseAndValidate(invalidTraversal, wrapperSchema).isLeft,
      )
    },
    test("validates finite operators and rejects unsafe paths, objects, and nested groups") {
      val valid = Json.Obj(
        "kind" -> Json.Str("all"),
        "clauses" -> Json.Arr(
          Json.Obj("op" -> Json.Str("startsWith"), "path" -> Json.Arr(Json.Str("id")), "value" -> Json.Str("id-")),
          Json.Obj("op" -> Json.Str("gte"), "path" -> Json.Arr(Json.Str("meta"), Json.Str("score")), "value" -> Json.Num(3)),
        ),
      )
      val unsafe = atom("eq", "meta.$value", Some(Json.Str("x")))
      val objectValue = atom("eq", "id", Some(Json.Obj("x" -> Json.Num(1))))
      val nested = Json.Obj("kind" -> Json.Str("all"), "clauses" -> Json.Arr(valid))
      val leafAny = atom("any", "id")
      val rootAny = Json.Obj(
        "kind" -> Json.Str("any"),
        "clauses" -> Json.Arr(
          Json.Obj("op" -> Json.Str("exists"), "path" -> Json.Arr(Json.Str("id"))),
          Json.Obj("op" -> Json.Str("gte"), "path" -> Json.Arr(Json.Str("meta"), Json.Str("score")), "value" -> Json.Num(3)),
        ),
      )
      assertTrue(
        parseAndValidate(valid, itemSchema).isRight,
        parseAndValidate(unsafe, itemSchema).isLeft,
        parseAndValidate(objectValue, itemSchema).isLeft,
        parseAndValidate(nested, itemSchema).isLeft,
        parseAndValidate(leafAny, itemSchema).isLeft,
        parseAndValidate(rootAny, itemSchema).isRight,
      )
    },
    test("evaluates every item in source order and finds the last item") {
      val predicate = parseAndValidate(atom("eq", "id", Some(Json.Str("id-6"))), itemSchema).toOption.get
      val result = classify(items, predicate, hostGuard = 2).toOption.get
      assertTrue(
        result.source == items,
        result.source.size == 7,
        result.matches == Vector(items.last),
        result.classification == Classification.Ready,
      )
    },
    test("string eq, in, contains, and startsWith are case-insensitive") {
      val eq = parseAndValidate(atom("eq", "id", Some(Json.Str("ID-6"))), itemSchema).toOption.get
      val inRaw = Json.Obj(
        "kind" -> Json.Str("atom"),
        "leaf" -> Json.Obj(
          "op" -> Json.Str("in"), "path" -> Json.Arr(Json.Str("id")),
          "values" -> Json.Arr(Json.Str("ABSENT"), Json.Str("ID-6")),
        ),
      )
      val in = parseAndValidate(inRaw, itemSchema).toOption.get
      val contains = parseAndValidate(atom("contains", "id", Some(Json.Str("ID-6"))), itemSchema).toOption.get
      val startsWith = parseAndValidate(atom("startsWith", "id", Some(Json.Str("ID-"))), itemSchema).toOption.get
      assertTrue(
        evaluate(eq, items.last),
        evaluate(in, items.last),
        evaluate(contains, items.last),
        evaluate(startsWith, items.head),
        canonical(eq).toJson.contains("\"value\":\"id-6\""),
        canonical(in).toJson.contains("\"id-6\""),
      )
    },
    test("TooBroad preserves the complete source and complete matches") {
      val predicate = parseAndValidate(atom("exists", "id"), itemSchema).toOption.get
      val result = classify(items, predicate, hostGuard = 2).toOption.get
      assertTrue(result.source == items, result.matches == items, result.classification == Classification.TooBroad)
    },
    test("TooBroad refinement is cumulative and reaches Ready without truncation") {
      for
        seen <- Ref.make(Vector.empty[SynthesisRequest])
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          override def synthesizeFilter(request: SynthesisRequest) =
            seen.update(_ :+ request) *> ZIO.succeed(LlmResult(
              if request.priorPredicate.isEmpty then atom("exists", "id") else atom("eq", "id", Some(Json.Str("id-6")))
            ))
          def summarize(prompt: String, evidence: Json.Arr) = ZIO.succeed(LlmResult("unused"))
        )
        report <- WorkflowRuntime.execute(
          filterWorkflow(atom("exists", "id")), Json.Obj(),
          new OperationInvoker { def call(operation: String, arguments: Json.Obj) = ZIO.dieMessage("no downstream call") },
          llm, ExecutionPolicy(fanOutLimit = Some(2), maxFilterAttempts = 3, maxRecoveries = 2),
        )
        requests <- seen.get
      yield assertTrue(
        report.output.asArray.exists(_.toVector == Vector(items.last)),
        report.metrics.recoveries == 1,
        requests.size == 2,
        requests.last.outcomeDiagnostics.exists(d => d.sourceCount == 7 && d.matchCount == 7),
      )
    },
    test("accepted NoMatches retains the refinement source for later replacement") {
      for
        calls <- Ref.make(0)
        requests <- Ref.make(Vector.empty[SynthesisRequest])
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          override def synthesizeFilter(request: SynthesisRequest) =
            requests.update(_ :+ request) *> calls.updateAndGet(_ + 1).map:
              case 1 => LlmResult(atom("lte", "meta.score", Some(Json.Num(4))))
              case 2 => LlmResult(atom("eq", "id", Some(Json.Str("absent"))))
              case 3 => LlmResult(atom("eq", "id", Some(Json.Str("id-6"))))
              case 4 => LlmResult(atom("eq", "id", Some(Json.Str("id-4"))))
              case other => throw IllegalStateException(s"unexpected synthesis $other")
          def summarize(prompt: String, evidence: Json.Arr) = ZIO.succeed(LlmResult("unused"))
        )
        report <- WorkflowRuntime.execute(
          filterWorkflow(atom("lte", "meta.score", Some(Json.Num(4)))), Json.Obj(),
          new OperationInvoker { def call(operation: String, arguments: Json.Obj) = ZIO.dieMessage("no downstream call") },
          llm, ExecutionPolicy(fanOutLimit = Some(2), maxFilterAttempts = 4, maxRecoveries = 3),
        )
        seen <- requests.get
        count <- calls.get
      yield assertTrue(
        report.output.asArray.exists(_.toVector == Vector(items(4))),
        count == 4,
        seen.drop(1).flatMap(_.outcomeDiagnostics.map(d => (d.classification, d.sourceCount, d.matchCount))) ==
          Vector(("TooBroad", 7, 5), ("NoMatches", 5, 0), ("NoMatches", 5, 0)),
        report.metrics.generativeCalls == 4,
        report.metrics.recoveries == 3,
      )
    },
    test("NoMatches replacement runs against retained parent source with observed runtime evidence") {
      for
        requests <- Ref.make(Vector.empty[SynthesisRequest])
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          override def synthesizeFilter(request: SynthesisRequest) =
            requests.update(_ :+ request) *> ZIO.succeed(LlmResult(
              if request.priorPredicate.isEmpty then atom("eq", "id", Some(Json.Str("absent")))
              else atom("eq", "id", Some(Json.Str("id-6")))
            ))
          def summarize(prompt: String, evidence: Json.Arr) = ZIO.succeed(LlmResult("unused"))
        )
        report <- WorkflowRuntime.execute(
          filterWorkflow(atom("eq", "id", Some(Json.Str("absent")))), Json.Obj(),
          new OperationInvoker { def call(operation: String, arguments: Json.Obj) = ZIO.dieMessage("no downstream call") },
          llm, ExecutionPolicy(fanOutLimit = Some(2), maxFilterAttempts = 3, maxRecoveries = 2),
        )
        seen <- requests.get
      yield assertTrue(
        report.output.asArray.exists(_.toVector == Vector(items.last)),
        report.metrics.recoveries == 1,
        seen.size == 2,
        seen.head.evidence.isEmpty,
        seen.last.evidence.exists(evidence =>
          evidence.complete &&
            evidence.sourceCount == items.size &&
            evidence.variantCount == items.size &&
            evidence.variants.elements.toVector.toSet == items.map(projectVariant(_, filterablePaths(itemSchema))).toSet
        ),
      )
    },
    test("filter synthesis request has no host guard or limit field") {
      val request = SynthesisRequest("prompt", itemSchema, None, Some(Diagnostic("TooBroad", 7, 7, "refine")))
      val text = request.toJson.toJson
      assertTrue(!text.contains("guard"), !text.contains("limit"), !text.contains("maxFilter"), !text.contains("fanOut"))
    },
    test("invalid object criteria return an encoded rejection and meter the returned result exactly once") {
      val invalid = atom("any", "id")
      for
        calls <- Ref.make(0)
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          override def synthesizeFilter(request: SynthesisRequest) =
            calls.updateAndGet(_ + 1).as(LlmResult(invalid, inputTokens = 11, outputTokens = 7, latencyMs = 13))
          def summarize(prompt: String, evidence: Json.Arr) = ZIO.succeed(LlmResult("unused"))
        )
        encoded <- llm.generate(Catalog.FilterName, SynthesisRequest("prompt", itemSchema, None, None).toJson)
        decoded = SynthesisResult.fromGenerativeValue(encoded)
        count <- calls.get
        metrics <- llm.metrics
      yield assertTrue(
        decoded.exists {
          case SynthesisResult.Rejected(rejection) =>
            rejection.kind == SynthesisRejectionKind.InvalidPredicate && rejection.message.contains("Unsupported operator 'any'")
          case _ => false
        },
        count == 1,
        metrics.filterCount == 1,
        metrics.filterInputTokens == 11,
        metrics.filterOutputTokens == 7,
        metrics.filterLatencyMs == 13,
      )
    },
    test("filter transport failure remains a failure and is not metered") {
      val transportFailure = RuntimeException("network unavailable")
      for
        calls <- Ref.make(0)
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          override def synthesizeFilter(request: SynthesisRequest) =
            calls.updateAndGet(_ + 1) *> ZIO.fail(transportFailure)
          def summarize(prompt: String, evidence: Json.Arr) = ZIO.succeed(LlmResult("unused"))
        )
        result <- llm.generate(Catalog.FilterName, SynthesisRequest("prompt", itemSchema, None, None).toJson).either
        count <- calls.get
        metrics <- llm.metrics
      yield assertTrue(
        result == Left(transportFailure),
        count == 1,
        metrics.filterCount == 0,
        metrics.filterInputTokens == 0,
        metrics.filterOutputTokens == 0,
        metrics.filterLatencyMs == 0L,
      )
    },
    test("runtime continues once after invalid initial criteria and meters both returned generations") {
      for
        calls <- Ref.make(0)
        requests <- Ref.make(Vector.empty[SynthesisRequest])
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          override def synthesizeFilter(request: SynthesisRequest) =
            requests.update(_ :+ request) *> calls.updateAndGet(_ + 1).map:
              case 1 => LlmResult(atom("any", "id"), inputTokens = 3, outputTokens = 2, latencyMs = 5)
              case 2 => LlmResult(atom("eq", "id", Some(Json.Str("id-6"))), inputTokens = 7, outputTokens = 4, latencyMs = 11)
              case other => throw IllegalStateException(s"unexpected synthesis $other")
          def summarize(prompt: String, evidence: Json.Arr) = ZIO.succeed(LlmResult("unused"))
        )
        report <- WorkflowRuntime.execute(
          filterWorkflow(atom("any", "id")), Json.Obj(),
          new OperationInvoker { def call(operation: String, arguments: Json.Obj) = ZIO.dieMessage("no downstream call") },
          llm, ExecutionPolicy(fanOutLimit = Some(2), maxFilterAttempts = 2, maxRecoveries = 1),
        )
        seen <- requests.get
        count <- calls.get
        metrics <- llm.metrics
      yield assertTrue(
        report.output.asArray.exists(_.toVector == Vector(items.last)),
        count == 2,
        seen.size == 2,
        seen.head.priorPredicate.isEmpty,
        seen(1).priorPredicate.isEmpty,
        seen(1).outcomeDiagnostics.isEmpty,
        seen(1).priorRejection.exists(_.kind == SynthesisRejectionKind.InvalidPredicate),
        report.metrics.generativeCalls == 2,
        report.metrics.recoveries == 1,
        metrics.filterCount == 2,
        metrics.filterInputTokens == 10,
        metrics.filterOutputTokens == 6,
        metrics.filterLatencyMs == 16,
      )
    },
    test("runtime preserves TooBroad basis through invalid and repeated continuations before Ready") {
      for
        calls <- Ref.make(0)
        requests <- Ref.make(Vector.empty[SynthesisRequest])
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          override def synthesizeFilter(request: SynthesisRequest) =
            requests.update(_ :+ request) *> calls.updateAndGet(_ + 1).map:
              case 1 => LlmResult(atom("exists", "id"))
              case 2 => LlmResult(atom("any", "id"))
              case 3 => LlmResult(atom("exists", "id"))
              case 4 => LlmResult(atom("eq", "id", Some(Json.Str("id-6"))))
              case other => throw IllegalStateException(s"unexpected synthesis $other")
          def summarize(prompt: String, evidence: Json.Arr) = ZIO.succeed(LlmResult("unused"))
        )
        report <- WorkflowRuntime.execute(
          filterWorkflow(atom("exists", "id")), Json.Obj(),
          new OperationInvoker { def call(operation: String, arguments: Json.Obj) = ZIO.dieMessage("no downstream call") },
          llm, ExecutionPolicy(fanOutLimit = Some(2), maxFilterAttempts = 4, maxRecoveries = 3),
        )
        seen <- requests.get
        count <- calls.get
        metrics <- llm.metrics
      yield assertTrue(
        report.output.asArray.exists(_.toVector == Vector(items.last)),
        count == 4,
        seen.size == 4,
        seen.drop(1).forall(_.priorPredicate.nonEmpty),
        seen.drop(1).flatMap(_.outcomeDiagnostics.map(d => (d.classification, d.sourceCount, d.matchCount))) ==
          Vector.fill(3)(("TooBroad", items.size, items.size)),
        seen(2).priorRejection.exists(_.kind == SynthesisRejectionKind.InvalidPredicate),
        seen(3).priorRejection.exists(_.kind == SynthesisRejectionKind.RepeatedPredicate),
        report.metrics.generativeCalls == 4,
        report.metrics.recoveries == 3,
        metrics.filterCount == 4,
      )
    },
    test("runtime reports FilterSynthesisRejected when invalid criteria exhaust without an evaluation") {
      for
        calls <- Ref.make(0)
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          override def synthesizeFilter(request: SynthesisRequest) =
            calls.updateAndGet(_ + 1).as(LlmResult(atom("any", "id")))
          def summarize(prompt: String, evidence: Json.Arr) = ZIO.succeed(LlmResult("unused"))
        )
        result <- WorkflowRuntime.execute(
          filterWorkflow(atom("any", "id")), Json.Obj(),
          new OperationInvoker { def call(operation: String, arguments: Json.Obj) = ZIO.dieMessage("no downstream call") },
          llm, ExecutionPolicy(fanOutLimit = Some(2), maxFilterAttempts = 1, maxRecoveries = 3),
        ).either
        count <- calls.get
        metrics <- llm.metrics
      yield assertTrue(
        result.left.toOption.exists(_.isInstanceOf[ExecutionError.FilterSynthesisRejected]),
        count == 1,
        metrics.filterCount == 1,
      )
    },
    test("accepted filter criteria are encoded in canonical form") {
      val returned = Json.Obj(
        "kind" -> Json.Str("all"),
        "clauses" -> Json.Arr(
          Json.Obj("op" -> Json.Str("startsWith"), "path" -> Json.Arr(Json.Str("meta"), Json.Str("tag")), "value" -> Json.Str("EV")),
          Json.Obj("op" -> Json.Str("contains"), "path" -> Json.Arr(Json.Str("id")), "value" -> Json.Str("ID-")),
        ),
      )
      for
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          override def synthesizeFilter(request: SynthesisRequest) = ZIO.succeed(LlmResult(returned))
          def summarize(prompt: String, evidence: Json.Arr) = ZIO.succeed(LlmResult("unused"))
        )
        encoded <- llm.generate(Catalog.FilterName, SynthesisRequest("prompt", itemSchema, None, None).toJson)
        decoded = SynthesisResult.fromGenerativeValue(encoded)
        expected = canonical(parseAndValidate(returned, itemSchema).toOption.get)
      yield assertTrue(decoded == Right(SynthesisResult.Accepted(expected)))
    },
    test("prior synthesis rejection is compact, sanitized, bounded, and guard-free") {
      val rejection = SynthesisRejection(
        SynthesisRejectionKind.RepeatedPredicate,
        " repeated\n predicate\t" + ("x" * 400),
      )
      val request = SynthesisRequest("prompt", itemSchema, None, None, Some(rejection))
      val text = request.toJson.toJson
      assertTrue(
        rejection.message.length <= MaxSynthesisRejectionMessageLength,
        rejection.message.endsWith("…"),
        !rejection.message.exists(Character.isISOControl),
        request.toJson.get("priorRejection").flatMap(_.asObject).flatMap(_.get("kind")).flatMap(_.asString).contains("repeated_predicate"),
        !text.contains("hostGuard"), !text.contains("fanOutLimit"), !text.contains("maxFilterAttempts"), !text.contains("maxRecoveries"),
      )
    },
    test("unsafe raw fanout fails closed with zero calls and never truncates") {
      val workflow = Workflow(Vector(
        Step.Construct("source", Expr.Literal(Json.Arr(items*))),
        Step.FanOut("fan", Expr.Ref("source"), "detail", Expr.Obj(Vector("id" -> Expr.Item(List("id"))))),
      ), Expr.Ref("fan"))
      for
        calls <- Ref.make(0)
        result <- WorkflowRuntime.execute(workflow, Json.Obj(), new OperationInvoker:
          def call(operation: String, arguments: Json.Obj) = calls.updateAndGet(_ + 1).as(Json.Obj())
        , policy = ExecutionPolicy(fanOutLimit = Some(10))).either
        count <- calls.get
      yield assertTrue(result.left.toOption.exists(_.isInstanceOf[ExecutionError.UnsafeFanOut]), count == 0)
    },
    test("declared maxItems permits planning and fanout output shape is array of operation output") {
      val sourceSchema = Json.Obj("type" -> Json.Str("array"), "maxItems" -> Json.Num(2), "items" -> itemSchema)
      val detailOutput = Json.Obj("type" -> Json.Str("object"), "properties" -> Json.Obj("text" -> string))
      val operation = OperationSpec(
        "detail", "detail", Json.Obj("type" -> Json.Str("object"), "properties" -> Json.Obj("id" -> string), "required" -> Json.Arr(Json.Str("id"))),
        detailOutput, CapabilityKind.Mcp,
      )
      val source = AvailableValue(Expr.Ref("source"), sourceSchema, "items", SchemaModel.SchemaPath(Vector("items")), ValueOrigin.ToolOutput("source", "list"), 1, isArray = true)
      for
        options <- GenericPlanner.candidates(PlanState("inspect", Vector(operation, Catalog.extract, Catalog.synthesizeFilter, Catalog.summarize), available = Vector(source)), OrchestrationConfig(hostGuard = 2))
        plan = options.collectFirst { case Candidate(_, _, Transition.Invoke(plan), _) if plan.mode == InvocationMode.FanOut => plan }.get
        output = plan.addedAvailable.last
      yield assertTrue(
        plan.addedSteps.exists(_.isInstanceOf[Step.FanOut]),
        output.isArray,
        output.schema.asObject.flatMap(_.get("items")).contains(detailOutput),
        output.expr match { case Expr.Ref(_, Nil) => true; case _ => false },
      )
    },
    test("NoProgress requires strict reduction of the complete prior match set") {
      val predicate = parseAndValidate(atom("startsWith", "id", Some(Json.Str("id-"))), itemSchema).toOption.get
      val result = classify(items, predicate, hostGuard = 2, priorMatchCount = Some(items.size)).toOption.get
      assertTrue(
        result.source == items,
        result.matches == items,
        result.classification == Classification.NoProgress,
      )
    },
    test("NoProgress continuation uses the retained complete source and can reach Ready") {
      for
        seen <- Ref.make(Vector.empty[SynthesisRequest])
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          override def synthesizeFilter(request: SynthesisRequest) =
            seen.update(_ :+ request) *> ZIO.succeed(LlmResult(
              request.outcomeDiagnostics.map(_.classification) match
                case None               => atom("exists", "id")
                case Some("TooBroad")   => atom("startsWith", "id", Some(Json.Str("id-")))
                case Some("NoProgress") => atom("eq", "id", Some(Json.Str("id-6")))
                case other              => throw IllegalStateException(s"unexpected diagnostic: $other")
            ))
          def summarize(prompt: String, evidence: Json.Arr) = ZIO.succeed(LlmResult("unused"))
        )
        report <- WorkflowRuntime.execute(
          filterWorkflow(atom("exists", "id")), Json.Obj(),
          new OperationInvoker { def call(operation: String, arguments: Json.Obj) = ZIO.dieMessage("no downstream call") },
          llm, ExecutionPolicy(fanOutLimit = Some(2), maxFilterAttempts = 4, maxRecoveries = 3),
        )
        requests <- seen.get
      yield assertTrue(
        report.output.asArray.exists(_.toVector == Vector(items.last)),
        report.metrics.recoveries == 2,
        requests.flatMap(_.outcomeDiagnostics.map(_.classification)) == Vector("TooBroad", "NoProgress"),
        requests.last.outcomeDiagnostics.exists(_.sourceCount == items.size),
      )
    },
    test("canonicalization normalizes case-insensitive values and boolean clause order") {
      val first = Json.Obj(
        "kind" -> Json.Str("all"),
        "clauses" -> Json.Arr(
          Json.Obj("op" -> Json.Str("contains"), "path" -> Json.Arr(Json.Str("id")), "value" -> Json.Str("ID-")),
          Json.Obj("op" -> Json.Str("startsWith"), "path" -> Json.Arr(Json.Str("meta"), Json.Str("tag")), "value" -> Json.Str("EV")),
        ),
      )
      val reordered = Json.Obj(
        "kind" -> Json.Str("all"),
        "clauses" -> Json.Arr(
          Json.Obj("op" -> Json.Str("startsWith"), "path" -> Json.Arr(Json.Str("meta"), Json.Str("tag")), "value" -> Json.Str("ev")),
          Json.Obj("op" -> Json.Str("contains"), "path" -> Json.Arr(Json.Str("id")), "value" -> Json.Str("id-")),
        ),
      )
      assertTrue(
        canonical(parseAndValidate(first, itemSchema).toOption.get) ==
          canonical(parseAndValidate(reordered, itemSchema).toOption.get),
      )
    },
    test("repeated canonical predicates become bounded rejections and exhaust as FilterNotReady") {
      for
        calls <- Ref.make(0)
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          override def synthesizeFilter(request: SynthesisRequest) =
            calls.updateAndGet(_ + 1).as(LlmResult(atom("exists", "id")))
          def summarize(prompt: String, evidence: Json.Arr) = ZIO.succeed(LlmResult("unused"))
        )
        result <- WorkflowRuntime.execute(
          filterWorkflow(atom("exists", "id")), Json.Obj(),
          new OperationInvoker { def call(operation: String, arguments: Json.Obj) = ZIO.dieMessage("no downstream call") },
          llm, ExecutionPolicy(fanOutLimit = Some(2), maxFilterAttempts = 3, maxRecoveries = 2),
        ).either
        count <- calls.get
        metrics <- llm.metrics
      yield assertTrue(
        result.left.toOption.exists(_.isInstanceOf[ExecutionError.FilterNotReady]),
        count == 3,
        metrics.filterCount == 3,
      )
    },
    test("filter-attempt and recovery limits exhaust independently") {
      def run(maxFilterAttempts: Int, maxRecoveries: Int) = for
        llm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.succeed(LlmResult(Json.Obj()))
          override def synthesizeFilter(request: SynthesisRequest) = ZIO.succeed(LlmResult(
            if request.priorPredicate.isEmpty then atom("exists", "id")
            else atom("gte", "meta.score", Some(Json.Num(1)))
          ))
          def summarize(prompt: String, evidence: Json.Arr) = ZIO.succeed(LlmResult("unused"))
        )
        result <- WorkflowRuntime.execute(
          filterWorkflow(atom("exists", "id")), Json.Obj(),
          new OperationInvoker { def call(operation: String, arguments: Json.Obj) = ZIO.dieMessage("no downstream call") },
          llm, ExecutionPolicy(
            fanOutLimit = Some(2), maxFilterAttempts = maxFilterAttempts, maxRecoveries = maxRecoveries,
          ),
        ).either
        metrics <- llm.metrics
      yield result -> metrics

      for
        attemptLimited <- run(maxFilterAttempts = 2, maxRecoveries = 5)
        recoveryLimited <- run(maxFilterAttempts = 5, maxRecoveries = 1)
      yield assertTrue(
        attemptLimited._1.left.toOption.exists(_.isInstanceOf[ExecutionError.FilterNotReady]),
        recoveryLimited._1.left.toOption.exists(_.isInstanceOf[ExecutionError.FilterNotReady]),
        attemptLimited._2.filterCount == 2,
        recoveryLimited._2.filterCount == 2,
      )
    },
    test("declared maxItems output violation blocks downstream static fanout") {
      val emptyInput = Json.Obj(
        "type" -> Json.Str("object"), "properties" -> Json.Obj(), "additionalProperties" -> Json.Bool(false),
      )
      val boundedArray = Json.Obj(
        "type" -> Json.Str("array"), "maxItems" -> Json.Num(2), "items" -> itemSchema,
      )
      val producerOutput = Json.Obj(
        "type" -> Json.Str("object"), "properties" -> Json.Obj("items" -> boundedArray),
        "required" -> Json.Arr(Json.Str("items")), "additionalProperties" -> Json.Bool(false),
      )
      val detailInput = Json.Obj(
        "type" -> Json.Str("object"), "properties" -> Json.Obj("id" -> string),
        "required" -> Json.Arr(Json.Str("id")), "additionalProperties" -> Json.Bool(false),
      )
      val detailOutput = Json.Obj("type" -> Json.Str("object"))
      val definitions = Chunk(
        ToolDefinition(ToolName("produce"), None, emptyInput, Some(producerOutput)),
        ToolDefinition(ToolName("detail"), None, detailInput, Some(detailOutput)),
      )
      val workflow = Workflow(Vector(
        Step.Call("source", "produce", Expr.Obj(Vector.empty)),
        Step.FanOut(
          "fan", Expr.Ref("source", List("items")), "detail",
          Expr.Obj(Vector("id" -> Expr.Item(List("id")))), FanOutAuthorization.StaticallyBound(2),
        ),
      ), Expr.Ref("fan"))
      for
        downstreamCalls <- Ref.make(0)
        adapter <- McpOperationInvoker.make(definitions, new RawMcpInvoker:
          def call(operation: String, arguments: Json.Obj) =
            if operation == "produce" then ZIO.succeed(CallToolResult(structuredContent = Some(Json.Obj(
              "items" -> Json.Arr(items(0), items(1), items(2)),
            ))))
            else downstreamCalls.updateAndGet(_ + 1).as(CallToolResult(structuredContent = Some(Json.Obj())))
        )
        result <- WorkflowRuntime.execute(
          workflow, Json.Obj(), adapter,
          policy = ExecutionPolicy(
            fanOutLimit = Some(2), maxRecoveries = 0,
          ),
        ).either
        calls <- downstreamCalls.get
        physical <- adapter.mcpPhysicalMetrics
      yield assertTrue(
        result.left.toOption.exists(_.getMessage.contains("above maxItems 2")),
        calls == 0,
        physical.calls == 1,
        physical.successes == 1,
      )
    },
  )
