package com.greenart7c3.citrine.service

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.greenart7c3.citrine.okhttp.HttpClientManager
import com.greenart7c3.citrine.server.Settings

/**
 * Coil image loader whose network fetches route through the app's OkHttp client so nsite
 * icon downloads honor the relay's Tor/proxy fail-closed policy instead of going direct.
 */
class ImageLoaderFactory : SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(
                OkHttpNetworkFetcherFactory(
                    callFactory = { HttpClientManager.getHttpClient(Settings.proxyAllUrls) },
                ),
            )
        }
        .build()
}
