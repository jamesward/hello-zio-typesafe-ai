package com.jamesward.zio_typesafe_ai.orchestration

import com.jamesward.ziohttp.mcp.{CallToolResult, ToolContent, ToolDefinition}
import com.jamesward.ziohttp.mcp.client.McpClient
import com.jamesward.zio_typesafe_ai.orchestration.runtime.OperationInvoker
import zio.*
import zio.json.*
import zio.json.ast.Json

object McpCatalog:
  def fromDefinitions(definitions: Chunk[ToolDefinition]): IO[Throwable, Vector[OperationSpec]] =
    val names = definitions.map(_.name.value).toVector
    val duplicates = names.groupMapReduce(identity)(_ => 1)(_ + _).collect:
      case (name, count) if count > 1 => name
    .toVector.sorted
    if duplicates.nonEmpty then ZIO.fail(IllegalArgumentException(s"Duplicate MCP operations: ${duplicates.mkString(", ")}"))
    else if names.exists(Set(Catalog.ExtractName, Catalog.FilterName, Catalog.SummarizeName)) then
      ZIO.fail(IllegalArgumentException(
        s"MCP operation names may not shadow internal capabilities '${Catalog.ExtractName}', '${Catalog.FilterName}', or '${Catalog.SummarizeName}'"
      ))
    else ZIO.succeed(
      definitions.toVector.map: definition =>
        OperationSpec(
          definition.name.value,
          definition.description.getOrElse(definition.name.value),
          definition.inputSchema,
          definition.outputSchema.getOrElse(Json.Obj()),
          CapabilityKind.Mcp,
        )
      .sortBy(_.name) ++ Vector(Catalog.extract, Catalog.synthesizeFilter, Catalog.summarize)
    )

object McpOrchestrator:
  def runMode(
    prompt: String,
    definitions: Chunk[ToolDefinition],
    operationInvoker: OperationInvoker,
    internalLlm: InternalLlmInvoker,
    mode: OrchestrationMode,
    config: OrchestrationConfig = OrchestrationConfig(),
    physicalMetrics: UIO[JevPhysicalMetrics] = ZIO.succeed(JevPhysicalMetrics()),
    planningObserver: com.jamesward.zio_typesafe_ai.TypeSafeAI.LoopObserver[GenericPlanner.PlanningError, model.Workflow] = GenericPlanner.semanticLoggingObserver,
  ): ZIO[com.jamesward.zio_typesafe_ai.TypeSafeAI.Client, Throwable, OrchestrationResult] =
    McpCatalog.fromDefinitions(definitions).flatMap(catalog =>
      GenericOrchestrator.runMode(prompt, catalog, operationInvoker, internalLlm, mode, config, physicalMetrics, planningObserver)
    )

trait RawMcpInvoker:
  def call(operation: String, arguments: Json.Obj): Task[CallToolResult]

object RawMcpInvoker:
  def fromClient(client: McpClient): RawMcpInvoker = new RawMcpInvoker:
    def call(operation: String, arguments: Json.Obj): Task[CallToolResult] = client.callTool(operation, arguments)

final class McpOperationInvoker private (
  schemas: Map[String, (Json.Obj, Json.Obj)],
  raw: RawMcpInvoker,
  physicalTracker: McpPhysicalTracker,
) extends OperationInvoker, McpPhysicalMetricsProvider:
  def call(operation: String, arguments: Json.Obj): Task[Json] =
    for
      schemas <- ZIO.fromOption(schemas.get(operation)).orElseFail(IllegalArgumentException(s"Unknown MCP operation '$operation'"))
      (inputSchema, outputSchema) = schemas
      _ <- ZIO.fromEither(SchemaModel.validateObject(arguments, inputSchema)).mapError(errors =>
        IllegalArgumentException(s"Arguments for '$operation' failed local schema validation: ${errors.mkString("; ")}")
      )
      started <- Clock.nanoTime
      rawExit <- raw.call(operation, arguments).exit
      finished <- Clock.nanoTime
      physicalSuccess = rawExit.toEither.exists(result => !result.isError.contains(true))
      _ <- physicalTracker.record(started, finished, physicalSuccess)
      result <- ZIO.done(rawExit)
      normalized <- McpOperationInvoker.normalize(operation, result)
      _ <- ZIO.fromEither(SchemaModel.validate(normalized, outputSchema)).mapError(errors =>
        IllegalArgumentException(s"Output from '$operation' failed declared schema validation: ${errors.mkString("; ")}")
      )
    yield normalized

  def mcpPhysicalMetrics: UIO[McpPhysicalMetrics] = physicalTracker.metrics

object McpOperationInvoker:
  def make(definitions: Chunk[ToolDefinition], raw: RawMcpInvoker): IO[Throwable, McpOperationInvoker] =
    val entries = definitions.map(definition => definition.name.value -> (definition.inputSchema -> definition.outputSchema.getOrElse(Json.Obj()))).toVector
    val duplicates = entries.groupMapReduce(_._1)(_ => 1)(_ + _).collect { case (name, count) if count > 1 => name }
    if duplicates.nonEmpty then ZIO.fail(IllegalArgumentException(s"Duplicate MCP schemas: ${duplicates.mkString(", ")}"))
    else McpPhysicalTracker.make.map(new McpOperationInvoker(entries.toMap, raw, _))

  def fromClient(definitions: Chunk[ToolDefinition], client: McpClient): IO[Throwable, McpOperationInvoker] =
    make(definitions, RawMcpInvoker.fromClient(client))

  def normalize(operation: String, result: CallToolResult): Task[Json] =
    val text = result.content.collect { case ToolContent.Text(value, _) => value }.mkString("\n")
    if result.isError.contains(true) then ZIO.fail(IllegalStateException(s"MCP operation '$operation' failed: $text"))
    else result.structuredContent match
      case Some(value: Json.Obj) => ZIO.succeed(value)
      case Some(value)           => ZIO.succeed(Json.Obj("result" -> value))
      case None => text.fromJson[Json] match
        case Right(value: Json.Obj) => ZIO.succeed(value)
        case Right(value)           => ZIO.succeed(Json.Obj("result" -> value))
        case Left(_)                => ZIO.succeed(Json.Obj("result" -> Json.Str(text)))
