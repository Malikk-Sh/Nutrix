package com.nutrix.app.integration.samsung

import android.app.Activity
import android.content.Context
import com.nutrix.app.integration.ExternalDaily
import com.nutrix.app.integration.ExternalExercise
import com.nutrix.app.integration.ExternalSleep
import com.nutrix.app.integration.ExternalWeight
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Used when the Samsung Health Data SDK AAR is not in app/libs. It never returns data and
 * reports SDK_NOT_BUNDLED so the UI can explain how to enable the direct integration.
 */
@Singleton
class UnbundledSamsungHealthGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : SamsungHealthGateway {
    override val sdkBundled: Boolean = false
    override fun isSamsungHealthInstalled(): Boolean = SamsungHealthPackage.isInstalled(context)
    override fun samsungHealthVersion(): String? = SamsungHealthPackage.versionName(context)
    override suspend fun status(): SamsungStatus = SamsungStatus.SDK_NOT_BUNDLED
    override suspend fun grantedCategories(): Set<SamsungDataCategory> = emptySet()
    override suspend fun requestPermissions(activity: Activity): Set<SamsungDataCategory> =
        throw SamsungHealthException(SamsungStatus.SDK_NOT_BUNDLED, null)
    override fun resolve(activity: Activity): Boolean = false
    override suspend fun readWeights(from: LocalDate, to: LocalDate): List<ExternalWeight> = emptyList()
    override suspend fun readSleep(from: LocalDate, to: LocalDate): List<ExternalSleep> = emptyList()
    override suspend fun readExercises(from: LocalDate, to: LocalDate): List<ExternalExercise> = emptyList()
    override suspend fun readDaily(from: LocalDate, to: LocalDate): List<ExternalDaily> = emptyList()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SamsungHealthModule {
    @Binds
    abstract fun gateway(impl: UnbundledSamsungHealthGateway): SamsungHealthGateway
}
