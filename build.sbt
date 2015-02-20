import android.Keys._
import android.Dependencies.{LibraryDependency, aar}

lazy val commonSettings = Seq(
        version := "0.1-SNAPSHOT",
        scalaVersion := "2.11.5"
    )

lazy val demo = crossProject
    .crossType(CrossType.Full)
    .in(file("demo"))
    
    /* Common settings */
    
    .settings(
        commonSettings: _*
    )
    .settings(        
        persistLauncher in Compile := true,
        persistLauncher in Test := false,
        testFrameworks += new TestFramework("utest.runner.Framework"),
        resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
        libraryDependencies ++= Seq(
            "com.github.olivierblanvillain" %%% "transport-core" % "0.1-SNAPSHOT",
            "com.lihaoyi" %%% "utest" % "0.3.0" % "test"
        )
    )
    
    /* JavaScript settings */
    
    .jsSettings(
        skip in packageJSDependencies := false,
        libraryDependencies ++= Seq(
            "org.scala-js" %%% "scalajs-dom" % "0.8.0",
            "com.github.olivierblanvillain" %%% "transport-javascript" % "0.1-SNAPSHOT"
        )
    )
    
    /* Standard JVM settings */
    
    .jvmSettings(
        LWJGLPlugin.lwjglSettings: _*
    )
    .jvmSettings(
        libraryDependencies ++= Seq(
            "com.github.olivierblanvillain" %%% "transport-tyrus" % "0.1-SNAPSHOT"
        )
    )
    
lazy val demoJVM = demo.jvm
lazy val demoJS = demo.js

lazy val demoAndroid = project
    .in(file("demo/android"))
    .settings(
        commonSettings: _*
    )
    .settings(
        android.Plugin.androidBuild: _*
    )
    .settings(
        platformTarget in Android := "android-19",
        proguardScala in Android := true,
        proguardOptions in Android ++= Seq(
            "-ignorewarnings",
            "-keep class scala.Dynamic"
        ),
        libraryDependencies ++= Seq(
            aar("com.google.android.gms" % "play-services" % "4.0.30"),
            aar("com.android.support" % "support-v4" % "r7")
        )
    )
    
