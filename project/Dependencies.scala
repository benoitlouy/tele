import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbt._

object Dependencies {
  object V {
    val collectionCompat = "2.6.0"
    val kindProjector = "0.13.2"
    val kinesis = "2.17.100"
    val kinesisClient = "2.3.9"
    val catsEffect = "3.3.0"
    val fs2 = "3.2.3"
    val munitCatsEffect = "1.0.7"
    val logback = "1.2.8"
    val circe = "0.14.1"
  }

  object D {
    val collectionCompat = Def.setting("org.scala-lang.modules" %%% "scala-collection-compat" % V.collectionCompat)

    val kinesis = "software.amazon.awssdk" % "kinesis" % V.kinesis
    val kinesisClient = "software.amazon.kinesis" % "amazon-kinesis-client" % V.kinesisClient

    val catsEffect = Def.setting("org.typelevel" %%% "cats-effect" % V.catsEffect)
    val fs2 = Def.setting("co.fs2" %%% "fs2-core" % V.fs2)
    val munitCatsEffect = Def.setting("org.typelevel" %%% "munit-cats-effect-3" % V.munitCatsEffect)

    val logback = "ch.qos.logback" % "logback-classic" % V.logback

    val circeCore = Def.setting("io.circe" %%% "circe-core" % V.circe)
    val circeParser = Def.setting("io.circe" %%% "circe-parser" % V.circe)
    val circeGeneric = Def.setting("io.circe" %%% "circe-generic" % V.circe)
  }
}
