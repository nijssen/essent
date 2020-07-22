organization := "lbl.gov"

version := "0.1"

name := "essent"

scalaVersion := "2.12.4"

scalacOptions ++= Seq("-deprecation", "-unchecked")

libraryDependencies += "com.github.scopt" %% "scopt" % "3.6.0"

libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2"

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.1" % "test"

libraryDependencies += "org.json4s" %% "json4s-native" % "3.5.3"

libraryDependencies += "edu.berkeley.cs" %% "firrtl" % "1.1.3"


// Assembly

assemblyJarName in assembly := "essent.jar"

assemblyOutputPath in assembly := file("./utils/bin/essent.jar")


// Ignore disabled .scala files
unmanagedSources / excludeFilter := HiddenFileFilter || "*disabled*.scala"
