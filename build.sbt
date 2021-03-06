import com.typesafe.sbt.SbtStartScript

scalaVersion := "2.9.2"

sbtVersion := "0.12.1"

name := "graphsc"

scalacOptions += "-deprecation"

mainClass in Compile := Some("graphsc.app.EqProverApp")

mainClass in oneJar := Some("graphsc.app.EqProverApp")

libraryDependencies += "org.scalatest" %% "scalatest" % "1.8" % "test"

libraryDependencies += "junit" % "junit" % "4.8.1" % "test"

libraryDependencies += "org.rogach" %% "scallop" % "0.8.1"

seq(com.github.retronym.SbtOneJar.oneJarSettings: _*)

seq(SbtStartScript.startScriptForClassesSettings: _*)

//seq(ScctPlugin.instrumentSettings : _*)
