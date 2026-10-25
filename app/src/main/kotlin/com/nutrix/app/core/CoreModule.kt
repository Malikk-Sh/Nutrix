package com.nutrix.app.core

import com.nutrix.app.core.security.KeystoreSecretCipher
import com.nutrix.app.core.security.SecretCipher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dagger.Provides

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreBindings {
    @Binds
    @Singleton
    abstract fun secretCipher(impl: KeystoreSecretCipher): SecretCipher
}

@Module
@InstallIn(SingletonComponent::class)
object CoreProviders {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
