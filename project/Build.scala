import sbt._

object ApplicationBuild extends Build {

  val appName         = "cms"
  val appVersion      = "1.0-SNAPSHOT"

  val appDependencies = Seq(
    "org.reactivemongo" %% "reactivemongo" % "0.10.0",
    "commons-codec" % "commons-codec" % "1.8",
    "org.scalatest" %% "scalatest" % "2.1.0-RC2" % "test",
    "org.mockito" % "mockito-all" % "1.9.0" % "test"
  )

  val main = play.Project(appName, appVersion, appDependencies).settings(
    // Add your own project settings here      
  )
}
