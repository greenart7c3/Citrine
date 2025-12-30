package com.greenart7c3.citrine.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.greenart7c3.citrine.Citrine
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityBroadcastReceiverTest {
    @MockK
    private lateinit var mockContext: Context

    @MockK
    private lateinit var mockConnectivityManager: ConnectivityManager

    @RelaxedMockK
    private lateinit var mockCitrineInstance: Citrine

    @RelaxedMockK
    private lateinit var mockNostrClient: NostrClient

    @RelaxedMockK
    private lateinit var mockApplicationScope: CoroutineScope

    private lateinit var receiver: ConnectivityBroadcastReceiver
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        receiver = ConnectivityBroadcastReceiver()

        // Mock Citrine singleton
        mockkObject(Citrine)
        every { Citrine.instance } returns mockCitrineInstance

        // Mock Citrine instance properties
        every { mockCitrineInstance.client } returns mockNostrClient
        every { mockCitrineInstance.applicationScope } returns CoroutineScope(testDispatcher + SupervisorJob())

        // Mock context and connectivity manager
        every { mockContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns mockConnectivityManager

        // Mock Log
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `onReceive should ignore non-connectivity intents`() {
        // Given
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        // When
        receiver.onReceive(mockContext, intent)

        // Then
        verify(exactly = 0) { mockCitrineInstance.client }
    }

    @Test
    fun `onReceive should return early if connectivity manager is null`() {
        // Given
        val intent = Intent(ConnectivityManager.CONNECTIVITY_ACTION)
        every { mockContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns null

        // When
        receiver.onReceive(mockContext, intent)

        // Then
        verify(exactly = 0) { mockCitrineInstance.client }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun `onReceive should reconnect client when connected on Android M+`() = runTest {
        // Given
        val intent = Intent(ConnectivityManager.CONNECTIVITY_ACTION)
        val mockNetwork = mockk<Network>()
        val mockCapabilities = mockk<NetworkCapabilities>()

        every { mockConnectivityManager.activeNetwork } returns mockNetwork
        every { mockConnectivityManager.getNetworkCapabilities(mockNetwork) } returns mockCapabilities
        every { mockCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        every { mockNostrClient.isActive() } returns false
        coEvery { mockNostrClient.connect() } just runs

        // When
        receiver.onReceive(mockContext, intent)
        advanceUntilIdle()

        // Then
        coVerify { mockNostrClient.connect() }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun `onReceive should not reconnect if client is already active on Android M+`() = runTest {
        // Given
        val intent = Intent(ConnectivityManager.CONNECTIVITY_ACTION)
        val mockNetwork = mockk<Network>()
        val mockCapabilities = mockk<NetworkCapabilities>()

        every { mockConnectivityManager.activeNetwork } returns mockNetwork
        every { mockConnectivityManager.getNetworkCapabilities(mockNetwork) } returns mockCapabilities
        every { mockCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        every { mockNostrClient.isActive() } returns true

        // When
        receiver.onReceive(mockContext, intent)
        advanceUntilIdle()

        // Then
        coVerify(exactly = 0) { mockNostrClient.connect() }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun `onReceive should handle cellular transport on Android M+`() = runTest {
        // Given
        val intent = Intent(ConnectivityManager.CONNECTIVITY_ACTION)
        val mockNetwork = mockk<Network>()
        val mockCapabilities = mockk<NetworkCapabilities>()

        every { mockConnectivityManager.activeNetwork } returns mockNetwork
        every { mockConnectivityManager.getNetworkCapabilities(mockNetwork) } returns mockCapabilities
        every { mockCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
        every { mockCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns true
        every { mockNostrClient.isActive() } returns false
        coEvery { mockNostrClient.connect() } just runs

        // When
        receiver.onReceive(mockContext, intent)
        advanceUntilIdle()

        // Then
        coVerify { mockNostrClient.connect() }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun `onReceive should handle ethernet transport on Android M+`() = runTest {
        // Given
        val intent = Intent(ConnectivityManager.CONNECTIVITY_ACTION)
        val mockNetwork = mockk<Network>()
        val mockCapabilities = mockk<NetworkCapabilities>()

        every { mockConnectivityManager.activeNetwork } returns mockNetwork
        every { mockConnectivityManager.getNetworkCapabilities(mockNetwork) } returns mockCapabilities
        every { mockCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
        every { mockCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns false
        every { mockCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } returns true
        every { mockNostrClient.isActive() } returns false
        coEvery { mockNostrClient.connect() } just runs

        // When
        receiver.onReceive(mockContext, intent)
        advanceUntilIdle()

        // Then
        coVerify { mockNostrClient.connect() }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun `onReceive should not reconnect when disconnected on Android M+`() = runTest {
        // Given
        val intent = Intent(ConnectivityManager.CONNECTIVITY_ACTION)
        val mockNetwork = mockk<Network>()

        every { mockConnectivityManager.activeNetwork } returns mockNetwork
        every { mockConnectivityManager.getNetworkCapabilities(mockNetwork) } returns null

        // When
        receiver.onReceive(mockContext, intent)
        advanceUntilIdle()

        // Then
        coVerify(exactly = 0) { mockNostrClient.connect() }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.LOLLIPOP])
    fun `onReceive should reconnect client when connected on Android Lollipop`() = runTest {
        // Given
        val intent = Intent(ConnectivityManager.CONNECTIVITY_ACTION)
        val mockNetworkInfo = mockk<android.net.NetworkInfo>()

        @Suppress("DEPRECATION")
        every { mockConnectivityManager.activeNetworkInfo } returns mockNetworkInfo
        @Suppress("DEPRECATION")
        every { mockNetworkInfo.isConnected } returns true
        every { mockNostrClient.isActive() } returns false
        coEvery { mockNostrClient.connect() } just runs

        // When
        receiver.onReceive(mockContext, intent)
        advanceUntilIdle()

        // Then
        coVerify { mockNostrClient.connect() }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.LOLLIPOP])
    fun `onReceive should not reconnect when disconnected on Android Lollipop`() = runTest {
        // Given
        val intent = Intent(ConnectivityManager.CONNECTIVITY_ACTION)
        val mockNetworkInfo = mockk<android.net.NetworkInfo>()

        @Suppress("DEPRECATION")
        every { mockConnectivityManager.activeNetworkInfo } returns mockNetworkInfo
        @Suppress("DEPRECATION")
        every { mockNetworkInfo.isConnected } returns false

        // When
        receiver.onReceive(mockContext, intent)
        advanceUntilIdle()

        // Then
        coVerify(exactly = 0) { mockNostrClient.connect() }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun `onReceive should handle connection errors gracefully`() = runTest {
        // Given
        val intent = Intent(ConnectivityManager.CONNECTIVITY_ACTION)
        val mockNetwork = mockk<Network>()
        val mockCapabilities = mockk<NetworkCapabilities>()

        every { mockConnectivityManager.activeNetwork } returns mockNetwork
        every { mockConnectivityManager.getNetworkCapabilities(mockNetwork) } returns mockCapabilities
        every { mockCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        every { mockNostrClient.isActive() } returns false
        coEvery { mockNostrClient.connect() } throws RuntimeException("Connection failed")

        // When
        receiver.onReceive(mockContext, intent)
        advanceUntilIdle()

        // Then
        coVerify { mockNostrClient.connect() }
        // Should not crash, error should be logged
    }
}
