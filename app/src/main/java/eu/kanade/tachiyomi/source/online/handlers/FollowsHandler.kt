package eu.kanade.tachiyomi.source.online.handlers

import com.elvishew.xlog.XLog
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.handlers.serializers.FollowsPageResult
import eu.kanade.tachiyomi.source.online.handlers.serializers.Result
import eu.kanade.tachiyomi.source.online.utils.FollowStatus
import eu.kanade.tachiyomi.source.online.utils.MdUtil
import eu.kanade.tachiyomi.source.online.utils.MdUtil.Companion.baseUrl
import eu.kanade.tachiyomi.source.online.utils.MdUtil.Companion.getMangaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import kotlin.math.floor

class FollowsHandler(val client: OkHttpClient, val headers: Headers, val preferences: PreferencesHelper) {

    /**
     * fetch follows by page
     */
    fun fetchFollows(): Observable<MangasPage> {
        return client.newCall(followsListRequest())
            .asObservable()
            .map { response ->
                followsParseMangaPage(response)
            }
    }

    /**
     * Parse follows api to manga page
     * used when multiple follows
     */
    private fun followsParseMangaPage(response: Response, forceHd: Boolean = false): MangasPage {

        var followsPageResult: FollowsPageResult? = null

        try {
            followsPageResult = MdUtil.jsonParser.decodeFromString(FollowsPageResult.serializer(), response.body!!.string())
        } catch (e: Exception) {
            XLog.e("error parsing follows", e)
        }
        val empty = followsPageResult?.result?.isEmpty()

        if (empty == null || empty) {
            return MangasPage(mutableListOf(), false)
        }
        val lowQualityCovers = if (forceHd) false else preferences.lowQualityCovers()

        val follows = followsPageResult!!.result.map {
            followFromElement(it, lowQualityCovers)
        }

        return MangasPage(follows, false)
    }

    /**fetch follow status used when fetching status for 1 manga
     *
     */

    private fun followStatusParse(response: Response): Track {
        var followsPageResult: FollowsPageResult? = null

        try {
            followsPageResult = MdUtil.jsonParser.decodeFromString(FollowsPageResult.serializer(), response.body!!.string())
        } catch (e: Exception) {
            XLog.e("error parsing follows", e)
        }
        val track = Track.create(TrackManager.MDLIST)
        val result = followsPageResult?.result
        if (result.isNullOrEmpty()) {
            track.status = FollowStatus.UNFOLLOWED.int
        } else {
            val follow = result.first()
            track.status = follow.follow_type
            if (result[0].chapter.isNotBlank()) {
                track.last_chapter_read = floor(follow.chapter.toFloat()).toInt()
            }
            track.tracking_url = MdUtil.baseUrl + follow.manga_id.toString()
            track.title = follow.title
        }
        return track
    }

    /**build Request for follows page
     *
     */
    private fun followsListRequest(): Request {
        return GET("${MdUtil.baseUrl}${MdUtil.followsAllApi}", headers, CacheControl.FORCE_NETWORK)
    }

    /**
     * Parse result element  to manga
     */
    private fun followFromElement(result: Result, lowQualityCovers: Boolean): SManga {
        val manga = SManga.create()
        manga.title = MdUtil.cleanString(result.title)
        manga.url = "/manga/${result.manga_id}/"
        manga.follow_status = FollowStatus.fromInt(result.follow_type)
        manga.thumbnail_url = MdUtil.formThumbUrl(manga.url, lowQualityCovers)
        return manga
    }

    /**
     * Change the status of a manga
     */
    suspend fun updateFollowStatus(mangaID: String, followStatus: FollowStatus): Boolean {
        return withContext(Dispatchers.IO) {

            val response: Response =
                if (followStatus == FollowStatus.UNFOLLOWED) {
                    client.newCall(
                        GET(
                            "$baseUrl/ajax/actions.ajax.php?function=manga_unfollow&id=$mangaID&type=$mangaID",
                            headers,
                            CacheControl.FORCE_NETWORK
                        )
                    )
                        .execute()
                } else {

                    val status = followStatus.int
                    client.newCall(
                        GET(
                            "$baseUrl/ajax/actions.ajax.php?function=manga_follow&id=$mangaID&type=$status",
                            headers,
                            CacheControl.FORCE_NETWORK
                        )
                    )
                        .execute()
                }

            response.body!!.string().isEmpty()
        }
    }

    suspend fun updateReadingProgress(track: Track): Boolean {
        return withContext(Dispatchers.IO) {
            val mangaID = getMangaId(track.tracking_url)
            val formBody = FormBody.Builder()
                .add("chapter", track.last_chapter_read.toString())
            XLog.d("chapter to update %s", track.last_chapter_read.toString())
            val response = client.newCall(
                POST(
                    "$baseUrl/ajax/actions.ajax.php?function=edit_progress&id=$mangaID",
                    headers,
                    formBody.build()
                )
            ).execute()

            val response2 = client.newCall(
                GET(
                    "$baseUrl/ajax/actions.ajax.php?function=manga_rating&id=$mangaID&rating=${track.score.toInt()}",
                    headers
                )
            )
                .execute()

            response.body!!.string().isEmpty()
        }
    }

    suspend fun updateRating(track: Track): Boolean {
        return withContext(Dispatchers.IO) {
            val mangaID = getMangaId(track.tracking_url)
            val response = client.newCall(
                GET(
                    "$baseUrl/ajax/actions.ajax.php?function=manga_rating&id=$mangaID&rating=${track.score.toInt()}",
                    headers
                )
            )
                .execute()

            response.body!!.string().isEmpty()
        }
    }

    /**
     * fetch all manga from all possible pages
     */
    suspend fun fetchAllFollows(forceHd: Boolean): List<SManga> {
        return withContext(Dispatchers.IO) {
            val listManga = mutableListOf<SManga>()
            val response = client.newCall(followsListRequest()).execute()
            val mangasPage = followsParseMangaPage(response, forceHd)
            listManga.addAll(mangasPage.mangas)
            listManga
        }
    }

    suspend fun fetchTrackingInfo(url: String): Track {
        return withContext(Dispatchers.IO) {
            val request = GET(
                "${MdUtil.baseUrl}${MdUtil.followsMangaApi}" + getMangaId(url),
                headers,
                CacheControl.FORCE_NETWORK
            )
            val response = client.newCall(request).execute()
            val track = followStatusParse(response)

            track
        }
    }
}
