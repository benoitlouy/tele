import sbt._

object Dependencies {
  object V {
    val collectionCompat = "2.6.0"
    val kindProjector = "0.13.2"
    val kinesis = "2.17.100"
    val kinesisClient = "2.3.9"
    val catsEffect = "3.2.9"
    val fs2 = "3.2.3"
    val munitCatsEffect = "1.0.7"
    val logback = "1.2.8"
    val circe = "0.14.1"
  }

  object L {
    val collectionCompat = "org.scala-lang.modules" %% "scala-collection-compat" % V.collectionCompat
    val kindProjector = "org.typelevel" %% "kind-projector" % V.kindProjector

    val kinesis = "software.amazon.awssdk" % "kinesis" % V.kinesis
    val kinesisClient = "software.amazon.kinesis" % "amazon-kinesis-client" % V.kinesisClient

    val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect
    val fs2 = "co.fs2" %% "fs2-core" % V.fs2
    val munitCatsEffect = "org.typelevel" %% "munit-cats-effect-3" % V.munitCatsEffect

    val logback = "ch.qos.logback" % "logback-classic" % V.logback

    val circeCore = "io.circe" %% "circe-core" % V.circe
    val circeParser = "io.circe" %% "circe-parser" % V.circe
    val circeGeneric = "io.circe" %% "circe-generic" % V.circe
  }
}
