name := "GnParser"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.11.6"

lazy val root = (project in file(".")).
  enablePlugins(BuildInfoPlugin).
  settings(
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "org.globalnames.parser",
    test in assembly := {}
  )

libraryDependencies ++= Seq(
  "org.json4s" %% "json4s-jackson" % "3.2.11",
  "org.littleshoot" % "littleshoot-commons-id" % "1.0.3",
  "commons-lang" % "commons-lang" % "2.6",
  "org.parboiled" %% "parboiled" % "2.1.0",
  "org.specs2" %% "specs2-core" % "3.3.1" % "test"
)

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"

scalacOptions in Test ++= Seq("-Yrangepos")


fork in run := true
