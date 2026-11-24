package ru.bysonic.chistytv.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Каталог для «Установить приложение» — тот же data/apps.json, что у chistytv.ps1. Прямых ссылок на APK в нём нет. */
@Serializable
data class AppCatalog(
  val version: Int = 0,
  val updated: String = "",
  val apps: List<AppEntry> = emptyList(),
) {
  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): AppCatalog = json.decodeFromString(serializer(), text.removePrefix("﻿"))
  }
}

@Serializable
data class AppEntry(
  val id: String,
  val title: String,
  @SerialName("package") val pkg: String,
  /** Официальная страница, откуда человек сам скачивает APK */
  val page: String = "",
  val note: String = "",
  /** SHA-256 сертификата издателя; указан — файл с другой подписью не ставим */
  val cert: String? = null,
)

/** Что за файл выбрал человек: обычный APK, набор из нескольких (.xapk, .apks, .apkm) или не годится */
object ApkCheck {
  private val BUNDLES = listOf(".xapk", ".apks", ".apkm")

  sealed interface Kind {
    data object Single : Kind

    /** apks — пути APK внутри архива */
    data class Bundle(val apks: List<String>) : Kind

    data class Invalid(val reason: String) : Kind
  }

  fun inspect(file: File, displayName: String = file.name): Kind {
    if (!file.isFile) return Kind.Invalid("файл не найден")
    val names = try {
      ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
    } catch (e: ZipException) {
      return Kind.Invalid("файл повреждён или это не APK (не открывается как архив).")
    } catch (e: IOException) {
      return Kind.Invalid("файл повреждён или это не APK (не открывается как архив).")
    }
    if ("AndroidManifest.xml" in names) return Kind.Single
    val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
    if (apks.isNotEmpty()) return Kind.Bundle(apks)
    if (BUNDLES.any { displayName.lowercase().endsWith(it) }) return Kind.Invalid("в наборе нет ни одного APK-файла.")
    return Kind.Invalid("в файле нет AndroidManifest.xml — это не APK.")
  }

  private val ABI_TOKENS = listOf("arm64_v8a", "armeabi_v7a", "x86_64", "x86")

  /**
   * Какие APK из набора ставить на этот телевизор. Файлы под чужой процессор (config.x86.apk на ARM) отбрасываем:
   * Android не принимает набор, в котором есть части под другую архитектуру. Языки и плотность экрана оставляем все.
   */
  fun pickSplits(apks: List<String>, abiList: String): List<String> {
    val tvAbis = abiList.lowercase().replace('-', '_').split(',').map { it.trim() }.toSet()
    return apks.filter { path ->
      val name = path.substringAfterLast('/').lowercase().replace('-', '_')
      val token = ABI_TOKENS.firstOrNull { name.contains(it) }
      token == null || token in tvAbis
    }
  }

  /** Распаковать выбранные APK в папку [dir]; имена свои, чтобы путь из архива не мог выйти за её пределы */
  fun extract(file: File, apks: List<String>, dir: File): List<File> {
    dir.mkdirs()
    return ZipFile(file).use { zip ->
      apks.mapIndexed { i, name ->
        val entry = zip.getEntry(name) ?: throw IOException("в наборе нет $name")
        // base.apk первым: pm install-multiple берёт основной файл из списка по порядку
        File(dir, "part$i.apk").also { out -> zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } } }
      }
    }
  }
}
