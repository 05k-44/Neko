package eu.kanade.tachiyomi.source.online.handlers

import com.elvishew.xlog.XLog
import com.github.salomonbrys.kotson.nullInt
import com.github.salomonbrys.kotson.obj
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.consumeBody
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.handlers.serializers.ApiMangaSerializer
import eu.kanade.tachiyomi.source.online.handlers.serializers.ChapterSerializer
import eu.kanade.tachiyomi.source.online.utils.MdLang
import eu.kanade.tachiyomi.source.online.utils.MdUtil
import okhttp3.Response
import org.jsoup.Jsoup
import java.util.Date
import kotlin.math.floor

class ApiMangaParser(val langs: List<String>) {

    fun mangaDetailsParse(response: Response, forceLatestCover: Boolean): SManga {
        return mangaDetailsParse(response.body!!.string(), forceLatestCover)
    }

    /**
     * Parse the manga details json into manga object
     */
    fun mangaDetailsParse(jsonData: String, forceLatestCover: Boolean): SManga {
        try {
            val manga = SManga.create()
            val networkApiManga = MdUtil.jsonParser.decodeFromString(ApiMangaSerializer.serializer(), jsonData)
            val networkManga = networkApiManga.manga
            manga.title = MdUtil.cleanString(networkManga.title)

            val coverList = networkManga.covers
            manga.thumbnail_url = MdUtil.cdnUrl +
                if (forceLatestCover && coverList.isNotEmpty()) {
                    coverList.last()
                } else {
                    networkManga.cover_url
                }

            manga.description = MdUtil.cleanDescription(networkManga.description)
            manga.author = MdUtil.cleanString(networkManga.author)
            manga.artist = MdUtil.cleanString(networkManga.artist)
            manga.lang_flag = networkManga.lang_flag
            val lastChapter = networkManga.last_chapter?.toFloatOrNull()
            lastChapter?.let {
                manga.last_chapter_number = floor(it).toInt()
            }

            networkManga.rating?.let {
                manga.rating = it.bayesian ?: it.mean
                manga.users = it.users
            }
            networkManga.links?.let {
                it.al?.let { manga.anilist_id = it }
                it.kt?.let { manga.kitsu_id = it }
                it.mal?.let { manga.my_anime_list_id = it }
                it.mu?.let { manga.manga_updates_id = it }
                it.ap?.let { manga.anime_planet_id = it }
            }
            val filteredChapters = filterChapterForChecking(networkApiManga)

            val tempStatus = parseStatus(networkManga.status)
            val publishedOrCancelled =
                tempStatus == SManga.PUBLICATION_COMPLETE || tempStatus == SManga.CANCELLED
            if (publishedOrCancelled && isMangaCompleted(networkApiManga, filteredChapters)) {
                manga.status = SManga.COMPLETED
                manga.missing_chapters = null
            } else {
                manga.status = tempStatus
            }

            val demographic = FilterHandler.demographics().filter { it.id == networkManga.demographic }.firstOrNull()

            val genres = networkManga.genres.mapNotNull { FilterHandler.allTypes[it.toString()] }
                .toMutableList()

            if (demographic != null) {
                genres.add(0, demographic.name)
            }

            if (networkManga.hentai == 1) {
                genres.add("Hentai")
            }

            manga.genre = genres.joinToString(", ")

            return manga
        } catch (e: Exception) {
            XLog.e(e)
            throw e
        }
    }

    /**
     * If chapter title is oneshot or a chapter exists which matches the last chapter in the required language
     * return manga is complete
     */
    private fun isMangaCompleted(
        serializer: ApiMangaSerializer,
        filteredChapters: List<Map.Entry<String, ChapterSerializer>>
    ): Boolean {
        if (filteredChapters.isEmpty() || serializer.manga.last_chapter.isNullOrEmpty()) {
            return false
        }
        val finalChapterNumber = serializer.manga.last_chapter!!
        if (MdUtil.validOneShotFinalChapters.contains(finalChapterNumber)) {
            filteredChapters.firstOrNull()?.let {
                if (isOneShot(it.value, finalChapterNumber)) {
                    return true
                }
            }
        }
        val removeOneshots = filteredChapters.filter { !it.value.chapter.isNullOrBlank() }
        return removeOneshots.size.toString() == floor(finalChapterNumber.toDouble()).toInt().toString()
    }

    private fun filterChapterForChecking(serializer: ApiMangaSerializer): List<Map.Entry<String, ChapterSerializer>> {
        serializer.chapter ?: return emptyList()
        return serializer.chapter.entries
            .filter { langs.contains(it.value.lang_code) }
            .filter {
                it.value.chapter?.let { chapterNumber ->
                    if (chapterNumber.toIntOrNull() == null) {
                        return@filter false
                    }
                    return@filter true
                }
                return@filter false
            }.distinctBy { it.value.chapter }
    }

