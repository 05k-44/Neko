package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.net.Uri
import androidx.core.text.isDigitsOnly
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.isMergedChapter
import eu.kanade.tachiyomi.source.online.utils.MdUtil
import eu.kanade.tachiyomi.util.storage.DiskUtil
import timber.log.Timber
import uy.kohesive.injekt.injectLazy

/**
 * This class is used to provide the directories where the downloads should be saved.
 * It uses the following path scheme: /<root downloads dir>/<source name>/<manga>/<chapter>
 *
 * @param context the application context.
 */
class DownloadProvider(private val context: Context) {

    /**
     * Preferences helper.
     */
    private val preferences: PreferencesHelper by injectLazy()

    /**
     * The root directory for downloads.
     */
    private var downloadsDir = preferences.downloadsDirectory().getOrDefault().let {
        val dir = UniFile.fromUri(context, Uri.parse(it))
        DiskUtil.createNoMediaFile(dir, context)
        dir
    }

    init {
        preferences.downloadsDirectory().asObservable().skip(1)
            .subscribe { downloadsDir = UniFile.fromUri(context, Uri.parse(it)) }
    }

    /**
     * Returns the download directory for a manga. For internal use only.
     *
     * @param manga the manga to query.
     * @param source the source of the manga.
     */
    @Synchronized
    internal fun getMangaDir(manga: Manga, source: Source): UniFile {
        try {
            return downloadsDir.createDirectory(getSourceDirName(source))
                .createDirectory(getMangaDirName(manga))
        } catch (e: Exception) {
            Timber.e(e)
            throw Exception(context.getString(R.string.invalid_download_location))
        }
    }

    /**
     * Returns the download directory for a source if it exists.
     *
     * @param source the source to query.
     */
    fun findSourceDir(source: Source): UniFile? {
        return downloadsDir.findFile(getSourceDirName(source))
    }

    /**
     * Returns the download directory for a manga if it exists.
     *
     * @param manga the manga to query.
     * @param source the source of the manga.
     */
    fun findMangaDir(manga: Manga, source: Source): UniFile? {
        val sourceDir = findSourceDir(source)
        return sourceDir?.findFile(getMangaDirName(manga))
    }

    /**
     * Returns the download directory for a chapter if it exists.
     *
     * @param chapter the chapter to query.
     * @param manga the manga of the chapter.
     * @param source the source of the chapter.
     */
    fun findChapterDir(chapter: Chapter, manga: Manga, source: Source): UniFile? {
        val mangaDir = findMangaDir(manga, source)
        return if (chapter.isMergedChapter()) {
            mangaDir?.findFile(getJ2kChapterName(chapter))
        } else {
            var dir = getValidChapterDirNames(chapter).mapNotNull { mangaDir?.findFile(it) }.firstOrNull()
            if (dir == null) {
                dir = mangaDir?.listFiles()?.find { it.name != null && it.name!!.endsWith(chapter.mangadex_chapter_id) }
            }
            dir
        }
    }

    /**
     * Returns a list of downloaded directories for the chapters that exist.
     *
     * @param chapters the chapters to query.
     * @param manga the manga of the chapter.
     * @param source the source of the chapter.
     */
    fun findChapterDirs(chapters: List<Chapter>, manga: Manga, source: Source): List<UniFile> {
        val mangaDir = findMangaDir(manga, source) ?: return emptyList()

        val idHashSet = chapters.map { it.mangadex_chapter_id }.toHashSet()
        val chapterNameHashSet = chapters.map { (it as Chapter).name }.toHashSet()

        return mangaDir.listFiles()!!.asList().filter { file ->
            file.name?.let { fileName ->
                val mangadexId = fileName.substringAfterLast(" - ", "")
                if (mangadexId.isNotEmpty() && mangadexId.isDigitsOnly()) {
                    return@filter idHashSet.contains(mangadexId)
                } else {
                    //dont need to check for j2k specific with scanlator because its format is contains file.name
                    return@filter chapterNameHashSet.contains(file.name!!)
                }
            }
            return@filter false
        }
        /*



        return chapters.mapNotNull { chp ->
            getValidChapterDirNames(chp).mapNotNull { mangaDir.findFile(it) }.firstOrNull()
        }*/
    }

