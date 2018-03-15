package scala.meta.gen

import java.io.File
import java.nio.file.Path

import org.scalameta.scalagen.Runner
import sbt._
import sbt.Keys._

import scala.meta._

object ScalagenTags {
  val SourceGeneration = Tags.Tag("source-generation")
}

object ScalagenPlugin extends AutoPlugin {
  override def trigger = allRequirements

  object autoImport {

    lazy val scalagenListGenerators =
      taskKey[Unit]("Lists all generators enabled")

    lazy val scalagen =
      taskKey[Seq[File]]("Applies scalagen Generators to sources")

    lazy val scalagenRecursive =
      settingKey[Boolean]("Should scalagen recursively generate definitions")

    lazy val scalagenGenerators = settingKey[Set[Generator]]("Set of Generators to use")

    lazy val generateTask = Def.task {
      val c = GeneratorRunner(
        (unmanagedSources in Compile).value,
        scalagenGenerators.value,
        scalagenRecursive.value,
        streams.value,
        sourceDirectory.value.toPath,
        sourceManaged.value.toPath)
      val t = GeneratorRunner(
        (unmanagedSources in Test).value,
        scalagenGenerators.value,
        scalagenRecursive.value,
        streams.value,
        sourceDirectory.value.toPath,
        sourceManaged.value.toPath)        
      c ++ t
    }
  }

  import autoImport._

  lazy val baseGenerateSettings: Seq[Def.Setting[_]] = Seq(
    scalagenGenerators := Set.empty,
    scalagenRecursive := false,
    scalagen := generateTask.value,
    (sources in Compile) := {
      GeneratorRunner.deleteNoLongerManaged(
        (managedSources in Compile).value,
        scalagenGenerators.value,
        (sourceDirectory in Compile).value.toPath,
        (sourceManaged in Compile).value.toPath
      )
      val overrided = GeneratorRunner.overridedSources(
        (managedSources in Compile).value,
        (sourceDirectory in Compile).value.toPath,
        (sourceManaged in Compile).value.toPath
      )
      ((sources in Compile).value.toSet[File] -- overrided.toSet[File]).toSeq
    },
    (sources in Test) := {
      GeneratorRunner.deleteNoLongerManaged(
        (managedSources in Test).value,
        scalagenGenerators.value,
        (sourceDirectory in Test).value.toPath,
        (sourceManaged in Test).value.toPath
      )      
      val overrided = GeneratorRunner.overridedSources(
        (managedSources in Test).value,
        (sourceDirectory in Test).value.toPath,
        (sourceManaged in Test).value.toPath
      )
      ((sources in Test).value.toSet[File] -- overrided.toSet[File]).toSeq
    },    
    sourceGenerators in Compile += scalagen,
    sourceGenerators in Test += scalagen
  )

  override lazy val projectSettings: Seq[Def.Setting[_]] =
    baseGenerateSettings
}

object GeneratorRunner {
 def overridedSources(
      input: Seq[File],
      sourcePath: Path,
      targetPath: Path): Seq[File] = {
      input.map{f =>
        val relative = targetPath.relativize(f.toPath)
        val originalFile = sourcePath.resolve(relative).toFile
        originalFile
      }        
  }

  def deleteNoLongerManaged(
      managed: Seq[File],
      generators: Set[Generator],
      sourcePath: Path,
      targetPath: Path) {

    val allManaged = (targetPath.toFile ** "*.scala").get
    val del = allManaged.filter{ case f => 
      !managed.contains(f)
    //   val t = scala.util.Try {
    //     val relative = targetPath.relativize(f.toPath)
    //     val originalFile = sourcePath.resolve(relative).toFile
    //     val n = originalFile.exists && {
    //       val text = IO.read(originalFile)
    //       generators.isEmpty || !generators.exists{g => text.contains(s"@${g.name}")}
    //     }
    //     println(f+" "+n+" "+originalFile.exists)
    //     !originalFile.exists || n
    //   }
    //   t.getOrElse(false)
    }
    del.foreach{f => IO.delete(f) }    
  }

  def apply(
      input: Seq[File],
      generators: Set[Generator],
      recurse: Boolean,
      strm: TaskStreams,
      sourcePath: Path,
      targetPath: Path): Seq[File] = {
    val validInput = input.par.flatMap{f =>
      val text = IO.read(f)
      if(!generators.isEmpty && generators.exists{g => text.contains(s"@${g.name}")}) Some(f -> text) else None
    }

    validInput.par
      .map{ case (f,text) =>
        val runner = Runner(generators, recurse)
        val parsed = text.parse[Source]
        val relative = sourcePath.relativize(f.toPath)
        val outputFile = targetPath.resolve(relative).toFile
        if( f.lastModified() > outputFile.lastModified() ) {
          val result: String = parsed.toOption
            .map(runner.transform(_).syntax)
            .getOrElse {
              strm.log.warn(s"Skipped ${f.name} as it could not be parsed")
              text
            }
          IO.write(outputFile, result)
        }
        outputFile
      }
      .to[Seq]
  }
}