    private fun isOneShot(chapter: ChapterSerializer, finalChapterNumber: String): Boolean {
        return chapter.title.equals("oneshot", true) ||
            ((chapter.chapter.isNullOrEmpty() || chapter.chapter == "0") && MdUtil.validOneShotFinalChapters.contains(finalChapterNumber))
    }

    private fun parseStatus(status: Int) = when (status) {
        1 -> SManga.ONGOING
        2 -> SManga.PUBLICATION_COMPLETE
        3 -> SManga.CANCELLED
        4 -> SManga.HIATUS
        else -> SManga.UNKNOWN
    }

    /**
     * Parse for the random manga id from the [MdUtil.randMangaPage] response.
     */
    fun randomMangaIdParse(response: Response): String {
        val randMangaUrl = Jsoup.parse(response.consumeBody())
            .select("link[rel=canonical]")
            .attr("href")
        return MdUtil.getMangaId(randMangaUrl)
    }

    fun chapterListParse(response: Response): List<SChapter> {
        return chapterListParse(response.body!!.string())
    }

    fun chapterListParse(jsonData: String): List<SChapter> {
        val now = Date().time
        val networkApiManga = MdUtil.jsonParser.decodeFromString(ApiMangaSerializer.serializer(), jsonData)
        val networkManga = networkApiManga.manga
        val networkChapters = networkApiManga.chapter
        if (networkChapters.isNullOrEmpty()) {
            return listOf()
        }
        val status = networkManga.status

        val finalChapterNumber = networkManga.last_chapter!!

        val chapters = mutableListOf<SChapter>()

        // Skip chapters that don't match the desired language, or are future releases

        val chapLangs = MdLang.values().filter { langs.contains(it.dexLang) }
        networkChapters.filter { langs.contains(it.value.lang_code) && (it.value.timestamp * 1000) <= now }
            .mapTo(chapters) { mapChapter(it.key, it.value, finalChapterNumber, status, chapLangs, networkChapters.size) }

        return chapters
    }

    fun chapterParseForMangaId(response: Response): Int {
        try {
            if (response.code != 200) throw Exception("HTTP error ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isEmpty()) {
                throw Exception("Null Response")
            }

            val jsonObject = JsonParser.parseString(body).obj
            return jsonObject["manga_id"]?.nullInt ?: throw Exception("No manga associated with chapter")
        } catch (e: Exception) {
            XLog.e(e)
            throw e
        }
    }

    private fun mapChapter(
        chapterId: String,
        networkChapter: ChapterSerializer,
        finalChapterNumber: String,
        status: Int,
        chapLangs: List<MdLang>,
        totalChapterCount: Int
    ): SChapter {
        val chapter = SChapter.create()
        chapter.url = MdUtil.apiChapter + chapterId
        val chapterName = mutableListOf<String>()
        // Build chapter name

        if (!networkChapter.volume.isNullOrBlank()) {
            val vol = "Vol." + networkChapter.volume
            chapterName.add(vol)
            chapter.vol = vol
        }

        if (!networkChapter.chapter.isNullOrBlank()) {
            val chp = "Ch." + networkChapter.chapter
            chapterName.add(chp)
            chapter.chapter_txt = chp
        }
        if (!networkChapter.title.isNullOrBlank()) {
            if (chapterName.isNotEmpty()) {
                chapterName.add("-")
            }
            chapterName.add(networkChapter.title)
            chapter.chapter_title = MdUtil.cleanString(networkChapter.title)
        }

        // if volume, chapter and title is empty its a oneshot
        if (chapterName.isEmpty()) {
            chapterName.add("Oneshot")
        }
        if ((status == 2 || status == 3)) {
            if ((isOneShot(networkChapter, finalChapterNumber) && totalChapterCount == 1) ||
                networkChapter.chapter == finalChapterNumber && finalChapterNumber.toIntOrNull() != 0
            ) {
                chapterName.add("[END]")
            }
        }

        chapter.name = MdUtil.cleanString(chapterName.joinToString(" "))
        // Convert from unix time
        chapter.date_upload = networkChapter.timestamp * 1000
        val scanlatorName = mutableSetOf<String>()

        networkChapter.group_name?.let {
            scanlatorName.add(it)
        }
        networkChapter.group_name_2?.let {
            scanlatorName.add(it)
        }
        networkChapter.group_name_3?.let {
            scanlatorName.add(it)
        }

        chapter.scanlator = MdUtil.cleanString(MdUtil.getScanlatorString(scanlatorName))

        chapter.mangadex_chapter_id = MdUtil.getChapterId(chapter.url)

        chapter.language = chapLangs.firstOrNull { it.dexLang.equals(networkChapter.lang_code) }?.name

        return chapter
    }
}
