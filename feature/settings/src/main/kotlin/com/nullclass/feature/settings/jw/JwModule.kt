package com.nullclass.feature.settings.jw

import android.content.Context
import android.content.pm.ApplicationInfo
import com.nullclass.importer.jw.JwAdapterRepository
import com.nullclass.importer.jw.JwBuiltinLibrary
import com.nullclass.importer.jw.JwLibraryClient
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
    fun provideAdapterRepository(
        store: JwUserAdapterStore,
        @ApplicationContext context: Context,
    ): JwAdapterRepository = JwAdapterRepository(
        builtin = JwBuiltinLibrary.load(JwInstallSupport.appVersionCode(context)),
        store = store,
    )

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