    /**
     * Renames the chapter folders with id's and removes it + null scanlators
     *
     * @param chapters the chapters to query.
     * @param manga the manga of the chapter.
     * @param source the source of the chapter.
     */
    fun renameChapters() {
        val db by injectLazy<DatabaseHelper>()
        val sourceManager by injectLazy<SourceManager>()
        val mangas = db.getLibraryMangas().executeAsBlocking()
        mangas.forEach sfor@{ manga ->
            val sourceId = manga.source
            val source = sourceManager.get(sourceId) ?: return@sfor
            val mangaDir = findMangaDir(manga, source) ?: return@sfor
            mangaDir.listFiles()?.forEach {
                val nameSplit = it.name?.split("_")?.toMutableList() ?: return@sfor
                if (nameSplit.size > 2 && nameSplit.first().first().isDigit()) {
                    nameSplit.removeAt(0)
                    val newName = nameSplit.joinToString("_").removePrefix("null_")
                    it.renameTo(newName)
                }
            }
        }
    }

    fun renameMangaFolder(from: String, to: String, sourceId: Long) {
        val sourceManager by injectLazy<SourceManager>()
        val source = sourceManager.get(sourceId) ?: return
        val sourceDir = findSourceDir(source)
        val mangaDir = sourceDir?.findFile(DiskUtil.buildValidFilename(from))
        mangaDir?.renameTo(to)
    }

    /**
     * Returns a list of all files in manga directory
     *
     * @param chapters the chapters to query.
     * @param manga the manga of the chapter.
     * @param source the source of the chapter.
     */
    fun findUnmatchedChapterDirs(
        chapters: List<Chapter>,
        manga: Manga,
        source: Source
    ): List<UniFile> {
        val mangaDir = findMangaDir(manga, source) ?: return emptyList()
        val idHashSet = chapters.map { it.mangadex_chapter_id }.toHashSet()
        val chapterNameHashSet = chapters.map { (it as Chapter).name }.toHashSet()

        return mangaDir.listFiles()!!.asList().filter { file ->
            file.name?.let { fileName ->
                if (fileName.endsWith(Downloader.TMP_DIR_SUFFIX)) {
                    return@filter true
                }
                val mangadexId = fileName.substringAfterLast(" - ", "")
                if (mangadexId.isNotEmpty() && mangadexId.isDigitsOnly()) {
                    return@filter !idHashSet.contains(mangadexId)
                } else {
                    //dont need to check for j2k specific with scanlator because its format is contains file.name
                    return@filter !chapterNameHashSet.contains(file.name!!)
                }
            }
            //everything else is considered true
            return@filter true
        }
    }

    /**
     * Returns a list of downloaded directories for the chapters that exist.
     *
     * @param chapters the chapters to query.
     * @param manga the manga of the chapter.
     * @param source the source of the chapter.
     */
    fun findTempChapterDirs(chapters: List<Chapter>, manga: Manga, source: Source): List<UniFile> {
        val mangaDir = findMangaDir(manga, source) ?: return emptyList()
        return chapters.mapNotNull { mangaDir.findFile("${getChapterDirName(it)}_tmp") }
    }

    /**
     * Returns the download directory name for a source always english to not break with other forks or current neko
     *
     * @param source the source to query.
     */
    fun getSourceDirName(source: Source): String {
        return "$source (EN)"
    }

    /**
     * Returns the download directory name for a manga.
     *
     * @param manga the manga to query.
     */
    fun getMangaDirName(manga: Manga): String {
        return DiskUtil.buildValidFilename(manga.originalTitle)
    }

    /**
     * Returns the chapter directory name for a chapter.
     *
     * @param chapter the chapter to query.
     */
    fun getChapterDirName(chapter: Chapter): String {
        if (chapter.isMergedChapter()) {
            return getJ2kChapterName(chapter)
        } else {
            val chapterId = if (chapter.mangadex_chapter_id.isNotBlank()) chapter.mangadex_chapter_id else MdUtil.getChapterId(chapter.url)
            return DiskUtil.buildValidFilename(chapter.name + " - " + chapterId)
        }
    }

    fun getJ2kChapterName(chapter: Chapter): String {
        return DiskUtil.buildValidFilename(
            if (chapter.scanlator != null) "${chapter.scanlator}_${chapter.name}"
            else chapter.name
        )
    }

    /**
     * Returns valid downloaded chapter directory names.
     *
     * @param chapter the chapter to query.
     */
    fun getValidChapterDirNames(chapter: Chapter): List<String> {
        return listOf(
            getChapterDirName(chapter),
            // chater names from j2k
            getJ2kChapterName(chapter),
            // Legacy chapter directory name used in v0.8.4 and before
            DiskUtil.buildValidFilename(chapter.name)
        )
    }
}
