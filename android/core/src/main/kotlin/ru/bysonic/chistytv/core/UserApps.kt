package ru.bysonic.chistytv.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
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

/** Годится ли файл как один обычный APK. Проверка идёт до отправки на телевизор. */
object ApkCheck {
  private val BUNDLES = listOf(".xapk", ".apks", ".apkm")

  /** Что не так с файлом; null — файл годится */
  fun problem(file: File, displayName: String = file.name): String? {
    if (!file.isFile) return "файл не найден"
    val lower = displayName.lowercase()
    BUNDLES.firstOrNull { lower.endsWith(it) }?.let {
      return "это набор файлов ($it), а не один APK. Нужен обычный .apk (у приложений для ТВ он обычно один)."
    }
    val names = try {
      ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
    } catch (e: ZipException) {
      return "файл повреждён или это не APK (не открывается как архив)."
    } catch (e: java.io.IOException) {
      return "файл повреждён или это не APK (не открывается как архив)."
    }
    if ("AndroidManifest.xml" in names) return null
    if ("manifest.json" in names || names.any { it.endsWith(".apk") }) return "внутри несколько APK — это набор. Нужен один обычный .apk."
    return "в файле нет AndroidManifest.xml — это не APK."
  }
}
