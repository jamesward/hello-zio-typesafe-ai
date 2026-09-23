package com.jamesward.zio_typesafe_ai.orchestration

import com.jamesward.ziohttp.mcp.{ToolDefinition, ToolName}
import com.jamesward.zio_typesafe_ai.orchestration.model.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object GenericPlannerSpec extends ZIOSpecDefault:
  private val string = Json.Obj("type" -> Json.Str("string"))
  private def obj(required: Vector[String] = Vector.empty, fields: (String, Json)*): Json.Obj = Json.Obj(
    "type" -> Json.Str("object"),
    "properties" -> Json.Obj(fields*),
    "required" -> Json.Arr(required.map(Json.Str(_))*),
    "additionalProperties" -> Json.Bool(false),
  )
  private def definition(name: String, input: Json.Obj, output: Json.Obj): ToolDefinition =
    ToolDefinition(ToolName(name), Some(s"generic $name operation"), input, Some(output))

  private val bootstrapDef = definition("bootstrap", obj(Vector("query"), "query" -> string), obj(Vector("sessionId"), "sessionId" -> string))
  private val chained = definition("chained", obj(Vector("sessionId"), "sessionId" -> string), obj(Vector("rows"),
    "rows" -> Json.Obj("type" -> Json.Str("array"), "items" -> obj(Vector("itemId"), "itemId" -> string))))
  private val detail = definition("detail", obj(Vector("sessionId", "itemId"), "sessionId" -> string, "itemId" -> string), obj(Vector("text"), "text" -> string))

  private def invoke(options: Vector[Candidate], name: String, mode: InvocationMode = InvocationMode.Direct): Candidate =
    options.collectFirst {
      case candidate @ Candidate(_, _, Transition.Invoke(plan), _) if plan.operation.name == name && plan.mode == mode => candidate
    }.getOrElse(throw IllegalStateException(s"missing $name/$mode candidate"))

  private def next(state: PlanState, candidate: Candidate): PlanState =
    GenericPlanner.applyTransition(state, candidate.transition).toOption.get.asInstanceOf[PlanState]

  def spec = suite("GenericPlanner")(
    test("prompt-only bootstrap inserts symbolic schema-projected extraction") {
      for
        catalog <- McpCatalog.fromDefinitions(Chunk(bootstrapDef))
        state = GenericPlanner.initial("find useful records", catalog)
        options <- GenericPlanner.candidates(state)
        selected = invoke(options, "bootstrap")
        plan = selected.transition.asInstanceOf[Transition.Invoke].plan
      yield assertTrue(
        state.available.isEmpty,
        plan.missing == Vector("query"),
        plan.addedSteps.headOption.exists {
          case Step.Generate(_, Catalog.ExtractName, _) => true
          case _                                        => false
        },
        plan.addedSteps.exists {
          case Step.Call(_, "bootstrap", Expr.Obj(fields)) => fields.exists(_._1 == "query")
          case _                                           => false
        },
      )
    },
    test("exact prior output bypasses extraction and stable newest binding wins") {
      for
        catalog <- McpCatalog.fromDefinitions(Chunk(bootstrapDef, chained))
        initial = GenericPlanner.initial("continue from a session", catalog)
        firstOptions <- GenericPlanner.candidates(initial)
        afterBootstrap = next(initial, invoke(firstOptions, "bootstrap"))
        secondOptions <- GenericPlanner.candidates(afterBootstrap)
        selected = invoke(secondOptions, "chained")
        plan = selected.transition.asInstanceOf[Transition.Invoke].plan
      yield assertTrue(
        plan.missing.isEmpty,
        !plan.addedSteps.exists(_.isInstanceOf[Step.Generate]),
        plan.bindings.exists((name, expression, _) => name == "sessionId" && expression.isInstanceOf[Expr.Ref]),
      )
    },
    test("semantic-only or mismatched names are never auto-bound") {
      val operation = OperationSpec("consumer", "consumer", obj(Vector("token"), "token" -> string), obj(), CapabilityKind.Mcp)
      val available = AvailableValue(
        Expr.Ref("producer", List("result")), string, "result", SchemaModel.SchemaPath(Vector("result")),
        ValueOrigin.ToolOutput("producer", "producer"), 10,
      )
      val state = PlanState("use prior data", Vector(operation, Catalog.extract, Catalog.summarize), available = Vector(available))
      GenericPlanner.candidates(state).map: options =>
        val plan = invoke(options, "consumer").transition.asInstanceOf[Transition.Invoke].plan
        assertTrue(plan.missing == Vector("token"), plan.bindings.isEmpty)
    },
    test("does not offer filtering when array item schema has no scalar predicate paths") {
      val symbol = obj(Vector("fqn", "link"), "fqn" -> string, "link" -> string)
      val wrapper = obj(Vector("result"),
        "result" -> Json.Obj("type" -> Json.Str("array"), "items" -> symbol),
      )
      val wrapperArray = Json.Obj("type" -> Json.Str("array"), "items" -> wrapper)
      val source = AvailableValue(
        Expr.Ref("fan"), wrapperArray, "wrappedResults", SchemaModel.SchemaPath(Vector.empty),
        ValueOrigin.ToolOutput("fan", "listRecords"), 1, isArray = true,
      )
      val operation = OperationSpec("noop", "noop", obj(), obj(), CapabilityKind.Mcp)
      GenericPlanner.candidates(PlanState("find a record", Vector(operation), available = Vector(source))).map: options =>
        assertTrue(!options.exists(_.transition.isInstanceOf[Transition.ApplyFilter]))
    },
    test("runtime singleton promotion derives only observed singleton paths") {
      val artifact = obj(Vector("groupId", "artifactId"), "groupId" -> string, "artifactId" -> string)
      val symbol = obj(Vector("fqn", "link"), "fqn" -> string, "link" -> string)
      val scalarWrapper = obj(Vector("result"), "result" -> string)
      val arrayWrapper = obj(Vector("result"),
        "result" -> Json.Obj("type" -> Json.Str("array"), "items" -> symbol),
      )
      def arrayOf(item: Json.Obj) = Json.Obj("type" -> Json.Str("array"), "items" -> item)
      val available = Vector(
        AvailableValue(Expr.Ref("artifact"), arrayOf(artifact), "artifacts", SchemaModel.SchemaPath(Vector("artifacts")), ValueOrigin.Filtered("artifact", "source"), 1, isArray = true, fanOutSafety = FanOutSafety.ReadyFiltered),
        AvailableValue(Expr.Ref("latest"), arrayOf(scalarWrapper), "latestResults", SchemaModel.SchemaPath(Vector.empty), ValueOrigin.ToolOutput("latest", "latest"), 2, isArray = true),
        AvailableValue(Expr.Ref("listed"), arrayOf(arrayWrapper), "listResults", SchemaModel.SchemaPath(Vector.empty), ValueOrigin.ToolOutput("listed", "list"), 3, isArray = true),
        AvailableValue(Expr.Ref("empty"), arrayOf(artifact), "empty", SchemaModel.SchemaPath(Vector("empty")), ValueOrigin.ToolOutput("empty", "empty"), 4, isArray = true),
        AvailableValue(Expr.Ref("many"), arrayOf(artifact), "many", SchemaModel.SchemaPath(Vector("many")), ValueOrigin.ToolOutput("many", "many"), 5, isArray = true),
        AvailableValue(Expr.Ref("latest"), arrayOf(scalarWrapper), "staleLatest", SchemaModel.SchemaPath(Vector("stale")), ValueOrigin.ToolOutput("latest", "old"), 0, isArray = true),
      )
      val values = Map[String, Json](
        "artifact" -> Json.Arr(Json.Obj("groupId" -> Json.Str("dev.example"), "artifactId" -> Json.Str("records"))),
        "latest" -> Json.Arr(Json.Obj("result" -> Json.Str("1.0.0"))),
        "listed" -> Json.Arr(Json.Obj("result" -> Json.Arr(
          Json.Obj("fqn" -> Json.Str("example.A"), "link" -> Json.Str("A.html")),
          Json.Obj("fqn" -> Json.Str("example.B"), "link" -> Json.Str("B.html")),
        ))),
        "empty" -> Json.Arr(),
        "many" -> Json.Arr(Json.Obj("groupId" -> Json.Str("a"), "artifactId" -> Json.Str("a")), Json.Obj("groupId" -> Json.Str("b"), "artifactId" -> Json.Str("b"))),
      )
      val plan = PlanState("find records", Vector.empty, available = available, nextOrdinal = 6)
      GenericOrchestrator.promoteSingletonArrays(plan, values, available).map: promoted =>
        val derived = promoted.available.filter(_.expr.isInstanceOf[Expr.At])
        val arrays = derived.filter(_.isArray)
        assertTrue(
          derived.map(_.exposedName).toSet == Set("groupId", "artifactId", "result"),
          derived.count(_.exposedName == "result") == 2,
          arrays.size == 1,
          arrays.head.schema.asObject.flatMap(_.get("items")).contains(symbol),
          arrays.head.fanOutSafety == FanOutSafety.Unsafe,
          derived.forall(_.expr match { case Expr.At(_, 0, _) => true; case _ => false }),
          !derived.exists(value => value.expr.toString.contains("empty") || value.expr.toString.contains("many")),
          !promoted.available.exists(_.exposedName == "staleLatest"),
          promoted.available.count(_.expr == Expr.Ref("latest")) == 1,
          promoted.available.map(_.expr).distinct.size == promoted.available.size,
          promoted.nextOrdinal == 10,
        )
    },
    test("schema descriptions constrain same-named fields to declared consumer operations") {
      val link = Json.Obj(
        "type" -> Json.Str("string"),
        "description" -> Json.Str("Javadoc link that can be passed to detail_doc."),
      )
      val rows = Json.Obj(
        "type" -> Json.Str("array"), "maxItems" -> Json.Num(2),
        "items" -> obj(Vector("link"), "link" -> link),
      )
      val producer = definition("produce_docs", obj(), obj(Vector("rows"), "rows" -> rows))
      val detailDoc = definition(
        "detail_doc",
        obj(Vector("link"), "link" -> Json.Obj("type" -> Json.Str("string"), "description" -> Json.Str("Link returned by produce_docs."))),
        obj(Vector("text"), "text" -> string),
      )
      val sourceFile = definition(
        "get_source_file",
        obj(Vector("link"), "link" -> Json.Obj("type" -> Json.Str("string"), "description" -> Json.Str("Link returned by list_source_files."))),
        obj(Vector("text"), "text" -> string),
      )
      for
        catalog <- McpCatalog.fromDefinitions(Chunk(producer, detailDoc, sourceFile))
        initial = GenericPlanner.initial("inspect docs", catalog)
        first <- GenericPlanner.candidates(initial)
        produced = next(initial, invoke(first, "produce_docs"))
        options <- GenericPlanner.candidates(produced)
      yield assertTrue(
        options.exists {
          case Candidate(_, _, Transition.Invoke(plan), _) => plan.operation.name == "detail_doc" && plan.mode == InvocationMode.FanOut
          case _                                           => false
        },
        !options.exists {
          case Candidate(_, _, Transition.Invoke(plan), _) => plan.operation.name == "get_source_file" && plan.mode == InvocationMode.FanOut
          case _                                           => false
        },
      )
    },
    test("schema-compatible array items produce a fan-out with exact item and global bindings") {
      for
        catalog <- McpCatalog.fromDefinitions(Chunk(bootstrapDef, chained, detail))
        initial = GenericPlanner.initial("inspect every record", catalog)
        o1 <- GenericPlanner.candidates(initial)
        s1 = next(initial, invoke(o1, "bootstrap"))
        o2 <- GenericPlanner.candidates(s1)
        s2 = next(s1, invoke(o2, "chained"))
        o3 <- GenericPlanner.candidates(s2)
        filter = o3.collectFirst { case c @ Candidate(_, _, Transition.ApplyFilter(_), _) => c }.get
        s3 = next(s2, filter)
        o4 <- GenericPlanner.candidates(s3)
        selected = invoke(o4, "detail", InvocationMode.FanOut)
        plan = selected.transition.asInstanceOf[Transition.Invoke].plan
        s4 = next(s3, selected)
        o5 <- GenericPlanner.candidates(s4)
        filteredProducer = filter.transition.asInstanceOf[Transition.ApplyFilter].plan.addedAvailable.origin match
          case ValueOrigin.Filtered(stepId, _) => Some(stepId)
          case _                               => None
      yield assertTrue(
        plan.missing.isEmpty,
        plan.bindings.map(_._1).toSet == Set("itemId", "sessionId"),
        plan.bindings.exists((name, expr, _) => name == "itemId" && expr.isInstanceOf[Expr.Item]),
        plan.addedSteps.exists(_.isInstanceOf[Step.FanOut]),
        !o4.exists(_.transition.isInstanceOf[Transition.AddSummary]),
        o5.exists(_.transition.isInstanceOf[Transition.AddSummary]),
        s3.evidence.size == 2,
        s3.evidence.exists(ref => filteredProducer.contains(ref.producerStepId)),
      )
    },
    test("direct singleton projections count as consuming a ReadyFiltered source") {
      val row = obj(Vector("itemId"), "itemId" -> string)
      val rows = Json.Obj("type" -> Json.Str("array"), "items" -> row)
      val filteredOrigin = ValueOrigin.Filtered("filtered", "parent")
      val source = AvailableValue(
        Expr.Ref("filtered"), rows, "rows", SchemaModel.SchemaPath(Vector("rows")), filteredOrigin, 1,
        isArray = true, fanOutSafety = FanOutSafety.ReadyFiltered,
      )
      val itemId = AvailableValue(
        Expr.At(Expr.Ref("filtered"), 0, List("itemId")), string, "itemId",
        SchemaModel.SchemaPath(Vector("rows", "itemId")), filteredOrigin, 2,
      )
      val session = AvailableValue(
        Expr.Literal(Json.Str("session")), string, "sessionId", SchemaModel.SchemaPath(Vector("sessionId")),
        ValueOrigin.ExtractedArgument("extract", "detail"), 3,
      )
      val consumed = InvocationFingerprint(
        "detail", InvocationMode.Direct, None,
        Vector(
          "itemId" -> "filter:filtered:parent:/rows/itemId",
          "sessionId" -> "extract:extract:detail:/sessionId",
        ),
        Vector.empty,
      )
      for
        catalog <- McpCatalog.fromDefinitions(Chunk(detail))
        state = PlanState(
          "summarize inspected rows", catalog,
          available = Vector(source, itemId, session),
          evidence = Vector(EvidenceRef("filtered", "rows", Expr.Ref("filtered"), "filtered")),
          attempted = Set(consumed),
        )
        options <- GenericPlanner.candidates(state)
      yield assertTrue(
        options.exists(_.transition.isInstanceOf[Transition.AddSummary]),
        options.exists {
          case Candidate(_, _, Transition.Invoke(plan), _) => plan.mode == InvocationMode.FanOut
          case _                                           => false
        },
      )
    },
    test("catalog membership, candidate ordering, IDs, and summaries are deterministic") {
      for
        catalog <- McpCatalog.fromDefinitions(Chunk(chained, bootstrapDef))
        state = GenericPlanner.initial("generic request", catalog)
        first <- GenericPlanner.candidates(state)
        second <- GenericPlanner.candidates(state)
        names = catalog.map(_.name).toSet
        candidateJson = first.map(_.view.toJson).mkString
      yield assertTrue(
        names == Set("bootstrap", "chained", Catalog.ExtractName, Catalog.FilterName, Catalog.SummarizeName),
        first.map(_.id) == second.map(_.id),
        first.map(_.sortKey) == first.map(_.sortKey).sorted,
        GenericPlanner.stateView(state).get("operationCatalog").flatMap(_.asArray).exists(_.size == 5),
        candidateJson.contains("\"description\":\"generic bootstrap operation\""),
        candidateJson.contains("\"inputSchema\":"),
        candidateJson.contains("\"outputSchema\":"),
        !first.exists(_.view.get("operationCatalog").nonEmpty),
      )
    },
    test("Jev retries are opt-in and choice guidance is not duplicated in state") {
      val defaults = OrchestrationConfig()
      val optedIn = OrchestrationConfig(jevTurnRetries = 2)
      val state = GenericPlanner.stateView(GenericPlanner.initial("generic request", Vector.empty))
      assertTrue(
        defaults.jevTurnRetries == 0,
        defaults.hostGuard == 12,
        defaults.jevFilterVariantThreshold == 255,
        optedIn.jevTurnRetries == 2,
        state.get("instruction").isEmpty,
      )
    },
    test("summary is unavailable before evidence and Finish is the only post-summary transition") {
      for
        catalog <- McpCatalog.fromDefinitions(Chunk(bootstrapDef))
        initial = GenericPlanner.initial("answer me", catalog)
        before <- GenericPlanner.candidates(initial)
        afterCall = next(initial, invoke(before, "bootstrap"))
        withEvidence <- GenericPlanner.candidates(afterCall)
        summary = withEvidence.find(_.transition.isInstanceOf[Transition.AddSummary]).get
        afterSummary = next(afterCall, summary)
        finishing <- GenericPlanner.candidates(afterSummary)
        workflow = GenericPlanner.applyTransition(afterSummary, finishing.head.transition).toOption.get.asInstanceOf[Workflow]
      yield assertTrue(
        !before.exists(_.transition.isInstanceOf[Transition.AddSummary]),
        !before.exists(_.transition.isInstanceOf[Transition.Finish]),
        withEvidence.exists(_.transition.isInstanceOf[Transition.AddSummary]),
        finishing.size == 1,
        finishing.head.transition.isInstanceOf[Transition.Finish],
        workflow.steps.exists { case Step.Generate(_, Catalog.SummarizeName, _) => true; case _ => false },
        workflow.result match { case Expr.Ref(_, List("summary")) => true; case _ => false },
      )
    },
  )
