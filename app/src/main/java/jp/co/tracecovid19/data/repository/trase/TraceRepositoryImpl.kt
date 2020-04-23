package jp.co.tracecovid19.data.repository.trase

import android.app.Activity
import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.squareup.moshi.Moshi
import io.reactivex.Single
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import jp.co.tracecovid19.data.api.trace.TraceApiService
import jp.co.tracecovid19.data.database.TraceCovid19Database
import jp.co.tracecovid19.data.database.deepcontactuser.DeepContactUserEntity
import jp.co.tracecovid19.data.database.tempuserid.TempUserIdEntity
import jp.co.tracecovid19.data.database.tracedata.TraceDataEntity
import jp.co.tracecovid19.data.exception.TraceCovid19Exception
import jp.co.tracecovid19.data.exception.TraceCovid19Exception.Reason.*
import jp.co.tracecovid19.data.model.PositivePerson
import jp.co.tracecovid19.data.model.PositivePersons
import jp.co.tracecovid19.data.model.TempUserId
import jp.co.tracecovid19.data.storage.FirebaseStorageService
import jp.co.tracecovid19.data.storage.FirebaseStorageService.FileNameKey.PositivePersonList
import jp.co.tracecovid19.data.storage.LocalCacheService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.GZIPInputStream


class TraceRepositoryImpl (private val moshi: Moshi,
                           private val api: TraceApiService,
                           private val auth: FirebaseAuth,
                           private val localCacheService: LocalCacheService,
                           private val firebaseStorageService: FirebaseStorageService,
                           private val db: TraceCovid19Database): TraceRepository {

    override fun fetchPositivePersons(activity: Activity): Single<List<PositivePerson>> {
        return Single.create { result ->
            // データを取得
            firebaseStorageService.loadDataIfNeeded(PositivePersonList, localCacheService.positivePersonListGeneration?:"0", activity)
                .subscribeOn(Schedulers.io())
                .subscribeBy (
                    onSuccess = { data ->
                        // データの取得に成功
                        data.data?.let { listData ->
                            // 取得データあり = 更新する
                            val dataStr = GZIPInputStream(listData.inputStream()).bufferedReader(UTF_8).use { it.readText() }
                            try {
                                // パースする
                                moshi.adapter(PositivePersons::class.java).fromJson(dataStr)?.let { parseResult ->
                                    // パース成功
                                    localCacheService.positivePersonList = parseResult.data
                                    localCacheService.positivePersonListGeneration = data.generation
                                    result.onSuccess(parseResult.data)
                                }?: result.onError( TraceCovid19Exception(Parse)) // データなしもパース失敗扱いとする
                            } catch (e: Throwable) {
                                // パース失敗
                                result.onError(e)
                            }
                        }?: result.onSuccess(localCacheService.positivePersonList) // 取得データなし = キャッシュのやつを使用する
                    },
                    onError = { e ->
                        result.onError(e)
                    }
            )
        }
    }

    override fun updateTempIds(): Single<Boolean> {
        return Single.create { result ->
            auth.currentUser?.getIdToken(true)?.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    task.result?.token?.let { token ->
                        api.fetchTempIds("Bearer $token")
                            .subscribeOn(Schedulers.io())
                            .subscribeBy (
                                onSuccess = { data ->
                                    runBlocking (Dispatchers.IO) {
                                        saveTempIds(data)
                                    }
                                    result.onSuccess(true)
                                },
                                onError = { e ->
                                    result.onError(e)
                                }
                            )
                    }?: result.onError(TraceCovid19Exception(Auth))
                } else {
                    result.onError(TraceCovid19Exception(Auth))
                }
            }
        }
    }

    override suspend fun loadTempIds(): List<TempUserId> = db.tempUserIdDao().selectAll().map { TempUserId.create(it) }
    override suspend fun availableTempUserIdCount(currentTime: Long): Int = db.tempUserIdDao().availableTempUserIdCount(currentTime)
    override suspend fun getTempUserId(currentTime: Long): List<TempUserIdEntity> = db.tempUserIdDao().getTempUserId(currentTime)
    override suspend fun getLatestTempUserId(): TempUserIdEntity = db.tempUserIdDao().getLatestTempUserId()

    override suspend fun insertTraceData(entity: TraceDataEntity) = db.traceDataDao().insert(entity)
    override fun selectAllTraceData(): LiveData<List<TraceDataEntity>> = db.traceDataDao().selectAll()
    override suspend fun selectTraceTempIdByTempIdGroup(): List<String> = db.traceDataDao().selectTempIdByTempIdGroup()
    override suspend fun selectTraceData(tempId: String): List<TraceDataEntity> = db.traceDataDao().select(tempId)

    override fun selectAllDeepContacttUsers(): LiveData<List<DeepContactUserEntity>> = db.deepContactUserDao().selectAll()
    override suspend fun selectDeepContactUsers(ids: List<String>): List<DeepContactUserEntity> = db.deepContactUserDao().select(ids)

    override suspend fun insertDeepContactUsers(entities: List<DeepContactUserEntity>, tempId: String) {
        db.withTransaction {
            entities.forEach { entity ->
                db.deepContactUserDao().insert(entity)
            }
            db.traceDataDao().delete(tempId)
        }
    }

    private suspend fun saveTempIds(ids: List<TempUserId>): Boolean {
        db.tempUserIdDao().insert(ids.map { TempUserIdEntity(it.tempId, it.fromTime, it.toTime) })
        return true
    }

    override suspend fun deleteAllData() {
        // TODO 全部のデータを消す
        localCacheService.positivePersonListGeneration = null
        localCacheService.positivePersonList = listOf()

        db.tempUserIdDao().deleteAll()
        db.deepContactUserDao().deleteAll()
        db.traceDataDao().deleteAll()
    }
}