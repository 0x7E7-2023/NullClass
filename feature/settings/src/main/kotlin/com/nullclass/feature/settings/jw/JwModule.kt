package com.nullclass.feature.settings.jw

import android.content.Context
import android.content.pm.ApplicationInfo
import com.nullclass.importer.jw.JwAdapterRepository
import com.nullclass.importer.jw.JwBuiltinLibrary
import com.nullclass.importer.jw.JwLibraryClient
import com.nullclass.importer.jw.JwOfficialLibrary
import com.nullclass.importer.jw.JwOfficialStore
import com.nullclass.importer.jw.JwOfficialUpdater
import com.nullclass.importer.jw.JwRemoteFetcher
import com.nullclass.importer.jw.JwUserAdapterStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object JwModule {

    /** debug 包允许 http 的库地址（本地调试用）；发布包只允许 https。 */
    private fun allowInsecure(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    @Provides
    @Singleton
    fun provideUserAdapterStore(@ApplicationContext context: Context): JwUserAdapterStore =
        JwUserAdapterStore(
            root = File(context.filesDir, JwUserAdapterStore.DIR_NAME),
            appVersionCode = JwInstallSupport.appVersionCode(context),
        )

    @Provides
    @Singleton
    fun provideOfficialStore(@ApplicationContext context: Context): JwOfficialStore =
        JwOfficialStore(File(context.filesDir, JwOfficialStore.DIR_NAME))

    /** 官方库取「随包内置」与「热更新下载」中版本更新的那份（APK 升级后内置可能反超下载的）。 */
    @Provides
    @Singleton
    fun provideAdapterRepository(
        store: JwUserAdapterStore,
        officialStore: JwOfficialStore,
        @ApplicationContext context: Context,
    ): JwAdapterRepository {
        val appVersionCode = JwInstallSupport.appVersionCode(context)
        val bundled = JwBuiltinLibrary.loadLibrary(appVersionCode)
        // ponytail: 首次注入时同步验签+解包（约几十 ms，与内置库加载同量级）；卡顿明显再挪到 Application 异步套用
        val downloaded = officialStore.load(appVersionCode)
        val builtin = if (downloaded != null && JwOfficialLibrary.compareVersions(downloaded.version, bundled.version) > 0) {
            downloaded
        } else {
            bundled
        }
        return JwAdapterRepository(builtin = builtin, store = store)
    }

    @Provides
    @Singleton
    fun provideOfficialUpdater(
        fetcher: JwRemoteFetcher,
        @ApplicationContext context: Context,
    ): JwOfficialUpdater = JwOfficialUpdater(fetcher, JwInstallSupport.appVersionCode(context))

    @Provides
    @Singleton
    fun provideRemoteFetcher(@ApplicationContext context: Context): JwRemoteFetcher =
        OkHttpJwRemoteFetcher(allowInsecure = allowInsecure(context))

    @Provides
    @Singleton
    fun provideLibraryClient(
        fetcher: JwRemoteFetcher,
        @ApplicationContext context: Context,
    ): JwLibraryClient = JwLibraryClient(
        fetcher = fetcher,
        appVersionCode = JwInstallSupport.appVersionCode(context),
        allowInsecure = allowInsecure(context),
    )
}
