import benchmark.javadocs.JacksonJavadocsBenchmark as Benchmark
import com.jamesward.zio_bedrock.{Bedrock, Converse}
import com.jamesward.zio_bedrock.Bedrock.*
import com.jamesward.zio_typesafe_ai.TypeSafeAI
import com.jamesward.zio_typesafe_ai.orchestration
import com.jamesward.ziohttp.mcp.{CallToolResult, ToolDefinition}
import com.jamesward.ziohttp.mcp.client.McpClient
import zio.*
import zio.http.{Client as HttpClient}
import zio.json.*
import zio.json.ast.Json
import zio.schema.codec.json.schemaJson

object Main extends ZIOAppDefault:

  private val McpUrl = Benchmark.mcpUrl
  private val Prompt = Benchmark.goal

  private def loggedTypeSafeAIClient(
    fullBodies: Boolean
  ): ZLayer[HttpClient, TypeSafeAI.Error, TypeSafeAI.Client] =
    TypeSafeAI.Client.live >>> TypeSafeAI.Client.observed(orchestration.JevExchangeLogger.selected(fullBodies))

  private def printMetrics(result: DynamicLoopResult[String], label: String): UIO[Unit] =
    ZIO.foreachDiscard(result.turns): turn =>
      Console.printLine(
        s"[$label:bedrock] turn=${turn.turn} stop=${turn.stopReason} inputTokens=${turn.usage.inputTokens} " +
          s"outputTokens=${turn.usage.outputTokens} tools=[${turn.toolNames.map(_.unwrap).mkString(", ")}]"
      ).orDie
    *> Console.printLine(s"\n${result.output}").orDie
    *> Console.printLine(
      s"\n[$label] Completed ${result.turns.size} Bedrock turns; " +
        s"inputTokens=${result.totals.usage.inputTokens}, " +
        s"outputTokens=${result.totals.usage.outputTokens}, " +
        s"totalTokens=${result.totals.usage.totalTokens}, " +
        s"latencyMs=${result.totals.latencyMs}"
    ).orDie


  private case class ToolTiming(calls: Int, summedMs: Long, wallMs: Long)

  private def summarizeIntervals(intervals: List[(Long, Long)]): ToolTiming =
    val sorted = intervals.sortBy(_._1)
    val merged = sorted.foldLeft(List.empty[(Long, Long)]):
      case (Nil, interval) => List(interval)
      case ((start, end) :: tail, (nextStart, nextEnd)) if nextStart <= end =>
        (start, math.max(end, nextEnd)) :: tail
      case (acc, interval) => interval :: acc
    ToolTiming(
      intervals.size,
      intervals.map((start, end) => (end - start) / 1000000L).sum,
      merged.map((start, end) => (end - start) / 1000000L).sum,
    )
  // ----- LLM loop baseline: every MCP operation is directly model-visible. -----

  private def rawMcpContent(result: CallToolResult): String = result.content.toJson

  private def invokeRaw(
    client: McpClient,
    name: String,
    arguments: Json.Obj,
    intervals: Ref[List[(Long, Long)]],
  ): Task[String] =
    for
      _ <- Console.printLine(s"[llm-loop:mcp] -> $name ${arguments.toJson}").orDie
      started <- Clock.nanoTime
      result <- client.callTool(name, arguments)
      finished <- Clock.nanoTime
      _ <- intervals.update((started -> finished) :: _)
      output = rawMcpContent(result)
      _ <- Console.printLine(s"[llm-loop:mcp] <- $name (${output.length} chars)").orDie
      _ <- ZIO.fail(RuntimeException(s"MCP tool $name failed: $output")).when(result.isError.contains(true))
    yield output

  private def llmLoopTools(definitions: Chunk[ToolDefinition]): Task[List[Tool[?]]] =
    val names = definitions.map(_.name.value).toList
    val duplicates = names.groupMapReduce(identity)(_ => 1)(_ + _).collect:
      case (name, count) if count > 1 => name
    if duplicates.nonEmpty then ZIO.fail(IllegalArgumentException(s"Duplicate MCP tools: ${duplicates.mkString(", ")}"))
    else ZIO.succeed(definitions.toList.map: definition =>
      Tool.dynamic(
        ToolName(definition.name.value),
        definition.description.getOrElse(definition.name.value),
        definition.inputSchema,
      )
    )

  private val llmLoopProgram = ZIO.scoped:
    for
      started <- Clock.nanoTime
      intervals <- Ref.make(List.empty[(Long, Long)])
      mcp <- McpClient.connect(McpUrl)
      definitions <- mcp.listTools
      tools <- llmLoopTools(definitions)
      available = definitions.map(_.name.value).toSet
      _ <- Console.printLine(s"[llm-loop] Exposing ${tools.size} raw MCP tools to Bedrock.")
      result <- Bedrock.dynamicLoop(Prompt, tools) { (name, input) =>
        val toolName = name.unwrap
        for
          _ <- ZIO.fail(IllegalArgumentException(s"Unknown MCP tool: $toolName")).unless(available.contains(toolName))
          arguments <- ZIO.fromEither(input.asJsonObject).mapError(IllegalArgumentException(_))
          output <- invokeRaw(mcp, toolName, arguments, intervals)
        yield DynamicToolResult.text(output)
      }.maxIterations(20).text.provideSomeLayer[HttpClient](Converse.configured)
      finished <- Clock.nanoTime
      recorded <- intervals.get
      toolsTiming = summarizeIntervals(recorded)
      totalTimeMs = (finished - started) / 1000000L
      bedrockTimeMs = result.turns.map(turn => turn.metrics.latencyMs).sum
      orchestrationTimeMs = math.max(0L, totalTimeMs - bedrockTimeMs - toolsTiming.wallMs)
      _ <- printMetrics(result, "llm-loop")
      _ <- Console.printLine(
        s"[llm-loop] timing: bedrockTimeMs=$bedrockTimeMs, toolCalls=${toolsTiming.calls}, " +
          s"toolTimeMs=${toolsTiming.wallMs}, summedToolTimeMs=${toolsTiming.summedMs}, " +
          s"orchestrationTimeMs=$orchestrationTimeMs, totalTimeMs=$totalTimeMs"
      )
    yield ()

  // ----- Nested Jev tool: outer Bedrock agent, inner exact-only Jev evidence loop. -----

  private def jevToolProgram(filterMode: orchestration.EvidenceFilterMode) = ZIO.scoped:
    val filterModeName = filterMode match
      case orchestration.EvidenceFilterMode.Jev => "jev-filters"
      case orchestration.EvidenceFilterMode.Llm => "llm-filters"
    for
      fullBodies <- zio.System.env("JEV_FULL_EXCHANGE_LOGS").map(_.contains("true"))
      started <- Clock.nanoTime
      nestedRuns <- Ref.make(Vector.empty[orchestration.OrchestrationEvidenceResult])
      mcp <- McpClient.connect(McpUrl)
      definitions <- mcp.listTools
      catalog <- orchestration.McpCatalog.fromDefinitions(definitions)
      initialInputSchema = orchestration.Catalog.initialInputSchema(catalog)
      syntheticTool = Tool.dynamic(
        ToolName("orchestrate_with_jev"),
        "Run a schema-safe Jev evidence loop over the provided operation catalog. Supply any known operation inputs; if evidence resolves another prerequisite, call again with that value.",
        initialInputSchema,
      )
      result <- (Bedrock.dynamicLoop(Prompt, List(syntheticTool)) { (name, toolInput) =>
        for
          _ <- ZIO.fail(IllegalArgumentException(s"Unknown synthetic tool: ${name.unwrap}"))
            .unless(name == syntheticTool.name)
          input <- ZIO.fromEither(toolInput.asJsonObject).mapError(IllegalArgumentException(_))
          operationInvoker <- orchestration.McpOperationInvoker.fromClient(definitions, mcp)
          baseJevClient <- ZIO.service[TypeSafeAI.Client]
          tracker <- orchestration.JevPhysicalTracker.make
          exchangeLogger = orchestration.JevExchangeLogger.selected(fullBodies)
          combinedObserver = TypeSafeAI.ExchangeObserver.fromFunction(observation =>
            exchangeLogger.observe(observation) *> tracker.observer.observe(observation)
          )
          invocationJevLayer = ZLayer.succeed(baseJevClient) >>> TypeSafeAI.Client.observed(combinedObserver)
          filterLlm <- filterMode match
            case orchestration.EvidenceFilterMode.Jev => ZIO.none
            case orchestration.EvidenceFilterMode.Llm =>
              for
                transport <- orchestration.BedrockLlmTransport.make
                invoker <- orchestration.InternalLlmInvoker.make(transport)
              yield Some(invoker)
          nested <- orchestration.GenericOrchestrator.runEvidence(
            Prompt,
            catalog,
            input,
            initialInputSchema,
            operationInvoker,
            orchestration.OrchestrationConfig(
              jevTurnRetries = 2,
              jevFilterVariantThreshold = 1024,
            ),
            tracker.metrics,
            filterMode = filterMode,
            filterLlm = filterLlm,
          ).provideLayer(invocationJevLayer)
          _ <- nestedRuns.update(_ :+ nested)
        yield DynamicToolResult(List(ToolResultBlock.json[Json](Json.Obj("evidence" -> nested.evidence))))
      }.maxIterations(6).text).provideSomeLayer[HttpClient](Converse.configured ++ TypeSafeAI.Client.live)
      finished <- Clock.nanoTime
      runs <- nestedRuns.get
      _ <- printMetrics(result, s"jev-tool:$filterModeName")
      _ <- ZIO.foreachDiscard(runs.zipWithIndex): (nested, index) =>
        Console.printLine(
          s"[jev-tool:$filterModeName:inner:${index + 1}] actionTrace=${nested.actionTrace.mkString(" -> ")} " +
            s"metrics=${nested.metrics.toJson.toJson}"
        )
      _ <- Console.printLine(s"[jev-tool:$filterModeName] nestedRuns=${runs.size}, totalTimeMs=${(finished - started) / 1000000L}")
    yield ()
  // ----- Flipped mode: Jev plans generic host-owned transitions; Bedrock extracts and summarizes. -----

  private def jevLoopProgram(mode: orchestration.OrchestrationMode) = ZIO.scoped:
    for
      fullBodies <- zio.System.env("JEV_FULL_EXCHANGE_LOGS").map(_.contains("true"))
      tracker <- orchestration.JevPhysicalTracker.make
      started <- Clock.nanoTime
      mcp <- McpClient.connect(McpUrl)
      definitions <- mcp.listTools
      catalog <- orchestration.McpCatalog.fromDefinitions(definitions)
      operationInvoker <- orchestration.McpOperationInvoker.fromClient(definitions, mcp)
      exchangeLogger = orchestration.JevExchangeLogger.selected(fullBodies)
      combinedObserver = TypeSafeAI.ExchangeObserver.fromFunction(observation =>
        exchangeLogger.observe(observation) *> tracker.observer.observe(observation)
      )
      jevLayer = TypeSafeAI.Client.live >>> TypeSafeAI.Client.observed(combinedObserver)
      result <- (for
        transport <- orchestration.BedrockLlmTransport.make
        internalLlm <- orchestration.InternalLlmInvoker.make(transport)
        result <- orchestration.GenericOrchestrator.runMode(
          Prompt,
          catalog,
          operationInvoker,
          internalLlm,
          mode,
          orchestration.OrchestrationConfig(
            jevTurnRetries = 2,
            jevFilterVariantThreshold = if mode.forceLlmFilters then 0 else 1024,
          ),
          tracker.metrics,
        )
      yield result).provideSomeLayer[HttpClient](Converse.configured ++ jevLayer)
      finished <- Clock.nanoTime
      _ <- Console.printLine(
        s"[jev-loop:${result.metrics.mode.wireName}] Jev logical turns=${result.planningTurns.size}; " +
          s"auditSnapshots=${result.planningAudit.size}"
      )
      _ <- Console.printLine(s"[jev-loop] action trace: ${result.actionTrace.mkString(" -> ")}")
      _ <- Console.printLine(s"[jev-loop] workflow:\n${result.workflow.toJson.toJsonPretty}")
      _ <- Console.printLine(s"\n${result.finalText}")
      _ <- Console.printLine(s"\n[jev-loop] metrics: ${result.metrics.toJson.toJson}")
    yield ()

  def parseJevLoopMode(args: Chunk[String]): Either[String, orchestration.OrchestrationMode] =
    orchestration.OrchestrationMode.parseCli(args)

  def run = (ZIOAppArgs.getArgs.flatMap:
    case Chunk("llm-loop")                => llmLoopProgram
    case Chunk("original")                => llmLoopProgram
    case Chunk("jev-tool")                => jevToolProgram(orchestration.EvidenceFilterMode.Jev)
    case Chunk("jev-tool", "jev-filters")  => jevToolProgram(orchestration.EvidenceFilterMode.Jev)
    case Chunk("jev-tool", "llm-filters")  => jevToolProgram(orchestration.EvidenceFilterMode.Llm)
    case Chunk("jev-loop")                            => jevLoopProgram(orchestration.OrchestrationMode.Plan)
    case Chunk("jev-loop", "plan")                    => jevLoopProgram(orchestration.OrchestrationMode.Plan)
    case Chunk("jev-loop", "no-plan")                 => jevLoopProgram(orchestration.OrchestrationMode.NoPlan)
    case Chunk("jev-loop", "plan-llm-filters")        => jevLoopProgram(orchestration.OrchestrationMode.PlanLlmFilters)
    case Chunk("jev-loop", "no-plan-llm-filters")     => jevLoopProgram(orchestration.OrchestrationMode.NoPlanLlmFilters)
    case args =>
      Console.printLineError(
        s"Usage: run llm-loop | run jev-tool [jev-filters|llm-filters] | run jev-loop [plan|no-plan|plan-llm-filters|no-plan-llm-filters]; received: ${args.mkString(" ")}"
      ) *> ZIO.fail(IllegalArgumentException("Select an LLM loop, Jev tool/filter mode, or Jev loop mode"))
  ).provideSomeLayer[ZIOAppArgs](HttpClient.default)
