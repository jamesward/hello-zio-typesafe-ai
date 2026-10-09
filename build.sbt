name := "hello-zio-typesafe-ai"

scalaVersion := "3.9.0"

libraryDependencies ++= Seq(
  "com.jamesward" %% "zio-bedrock" % "0.1.0",
  "com.jamesward" %% "zio-typesafe-ai" % "0.1.0",
  "com.jamesward" %% "zio-http-mcp" % "0.8.3",

  "dev.zio" %% "zio-test" % "2.1.26" % Test,
  "dev.zio" %% "zio-test-sbt" % "2.1.26" % Test,
)

fork := true

// sbt-mcp (loopback-only: its tools can execute build tasks)
ThisBuild / mcpEnabled := true
ThisBuild / mcpHost := "127.0.0.1"
ThisBuild / mcpPort := 5105

// SkillsJars: extract agent Skills with `./sbt extractSkillsJars`
skillsJarsOutputDir := Some(file(".kiro/skills"))

libraryDependencies += "com.jamesward" % "skills" % "0.0.11" % Skills
