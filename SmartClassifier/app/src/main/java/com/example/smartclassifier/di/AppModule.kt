package com.example.smartclassifier.di

import com.example.smartclassifier.data.repository.FaceRepositoryImpl
import com.example.smartclassifier.data.repository.ImageClassifierRepositoryImpl
import com.example.smartclassifier.data.repository.StickerRepositoryImpl
import com.example.smartclassifier.domain.repository.FaceRepository
import com.example.smartclassifier.domain.repository.ImageClassifierRepository
import com.example.smartclassifier.domain.repository.StickerRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindImageClassifierRepository(
        impl: ImageClassifierRepositoryImpl
    ): ImageClassifierRepository

    @Binds
    @Singleton
    abstract fun bindFaceRepository(
        impl: FaceRepositoryImpl
    ): FaceRepository

    @Binds
    @Singleton
    abstract fun bindStickerRepository(
        impl: StickerRepositoryImpl
    ): StickerRepository
}
