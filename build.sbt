name := "hello-zio-typesafe-ai"

scalaVersion := "3.10.1"

libraryDependencies ++= Seq(
  "com.jamesward" %% "zio-bedrock" % "0.2.0",
  "com.jamesward" %% "zio-decisions" % "0.2.0",
  "com.jamesward" %% "zio-http-mcp" % "0.15.0",

  "dev.zio" %% "zio-test" % "2.1.27" % Test,
  "dev.zio" %% "zio-test-sbt" % "2.1.27" % Test,
)

fork := true

// SkillsJars: extract agent Skills with `./sbta extractSkillsJars`
skillsJarsOutputDir := Some(file(".kiro/skills"))

libraryDependencies += "com.jamesward" % "skills" % "0.0.27" % Skills
